package com.buaa.schedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.buaa.schedule.domain.widget.RefreshChainCarrierGate
import com.buaa.schedule.domain.widget.RefreshChainCarrierPlan
import com.buaa.schedule.domain.widget.WidgetRolloverActionKind
import com.buaa.schedule.domain.widget.WidgetRolloverClaim
import com.buaa.schedule.domain.widget.WidgetRolloverGate
import com.buaa.schedule.domain.widget.WidgetRolloverSentinel
import com.buaa.schedule.reminder.WakeLocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 事件驱动的 Widget/提醒刷新接收器（时间类变化的唯一入口）：
 * - 自发的零点刷新（今天/明天 Widget 的日期滚动）；
 * - 系统时间被手动修改、时区变化、日期变化（BootReceiver 不再订阅这几个，
 *   避免同一事件被两个接收器各跑一遍）；
 * - 精确闹钟权限被用户授予（把提醒升级为精确闹钟）。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val timeChanged = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_DATE_CHANGED
        val exactAlarmPermissionChanged =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        if (action != ACTION_MIDNIGHT_REFRESH && !timeChanged && !exactAlarmPermissionChanged) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // goAsync 只保证进程存活、不保证 CPU 醒着：零点/改时间多半在 Doze 里，
                // 这段要查库重算快照并重排闹钟，中途睡回去就会留下"组件停在昨天"
                // （唤醒锁用法同 ReminderReceiver）
                WakeLocks.withPartialWakeLock(
                    context,
                    "widget_refresh",
                    // 这一段比 ReminderReceiver 的单条提醒重排重得多（全学期快照 sync +
                    // 6 个组件重绘），默认 5 秒不够，会被系统提前收回
                    timeoutMs = 10_000L,
                ) {
                    // 一枚链只读一遍（#218 ＋在册 #536 F2 同族的第二处落点，T366①）：下面这几步原先
                    // 各自回库查同一批表（改时间那一趟满档读学期行 4 遍、节次表 4 遍、展示口径的课程
                    // 3+K 遍、提醒册子 4 遍），而这些步骤之间没有任何写那几张表的动作 —— 本文件里
                    // 唯一那手写入是跨天配额的 CAS，写的是一枚进程内 AtomicLong，不打断载体。
                    // 取不取由判据分档（本体在 domain/widget/RefreshChainCarrierGate，这里只按档位分派）：
                    // 纯零点那一趟唯一的消费者只要那三格、自己一趟就读得完，载体反而多读一趟 ⇒ 不取。
                    // 取不到载体就当没有 —— 各步照旧自己读，宁可多读一趟也不许少读。这一手必须在锁内：
                    // 它本身就是一趟查库，而 goAsync 只保证进程存活、不保证 CPU 醒着（同上那段唤醒锁）。
                    val carrierPlan = RefreshChainCarrierGate.decide(
                        hasReminderChainStep = timeChanged || exactAlarmPermissionChanged,
                        hasPreviewStep = timeChanged,
                        hasWidgetRefreshStep = action == ACTION_MIDNIGHT_REFRESH || timeChanged,
                    )
                    val chainSnapshot = when (carrierPlan) {
                        RefreshChainCarrierPlan.Build -> readChainSnapshotOrNull(context)
                        RefreshChainCarrierPlan.SkipOnlyWidgetConsumer -> null
                        RefreshChainCarrierPlan.SkipNoCarrierConsumer -> null
                    }
                    if (action == ACTION_MIDNIGHT_REFRESH || timeChanged) {
                        // 零点会醒两次：系统 00:00 的 ACTION_DATE_CHANGED 与我们自排的
                        // ACTION_MIDNIGHT_REFRESH 都落到这里，各跑一遍"全学期快照 sync +
                        // 组件重绘"。自排那条是对抗 ROM 的兜底（它同样可能吞掉系统广播），
                        // 两条都留，但重复的那次全量刷新没有意义。
                        //
                        // 改前那一手逐字留在这里做取证（源码形状守卫按"代码里 0 处、注释里 1 处"
                        // 双向复算，拿它证明下面这个 `when` 不是把结论折成一枚 Boolean）：
                        // ```
                        // if (claimRolloverRefresh()) { BackgroundSync.refreshWidgets(context) }
                        // ```
                        // 那一形的病是"没刷过"与"刚刚刷过"共用同一枚 `0L`，开机后窗口之内的
                        // 真跨天被整段跳过而一句话不说。分档判据住在
                        // `domain/widget/WidgetRolloverGate.kt`，这里只按档位分派。
                        when (val claim = claimRolloverRefresh(rolloverActionKind(action))) {
                            is WidgetRolloverClaim.GrantedFirstClaim,
                            is WidgetRolloverClaim.GrantedAfterWindow -> {
                                BackgroundSync.refreshWidgets(context, snapshot = chainSnapshot)
                                Log.i(TAG, "跨天全量刷新放行: ${rolloverClaimTrace(claim)}")
                            }

                            is WidgetRolloverClaim.SentinelContradiction -> {
                                // 旗与哨兵互相打脸 = 领取这一族的形状病了，不是今天真的没课要画。
                                // 宁可多刷一遍，也不许把真跨天咽掉，所以这一档照刷且必须告警。
                                BackgroundSync.refreshWidgets(context, snapshot = chainSnapshot)
                                Log.w(TAG, "跨天刷新哨兵自相矛盾 仍按放行处理: ${rolloverClaimTrace(claim)}")
                            }

                            is WidgetRolloverClaim.SuppressedAsDuplicate -> {
                                // 去重本业：同窗口内的重复那一条。跳过全量刷新，续排照常，
                                // 但这一手要说得出话（HyperOS 把 logcat 截在 Info 级 ⇒ Log.i）
                                Log.i(
                                    TAG,
                                    "跨天全量刷新被去重压掉 同窗口内的重复: ${rolloverClaimTrace(claim)}",
                                )
                            }

                            is WidgetRolloverClaim.SuppressedClockInverted -> {
                                // 递进来的读数倒退：拿错尺的形状，不是"刚刚刷过"，不许共用那一句
                                Log.w(
                                    TAG,
                                    "跨天全量刷新跳过 单调钟读数倒退: ${rolloverClaimTrace(claim)}",
                                )
                            }

                            is WidgetRolloverClaim.NotRolloverAction -> {
                                // 外面的 `if` 已经拦掉非时间类动作；走到这里就是接线错了
                                Log.w(TAG, "非跨天动作走到了配额领取处: ${rolloverClaimTrace(claim)}")
                            }
                        }
                        // 续排零点闹钟这一手**没有载体形参**：签名只有 context 与 hasAnyWidget
                        // （BackgroundSync 裸 :434 那一枚），它体内一趟表都不读（读的是 LocalDate.now +
                        // 跨 binder 探测 + AlarmManager）⇒ 这里保持裸调不是漏接，没什么可递的。
                        BackgroundSync.scheduleWidgetMidnight(context)
                    }
                    if (timeChanged || exactAlarmPermissionChanged) {
                        // 时间/时区变化会让已注册的触发时间失准，权限变化可升级为精确闹钟；
                        // 这里的 Boolean 同样不能丢——改完时间课堂铃窗口也要重排
                        // 载体递进去：这一手体内是「重排提醒」＋（提醒那条链没接手课堂铃时）
                        // 「课堂铃兜底续排」，那两步读的正是头顶这一份装着的同一批表（#218）
                        BackgroundSync.rescheduleRemindersAndBells(context, snapshot = chainSnapshot)
                    }
                    if (timeChanged) {
                        // 明日预告按 22:00 定时，时间/时区变化后要重新对齐；
                        // 通知渠道也需要确保存在（原先由 BootReceiver 顺带做）
                        BackgroundSync.scheduleTomorrowPreview(context, snapshot = chainSnapshot)
                        com.buaa.schedule.reminder.ReminderNotifications.ensureChannels(context)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消是控制流信号，不是错误：绝不能在这里吞掉，
                // 否则上游的 cancel/超时会被当成"正常完成"，结构化并发失效。
                throw e
            } catch (e: SecurityException) {
                // 精确闹钟权限在调度中途被撤销（或 ROM 收紧）时系统会抛 SecurityException：
                // 这是要用户去设置里处理的权限问题，与一般性失败不同，单独留痕便于定位。
                // BackgroundSync 内部已有 canScheduleExactAlarms 守卫，走到这里多为非预期路径。
                Log.w(TAG, "事件刷新被系统拒绝（权限类失败）: $action", e)
            } catch (e: Exception) {
                Log.w(TAG, "事件刷新失败: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WidgetRefreshReceiver"
        const val ACTION_MIDNIGHT_REFRESH = "com.buaa.schedule.action.WIDGET_MIDNIGHT_REFRESH"

        /**
         * 跨天全量刷新的去重窗口。两个信号（系统 DATE_CHANGED、自排零点闹钟）
         * 正常都在 00:00±几十秒内到达，留足余量又不至于长到让真实的数据变化被吞掉。
         *
         * ⚠️ 这一枚窗口只许拿来比"距离上一次领取"，不许拿来比"距离开机"：
         * 判据本体在 `domain/widget/WidgetRolloverGate.kt`，那一侧的短路（从没领过 ⇒ 放行）
         * 排在窗口比较之前。
         */
        private const val ROLLOVER_SUPPRESS_MS = 120_000L

        /**
         * 与判据同源的「没有上一次」哨兵。这里只是别名 —— 本文件不许另写一枚负数字面量，
         * 否则刻度与判据两头各有一套「从没刷过」。
         */
        private const val NEVER_CLAIMED_MILLIS = WidgetRolloverSentinel.NEVER_CLAIMED_MILLIS

        /**
         * 上一次领取跨天刷新配额的时刻（`elapsedRealtime` 刻度）。
         *
         * 初始值是 [NEVER_CLAIMED_MILLIS]（`-1L`），**不是 `0L`**：
         * 改前写的是 `java.util.concurrent.atomic.AtomicLong(0L)`，而 `elapsedRealtime()`
         * 是"开机以来的毫秒数"、开机那一刻恰好从 0 起走 ⇒ "从没刷过"与"刚刚在开机那一瞬
         * 刷过"在类型上不可区分，于是开机后 [ROLLOVER_SUPPRESS_MS] 之内到达的那四条广播
         * 全被窗口判据当成重复而整段跳过（且改前那一支一条日志都不留）。
         * `-1L` 落在这只钟取不出来的那一侧，所以它只可能表示"没有上一次"。
         */
        private val lastRolloverAt = java.util.concurrent.atomic.AtomicLong(NEVER_CLAIMED_MILLIS)

        /** 把落到这条链上的动作归成判据认得的五档 */
        private fun rolloverActionKind(action: String): WidgetRolloverActionKind = when (action) {
            ACTION_MIDNIGHT_REFRESH -> WidgetRolloverActionKind.SelfMidnightAlarm
            Intent.ACTION_DATE_CHANGED -> WidgetRolloverActionKind.SystemDateChanged
            Intent.ACTION_TIME_CHANGED -> WidgetRolloverActionKind.SystemTimeChanged
            Intent.ACTION_TIMEZONE_CHANGED -> WidgetRolloverActionKind.SystemTimezoneChanged
            else -> WidgetRolloverActionKind.NotRollover
        }

        /**
         * 领取这一次跨天刷新的"全量刷新"配额，交回判据的分档结论（**不许折成 Boolean**）。
         *
         * 计时用 `elapsedRealtime`（单调）而不是墙上时钟：用户改时间本身就会
         * 以 `ACTION_TIME_CHANGED` 走到这个分支，拿被改的时钟判窗口等于判不出来。
         * 判据不吃钟：读数在这里现取，当参数递进去。
         *
         * ⚠️ 必须 CAS：DATE_CHANGED 与自排零点闹钟恰恰都在 00:00±几十秒到达，
         * 各自起的 IO 协程用 @Volatile 的"先读后写"会双双领到配额，全学期快照
         * 与 6 个组件重绘跑两遍。哨兵只有这一枚、写入只有这一手，
         * `hasEverRefreshed` 由它现推 —— 别让旗标与时间戳分居两枚字段，那样又会各说一套。
         */
        private fun claimRolloverRefresh(actionKind: WidgetRolloverActionKind): WidgetRolloverClaim {
            val now = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val prev = lastRolloverAt.get()
                val claim = WidgetRolloverGate.decide(
                    actionKind = actionKind,
                    nowElapsedRealtime = now,
                    lastClaimedAt = prev,
                    windowMillis = ROLLOVER_SUPPRESS_MS,
                    hasEverRefreshed = prev != NEVER_CLAIMED_MILLIS,
                )
                val commitAt = rolloverCommitAtOrNull(claim) ?: return claim
                if (lastRolloverAt.compareAndSet(prev, commitAt)) return claim
            }
        }

        /**
         * 这一档要不要把哨兵推到新的读数；`null` = 没占着配额，哨兵一格都不许动。
         * 六档逐枚点名、不带 `else`：判据新增一档时这里必须跟着表态。
         */
        private fun rolloverCommitAtOrNull(claim: WidgetRolloverClaim): Long? = when (claim) {
            is WidgetRolloverClaim.GrantedFirstClaim -> claim.claimedAtElapsedRealtime
            is WidgetRolloverClaim.GrantedAfterWindow -> claim.claimedAtElapsedRealtime
            is WidgetRolloverClaim.SentinelContradiction -> claim.nowElapsedRealtime
            is WidgetRolloverClaim.SuppressedAsDuplicate,
            is WidgetRolloverClaim.SuppressedClockInverted,
            is WidgetRolloverClaim.NotRolloverAction -> null
        }

        /** 取证行：把档位与它据以判断的读数原样念出来（Info/Warn 两级，release 里不许被削掉） */
        private fun rolloverClaimTrace(claim: WidgetRolloverClaim): String = when (claim) {
            is WidgetRolloverClaim.GrantedFirstClaim ->
                "action=${claim.actionKind} 本进程首次领取 nowElapsed=${claim.claimedAtElapsedRealtime}"

            is WidgetRolloverClaim.GrantedAfterWindow ->
                "action=${claim.actionKind} 出窗放行 nowElapsed=${claim.claimedAtElapsedRealtime} " +
                    "距上次领取=${claim.elapsedSinceLastClaimMillis}ms"

            is WidgetRolloverClaim.SuppressedAsDuplicate ->
                "action=${claim.actionKind} 距上次领取=${claim.elapsedSinceLastClaimMillis}ms " +
                    "窗口=${claim.windowMillis}ms"

            is WidgetRolloverClaim.SuppressedClockInverted ->
                "action=${claim.actionKind} nowElapsed=${claim.nowElapsedRealtime} " +
                    "lastClaimed=${claim.lastClaimedAt}"

            is WidgetRolloverClaim.SentinelContradiction ->
                "action=${claim.actionKind} hasEverRefreshed=${claim.hasEverRefreshed} " +
                    "lastClaimed=${claim.lastClaimedAt} nowElapsed=${claim.nowElapsedRealtime}"

            is WidgetRolloverClaim.NotRolloverAction ->
                "action=${claim.actionKind}"
        }
    }
}
