package com.buaa.schedule.domain.model

import java.time.LocalDate

data class Semester(
    val id: Long = 0L,
    val termCode: String,
    val termName: String,
    val startDate: String, // ISO-8601 yyyy-MM-dd，应为周一
    val totalWeeks: Int,
)

/**
 * 归一到该日期所在自然周的周一。
 *
 * 全应用的课次日期都是 `startDate.plusWeeks(w-1).plusDays(dayOfWeek-1)`，
 * 开学日期只要不是周一，整张课表就会整体偏移且周次编号错位。
 * 公式只写这一份（[com.buaa.schedule.domain.schedule.WeekCalculator.mondayOf] 委托到这里）。
 * 周日（ISO 一周的最后一天）要往前退 6 天，不能用 previousOrNext。
 */
fun mondayOfWeekAnchor(date: LocalDate): LocalDate =
    date.minusDays((date.dayOfWeek.value - 1).toLong())

/**
 * 安全解析开学日期。数据库中可能存在用户输入的非法格式（旧版本未校验），
 * 解析失败返回 null，调用方按“未设置学期”降级，避免崩溃。
 *
 * ⚠️ 成功时一律归一到周一（[mondayOfWeekAnchor]）。写侧各入口早已过 mondayOf，
 * 但那些防护是逐轮审计陆续加的，库里可能留有非周一起点的旧行：
 * 不归一的话，课表网格/导出（自己 mondayOf）与提醒/实况/今日（读裸值）会按两套锚点
 * 各算各的，同一节课最多错开 6 天（R7 P2-12）。周一日期上是幂等的。
 */
val Semester.startLocalDate: LocalDate?
    get() = runCatching { LocalDate.parse(startDate) }.getOrNull()?.let { mondayOfWeekAnchor(it) }
