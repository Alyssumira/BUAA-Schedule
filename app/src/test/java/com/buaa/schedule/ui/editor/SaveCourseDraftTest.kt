package com.buaa.schedule.ui.editor

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.CourseSaveOptions
import com.buaa.schedule.domain.schedule.CourseConstraints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器保存编排的回归测试。
 *
 * 历史 P1：部分周次编辑会把“选中的周次”拆成**新行**（新 id），但旧代码用
 * `initialCourse.id` 去写提醒，于是提醒落到了“剩余周次”的旧行上，
 * 用户实际编辑的那门课反而没有提醒。
 *
 * 这里用假的 onSave 返回一个不同于 course.id 的落库 id，
 * 断言提醒一定写到 onSave 返回的那个 id 上。
 */
class SaveCourseDraftTest {

    private fun course(id: Long = 1L) = Course(
        id = id,
        name = "高等数学",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = listOf(3, 4),
    )

    private class ReminderCapture {
        val calls = mutableListOf<Triple<Long, Boolean, Int>>()
        fun record(id: Long, enabled: Boolean, minutes: Int) {
            calls += Triple(id, enabled, minutes)
        }
    }

    @Test
    fun reminderGoesToIdReturnedBySaveNotOriginalId() = runBlocking {
        val originalId = 1L
        val newRowId = 999L
        val capture = ReminderCapture()
        var savedOptions: CourseSaveOptions? = null

        val ok = saveCourseDraft(
            course = course(originalId),
            options = CourseSaveOptions(partialWeeks = true),
            isEditingExisting = true,
            reminderEnabled = true,
            advanceMinutesText = "15",
            onSave = { _, options ->
                savedOptions = options
                newRowId // 拆出新行，id 与原课程不同
            },
            onSaveReminder = capture::record,
        )

        assertTrue(ok)
        assertEquals(true, savedOptions?.partialWeeks)
        assertEquals("提醒必须写到 onSave 返回的行", 1, capture.calls.size)
        assertEquals(newRowId, capture.calls.single().first)
        assertEquals(true, capture.calls.single().second)
        assertEquals(15, capture.calls.single().third)
    }

    @Test
    fun saveFailureSkipsReminderAndReportsFailure() = runBlocking {
        val capture = ReminderCapture()
        val ok = saveCourseDraft(
            course = course(),
            options = CourseSaveOptions(),
            isEditingExisting = true,
            reminderEnabled = true,
            advanceMinutesText = "15",
            onSave = { _, _ -> null }, // 归一化失败/写库失败
            onSaveReminder = capture::record,
        )

        assertFalse("保存失败必须返回 false，UI 保留草稿", ok)
        assertTrue("保存失败不应写提醒", capture.calls.isEmpty())
    }

    @Test
    fun creatingNewCourseDoesNotTouchReminders() = runBlocking {
        val capture = ReminderCapture()
        val ok = saveCourseDraft(
            course = course(id = 0L),
            options = CourseSaveOptions(),
            isEditingExisting = false,
            reminderEnabled = true,
            advanceMinutesText = "15",
            onSave = { _, _ -> 42L },
            onSaveReminder = capture::record,
        )

        assertTrue(ok)
        assertTrue("新增课程没有提醒设置，不应写提醒", capture.calls.isEmpty())
    }

    @Test
    fun advanceMinutesIsClampedAndFallsBackToDefault() = runBlocking {
        val capture = ReminderCapture()

        saveCourseDraft(
            course = course(),
            options = CourseSaveOptions(),
            isEditingExisting = true,
            reminderEnabled = true,
            advanceMinutesText = "99999",
            onSave = { _, _ -> 7L },
            onSaveReminder = capture::record,
        )
        assertEquals(CourseConstraints.MAX_ADVANCE_MINUTES, capture.calls.single().third)

        saveCourseDraft(
            course = course(),
            options = CourseSaveOptions(),
            isEditingExisting = true,
            reminderEnabled = false,
            advanceMinutesText = "不是数字",
            onSave = { _, _ -> 7L },
            onSaveReminder = capture::record,
        )
        assertEquals(10, capture.calls.last().third)
        assertEquals(false, capture.calls.last().second)
    }

    @Test
    fun parsePeriodsStillBehaves() {
        assertEquals(listOf(1, 2, 9, 10), parsePeriods("1", "2", "9-10"))
        assertTrue("结束节次早于开始节次应按非法处理", parsePeriods("9", "2", "").isEmpty())
    }
}
