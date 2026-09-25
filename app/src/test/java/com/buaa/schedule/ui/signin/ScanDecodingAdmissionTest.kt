package com.buaa.schedule.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空白原文的准入判据（T87 / #107）：表驱动。
 *
 * 这张表要回答的只有一件事 —— **哪些解码结果不该算「一次扫码结果」**。
 * 现场起因是装机实测到的那一发：AVD 虚拟场景里那面棋盘格被 ML Kit 解成一枚**空原文**
 * （`rawValue == ""`），一路穿过提交闸门进了状态机，界面对用户说
 * 「扫到的不是签到码：…而它连链接都不是」，而用户什么都没扫。
 *
 * 表里最要紧的三条不是"杀掉多少"，而是三条**别杀错**：
 * ① 长度 1 的文本（"A" / "0"）必须放行 —— 单字符 QR 的字节数组就是 1 字节，
 *    拿"字节少 / 看着不像链接"当准入条件就会把真码一起挡掉（本卡点名要验的那一档：
 *    `rawBytes` 空或短、`displayValue` 却非空白 ⇒ 收）；
 * ② 空串与全空白是**两档**，措辞必须分得开：前者是解码器给了个空东西（误检），
 *    后者可能真是用户扫到一条只含空白的文本 —— 这两件事对排查的含义不同；
 * ③ 来源（相机帧 / 相册图）**不参与**"收不收"：同一份 payload 两档结论必须逐字相同，
 *    来源只改措辞（相册那条界面另有「图里没认出二维码」那一句）。
 *
 * 纯度与接线（判据文件零 import、空白原文走不到 `rejectScan`）在
 * [ScanBlankDecodingWiringGuardTest] 里钉，本文件只管判据语义。
 */
class ScanDecodingAdmissionTest {

    /** 收与不收的主表：payload → 期望档级 + 期望长度（两种来源各跑一遍） */
    @Test
    fun everyPayloadShapeGetsExactlyOneVerdict() {
        data class Row(
            val label: String,
            val payload: String?,
            val blankness: DecodingBlankness,
            val length: Int,
        )

        val ideographicSpace = "　"      // 全角空格：Unicode White_Space
        val nonBreakingSpace = " "  // 不换行空格：Kotlin 的 isBlank 也算它是空白（与 java.lang.Character.isWhitespace 不同）
        val zeroWidthSpace = "​"      // 零宽空格：不在 White_Space 表里 ⇒ 不是空白
        val rows = listOf(
            Row(
                "装机实测那一发：棋盘格解出来的空串（长度 0，不是 null）",
                "",
                DecodingBlankness.EmptyText,
                length = 0,
            ),
            Row("三个半角空格", "   ", DecodingBlankness.WhitespaceOnly, length = 3),
            Row("只有换行", "\n", DecodingBlankness.WhitespaceOnly, length = 1),
            Row("只有制表", "\t", DecodingBlankness.WhitespaceOnly, length = 1),
            Row("回车 + 换行 + 制表 + 空格", "\r\n\t ", DecodingBlankness.WhitespaceOnly, length = 4),
            Row("全角空格（表意空格 U+3000 也是空白）", ideographicSpace, DecodingBlankness.WhitespaceOnly, length = 1),
            Row("解码器连原文都没给（rawValue == null）", null, DecodingBlankness.NoText, length = 0),
            // ---- 下面这些必须**放行**：收与不收的分界只看"有没有一个可读字符" ----
            Row("长度 1 的文本（rawBytes 只有 1 字节的真码）", "A", DecodingBlankness.None, length = 1),
            Row("长度 1 的数字零（看着像空，其实是个字符）", "0", DecodingBlankness.None, length = 1),
            Row(
                "不换行空格 U+00A0：Kotlin 的 isBlank 按 Unicode White_Space 属性判（比 " +
                    "java.lang.Character.isWhitespace 宽），所以它算空白；判据跟着 stdlib，不自造第三种定义",
                nonBreakingSpace,
                DecodingBlankness.WhitespaceOnly,
                length = 1,
            ),
            Row(
                "零宽空格 U+200B：不在 White_Space 表里 ⇒ 收（放行之后由七档那一头说话）",
                zeroWidthSpace,
                DecodingBlankness.None,
                length = 1,
            ),
            Row("长度 1 的汉字", "码", DecodingBlankness.None, length = 1),
            Row("真码逐字原文", REAL_ICLASS_CODE, DecodingBlankness.None, length = REAL_ICLASS_CODE.length),
            Row(
                "前后带空白的真码（长度按原文算，不按 trim 后算）",
                "  $REAL_ICLASS_CODE\n",
                DecodingBlankness.None,
                length = REAL_ICLASS_CODE.length + 3,
            ),
            Row("别家的一条链接", FOREIGN_LINK, DecodingBlankness.None, length = FOREIGN_LINK.length),
        )

        for (row in rows) {
            for (source in DecodingSource.entries) {
                val admission = decodingAdmission(row.payload, source)
                assertEquals("${row.label}（$source）落错档：", row.blankness, admission.blankness)
                assertEquals("${row.label} 的长度报错了：", row.length, admission.payloadLength)
                assertEquals(
                    "${row.label}（$source）的收/不收与档级不一致：",
                    row.blankness == DecodingBlankness.None,
                    admission.admitted,
                )
            }
        }
        assertEquals("四档里有一档没被人钉住：", 4, rows.map { it.blankness }.distinct().size)
    }

