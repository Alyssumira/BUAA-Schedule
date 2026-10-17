package com.buaa.schedule.data.import

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在册 #727⓪：`TeachingScheduleParser.parse` 对**语法合法而形状怪**的正文不许抛。
 *
 * ## 这张表钉的是判据本身，不钉 IO
 * 判据函数 [TeachingScheduleParser.parse] 是纯 JVM 的：零 android import、不读盘、不读钟，
 * `Build.VERSION`／权限／设备度量一概不碰 —— 那些设备侧事实只许由调用点当参数递进来。
 * 本表因此只喂正文 String，不去碰 `SpecialDayCache` 那两只 `File` 读写手（文件形状一族
 * 由 `SpecialDayCacheAtomicWriteGuardTest` 钉，同一张铁律：非纯函数只有守卫这一条落点）。
 *
 * ## 抛出去的那一路为什么能把进程打死（盘面现量三处）
 * - `SpecialDayCache.loadAll`：那手 `runCatching` 只罩住**读文件**，`parse` 裸着。
 * - `TeachingScheduleParser.parse`：那手 `runCatching` 只罩住 `parseToJsonElement`，`walk` 与 `extractDay` 裸着。
 * - `extractDay`：日期／类型／备注三格各取一次标量文本，用的是 kotlinx.serialization 那个
 *   **非标量即抛**的取法 —— 语法合法而形状怪（`{"rq":{"a":1}}` 这一形）就是从这一格抛出去的。
 *
 * ⇒ 一枚形状怪的缓存文件让 `walk` 抛 ⇒ 整条 `loadAll` 抛给调用点；第一枚调用点长在
 * `ScheduleViewModel` 的 `init` 里、是一枚裸 `launch`，而 main 侧没有 `CoroutineExceptionHandler`
 * （那两枚 `CoroutineExceptionHandler` 字样都在注释里，说的恰恰是"没有"）⇒ 冷启动即崩。
 * 第二枚调用点在 `refreshSpecialDays` 的 Fetch 档里，外层只有 `try/finally`、没有 `catch`，同样裸着。
 *
 * ## 三组格（每格红的都是不同的事）
 * - **A** 形状怪逐枚点名（在册 7 枚）：一枚都不许抛；产出名册逐枚写死 —— 有的整枚节点丢掉，
 *   有的只是那一格折成 null 而条目照旧产出（"丢该节点"不等于"丢整枚文件"，这一档差别正是选落点的理由）。
 * - **B** 正常路径不许被邻居带倒（在册 5 枚）：同一棵子里坏节点与好节点并存 ⇒ 好节点一枚不少、按日期升序。
 *   把兜底扩成"整棵树一起吞"的话，红的就是这一组与 A 的产出名册。
 * - **C** 语法不合法那一档口径一字不动（在册 6 枚）：空白／非 JSON／截断 ⇒ 空列表且不抛。
 *   这组今天就是绿的，留着是证明修 A 没把 C 那一手改道。
 */
class TeachingScheduleParserPoisonNodeTableTest {

