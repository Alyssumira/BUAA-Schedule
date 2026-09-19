package com.buaa.schedule.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit

/**
 * 「上课自动勿扰」的进入与恢复。
 *
 * 关键点：**进入前先记住用户当时的 `interruptionFilter`，退出时恢复它**。
 * 此前下课时无条件写成 `INTERRUPTION_FILTER_ALL`，等于替用户关掉了他自己开的
 * 「仅优先」/「完全静默」；而如果用户在上课前本来就是全响铃，写 ALL 又是多余的。
 * 同时"没进过勿扰就不要动用户设置"这一点也很重要 —— 只在真正进入过之后才恢复。
 *
 * 未开启开关、未授予勿扰访问权限（特殊访问权限）时，所有动作都是 no-op（降级安全）。
 *
 * **恢复不能只靠下课铃广播**：整机静音是用户最能感知到的一种故障，而下课铃
 * 恰恰是国产 ROM（澎湃 / MIUI）最爱清的一类闹钟 —— 清了、后台又被杀，手机就
 * 永久静音，只能等下一节课的上课铃或用户自己发现。所以 [enter] 同时落一个
 * 「恢复期限」并排一只看门狗闹钟（[ClassProgressReceiver.ACTION_DND_WATCHDOG]），到期后由
 * [selfCheck] 强制恢复；冷启动路径同样调 [selfCheck]，覆盖"期限之前整个进程都被杀掉"
 * 导致看门狗闹钟也被清掉的更坏情况。
 */
object ClassProgressDnd {

    private const val TAG = "ClassProgressDnd"

    /** 进入勿扰前用户原本的 interruptionFilter */
    private const val KEY_SAVED_FILTER = "dnd_saved_interruption_filter"

    /** 最迟必须在何时恢复勿扰（= 下课时刻 + [RESTORE_GRACE_MINUTES]），毫秒时间戳 */
    private const val KEY_DND_DEADLINE = "dnd_restore_deadline"

    /** 下课后允许广播迟到的余量：正常情况下课铃先到，看门狗只在它被吞掉时才响 */
    private const val RESTORE_GRACE_MINUTES = 30L

    /**
     * 拿不到下课时刻时的兜底上限（一节课最长不过大半天，4 小时足够覆盖任何节次段）。
     * 没有期限就没有看门狗，这条链对缺数据的课等于没装，所以宁可给一个宽上限。
     */
    private const val FALLBACK_LIMIT_MINUTES = 240L

    /**
     * 看门狗闹钟的 requestCode：与上/下课铃同一段（30_260_03x）但必须独立 ——
     * PendingIntent 判等只看 (requestCode, filterEquals)，共用会把下课铃整个顶掉。
     */
    private const val REQUEST_DND_WATCHDOG = 30_260_034

