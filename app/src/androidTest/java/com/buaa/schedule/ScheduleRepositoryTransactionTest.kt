package com.buaa.schedule

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.repository.ScheduleRepository
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ImportHistory
import com.buaa.schedule.domain.model.Semester
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导入的**事务语义**（内存库，需设备/模拟器）。
 *
 * 「替换学期课程」与「写一条导入历史」必须是同一个事务：
 * 分两次调用时中间失败会留下「课表已经换掉、导入历史里却查不到这次导入」的
 * 不一致状态，用户看到的就是导入记录凭空失踪。
 */
@RunWith(AndroidJUnit4::class)
class ScheduleRepositoryTransactionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ScheduleRepository

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027 学年第一学期",
        startDate = "2026-09-07",
        totalWeeks = 19,
    )

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

    private fun course(name: String, day: Int = 1, weeks: List<Int> = (1..16).toList()) = Course(
        name = name,
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = day,
        periods = listOf(1, 2),
        weeks = weeks,
        semesterCode = semester.termCode,
    )

    private fun history(courseCount: Int) = ImportHistory(
        source = "buaa",
        importedAt = 1_700_000_000_000L,
        termCode = semester.termCode,
        courseCount = courseCount,
    )

    @Test
    fun coursesAndHistoryAreCommittedTogether() = runBlocking {
        repository.replaceSemesterCoursesWithHistory(
            semester,
            listOf(course("高等数学"), course("大学物理", day = 3)),
            history(2),
        )

        assertEquals(2, repository.getCoursesBySemester(semester.termCode).size)
        val histories = repository.importHistory.first()
        assertEquals("必须恰好落一条导入历史", 1, histories.size)
        assertEquals(2, histories[0].courseCount)
    }

    @Test
    fun compositeFailureLeavesNeitherCoursesNorHistory() = runBlocking {
        // 先建立一次成功导入作为基线
        repository.replaceSemesterCoursesWithHistory(semester, listOf(course("旧课")), history(1))
        assertEquals(1, repository.importHistory.first().size)

        val threw = runCatching {
            db.withTransaction {
                repository.replaceSemesterCoursesWithHistory(
                    semester,
                    listOf(course("新课 A"), course("新课 B")),
                    history(2),
                )
                error("模拟导入之后紧接着失败")
            }
        }.isFailure

        assertTrue("外层失败应当抛出", threw)
        // 关键断言：不允许出现「课表换了但历史没写」或「历史写了但课表没换」
        val names = repository.getCoursesBySemester(semester.termCode).map { it.name }
        val historyCount = repository.importHistory.first().size
        if (names == listOf("旧课")) {
            assertEquals("课表未变时历史也不能多出一条", 1, historyCount)
        } else {
            assertEquals("课表已替换时必须同时有两条历史", listOf("新课 A", "新课 B"), names.sorted())
            assertEquals(2, historyCount)
        }
    }

    @Test
    fun manualCoursesSurviveSemesterImport() = runBlocking {
        val manual = course("手动加的课").copy(semesterCode = null)
        repository.saveCourse(manual)

        repository.replaceSemesterCoursesWithHistory(semester, listOf(course("高等数学")), history(1))

        val all = repository.getAllCourses()
        assertTrue("手动课程不能被学期导入删掉", all.any { it.name == "手动加的课" })
        assertTrue("导入课程必须写入", all.any { it.name == "高等数学" })
    }

    @Test
    fun otherSemestersAreUntouchedByImport() = runBlocking {
        repository.saveCourse(course("上学期的课").copy(semesterCode = "2025-2026-2"))

        repository.replaceSemesterCoursesWithHistory(semester, listOf(course("高等数学")), history(1))

        assertEquals(
            "其他学期的课程不受影响",
            listOf("上学期的课"),
            repository.getCoursesBySemester("2025-2026-2").map { it.name },
        )
    }

    @Test
    fun reImportingSameSemesterDoesNotDuplicateCourses() = runBlocking {
        repeat(3) {
            repository.replaceSemesterCoursesWithHistory(semester, listOf(course("高等数学")), history(1))
        }
        val names = repository.getCoursesBySemester(semester.termCode).map { it.name }
        assertEquals("重复导入不能叠加出多份同名课程", listOf("高等数学"), names)
    }

    @Test
    fun illegalCoursesAreDroppedWithoutKillingTheWholeImport() = runBlocking {
        repository.replaceSemesterCoursesWithHistory(
            semester,
            listOf(
                course("正常课"),
                course("空周次").copy(weeks = emptyList()),
                course("空节次").copy(periods = emptyList()),
                course("越界星期").copy(dayOfWeek = 9),
            ),
            history(1),
        )
        assertEquals(
            listOf("正常课"),
            repository.getCoursesBySemester(semester.termCode).map { it.name },
        )
    }
}
