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
 * 看门狗那三手 `set` 的留痕口：**级别只许 `Log.w` 以上** ——
 * 这台机器上的 HyperOS 把 logcat 截在 Info，`Log.d` 写下去等于没写，而这两档失败的后果是
 * **整机停在「完全静默」且没有闹钟来解除**（本文件类注释那一族的账）。
 *
 * 形状与默认值都照 `ClassProgressScheduler.kt` 里 `armEndBell` 侧那枚同族留痕口：
 * 收成参数而不是写死在函数体里，因为 `android.util.Log` 在本模块 JVM 单测里是抛 "not mocked"
 * 的桩，写死就先崩在报告口之前，那条"失败必须被看见"的表驱动断言会红得看不出原因。
 * 失败原因（哪一档、哪一手）由调用点给，tag 与级别只此一份。
 */
private val LOG_DND_ARM_FAILURE: (reason: String, error: Throwable) -> Unit = { reason, error ->
    Log.w("ClassProgressDnd", reason, error)
}

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
 *
 * **排程当场失败也要留得下痕迹与一格事实**：那三手 `set` 全抛（未授予精确闹钟权限那一档最容易走到）
 * 时，闹钟根本不存在，而 [selfCheck] 的自愈是**期限门控**的（见 [restoreIfStale]）——
 * 于是"只剩冷启动自愈"那句话当时是不成立的，静默没有任何上界。改法：[armWatchdog] 把结论落进
 * [KEY_WATCHDOG_REARM_PENDING]，[selfCheck] 起手读到它且期限还没到时**补排一次**
 * （补的是 prefs 里那一格期限，不重算宽限、不推后恢复点）。
 * 覆盖面照实写：这一格盖得住"排程当场失败"，盖不住"排成功之后被 ROM 吞掉"——
 * 那一刻 `set` 确实返回了，应用侧唯一的探测手段 `FLAG_NO_CREATE` 问的是 PendingIntent 记录在不在，
 * 而那只记录在 set 之前就已经被造出来了（见 [armWatchdog]），事后探不出闹钟。
 */
object ClassProgressDnd {

    private const val TAG = "ClassProgressDnd"

    /** 进入勿扰前用户原本的 interruptionFilter */
    private const val KEY_SAVED_FILTER = "dnd_saved_interruption_filter"

    /** 最迟必须在何时恢复勿扰（= 下课时刻 + [RESTORE_GRACE_MINUTES]），毫秒时间戳 */
    private const val KEY_DND_DEADLINE = "dnd_restore_deadline"

