package com.buaa.schedule.data.import

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BuaaScheduleRealResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseRealWeekResponse() {
        val raw = """
            {
                "datas": {
                    "arrangedList": [
                        {
                            "teachingTarget": "267612",
                            "endSection": 9,
                            "placeName": "不使用教室",
                            "courseSerialNo": "006",
                            "courseName": "《启航学堂》新生入学教育课程A(1)",
                            "credit": "0.0",
                            "week": null,
                            "beginTime": "15:50",
                            "byCode": "0",
                            "courseCode": "B370026017",
                            "beginSection": 8,
                            "teachClassName": null,
                            "weeksAndTeachers": "1周[实践]\/曾煜[主讲]",
                            "teachClassId": "202620271B370026017006",
                            "cellDetail": [
                                {"text": "（本）《启航学堂》新生入学教育课程A(1)", "color": null},
                                {"text": "曾煜[1周]", "color": null},
                                {"text": "不使用教室", "color": null},
                                {"text": "8-9节", "color": null}
                            ],
                            "titleDetail": [
                                "本研标识：本",
                                "课程号：B370026017",
                                "课程名：《启航学堂》新生入学教育课程A(1)",
                                "课序号：006",
                                "上课教师：曾煜\/8-9节",
                                "上课地点：沙河校区\/沙河校区场地\/不使用教室"
                            ],
                            "tags": [],
                            "multiCourse": null,
                            "endTime": "17:25",
                            "color": "#FFF0CC",
                            "dayOfWeek": 5
                        }
                    ],
                    "notArrangeList": [],
                    "practiceList": [],
                    "code": "26376074",
                    "name": ""
                },
                "code": "0",
                "msg": null
            }
        """.trimIndent()

        val response = json.decodeFromString<BuaaScheduleResponse>(raw)
        val items = response.datas?.arrangedList.orEmpty()
        assertEquals(1, items.size)

        val courses = BuaaScheduleParser.parseArrangedList(items, "2026-2027-1")

        assertEquals(1, courses.size)
        val course = courses[0]
        assertEquals("《启航学堂》新生入学教育课程A(1)", course.name)
        assertEquals("曾煜", course.teacher)
        assertEquals(listOf(1), course.weeks)
        assertEquals(5, course.dayOfWeek)
        assertEquals(listOf(8, 9), course.periods)
        assertEquals("沙河校区", course.campus)
        assertEquals("不使用教室", course.location)
        assertNotNull(course.sourceGroupKey)
    }
}
