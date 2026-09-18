package com.buaa.schedule.data.import

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BuaaScheduleResponse(
    val datas: BuaaScheduleData? = null,
    val code: String? = null,
    val msg: String? = null,
)

@Serializable
data class BuaaScheduleData(
    @SerialName("arrangedList")
    val arrangedList: List<BuaaCourseDto>? = emptyList(),
    @SerialName("notArrangeList")
    val notArrangeList: List<BuaaCourseDto>? = emptyList(),
    @SerialName("practiceList")
    val practiceList: List<BuaaCourseDto>? = emptyList(),
)

@Serializable
data class BuaaCourseDto(
    @SerialName("courseName")
    val courseName: String? = null,
    @SerialName("courseCode")
    val courseCode: String? = null,
    @SerialName("courseSerialNo")
    val courseSerialNo: String? = null,
    val credit: String? = null,
    @SerialName("dayOfWeek")
    val dayOfWeek: Int? = null,
    @SerialName("beginSection")
    val beginSection: Int? = null,
    @SerialName("endSection")
    val endSection: Int? = null,
    @SerialName("beginTime")
    val beginTime: String? = null,
    @SerialName("endTime")
    val endTime: String? = null,
    @SerialName("placeName")
    val placeName: String? = null,
    @SerialName("campusName")
    val campusName: String? = null,
    @SerialName("weeksAndTeachers")
    val weeksAndTeachers: String? = null,
    @SerialName("teachClassId")
    val teachClassId: String? = null,
    @SerialName("cellDetail")
    val cellDetail: List<CellDetailDto>? = emptyList(),
    @SerialName("titleDetail")
    val titleDetail: List<String>? = emptyList(),
    val tags: List<CellDetailDto>? = emptyList(),
    val color: String? = null,
)

@Serializable
data class CellDetailDto(
    val text: String? = null,
    val color: String? = null,
)

@Serializable
data class BuaaTermWeeksResponse(
    val datas: List<BuaaTermWeekDto>? = emptyList(),
    val code: String? = null,
    val msg: String? = null,
)

@Serializable
data class BuaaTermWeekDto(
    val term: String? = null,
    @SerialName("curWeek")
    val currentWeek: Boolean? = null,
    @SerialName("startDate")
    val startDate: String? = null,
    @SerialName("endDate")
    val endDate: String? = null,
    @SerialName("serialNumber")
    val serialNumber: Int? = null,
    val name: String? = null,
)
