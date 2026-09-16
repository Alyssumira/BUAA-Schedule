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
 */
object WakeLocks {

    inline fun <T> withPartialWakeLock(
        context: Context,
        tag: String,
        timeoutMs: Long = 5_000L,
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
