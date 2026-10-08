package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T279②：WakeUp 导出件的 JSON 文本层 —— 表驱动逐格钉 [WakeUpJsonText]（纯 JVM、零时钟、零 android）。
 *
 * ## 钉的是哪三件事
 *
 * 1. **表 ①（短转义 7 枚）与表 ②（C0 全 32 档）**逐枚打格：期望值在测试里**逐字写死**
 *    （不是拿内核那张表自己算一遍再跟自己对 ⇒ 那种断言恒真）。表 ② 的 32 格一行一枚，
 *    其中 `\b \t \n \f \r` 五枚走短名、其余 27 枚走 `\u00xx`；
 * 2. **每道边界两侧各一格**：0x1F 转义 vs 0x20 原样、0x21 原样 vs 0x22 转义 vs 0x23 原样、
 *    0x5B 原样 vs 0x5C 转义 vs 0x5D 原样、0x0A/0x0D 走短名 vs 0x0B 只能走 `\u`、
 *    U+007F（DEL）两侧都不动、代理码元两侧（配对的两枚原样出场 / 孤立的各自折成 `\udxxx`）；
 * 3. **丙档的两手都有格子**：sanitize 那一手钉"换行折成空格 + 连续空格并一枚 + 首尾去空"，
 *    escaper 那一手钉"不依赖上游洗过"（同一枚裸控制字符直接进 [WakeUpJsonText.escaped] 仍出转义）。
 *
 * ## 两把错尺并排复算（反套套逻辑）
 *
 * - **朝宽**（改前那一形：只折 `\\` 与 `"`）⇒ [改前那一形会把裸换行留在文件里] 先证明那一形
 *   **确实**交出裸 0x0A（错尺真的咬得住，不是测试自己造的假输入），再证本内核在同枚输入上交出合法文本；
 * - **朝窄**（"sanitize 以后就不用转义了"那种乐观）⇒ [escaped 不依赖上游洗过] 把裸控制字符
 *   直接喂给 escaper 自己，断言它不产假绿。
 *
 * ## 端到端那一格为什么必须打
 *
 * 全仓此前没有任何一格**按 JSON 解析**过 WakeUp 导出件（`WakeUpExportTermTrimTest` 用的是手写正则，
 * 所以"这份文件根本不是合法 JSON"那一格在它眼里全绿）。本文件的端到端格走 [Json.parseToJsonElement]
 * 加自写的"整份文档 0 枚裸 C0"那把尺，两头各量一遍；另有一格钉 N6（别名进导出件、原名只在没别名时出场）。
 *
 * ⚠️ 零 adb：对方 App 拿到合法 JSON 以后怎么排课不在本卡的读数面里（卡面明说本卡零设备）。
 */
class WakeUpJsonTextTest {