    /** A：形状怪的正文只许"丢那一枚节点／折那一格"，不许把抛出送给调用点。 */
    @Test
    fun malformedNodesAreDroppedInsteadOfThrownToTheCaller() {
        val rows = listOf(
            Row(
                "日期格是对象 ⇒ 整枚节点丢掉，兄弟照旧",
                """{"datas":{"JJR":[{"rq":"2026-10-01","mc":"国庆节","lx":"1"},""" +
                    """{"rq":{"a":1},"mc":"形状怪的日期格"}]}}""",
                "2026-10-01休",
            ),
            Row(
                "日期格是数组 ⇒ 整枚节点丢掉",
                """{"datas":{"JJR":[{"rq":"2026-10-02","mc":"国庆节","lx":"1"},""" +
                    """{"rq":["2026-10-03"],"mc":"日期写成数组"}]}}""",
                "2026-10-02休",
            ),
            Row(
                "类型格是对象 ⇒ 那一格折成 null，条目按备注格照样产出",
                """{"datas":{"JJR":[{"rq":"2026-10-04","mc":"假期","lx":{"a":1}},""" +
                    """{"rq":"2026-10-05","mc":"劳动节","lx":"2"}]}}""",
                "2026-10-04休 ／ 2026-10-05休",
            ),
            Row(
                "备注格是数组 ⇒ 那一格折成 null，条目按类型格照样产出",
                """{"datas":{"JJR":[{"rq":"2026-10-06","mc":["国庆"],"lx":"节假日"}]}}""",
                "2026-10-06休",
            ),
            Row(
                "取证原文那一枚：正文顶层就是形状怪的日期格 ⇒ 空列表",
                """{"rq":{"a":1}}""",
                "",
            ),
            Row(
                "数组套数组里的形状怪 ⇒ 递归两手都接得住",
                """{"JJR":[[{"rq":{"x":[1]}}],{"rq":"2026-10-07","mc":"中秋节"}]}""",
                "2026-10-07休",
            ),
            Row(
                "坏邻居不许带走一枚「班」档",
                """{"datas":{"JJR":[{"rq":"2026-09-28","mc":"国庆节调休上班","lx":"2"},""" +
                    """{"mc":"缺日期","lx":{"a":1}}]}}""",
                "2026-09-28班",
            ),
        )
        val problems = problemsOf(rows)
        assertTrue(
            "形状怪的那一手抛给调用点的代价不是「这一月没标注」，而是整条 loadAll 抛出去 ⇒ " +
                "`init` 里那枚裸 launch 把它送进线程未捕获异常 ⇒ 冷启动即崩（在册 " + rows.size + " 枚，逐枚）：" +
                problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** B：兜底的射程止于那一枚节点 —— 好条目不许因为邻居坏而一起消失。 */
    @Test
    fun goodEntriesSurviveTheirMalformedNeighbours() {
        val rows = listOf(
            Row(
                "对照：夹具本身解得出两条",
                GOOD_PAIR,
                "2026-10-01休 ／ 2026-10-02休",
            ),
            Row(
                "坏节点夹在两只好节点中间 ⇒ 两枚都在",
                """{"datas":{"JJR":[{"rq":"2026-10-01","mc":"国庆节","lx":"1"},""" +
                    """{"rq":{"a":1},"mc":"坏邻居"},{"rq":"2026-10-02","mc":"国庆节","lx":"1"}]}}""",
                "2026-10-01休 ／ 2026-10-02休",
            ),
            Row(
                "坏节点长在顶层兄弟字段上 ⇒ 不外溢到 datas",
                """{"rq":{"a":1},"datas":{"JJR":[{"rq":"2026-10-01","mc":"国庆节","lx":"1"},""" +
                    """{"rq":"2026-10-02","mc":"国庆节","lx":"1"}]}}""",
                "2026-10-01休 ／ 2026-10-02休",
            ),
            Row(
                "整棵子树都是坏节点 ⇒ 空列表，而不是抛出",
                """{"datas":{"JJR":[{"rq":{"a":1}},{"lx":["x"]},{"mc":{"a":1}}]}}""",
                "",
            ),
            Row(
                "坏节点不许打乱「按日期升序」那一手",
                """{"datas":{"JJR":[{"rq":"2026-10-10","mc":"节"},{"rq":{"a":1}},""" +
                    """{"rq":"2026-10-09","mc":"节"}]}}""",
                "2026-10-09休 ／ 2026-10-10休",
            ),
        )
        val problems = problemsOf(rows)
        assertTrue(
            "正常路径被吞了（在册 " + rows.size + " 枚）。这一组钉的是落点选择本身：把兜底扩成「整棵树一起接住」 " +
                "（在 parse 或 loadAll 那一头套一手 runCatching）行为上一枚坏节点就能抹掉整月的标注，" +
                "而那种吞法在这张表里当场红：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** C：语法不合法那一档的口径（空列表、不抛）一字不动。 */
    @Test
    fun malformedSyntaxStillFoldsIntoEmptyListWithoutThrowing() {
        val rows = listOf(
            Row("空正文", "", ""),
            Row("只有空白", "   ", ""),
            Row("压根不是 JSON", "not json at all", ""),
            Row("截断在键后面", """{"datas":""", ""),
            Row("截断在半截数组上", "[1,2", ""),
            Row("没闭合的字符串", "\"unterminated", ""),
        )
        val problems = problemsOf(rows)
        assertTrue(
            "语法不合法那一档改道了（在册 " + rows.size + " 枚）。这一档原本是「正文语法不合法 ⇒ 跳过这一枚文件」，" +
                "而它是 `SpecialDayCache` 那两档结论里的第一档；修形状怪那一档不许把它一起动掉：" +
                problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    // ---- 夹具与小工具 ------------------------------------------------------------------------------

    /** 逐枚跑一遍：不合格的行念成一句问题串，合格交回空名册。 */
    private fun problemsOf(rows: List<Row>): List<String> = rows.mapNotNull { row ->
        val actual = verdictOf(row.raw)
        if (actual == row.expected) {
            null
        } else {
            row.label + "：实到「" + actual + "」／写死「" + row.expected + "」｜正文 " + row.raw
        }
    }

    /**
     * 跑一枚正文。**抛出被折成读数串**而不是让异常飞出去：这样红是 failure、读数里点名是哪一枚 payload
     * 抛的、抛的是哪一类，而不会在报告里缩成一枚"某处炸了"的 error。
     */
    private fun verdictOf(raw: String): String {
        val outcome = runCatching { TeachingScheduleParser.parse(raw) }
        val thrown = outcome.exceptionOrNull()
        if (thrown != null) return "抛出 " + thrown::class.java.name
        return outcome.getOrDefault(emptyList())
            .joinToString(separator = " ／ ") { day ->
                day.date.toString() + if (day.isHoliday) "休" else "班"
            }
    }

    private class Row(
        val label: String,
        val raw: String,
        val expected: String,
    )

    private companion object {
        const val GOOD_PAIR =
            """{"datas":{"JJR":[{"rq":"2026-10-01","mc":"国庆节","lx":"1"},""" +
                """{"rq":"2026-10-02","mc":"国庆节","lx":"1"}]}}"""
    }
}
