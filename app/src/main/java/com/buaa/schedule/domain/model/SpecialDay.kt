package com.buaa.schedule.domain.model

import java.time.LocalDate

/**
 * 特殊日期标注：节假日（休）与调休上班日（班）。
 *
 * 数据来源：教务「学习日程」`api/home/teachingSchedule/list.do?rq=日期&lxdm=student`
 * 的 JJR 字段（抓包获得，非公开文档）。
 *
 * ⚠️ 语义约定（产品决策）：这类信息**只用于标注，不参与任何计算**——
 * 周次计算、课程过滤、提醒调度仍按学期开学日 + 总周数的固定口径。
 * 也就是说：国庆周照样显示当周课表，只是表头上多一个「休」的视觉提示。
 */
data class SpecialDay(
    val date: LocalDate,
    /** true = 节假日（休）；false = 调休上班日（班） */
    val isHoliday: Boolean,
    /** 名称 / 说明（如「国庆节」「国庆节调休上班」） */
    val note: String? = null,
) {
    companion object {
        const val BADGE_HOLIDAY = "休"
        const val BADGE_WORKDAY = "班"

        fun badgeOf(day: SpecialDay): String = if (day.isHoliday) BADGE_HOLIDAY else BADGE_WORKDAY
    }
}