    /** 来源不参与判据：同一份 payload 在两条路上必须给出逐字相同的结论（只有措辞不同） */
    @Test
    fun theSourceNeverChangesTheVerdict() {
        for (payload in listOf<String?>("x", null, "", " ", "\n", "\t\t", "A", "签到", REAL_ICLASS_CODE)) {
            val camera = decodingAdmission(payload, DecodingSource.CameraFrame)
            val gallery = decodingAdmission(payload, DecodingSource.GalleryImage)
            assertEquals("同一份原文在两条路上档级不同：$payload", camera.blankness, gallery.blankness)
            assertEquals("同一份原文在两条路上长度不同：$payload", camera.payloadLength, gallery.payloadLength)
            assertEquals("同一份原文在两条路上收/不收不同：$payload", camera.admitted, gallery.admitted)
        }
    }

    /**
     * 不变量：`admitted` 与"原文里有一个可读字符"同真同假。
     *
     * 这一条是判据的本体 —— 判死棘轮、七档措辞、界面文案都不该改变它；
     * 而它一旦被写成"另加一条长度门槛 / 另加一条字符集门槛"，真码就会在这里被吃掉。
     */
    @Test
    fun admittedMeansExactlyOneReadableCharacter() {
        val samples: List<String?> = listOf(
            null, "", " ", "  ", "\n", "\r", "\t", " \t\n\r", "　", " ",
            "a", "A1", "0", "-", "码", "http", REAL_ICLASS_CODE, "   x   ",
        )
        for (payload in samples) {
            val shown = payload?.replace("\n", "\\n")?.replace("\t", "\\t")?.replace("\r", "\\r")
            assertEquals(
                "不变量在「$shown」上破了：",
                payload != null && payload.isNotBlank(),
                decodingAdmission(payload, DecodingSource.CameraFrame).admitted,
            )
        }
    }

    // ---- 节流：按帧的东西不做去重就是把 logcat 冲干净 ----

    /** 第一次必说、之后每 [BlankDecodingLogStride] 次一次、**换档必说**；其余一律闭嘴 */
    @Test
    fun theTraceSpeaksOncePerKindChangeAndThenOncePerStride() {
        data class Row(val count: Long, val blankness: DecodingBlankness, val previous: DecodingBlankness?, val speak: Boolean)

        val rows = listOf(
            Row(1, DecodingBlankness.EmptyText, null, speak = true),
            Row(2, DecodingBlankness.EmptyText, DecodingBlankness.EmptyText, speak = false),
            Row(49, DecodingBlankness.EmptyText, DecodingBlankness.EmptyText, speak = false),
            Row(50, DecodingBlankness.EmptyText, DecodingBlankness.EmptyText, speak = true),
            Row(51, DecodingBlankness.EmptyText, DecodingBlankness.EmptyText, speak = false),
            Row(100, DecodingBlankness.EmptyText, DecodingBlankness.EmptyText, speak = true),
            // 换档那一发即使不在步长上也要说话：空串与全空白是两种病因
            Row(3, DecodingBlankness.WhitespaceOnly, DecodingBlankness.EmptyText, speak = true),
            Row(4, DecodingBlankness.NoText, DecodingBlankness.WhitespaceOnly, speak = true),
            Row(5, DecodingBlankness.NoText, DecodingBlankness.NoText, speak = false),
        )
        for (row in rows) {
            val trace = traceBlankDecoding(BlankDecodingLedger(row.count - 1L, row.previous), row.blankness)
            assertEquals("第 ${row.count} 次（${row.blankness}，上次 ${row.previous}）：", row.speak, trace.speak)
            assertEquals("序号要报这一次自己的数：", row.count, trace.occurrence)
            assertEquals("账本没跟着翻档：", row.blankness, trace.ledger.lastBlankness)
            assertEquals("账本没自增：", row.count, trace.ledger.count)
        }
    }

