package com.buaa.schedule.reminder

import android.app.NotificationManager
import android.content.Context
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
 */
object ClassProgressDnd {

    /** 进入勿扰前用户原本的 interruptionFilter */
    private const val KEY_SAVED_FILTER = "dnd_saved_interruption_filter"

    private fun prefs(context: Context) =
        context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)

    /** 上课铃：开启开关且已授予权限时切到完全静默，并记录原状态 */
    fun enter(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(ClassProgressReceiver.PREF_DND, false)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        runCatching {
            // 只在尚未记录时保存，避免连续两节课把"第二节课时已是 NONE"存成原状态
            if (!prefs.contains(KEY_SAVED_FILTER)) {
                prefs.edit { putInt(KEY_SAVED_FILTER, manager.currentInterruptionFilter) }
            }
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        }
    }

    /**
     * 下课铃 / 取消路径：恢复用户原本的勿扰状态。
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
            prefs.edit { remove(KEY_SAVED_FILTER) }
        }
        // 失败则保留记录，等下次恢复机会（下次下课铃 / 手动重排）
    }
}
