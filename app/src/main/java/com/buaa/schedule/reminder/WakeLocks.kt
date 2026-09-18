package com.buaa.schedule.reminder

import android.content.Context
import android.os.PowerManager

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
 */
object WakeLocks {

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
        return try {
            block()
        } finally {
            // 超时后系统已自动释放，此时 isHeld=false，不能再 release
            runCatching { if (lock.isHeld) lock.release() }
        }
    }
}
