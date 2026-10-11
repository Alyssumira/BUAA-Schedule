package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot

/**
 * 一枚后台链的读载体：入口读一遍，链上四步（重排提醒 / 兜底续排 / 组件快照 / 明日预告）共用。
 *
 * ## 它存在的唯一理由（#536 F2 / 台账 #218）
 *
 * 同一次唤醒锁（或同一次改课表收尾）里，四步各自去查同一批表：学期行 4 遍、节次表 3 遍、
 * 展示口径的课程 3 + K 遍（K = 学期数 + 1，见
 * [com.buaa.schedule.widget.WidgetDataSynchronizer.planSnapshotRows] 那本排产账）。
 * 四步之间没有任何写这四张表的动作，所以后面那几遍读到的必然是同一份内容 —— 白付的往返。
 *
 * ## 为什么是"当参数传下去"，不是缓存
 *
 * 抄的是本仓已经写下那条线（[com.buaa.schedule.widget.BackgroundSync.refreshWidgets] 的
 * `hasAnyWidget` 那段）：共享方式是**把结论当参数传下去，而不是缓存起来**；没有跨调用的缓存，
 * 就不存在陈旧问题。这枚载体的生命周期等于一次链：它在入口造出来，链跑完就没了，
 * 任何一步都不许把它存进字段、单例或 prefs 里跨调用复用。
 *
 * ## 装什么、不装什么
 *
 * 只装**链上被读第二遍**的那几张：学期行、该学期的展示口径课程、节次表、提醒册子。
 * 不装 `getAllSemesters()` 与那 K 趟按学期的课程查询 —— 它们在链上各只被读一遍
 * （组件快照按 key 排产是本文件顶部说的 K 的来源，不是这张卡要收的那一笔），
 * 提前替全链预取反而要给"桌面上一个组件都没放"的用户多读一趟学期表。
 *
 * ## 不带任何派生参数
 *
 * 按 `docs/derived-field-audit.md` §6.0 那条成文判据（值 = f(同表另一枚参数) 只有两条正当落点，
 * 没有第三条），这里**故意不把 `semesterStart` / `totalWeeks` 做成字段**：消费者直接从装着的那枚
 * 学期行上读（`ClassProgressScheduler` 的接线仍然写 `totalWeeks = semester.totalWeeks,`，
 * 逐字节留在 `TermWeekGateSingleSourceGuardTest` 在册的那一格上）。参数表少一枚字段，
 * 就少一枚"每个 copy 站点都得记得重算"的债。
 *
 * 唯一的判据 [covers] 长在类体里，不进参数表、不进 `equals` / `componentN`。
 */
data class ChainReadSnapshot(
    /** 当前学期那一行；没有学期行就是 null（与 `ScheduleRepository.getCurrentSemester()` 同一口径） */
    val semester: Semester?,
    /** `getDisplayCourses(semester)` 的那一份：手动课程 + 该学期课程 */
    val courses: List<Course>,
    /** 节次表全库只有一张，所以整链一份 */
    val timeSlots: List<TimeSlot>,
    /** 提醒册子按 courseId 归好（排铃那一步吃的就是这本册子） */
    val remindersByCourseId: Map<Long, ReminderSetting>,
) {

    /**
     * 这一枚载体的 [courses] 是否已经回答了 `getDisplayCourses(row)` 那一问。
     *
     * 判据跟着仓储层那一问的实际入参走（`ScheduleRepository.getDisplayCourses`）：
     * `row == null` 那一档查的是 `courseDao.getAll()`，非 null 那一档查的是
     * `courseDao.getDisplay(row.termCode)` —— 所以"同一问"等价于"同一档 + 同一个 termCode"，
     * 与学期行的其它字段（`id` / `totalWeeks` / `startDate`）无关：那些字段改不出另一个答案。
     * 载体里的 `courses` 正是按 `semester` 那一枚行取回来的，因此：
     *
     * - `row` 与载体装的学期行是同一枚 → 同一问，答案已经在手上；
     * - `row` 与载体装的学期行 `termCode` 相同但是另一枚实例（`getAllSemesters()` 每轮新造对象）→
     *   仍是同一问；
     * - 学期行缺失那一档：只有两边都 null 才算同一问，一边 null 一边有学期行是两道不同的查询。
     *
     * 纯函数：不接 Context、不读钟、不碰权限，输入全是已经算好的值。
     */
    fun covers(row: Semester?): Boolean {
        val held = semester
        if (row == null) return held == null
        return held != null && held.termCode == row.termCode
    }
}
