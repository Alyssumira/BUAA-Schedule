package com.buaa.schedule.reminder

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * 短时 PARTIAL_WAKE_LOCK（SleepDown 同款做法，见 BUAA_ROM_ADAPTATION_2026-09-15）。
 *
 * `goAsync()` 只保证广播进程存活，**不保证 CPU 持续唤醒**：Doze 深度休眠下，
 * 系统派发广播自带的唤醒锁在 onReceive 返回后就可能松开，此后协程里的
 * DB 查询/全量重排会跑在随时可能再度入睡的 CPU 上，重排被打断后提醒链条断掉。
 * 包一个带超时的部分唤醒锁，把「闹钟触发后的重活」关进 CPU 醒着的时间里。
 *
 * 带超时是为了防御：即使 block 卡死或忘记释放，锁也会在超时后由系统收回，
 * 不会把整机拖成常亮耗电。
 *
 * **超时是兜底上限，不是持锁时长**，所以它跟省电没关系：block 一跑完 [withPartialWakeLock]
 * 就在 `finally` 里 release，真正决定待机电流的是 block 的实际耗时（审计 §4.3 对账的
 * 正是「每 tag 次数 × 平均持锁时长」）。把上限调小表面省锁，实际只是把「重活跑到半路
 * CPU 睡回去」这个本文件要防的故障请回来，而且一声不响 ——
 * `docs/AUDIT-BATTERY-2026-09-18.md` §2.5 数的问题就是它。上限调大只在病态路径（block 卡死）
 * 上多付几秒，因此默认值取重的那一头，理由见 [withPartialWakeLock] 里 timeoutMs 的注释。
 *
 * 每一次持锁都会经 [logHold] 留一行 logcat（tag `WakeLocks`），带上实际耗时与
 * 「退出时锁还在不在手上」——这类静默降级以前什么痕迹都不留，现在一行就够：
 * `adb logcat -s WakeLocks` 直接读，不必像 §4.3 那样先起基线再比电量。
 * 它也是下一轮回答「跑不完时那半套闹钟要不要管」的输入。
 */
object WakeLocks {

    /** 取证日志的 logcat tag（与唤醒锁名字里那个 tag 不是一回事：那个进 dumpsys，这个进 logcat） */
    private const val LOG_TAG = "WakeLocks"

    inline fun <T> withPartialWakeLock(
        context: Context,
        tag: String,
        // 默认 10 秒，与 BootReceiver.kt:41 / WidgetRefreshReceiver.kt:44 原本显式传的那个值同口径
        // （那两处早就写下判断「默认 5 秒不够，会被系统提前收回」，可审计 §2.5 发现真正最重的四处
        // —— notify + KeyStore 解密 + 全量重排、121 天 × 全课程搜索、三张表查询 + 全量窗口搜索 ——
        // 反而在吃 5 秒）。为什么改默认值而不是给这四处各传一份 10_000L：
        // 超时是卡死兜底、不是持锁时长，轻量调用点吃这个默认值一分钱不多花（跑完即 release），
        // 而显式传参等于把同一个魔数复制成六份，且第七处新调用点一忘就落回 §2.5 那个故障面；
        // 契约收在被调函数身上，调用点想忘也忘不掉（同 BackgroundSync.scheduleTomorrowPreview
        // 那次「入口挂起、罩不罩锁由调用点决定」的取舍）。真的更重的调用点照样可以自己传更大的值。
        timeoutMs: Long = 10_000L,
        block: () -> T,
    ): T {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val lock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buaa:schedule:$tag")
            ?: return block()
        lock.setReferenceCounted(false)
        runCatching { lock.acquire(timeoutMs) }
        // 掐表用 elapsedRealtime（开机以来的单调毫秒），不用 currentTimeMillis：后者是墙上时钟，
        // 用户改系统时间 / 时区跳变（「改时间」正是本锁的触发场景之一）会把耗时算成负数或几十亿，
        // 这条日志就从证据变成噪声。同一个仓库里 WidgetRefreshReceiver.kt:100 为同一件事站过台。
        val acquiredAt = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            // 先读 isHeld、再 release，这个顺序就是取证本身：本函数在 block 返回之前从不 release，
            // 所以此刻 isHeld == false 只有一个解释 —— 超时到点、锁已被系统收回，这一轮没跑赢超时
            // （acquire 自己失败也会落这一位，那时「不在手上」同样是真话）。
            // 这是精确信号，不拿「耗时 ≥ 上限」去比大小：后者在 block 提前返回、系统还没处理完
            // 超时的那格竞态里会说谎。
            // 只读一次并存在局部量里：日志判的与下面决定要不要 release 的必须是同一个采样。
            val stillHeld = lock.isHeld
            logHold(tag, SystemClock.elapsedRealtime() - acquiredAt, timeoutMs, stillHeld)
            // 超时后系统已自动释放，此时 isHeld=false，不能再 release
            runCatching { if (stillHeld) lock.release() }
        }
    }

    /**
     * 一次持锁的结果写进 logcat：正常一行 `Log.d`，没跑赢超时一行 `Log.w`。
     *
     * 刻意**不是 inline**：inline 的函数体逐调用点展开，全仓库六个调用点各烘一份消息常量，
     * 白付 dex 体积（release 包的字节数是有账的，见 `docs/RELEASE.md`「包体与 ABI」）；
     * 而这函数只在 block 返回后跑一次，展开没有任何收益。又因为 public inline 函数碰不到
     * private 成员（Kotlin 的 "Public-API inline function cannot access non-public-API"），
     * 只能 `@PublishedApi internal` —— 对模块外仍然不可见。
     *
     * 消息里的 `tag=` / `elapsed=` / `timeout=` 是给 `adb logcat -s WakeLocks` 之后 grep 用的。
     */
    @PublishedApi
    internal fun logHold(tag: String, elapsedMs: Long, timeoutMs: Long, stillHeld: Boolean) {
        if (stillHeld) {
            Log.d(LOG_TAG, "唤醒锁跑完 tag=$tag elapsed=${elapsedMs}ms timeout=${timeoutMs}ms held=true")
            return
        }
        // 要抓的就是这一条：block 还在跑（或刚好跑完）时锁已经不在了，之后那几步查库/排闹钟
        // 已经没有唤醒保证 —— §2.5 说的「静默降级」以前一条日志都不留，现在留了。
        Log.w(
            LOG_TAG,
            "唤醒锁没跑赢超时 tag=$tag elapsed=${elapsedMs}ms timeout=${timeoutMs}ms held=false：" +
                "block 期间锁已被系统收回，剩下的步骤不再有保障（要么加大这一处的超时，要么削减工作量）",
        )
    }
}