    /**
     * 「上一次排看门狗没排上」这一格事实（写了就是 true = 待补排；排上了就整格撤掉）。
     *
     * 为什么必须有一格 prefs、不能改成"起手探一眼闹钟还在不在"：[armWatchdog] 在两次 `set`
     * **之前**就先经 [watchdogPendingIntent] 用 `FLAG_UPDATE_CURRENT` 把那只 PendingIntent 造出来了，
     * 于是 `FLAG_NO_CREATE` 那一探（[cancelWatchdog] 里那一枚）问的是"PI 记录在不在"，
     * 不是"闹钟挂没挂"—— `set` 抛了 PI 照样在。全仓也没有任何一处周期性去探它。
     * ⇒ 这条链上唯一知道自己失败的地方就是失败当场那一步，它必须把结论落成事实，
     *   否则 [selfCheck] 面对的是一个"看起来一切正常"的状态。
     *
     * 与「恢复期限」那一格是**两本账**：期限答"最迟什么时候必须恢复"，这一格答"到那个点的闹钟在不在"。
     * 把它折进期限（失败就清期限）是不行的 —— 排程失败恰恰发生在课还在上的时候，
     * 期限清成 0 会让 [restoreIfStale] 在下一次起手当场动手 = 把正在生效的勿扰放开、
     * 约 5 秒后再被续排链重新 enter（那正是 `BootDndSelfHealDecisionTest`
     * 的 freshRecordWithinDeadlineIsNotRestoredAtBoot 用实测 logcat 钉住不许复发的抖动）。
     */
    private const val KEY_WATCHDOG_REARM_PENDING = "dnd_watchdog_rearm_pending"

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
                // 这一轮的书账到此为止：待补排那一格一起撤（闹钟已经不需要再排了）
                remove(KEY_WATCHDOG_REARM_PENDING)
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
     *
     * 起手先补那一格「上一次没排上」的闹钟（判据见 [watchdogRearmDue]）：排程失败时闹钟根本
     * 不存在，光靠期限门控就只剩"下一次起手且期限已过"那一档，补排把它换成"每一次起手都试一次"。
     * 整趟只读**一只钟**一次（`nowMillis` 在下面三处判据之间共用）：补排的期限门控与硬超时门控
     * 必须是同一个读数，跨秒那一瞬两判各说各的就会同时补错排、又漏掉恢复。
     */
    fun selfCheck(context: Context) {
        runCatching {
            val prefs = prefs(context)
            val deadline = prefs.getLong(KEY_DND_DEADLINE, 0L)
            val hasSavedFilter = prefs.contains(KEY_SAVED_FILTER)
            val nowMillis = System.currentTimeMillis()
            if (watchdogRearmDue(
                    hasSavedFilter = hasSavedFilter,
                    rearmPending = prefs.getBoolean(KEY_WATCHDOG_REARM_PENDING, false),
                    deadline = deadline,
                    nowMillis = nowMillis,
                )
            ) {
                // 取证行：念得出补的是哪一格期限，装机上"闹钟图标什么时候出现"只能靠它
                Log.w(TAG, "勿扰看门狗上一次没排上，按期限 $deadline 补排一次")
                // 补排吃的是 prefs 里那一格期限，不重算「现在 + 宽限」：
                // 重算就是替这一节课把恢复点往后推，而且宽限量会在第二处被再摊一遍。
                // 排上没有由 armWatchdog 自己再落一次旗 ⇒ 下一次起手接着试。
                armWatchdog(context, deadline)
            }
            restoreIfStale(
                hasSavedFilter = hasSavedFilter,
                deadline = deadline,
                nowMillis = nowMillis,
            ) {
                Log.w(TAG, "勿扰已过恢复期限仍未收到下课铃（$deadline），按硬超时恢复")
                restore(context)
            }
        }.onFailure { Log.w(TAG, "勿扰自愈失败", it) }
    }

