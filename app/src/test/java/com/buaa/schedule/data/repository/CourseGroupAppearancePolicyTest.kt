package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T128：把「组外观那一支往一行上该盖哪一版、这一行到底用不用写」钉成表驱动单测（纯 JVM）。
 *
 * 判据本体是 [groupAppearanceRow]，完整账写在 `CourseGroupAppearancePolicy.kt` 段首。
 * 这张表量的是两件事：
 * 1. **改了谁** —— 交回去的那一版逐字段等于目标态（名称/地点/校区/两枚颜色维/学分），
 *    而**时间、教师、周次三维一个字都不许动**（同组片段本就各有时间安排）；
 * 2. **动没动** —— 与传入那一行逐字段相等时交回 **null**，也就是「这一行不必写」。
 *    第 2 件就是 T122 那枚扳机的内容本体：管理页反复点当前已选中那块色板时，
 *    这一支必须能报出「整组一行都没改」，压栈点才判得出同值重写。
 *
 * 档位（如实写明）：`:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric、没有能在 JVM 上跑 Room
 * 的缝**，所以本文件钉的是**判据**；「仓储那一支与 VM 有没有真的用上它」由源码核对守卫
 * `UndoUpdateEntryGuardTest` 第 ③⑥ 层钉，**SQLite 级「真写一行、再写一次同一行」这一档没做**（红线零设备）。
 *
 * 两侧都要有格子：**朝宽扭**一次（把末尾那枚 `takeIf { it != original }` 摘掉 ⇒ 全等也交回一行）
 * 红在表 ②；**朝窄扭**一次（把 `appearanceChanged` 收成只看 `colorIndex`）红在表 ③ 里
 * 「只改名称/地点/学分也要立旗」那几格；再把旗标那一枚析取写成恒 `false` ⇒ 表 ① 整表红。
 */
class CourseGroupAppearancePolicyTest {

    // ─────────────── ① 真的改了外观：交回目标态那一版，时间三维一字不动 ───────────────

    @Test
    fun `改掉了的那些行交回目标态 时间教师周次三维一字不动`() {
        for (cell in writeCells) {
            val original = cell.original
            val written = groupAppearanceRow(original, cell.incoming, cell.customColorArgb)
            assertNotNull(
                "判据格子「${cell.name}」：这一趟确实改了外观，应当交回一行而不是 null" +
                    "（红了 = 判据朝窄扭，把一次真改动判成不必写）\n" +
                    "  原行：${describe(original)}\n  incoming：${describe(cell.incoming)}",
                written,
            )
            assertEquals(
                "判据格子「${cell.name}」落库那一版对不上：\n" +
                    "  实到 ${describe(written)}\n  应为 ${describe(cell.target(original))}\n" +
                    "复算：awk '/^internal fun groupAppearanceRow/,/^}/' " +
                    "app/src/main/java/com/buaa/schedule/data/repository/CourseGroupAppearancePolicy.kt",
                cell.target(original),
                written,
            )
        }
    }

    // ─────────────── ② 逐字段全等：一行都不必写（T122 那枚扳机的内容本体） ───────────────

    @Test
    fun `逐字段本来就一样 那一行不必写`() {
        for (cell in noopCells) {
            val original = cell.original
            val written = groupAppearanceRow(original, cell.incoming, cell.customColorArgb)
            assertNull(
                "判据格子「${cell.name}」：这一行逐字段已经是目标态，必须交回 null（= 不必写）。\n" +
                    "  原行：${describe(original)}\n  incoming：${describe(cell.incoming)}\n" +
                    "红了 = 判据朝宽扭（比如把末尾的 takeIf 那一闸摘掉），于是「反复点同一块色板」" +
                    "这种同值重写又会写一遍库、并被压栈点当成「动过了东西」—— " +
                    "那正是 §9.5② 那枚 no-op 撤销条目的出处",
                written,
            )
        }
    }

    // ─────────────── ③ 旗标跟着真改动走：空操作不许新立、立过的不许洗 ───────────────

    @Test
    fun `旗标只跟着真改动走 已经立着的也不许被空操作洗回去`() {
        val untouched = row()
        assertNull(
            "外观全等 + 原本没标 manual ⇒ 不必写，也就「不许」只为立旗去写那一行" +
                "（旧实现正是这么干的：一枚无条件字面量盖给整组）。" +
                "红了 = 判据朝宽扭，反复点色板又开始把整组踢出教务匹配",
            groupAppearanceRow(untouched, untouched, untouched.customColorArgb),
        )

        val alreadyFlagged = row(isManual = true)
        assertNull(
            "外观全等 + 原本已标 manual ⇒ 也不必写：粘住的旗标不需要被再确认一次。" +
                "这一格同时钉住反方向 —— 谁改成「重写一遍、把旗标冲回 false」，" +
                "一行早已改过的课就又掉进教务匹配（同 ui/home/ManualTimeOverridePolicy.kt 那笔账）",
            groupAppearanceRow(alreadyFlagged, alreadyFlagged, alreadyFlagged.customColorArgb),
        )

        val recolored = alreadyFlagged.copy(colorIndex = 3)
        assertEquals(
            "改动了外观 + 原本已标 ⇒ 交回的那一版旗标必须仍是 true（不许被洗回 false）",
            true,
            groupAppearanceRow(alreadyFlagged, recolored, recolored.customColorArgb)?.isManualOverride,
        )

        for (needle in listOf("name", "location", "campus", "credit")) {
            val incoming = withField(needle, row(colorIndex = 2))
            val written = groupAppearanceRow(row(), incoming, incoming.customColorArgb)
            assertEquals(
                "只改「$needle」这一维也算手改过这门课 ⇒ 旗标必须立起来。" +
                    "红了 = 判据朝窄扭，把这一维漏在 appearanceChanged 之外：" +
                    "那一行改了却不标，下次教务刷新就直接把它冲掉",
                true,
                written?.isManualOverride,
            )
            assertEquals(
                "只改「$needle」时其余外观维也照旧整组同步一遍（落库那一版的名称必须是 incoming 的那个）",
                incoming.name,
                written?.name,
            )
        }
    }

    // ─────────────── ④ 学分那一维的 null 口径：「这次没填」不算改动 ───────────────

    @Test
    fun `学分为 null 表示这次没填 不许把兄弟片段已有的学分抹掉`() {
        assertNull(
            "原行 credit=3.0、incoming credit=null（这次没填）⇒ 逐字段没变，不必写。" +
                "红了 = 把 null 当成「填了 0 分」，整组学分被抹平",
            groupAppearanceRow(row(credit = 3.0), row(credit = null), null),
        )
        assertEquals(
            "原行 credit=3.0、incoming credit=2.0 ⇒ 改到的是学分，交回的那一版必须是 2.0",
            2.0,
            groupAppearanceRow(row(credit = 3.0), row(credit = 2.0), null)?.credit,
        )
        assertNull(
            "原行 credit=null、incoming credit=null ⇒ 仍不必写（不许凭空造一个 0.0 出来当改动）",
            groupAppearanceRow(row(credit = null), row(credit = null), null),
        )
    }

    // ─────────────── ⑤ 非法自定义色：内核只认调用点归一化后递进来的那一枚参数 ───────────────

    @Test
    fun `自定义色取调用点归一化后的那一枚 不取 incoming 里的脏值`() {
        val original = row(customColorArgb = 0xFF112233L)
        val incoming = original.copy(colorIndex = 4, customColorArgb = -1L)
        val written = groupAppearanceRow(original, incoming, customColorArgb = null)
        assertEquals(
            "incoming 带着越界色 -1，调用点按 CourseConstraints.normalizeCustomColorArgb 归一化成 null " +
                "递进来 ⇒ 落库那一版的自定义色必须是 null、颜色位取 incoming 的 4。" +
                "红了 = 内核自己去读了 incoming 那枚脏色，非法色又会扩散到同组所有片段",
            incoming.copy(customColorArgb = null, isManualOverride = true),
            written,
        )
        assertNull(
            "反过来：归一化以后与那一行逐字段相等（色没变、只是 incoming 带了枚脏色）⇒ 不必写",
            groupAppearanceRow(row(customColorArgb = null), row(customColorArgb = -1L), null),
        )
    }

    // ─────────────── ⑥ 不许长出第二把尺子：判据本体全仓一份、且仍是纯判据 ───────────────

    @Test
    fun `判据本体全仓一份 组那一支没有旁路`() {
        val source = policySource()
        val code = stripComments(source)
        assertEquals(
            "internal fun groupAppearanceRow( 在内核文件里恰好一处定义。" +
                "长出第二处 = 同一件事两把尺子（本仓算违反）\n" +
                "复算：grep -c \"internal fun groupAppearanceRow(\" " +
                "app/src/main/java/com/buaa/schedule/data/repository/CourseGroupAppearancePolicy.kt",
            1,
            Regex("internal fun groupAppearanceRow\\(").findAll(code).count(),
        )
        assertEquals(
            "内核文件里不许再出现无条件的 isManualOverride = true 那一枚字面量" +
                "（段首 KDoc 抄了它本身，故本格先剥注释）。\n" +
                "复算：grep -n \"isManualOverride = true\" " +
                "app/src/main/java/com/buaa/schedule/data/repository/CourseGroupAppearancePolicy.kt\n" +
                "落点册子（文件 × 枚数）由 ManualTimeOverrideWiringGuardTest 第 ⑤ 层钉，这一格只管本文件",
            0,
            Regex("isManualOverride\\s*=\\s*true").findAll(code).count(),
        )
        assertEquals(
            "内核必须仍是纯判据：零 android import、零时钟读取（外部事实一律当参数递进来）。\n" +
                "复算：grep -n \"^import\" " +
                "app/src/main/java/com/buaa/schedule/data/repository/CourseGroupAppearancePolicy.kt",
            listOf("import com.buaa.schedule.domain.model.Course"),
            Regex("(?m)^import .*$").findAll(source).map { it.value }.toList(),
        )
    }

    // ─────────────── 格子表 ───────────────

    /**
     * 一格一笔。[target] 是**手写的**目标态：判据与期望值各算各的，
     * 不许拿判据的输出去当期望值（那是套套逻辑）。noop 那些格子的 [target] 不参与断言。
     */
    private class Cell(
        val name: String,
        val incoming: Course,
        val customColorArgb: Long?,
        val original: Course,
        val target: (Course) -> Course,
    )

    private val writeCells = listOf(
        Cell(
            name = "只换颜色位（管理页那颗色板）",
            incoming = row(colorIndex = 4),
            customColorArgb = null,
            original = row(colorIndex = 1),
            target = { it.copy(colorIndex = 4, isManualOverride = true) },
        ),
        Cell(
            name = "换成一枚合法的自定义色",
            incoming = row(colorIndex = 4, customColorArgb = 0xFF00FF00L),
            customColorArgb = 0xFF00FF00L,
            original = row(colorIndex = 1),
            target = { it.copy(colorIndex = 4, customColorArgb = 0xFF00FF00L, isManualOverride = true) },
        ),
        Cell(
            name = "只改名称",
            incoming = row(name = "高等数学 B"),
            customColorArgb = null,
            original = row(),
            target = { it.copy(name = "高等数学 B", isManualOverride = true) },
        ),
        Cell(
            name = "只改地点",
            incoming = row(location = "新主楼 A101"),
            customColorArgb = null,
            original = row(),
            target = { it.copy(location = "新主楼 A101", isManualOverride = true) },
        ),
        Cell(
            name = "只改校区",
            incoming = row(campus = "沙河"),
            customColorArgb = null,
            original = row(),
            target = { it.copy(campus = "沙河", isManualOverride = true) },
        ),
        Cell(
            name = "只改学分",
            incoming = row(credit = 2.0),
            customColorArgb = null,
            original = row(credit = 3.0),
            target = { it.copy(credit = 2.0, isManualOverride = true) },
        ),
        Cell(
            name = "incoming 颜色位是负数、原行是 2 ⇒ 落库取 coerce 之后的 0",
            incoming = row(colorIndex = -3),
            customColorArgb = null,
            original = row(colorIndex = 2),
            target = { it.copy(colorIndex = 0, isManualOverride = true) },
        ),
        Cell(
            name = "兄弟片段没填学分、整组这次填了 ⇒ 学分也要盖下来",
            incoming = row(credit = 1.0),
            customColorArgb = null,
            original = row(credit = null),
            target = { it.copy(credit = 1.0, isManualOverride = true) },
        ),
    )

    private val noopCells = listOf(
        Cell(
            name = "反复点当前已选中那块色板（全仓唯一保证同值的那条路径）",
            incoming = row(),
            customColorArgb = null,
            original = row(),
            target = { it },
        ),
        Cell(
            name = "同上、但那一行已经标过 manual",
            incoming = row(),
            customColorArgb = null,
            original = row(isManual = true),
            target = { it },
        ),
        Cell(
            name = "incoming 没填学分、原行有学分",
            incoming = row(credit = null),
            customColorArgb = null,
            original = row(credit = 3.0),
            target = { it },
        ),
        Cell(
            name = "incoming 的时间/教师/周次与原行不同（那三维不归这一支管）",
            incoming = row(teacher = "别的老师", periods = listOf(5, 6), weeks = (21..30).toList()),
            customColorArgb = null,
            original = row(),
            target = { it },
        ),
        Cell(
            name = "incoming 颜色位是负数、原行本来就是 0（coerce 之后相等）",
            incoming = row(colorIndex = -1),
            customColorArgb = null,
            original = row(colorIndex = 0),
            target = { it },
        ),
        Cell(
            name = "incoming 带了枚越界色、调用点归一化成 null、原行本来就是 null",
            incoming = row(customColorArgb = -1L),
            customColorArgb = null,
            original = row(customColorArgb = null),
            target = { it },
        ),
    )

    private fun row(
        name: String = "高等数学",
        location: String? = "主楼",
        campus: String? = "学院路",
        colorIndex: Int = 1,
        customColorArgb: Long? = null,
        teacher: String? = "张老师",
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = (1..16).toList(),
        credit: Double? = 3.0,
        isManual: Boolean = false,
    ): Course = Course(
        id = 7L,
        name = name,
        teacher = teacher,
        location = location,
        campus = campus,
        dayOfWeek = 1,
        periods = periods,
        weeks = weeks,
        colorIndex = colorIndex,
        customColorArgb = customColorArgb,
        sourceGroupKey = "G-1",
        semesterCode = "2026-2027-1",
        isManualOverride = isManual,
        credit = credit,
    )

    /** 只改某一维，其余与那一版逐字段相同 */
    private fun withField(field: String, incoming: Course): Course = when (field) {
        "name" -> incoming.copy(name = "一般物理")
        "location" -> incoming.copy(location = "老主楼 202")
        "campus" -> incoming.copy(campus = "新校区")
        "credit" -> incoming.copy(credit = 1.0)
        else -> error("未知的维「$field」：加格子要先在 withField 里加分支，别让它静默落回 baseline")
    }

    private fun describe(course: Course?): String = if (course == null) {
        "null（不必写）"
    } else {
        "name=${course.name}, location=${course.location}, campus=${course.campus}, " +
            "colorIndex=${course.colorIndex}, customColorArgb=${course.customColorArgb}, " +
            "credit=${course.credit}, manual=${course.isManualOverride}, " +
            "teacher=${course.teacher}, periods=${course.periods}, weeks=${course.weeks.size} 周"
    }

    // ─────────────── 第 ⑥ 组那三枚格子的读取口 ───────────────

    /**
     * 读内核文件本身，而不是内联一份副本：内联副本就成了「拿常量比常量」的套套逻辑（#118 那一族），
     * 判据本体改了它一个字都不吭。找不到文件直接红 —— 文件挪过家这件事本身也要被钉住。
     */
    private fun policySource(): String {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf(
                "app/src/main/java/com/buaa/schedule/data/repository/CourseGroupAppearancePolicy.kt",
                "src/main/java/com/buaa/schedule/data/repository/CourseGroupAppearancePolicy.kt",
            ).map { File(dir, it) }.firstOrNull { it.isFile }
            if (hit != null) return hit.readText()
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 CourseGroupAppearancePolicy.kt：当前目录 ${File("").absolutePath}")
    }

    /** 只抹注释、保留字符串字面量，长度与换行位置不变（行号因此仍然可信） */
    private fun stripComments(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }
}
