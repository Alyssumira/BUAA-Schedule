package com.buaa.schedule

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.widget.BackgroundSync
import com.buaa.schedule.widget.WidgetData
import com.buaa.schedule.widget.WidgetDataSynchronizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 组件数据新鲜度契约。
 *
 * 组件（含 RemoteViewsService）读的是 `WidgetDataCache` → `WidgetDataSynchronizer` 快照。
 * 如果快照只在 12 小时的兜底 Worker 里重写，"改完课表组件还是旧课表"这个 bug 就会复活 —— 
 * 本用例把这份契约钉死：走一次 [BackgroundSync.refreshWidgets] 之后，
 * 快照必须等于主库当前内容。
 *
 * 运行：./gradlew :app:connectedDebugAndroidTest（需要设备或模拟器）
 */
@RunWith(AndroidJUnit4::class)
class WidgetRefreshFreshnessTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = (context.applicationContext as BUAAApplication).repository
    private val db get() = AppDatabase.getInstance(context.applicationContext)

    @Before
    fun seedCourse() = runBlocking {
        repository.saveSemester(
            Semester(
                termCode = TERM_CODE,
                termName = TERM_CODE,
                startDate = "2026-09-07",
                totalWeeks = 16,
            )
        )
        repository.saveCourse(
            Course(
                name = FRESHNESS_COURSE_NAME,
                dayOfWeek = 1,
                periods = listOf(1, 2),
                weeks = listOf(1, 2),
                semesterCode = TERM_CODE,
            )
        )
    }

    @After
    fun cleanUp() = runBlocking {
        db.courseDao().deleteBySemester(TERM_CODE)
        db.semesterDao().deleteByTermCode(TERM_CODE)
    }

    @Test
    fun refreshingWidgetsRewritesSnapshotFromDatabase() = runBlocking {
        // 1) 伪造一份明显过期、与主库不一致的快照
        WidgetDataSynchronizer.save(context, "current", WidgetData(null, emptyList(), emptyList()))

        // 2) 走组件刷新路径（应用内改课表后 ScheduleViewModel 调的就是它）
        BackgroundSync.refreshWidgets(context)

        // 3) 快照必须已被重写成主库当前内容（含刚写入的那门课）
        val expected = repository.getDisplayCourses(repository.getCurrentSemester())
        val actual = WidgetDataSynchronizer.load(context, "current")?.data

        assertEquals(expected.size, actual?.courses?.size)
        assertEquals(
            expected.map { it.name }.sorted(),
            actual?.courses?.map { it.name }?.sorted(),
        )
        assertEquals(true, actual?.courses?.any { it.name == FRESHNESS_COURSE_NAME })
    }

    companion object {
        private const val TERM_CODE = "TEST-WIDGET-FRESHNESS"
        private const val FRESHNESS_COURSE_NAME = "组件新鲜度测试课"
    }
}
