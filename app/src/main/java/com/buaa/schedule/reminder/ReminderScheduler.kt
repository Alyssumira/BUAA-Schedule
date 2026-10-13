package com.buaa.schedule.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.EffectiveReminderSetting
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.model.toStartEndTimes
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.WeekdayDateOffset
import com.buaa.schedule.domain.schedule.WeekdayOffset
import com.buaa.schedule.domain.schedule.toEpochMillis
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 课程提醒调度器。
 *
 * 为每门启用提醒的课程计算“触发时间 = 下次上课时间 - 提前量”，
 * 从中挑出**触发时刻最早的那一等**并各排一枚闹钟（并列是常态，不是一门课的运气）；
 * 课程表变化后重新调用 rescheduleAll 即可。
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    /**
     * 课前提醒闹钟槽位窗口的**第一格** requestCode。
     *
     * 写成常量而不是两处各写一个 `0`：判等靠的是 requestCode + Intent.filterEquals，
     * 两边对不上就是"查得到一条永远不存在的闹钟"这种查不出来的错。
     *
     * T205 起它的含义从"全仓库唯一一条闹钟"改成"一段连续槽位的第一格"：同一触发时刻并列
     * [MAX_TIED_REMINDERS] 门课以内是每门一枚（[armTiedReminders]），槽位号只出现在
     * [reminderSlotIntent] 这一格里 —— 排、撤、查三条路共用同一个 builder，
     * requestCode 与 Intent 才可能在每一处都对得上。分成两份 builder（或者两份槽位算法）
     * 就是这枚常量当初警告的那个坑：查的那一侧永远查不到排的那一侧。
     */
    private const val REQUEST_CODE_REMINDER = 0

    /**
     * 同一触发时刻最多排得出几枚闹钟（并列超过这个数时超出部分本轮排不出去，
     * 但一定被 [armTiedReminders] 那句 `Log.w` 念出来，不再静默）。
     *
     * 代价要说清，这枚数是两笔账的乘数：
     * - [cancelAll] 与 [hasPendingReminder] 每轮都要把 `[0, MAX_TIED_REMINDERS)` 这一段槽位
     *   各查一遍（`FLAG_NO_CREATE` 只查不造），每加重排一次就多几趟 PendingIntent 查表；
     * - 每枚闹钟响过之后 [ReminderReceiver] 都会自己跑一遍全量重排，并列 N 门就是 N 趟
     *   （读库 + 选窗口）—— 这是"排多条闹钟"这条路的价，另一条路（一枚闹钟拉起、
     *   接收侧把同一时刻的计划全念出来）要改的是接收侧，不在本模块的改权里。
     * 取 4：撞节次在这台机器上的真实形状是 2 门（周三 3-4 节两门并排），3 门已是少数，
     * 4 门以上属于"整周课表同一时刻全上"那种不存在的形状，再多就是白付查表趟数。
     */
    private const val MAX_TIED_REMINDERS = 4

    data class ReminderPlan(
        val course: Course,
        /** 本次提醒对应的那个**连续节次段**（跨午休的课一段一次），不是整门课的节次 */
        val segment: IntRange,
        val classStart: LocalDateTime,
        val advanceMinutes: Int,
        val triggerAtMillis: Long,
        /** 这一次上课落在第几周：课前实况的「第 N 周」取它，与 [segment] 同为"这一次"的属性 */
        val week: Int,
        /**
         * 这一提前量的**出处**（T214 病灶 B，#264）：读来的行，还是"库里没有那一行"按默认造的。
         *
         * 病灶的形态是「没设过」被读成「设了、且开着」而无人交代。行为口径本身没改
         * （默认仍然是开启 + [com.buaa.schedule.domain.model.ReminderSetting.DEFAULT_ADVANCE_MINUTES] 分钟，
         * 那两枚常量全仓只有一处字面量），改的是**这一档必须说得出自己是哪一档**：
         * 排查现场时 `toString()` 里就带着它，用户看得见的那一句在编辑器「提醒」那一组
         * （`ui/editor/CourseEditorScreen.kt` 的 `editorReminderSectionSummary`）。
         */
        val settingBasis: EffectiveReminderSetting.Basis,
        /**
         * 与这一条**同一触发时刻**并列的其余各门（不含自己）。
         *
         * 只有等首（[planTiedReminders] 交出来的那一条）这一格非空，伙伴自己那一格恒为
         * `emptyList()`：两边互指会让 `toString()` 与 `equals()` 绕无穷圈，而
         * [rescheduleAll] 返回给调用方的正是等首那枚，排查现场时会被打印。
         * 要"本轮一起排出去的全部各门"请走 [armableTogether]，别自己拼顺序。
         */
        val sameInstantPartners: List<ReminderPlan> = emptyList(),
    ) {

        /** 等首 + 同刻伙伴 = 本轮该一起排出去的那一等（槽位不够时被挤掉的部分由 [armTiedReminders] 留痕） */
        internal fun armableTogether(): List<ReminderPlan> = listOf(this) + sameInstantPartners
    }

    /**
     * 重排课前提醒与上/下课铃。
     *
     * @return 本轮排上的那**一等**课前提醒里的等首（[ReminderPlan.sameInstantPartners] 带着
     *   一起排出去的其余各门，最多 [MAX_TIED_REMINDERS] 枚闹钟）；null 表示本轮没有待触发的课前提醒
     *   （没配学期 / 课表为空 / 学期已结束 / 提醒全关 / 课的周次全在学期外）。
     *   ⚠️ null **不等于**"课堂侧状态已经被收干净"：此刻正处在课堂窗口内（正在上课，
     *   或在数着一节还没开始的课的课前倒计时）时那道撤销会被跳过
     *   （见 [shouldTakeDownClassProgress]），清理交给调用方的续排链
     *   （[ClassProgressScheduler.rescheduleNextWindow]）。
     *
     * 把结论带出来是为了让调用方复用：[com.buaa.schedule.widget.BackgroundSync.rescheduleReminders]
     * 要的"还有没有待触发的提醒"与这里"排不排闹钟"是同一个问题的两面，
     * 它自己再搜一遍等于把 O(课程数 × 剩余周次 × 节次段) 的窗口展开在一次唤醒里做两遍
     * （学期中段上千次窗口构造），而且外层那一遍必须再读一次时钟——
     * 正好破掉下面那段"一次重排只读一次时钟"的同源约束。
     *
     * [semester] 同时是**总周数**的唯一出处（`semester.totalWeeks`）：T214 病灶 A 之前这里只把
     * `semester.startLocalDate` 递给两条链，`totalWeeks` 全程没当判据用过。
     */
    fun rescheduleAll(
        context: Context,
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        reminders: Map<Long, ReminderSetting> = emptyMap(),
    ): ReminderPlan? {
        val semesterStart = semester?.startLocalDate
        // 学期行在不在，决定了"这一周在不在学期内"判不判得出来：null 那一档由 TermWeekGate
        // 答"不放行"（与展示侧的 showAllCoursesWithoutWeek 故意不对称，理由见那枚内核的第 3 条口径）
        val totalWeeks = semester?.totalWeeks
        if (semesterStart == null || courses.isEmpty()) {
            cancelAll(context)
            // 只撤课前提醒还不够：上下课铃、常驻通知与勿扰都得收干净，
            // 否则"上课中途清空课表"会留下永久勿扰 + 一条滑不掉的常驻通知。
            // 这条分支不看任何判据、无条件收：它对应的是"真的没数据"（没配学期 / 课表已空），
            // 与下面那条 plan==null 不同 —— 那种用户课还在上，见那里的说明（R5 F-11）
            ClassProgressScheduler.cancelAll(context)
            return null
        }
        // 两个课堂开关：一次重排只读这一份（plan == null 那条分支的判据与下面排铃那段都要）
        val prefs = context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val classProgress = prefs.getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true)
        val dndEnabled = prefs.getBoolean(ClassProgressReceiver.PREF_DND, false)
        // 一次重排只读一次时钟：planNextReminder 拿 `now` 判"这一段还没开始"、
        // 拿 `nowMillis` 判"触发时刻过了没有"，两次各读各的就会在跨秒那一刻自相矛盾
        // （表现为提前量刚好用尽的那节课被跳过，链条跳到下周）。
        val now = LocalDateTime.now()
        val plan = planOnce(courses, semesterStart, totalWeeks, timeSlots, reminders, now)
        cancelAll(context)
        if (plan == null) {
            // 没有下一条提醒（提醒全关 / 学期已结束 / 课的周次全在学期外那几周上）**不等于**
            // 课堂侧状态也该当场收干净。
            // 此前这里无条件 ClassProgressScheduler.cancelAll，实测（emulator-5554、ed70a1d）
            // 「课前提醒全关」的用户每重排一次，正在上的那节课就被强制恢复勿扰
            // （ZenModeController 20:06:05.450 →0）、约 5 秒后再被紧随其后的续排链补回来
            // （20:06:10.610 →2）；更糟的是中间那道撤销把勿扰记录与看门狗闹钟一起抹掉，
            // 而冷启动 selfCheck 判的是"有残留记录才动手" —— 那次重新进入一旦被 ROM 吞掉
            // 就再也救不回来，这一节课的自动勿扰永久没进。
            // 带判据的清理只此一份实现（ClassProgressScheduler.rescheduleWindows 里那个
            // "课还没开始且不在数课前倒计时"的分支），这里只决定"现在就收"还是
            // "交给续排链收"，不长出第二份判据。
            // ai/T11 那枚补上了"正在上课"，这一枚补的是同族的另一半：**正在数一节还没开始的
            // 课的课前倒计时**。那一刻 plan 也是 null（唯一一条课前提醒已经触发过了），
            // 于是每次改设置 / 改课表 / 关掉提醒引发的重排都会走这里，把岛上那条倒计时
            // 连同**紧随其后那一发上课铃**一起撤掉（cancelAll 第一步就是撤双铃）——
            // 与"正在上课"那处不同，这里被撤掉的铃再也没有哪条链会补回来：本轮没有下一条提醒，
            // 那一节课的课堂实况与自动勿扰就此永久缺席。
            val takenDown = takeDownClassProgressIfNeeded(
                courses = courses,
                semesterStart = semesterStart,
                totalWeeks = totalWeeks,
                timeSlots = timeSlots,
                classProgressEnabled = classProgress,
                dndEnabled = dndEnabled,
                // 与上面那次搜索同一只钟：选窗口、判"在不在上课"、以及喂给倒计时判据的
                // 开课毫秒都由这一个 now 派生
                now = now,
                // 只判**归属**、不带上 ReminderNotifications.isCountingDownTo 那个内部读真钟的
                // 时间判：这一判问的"课还没开始"上面已经用注入的 now 判过了（见
                // shouldTakeDownClassProgress 的论证 3 —— 两处判据各读一只钟就是跨秒自相矛盾）
                countingDownTo = ReminderNotifications::ownsCountdownTo,
                tearDown = { ClassProgressScheduler.cancelAll(context) },
            )
            if (!takenDown) {
                Log.d(TAG, "正处在课堂窗口内（正在上课 / 正数着这节的课前倒计时）：本轮不撤销课堂铃与勿扰，交给续排链")
            }
            return null
        }
        // 上/下课铃与常驻通知的撤销/重排统一交给 scheduleClassProgress：
        // 它会先撤掉旧的两个闹钟，再按最新的「尚未结束的最早一次课」重排，
        // 并在没有课在进行时把遗留的常驻通知与勿扰收干净。

        // getSystemService(Class) 在极端情况下返回 null（系统服务未就绪/定制 ROM），
        // 不判空会直接 NPE，而这条路径是从广播里调的，崩了就是"设置里点保存闪退"。
        // ⚠️ 但**不能早退**：拿不到 AlarmManager 只意味着"这一次课前闹钟排不上"，
        // 下面那段课堂铃的撤销/重排跟 AlarmManager 无关（它自己会判 null），
        // 早退会把清理块一起跳过，留下永不消失的常驻通知 + 永不恢复的勿扰。
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            Log.w(TAG, "AlarmManager 不可用，跳过课前提醒排程（课堂铃清理照常执行）")
        } else {
            armTiedReminders(context, alarmManager, plan)
        }

        // 上/下课铃与常驻通知（"课程进行中"）和「上课自动勿扰」是两个独立开关，
        // 但共用同一对上课/下课闹钟：任一开启都必须排铃，接收器里再按开关决定做什么。
        // 此前任一开关关闭就直接不排铃，导致"只开勿扰、关掉常驻通知"的用户整条链路
        // 都不跑 —— 表现为上课自动勿扰"好像没用"。两个开关取函数开头读的那一份。
        if (classProgress || dndEnabled) {
            // 口径与下课铃广播共用 ClassProgressScheduler.rescheduleWindows
            // 结论必须念：false = 本轮至少有一枚课堂铃"该排而没排上"，那一侧的具体原因由
            // ClassProgressScheduler 自己的 `Log.w` 留痕，这里补的是整轮那一句结论
            //（下课铃没排上 = 勿扰挂着没人解除，这条链的后果写在 #16 / T12 / T14 的账里）。
            val bellsArmed = ClassProgressScheduler.rescheduleWindows(
                context = context,
                courses = courses,
                semesterStart = semesterStart,
                // 同一次重排的同一枚学期行：这里不传就是"屏上画不到的那一周照样排铃、照样挂勿扰"（#263）
                totalWeeks = totalWeeks,
                timeSlots = timeSlots,
            )
            if (!bellsArmed) {
                Log.w(TAG, "本轮课堂铃没排上：勿扰与常驻通知的解除这一轮没有闹钟接手")
            }
        } else {
            // 两个开关都关了：连常驻通知 / 勿扰遗留状态一起收干净，
            // 别留着一条不会再更新的通知或一个永不恢复的勿扰
            ClassProgressScheduler.cancelAll(context)
        }
        return plan
    }

    /**
     * 把「同一触发时刻的那一等」一起排上：一门一枚闹钟，各占一段连续槽位。
     *
     * 为什么不是"只排头名"：并列在本仓是常态而不是异常（同一时刻几门课同时开课），
     * 只取一枚就是让数据库行序决定"哪门课配得上被提醒"，而落榜那一门在下一次重排时
     * 触发时刻已经过了（[planTiedReminders] 的 `triggerAt <= nowMillis` 那判），
     * 这一节课它一声不响。
     *
     * `canScheduleExactAlarms()` 一次取好喂给每一枚槽位：它是问系统服务的，
     * 并列三门就问三遍是白付趟数，而同一次重排里授权状态不会自己变。
     */
    private fun armTiedReminders(context: Context, alarmManager: AlarmManager, head: ReminderPlan) {
        val tied = head.armableTogether()
        val slotting = allocateReminderSlots(tied, MAX_TIED_REMINDERS)
        if (slotting.droppedCount > 0) {
            Log.w(
                TAG,
                "同一触发时刻并列 ${tied.size} 门课，槽位只有 $MAX_TIED_REMINDERS 枚：" +
                    "本轮有 ${slotting.droppedCount} 门排不出去（这一节课它们没有课前提醒）",
            )
        }
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        slotting.armed.forEachIndexed { slot, plan ->
            armOneReminder(context, alarmManager, plan, slot, canExact)
        }
    }

    /**
     * 一枚槽位排一门课的提醒。
     *
     * Android 12+ 精确闹钟是特殊权限（默认拒绝），未授予时降级为不精确闹钟，避免崩溃。
     * `canScheduleExactAlarms()` 本身也可能抛 SecurityException（权限被运行期撤销），
     * 因此整段调度都套上 runCatching 兜底，失败只是这一次不精确，不影响其余链路。
     * 失败一定留痕并带上槽位与课程：并列时"哪一枚没排上"只能从这两格里认出来。
     */
    private fun armOneReminder(
        context: Context,
        alarmManager: AlarmManager,
        plan: ReminderPlan,
        slot: Int,
        canExact: Boolean,
    ) {
        val pendingIntent = reminderSlotIntent(
            context = context,
            slot = slot,
            plan = plan,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pendingIntent)
            }
        }.onFailure { Log.w(TAG, "调度课程提醒失败 slot=$slot course=${plan.course.id}", it) }
    }

    /**
     * 「本轮挑不出下一条课前提醒」那条分支的编排：课堂侧那一揽子状态（上/下课铃、常驻通知、
     * 勿扰、勿扰看门狗）是现在就收，还是留给紧随其后的续排链去收。
     *
     * 形状抄 `BackgroundSync.runColdStartWidgetSteps` —— 本模块单测没有 Robolectric
     * （[ClassProgressScheduler.cancelAll] 要 Context，android.jar 里全是抛 "not mocked" 的桩），
     * 真跑一次撤销得设备，JVM 侧钉得住的只有"那个撤销动作被调了几遍"，
     * 所以撤销本身收成一个注入的 lambda（生产接线传的就是 `cancelAll`）。
     *
     * [countingDownTo] 走同一套路：它读的是 [ReminderNotifications] 那对进程内 `@Volatile`
     * （倒计时挂在谁身上），JVM 侧既造不出来也不该造，只能注进来才测得到判据本身。
     * 生产接线传 [ReminderNotifications.ownsCountdownTo]，理由见
     * [shouldTakeDownClassProgress] 的论证 3。
     *
     * @return true 表示当场收了；false 表示这一节课正在进行、或它的课前倒计时正挂在屏幕上
     *   （那节课还没开始），这道撤销被跳过
     *   （调用方据此留一行取证日志，设备上看勿扰有没有又被抖一次）
     */
    internal fun takeDownClassProgressIfNeeded(
        courses: List<Course>,
        semesterStart: LocalDate,
        totalWeeks: Int?,
        timeSlots: List<TimeSlot>,
        classProgressEnabled: Boolean,
        dndEnabled: Boolean,
        now: LocalDateTime,
        countingDownTo: (courseId: Long, classStartMillis: Long) -> Boolean,
        tearDown: () -> Unit,
    ): Boolean {
        val due = shouldTakeDownClassProgress(
            courses = courses,
            semesterStart = semesterStart,
            totalWeeks = totalWeeks,
            timeSlots = timeSlots,
            classProgressEnabled = classProgressEnabled,
            dndEnabled = dndEnabled,
            now = now,
            countingDownTo = countingDownTo,
        )
        if (!due) return false
        tearDown()
        return true
    }

    /**
     * 纯函数：本轮没有课前提醒可排时，课堂侧那一揽子撤销该不该当场执行。
     *
     * 只有**此刻与课堂窗口有关**才放过：正在上这一节，或屏幕上正数着这一节（还没开始）的
     * 课前倒计时；其余情况照旧当场收干净：
     * - 两个课堂开关都关着：[ClassProgressScheduler.rescheduleNextWindow] 会因此早退，
     *   续排链根本不会跑，不当场收就永远收不掉；
     * - 挑不出课堂窗口（没有课 / 学期已结束）：此刻没有课在进行，收铃正是 R5 F-11 要的；
     * - 窗口还没开始、或已经结束：遗留的常驻通知与勿扰该收，
     *   用户把正在上的那门课删掉（重排后窗口对不上）也走这一条 ——
     *   **但"屏幕上正数着这节还没开始的课的课前倒计时"时不收**，见下面论证 1。
     *
     * 而"正在上课"时收干净的代价是**连自愈凭据一起抹掉**：勿扰记录与看门狗闹钟都在
     * [ClassProgressDnd] 那一侧，撤销走的 restore 会把记录清掉，之后
     * [ClassProgressDnd.selfCheck] 判的是"有残留记录才动手" —— 救不回来。
     * 偏偏"正在上课"说明这两样都是当下正要用的东西。
     *
     * 三个判据都不在这里重写：窗口口径是 [ClassProgressScheduler.planNextClassWindow]
     * （含正在上的这一节），"在不在上课"是 [ClassProgressScheduler.ClassWindow.ongoingAt]，
     * "在不在数这节课的课前倒计时"由 [countingDownTo] 注入（生产传
     * [ReminderNotifications.ownsCountdownTo]），带判据的那份清理实现仍然只有
     * [ClassProgressScheduler.rescheduleWindows] 一处（它在 `:280-291` 用的就是同一对状态）。
     * **"这一周在不在学期内"也不在这里重写**：它由 [totalWeeks] 递给窗口那一读，
     * 判据本体是 [TermWeekGate.isInTermWeek]（与展示侧同一把尺，见那枚内核的三条口径）。
     *
     * **论证 1 —— 为什么放过这一判不会变成"永远收不掉"**：能走到这里说明窗口就在眼前，
     * 而那一发上课铃不是别处来的：课前提醒的广播（`ReminderReceiver.kt:44-62`）在下发倒计时的
     * 同一次里就调 `rescheduleAll`，走到 [ClassProgressScheduler.rescheduleWindows] 把这一节的
     * 上课铃排成 `setAlarmClock`。"不收"保住的就是它 —— 反过来当场 `cancelAll` 会连它一起撤，
     * 而这条分支本来就没有下一条提醒，被撤掉的铃再没有任何人续排（这一节的实况与自动勿扰永久缺席）。
     * 铃一响，ACTION_START 以 IN_CLASS 重发实况、把倒计时归零，并排好下课铃，续排链因此在
     * 下一环重新判一次；归属对不上（课被删 / 时间被改）时 [countingDownTo] 直接返回 false，
     * 照收，R5 F-11 的清理语义一字未动。这与 `rescheduleWindows:281-285` 已经做的取舍是同一笔账。
     *
     * **论证 2 —— 为什么"两个开关都关"那一判必须排在倒计时判之前**：那条路上没有人在续排
     * （[ClassProgressScheduler.rescheduleNextWindow] 判完开关就 `return`，
     * [rescheduleAll] 下面那段也走 `cancelAll` 而不是重排），"等下一环重新判"这个前提不成立，
     * 收了才是终态；反过来若让倒计时判抢先，留下的就是一条永远不会再被更新的常驻通知加一个
     * 永不恢复的勿扰。倒计时只是"展示状态"，不是"有人在接手"的证据 —— 它是进程内的
     * `@Volatile`，重启即归零，撑不起"继续等"的承诺。
     *
     * **论证 3 —— 为什么只判归属、不用 [ReminderNotifications.isCountingDownTo]**：
     * 那只把 `System.currentTimeMillis()` 读在自己身上，而本函数"课还没开始"这件事判的是
     * 注入的 [now]（[planOnce] 与 `rescheduleWindows:267-269` 各有一段同类注释防的就是
     * 一次判断两只钟）。两个时刻都从真钟读，就会在跨秒那一瞬先掉个儿：注入的 now 说还没开始、
     * 真钟说已开始 ⇒ 守卫失效，当场拆掉的恰恰是"这一节正要开始、勿扰正要生效"的那一秒 ——
     * 与本卡要修的故障同形。[ReminderNotifications.ownsCountdownTo] 的归属是清得掉的
     * （ACTION_START 落地即归零），"归属还在 + 注入的 now 说没开始"这两条合起来就是
     * isCountingDownTo 想表达的内容，而且时间只由 [now] 读一次。
     *
     * [now] 一次判断只读一次时钟：选窗口、判"在不在上课"、以及喂给 [countingDownTo] 的
     * 开课毫秒全部由它派生（同 [planOnce] 的约束）。
     * 时区同一条规矩：下面那一手换算只现读一次（改前那一次藏在换算件 `toEpochMillis` 的
     * 默认参数位里，枚数一样，只是从尺眼下挪到了明面上）。
     */
    internal fun shouldTakeDownClassProgress(
        courses: List<Course>,
        semesterStart: LocalDate,
        totalWeeks: Int?,
        timeSlots: List<TimeSlot>,
        classProgressEnabled: Boolean,
        dndEnabled: Boolean,
        now: LocalDateTime,
        countingDownTo: (courseId: Long, classStartMillis: Long) -> Boolean,
    ): Boolean {
        if (!classProgressEnabled && !dndEnabled) return true
        val window = ClassProgressScheduler.planNextClassWindow(
            courses = courses,
            semesterStart = semesterStart,
            timeSlots = timeSlots,
            now = now,
            // 这一读也是排铃判据的一环（"还在不在上课"决定收不收勿扰），所以 totalWeeks 必须递进去：
            // 屏上画不到的那一周不算"正在上课"，也就没有"这一节的勿扰别动"可保护
            totalWeeks = totalWeeks,
        ) ?: return true
        // 换算件（domain/schedule/PeriodWindows.kt 里那两枚）的默认时区已摘 ⇒ 这里显式递一枚，
        // 且只现读这一次：下面那一判吃的就是它（尺在 reminder/CountdownClockSingleReadGuardTest ⑧）。
        val zone = ZoneId.systemDefault()
        if (window.ongoingAt(now.toEpochMillis(zone))) return false
        return !countingDownTo(window.courseId, window.startMillis)
    }

    /**
     * 一轮重排里对全量搜索的**唯一一次**调用。
     *
     * 入参只给一个时钟读数 [now]，`nowMillis` 由它换算 —— 不给"两个判据各读各的钟"
     * 留位置（同 [rescheduleAll] 里那段跨秒自相矛盾的注释）。
     *
     * [search] 是留给单测的注入缝：生产走 [planNextReminder]，单测换成计数版，
     * 就能钉住"一次重排只搜一轮、结论与内部 plan 同源"。
     */
    internal fun planOnce(
        courses: List<Course>,
        semesterStart: LocalDate,
        totalWeeks: Int?,
        timeSlots: List<TimeSlot>,
        reminders: Map<Long, ReminderSetting>,
        now: LocalDateTime,
        search: (
            List<Course>,
            LocalDate,
            Int?,
            List<TimeSlot>,
            Map<Long, ReminderSetting>,
            LocalDateTime,
            Long,
        ) -> ReminderPlan? = ::planNextReminder,
    ): ReminderPlan? {
        // 一次重排里时钟读数只有一只（[now]），时区同理只现读一次：换算件的默认时区已摘，
        // 这里显式递同一枚给 nowMillis。（再往下 planNextReminder / planTiedReminders 还各带一枚
        // `zone: ZoneId = ZoneId.systemDefault()` 默认参数 —— 那是本卡范围外的同一形，登记为残账。）
        val zone = ZoneId.systemDefault()
        return search(courses, semesterStart, totalWeeks, timeSlots, reminders, now, now.toEpochMillis(zone))
    }

    /**
     * 纯函数：挑出「下一个应触发的提醒」那一**等**的全部课程。
     *
     * - 按“触发时间”（上课时间 - 提前量）排序，而不是上课时间本身——
     *   不同课程提前量不同时，触发顺序和上课顺序可能不一致；
     * - 触发时间已过的候选直接跳过（哪怕课还没上），不补发陈旧提醒。
     *   这样上一次提醒触发后重新调度，会正确落到下一门课，链条不会中断。
     * - **并列的那一等整体交出来**，不再只回头名：以前这里是 `.minByOrNull { it.triggerAtMillis }`，
     *   而 Kotlin 的 `minByOrNull` 取的是**第一个**最小键，于是"两门课同一时刻开课"时
     *   只有数据库行序排在前面的那门能被挑走，另一门等下一次重排时触发时刻已经过了 ⇒ 被上面
     *   那条"已过即跳"判掉 ⇒ 这一节课它一声不响。判据过严同样是错：不并列的候选必须继续各排各的，
     *   所以返回的是"最小键那一等"，不是"全部候选"。
     *
     * 零 android、零时钟：[now] / [nowMillis] / [zone] 全由参数给（本仓收单硬口径）。
     * 学期边界同样**当参数给**（[semesterStart] + [totalWeeks]）：本函数与它调的
     * [nextOccurrence] 都不读库、不读钟，也不许在这里再写一遍 `week <= totalWeeks`
     * —— 那一判只有 [TermWeekGate.isInTermWeek] 一枚，而它委托给展示侧同一把尺。
     *
     * 提醒那一档同样当参数给（[reminders] 就是那一行，可能没有）：三档判定
     * （有行且开 / 有行但关 / 没有行按默认）的唯一实现是
     * [EffectiveReminderSetting.of]，这里不写第二份 `?:` 兜底，结论的出处带在
     * [ReminderPlan.settingBasis] 上。
     *
     * @param totalWeeks 学期总周数；null = 没有学期行可问 ⇒ [TermWeekGate] 答"不放行"
     *   （排铃侧与展示侧在这一档故意不对称，理由写在 [TermWeekGate] 的第 3 条口径）
     *
     * @return 等首在前的那一等（等首的 [ReminderPlan.sameInstantPartners] 带着其余各门）；
     *   没有可排候选时是**空表**（不是 null —— 这条判据不再需要"空表还是 null"的第二档）。
     *   次序按课程 id 升序，那只是**槽位分配**的稳定序：同一节课表重排两次落到同样的槽位上，
     *   它不取舍任何一门课（取舍只有"触发时刻已过"、"提醒被关掉"与"周次全在学期外"三条）。
     */
    fun planTiedReminders(
        courses: List<Course>,
        semesterStart: LocalDate,
        totalWeeks: Int?,
        timeSlots: List<TimeSlot>,
        reminders: Map<Long, ReminderSetting>,
        now: LocalDateTime,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ReminderPlan> {
        val slots = (if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT)
            .toStartEndTimes()
        val gapMinutes = periodGapMinutesOf(slots)
        val candidates = courses.asSequence()
            .mapNotNull { course ->
                // 三档只此一份取法（改前这里是两行：`if (setting != null && !setting.enabled) return` +
                // `setting?.advanceMinutes ?: 10` —— 库里没有那一行时前一句放行、后一句造出一枚提前量，
                // 于是"没设过"被读成"设了、且开着"，而且这两处都说不清自己是哪一档）
                val effective = EffectiveReminderSetting.of(reminders[course.id])
                if (!effective.enabled) return@mapNotNull null
                val occurrence = nextOccurrence(course, semesterStart, totalWeeks, slots, gapMinutes, now)
                    ?: return@mapNotNull null
                val advanceMinutes = CourseConstraints.normalizeAdvanceMinutes(effective.advanceMinutes)
                val triggerAt = occurrence.classStart.atZone(zone).toInstant().toEpochMilli() -
                    advanceMinutes * 60_000L
                if (triggerAt <= nowMillis) return@mapNotNull null
                ReminderPlan(
                    course = course,
                    segment = occurrence.segment,
                    classStart = occurrence.classStart,
                    advanceMinutes = advanceMinutes,
                    triggerAtMillis = triggerAt,
                    week = occurrence.week,
                    settingBasis = effective.basis,
                )
            }
            .toList()
        if (candidates.isEmpty()) return emptyList()
        val earliest = candidates.minOf { it.triggerAtMillis }
        val tied = candidates.filter { it.triggerAtMillis == earliest }.sortedBy { it.course.id }
        val partners = tied.drop(1)
        // 只有等首带伙伴（伙伴自己那格留空表，否则 toString/equals 互指绕无穷圈）
        return listOf(tied.first().copy(sameInstantPartners = partners)) + partners
    }

    /**
     * 纯函数：并列那一等里本轮真排得上的有哪几枚、被槽位上限挤掉的是几门。
     *
     * 单独一格是为了让"槽位不够"这件事能被表驱动钉住：藏在排程那段里，JVM 侧既造不出
     * Context 也读不到那句 `Log.w`，超限就只能等真机上去猜。
     * 裁的是**表尾**（等首永远在第 0 格，槽位 0 一定用它），被裁掉的门数由调用方留痕。
     */
    internal fun allocateReminderSlots(tied: List<ReminderPlan>, maxSlots: Int): ReminderSlotting {
        val safeMax = maxSlots.coerceAtLeast(0)
        val armed = tied.take(safeMax)
        return ReminderSlotting(armed = armed, droppedCount = tied.size - armed.size)
    }

    /** [allocateReminderSlots] 的结论：排得上的（按槽位序，0 起）与被槽位上限挤出去的门数 */
    internal data class ReminderSlotting(val armed: List<ReminderPlan>, val droppedCount: Int)

    /**
     * 纯函数：挑选下一个应触发的提醒（= [planTiedReminders] 那一等的等首）。
     *
     * 保留这个形状是因为「本轮还有没有待触发的提醒」这种问句只要一个头名就够，
     * 而一次重排的真排程要走 [planTiedReminders] 拿整等（少拿整等就是并列吃课那件事）。
     */
    fun planNextReminder(
        courses: List<Course>,
        semesterStart: LocalDate,
        totalWeeks: Int?,
        timeSlots: List<TimeSlot>,
        reminders: Map<Long, ReminderSetting>,
        now: LocalDateTime,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReminderPlan? = planTiedReminders(
        courses, semesterStart, totalWeeks, timeSlots, reminders, now, nowMillis, zone,
    ).firstOrNull()

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // FLAG_NO_CREATE：只取"已存在"的那个来撤销。
        // 之前用 FLAG_UPDATE_CURRENT，等于每次取消都新建一个永不使用的 PendingIntent。
        // （PendingIntent 判等基于 Intent.filterEquals，不含 extras，因此这里不需要拼 extras）
        // 槽位要一整段一起撤：并列时一轮排出去的是好几枚，只撤第 0 格就是
        // "重排过一遍，落榜那几门的旧闹钟还挂在系统里，到点念一节已经不存在的课"。
        for (slot in reminderSlots()) {
            existingReminderSlot(context, slot)?.let { alarmManager.cancel(it) }
        }
    }

    /**
     * 课前提醒那条闹钟是否还挂着。用 [PendingIntent.FLAG_NO_CREATE] **只查不造**，
     * 与 [cancelAll] 共用同一个 builder（同一份 requestCode 槽位窗口 + 同一个 Intent ——
     * 判等靠 `Intent.filterEquals`，所以不必拼 extras）。
     *
     * 并列之后问的是"这一段槽位里**有没有**还挂着的"，不是"第 0 格挂着没有"：
     * 只查第 0 格会把"另外几门还排着"读成"一条都没有"。
     *
     * 供冷启动判据的钥匙 2 用（`com.buaa.schedule.widget.ColdStartRebuild`）：
     * 那里写着"探得到 PI 逻辑上不等于闹钟还在排"这条实测反向事实，以及为什么
     * 在冷启动这个时点可以接受 —— 别在这里再写一遍。
     */
    internal fun hasPendingReminder(context: Context): Boolean =
        reminderSlots().any { existingReminderSlot(context, it) != null }

    /** 本模块的全部课前提醒槽位号：排、撤、查三条路共用这一份枚举（口径只在 [REQUEST_CODE_REMINDER] 一处） */
    private fun reminderSlots(): IntRange = 0 until MAX_TIED_REMINDERS

    /**
     * 槽位 → [PendingIntent] 的**唯一** builder：[armOneReminder]（排）、
     * [existingReminderSlot]（撤与查）都从这一格过。
     *
     * 判等靠 requestCode + `Intent.filterEquals`，而 `filterEquals` **不看 extras**，
     * 所以"只查不造"那一面（[PendingIntent.FLAG_NO_CREATE]）传 null 当 plan 也能对上排出去的那一枚。
     * 两处各拼一份 requestCode 与 Intent（或一处拼一处不拼）就是这枚常量当初警告的坑：
     * 查的那一侧永远查不到排的那一侧，而且查不出错。
     */
    private fun reminderSlotIntent(
        context: Context,
        slot: Int,
        plan: ReminderPlan?,
        flags: Int,
    ): PendingIntent {
        val intent = baseIntent(context)
        if (plan != null) intent.putExtras(preClassWindow(plan).toExtras())
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REMINDER + slot,
            intent,
            flags,
        )
    }

    /** 只查不造的那一面：撤闹钟与「闹钟还挂着吗」都读它，槽位号与 Intent 与排出去的那一枚同源 */
    private fun existingReminderSlot(context: Context, slot: Int): PendingIntent? =
        reminderSlotIntent(
            context = context,
            slot = slot,
            plan = null,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun baseIntent(context: Context): Intent = Intent(context, ReminderReceiver::class.java)

    /**
     * 课前那一段的实况窗口：与上课铃共用一套 extras 序列化，才不会又只补齐其中一条链。
     *
     * 课前这一段进度条量的是"这段等待过去了多少"：收铃时刻就是上课时刻，
     * 所以 start/end 先都填上课时间，真正开跑时由接收器把 start 改成"此刻"。
     */
    private fun preClassWindow(plan: ReminderPlan): ClassProgressScheduler.ClassWindow {
        val classStartMillis = plan.classStart
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return ClassProgressScheduler.ClassWindow(
            courseId = plan.course.id,
            courseName = plan.course.displayName,
            location = plan.course.location,
            sectionText = periodLabel(plan.segment),
            startMillis = classStartMillis,
            endMillis = classStartMillis,
            // 以下四项是课前倒计时那条实况此前缺的全部信息：
            // 与上课铃共用一套 extras 序列化，才不会又只补齐其中一条链。
            teacher = plan.course.teacher,
            week = plan.week,
            dayOfWeek = plan.classStart.dayOfWeek.value,
            colorArgb = courseColor(plan.course).toArgb(),
        )
    }

    /**
     * 该课程「下一次将要开始」的那一段：周次升序 × 段升序即时间升序，取第一个晚于 now 的。
     *
     * 必须按**节次段**枚举，口径与 [ClassProgressScheduler.planNextClassWindow] 一致。
     * 此前它只取整门课第一节课的时间，于是 `[1,2,9,10]` 这种跨午休的课：上午那段一上课，
     * 本周就再没有"晚于 now 的第一节"，挑到的下一次直接跳到**下周**——下午那节的课前提醒
     * 整学期都不会发。节次文案同理，见 [preClassWindow]。
     *
     * 缺时间表时跳过该段，不兜底成 08:00：那会凭空造出一节 8 点的课，
     * 让一门没有任何时间信息的课每天早上被提醒一次（与课堂窗口同一取舍）。
     *
     * **周次先过 [TermWeekGate.isInTermWeek] 再生成日期**（T214 病灶 A，与
     * [ClassProgressScheduler] 的 `nextOrCurrentWindow` 同一判据、同一条链）：
     * 超出 `totalWeeks` 的那几周在周课表上是空的（展示侧 `WeekCalculator.currentWeekOrNull` 判 null），
     * 改前这里照旧 `plusWeeks(week - 1)` 生成日期、照旧排闹钟。越界周次是**跳过**，
     * 不是把整门课丢掉 —— `weeks = [1, 2, 17]` 的课在第 1、2 周继续照响。
     *
     * 日期那一行保持 `semesterStart.plusWeeks(week - 1)` 原样（锚点归一是
     * `domain/model/Semester.kt` 的 `startLocalDate` 那一处的活儿，改它属于另一张卡）：
     * 生产递进来的学期起点一律已过 `mondayOfWeekAnchor`，与判据内部那一周周一无关紧要地一致。
     */
    private fun nextOccurrence(
        course: Course,
        semesterStart: LocalDate,
        totalWeeks: Int?,
        slots: Map<Int, Pair<LocalTime, LocalTime>>,
        gapMinutes: (Int, Int) -> Long?,
        now: LocalDateTime,
    ): Occurrence? {
        // **星期那一维也要过尺**（#524，T275 审计 N1，与 [ClassProgressScheduler] 的
        // `nextOrCurrentWindow` 同一判据、同一条链）：改前这里判完周号就直接
        // `(course.dayOfWeek - 1)` 推日期，脏星期（教务返回过 0 / 8）于是被推成**相邻那一周**的
        // 真实日子（0 → 上一周的周日、8 → 下一周的周一），屏上根本没有这门课的那一天照样响课前提醒。
        // 判据只在 [WeekdayDateOffset] 那一处，这里不写第二遍；两档 Rejected 一律「算不出下一次上课」
        // = 交回 null，与同函数「没有学期起点就不排铃」同一口径（离开本应用的数据宁少不吵）。
        val dayTier = WeekdayDateOffset.dayOffsetOrReject(course.dayOfWeek)
        if (dayTier !is WeekdayOffset.Accepted) return null
        val segments = course.periods.toPeriodSegments(gapMinutes)
        for (week in course.weeks.sorted()) {
            if (!TermWeekGate.isInTermWeek(week, semesterStart, totalWeeks)) continue
            val date = semesterStart.plusWeeks((week - 1).toLong())
                .plusDays(dayTier.offsetDays)
            for (segment in segments) {
                val start = slots[segment.first]?.first ?: continue
                val dateTime = date.atTime(start)
                if (dateTime.isAfter(now)) return Occurrence(segment, dateTime, week)
            }
        }
        return null
    }

    /** 一次未来上课的开始：节次段 + 时刻 + 教学周 */
    private data class Occurrence(
        val segment: IntRange,
        val classStart: LocalDateTime,
        val week: Int,
    )

}
