package com.buaa.schedule.data.calendar

import android.provider.CalendarContract
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.MarkerBlindReason
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.MarkerRead
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.ReclaimPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T265③：所有权标记那一枚**读侧契约**（纯 JVM，零设备）。
 *
 * 这三枚判据是"回收范围"那条链上唯一一枚直接决定「删谁的」的东西，所以两侧都要钉：
 *
 * - **朝宽**（判据放宽会删到用户的日程）：selection 里长出第二枚列（`OR` / `TITLE` / `CALENDAR_ID` /
 *   `IN`）或实参表多出第二枚 —— 那就不再是"等值匹配本包名"这一件事。
 *   投影里长出 TITLE / 日历名之类的列 = 有人在拿"看着像我们建的"当判据（契约上写明刻意没有它们）。
 * - **朝窄**（判据收窄会留下谁的）：按 `CALENDAR_ID` 收窄到当前目标日历 —— 换过目标日历的用户
 *   就永远删不掉旧日历上的那些（映射表里的行本来就是跨日历的），旧孤儿继续住在用户日历上。
 *
 * [CalendarOwnershipQuery.fold] 那五行是档位本尊：`Answered + 空集`（一次可信的"没有"）与
 * `Refused`（这台机器不肯答）必须分开，而**没问过**（NotAttempted）那一档即使手里有 id 也不许交回 ——
 * 那是朝宽那一刀的另一种形状（把没验证过的集合当可信枚举去删）。
 */
class CalendarOwnershipQueryTest {

    @Test
    fun projectionIsTheIdColumnAlone() {
        val columns = CalendarOwnershipQuery.projection.toList()
        assertEquals(
            "投影必须只有 _ID 一枚列（回收只需要 id；多一枚列就是在给『看着像我们建的』那种模糊判据留位置）：",
            listOf(CalendarContract.Events._ID),
            columns,
        )
        assertTrue("不允许出现空列名：${columns.joinToString()}", columns.none { it.isBlank() })
    }

    @Test
    fun selectionIsAnExactPackageMatchWithNoSecondColumnAndNoCalendarNarrowing() {
        val selection = CalendarOwnershipQuery.selection
        assertTrue(
            "selection 必须按所有权标记那一枚列等值匹配，实际是「$selection」",
            selection.contains(CalendarContract.Events.CUSTOM_APP_PACKAGE),
        )
        assertTrue("selection 必须用占位符而不是把包名拼进串里：$selection", selection.contains("?"))
        // 朝宽那一侧：等值匹配之外长出来的每一枚都是第二把尺
        for (forbidden in listOf("OR", "or ", "IN (", "LIKE")) {
            assertFalse(
                "selection 里不许出现「$forbidden」—— 等值匹配本包名是唯一一枚既不宽也不窄的判据：" +
                    selection,
                selection.contains(forbidden),
            )
        }
        // 朝窄那一侧：按日历收窄 = 换过目标日历的用户永远删不掉旧日历上的那些
        for (narrowing in listOf(
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )
        ) {
            assertFalse(
                "selection 里不许出现「$narrowing」：收窄到某个日历/某类标题会把旧目标日历上的孤儿永久留下：" +
                    selection,
                selection.contains(narrowing),
            )
        }
    }

    @Test
    fun selectionArgsIsExactlyTheOwnPackageName() {
        val args = CalendarOwnershipQuery.selectionArgs("com.buaa.schedule")
        assertEquals(
            "实参表必须恰一枚（多一枚就是在放宽或收窄范围）：${args.joinToString()}",
            1,
            args.size,
        )
        assertEquals("唯一那枚实参就是本包名：", "com.buaa.schedule", args.single())
    }

