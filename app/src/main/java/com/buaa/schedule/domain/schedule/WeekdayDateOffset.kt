package com.buaa.schedule.domain.schedule

/**
 * 「这门课的星期序号换算不换算得出日期」的**唯一**判据（#524，T275 审计 N1）。
 *
 * 纯 JVM 内核：进来的是一枚 `Int`，出去的是 **sealed 档位**（不是 Boolean —— 本仓成文禁令：
 * 把「越界」折成 Boolean 之后每一枚调用点都得自己再问一遍「越的是哪一头、本来会推到哪天」，
 * 那就是同一把尺写三遍的第二种写法）。内核里没有时钟、没有时区、没有权限、没有
 * `Build.VERSION` / `SDK_INT`，也不读库 —— 三处推导与写侧那一判拿到的都是同一个纯函数结果。
 *
 * ## 病在哪（三处日期推导都吃裸 `course.dayOfWeek`）
 *
 * 改前两处形状逐字相同（`ScheduleOccurrences.build` / `ClassProgressScheduler.nextOrCurrentWindow`
 * / `ReminderScheduler.nextOccurrence`）：
 *
 * ```
 * val date = semesterStart.plusWeeks((week - 1).toLong())
 *     .plusDays((course.dayOfWeek - 1).toLong())   // ← 这一手没有护栏
 * ```
 *
 * 同一条链上的**周号**先过了 `TermWeekGate.isInTermWeek`，但那枚判据的参数表里根本没有星期
 * （它自己走 `SemesterWeekDates.mondayOf(start, week) ?: return false`，只对 `week < 1` 交 null）。
 * 展示侧每一枚内核倒是自己拦脏星期（`SemesterStats.dayLoads`、`WeekDaySchedule`、`WeekFreeGrid`、
 * `WeeklyLoadTrend` 各写一遍 `if (day !in 1..SemesterStats.TOTAL_DAYS) continue`，
 * 而 `SemesterStats` 那里逐字写着「脏星期序号（教务确实返回过 0 / 8）」）。于是
 * **屏上一格都画不出来的那一行，日期照推、日历照写、铃照响**。
 *
 * ## 0 与 8 不对称：差在哪一行
 *
 * 两侧都是同一枚 `(dayOfWeek - 1)` 决定的，没有任何一处夹取，所以符号就是判据：
 * - `dayOfWeek = 0` → 偏移 `-1` → **上一周的周日**（学期第 1 周那一门课会推到开学日之前）；
 * - `dayOfWeek = 8` → 偏移 `+7` → **下一周的周一**（最后一周那一门课会推到学期之外）。
 *
 * 两档都落在「这门课自己的 `weeks` 过得了周号尺」的那一周之外，方向相反 ⇒
 * 档位也分两枚（[WeekdayOffset.BeforeWeek] / [WeekdayOffset.AfterWeek]），各自原样交回
 * 「改前那一手本来会加上的天数」，调用点与测试都念得出偏到哪一侧，不必自己重算。
 *
 * ## 谁问这一枚尺（第⓪问裁的丁档）
 *
 * - **读侧三处**（本课次展开 + 排铃两条链）：拿到 [WeekdayOffset.Accepted] 才推日期，
 *   两档 Rejected 一律跳过 —— 导出的日程与闹钟都是「离开本应用的数据」，判不出就不写，
 *   与 `TermWeekGate` 那一档「判不出 ⇒ 不放行」同口径。
 * - **写侧一处**（[CourseConstraints.normalize]）：同一枚内核，脏星期整行判死（既有口径不变）。
 *   读侧护栏**不能省**：库里已有的脏行有三条会把它原样写回去的路
 *   （`ImportPlanner.buildImportPlan` 末行 `existing.filter { it.isManualOverride }` 不过 normalize、
 *   撤销链 `courseDao.update(snapshot.toEntity())` 自己注释着「原样写回，不再过 normalize」、
 *   Room 迁移 7→8 的 `INSERT INTO courses_new SELECT … FROM courses` 逐行搬、`courses` 表
 *   对 `dayOfWeek` 零 CHECK），而 normalize 只会**整行判死**、不会**修** —— 一门已经躺在库里的
 *   `dayOfWeek = 0` 谁也救不回来，能救用户日历与闹钟的只有读侧这一判。
 */