    /** 记账与说话不能各走一半；旧账本也不许被原地改（两条引擎共用一颗闸门） */
    @Test
    fun theLedgerIsReplacedNotMutated() {
        val before = BlankDecodingLedger(41L, DecodingBlankness.EmptyText)
        val trace = traceBlankDecoding(before, DecodingBlankness.EmptyText)
        assertEquals("旧账本被原地改了（计数）：", 41L, before.count)
        assertEquals("旧账本被原地改了（档位）：", DecodingBlankness.EmptyText, before.lastBlankness)
        assertEquals(42L, trace.ledger.count)
        assertEquals("空账本的计数该从零起：", 0L, BlankDecodingLedger().count)
        assertEquals("空账本没有上一档：", null, BlankDecodingLedger().lastBlankness)
    }

    /** 装机实测那面棋盘格（795 帧里 794 帧都空，8.10 帧/秒）在改后究竟说几句话：不是一帧一行 */
    @Test
    fun aWholeSceneOfBlankFramesStaysReadable() {
        var ledger = BlankDecodingLedger()
        var spoken = 0
        for (frame in 1L..794L) {
            val trace = traceBlankDecoding(ledger, DecodingBlankness.EmptyText)
            ledger = trace.ledger
            if (trace.speak) spoken++
        }
        assertEquals("794 次空白说了 $spoken 行，节流口径漂了：", 1 + (794L / BlankDecodingLogStride).toInt(), spoken)
        assertTrue("一条都不说那就成静默丢弃了（本卡要避免的另一头）：$spoken", spoken >= 2)
        assertEquals("账本没数满：", 794L, ledger.count)
    }

    // ---- 取证行：一句话里四件事一枚不少 ----

    /** 空串与全空白那两档的措辞**必须**分得开：那种区分就是这一卡的交付物 */
    @Test
    fun theTraceLineTellsMisdetectionApartFromARealBlankCode() {
        val empty = decodingAdmissionTraceText(
            decodingAdmission("", DecodingSource.CameraFrame), occurrence = 7L, frame = 88L,
        )
        val blanks = decodingAdmissionTraceText(
            decodingAdmission("  \n", DecodingSource.CameraFrame), occurrence = 8L, frame = 90L,
        )
        assertTrue("空串那一档没说出「解码器给了个空东西」：$empty", empty.contains("空原文"))
        assertTrue("空串那一档还在指责用户：$empty", empty.contains("最像误检") && empty.contains("不是用户扫错了东西"))
        assertTrue("全空白那一档没说「可能真扫到一条空白文本」：$blanks", blanks.contains("只含空白的文本"))
        assertFalse("两档混成同一句话了：", empty == blanks)
        assertTrue("全空白那一档没报自己的长度：$blanks", blanks.contains("长度=3"))
        assertTrue("空串那一档报错了长度：$empty", empty.contains("长度=0"))
        for (line in listOf(empty, blanks)) {
            assertTrue("留痕没带本轮序号：$line", line.contains("本轮第 "))
            assertTrue("留痕没说清后果（没进状态机、没弹卡）：$line", line.contains("没弹失败卡"))
            assertTrue("留痕没写「参数值=未记录」，读日志的人要猜是不是漏了：$line", line.contains("参数值=未记录"))
            assertTrue("留痕没带「不收」这个关键词：$line", line.startsWith("解码原文不收"))
            assertTrue("留痕没带帧号：$line", Regex("第 \\d+ 帧").containsMatchIn(line))
        }
    }

    /** 来源与帧号：相册那条没有帧，必须说实话而不是编一个 0 */
    @Test
    fun theTraceLineNamesTheSourceAndSaysWhenThereIsNoFrame() {
        val camera = decodingAdmissionTraceText(
            decodingAdmission("", DecodingSource.CameraFrame), occurrence = 1L, frame = 42L,
        )
        val gallery = decodingAdmissionTraceText(
            decodingAdmission(" ", DecodingSource.GalleryImage), occurrence = SingleActionOrdinal, frame = NoDecodingFrame,
        )
        assertTrue("相机那条没报名字：$camera", camera.contains("源=相机帧") && camera.contains("第 42 帧"))
        assertTrue("相册那条没报名字：$gallery", gallery.contains("源=相册图"))
        assertTrue("相册那条编了个帧号：$gallery", gallery.contains("帧号未记录"))
        assertFalse("相机那条混进了未记录那一档：$camera", camera.contains("帧号未记录"))
        assertTrue("相册那条没说界面到底说了哪一句：$gallery", gallery.contains("那张图里没认出二维码"))
        assertTrue("相册那一档说的是全空白，措辞就该是空白那一档：$gallery", gallery.contains("只含空白的文本"))
    }

