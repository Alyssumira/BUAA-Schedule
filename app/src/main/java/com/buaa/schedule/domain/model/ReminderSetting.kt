package com.buaa.schedule.domain.model

data class ReminderSetting(
    val courseId: Long,
    val enabled: Boolean = true,
    val advanceMinutes: Int = 10,
)