package com.buaa.schedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val termCode: String,
    val termName: String,
    val startDate: String,
    val totalWeeks: Int,
)