    @Test
    fun foldSeparatesAnsweredEmptyFromRefusedAndNeverHandsBackIdsWithoutAsking() {
        val cells = listOf(
            // 问到了答案（哪怕答案是"一枚都没有"）
            FoldCell("问到了两枚 ⇒ Answered + 那两枚", listOf(1L, 2L), true, MarkerRead.Answered, setOf(1L, 2L)),
            FoldCell("问了而答案是空集 ⇒ Answered + 空集（这是一次可信的『没有』）", emptyList(), true, MarkerRead.Answered, emptySet()),
            FoldCell("问失败（异常/null cursor）⇒ Refused + 空集（不是『答了零枚』）", null, true, MarkerRead.Refused, emptySet()),
            FoldCell("没问 ⇒ NotAttempted + 空集", null, false, MarkerRead.NotAttempted, emptySet()),
            // 朝宽那一刀：没问过，手里那份 id 也不许被当可信枚举交回
            FoldCell(
                "**朝宽那一刀**：没问而调用点递来 id ⇒ 仍然 NotAttempted + 空集",
                listOf(7L, 8L, 9L), false, MarkerRead.NotAttempted, emptySet(),
            ),
        )
        for (cell in cells) {
            val (read, ids) = CalendarOwnershipQuery.fold(cell.rows, cell.attempted)
            assertEquals("${cell.label} —— 档位不对：", cell.expectedRead, read)
            assertEquals("${cell.label} —— 交回的 id 集合不对：", cell.expectedIds, ids)
        }
    }

    private data class FoldCell(
        val label: String,
        val rows: List<Long>?,
        val attempted: Boolean,
        val expectedRead: MarkerRead,
        val expectedIds: Set<Long>,
    )

    /**
     * 契约与内核的接缝：三档 marker 各自喂进 `planReclaim` 之后，删除范围的**来源**必须还是那句话
     * ——「标记 ∪ 映射」或「只有映射」或「一枚都没有」。这一格是读侧契约的意义所在：
     * fold 交回的档位若与内核要的档位接不上，前面四格都白钉。
     */
    @Test
    fun foldedTiersFeedTheKernelWithoutEverWideningBeyondMarkerPlusMapping() {
        val mappings = setOf(3L, 4L)
        // 标记答回三枚（其中 3 映射也记着）⇒ 范围 = 标记 ∪ 映射，孤儿是 1、2 那两枚
        val (answered, answeredIds) = CalendarOwnershipQuery.fold(listOf(1L, 2L, 3L), attempted = true)
        assertEquals(
            "Answered 那一档喂进内核：并集必须含映射多出的 4，孤儿计数 2：",
            ReclaimPlan.ByOwnershipMarker(setOf(1L, 2L, 3L, 4L), orphansBeyondMappings = 2),
            CalendarReclaimPlanner.planReclaim(answered, answeredIds, mappings),
        )
        // 答了空集而映射有货 ⇒ 不许升级成按标记回收
        val (answeredEmpty, emptyIds) = CalendarOwnershipQuery.fold(emptyList(), attempted = true)
        assertEquals(
            "Answered + 空集而映射记着两枚 ⇒ 只删那两枚，理由是 AnsweredEmptyAgainstMappings：",
            ReclaimPlan.RecordedOnly(mappings, MarkerBlindReason.AnsweredEmptyAgainstMappings),
            CalendarReclaimPlanner.planReclaim(answeredEmpty, emptyIds, mappings),
        )
        // provider 拒答 ⇒ 只删映射那两枚（用户自己的日程不在里面）
        val (refused, refusedIds) = CalendarOwnershipQuery.fold(null, attempted = true)
        assertEquals(
            "Refused ⇒ 只删映射那两枚：",
            ReclaimPlan.RecordedOnly(mappings, MarkerBlindReason.ProviderRefused),
            CalendarReclaimPlanner.planReclaim(refused, refusedIds, mappings),
        )
        // 朝宽那一刀：没问过，调用点手里那份 id 一枚都不许进删除范围
        val (notAttempted, notAttemptedIds) = CalendarOwnershipQuery.fold(listOf(100L, 200L), attempted = false)
        assertEquals(
            "NotAttempted 而手里有 id ⇒ 那些 id 一枚都不许进范围（等于「没验证过的集合拿来当枚举」）：",
            ReclaimPlan.RecordedOnly(mappings, MarkerBlindReason.NotAttempted),
            CalendarReclaimPlanner.planReclaim(notAttempted, notAttemptedIds, mappings),
        )
        assertEquals(
            "靶子：NotAttempted 那一档 fold 交回的 id 集合本身也必须是空集（不是靠内核忽略它才没删）：",
            emptySet<Long>(),
            notAttemptedIds,
        )
    }
}
