package com.buaa.schedule

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.repository.ScheduleRepository
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仓库层回归测试（内存库，需设备/模拟器）：
 *
 * 1. 部分周次编辑会把选中周次拆成新行，**提醒必须跟着用户编辑的那一行走**，
 *    而不能写回“剩余周次”的旧行（P1-1 回归）。
 * 2. 越界的 customColorArgb 在入库前被丢弃（P1-3 回归）。
 * 3. 归一化失败的课程不会被静默写入（P2 回归）。
 *
 * 运行：./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ScheduleRepositoryReminderTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ScheduleRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.buildForTest(context)
        repository = ScheduleRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun baseCourse(weeks: List<Int> = (1..16).toList()) = Course(
        name = "高等数学",
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = weeks,
        semesterCode = "2026-2027-1",
    )

    @Test
    fun partialWeekSplitKeepsReminderOnEditedRow() = runBlocking {
        val originalId = repository.saveCourse(baseCourse())!!
        repository.saveReminder(ReminderSetting(courseId = originalId, enabled = true, advanceMinutes = 15))

        // 用户只把第 3、4 周改成周三，其余周次保持不变
        val edited = baseCourse(weeks = listOf(3, 4)).copy(id = originalId, dayOfWeek = 3)
        val savedId = repository.updateCoursePartialWeeks(baseCourse().copy(id = originalId), edited)?.savedId

        assertNotNull("编辑应当成功", savedId)
        assertNotEquals("选中周次应拆成新行，而不是覆盖原行", originalId, savedId)

        val rows = repository.getAllCourses()
        assertEquals(2, rows.size)
        val editedRow = rows.first { it.id == savedId }
        val remainingRow = rows.first { it.id == originalId }
        assertEquals(listOf(3, 4), editedRow.weeks)
        assertEquals(3, editedRow.dayOfWeek)
        assertEquals((1..16).filter { it !in listOf(3, 4) }, remainingRow.weeks)

        // 关键断言：提醒落在“用户编辑的那一行”
        val reminders = repository.getReminders()
        val editedReminder = reminders.firstOrNull { it.courseId == savedId }
        assertNotNull("被编辑的课程必须保留提醒", editedReminder)
        assertEquals(15, editedReminder!!.advanceMinutes)
    }

    /**
     * 编辑器的修复契约：提醒按 `onSave` 返回的行 id 写入。
     * 两行是彼此独立的课程行，提醒必须能分别生效——写错行会让被编辑课程丢掉提醒。
     */
    @Test
    fun editedRowAndRemainingRowKeepIndependentReminders() = runBlocking {
        val originalId = repository.saveCourse(baseCourse())!!
        repository.saveReminder(ReminderSetting(courseId = originalId, enabled = true, advanceMinutes = 10))

        val edited = baseCourse(weeks = listOf(3, 4)).copy(id = originalId, dayOfWeek = 3)
        val savedId = repository.updateCoursePartialWeeks(baseCourse().copy(id = originalId), edited)!!.savedId

        // 模拟修复后的编辑器：把用户新选的提醒写到返回行
        repository.saveReminder(ReminderSetting(courseId = savedId, enabled = true, advanceMinutes = 30))

        val byCourse = repository.getReminders().associateBy { it.courseId }
        assertEquals("被编辑的课 = 用户新设置", 30, byCourse[savedId]!!.advanceMinutes)
        assertEquals("剩余周次的课保持原设置", 10, byCourse[originalId]!!.advanceMinutes)
    }

    @Test
    fun editingAllWeeksInPlaceKeepsSameRowAndReminder() = runBlocking {
        val originalId = repository.saveCourse(baseCourse())!!
        repository.saveReminder(ReminderSetting(courseId = originalId, enabled = true, advanceMinutes = 20))

        val edited = baseCourse(weeks = (1..16).toList()).copy(id = originalId, location = "J3-202")
        val savedId = repository.updateCoursePartialWeeks(baseCourse().copy(id = originalId), edited)?.savedId

        assertEquals("覆盖全部周次时应原地更新", originalId, savedId)
        assertEquals("J3-202", repository.getCourseById(originalId)!!.location)
        assertEquals(
            20,
            repository.getReminders().first { it.courseId == originalId }.advanceMinutes,
        )
    }

    @Test
    fun outOfRangeCustomColorIsDroppedOnSave() = runBlocking {
        val id = repository.saveCourse(
            baseCourse(weeks = listOf(1)).copy(customColorArgb = 0x1FFFFFFFFL)
        )
        assertNotNull(id)
        assertNull("越界自定义色必须被丢弃", repository.getCourseById(id!!)!!.customColorArgb)
    }

    @Test
    fun inRangeCustomColorIsPersisted() = runBlocking {
        val id = repository.saveCourse(
            baseCourse(weeks = listOf(1)).copy(customColorArgb = 0xFF5B8DEFL)
        )
        assertEquals(0xFF5B8DEFL, repository.getCourseById(id!!)!!.customColorArgb)
    }

    @Test
    fun invalidCourseIsRejectedWithoutWriting() = runBlocking {
        assertNull(repository.saveCourse(baseCourse(weeks = listOf(0))))
        assertTrue("非法课程不应落库", repository.getAllCourses().isEmpty())
    }
}
