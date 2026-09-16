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
 * 安全解析开学日期。数据库中可能存在用户输入的非法格式（旧版本未校验），
 * 解析失败返回 null，调用方按“未设置学期”降级，避免崩溃。
 */
val Semester.startLocalDate: LocalDate?
    get() = runCatching { LocalDate.parse(startDate) }.getOrNull()