    private fun prefs(context: Context) =
        context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 上课铃：开启开关且已授予权限时切到完全静默，并记录原状态。
     *
     * [endMillis] 是这一节课的下课时刻（来自 [ClassProgressReceiver] 的窗口）。
     * 它只用于算恢复期限，<=0（节次表缺下课时间）时退到 [FALLBACK_LIMIT_MINUTES]。
     */
    fun enter(context: Context, endMillis: Long = 0L) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(ClassProgressReceiver.PREF_DND, false)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        val deadline = deadlineOf(endMillis)
        val entered = runCatching {
            prefs.edit {
                // 只在尚未记录时保存，避免连续两节课把"第二节课时已是 NONE"存成原状态
                if (!prefs.contains(KEY_SAVED_FILTER)) {
                    putInt(KEY_SAVED_FILTER, manager.currentInterruptionFilter)
                }
                // 期限每次都往后推：连续两节课共用同一条原状态记录，恢复点却是最后一节的下课
                putLong(KEY_DND_DEADLINE, deadline)
            }
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            true
        }.getOrDefault(false)
        // 静音没做成就不要留下一个"迟早要响"的看门狗，白白唤醒一次设备
        if (entered) armWatchdog(context, deadline)
    }

    /**
     * 下课铃 / 取消路径 / 看门狗：恢复用户原本的勿扰状态。
     *
     * **判断依据是「有没有记录过原状态」，而不是「开关现在是否还开着」。**
     * 此前先判断 `PREF_DND`：用户在上课期间把开关关掉，这里就直接 return，
     * 既不恢复 filter 也不清除记录 —— 整机永久停在「完全静默」，
     * 而且此后每次 restore 都会早退，永远不会自愈。
     */
    fun restore(context: Context) {
        val prefs = prefs(context)
        // 没记录过原状态（即本次没进过勿扰）时什么都不做
        if (!prefs.contains(KEY_SAVED_FILTER)) return
        val saved = prefs.getInt(KEY_SAVED_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
        // ⚠️ 先确认**这次真的能恢复**，再清记录。
        // 此前顺序是"先 remove 再判权限"：权限被用户撤掉时记录已被抹掉、filter 却没动，
        // 于是这条"本来要恢复成什么样"的信息永久丢失；等用户重新授予权限后再调 restore，
        // 会因为 contains()==false 而直接早退，手机再也无法自动退出勿扰。
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        val ok = runCatching {
            manager.setInterruptionFilter(saved)
            true
        }.getOrDefault(false)
        if (ok) {
            prefs.edit {
                remove(KEY_SAVED_FILTER)
                remove(KEY_DND_DEADLINE)
            }
            cancelWatchdog(context)
        }
        // 失败则保留记录，等下次恢复机会（下课铃 / 看门狗 / 冷启动 selfCheck）
    }

    /**
     * 硬超时自愈：**冷启动**（BUAAApplication）、**开机重建**（BootReceiver）与**看门狗闹钟**
     * 共用这一个入口。
     *
     * 只在「有残留记录」且「恢复期限已过」时动手，因此正在上课的那节课不会被误恢复。
     * 期限缺失（0）说明这条记录是加看门狗之前的旧版本留下的 —— 那种记录本来就没人会
     * 来恢复，按"已过期限"处理，正好让覆盖安装的第一次冷启动自愈掉。
     */
    fun selfCheck(context: Context) {
        runCatching {
            val prefs = prefs(context)
            val deadline = prefs.getLong(KEY_DND_DEADLINE, 0L)
            restoreIfStale(
                hasSavedFilter = prefs.contains(KEY_SAVED_FILTER),
                deadline = deadline,
                nowMillis = System.currentTimeMillis(),
            ) {
                Log.w(TAG, "勿扰已过恢复期限仍未收到下课铃（$deadline），按硬超时恢复")
                restore(context)
            }
        }.onFailure { Log.w(TAG, "勿扰自愈失败", it) }
    }

    /**
     * 「这一轮到底该不该按硬超时动手」判据的唯一出处 + 编排：
     * 「有记录」且「恢复期限已过（缺失/0 按已过处理）」时把动作交给 [onStale] 并返回 true，
     * 其余情况原样返回 false、什么都不做。
     *
     * 判据体从 [selfCheck] 里搬出来 —— 不接 Context、动作收成注入的 lambda
     * （同 [ReminderScheduler.takeDownClassProgressIfNeeded] 的手法：本模块单测没有
     * Robolectric，[restore] 要 Context，android.jar 里全是抛 "not mocked" 的桩，
     * JVM 侧钉得住的只有"恢复动作被调了几遍"）。开机那一步（BootReceiver）经 [selfCheck]
     * 共用这份判据，**不许**在调用点再长第二份「有记录 + 期限」的比较式。
     *
     * [hasSavedFilter] 即"是否记录过用户原状态"；[deadline] 是 `dnd_restore_deadline`；
     * [nowMillis] 是这一次判断唯一的时钟读数（调用方传入，判据自己不读钟）。
     */
    internal fun restoreIfStale(
        hasSavedFilter: Boolean,
        deadline: Long,
        nowMillis: Long,
        onStale: () -> Unit,
    ): Boolean {
        if (!hasSavedFilter) return false
        if (deadline > nowMillis) return false
        onStale()
        return true
    }

    /** 恢复期限 = 下课 + 宽限；下课时刻不可用时按兜底上限从此刻算 */
    private fun deadlineOf(endMillis: Long): Long =
        if (endMillis > 0L) {
            endMillis + RESTORE_GRACE_MINUTES * 60_000L
        } else {
            System.currentTimeMillis() + FALLBACK_LIMIT_MINUTES * 60_000L
        }

    /**
     * 排看门狗闹钟（Android 12+ 无精确闹钟权限时逐档降级）。
     *
     * 用 `setAlarmClock`（「用户闹钟」档）：它正是为"到点必须响"准备的档位，
     * 省电白名单对它的豁免最彻底 —— 而这一步的全部意义就是对抗省电策略。
     * 代价是状态栏会多一个闹钟图标，最迟 [RESTORE_GRACE_MINUTES] 分钟后就消失，
     * 比"手机静音一整天"轻得多。
     */
    private fun armWatchdog(context: Context, atMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = watchdogPendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        if (canExact) {
            runCatching {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(atMillis, ClassProgressScheduler.alarmShowIntent(context)),
                    operation,
                )
            }.recoverCatching {
                Log.w(TAG, "勿扰看门狗 setAlarmClock 失败，退回精确档", it)
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
            }.onFailure { Log.w(TAG, "勿扰看门狗排程失败，只剩冷启动自愈", it) }
        } else {
            // 未授予 SCHEDULE_EXACT_ALARM：精确档会抛 SecurityException，退非精确档
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
            }.onFailure { Log.w(TAG, "勿扰看门狗排程失败，只剩冷启动自愈", it) }
        }
    }

    /**
     * 撤销看门狗。restore 成功与 [ClassProgressScheduler.cancelAll] 都要调它，
     * 否则用户主动关掉功能后还会白醒一次。
     */
    fun cancelWatchdog(context: Context) {
        runCatching {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val operation = PendingIntent.getBroadcast(
                context,
                REQUEST_DND_WATCHDOG,
                watchdogIntent(context),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
            // 只 alarmManager.cancel 不够：PI 记录还被应用侧引用钉着，
            // 处理方式同 ClassProgressScheduler.cancel
            alarmManager.cancel(operation)
            operation.cancel()
        }.onFailure { Log.w(TAG, "撤销勿扰看门狗失败", it) }
    }

    private fun watchdogPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_DND_WATCHDOG,
            watchdogIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun watchdogIntent(context: Context): Intent =
        Intent(context, ClassProgressReceiver::class.java)
            .putExtra(ClassProgressReceiver.EXTRA_ACTION, ClassProgressReceiver.ACTION_DND_WATCHDOG)
}
