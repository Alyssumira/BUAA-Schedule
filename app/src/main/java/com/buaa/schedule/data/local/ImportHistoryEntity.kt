package com.buaa.schedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "import_history")
data class ImportHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val source: String,
    val importedAt: Long,
    val termCode: String?,
    val courseCount: Int,
    val message: String?,
)
