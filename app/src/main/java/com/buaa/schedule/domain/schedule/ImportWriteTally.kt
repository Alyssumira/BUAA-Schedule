package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course

/**
 * 一批待写库的课程过 [CourseConstraints.normalize] 之后交回的那本账：留下几条、判死几条。
 *
 * 零 android / androidx import（本仓口径）：判据全在 [CourseConstraints] 里，这一件不碰数据库、
 * 不读时钟、不读设备，所以"哪几行会被判死"这件事在 JVM 侧就能逐格断言。
 *
 * ## 病在哪
 *
 * 导入确认卡那句「导入完成：新增 N、更新 M」念的是**预览选中的条数**，而落库那一刀在这句之后
 * 还要过一次 `CourseConstraints.normalize`（`ScheduleRepository` 的
 * [com.buaa.schedule.data.repository.ScheduleRepository.replaceSemesterCoursesWithHistory]），
 * 被判死的行当场蒸发（周次/星期几越界、没有节次、名称空白）⇒ 课表上少一门、屏幕上那句还是多的，
 * 而且没有任何人念过少的那一门。备份恢复那一路早就记着这本账
 * （`RestoreResult.Applied` 带 `skippedInvalid`，VM 把它念成"跳过无效数据 N 条"），
 * 姊妹落点缺的就是这一枚。
 *
 * ## 为什么 [writtenCount] 是类体属性而不是构造参数
 *
 * 它恒等于 `writtenCourses.size`，是**同一张表里的派生值**。本仓规矩：值 = f(同表参数) 就写进类体属性，
 * 不给它第二次 copy 走漏的机会（另一支"每个 copy 站点配一枚守卫"是给跨表派生值用的，这里用不上）。
 */
data class ImportWriteOutcome(
    val writtenCourses: List<Course>,
    val droppedCount: Int,
) {
    /** 真正进库的条数（= `writtenCourses.size`，所以不可能是第二个数） */
    val writtenCount: Int get() = writtenCourses.size
}

/**
 * 把"过一遍 normalize"与"数清丢了几条"绑成一次动作：调用方拿到行的同时也拿到条数，
 * 拿不到"只写不报"的那一种写法。
 */
object ImportWriteTally {

    /**
     * 逐条过 [CourseConstraints.normalize]，交回留下的行与判死的条数。
     *
     * 这里刻意不写 `courses.mapNotNull(CourseConstraints::normalize)` —— 那个形状正是本卡的病：
     * 丢掉的行在返回值里没有格子，调用方连"少了"都不知道。两本册子一起交回是这条链的唯一入口。
     */
    fun tally(importedCourses: List<Course>): ImportWriteOutcome {
        val kept = ArrayList<Course>(importedCourses.size)
        var dropped = 0
        for (course in importedCourses) {
            val normalized = CourseConstraints.normalize(course)
            if (normalized == null) dropped++ else kept.add(normalized)
        }
        return ImportWriteOutcome(writtenCourses = kept, droppedCount = dropped)
    }
}