    /**
     * 「这一趟自愈该不该补排那只看门狗」判据的唯一出处：**零 Context、零 android、零读钟**，
     * 三枚输入全是注入的标量。形状照 [restoreIfStale] —— 本模块单测没有 Robolectric，
     * `getSharedPreferences` 是抛 "not mocked" 的桩，[armWatchdog] 要 Context，
     * JVM 侧钉得住的只有"该不该补排"这一格结论（排程事件本身另有 [armWatchdogAttempt] 那枚纯档位）。
     *
     * 三判缺一不可，各自拦一种"看着对"：
     * - `hasSavedFilter`：没进过勿扰就没有要补的闹钟，一次 binder 都不许多付
     *   （同 [restore] 那条"没进过勿扰就不要动"的不变量）；
     * - `rearmPending`：旗不在就说明上一次确实排上了。正常上课不该每次起手都重排一遍 ——
     *   `setAlarmClock` 是同 requestCode 顶替重写，白多一次状态栏图标闪动，也多给 ROM 一次吞的机会；
     * - 期限未到：已过期限那一档归下面的 [restoreIfStale]（硬超时恢复），这里再补一只
     *   立刻就该撤销的闹钟是纯多付一次唤醒。
     *
     * 期限门控**复用** [restoreIfStale] 那一把尺（传一枚空动作、只取结论），不在这里再摊一份
     * 「deadline 与 nowMillis 谁大」：那格比较式全文件只许长在 seam 里一处，
     * 口径见 `BootDndSelfHealDecisionTest` 的 deadlinePredicateLivesInExactlyOnePlace。
     * [restoreIfStale] 不读钟、不摸 prefs，除 onStale 那一手之外没有副作用 ⇒ 空动作下它就是纯谓词。
     */
    internal fun watchdogRearmDue(
        hasSavedFilter: Boolean,
        rearmPending: Boolean,
        deadline: Long,
        nowMillis: Long,
    ): Boolean {
        if (!hasSavedFilter) return false
        if (!rearmPending) return false
        // 期限已过那一档归下面那一次 restoreIfStale（硬超时恢复）：这里再补一只立刻就该撤销的
        // 闹钟是纯多付一次唤醒。门控复用那一把尺（空动作、只取结论），不在这里摊第二份比较式。
        return !restoreIfStale(hasSavedFilter = hasSavedFilter, deadline = deadline, nowMillis = nowMillis, onStale = {})
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

    /**
     * 「时间类变化之后，那只勿扰看门狗到底该不该跟着新帧挪」的分档结论，一共六档
     * （判据本体见 [watchdogRearmDecision]）。
     *
     * 为什么不折成一枚 Boolean（本仓那一族的口径，同 `domain/widget/WidgetRolloverGate`）：
     * 五种"不动手"各有各的原因，而它们的**后果不同** —— `NoClassInSession` 是"这一格遗留归
     * [restore]，替下一节课把恢复点推后就是推迟一次该发生的恢复"，`AlreadyCurrent` 是"根本不用
     * 付 binder"。折成布尔就只剩"没动"，下一次读代码的人无从知道该动没动。
     */
    internal enum class WatchdogRearm {
        /** 没进过勿扰（没有原状态记录）：一条闹钟、一格 prefs 都不许动 */
        NoWatchdogToMove,

        /**
         * 新帧这一轮**没有"正在上的那一节"**（挑出来的是下一节 / 根本没有窗口）。
         *
         * 这一档是这一手最容易写坏的地方：[ClassProgressScheduler.rescheduleWindows] 选的是
         * "最早一个还没结束的窗口"，下课之后（或用户把钟拨过这一节之后）它就是**下一节**，
         * 而上一节课那一格记录可能还在（[restore] 因权限被撤而失败时刻意保留记录）。
         * 照下一节的下课时刻去重排 = 替一格已经没有主人的记录把恢复点推后一整节课。
         * 那笔账归 [restore] / [selfCheck]，不归这里。
         */
        NoClassInSession,

        /** 算不出新帧的下课时刻（节次表缺下课时间那一族窗口）：宁可按旧帧挂着，也不凭空造一条期限 */
        NoFreshDeadline,

        /**
         * 那格期限从来没写过（0）：旧版本留下的记录，[selfCheck] 本来就按"已过期限"处理它
         * （见那一份的说明）。这里不许替它造一条新帧期限 —— 那等于把一次已经到点的恢复推迟。
         */
        LegacyRecordWithoutDeadline,

        /** 期限本来就跟着新帧走过（改时间没挪动这一节 / 上课铃重放那一手已经重建过）：一次 binder 都不许多付 */
        AlreadyCurrent,

        /** 唯一该动手的那一档：prefs 里那一格期限与那只闹钟一起改写到新帧 */
        Rearm,
    }

    /**
     * 判据本体：**零 Context、零 android、四枚输入全是注入的标量、体内一处钟都不读**。
     *
     * 连"要不要读钟"都不发生：[classOngoing] 由调用点拿那只**已经注入的** `now` 走
     * [ClassProgressScheduler.ClassWindow.ongoingAt] 算好再递进来（本仓成文禁令的另一半 ——
     * 判据要"现在几点"就当参数递进来，函数体不读钟；`domain/schedule/TodayPlanner.kt`
     * 那份类注释逐字写着「函数体不读 `LocalDate.now()`」，[restoreIfStale] 与
     * [ClassProgressScheduler.armStartBell] 那两族 seam 是同一套形状）。
     * 收成这样的代价与前两处相同："接线到底注没注对那几样"只能按源码形状核对
     * （本模块没有 Robolectric，`getSharedPreferences` 是抛 "not mocked" 的桩），
     * 买到的是**这六档在 JVM 里逐档量得出来**。
     *
     * 档位的先后不许换：
     * 1. `NoWatchdogToMove` 排最前 = "没进过勿扰就不要动用户设置"那条不变量（见 [restore]）；
     * 2. `NoClassInSession` 抢在其余之前 = 没有主人的记录一格都不许推（见那一档）；
     * 3. `LegacyRecordWithoutDeadline` 排在 `AlreadyCurrent` 之前 = 两者同时成立（0 == 0）时
     *    走"旧记录不归这里"那一档，而不是"本来就一致"那一档。
     *
     * 为什么六档里没有「新期限本身都已经过点了」这一档：[classOngoing] 为真就意味着
     * `此刻 < 下课时刻 < 下课 + 宽限 = fresh`（end 取开区间，与 ongoingAt 同一把尺），
     * 那一档在这一跳里取不到值。写出来就是一枚假档位，表里也就没有它的一行。
     *
     * @param hasSavedFilter 即"这一轮进过勿扰、记着用户原本的状态"（`dnd_saved_interruption_filter`）
     * @param classOngoing 新帧这一轮到底有没有"正在上的那一节"（调用点用注入的钟走 ongoingAt）
     * @param storedDeadlineMillis prefs 里那一格**旧帧**期限（`dnd_restore_deadline`；0 = 没写过）
     * @param freshDeadlineMillis 按新帧重算出来的期限；算不出就是 null（见 [deadlineOfEnd]）
     */
    internal fun watchdogRearmDecision(
        hasSavedFilter: Boolean,
        classOngoing: Boolean,
        storedDeadlineMillis: Long,
        freshDeadlineMillis: Long?,
    ): WatchdogRearm = when {
        !hasSavedFilter -> WatchdogRearm.NoWatchdogToMove
        !classOngoing -> WatchdogRearm.NoClassInSession
        freshDeadlineMillis == null -> WatchdogRearm.NoFreshDeadline
        storedDeadlineMillis <= 0L -> WatchdogRearm.LegacyRecordWithoutDeadline
        storedDeadlineMillis == freshDeadlineMillis -> WatchdogRearm.AlreadyCurrent
        else -> WatchdogRearm.Rearm
    }

    /**
     * 课堂铃重排那一跳顺手把勿扰看门狗也对齐到**新帧**（#603）。
     *
     * 病灶：本仓每一枚闹钟都是唤醒档 ＋**绝对 epoch 毫秒**，毫秒是**排铃那一刻**按当时的
     * 系统时间/默认时区算出来的。用户改时间或跨时区之后系统派发 TIME_SET / TIMEZONE_CHANGED，
     * 唯一入口（`widget/WidgetRefreshReceiver`）那一跳会经 [ClassProgressScheduler.rescheduleWindows]
     * 用新帧把双铃重排一遍 —— 但那只看门狗不在双铃里：它唯一的排程点是 [enter]，而 enter
     * 唯一的调用点是**上课铃那一刻**。于是"下课 + 宽限"那一格期限连同闹钟一起停在旧帧，
     * 勿扰就在一个错误的墙钟时刻解除（早解还是晚解取决于偏移方向），而 [selfCheck] 判的
     * 也是那一个旧帧值。
     *
     * 为什么不写成 `WidgetRefreshReceiver` 里另起一步，而要吃现成的重排那一跳：那一侧**拿不到**
     * 这一节课的下课时刻 —— prefs 只存旧帧的毫秒，"排铃那一刻用的是哪个默认时区"全仓没有
     * 任何地方记着，那只 PendingIntent 的 extras 系统侧也读不回来；
     * 要从新帧反推就得把 [ClassProgressScheduler.planNextClassWindow] 那轮全量窗口搜索
     * 再跑一遍。而调用点刚刚算完新帧的 `window.endMillis`、整次重排且只读了一只钟
     * （`reminder/CountdownClockSingleReadGuardTest` 第 ① 层钉的就是它）：新帧就在手上。
     *
     * 为什么不能只靠"已过期的上课铃立即重放 ⇒ 重新 enter"那条**已经存在**的通路：那条通路确实
     * 会把期限与闹钟一起写回新帧（本卡复算过，它在"课还在上"那一支是走得到的），但它靠一枚
     * **闹钟**去更新一格 **prefs 事实**，而这条路本仓从没钉过（全仓零枚守卫写过 `armWatchdog`），
     * 且它的前提正是这只看门狗要防的那件事 —— ROM 吞闹钟。落在那一支上的现场就是 #603。
     *
     * 为什么不先 [cancelWatchdog] 再排（卡面那一手）：两只闹钟共用同一枚 requestCode
     * （[REQUEST_DND_WATCHDOG]）与同一份 PendingIntent，`armWatchdog` 用
     * `FLAG_UPDATE_CURRENT` 再 set 一次就是**顶替**，这正是 [enter] 在两节连排的课之间
     * 已经在做的那一手（它自己也不先 cancel）。而 [cancelWatchdog] 里带着
     * `operation.cancel()` —— 那会把 PI 记录整条摘掉，随后那一手 set 万一抛（ROM 收紧
     * 精确闹钟就是这条链已知的失败点），现场就从"闹钟在旧帧"变成"根本没有闹钟"。
     *
     * 整块吞异常（与 [enter] / [selfCheck] 同一条规矩：这一步是从广播协程里调的，
     * 抛出去只是把"双铃已经排好"那一半也带走）。失败只留一行痕迹、不向外报结论 ——
     * 这一手不动就是"保持现状"，而现状已经由那只旧帧闹钟兜着。
     *
     * @param classOngoing 新帧这一轮**正在上**这一节吗；假 ⇒ 一格都不许动（[WatchdogRearm.NoClassInSession]）
     * @param freshDeadlineMillis 新帧的"下课 + 宽限"（[deadlineOfEnd]）；null = 算不出
     */
    fun alignWatchdogToNewFrame(context: Context, classOngoing: Boolean, freshDeadlineMillis: Long?) {
        runCatching {
            val prefs = prefs(context)
            val stored = prefs.getLong(KEY_DND_DEADLINE, 0L)
            if (watchdogRearmDecision(
                    hasSavedFilter = prefs.contains(KEY_SAVED_FILTER),
                    classOngoing = classOngoing,
                    storedDeadlineMillis = stored,
                    freshDeadlineMillis = freshDeadlineMillis,
                ) != WatchdogRearm.Rearm
            ) return
            // 取证行：改前/改后两格毫秒都在，量"这一节课的勿扰到底被挪到了哪一秒"只能靠它
            Log.i(TAG, "勿扰看门狗按新帧重排 $stored -> $freshDeadlineMillis")
            // 期限先落 prefs、再重排闹钟：[selfCheck] 判的就是那一格，
            // 只挪闹钟不改格子等于闹钟在新帧、判据还在旧帧。
            // null 那一档在判据里就叫 NoFreshDeadline 拦下了，走到这里必然是有值的：
            // 只做类型收敛，不再兜第二种值（兜了就等于两处各有一套"算不出怎么办"）。
            val fresh = freshDeadlineMillis ?: return
            prefs.edit { putLong(KEY_DND_DEADLINE, fresh) }
            armWatchdog(context, fresh)
        }.onFailure { Log.w(TAG, "勿扰看门狗按新帧重排失败", it) }
    }

    /**
     * 「下课 + 宽限」这一把尺的唯一出处：**不读钟**，下课时刻不可用时交回 null
     * （由调用方决定兜底 —— [deadlineOf] 退到 [FALLBACK_LIMIT_MINUTES]，
     * [ClassProgressScheduler.rescheduleWindows] 那一跳则干脆不动，见 [WatchdogRearm.NoFreshDeadline]）。
     *
     * 从 [deadlineOf] 里分出来是为了让"新帧重算期限"那一跳复用同一把尺：宽限量在两处各写
     * 一遍，改一处就是一格旧帧期限与一格新帧期限用不同的余量。
     */
    internal fun deadlineOfEnd(endMillis: Long): Long? =
        if (endMillis > 0L) endMillis + RESTORE_GRACE_MINUTES * 60_000L else null

    /** 恢复期限 = 下课 + 宽限；下课时刻不可用时按兜底上限从此刻算（那只钟只读在这一行） */
    private fun deadlineOf(endMillis: Long): Long =
        deadlineOfEnd(endMillis) ?: System.currentTimeMillis() + FALLBACK_LIMIT_MINUTES * 60_000L

    /**
     * 排看门狗闹钟（Android 12+ 无精确闹钟权限时逐档降级），并把**排上没有**这件事落进 prefs。
     *
     * 用 `setAlarmClock`（「用户闹钟」档）：它正是为"到点必须响"准备的档位，
     * 省电白名单对它的豁免最彻底 —— 而这一步的全部意义就是对抗省电策略。
     * 代价是状态栏会多一个闹钟图标，最迟 [RESTORE_GRACE_MINUTES] 分钟后就消失，
     * 比"手机静音一整天"轻得多。
     *
     * 改前这里两支失败都只念一句"勿扰看门狗排程失败，只剩冷启动自愈"就走到函数末尾：
     * 那句"冷启动自愈"本身是**期限门控**的（见 [restoreIfStale]），闹钟不存在时它就只剩
     * "下一次起手恰好已过期限"那一档；而下一节上课铃还会把 prefs 里那一格期限往后覆写，
     * 于是净效果是**越等越久**，静默的静态上界根本出不来。
     * 现在结论必须落账（[KEY_WATCHDOG_REARM_PENDING]）：三手 `set` 全抛 ⇒ 落旗，
     * 排上 ⇒ 撤旗，[selfCheck] 起手读的就是这一格。
     * 拿不到 AlarmManager 那一手同样落旗（三手都没试 = 没排上；口径同
     * `ClassProgressScheduler.schedule` 那句"拿不到 AlarmManager 也是 false，答 true 就是假成功"），
     * 改前它是静默 `?: return`，一句留痕都没有。
     */
    private fun armWatchdog(context: Context, atMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            Log.w(TAG, "勿扰看门狗没拿到 AlarmManager，三手 set 都没试：已落补排旗，等下一次起手")
            recordWatchdogArmOutcome(context, armed = false)
            return
        }
        val operation = watchdogPendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        armWatchdogAttempt(
            canExact = canExact,
            setAlarmClock = {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(atMillis, ClassProgressScheduler.alarmShowIntent(context)),
                    operation,
                )
            },
            setExact = { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation) },
            setPowerSave = { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation) },
            recordArmOutcome = { armed -> recordWatchdogArmOutcome(context, armed) },
        )
    }

    /**
     * [armWatchdog] 的档位本体：**零 android、零时钟**，三手 `set`、落账口与留痕口全是参数 ——
     * 形状逐字照 `ClassProgressScheduler.armEndBell`（同一族在册先例，表驱动见
     * `ClassProgressBellArmTest`）。
     *
     * 收成这一形买到的是：**"set 抛了"与"结论落没落账"这两档在 JVM 里都是可注入的事件**
     * （递一枚会抛的 lambda 就是、落账口收进一枚记下实参的 lambda 就是），不需要真机也不需要
     * Robolectric ⇒ 结论／留痕／落账三样都能被表驱动逐档量，连"排上了却不落账"这种半截修法都跑得红。
     * 量不到的仍然只有一件，也照实写在这里：真 `AlarmManager` 在澎湃／MIUI 上到底抛不抛、
     * 抛在哪一手，以及"排成功之后被系统吞掉"那一档（那一刻 `set` 返回了，档位本体答"排上了"，
     * 旗不落 —— 那一档只有装机读得出）。
     *
     * 三档：授权了就试 `setAlarmClock`，它抛就留痕并退回精确档；精确档也抛就判"没排上"。
     * 未授权那一支（`canScheduleExactAlarms` 为假 —— 最容易走到的那一支）直接省电档，
     * 它抛同样判"没排上"。两手都失败的场合**两句留痕都要在**：改前
     * `recoverCatching` 的返回值被丢掉，退回那一手再失败时读起来就像"一次都没失败过"。
     *
     * 落账口 [recordArmOutcome] **必填**（不给默认值）：给了默认空 lambda，"忘传"与"传错"在编译期
     * 与运行期都不出声，那条"失败必须落到下一趟起手"的链就整条断在参数上。
     * 三条出口（省电档、精确档、`setAlarmClock` 一次成功）**共用末尾那一手调用** ⇒ 每次排程恰好
     * 落账一次，既不许多落（两次就是两本账）也不许漏落。
     */
    internal fun armWatchdogAttempt(
        canExact: Boolean,
        setAlarmClock: () -> Unit,
        setExact: () -> Unit,
        setPowerSave: () -> Unit,
        recordArmOutcome: (armed: Boolean) -> Unit,
        logFailure: (reason: String, error: Throwable) -> Unit = LOG_DND_ARM_FAILURE,
    ): Boolean {
        val armed = if (!canExact) {
            // 未授予 SCHEDULE_EXACT_ALARM：精确档会抛 SecurityException，退非精确档
            armWatchdogTry("勿扰看门狗省电档没排上：已落补排旗，只剩下一次起手补排", setPowerSave, logFailure)
        } else {
            val clock = runCatching(setAlarmClock).onFailure {
                logFailure("勿扰看门狗 setAlarmClock 失败，退回精确档", it)
            }
            if (clock.isSuccess) true
            else armWatchdogTry("勿扰看门狗退回精确档那一手也没排上：已落补排旗，只剩下一次起手补排", setExact, logFailure)
        }
        recordArmOutcome(armed)
        return armed
    }

    /**
     * 一次排程尝试的**唯一**形状：`runCatching` 之后必须接 `onFailure`，结论由 `isSuccess` 带出去。
     * 裸着一枚 `runCatching` 再让函数走到末尾，就是把失败读成成功 —— 本卡那两支病灶都是这个形状
     * （口径逐字同 `ClassProgressScheduler.armBellAttempt`，那张卡把这句话说给课堂铃，这里补齐看门狗）。
     */
    private fun armWatchdogTry(
        reason: String,
        attempt: () -> Unit,
        logFailure: (reason: String, error: Throwable) -> Unit,
    ): Boolean = runCatching(attempt).onFailure { logFailure(reason, it) }.isSuccess

    /**
     * 排程结论落账：排上了撤旗、没排上落旗（[selfCheck] 起手读的就是这一格）。
     *
     * 全文件只许这一处写它 —— 两处各写一遍就是两本账，改一处忘一处就得到"闹钟排上了却留着旗"
     * 那种每次起手都重排一遍的状态。落账失败也要留痕：它一抛，补排链就整条失去记忆。
     */
    private fun recordWatchdogArmOutcome(context: Context, armed: Boolean) {
        runCatching {
            prefs(context).edit {
                if (armed) remove(KEY_WATCHDOG_REARM_PENDING) else putBoolean(KEY_WATCHDOG_REARM_PENDING, true)
            }
        }.onFailure { Log.w(TAG, "勿扰看门狗补排旗落账失败（armed=$armed）", it) }
    }

    /**
     * 撤销看门狗。restore 成功与 [ClassProgressScheduler.cancelAll] 都要调它，
     * 否则用户主动关掉功能后还会白醒一次。
     */
    fun cancelWatchdog(context: Context) {
        runCatching {
            // 主动撤销 = "这只看门狗不要了"，待补排那一格当场一起撤：
            // 留着旗会让 selfCheck 起手补排一只刚刚被用户撤掉的闹钟（关掉功能、清空课表那一族）。
            // 写在取服务之前：`?: return` 那一手也得把书账清干净，不然清不掉的那一枚就是残留。
            prefs(context).edit { remove(KEY_WATCHDOG_REARM_PENDING) }
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
