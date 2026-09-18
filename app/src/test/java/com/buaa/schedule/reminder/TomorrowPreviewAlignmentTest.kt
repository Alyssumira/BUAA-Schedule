package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 明日预告"排到哪一天"这条判定（§2.7）。
 *
 * 广播里那条链早就学乖了：往后找第一个"明天有课"的日子，再复核真正会响那天。
 * 但**事件入口**（改课表 / 开机 / 改时间 / 设置页开关）此前只读一个 prefs 开关
 * 就调 `TomorrowPreviewScheduler.schedule(context)`，而那个重载写死"明天"——
 * 寒暑假与周末里每次事件都重新武装一次必然空转的 22:00 精确闹钟
 * （醒一次查四张表 + 跑一遍 121 天 × 全课程搜索，然后链条停下）。
 *
 * 现在两边共用 [TomorrowPreviewReceiver.nextScheduledPreviewDay]：
 * 它给 null 就是"这一次没有值得醒的晚上"，什么都不排；给哪天就排哪天。
 * 事件入口确实用了它，由 [eventEntryGoesThroughTheSharedJudgement] 钉住
 * （`BackgroundSync.scheduleTomorrowPreview` 要 Context，JVM 侧只能核源码）。
 */
class TomorrowPreviewAlignmentTest {

    /** 2026-09-07 周一开学，19 周：第 19 周是 2027-01-11 ~ 2027-01-17 */
    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027 秋季学期",
        startDate = "2026-09-07",
        totalWeeks = 19,
    )

    /** 只有周一有课，且整学期都有 */
    private val mondayCourse = Course(
        name = "高等数学",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = (1..19).toList(),
    )

    private val courses = listOf(mondayCourse)

    @Test
    fun armsTonightWhenTomorrowHasCourses() {
        // 明天（周一）有课：今晚就是该醒的那一晚，照排
        val today = LocalDate.of(2026, 9, 13) // 周日
        assertEquals(
            today,
            TomorrowPreviewReceiver.nextScheduledPreviewDay(courses, semester, today, today.atTime(21, 0)),
        )
    }

    @Test
    fun doesNotArmTonightWhenTomorrowHasNoCourses() {
        // 周六：明天是周日，没课 —— 今晚这一枪必然空转，不许排
        val saturday = LocalDate.of(2026, 9, 12)
        val day = TomorrowPreviewReceiver.nextScheduledPreviewDay(
            courses, semester, saturday, saturday.atTime(21, 0),
        )
        assertEquals("要排的是周日那晚（它的次日周一有课），不是周六", LocalDate.of(2026, 9, 13), day)
    }

    @Test
    fun stopsWhenNothingIsLeftToPreview() {
        // 学期已结束：往后 120 天都找不到可推的内容，事件入口什么都不排
        val holiday = LocalDate.of(2027, 2, 1)
        assertNull(TomorrowPreviewReceiver.nextScheduledPreviewDay(courses, semester, holiday, holiday.atTime(21, 0)))
        // 课表为空 / 还没配学期，同一口径
        assertNull(TomorrowPreviewReceiver.nextScheduledPreviewDay(emptyList(), semester, holiday, holiday.atTime(21, 0)))
        assertNull(
            TomorrowPreviewReceiver.nextScheduledPreviewDay(
                courses, semester = null, LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 13).atTime(21, 0),
            ),
        )
    }

    @Test
    fun rolloverNightIsReviewedToo() {
        // 学期最后一节可推的课是 2027-01-11（周一，第 19 周），该推它的那一晚是 01-10（周日）
        val lastPreviewNight = LocalDate.of(2027, 1, 10)
        assertEquals(
            "22:00 还没过时照排这一晚",
            lastPreviewNight,
            TomorrowPreviewReceiver.nextScheduledPreviewDay(courses, semester, lastPreviewNight, lastPreviewNight.atTime(21, 0)),
        )
        // 22:00 已过：闹钟会顺延到 01-11，而 01-11 的次日（周二）没有课 ——
        // 顺延那天从没参与过搜索，不复核就把链条就多排一晚空转（审计点名的那个边界）
        assertNull(
            TomorrowPreviewReceiver.nextScheduledPreviewDay(courses, semester, lastPreviewNight, lastPreviewNight.atTime(22, 30)),
        )
    }

    /**
     * 事件入口确实用的是上面那份判定，而且关掉开关时仍然撤销闹钟。
     *
     * 这条守卫认的是源码形状：入口一旦改回"读个开关就排明天"，
     * `schedule(context)` 那个写死明天的重载就会回来。
     */
    @Test
    fun eventEntryGoesThroughTheSharedJudgement() {
        val body = functionBody(
            mainText(BACKGROUND_SYNC_FILE),
            "fun scheduleTomorrowPreview(context: Context)",
        )

        assertTrue("事件入口要先问过课表", body.contains("nextScheduledPreviewDay("))
        assertTrue("关掉开关仍然必须撤销闹钟", body.contains("TomorrowPreviewScheduler.cancel(context)"))
        assertTrue(
            "不许再走那个不看课表、写死「明天」的重载",
            !body.replace("nextScheduledPreviewDay(", "").contains("schedule(context)"),
        )
    }

    // ---- 源码核对 ------------------------------------------------------------

    /** 函数体：从签名处按花括号配平切出来，注释不参与判断 */
    private fun functionBody(source: String, signature: String): String {
        val code = withoutComments(source)
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：函数改名或挪过家，这条守卫要跟着改" }
        val open = code.indexOf('{', code.indexOf(')', at))
        check(open >= 0) { "$signature 后面没有函数体" }
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) return code.substring(open, i + 1)
        }
        throw IllegalStateException("$signature 的花括号配不上对")
    }

    private fun withoutComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        return out.toString().lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    private fun mainText(relative: String): String {
        val root = findMainJavaDir()
        val file = File(root, relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛（跳过的守卫等于没守卫） */
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

    private companion object {
        const val BACKGROUND_SYNC_FILE = "com/buaa/schedule/widget/BackgroundSync.kt"
    }
}
