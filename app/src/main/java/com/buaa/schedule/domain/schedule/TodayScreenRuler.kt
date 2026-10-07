package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course

/**
 * 今日页那一屏「用哪把尺」的**唯一**判据内核（#344 / 卡面 T264-01）。
 *
 * ## 病（用户每天看的那一屏）
 *
 * 没设学期时，同一枚 [com.buaa.schedule.ui.home.DayView] 里三处各读各的尺：
 * 列表按「星期几」画课（改前那三档 `when` 的第一档 `semester == null -> courses.filter { it.dayOfWeek == … }`）、
 * Hero 走 [TodayPlanner] 那把「学期 + 周次」尺（改前起手 `?: return EMPTY`，没学期直接空），
 * 而 Hero 那扇门是按**列表**那把尺开的（改前 `dayCourses.isNotEmpty()`）。
 * 于是库里真有 14:00 那节课、用户只是没设学期时，屏上是：列表挂着 14:00 那张卡，
 * Hero 却念「今天没有更多课了」——一句谎话压在一张真的卡上。
 *
 * ## 修法：三处吃同一枚结论，尺还是那两把在册尺
 *
 * 内核**不新造第三把尺**，它只做「这一屏用哪一把、门开不开、Hero 念哪句」这一件事：
 *
 * - [DayCourseScope.TermWeek]：学期在、那一周取得到 ⇒ 「学期 + 周次」那把尺
 *   （周号由调用点从 [WeekCalculator.currentWeekOrNull] 算好后交回，本文件一次都没调它）。
 * - [DayCourseScope.WeekdayOnly]：没设学期 ⇒ 「星期几」那把尺。**这是裁定的甲档**：
 *   Hero 退到「按星期几 + 当前时刻」那一档，与列表同源，于是「没有更多课了」只在真的排完时才念；
 *   新用户手动加的课照旧可见（改前 :605 那档自己写明的产品意图，本卡不许顺手关掉）。
 * - [DayCourseScope.OutOfTerm]：学期在、开学日解析不出或那一周取不到（含 `totalWeeks = 0`）
 *   ⇒ 两把尺都不放行。**这一档是同一枚 `when` 的下一档已经判过的形状**（改前注释：
 *   「列表不能再放行全部周次，否则"列表有课、Hero 与进度条全空"同屏打架」），本卡照它同族，
 *   只是把「谁窄听谁的」这条判断从 DayView 的 `when` 里搬进内核，让三处都吃它。
 *
 * ## 零时钟（本仓成文禁令）
 *
 * 函数体里没有 `LocalDate.now()` / `LocalTime.now()` / `ZoneId` / `Build.VERSION` / 权限读取，
 * 也没有任何 android import：当前时刻落点由调用点把**已算好的** [TodayPlan] 递进来（那是
 * [TodayPlanner] 吃 `now` 参数的产物），有没有学期、那一周是第几周、两把尺各自命中了哪些课同理。
 *
 * ## 不许折成 Boolean
 *
 * 「门开不开」与「念哪句」是一张 sealed 档位 [TodayHeroGate] 的两面，不是一个布尔：
 * 布尔会把「假期里没有课」与「课全上完了」压成同一句假话，正是本卡的病灶形状。
 */
object TodayScreenRuler {

    /**
     * 这一屏用哪把尺。
     *
     * @param hasSemester 有没有学期行（调用点 `semester != null`，内核不查库）
     * @param termWeek 「学期 + 周次」那把尺算出来的那一周；null = 那一周取不到
     *                 （开学日解析失败、开学前 / 假期、`totalWeeks = 0` —— 判据本体在
     *                 [WeekCalculator.currentWeekOrNull]，本函数不重述它）
     */
    fun scopeOf(hasSemester: Boolean, termWeek: Int?): DayCourseScope = when {
        !hasSemester -> DayCourseScope.WeekdayOnly
        termWeek == null -> DayCourseScope.OutOfTerm
        else -> DayCourseScope.TermWeek(termWeek)
    }

    /**
     * 列表该画哪一批课：内核选定尺之后，那一把尺命中的课。
     *
     * 两把尺的命中集都由调用点**预先算好**交进来（`weekdayCourses` ⊇ `termWeekCourses` 是界面那侧
     * 的构造顺序，内核不重算包含关系，只按档位取一份），于是「屏上有几张卡」这一问只有一个答案。
     */
    fun coursesFor(
        scope: DayCourseScope,
        weekdayCourses: List<Course>,
        termWeekCourses: List<Course>,
    ): List<Course> = when (scope) {
        is DayCourseScope.TermWeek -> termWeekCourses
        DayCourseScope.WeekdayOnly -> weekdayCourses
        DayCourseScope.OutOfTerm -> emptyList()
    }

