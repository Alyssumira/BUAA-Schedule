package com.buaa.schedule.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实况卡片正文（两行：静态课次信息 + 会走的倒计时）。
 *
 * 课中的卡以前只有一行节次，用户的评价是"简陋"；课前则压根上不了岛，因为它是另发的
 * 一条带 chronometer 的普通提醒。现在两段共用同一套取文案的函数，**只差 phase 参数**，
 * 这里钉的就是这个形状：行数、分隔符、以及"缺项不能留下悬空的 · "。
 */
class LiveUpdateBodyTextTest {

    private val minute = 60_000L

    /** 只钉形状不钉具体时刻：HH:mm 走系统默认时区，用例不该跟着机器配置漂 */
    private val timeRange = Regex("""\d{2}:\d{2}–\d{2}:\d{2}""")

    private val start = 1_760_000_000_000L
    private val end = start + 90 * minute

    @Test
    fun metaLineJoinsOnlyThePartsThatExist() {
        assertEquals("第 3-4 节 · 10:00–11:30 · 主M301", liveMetaLine("第 3-4 节", "10:00–11:30", "主M301"))
        assertEquals("第 3-4 节 · 主M301", liveMetaLine("第 3-4 节", null, "主M301"))
        assertEquals("第 3-4 节", liveMetaLine("第 3-4 节", "", "   "))
        // 三项全空就是空串：卡片上不能出现一行只有 " · " 的东西
        assertEquals("", liveMetaLine("", null, null))
    }

    @Test
    fun countdownLineNamesTheBellItIsCountingTo() {
        val now = start
        // 课前倒计到上课铃、课中倒计到下课铃：数字相同但指向不同的事件，说错铃比不说更糟
        assertEquals("还有 38 分钟上课", liveCountdownLine(LivePhase.BEFORE_CLASS, now + 38 * minute, now))
        assertEquals("还有 38 分钟下课", liveCountdownLine(LivePhase.IN_CLASS, now + 38 * minute, now))
        // 过点之后不出现负数，也不说"还有 0 分钟"
        assertEquals("马上上课", liveCountdownLine(LivePhase.BEFORE_CLASS, now, now))
        assertEquals("马上上课", liveCountdownLine(LivePhase.BEFORE_CLASS, now - 5 * minute, now))
        assertEquals("即将下课", liveCountdownLine(LivePhase.IN_CLASS, now - 5 * minute, now))
    }

    @Test
    fun minutesLeftRoundsUpAndClamps() {
        // 向上取整：还剩 1 秒也不能写"0 分钟"，那等于告诉用户已经打铃了
        assertEquals(1L, minutesLeft(start + 1_000L, start))
        assertEquals(2L, minutesLeft(start + 90_000L, start))
        assertEquals(0L, minutesLeft(start - minute, start))
        // 正文那一行必须由同一个函数取数：它与岛上小字并排显示，两套口径会当场互相打脸
        assertEquals(
            "还有 ${minutesLeft(end, start)} 分钟下课",
            liveCountdownLine(LivePhase.IN_CLASS, end, start),
        )
    }

    @Test
    fun bodyIsTwoLinesWithTheCountdownLast() {
        val now = end - 38 * minute
        val lines = liveBody("第 3-4 节", start, end, "主M301", LivePhase.IN_CLASS, now).lines()

        assertEquals(2, lines.size)
        // 第一行必须带上时间区间——只有节次的旧版一行文案就是"简陋"本身
        assertTrue("第一行缺时间区间：${lines[0]}", timeRange.containsMatchIn(lines[0]))
        assertTrue(lines[0].contains("第 3-4 节"))
        assertTrue(lines[0].contains("主M301"))
        assertEquals("还有 38 分钟下课", lines[1])
    }

    @Test
    fun bothPhasesProduceTheSameCarrierShape() {
        // 同一条通知 id 上换阶段：行数变了会让岛上的排版在打铃那一刻整体跳一下
        val before = liveBody("第 1 节", start, end, "主M301", LivePhase.BEFORE_CLASS, start - 20 * minute)
        val during = liveBody("第 1 节", start, end, "主M301", LivePhase.IN_CLASS, start + 20 * minute)

        assertEquals(before.lines().size, during.lines().size)
        assertEquals(before.lineSequence().first(), during.lineSequence().first())
        assertTrue(before.endsWith("分钟上课"))
        assertTrue(during.endsWith("分钟下课"))
    }

    @Test
    fun bodyKeepsItsTwoLinesWhenLocationAndSectionAreMissing() {
        // 补课/讲座类课程常没有地点，节次也可能为空：这时第二行仍然要在，卡片不能塌成一行
        val lines = liveBody("", start, end, null, LivePhase.IN_CLASS, end - 5 * minute).lines()

        assertEquals(2, lines.size)
        assertTrue("只剩区间时不该有分隔符：${lines[0]}", timeRange.matches(lines[0]))
        assertFalse(lines[0].contains("·"))
        assertEquals("还有 5 分钟下课", lines[1])
    }
}