    /**
     * 日志行不许被原文切开：全空白那种 payload 里就有换行与制表，
     * 一旦措辞把原文拼进去，一条取证记录就会变成好几行（那等于可以伪造行）。
     */
    @Test
    fun theTraceLineIsAlwaysASinglePhysicalLine() {
        for (payload in listOf<String?>("", " ", "\n", "\t", "\r\n\t ", "　", " ", "\n\n\n")) {
            val admission = decodingAdmission(payload, DecodingSource.CameraFrame)
            val line = decodingAdmissionTraceText(admission, occurrence = 3L, frame = 12L)
            for (banned in listOf('\n', '\r', '\t', '　', ' ')) {
                val escaped = banned.code.let { "\\u${it.toString(16)}" }
                assertFalse("原文里的字符 $escaped 被拼进取证行了：$line", line.contains(banned))
            }
            assertEquals("取证行成了多行：", 1, line.lines().size)
        }
    }

    /** 收了的东西绝不走留痕：非空白的原文一律放行（留痕只服务"不收"那一支） */
    @Test
    fun readablePayloadIsNeverCalledBlank() {
        for (payload in listOf("A", "0", "码", REAL_ICLASS_CODE, "  $REAL_ICLASS_CODE", FOREIGN_LINK)) {
            assertTrue("非空白原文被判成不收：$payload", decodingAdmission(payload, DecodingSource.CameraFrame).admitted)
        }
    }

    // ---- 闸门三种出路的措辞 ----

    /** 兜底那一行的收尾半句：三档三样，"空白被准入收下"不许说成"被闸门压住" */
    @Test
    fun theThreeDoorOutcomesGetThreeDifferentSentences() {
        val clauses = DecodingSubmission.entries.map { decodedTextSubmissionClause(it) }
        assertEquals("三档说成了 ${clauses.distinct().size} 样：", 3, clauses.distinct().size)
        assertTrue("放行那一档没提闸门：", clauses[DecodingSubmission.Submitted.ordinal].contains("递交签到"))
        assertEquals(
            "闸门压住那一档的话与改前逐字不同（本卡只多开一档，不改别人的句子）：",
            "这一份被提交闸门压住（同一份原文刚投过，或结果卡正等用户按）",
            clauses[DecodingSubmission.HeldByGate.ordinal],
        )
        val blank = clauses[DecodingSubmission.BlankRejected.ordinal]
        assertTrue("空白那一档没说清是被准入收下的：$blank", blank.contains("空白原文") && blank.contains("准入"))
        assertFalse("空白那一档把责任推给了提交闸门：$blank", blank.contains("被提交闸门压住"))
    }

    /** 档级与来源都是封闭档：多一档就得回这张表里加一行，编译器与这条守卫一起盯着 */
    @Test
    fun theEnumsStayClosed() {
        assertEquals("档级数目变了（None/NoText/EmptyText/WhitespaceOnly）：", 4, DecodingBlankness.entries.size)
        assertEquals("解码来源只该有相机帧与相册图两枚：", 2, DecodingSource.entries.size)
        assertEquals("闸门出路该是三档：", 3, DecodingSubmission.entries.size)
        assertEquals("节流步长：", 50L, BlankDecodingLogStride)
        assertEquals("相册那条的帧号哨兵：", -1L, NoDecodingFrame)
        assertEquals("相册那条的序号哨兵：", 1L, SingleActionOrdinal)
        assertEquals("默认结论是收：", DecodingBlankness.None, decodingAdmission("x", DecodingSource.CameraFrame).blankness)
    }

    private companion object {
        /** 真码逐字原文（口径见 `IClassSignUrl`：端口与参数名都是契约的一部分） */
        const val REAL_ICLASS_CODE =
            "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?courseSchedId=1234567&timestamp=1695000000"

        const val FOREIGN_LINK = "https://weixin.qq.com/r/abc?x=1"
    }
}
