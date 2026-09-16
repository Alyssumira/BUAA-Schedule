package com.buaa.schedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "time_slots")
data class TimeSlotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val number: Int,
    val startTime: String,
    val endTime: String,
)