    /**
     * Hero 那扇门开不开、开了念哪一句。
     *
     * @param isToday 只有「今天」那一页有 Hero；翻到别的一天时 [plan] 本来就是 null
     * @param hasCards 列表有没有卡（[coursesFor] 的结论非空）——**门与列表同源**就是这一枚参数，
     *                 调用点不许拿另一把尺的命中数来开门
     * @param plan 当前时刻落点：[TodayPlanner] 吃同一把尺那一批课算好的产物；null / 空课次 = 判不了
     */
    fun heroGateOf(
        scope: DayCourseScope,
        isToday: Boolean,
        hasCards: Boolean,
        plan: TodayPlan?,
    ): TodayHeroGate = when {
        !isToday -> TodayHeroGate.Closed(TodayHeroAbsence.NOT_TODAY)
        // 假期 / 开学日坏 / totalWeeks = 0：两把尺都不放行，列表与门一起收着
        scope is DayCourseScope.OutOfTerm -> TodayHeroGate.Closed(TodayHeroAbsence.OUT_OF_TERM)
        // 这一天真没课：空态卡已经把这句话说了，Hero 再念一遍是重复（M2 那条既有裁定）
        !hasCards -> TodayHeroGate.Closed(TodayHeroAbsence.NO_CARDS)
        // 列表有卡、课次却一节都没算出来（节次表空 / 段没有上课时间）：这是"判不了"，
        // 不是"上完了" —— 关着比念「今天没有更多课了」诚实
        plan == null || plan.slots.isEmpty() -> TodayHeroGate.Closed(TodayHeroAbsence.PLAN_CANNOT_SPEAK)
        else -> when {
            plan.ongoing != null -> TodayHeroGate.Ongoing(plan.ongoing, plan.minutesRemaining)
            plan.next != null -> TodayHeroGate.Next(plan.next, plan.minutesToNext)
            else -> TodayHeroGate.NothingMore
        }
    }
}

/**
 * 今日页那一屏用的尺（三档，见 [TodayScreenRuler] 的 KDoc）。
 *
 * 它是「这一屏走哪把尺」的结论，不是判据本体：周号仍出自 [WeekCalculator]，
 * 「这一周在不在学期内」仍出自 [TermWeekGate]，本类型不重述任何比较式。
 */
sealed class DayCourseScope {

    /** 学期在、那一周取得到：按「学期 + 周次」那把尺 */
    data class TermWeek(val week: Int) : DayCourseScope()

    /** 没设学期：按「星期几」那把尺（新用户的手动课靠它才在屏上） */
    data object WeekdayOnly : DayCourseScope()

    /** 学期在、那一周取不到：两把尺都不放行（假期 / 开学日脏数据 / `totalWeeks = 0`） */
    data object OutOfTerm : DayCourseScope()
}

/** Hero 那一档结论：门开不开 + 开了念哪句，一枚 sealed 里两码事不许各自成立 */
sealed class TodayHeroGate {

    /** 门开着，且此刻正在上课：念「正在上课」 */
    data class Ongoing(val slot: TodayCourseSlot, val minutesRemaining: Long?) : TodayHeroGate()

    /** 门开着，还没上课：念「下一节」 */
    data class Next(val slot: TodayCourseSlot, val minutesToNext: Long?) : TodayHeroGate()

    /**
     * 门开着，今天的课真的排完了：才许念「今天没有更多课了」。
     *
     * 能走到这一档的前提是 [TodayScreenRuler.heroGateOf] 已经核过「列表有卡」与「课次算得出来」，
     * 也就是这一句说出口时，屏上确实没有还没上的课了。
     */
    data object NothingMore : TodayHeroGate()

    /** 门关着：整块 Hero 不出现 */
    data class Closed(val reason: TodayHeroAbsence) : TodayHeroGate()

    /**
     * 门开不开（[TodayScreenRuler.heroGateOf] 的结论，调用点只读不判）。
     */
    val isOpen: Boolean get() = this !is Closed
}

/** [TodayHeroGate.Closed] 的分档原因：四档各有各的诚实说法，不许折成一句 */
enum class TodayHeroAbsence {
    /** 翻到了别的一天，Hero 本来就不属于这一屏 */
    NOT_TODAY,

    /** 学期在、那一周取不到（假期 / 开学日脏数据 / `totalWeeks = 0`）：两把尺都不放行 */
    OUT_OF_TERM,

    /** 这一天一门课都没有：空态卡已经把这句话说了 */
    NO_CARDS,

    /** 列表挂着卡、课次却一节都没算出来：判不了，没资格说「没有更多课了」 */
    PLAN_CANNOT_SPEAK,
}
