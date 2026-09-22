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
 *
 * 至于「这一天到底挂不挂、挂哪一枚」不由本类回答：判据在纯 JVM 的
 * `ui/SpecialDayBadgePolicy`（T62①），渲染在 `ui/home/SpecialDayBadge`（T62②），
 * 三处界面（周表头 / 今日页页头 / 顶栏）问的都是同一份。本类以前那个
 * `badgeOf(isHoliday)` 就是被删掉的第四份答案：它只说得出"数据里写了什么"，
 * 说不出"重复条目谁赢""周末那天既休又班该怎么读"。
 */
data class SpecialDay(
    val date: LocalDate,
    /** true = 节假日（休）；false = 调休上班日（班） */
    val isHoliday: Boolean,
    /** 名称 / 说明（如「国庆节」「国庆节调休上班」） */
    val note: String? = null,
)