    private companion object {
        /** 表 ② 的期望值：逐字写死，32 枚一行一枚（改前那一手对这些一枚都不动） */
        val C0_EXPECTED: List<Pair<Char, String>> = listOf(
            '\u0000' to "\\u0000",
            '\u0001' to "\\u0001",
            '\u0002' to "\\u0002",
            '\u0003' to "\\u0003",
            '\u0004' to "\\u0004",
            '\u0005' to "\\u0005",
            '\u0006' to "\\u0006",
            '\u0007' to "\\u0007",
            '\u0008' to "\\b",
            '\u0009' to "\\t",
            '\u000A' to "\\n",
            '\u000B' to "\\u000b",
            '\u000C' to "\\f",
            '\u000D' to "\\r",
            '\u000E' to "\\u000e",
            '\u000F' to "\\u000f",
            '\u0010' to "\\u0010",
            '\u0011' to "\\u0011",
            '\u0012' to "\\u0012",
            '\u0013' to "\\u0013",
            '\u0014' to "\\u0014",
            '\u0015' to "\\u0015",
            '\u0016' to "\\u0016",
            '\u0017' to "\\u0017",
            '\u0018' to "\\u0018",
            '\u0019' to "\\u0019",
            '\u001A' to "\\u001a",
            '\u001B' to "\\u001b",
            '\u001C' to "\\u001c",
            '\u001D' to "\\u001d",
            '\u001E' to "\\u001e",
            '\u001F' to "\\u001f",
        )

        /** 一枚都不许被转义、也不该被 sanitize 折掉的对照字符（每道边界"另一侧"那一格） */
        val MUST_STAY_RAW = listOf(' ', '!', '#', '[', ']', '\u007F', '\u00A0', '高', '，')

        /** 改前那一手的复刻（只折反斜杠与双引号）：并排复算用的那把错尺 */
        fun legacyJsonStr(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** 整段文本里裸 C0 的枚数（RFC 8259 §7 判死的那一档） */
        fun rawControlCount(text: String): Int = text.count { it.code <= 0x1F }

        fun course(name: String, alias: String? = null, teacher: String? = "张三", location: String? = "J3-101") =
            Course(
                id = 1L,
                name = name,
                alias = alias,
                teacher = teacher,
                location = location,
                dayOfWeek = 1,
                periods = listOf(1, 2),
                weeks = listOf(1, 2, 3),
            )

        val semester = Semester(
            termCode = "T279",
            termName = "2026-2027 学年第一学期",
            startDate = "2026-09-07",
            totalWeeks = 19,
        )
    }

    // ─────────────── ① 表 ①：七枚短转义逐格 ───────────────

    @Test
    fun `表一 七枚短转义逐格`() {
        assertEquals(7, WakeUpJsonText.SHORT_ESCAPES.size)
        for ((char, expected) in WakeUpJsonText.SHORT_ESCAPES) {
            assertEquals(
                "短转义「$expected」那一枚（U+%04X）交回的正文不对".format(char.code),
                expected,
                WakeUpJsonText.escaped(char.toString()),
            )
        }
        // 表 ① 的七枚与表 ② 的三十二枚必须同判（两枚结构性字符 + 五枚短名 + 二十七枚 \\u）
        assertEquals(
            "两张表合起来的覆盖面 = 32 枚 C0 + 双引号 + 反斜杠（后两枚也在 C0 段之外，另算两枚）",
            34,
            (WakeUpJsonText.SHORT_ESCAPES.keys + C0_EXPECTED.map { it.first }).distinct().size,
        )
    }

    // ─────────────── ② 表 ②：C0 全 32 档逐格（期望值逐字写死） ───────────────

    @Test
    fun `表二 C0 三十二档逐格`() {
        assertEquals(32, C0_EXPECTED.size)
        for ((char, expected) in C0_EXPECTED) {
            val actual = WakeUpJsonText.escaped(char.toString())
            assertEquals(
                "U+%04X 这一档必须转义，实到「%s」。裸字符出场 = 整份 .json 在那一枚字符处判死，" +
                    "对方 App 零门课而屏幕上念「已导出」".format(char.code, actual),
                expected,
                actual,
            )
        }
    }

    @Test
    fun `表二里走短名的恰是五枚 其余二十七枚走 u 转义`() {
        val shortNames = C0_EXPECTED.filter { it.second.length == 2 }
        assertEquals(
            "C0 里 RFC 8259 给了短名的是 \\b \\t \\n \\f \\r 五枚（其余 27 枚必须走 \\u00xx）",
            listOf("\\b", "\\t", "\\n", "\\f", "\\r"),
            shortNames.map { it.second },
        )
        val hex = C0_EXPECTED.filter { it.second.length == 6 }
        assertEquals("走 \\u00xx 的那 27 档枚数不对", 27, hex.size)
        assertTrue(
            "每一枚都得是 `\\u00` 开头 + 两位十六进制（本仓用小写数码表，与常见解析器的产出同形）：" +
                hex.joinToString { it.second },
            hex.all { it.second.startsWith("\\u00") && it.second.substring(4).all { d -> d in "0123456789abcdef" } },
        )
    }

    // ─────────────── ③ 每道边界两侧 ───────────────

    @Test
    fun `边界两侧 逐格`() {
        // 0x1F 与 0x20：强制转义那一段的最后一枚 vs 第一枚合法裸字符
        assertEquals("\\u001f", WakeUpJsonText.escaped("\u001F"))
        assertEquals(" ", WakeUpJsonText.escaped(" "))
        // 0x21 / 0x22 / 0x23：感叹号原样、双引号转义、井号原样
        assertEquals("!", WakeUpJsonText.escaped("!"))
        assertEquals("\\\"", WakeUpJsonText.escaped("\""))
        assertEquals("#", WakeUpJsonText.escaped("#"))
        // 0x5B / 0x5C / 0x5D：左方括号原样、反斜杠转义、右方括号原样
        assertEquals("[", WakeUpJsonText.escaped("["))
        assertEquals("\\\\", WakeUpJsonText.escaped("\\"))
        assertEquals("]", WakeUpJsonText.escaped("]"))
        // 0x0A / 0x0D 走短名，紧挨着的 0x0B 只能走 \\u（三枚相邻的档位各一格）
        assertEquals("\\n", WakeUpJsonText.escaped("\n"))
        assertEquals("\\r", WakeUpJsonText.escaped("\r"))
        assertEquals("\\u000b", WakeUpJsonText.escaped("\u000B"))
        // U+007F（DEL）：不在 §7 强制的那一段里 ⇒ 既不转义也不折空格（第 ⓪ 问裁定的那一格）
        assertEquals("\u007F", WakeUpJsonText.escaped("\u007F"))
        assertEquals("a\u007Fb", WakeUpJsonText.sanitize("a\u007Fb"))
    }

    @Test
    fun `不许动的字符 一枚都不动`() {
        for (ch in MUST_STAY_RAW) {
            assertEquals(
                "U+%04X 不在强制转义的那一段里，也不该被 sanitize 折掉（除非它挨着别的空格）".format(ch.code),
                ch.toString(),
                WakeUpJsonText.escaped(ch.toString()),
            )
        }
        assertEquals("高等数学", WakeUpJsonText.escaped("高等数学"))
        // ICS 那条链要转义的 `;` 与 `,` 在 JSON 里是普通字符：不许把两把尺折成一把
        assertEquals("J3-101, 主楼; 三层", WakeUpJsonText.escaped("J3-101, 主楼; 三层"))
    }

    // ─────────────── ④ 代理码元两侧：配对原样 / 孤立转义 ───────────────

    @Test
    fun `emoji 的代理对原样出场`() {
        val graduationCap = "\uD83C\uDF93" // 🎓 = U+1F393
        assertEquals(
            "配对代理（emoji）是合法的 UTF-8，两枚码元一枚都不许动（动了 = 用户打的字在对方 App 里变样）",
            graduationCap,
            WakeUpJsonText.escaped(graduationCap),
        )
        val astral = "\uD80D\uDCD8" // U+134D8（辅助平面的生僻字，与 emoji 同族两枚码元）
        assertEquals(astral, WakeUpJsonText.escaped(astral))
        assertEquals("上课🎓 J3-101", WakeUpJsonText.sanitize("上课🎓  J3-101"))
        assertEquals("\"高数🎓\"", WakeUpJsonText.literal("高数🎓"))
        assertFalse(
            "带 emoji 的整串文档里不许有裸 C0",
            rawControlCount(WakeUpJsonText.literal("高数🎓\nJ3")) > 0,
        )
    }

    @Test
    fun `孤立代理折成 u 转义 而不是落盘变成问号`() {
        // 半枚 emoji（粘贴被截断 / 旧库里的脏行）在 UTF-8 里编码不出来 ⇒ 落盘会被折成 `?`
        assertEquals("\\ud83c", WakeUpJsonText.escaped("\uD83C"))
        assertEquals("\\udf93", WakeUpJsonText.escaped("\uDF93"))
        assertEquals("\\ud83cA", WakeUpJsonText.escaped("\uD83CA"))
        assertEquals("a\\udf93b", WakeUpJsonText.escaped("a\uDF93b"))
        val lone = WakeUpJsonText.literal("\uD83C")
        assertEquals("\"\\ud83c\"", lone)
        assertEquals("转义写出来的是 ASCII：引号 2 枚 + 转义 6 枚 = 8 ⇒ $lone", 8, lone.length)
        // 全档扫一遍：D800–DFFF 每一枚孤立代理都不得以裸码元出场
        for (code in 0xD800..0xDFFF) {
            val out = WakeUpJsonText.escaped(code.toChar().toString())
            assertFalse(
                "U+%04X 是孤立代理，裸码元出场 = 落盘折成 `?`（对方收到的是问号，无从复原）".format(code),
                out.any { it.isSurrogate() },
            )
            assertEquals("\\u" + code.toString(16), out)
        }
    }

    // ─────────────── ⑤ 乙档那一手：sanitize 的格子 ───────────────

    @Test
    fun `sanitize 把行结束符折成空格`() {
        // CRLF 与 CR 先归一再折（IcsExporter 那一句"必须先统一换行再转义"的同一手）
        assertEquals("高等 数学", WakeUpJsonText.sanitize("高等\r\n数学"))
        assertEquals("高等 数学", WakeUpJsonText.sanitize("高等\r数学"))
        assertEquals("高等 数学", WakeUpJsonText.sanitize("高等\n数学"))
        assertEquals("高等 数学", WakeUpJsonText.sanitize("高等\n\n数学"))
        assertEquals("高等 数学", WakeUpJsonText.sanitize("高等\u0000数学"))
        assertEquals("高数 张三", WakeUpJsonText.sanitize("高数\t\t\t张三"))
        assertEquals("高数 张三", WakeUpJsonText.sanitize("高数   张三"))
        // 首尾两侧：开头没有内容可押空格 ⇒ 直接丢；末尾没人再 append ⇒ 天然去空
        assertEquals("lead", WakeUpJsonText.sanitize("  lead"))
        assertEquals("trail", WakeUpJsonText.sanitize("trail  \n"))
        assertEquals("x", WakeUpJsonText.sanitize("\n\nx\n\n"))
        assertEquals("", WakeUpJsonText.sanitize(""))
        assertEquals("", WakeUpJsonText.sanitize("   \t\r\n"))
        assertEquals("a b c", WakeUpJsonText.sanitize(" a  b \r\nc "))
        // 归一化排在折空格之前：一枚 CRLF 只能折出**一枚**空格（反过来做就是两枚）
        assertEquals(1, WakeUpJsonText.sanitize("a\r\nb").count { it == ' ' })
    }

    @Test
    fun `literal 是丙档那一手 先洗再转 再包引号`() {
        assertEquals("\"高等 数学\"", WakeUpJsonText.literal("高等\n数学"))
        assertEquals("\"说\\\"引号\\\"\"", WakeUpJsonText.literal("说\"引号\""))
        assertEquals("\"C:\\\\教室\"", WakeUpJsonText.literal("C:\\教室"))
        // 同一枚输入里既有引号又有反斜杠：两枚各自转义，且不发生二次转义
        assertEquals("\"a\\\\\\\"b\"", WakeUpJsonText.literal("a\\\"b"))
        assertEquals("\"\"", WakeUpJsonText.literal("\n"))
    }

    // ─────────────── ⑥ 总括性：不依赖上游洗过（反朝窄） ───────────────

    @Test
    fun `escaped 不依赖上游洗过`() {
        val dirty = "\u0000\u0001 \t\n\r\u001F 高数"
        val out = WakeUpJsonText.escaped(dirty)
        assertEquals(
            "没洗过的输入照样逐枚转义（表 ① 的短名 + 其余 \\u00xx）",
            "\\u0000\\u0001 \\t\\n\\r\\u001f 高数",
            out,
        )
        assertEquals("正文里一枚裸 C0 都不许有", 0, rawControlCount(out))
        // 同一枚输入走丙档：洗过以后交回的是干净文本，不是那一串转义
        assertEquals("\"高数\"", WakeUpJsonText.literal(dirty))
    }

    @Test
    fun `改前那一形会把裸换行留在文件里`() {
        val raw = "高等\n数学"
        // 并排复算：先证明那把错尺真的咬得住（它交出的串里有裸 0x0A）
        val legacy = legacyJsonStr(raw)
        assertEquals(
            "改前那一手只折反斜杠与双引号 ⇒ 裸换行原样出场。" +
                "这一格红了就说明尺子退化成了恒真（错尺不复算成功）",
            1,
            legacy.count { it == '\n' },
        )
        assertEquals(1, rawControlCount(legacy))
        // 再证明本内核在同一枚输入上交出的是合法文本
        assertEquals(0, rawControlCount(WakeUpJsonText.literal(raw)))
        assertEquals("\"高等 数学\"", WakeUpJsonText.literal(raw))
    }

    @Test
    fun `整段码位扫描 任何输入都不产裸控制字符`() {
        var cells = 0
        for (code in 0x00..0x02FF) {
            val literal = WakeUpJsonText.literal(code.toChar().toString())
            cells++
            assertTrue(
                "U+%04X 的字面量应以引号首尾括起：$literal".format(code),
                literal.startsWith("\"") && literal.endsWith("\"") && literal.length >= 2,
            )
            val body = literal.substring(1, literal.length - 1)
            assertEquals("U+%04X 的正文里有裸控制字符：$literal".format(code), 0, rawControlCount(body))
        }
        assertEquals("扫过的码位枚数（0x00..0x02FF = 768 枚）", 768, cells)
    }

    // ─────────────── ⑦ 端到端：导出件真的按 JSON 解析得过 + N6 别名 ───────────────

    @Test
    fun `导出件带裸换行时仍是合法JSON 且对方拿到的是干净的文本`() {
        val dirty = course(
            name = "高级语言\r\n程序设计",
            alias = "英语（三）\t上",
            teacher = "王\r明",
            location = "主楼A-101\n三层",
        )
        val json = ScheduleExporters.toWakeUpJson(
            listOf(dirty),
            Semester(termCode = "T", termName = "2026 秋\r学期", startDate = "2026-09-07", totalWeeks = 19),
            emptyList(),
        )
        // 改前这里出场 6 枚裸 C0：名称的 CRLF 两枚 + 教师的 CR + 地点的 LF + 学期名两处各一枚
        assertEquals("整份文档里裸 C0 必须 0 枚", 0, rawControlCount(json))
        assertEquals(
            "并排复算：同一枚名称在原样出场的那两枚字符（CR 与 LF）就是 2 枚裸控制字符 ⇒ 整份文件判死",
            2,
            rawControlCount(legacyJsonStr("高级语言\r\n程序设计")),
        )
        assertEquals("教师那一枚 CR 同样原样出场", 1, rawControlCount(legacyJsonStr("王\r明")))
        assertEquals("学期名两处各一枚（顶层与 tableInfo）", 2, rawControlCount(legacyJsonStr("2026 秋\r学期") + legacyJsonStr("2026 秋\r学期")))
        val tree = Json.parseToJsonElement(json).jsonObject
        val first = tree["courses"]!!.jsonArray[0].jsonObject
        assertEquals("课程名字段出场的是别名（sanitize 以后）", "英语（三） 上", first["name"]!!.jsonPrimitive.content)
        assertEquals("教师字段里的 CR 被折成空格而不是撕裂字符串", "王 明", first["teacher"]!!.jsonPrimitive.content)
        assertEquals("地点字段里的换行被折成空格", "主楼A-101 三层", first["position"]!!.jsonPrimitive.content)
        assertEquals("学期名两处同一手：顶层", "2026 秋 学期", tree["name"]!!.jsonPrimitive.content)
        assertEquals(
            "学期名两处同一手：tableInfo",
            "2026 秋 学期",
            tree["tableInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `别名进导出件而教务原名只在没有别名时出场`() {
        val aliased = course(name = "高级语言程序设计", alias = "  英语（三）  ")
        val noAlias = course(name = "高等数学 B", alias = null)
        val blankAlias = course(name = "离散数学", alias = "   ")
        val json = ScheduleExporters.toWakeUpJson(listOf(aliased, noAlias, blankAlias), semester, emptyList())
        val names = Json.parseToJsonElement(json).jsonObject["courses"]!!.jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(
            "三条都走 displayName 那一枚出处（N6）：别名优先并 trim，空白别名回退教务原名（Course.kt 的定义）",
            listOf("英语（三）", "高等数学 B", "离散数学"),
            names,
        )
    }

    @Test
    fun `没有别名的课 导出件与改前逐字节相同`() {
        // 不连坐那一格：本卡换了字段取法，但无别名的课（绝大多数）一个字都不该变
        val plain = course(name = "高等数学 A", teacher = "李四", location = "主楼 101")
        val json = ScheduleExporters.toWakeUpJson(listOf(plain), semester, emptyList())
        assertTrue("课程对象应逐字节含这一串（字段顺序与枚数都没动）：$json", json.contains(
            """{"name":"高等数学 A","teacher":"李四","position":"主楼 101","day":1,""" +
                """"startNode":1,"step":2,"startWeek":1,"endWeek":3,"type":0,"color":0}""",
        ))
    }
}
