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
import com.buaa.schedule.domain.schedule.toEpochMillis
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 课程提醒调度器。
 *
 * 为每门启用提醒的课程计算“触发时间 = 下次上课时间 - 提前量”，
 * 从中选出最近的未来一次设置闹钟；课程表变化后重新调用 rescheduleAll 即可。
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    private const val DEFAULT_ADVANCE_MINUTES = 10

    /**
     * 课前提醒闹钟的 requestCode（全仓库唯一一条，排与查共用它）。
     * 写成常量而不是两处各写一个 `0`：判等靠的是 requestCode + Intent.filterEquals，
     * 两边对不上就是"查得到一条永远不存在的闹钟"这种查不出来的错。
     */
    private const val REQUEST_CODE_REMINDER = 0

    data class ReminderPlan(
        val course: Course,
        /** 本次提醒对应的那个**连续节次段**（跨午休的课一段一次），不是整门课的节次 */
        val segment: IntRange,
        val classStart: LocalDateTime,
        val advanceMinutes: Int,
        val triggerAtMillis: Long,
        /** 这一次上课落在第几周：课前实况的「第 N 周」取它，与 [segment] 同为"这一次"的属性 */
        val week: Int,
    )

    /**
     * 重排课前提醒与上/下课铃。
     *
     * @return 本轮排上的那一条课前提醒；null 表示本轮没有待触发的课前提醒
     *   （没配学期 / 课表为空 / 学期已结束 / 提醒全关）。
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
     */
    fun rescheduleAll(
        context: Context,
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        reminders: Map<Long, ReminderSetting> = emptyMap(),
    ): ReminderPlan? {
        val semesterStart = semester?.startLocalDate
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
        val plan = planOnce(courses, semesterStart, timeSlots, reminders, now)
        cancelAll(context)
        if (plan == null) {
            // 没有下一条提醒（提醒全关 / 学期已结束）**不等于**课堂侧状态也该当场收干净。
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
            val pendingIntent = createPendingIntent(context, plan)
            // Android 12+ 精确闹钟是特殊权限（默认拒绝），未授予时降级为不精确闹钟，避免崩溃。
            // canScheduleExactAlarms() 本身也可能抛 SecurityException（权限被运行期撤销），
            // 因此整段调度都套上 runCatching 兜底，失败只是这一次不精确，不影响其余链路。
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
            runCatching {
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pendingIntent)
                }
            }.onFailure { Log.w(TAG, "调度课程提醒失败", it) }
        }

        // 上/下课铃与常驻通知（"课程进行中"）和「上课自动勿扰」是两个独立开关，
        // 但共用同一对上课/下课闹钟：任一开启都必须排铃，接收器里再按开关决定做什么。
        // 此前任一开关关闭就直接不排铃，导致"只开勿扰、关掉常驻通知"的用户整条链路
        // 都不跑 —— 表现为上课自动勿扰"好像没用"。两个开关取函数开头读的那一份。
        if (classProgress || dndEnabled) {
            // 口径与下课铃广播共用 ClassProgressScheduler.rescheduleWindows
            ClassProgressScheduler.rescheduleWindows(context, courses, semesterStart, timeSlots)
        } else {
            // 两个开关都关了：连常驻通知 / 勿扰遗留状态一起收干净，
            // 别留着一条不会再更新的通知或一个永不恢复的勿扰
            ClassProgressScheduler.cancelAll(context)
        }
        return plan
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
     */
    internal fun shouldTakeDownClassProgress(
        courses: List<Course>,
        semesterStart: LocalDate,
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
        ) ?: return true
        if (window.ongoingAt(now.toEpochMillis())) return false
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
        timeSlots: List<TimeSlot>,
        reminders: Map<Long, ReminderSetting>,
        now: LocalDateTime,
        search: (
            List<Course>,
            LocalDate,
            List<TimeSlot>,
            Map<Long, ReminderSetting>,
            LocalDateTime,
            Long,
        ) -> ReminderPlan? = ::planNextReminder,
    ): ReminderPlan? = search(courses, semesterStart, timeSlots, reminders, now, now.toEpochMillis())

    /**
     * 纯函数：挑选下一个应触发的提醒。
     *
     * - 按“触发时间”（上课时间 - 提前量）排序，而不是上课时间本身——
     *   不同课程提前量不同时，触发顺序和上课顺序可能不一致；
     * - 触发时间已过的候选直接跳过（哪怕课还没上），不补发陈旧提醒。
     *   这样上一次提醒触发后重新调度，会正确落到下一门课，链条不会中断。
     */
    fun planNextReminder(
        courses: List<Course>,
        semesterStart: LocalDate,
        timeSlots: List<TimeSlot>,
        reminders: Map<Long, ReminderSetting>,
        now: LocalDateTime,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReminderPlan? {
        val slots = (if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT)
            .toStartEndTimes()
        val gapMinutes = periodGapMinutesOf(slots)
        return courses.asSequence()
            .mapNotNull { course ->
                val setting = reminders[course.id]
                if (setting != null && !setting.enabled) return@mapNotNull null
                val occurrence = nextOccurrence(course, semesterStart, slots, gapMinutes, now)
                    ?: return@mapNotNull null
                val advanceMinutes =
                    CourseConstraints.normalizeAdvanceMinutes(setting?.advanceMinutes ?: DEFAULT_ADVANCE_MINUTES)
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
                )
            }
            .minByOrNull { it.triggerAtMillis }
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // FLAG_NO_CREATE：只取"已存在"的那个来撤销。
        // 之前用 FLAG_UPDATE_CURRENT，等于每次取消都新建一个永不使用的 PendingIntent。
        // （PendingIntent 判等基于 Intent.filterEquals，不含 extras，因此这里不需要拼 extras）
        existingPendingIntent(context)?.let { alarmManager.cancel(it) }
    }

    /**
     * 课前提醒那条闹钟是否还挂着。用 [PendingIntent.FLAG_NO_CREATE] **只查不造**，
     * 与 [cancelAll] 共用同一个 builder（同一份 requestCode + 同一个 Intent ——
     * 判等靠 `Intent.filterEquals`，所以不必拼 extras）。
     *
     * 供冷启动判据的钥匙 2 用（`com.buaa.schedule.widget.ColdStartRebuild`）：
     * 那里写着"探得到 PI 逻辑上不等于闹钟还在排"这条实测反向事实，以及为什么
     * 在冷启动这个时点可以接受 —— 别在这里再写一遍。
     */
    internal fun hasPendingReminder(context: Context): Boolean = existingPendingIntent(context) != null

    private fun existingPendingIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE_REMINDER,
        baseIntent(context),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun baseIntent(context: Context): Intent = Intent(context, ReminderReceiver::class.java)

    private fun createPendingIntent(context: Context, plan: ReminderPlan): PendingIntent {
        val classStartMillis = plan.classStart
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 课前这一段实况量的是"这段等待过去了多少"：收铃时刻就是上课时刻，
        // 所以 start/end 先都填上课时间，真正开跑时由接收器把 start 改成"此刻"。
        val window = ClassProgressScheduler.ClassWindow(
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
        val intent = baseIntent(context).apply { putExtras(window.toExtras()) }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REMINDER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 该课程「下一次将要开始」的那一段：周次升序 × 段升序即时间升序，取第一个晚于 now 的。
     *
     * 必须按**节次段**枚举，口径与 [ClassProgressScheduler.planNextClassWindow] 一致。
     * 此前它只取整门课第一节课的时间，于是 `[1,2,9,10]` 这种跨午休的课：上午那段一上课，
     * 本周就再没有"晚于 now 的第一节"，挑到的下一次直接跳到**下周**——下午那节的课前提醒
     * 整学期都不会发。节次文案同理，见 [createPendingIntent]。
     *
     * 缺时间表时跳过该段，不兜底成 08:00：那会凭空造出一节 8 点的课，
     * 让一门没有任何时间信息的课每天早上被提醒一次（与课堂窗口同一取舍）。
     */
    private fun nextOccurrence(
        course: Course,
        semesterStart: LocalDate,
        slots: Map<Int, Pair<LocalTime, LocalTime>>,
        gapMinutes: (Int, Int) -> Long?,
        now: LocalDateTime,
    ): Occurrence? {
        val segments = course.periods.toPeriodSegments(gapMinutes)
        for (week in course.weeks.sorted()) {
            val date = semesterStart.plusWeeks((week - 1).toLong())
                .plusDays((course.dayOfWeek - 1).toLong())
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
