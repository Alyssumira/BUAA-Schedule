package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.schedule.toEpochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 一次重排里那次全量窗口搜索只许发生**一轮**（§2.3）。
 *
 * [ReminderScheduler.planNextReminder] 的代价不是"课程数"而是
 * "(当前周次−1) × 节次段数 × 课程数"——`nextOccurrence` 按周次升序遍历、
 * 只在第一个尚未开始的窗口处 return，所以每个已经过去的教学周都要被重走一遍
 * （学期中段 40 门课就是上千次窗口构造，且这条路径挂在每一次冷唤醒上）。
 * 此前 `BackgroundSync.rescheduleReminders` 为了拿"还有没有待触发提醒"这个 Boolean，
 * 把 `rescheduleAll` 内部已经算过的那一遍又跑了一遍，还是**另读一次时钟**跑的
 * （`LocalDateTime.now()` 与 `System.currentTimeMillis()` 各读各的，
 * 正是 `rescheduleAll` 里那段注释要防的跨秒自相矛盾）。
 *
 * 三条守卫各钉一半：
 * - [oneRoundSearchesExactlyOnce]：一轮只搜一次，且带出去的就是那一份结论；
 * - [roundClocksComeFromTheSingleNow]：`nowMillis` 由同一个 `now` 换算，不另读钟；
 * - [fullWindowSearchHasOnlyOneCallSite]：全仓库没有第二处调用点 —— 前两条只钉住
 *   [ReminderScheduler.planOnce] 自己，"外层不许再搜一轮"只能在调用点上抓。
 */
class ReminderRescheduleRoundTest {

    private val semesterStart = LocalDate.of(2026, 9, 7) // 周一
    private val zone = ZoneId.of("Asia/Shanghai")

    private val mondayCourse = Course(
        id = 1,
        name = "高等数学",
        dayOfWeek = 1,
        periods = listOf(1),
        weeks = (1..16).toList(),
    )

    @Test
    fun oneRoundSearchesExactlyOnce() {
        val now = LocalDateTime.of(2026, 9, 7, 7, 0)
        var rounds = 0
        var searched: ReminderScheduler.ReminderPlan? = null

        val plan = ReminderScheduler.planOnce(
            courses = listOf(mondayCourse),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = mapOf(1L to ReminderSetting(1, true, 10)),
            now = now,
        ) { courses, start, slots, reminders, searchedAt, nowMillis ->
            rounds++
            searched = ReminderScheduler.planNextReminder(
                courses = courses,
                semesterStart = start,
                timeSlots = slots,
                reminders = reminders,
                now = searchedAt,
                nowMillis = nowMillis,
                zone = zone,
            )
            searched
        }

        assertEquals("一次重排搜了两轮：第二轮的结论要么复用第一轮的，要么别要", 1, rounds)
        // 带出去给调用方的必须是那一轮搜出来的同一个对象，而不是又算出来的一份
        assertSame(plan, searched)
    }

    @Test
    fun roundClocksComeFromTheSingleNow() {
        // 故意拿一天前的时刻：只要 planOnce 内部再读一次钟来算 nowMillis，
        // 两个值就一定差出一天，这条断言当场红 —— 换成"当下"就成了碰运气的比较
        val now = LocalDateTime.now().minusDays(1)
        var seenNow: LocalDateTime? = null
        var seenMillis = 0L

        ReminderScheduler.planOnce(
            courses = listOf(mondayCourse),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = emptyMap(),
            now = now,
        ) { courses, start, slots, reminders, searchedAt, nowMillis ->
            seenNow = searchedAt
            seenMillis = nowMillis
            ReminderScheduler.planNextReminder(courses, start, slots, reminders, searchedAt, nowMillis, zone)
        }

        // "这一段还没开始"与"触发时刻过了没有"两个判据必须读同一只钟
        assertEquals(now, seenNow)
        assertEquals(now.toEpochMillis(), seenMillis)
    }

    /**
     * 全仓库范围内，那轮搜索只有一个调用点。
     *
     * 本模块的单元测试没有 Robolectric（`rescheduleAll` 要 Context），
     * 所以"外层不许再搜一轮"只能在源码结构上钉：把 `BackgroundSync` 那次重复搜索改回去，
     * 这条就红。写法与 `GlassSurfaceSingleChildTest` 一致 —— 注释里提到函数名的地方不算。
     */
    @Test
    fun fullWindowSearchHasOnlyOneCallSite() {
        val hits = mutableListOf<String>()
        for (file in mainSources()) {
            file.readText().lines().forEachIndexed { index, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                if (line.contains("planNextReminder(") && !line.contains("fun planNextReminder(")) {
                    hits += "${file.relative}:${index + 1} $code"
                }
            }
        }
        assertTrue(
            "planNextReminder 又多了一处调用点。要判断「本轮还有没有待触发的课前提醒」，" +
                "请复用 ReminderScheduler.rescheduleAll 的返回值：\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    private class SourceFile(val relative: String, private val file: File) {
        fun readText(): String = file.readText()
    }

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛（跳过的守卫等于没守卫） */
    private fun mainSources(): List<SourceFile> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { SourceFile(it.relativeTo(root).path.replace('\\', '/'), it) }
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}
