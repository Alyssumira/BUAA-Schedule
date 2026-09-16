package com.buaa.schedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey
    val courseId: Long,
    val enabled: Boolean = true,
    val advanceMinutes: Int = 10,
)