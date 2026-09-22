package com.buaa.schedule.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计页三态判定内核（[statsPageStageOf]）的表驱动单测（T74，台账 #115）。
 *
 * 台账 #115 的原话是「学期统计页首帧说'还没有课程可统计'，而首页明明有 18 门课」。
 * 这一族 bug 的形状不是"算错了"，而是**把两档并成了一档**：改前那一档分支只看
 * `courseCount == 0`，于是"还没读到"与"真的没课"共用同一句话。所以这张表钉的
 * 全是两档之间的接缝：就绪前 0 门 / 就绪后 0 门 / 就绪后 18 门 / 就绪信号与数据同时到，
 * 外加"数据先到、就绪信号后到"那一格（现下的 `uiState` 到不了，但必须答得确定）。
 *
 * 表里的条数全是**合成整数**：装机那 18 门课（库里 22 段排课）在这里只作为一个
 * "大于零的条数"出现，本卡真机侧的数量口径由 `SemesterStats` 那一族单测钉着。
 * 至于"改前那一帧到底拿到的是什么"，那是接线层的事实，由
 * [com.buaa.schedule.ui.stats.StatsPageStageWiringGuardTest] 扫源码钉，不在这里复刻。
 */
class StatsPageStageTest {

    // ---- ① 单格边界：两枚入参的每一种组合都要有确定答案 ----

    @Test
    fun stageTableAnswersEveryParameterCombination() {
        val table = listOf(
            // 就绪前 0 门：改前就是这一格说了假话（首帧的 initialValue 落在这里）
            Row("就绪前 0 门", ready = false, courseCount = 0, expected = StatsPageStage.Loading),
            // 就绪前 18 门：数据先到、就绪信号后到 —— 仍然不许提前断言，也不许说空
            Row("就绪前 18 门", ready = false, courseCount = 18, expected = StatsPageStage.Loading),
            // 就绪后 0 门：这一次"还没有课程可统计"是真话
            Row("就绪后 0 门", ready = true, courseCount = 0, expected = StatsPageStage.Empty),
            // 就绪后 18 门：就绪信号与数据同一帧到（装机实测就是这一格）
            Row("就绪后 18 门", ready = true, courseCount = 18, expected = StatsPageStage.Ready),
            // 一门课的边界：Ready 的门槛是 >0，不是"够多了才画"
            Row("就绪后 1 门", ready = true, courseCount = 1, expected = StatsPageStage.Ready),
            // 条数为负（上游算歪时的兜底）：按空处理，不抛
            Row("就绪后 -1 门", ready = true, courseCount = -1, expected = StatsPageStage.Empty),
            // 就绪前条数为负：先看就绪，负数那一档轮不到
            Row("就绪前 -1 门", ready = false, courseCount = -1, expected = StatsPageStage.Loading),
        )
        for (row in table) {
            assertEquals(
                "${row.name}：ready=${row.ready} courseCount=${row.courseCount} 应当答 ${row.expected}",
                row.expected,
                statsPageStageOf(ready = row.ready, courseCount = row.courseCount),
            )
        }
    }

    /** 就绪这一票优先：把顺序写反（先看条数）就是 #115 的成因本体 */
    @Test
    fun readinessOutranksCourseCount() {
        for (count in listOf(0, 18, Int.MAX_VALUE)) {
            assertEquals(
                "没就绪的时候，$count 门课也只能答 Loading——就绪前不许对'有没有课'下断言",
                StatsPageStage.Loading,
                statsPageStageOf(ready = false, courseCount = count),
            )
        }
    }

    // ---- ② 帧序列：这一页从进入那一刻起会依次答出什么 ----

