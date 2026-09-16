package com.buaa.schedule.domain.model

data class ImportHistory(
    val id: Long = 0L,
    val source: String,
    val importedAt: Long,
    val termCode: String? = null,
    val courseCount: Int = 0,
    val message: String? = null,
)