sealed class WeekdayOffset {

    /** 送进来的那枚星期序号（原样留着，档位里不藏数） */
    abstract val dayOfWeek: Int

    /**
     * 相对该教学周周一的天偏移。
     *
     * [Accepted] 那一档是 `0..6`；两档 Rejected 交回的是**改前那一手本来会加上去的那个数**
     * （`0` → `-1`、`8` → `+7`），这样「屏上没有这门课、日历里却多出哪天」这件事在测试与
     * 日志里都说得出具体的日子，而不是只剩一句「越界了」。
     */
    abstract val offsetDays: Long

    /** 合法：星期序号落在 [WeekdayDateOffset.LEGAL_DAY_RANGE] 内 */
    data class Accepted(override val dayOfWeek: Int, override val offsetDays: Long) : WeekdayOffset()

    /** 越下界（`dayOfWeek < 1`）：偏移为负 ⇒ 那一手会把课推到**上一周的周日** */
    data class BeforeWeek(override val dayOfWeek: Int, override val offsetDays: Long) : WeekdayOffset()

    /** 越上界（`dayOfWeek > 7`）：偏移 ≥ 7 ⇒ 那一手会把课推到**下一周的周一** */
    data class AfterWeek(override val dayOfWeek: Int, override val offsetDays: Long) : WeekdayOffset()
}

/**
 * [WeekdayOffset] 的算法本体。单独一枚 `object` 是为了让「星期那一维的尺」在源码里有一个
 * 可以逐字节点名的宿主（守卫第 ①② 层按 `dayOffsetOrReject(` 数调用点，第 ⑤ 层按
 * [LEGAL_DAY_RANGE] 那一行钉「上界只有 SemesterStats.TOTAL_DAYS 一个出处」）。
 */
object WeekdayDateOffset {

    /** 合法星期序号下界（ISO 周一 = 1） */
    const val FIRST_DAY_INDEX: Int = 1

    /**
     * 合法星期序号上界：**取自展示侧那枚 [SemesterStats.TOTAL_DAYS]**，不是再写一遍 7。
     *
     * 展示侧四枚内核的 `1..SemesterStats.TOTAL_DAYS` 与本行是同一个数；本卡不动那四枚
     * （它们在的是「按下标取数组」那一族，护栏早已存在），收的是推导侧与写侧这两族。
     */
    const val LAST_DAY_INDEX: Int = SemesterStats.TOTAL_DAYS

    /** 合法区间：写侧 [CourseConstraints.normalize] 与读侧三处推导共用的那把尺 */
    val LEGAL_DAY_RANGE: IntRange = FIRST_DAY_INDEX..LAST_DAY_INDEX

    /**
     * 纯判据：一枚星期序号 → 该不该换算成日期、换算的话偏移几天。
     *
     * @param dayOfWeek 课程行上的星期序号（可能是教务脏数据里的 0 / 8 / 负数 / `Int.MAX_VALUE`）
     * @return [WeekdayOffset.Accepted]（偏移 `0..6`）或两档 Rejected 之一（各自带着本来会加上的那个偏移）
     */
    fun dayOffsetOrReject(dayOfWeek: Int): WeekdayOffset {
        // 唯一一处「减一」：改前三处推导各写各的同一手，现在这手只住在这里。
        // ⚠️ 先 `toLong()` 再减：改前那一形 `(course.dayOfWeek - 1).toLong()` 在 `Int.MIN_VALUE` 上
        // 会先在 Int 里溢出成正数（`-2147483648 - 1` → `2147483647`），把「往上一周偏」说成
        // 「往下一周偏」—— 档位判据（下面两枚比较）不受影响，但交回给日志与测试的那个数会撒谎。
        val offsetDays = dayOfWeek.toLong() - 1L
        return when {
            dayOfWeek < FIRST_DAY_INDEX -> WeekdayOffset.BeforeWeek(dayOfWeek, offsetDays)
            dayOfWeek > LAST_DAY_INDEX -> WeekdayOffset.AfterWeek(dayOfWeek, offsetDays)
            else -> WeekdayOffset.Accepted(dayOfWeek, offsetDays)
        }
    }
}
