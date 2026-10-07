package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course

/**
 * 一门课进不了 WakeUp 导出件的那两档原因（顺序即判据顺序，与导出器里那两枚
 * `?: return@mapNotNull null` 一一对应：节次那一档 T27，周次那一档 T203/#237）。
 *
 * 两档**互斥**：[WakeUpDropTally.wakeUpDrop] 先判节次再判周次，所以一门既没节次又没周次的课
 * 只落在 [NO_PERIOD] 那一格 ⇒ 报数不会把同一门课念两次。
 */
enum class WakeUpDrop { NO_PERIOD, NO_WEEK }

/**
 * 导出件按两档各丢下几门（[com.buaa.schedule.ui.settings.wakeUpExportMessage] 吃的就是这两个数）。
 *
 * ## 为什么 [droppedCourses] 是类体属性而不是构造参数
 *
 * 它恒等于两数之和，是**同一张表里的派生值**（本仓规矩：值 = f(同表参数) 就写进类体属性，
 * 不给它第二次 copy 走漏的机会）。
 */
data class WakeUpDropCounts(
    val noPeriodCourses: Int,
    val noWeekCourses: Int,
) {
    /** 丢掉的总门数：两档互斥，所以直接相加就是它，不存在"既没节次又没周次的那门被加两遍" */
    val droppedCourses: Int get() = noPeriodCourses + noWeekCourses
}

/**
 * 数清 [ScheduleExporters.toWakeUpJson] 会丢下哪几档、各几门 —— 导出的门数**可以少于**入参，
 * 调用方要按"写出去了几门 + 各因哪一档被丢"说话，不能拿 `courses.size` 报数。
 *
 * ## 病在哪
 *
 * 导出器在 T27 之后就不写"一条节次都没有"的课，T203 又把"一条周次都没有"的课判成同一口径
 * （此前它被造出 `startWeek:1,endWeek:1,type:0` 这么一条**离开本应用**的假课，对方 App 照单收下）。
 * 而调用方 `ScheduleViewModel.exportWakeUpTo` 的报数只数了**没节次**那一档
 * （`courses.count { it.periods.isEmpty() }`）⇒ 空周次那几门被导出器丢下后，屏幕上那句
 * "N 门课"还是多的，少掉的那几门没人念。这与 T181① 那句「导入完成：新增 N」念的是
 * 归一化**之前**的数是同一族账，缺的就是这一本册子。
 *
 * 零 android / androidx import（本仓口径）：判据两条都在 `Course` 自己的字段上，
 * 不碰数据库、不读时钟，所以"哪几门会被丢下"这件事在 JVM 侧就能逐格断言。
 */
object WakeUpDropTally {

    /** 这门课因哪一档进不了导出件；`null` = 出场。 */
    fun wakeUpDrop(course: Course): WakeUpDrop? = when {
        course.periods.isEmpty() -> WakeUpDrop.NO_PERIOD
        course.weeks.isEmpty() -> WakeUpDrop.NO_WEEK
        else -> null
    }

    /** 逐门过 [wakeUpDrop]，交回两档各自的门数（一门只落一档）。 */
    fun tally(courses: List<Course>): WakeUpDropCounts {
        val byReason = courses.groupingBy { wakeUpDrop(it) }.eachCount()
        return WakeUpDropCounts(
            noPeriodCourses = byReason[WakeUpDrop.NO_PERIOD] ?: 0,
            noWeekCourses = byReason[WakeUpDrop.NO_WEEK] ?: 0,
        )
    }
}