    /**
     * 装机实测那一串：首帧 `loading=true courses=0`，3080ms 后 `loading=false courses=18`。
     *
     * 钉的是"中间那一档绝不能是 Empty"——改前正是这一串的第一帧把 Empty 画上了屏。
     */
    @Test
    fun framesOfAWarmDatabaseNeverPassThroughTheEmptyStage() {
        val stages = frames(
            listOf(
                Frame(ready = false, courseCount = 0),
                Frame(ready = true, courseCount = 18),
            ),
        )
        assertEquals(listOf(StatsPageStage.Loading, StatsPageStage.Ready), stages)
        assertNotEquals(
            "有课的库在就绪那一帧之前不许出现空态文案（台账 #115 就是这句话先上了屏）",
            StatsPageStage.Empty,
            stages.first(),
        )
    }

    /** 真的没课：加载那一档照样要先走一遍，然后才轮得到那句断言 */
    @Test
    fun framesOfAColdDatabaseSayEmptyOnlyAfterReadiness() {
        val stages = frames(
            listOf(
                Frame(ready = false, courseCount = 0),
                Frame(ready = true, courseCount = 0),
            ),
        )
        assertEquals(listOf(StatsPageStage.Loading, StatsPageStage.Empty), stages)
    }

    /** 用户把课删光：Ready → Empty，不许退回 Loading（退回会像是"又没读到"） */
    @Test
    fun deletingEveryCourseLandsOnEmptyNotLoading() {
        val stages = frames(
            listOf(
                Frame(ready = true, courseCount = 18),
                Frame(ready = true, courseCount = 0),
            ),
        )
        assertEquals(listOf(StatsPageStage.Ready, StatsPageStage.Empty), stages)
    }

    /** 新增第一门课：Empty → Ready，中间不许再插一帧 Loading（数据与就绪信号同一帧） */
    @Test
    fun firstImportFlipsStraightFromEmptyToReady() {
        val stages = frames(
            listOf(
                Frame(ready = false, courseCount = 0),
                Frame(ready = true, courseCount = 0),
                Frame(ready = true, courseCount = 1),
            ),
        )
        assertEquals(listOf(StatsPageStage.Loading, StatsPageStage.Empty, StatsPageStage.Ready), stages)
    }

    /** 就绪之后不会再回 Loading：`loading` 一旦被 combine 写成 false 就不会翻回去 */
    @Test
    fun readinessIsMonotonicInPractice() {
        val stages = frames(
            listOf(
                Frame(ready = false, courseCount = 0),
                Frame(ready = true, courseCount = 18),
                Frame(ready = true, courseCount = 17),
                Frame(ready = true, courseCount = 18),
            ),
        )
        assertEquals(StatsPageStage.Loading, stages.first())
        assertTrue(
            "就绪之后又冒出 Loading：这一页会在用户眼前闪回'正在读取'，$stages",
            stages.drop(1).none { it == StatsPageStage.Loading },
        )
    }

    // ---- ③ 三档本身：互斥、穷尽、顺序就是时间顺序 ----

    @Test
    fun threeStagesAreDeclaredInTimeOrder() {
        assertEquals(
            "声明顺序写死了是 加载中 → 真的空 → 有内容（守卫与调用点都按这个顺序读）",
            listOf(StatsPageStage.Loading, StatsPageStage.Empty, StatsPageStage.Ready),
            StatsPageStage.entries.toList(),
        )
    }

    @Test
    fun everyStageIsReachable() {
        val reached = setOf(
            statsPageStageOf(ready = false, courseCount = 0),
            statsPageStageOf(ready = true, courseCount = 0),
            statsPageStageOf(ready = true, courseCount = 18),
        )
        assertEquals("有一档谁都到不了，说明调用点少喂了一件事实：$reached", StatsPageStage.entries.toSet(), reached)
    }

    // ---- 表的小工具 ----

    private data class Row(val name: String, val ready: Boolean, val courseCount: Int, val expected: StatsPageStage)

    /** 一帧 uiState 落到这一页上的样子：只带内核吃的那两枚事实 */
    private data class Frame(val ready: Boolean, val courseCount: Int)

    private fun frames(frames: List<Frame>): List<StatsPageStage> =
        frames.map { statsPageStageOf(ready = it.ready, courseCount = it.courseCount) }
}
