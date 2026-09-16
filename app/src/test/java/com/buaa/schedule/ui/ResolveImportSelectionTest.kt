package com.buaa.schedule.ui

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.ImportPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入逐条勾选的语义测试。
 *
 * 覆盖导入会先清空该学期再写入，因此「取消勾选」必须是**原样保留**已存在的课程，
 * 否则用户只是想"这次别动它"，课表里这门课就被删了（曾在此处设计过错误语义）。
 */
class ResolveImportSelectionTest {

    private fun course(
        id: Long,
        name: String,
        weeks: List<Int>,
        day: Int = 1,
        periods: List<Int> = listOf(1, 2),
    ) = Course(id = id, name = name, dayOfWeek = day, periods = periods, weeks = weeks)

    private val existing = listOf(
        course(11, "已存在不动的课", weeks = listOf(1, 2)),
        course(12, "将被更新的课", weeks = listOf(3, 4)),
    )

    private val imported = listOf(
        course(0, "全新课程", weeks = (1..16).toList()),
        course(12, "将被更新的课", weeks = (3..8).toList()),
        course(0, "已存在不动的课", weeks = listOf(1, 2)),
    )

    @Test
    fun nothingExcludedImportsEverything() {
        val result = resolveImportSelection(imported, emptySet(), existing)
        assertEquals(3, result.toWrite.size)
        assertEquals(1, result.addedCount)
        assertEquals(1, result.changedCount)
        assertEquals(0, result.keptCount)
    }

    @Test
    fun excludingExistingCourseKeepsItUntouched() {
        val key = ImportPlanner.courseKey(imported.first { it.name == "已存在不动的课" })
        val result = resolveImportSelection(imported, setOf(key), existing)

        // 该课程以"本地已存在的原样"写回，而不是被删掉
        val kept = result.toWrite.filter { it.name == "已存在不动的课" }
        assertEquals(1, kept.size)
        assertEquals(listOf(1, 2), kept.first().weeks)
        assertEquals(11L, kept.first().id)
        assertEquals(1, result.keptCount)
        // 新增/更新计数不应把它算进去
        assertEquals(1, result.addedCount)
        assertEquals(1, result.changedCount)
    }

    @Test
    fun excludingBrandNewCourseSimplySkipsIt() {
        val key = ImportPlanner.courseKey(imported.first { it.name == "全新课程" })
        val result = resolveImportSelection(imported, setOf(key), existing)
        assertTrue(result.toWrite.none { it.name == "全新课程" })
        assertEquals(0, result.addedCount)
        assertEquals(0, result.keptCount)
    }

    @Test
    fun excludingEverythingStillKeepsExistingOnes() {
        val allKeys = imported.mapTo(mutableSetOf()) { ImportPlanner.courseKey(it) }
        val result = resolveImportSelection(imported, allKeys, existing)
        assertEquals(2, result.toWrite.size)
        assertEquals(2, result.keptCount)
        assertEquals(0, result.addedCount)
        assertEquals(0, result.changedCount)
    }

    @Test
    fun excludedCourseNotPresentLocallyIsDropped() {
        // 取消勾选、但本地根本没有这门课：没有"原样"可言，直接不写
        val ghost = course(0, "本地不存在的课", weeks = listOf(1, 2))
        val result = resolveImportSelection(imported + ghost, setOf(ImportPlanner.courseKey(ghost)), existing)
        assertTrue(result.toWrite.none { it.name == "本地不存在的课" })
        assertEquals(0, result.keptCount)
    }
}
