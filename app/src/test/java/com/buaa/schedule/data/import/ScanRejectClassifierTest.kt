package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拒绝原因**分档**的表驱动单测（T83①③，T85 跟着"只留 iClass"改口）。
 *
 * 三件事分开钉，少一件都会漏：
 * ① **入出**：每一条原文落到哪一档、带着什么形状（host / 端口 / 路径 / 参数名 / 缺哪几项）；
 * ② **不变量**：`classify` 说"收得下"（[ScanRejectRung.Recognized]）与 [ScanTargetParser.parse]
 *    真的收下，必须是同一件事 —— 判档是抄解析器自己的门槛来判的，这一条用一枚全矩阵跑遍
 *    （scheme × authority × 路径 × query 四轴组合），两把尺子一旦分家就红在这里，
 *    而不是红在同学的签到上；
 * ③ **措辞纪律**：每一档一句人话、逐字钉住（漂一个字就红），不许出现"请把手机对准…"那种
 *    没有信息量的指令句，也不许一个参数值出现在界面或日志上（滚动码的 `timestamp` 不出去）。
 *
 * ⚠️ T85 拆掉智学北航那一族时，**七档一枚没少**：档位量的是链接形状 → host → 路由 →
 * 参数齐备 → 值合法 → 是不是发过的请求，与"接了几族"无关。跟着改的只有措辞里的"两族"、
 * `族=` 的取值域（四枚 → 三枚），以及那几张 SPOC 形状的靶子 —— 它们现在都是**别家**的输入，
 * 钉的是"别家的码要说别家"，一档没浪费：`*.buaa.edu.cn` 下面不止一台，这一族的 host 门槛
 * 恰恰需要这类用例来证明它没有松成"整个学校都收"。
 */
class ScanRejectClassifierTest {

    /** 真码逐字原文（投影照片经 zxing-cpp 解出）：这条链上唯一一份**取证到的**入参形状 */
    private val realCode = REAL_CODE

    // ---- ① 入出表 --------------------------------------------------------------------

    @Test
    fun `每一条原文落到哪一档带着什么形状`() {
        for (case in CASES) {
            val info = ScanRejectClassifier.classify(case.raw)
            assertEquals("${case.title}：档位错了", case.rung, info.rung)
            assertEquals("${case.title}：归哪一族错了", case.family, info.family)
            assertEquals("${case.title}：host 错了", case.host, info.host)
            assertEquals("${case.title}：端口错了", case.port, info.port)
            assertEquals("${case.title}：长度错了", (case.raw ?: "").trim().length, info.textLength)
            assertEquals("${case.title}：参数名表错了", case.paramNames, info.paramNames)
            assertEquals("${case.title}：缺的那几项错了", case.wanted, info.wantedParams)
            assertEquals("${case.title}：不合用的那几项错了", case.flagged, info.flaggedParams)
            val trimmed = (case.raw ?: "").trim()
            assertEquals(
                "${case.title}：链接形状与 scheme 必须同生同灭",
                trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true),
                info.scheme != null,
            )
        }
    }

    /** 档位必须说得出话，而且**每一档都有人走**：enum 里长出一档没人钉、或两档说成一句，都是假分档 */
    @Test
    fun `每一档都有人钉且各不相同`() {
        val covered = CASES.map { it.rung }.toSet()
        for (rung in ScanRejectRung.values()) {
            assertTrue("档位 $rung 一条用例都没有：那是给界面留的一条死路", covered.contains(rung))
        }
        val sentences = ScanRejectRung.values().map { rung ->
            val case = CASES.first { it.rung == rung }
            scanRejectCardText(ScanRejectClassifier.classify(case.raw))
        }
        assertEquals("有两档说成了同一句话（分档等于没分）：", sentences.size, sentences.toSet().size)
        assertEquals("档位表就这些：", 7, ScanRejectRung.values().size)
        assertEquals("这一族 + 别家 + 说不清，三枚（T85 之前是四枚，多的那枚是智学北航）：", 3, ScanCodeFamily.values().size)
    }

    /** 路径只念我们这一族的：别家 host 的链接连归属都判不出，路径上有什么没人核过 */
    @Test
    fun `别家的路径不上界面也不上日志`() {
        val foreign = CASES.first { it.title == "别家的链接" }
        val info = ScanRejectClassifier.classify(foreign.raw)
        assertNotNull("这条用例本该带着路径（否则判不出这一档在钉什么）：", info.path)
        val evidence = scanRejectEvidenceText(info)
        val line = scanRejectForensicLine(info)
        assertFalse("别家路径上了失败卡：$evidence", evidence.contains(info.path!!))
        assertTrue("取证行上没写明这是规则不是漏了：$line", line.contains("路径=不记录"))
        val ours = CASES.first { it.rung == ScanRejectRung.WrongRoute }
        val oursInfo = ScanRejectClassifier.classify(ours.raw)
        assertTrue(
            "我们自己的路径却没念出来：${scanRejectEvidenceText(oursInfo)}",
            scanRejectEvidenceText(oursInfo).contains(oursInfo.path!!),
        )
    }

    /** 判据把原文装在返回值里就是白装（而且早晚会有人整个打印出去）：形状件才是它交出去的东西 */
    @Test
    fun `判据的返回值里装不下原文`() {
        val info = ScanRejectClassifier.classify(realCode)
        val rendered = info.toString()
        for (value in NEVER_SHOWN) {
            assertFalse("data class 的 toString 里漏出了参数值「$value」：$rendered", rendered.contains(value))
        }
        assertFalse("判据把整条原文带出来了：$rendered", rendered.contains(realCode))
    }

    // ---- ② 不变量 --------------------------------------------------------------------

    /**
     * 全矩阵：`classify == Recognized` ⟺ `parse != null`。
     *
     * 判档若自己另量一遍门槛，这里就会分家 —— 而分家的表现是"界面上一句假话"，
     * 不是"测试红了"，所以这一条要跑遍组合而不是抽样。
     */
    @Test
    fun `判档与收码是同一本账`() {
        var checked = 0
        for (scheme in SCHEMES) {
            for (authority in AUTHORITIES) {
                for (path in PATHS) {
                    for (query in QUERIES) {
                        val head = if (scheme.isEmpty()) authority.removePrefix("://") else "$scheme$authority"
                        val text = "$head$path$query"
                        val accepted = ScanTargetParser.parse(text) != null
                        val info = ScanRejectClassifier.classify(text)
                        checked++
                        assertEquals(
                            "「收不收」与「为什么说它不收」分家了：$text",
                            accepted,
                            info.rung == ScanRejectRung.Recognized,
                        )
                        if (!accepted) {
                            val card = scanRejectCardText(info)
                            val evidence = scanRejectEvidenceText(info)
                            val line = scanRejectForensicLine(info)
                            assertTrue("失败档却说不出话：$text", card.isNotBlank())
                            assertTrue("失败档说空话：$card", card.length > 8)
                            assertTrue("失败档连长度都不给：$text", evidence.contains("字符"))
                            assertTrue("失败档没说扫到的是什么：$text", evidence.contains("参数="))
                            for (value in NEVER_SHOWN) {
                                for (output in listOf(card, evidence, line)) {
                                    assertFalse("「$text」的输出里漏出了参数值「$value」：$output", output.contains(value))
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("矩阵只剩 $checked 条，四轴里某一轴空了：", checked >= 1_500)
    }

    /** null 与空白也必须说得出话（这条链上没有"什么都不发生"那一档） */
    @Test
    fun `空输入也有一档`() {
        for (raw in listOf<String?>(null, "", "   ")) {
            val info = ScanRejectClassifier.classify(raw)
            assertEquals("空输入必须落到「不是一张码」：$raw", ScanRejectRung.NotACode, info.rung)
            assertTrue("空输入的取证行连长度都没有：", scanRejectForensicLine(info).contains("长度=0"))
        }
    }

    // ---- ③ 措辞与隐私 ----------------------------------------------------------------

    /** 界面原话逐字钉住：改一个字都要红在这里，而不是红在同学的手机上 */
    @Test
    fun `每一档的界面原话`() {
        assertEquals(
            "iClass 的判据说这张码收得下，签到却没开始 —— 它的形状已记进取证日志。",
            scanRejectCardText(ScanRejectClassifier.classify(realCode)),
        )
        assertEquals(
            "扫到的不是签到码：这一族只认北航 iClass 的签到链接，而它连链接都不是。",
            scanRejectCardText(ScanRejectClassifier.classify("智慧教室 3 号楼")),
        )
        assertEquals(
            "这是 weixin.qq.com 的码，但不是北航 iClass 的签到码。",
            scanRejectCardText(ScanRejectClassifier.classify("https://weixin.qq.com/r/abc?x=1")),
        )
        // T85 新账：智学北航的域名从前是"我们这一族的"，现在是**别家**。这一句逐字钉住，
        // 因为它是这张卡最容易写成假话的地方 —— 判据若还留着那一道 host 门槛，文案就会说漏。
        assertEquals(
            "这是 spoc.buaa.edu.cn 的码，但不是北航 iClass 的签到码。",
            scanRejectCardText(ScanRejectClassifier.classify(FOREIGN_SPOC_CODE)),
        )
        assertEquals(
            "这是一条认不出服务器的链接，也说不出它是不是北航 iClass 的码。",
            scanRejectCardText(ScanRejectClassifier.classify(SUFFIX_SPOOF)),
        )
        assertEquals(
            "这是 iclass.buaa.edu.cn:8081 的码，可 /app/course/stu_auto_sign.action 不是学生扫码签到的入口。",
            scanRejectCardText(ScanRejectClassifier.classify(AUTO_SIGN_ROUTE)),
        )
        assertEquals(
            "这是 iclass.buaa.edu.cn:8081 的码，还缺签到要用的参数：courseSchedId。",
            scanRejectCardText(ScanRejectClassifier.classify(NO_SCHED_ID)),
        )
        assertEquals(
            "这是 iclass.buaa.edu.cn:8081 的码，参数名对得上，值却用不了：courseSchedId。",
            scanRejectCardText(ScanRejectClassifier.classify(BAD_SCHED_ID)),
        )
        assertEquals(
            "这是 iclass.buaa.edu.cn:8081 的码，可它已经带着提交才拼的 id —— 是一条发过的请求，不是投影那张码。",
            scanRejectCardText(ScanRejectClassifier.classify("$realCode&id=$USER_ID")),
        )
        // 从前"只有路由片段"是智学北航认的第二种形态，如今与一串普通文本同档
        assertEquals(
            "扫到的不是签到码：这一族只认北航 iClass 的签到链接，而它连链接都不是。",
            scanRejectCardText(ScanRejectClassifier.classify("/pages/table/signIn?qdid=abc")),
        )
    }

    /** 失败卡第二行的形状：host（含端口）、路径（只有我们这一族才给）、参数名、字符数，一个字都不多 */
    @Test
    fun `失败卡上的取证行念得出形状`() {
        assertEquals(
            "host=weixin.qq.com · 参数=x · 31 字符",
            scanRejectEvidenceText(ScanRejectClassifier.classify("https://weixin.qq.com/r/abc?x=1")),
        )
        assertEquals(
            "host=iclass.buaa.edu.cn:8081 · 路径=/app/course/stu_scan_sign.action · 参数=courseschedid、timestamp · 108 字符",
            scanRejectEvidenceText(ScanRejectClassifier.classify(realCode)),
        )
        assertEquals(
            "host=(读不出服务器) · 参数=courseschedid · 60 字符",
            scanRejectEvidenceText(ScanRejectClassifier.classify("http://:8081/app/course/stu_scan_sign.action?courseSchedId=1")),
        )
    }

    /** 措辞纪律：这一档是我们判出来的，不是用户操作错了 —— 不许出现"把手机对准"那类没有信息量的指令 */
    @Test
    fun `措辞里不许出现没有信息量的指令句`() {
        for (case in CASES) {
            val info = ScanRejectClassifier.classify(case.raw)
            val card = scanRejectCardText(info)
            assertTrue("${case.title}：失败卡上那句话太长了（卡片会被顶出画面）：$card", card.length <= 90)
            for (banned in BANNED_PHRASES) {
                assertFalse("${case.title}：失败卡上出现了「$banned」：$card", card.contains(banned))
                assertFalse(
                    "${case.title}：取证行上出现了「$banned」",
                    scanRejectEvidenceText(info).contains(banned),
                )
                assertFalse(
                    "${case.title}：日志上出现了「$banned」",
                    scanRejectForensicLine(info).contains(banned),
                )
            }
        }
        // 措辞本体（不带 host/路径插值的那半句）必须短：这是"措辞要短"唯一能被钉住的形式
        for (rung in ScanRejectRung.values()) {
            val card = scanRejectCardText(infoOf(rung))
            assertTrue("$rung 的固定措辞太长了：$card", card.length <= 70)
        }
    }

    /**
     * 隐私：真实参数值一个字都不许出现在界面或日志上。
     *
     * 判形状时当然读了值（`courseSchedId=2488752`、`timestamp=…`），而滚动码的值一旦进了 logcat，
     * 就等于把"还能不能再签一次"写进了系统日志 —— 这一条跑遍表与矩阵，不靠自觉。
     */
    @Test
    fun `一个参数值都不许出现在界面或日志上`() {
        val inputs = ArrayList<String?>()
        inputs += CASES.map { it.raw }
        inputs += listOf(realCode, "$realCode&id=$USER_ID", FOREIGN_SPOC_CODE, SUFFIX_SPOOF)
        for (text in inputs) {
            val info = ScanRejectClassifier.classify(text)
            for (line in listOf(scanRejectCardText(info), scanRejectEvidenceText(info), scanRejectForensicLine(info))) {
                for (value in NEVER_SHOWN) {
                    assertFalse("「$text」的输出里漏出了参数值「$value」：$line", line.contains(value))
                }
            }
        }
        // 明写规则，而不是留一个空槽让人猜
        assertTrue(scanRejectForensicLine(ScanRejectClassifier.classify(realCode)).contains("参数值=未记录"))
    }

    /** 取证行的形状（档位 + 长度 + host + 参数名 + 一句"值未记录"），逐字钉两条真的 */
    @Test
    fun `取证行长这样`() {
        assertEquals(
            "扫码解析失败 档=RequestUrlNotCode 族=IClass 长度=120 形状=http://iclass.buaa.edu.cn:8081" +
                "/app/course/stu_scan_sign.action 参数名=courseschedid,timestamp,id 参数值=未记录 不合用=id",
            scanRejectForensicLine(ScanRejectClassifier.classify("$realCode&id=$USER_ID")),
        )
        // `族=` 这一列 T85 之后只有 IClass / Foreign / None 三种取值：从前这条靶子（同为
        // `*.buaa.edu.cn` 的另一个子域）判的是 `族=Spoc 档=MissingParams 缺=zjdm+czid,qdid`，
        // 现在它就是一个别家 host —— 少一枚枚举、多一句实话。
        assertEquals(
            "扫码解析失败 档=ForeignHost 族=Foreign 长度=34 形状=https://jw.buaa.edu.cn 路径=不记录" +
                " 参数名=foo 参数值=未记录",
            scanRejectForensicLine(ScanRejectClassifier.classify("https://jw.buaa.edu.cn/xk/qr?foo=1")),
        )
    }

    /** 收得下那一档的日志前缀不许写"失败"：那一格说的是两本账漂移，不是解析失败 */
    @Test
    fun `判档与收码打脸时日志说实话`() {
        val line = scanRejectForensicLine(ScanRejectClassifier.classify(REAL_CODE))
        assertTrue("这一档的前缀没换过来：$line", line.startsWith("扫码解析判档与收码打脸"))
        assertFalse("明明收得下，日志却报解析失败：$line", line.startsWith("扫码解析失败"))
        // 真失败那几档的前缀不动（`logcat -s ScanSignInParse | grep 扫码解析失败` 是它的过滤条件）
        for (case in CASES.filter { it.rung != ScanRejectRung.Recognized }) {
            assertTrue(
                "${case.title}：失败档的日志前缀漂了：${scanRejectForensicLine(ScanRejectClassifier.classify(case.raw))}",
                scanRejectForensicLine(ScanRejectClassifier.classify(case.raw)).startsWith("扫码解析失败"),
            )
        }
    }

    /** 名字、host 与路径都封顶：一枚 200 字的怪 host 不能把失败卡顶出画面 */
    @Test
    fun `超长的形状被截断而不是撑破卡片`() {
        val host = "a".repeat(60) + ".buaa.edu.cn"
        val info = ScanRejectClassifier.classify("https://$host/" + "b".repeat(80) + "?" + "c".repeat(60) + "=1")
        val card = scanRejectCardText(info)
        val evidence = scanRejectEvidenceText(info)
        assertFalse("host 没截断：$card", card.contains("a".repeat(33)))
        assertFalse("路径没截断：$evidence", evidence.contains("b".repeat(41)))
        assertFalse("参数名没截断：$evidence", evidence.contains("c".repeat(25)))
        assertTrue("截断处没留记号：$evidence", evidence.contains("…"))
        assertTrue("卡片那句话还是太长了：$card", card.length <= 90)
        // 取证行是"三枚封顶值 + 长度"，界面上占第二行：给它一个能容下 33+41+25 的账，而不是无限长
        assertEquals("第二行取证也太长了：$evidence", true, evidence.length <= 140)
        // 参数名多了也一样封顶：界面与日志都只念前几枚，第七枚起只留一个省略号
        val many = ScanRejectClassifier.classify("https://p.buaa.edu.cn/x?" + (1..12).joinToString("&") { "k$it=v" })
        assertEquals("判据自己把那串名字数错了：", 12, many.paramNames.size)
        assertTrue("前六枚都没念全：${scanRejectEvidenceText(many)}", scanRejectEvidenceText(many).contains("k6"))
        assertFalse("第七枚名字也念上去了：${scanRejectEvidenceText(many)}", scanRejectEvidenceText(many).contains("k7"))
    }

    // ---- 表 --------------------------------------------------------------------------

    private data class Case(
        val title: String,
        val raw: String?,
        val rung: ScanRejectRung,
        val family: ScanCodeFamily,
        val host: String? = null,
        val port: String? = null,
        val paramNames: List<String> = emptyList(),
        val wanted: List<String> = emptyList(),
        val flagged: List<String> = emptyList(),
    )

    /** 只给档位与措辞用的合成形状：host / 路径都取最短的那一枚，量出来的才是**措辞**本身 */
    private fun infoOf(rung: ScanRejectRung): ScanRejectInfo {
        val info = ScanRejectClassifier.classify(
            when (rung) {
                ScanRejectRung.Recognized -> realCode
                ScanRejectRung.NotACode -> "x"
                ScanRejectRung.ForeignHost -> "http://h.cn/x?a=1"
                ScanRejectRung.WrongRoute -> "http://iclass.buaa.edu.cn/r?a=1"
                ScanRejectRung.MissingParams -> "http://iclass.buaa.edu.cn/app/course/stu_scan_sign.action?a=1"
                ScanRejectRung.BadParamValue -> "http://iclass.buaa.edu.cn/app/course/stu_scan_sign.action?courseSchedId=%20x"
                ScanRejectRung.RequestUrlNotCode -> "http://iclass.buaa.edu.cn/app/course/stu_scan_sign.action?courseSchedId=1&id=2"
            },
        )
        check(info.rung == rung) { "合成形状落到了 $info，而不是 $rung" }
        return info
    }

    private companion object {
        const val REAL_CODE =
            "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?courseSchedId=2488752&timestamp=1790247391994"

        const val SCAN_SIGN = "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action"
        const val NO_SCHED_ID = "$SCAN_SIGN?timestamp=1790247391994"
        const val BAD_SCHED_ID = "$SCAN_SIGN?courseSchedId=%20x"
        const val AUTO_SIGN_ROUTE = "http://iclass.buaa.edu.cn:8081/app/course/stu_auto_sign.action?courseSchedId=2488752"

        /** 后缀伪装：`…:8081.evil.com` —— splitAuthority 那一刀就把它判成畸形（authority 读不出来） */
        const val SUFFIX_SPOOF =
            "http://iclass.buaa.edu.cn:8081.evil.com/app/course/stu_scan_sign.action?courseSchedId=2488752"

        /**
         * 智学北航那张真码。T85 拆掉那一族之后它是**别家**的输入 —— 名字与形状都留着，
         * 因为"另一个 `*.buaa.edu.cn` 子域必须被 host 门槛挡在外面"这一条判据，
         * 没有比这张真码更合适的靶子。
         */
        const val FOREIGN_SPOC_CODE =
            "https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?qdid=1AA9A5D7F4295A0DE0630211FE0AB83E"
        const val USER_ID = "88888888"

        /** 一个都不许出现在任何输出里的参数值（取自实测、真码，以及下面那枚全矩阵） */
        val NEVER_SHOWN = listOf(
            "2488752", "1790247391994", "1AA9A5D7F4295A0DE0630211FE0AB83E", "1AA9A5",
            "88888888", "ZJ001", "ZJ11", "CZ22", "7777",
        )

        /** 卡面上不许出现的说法：全是"没有信息量的指令"与"已经不存在的出路" */
        val BANNED_PHRASES = listOf("对准", "请把", "走近", "拿稳", "重新扫", "换一张", "手输", "设置", "稍后", "秒", "照明")

        val CASES = listOf(
            Case("真码·iClass 收得下", REAL_CODE, ScanRejectRung.Recognized, ScanCodeFamily.IClass, host = "iclass.buaa.edu.cn", port = "8081", paramNames = listOf("courseschedid", "timestamp")),
            Case(
                "真码换成 https 那一档也收得下", REAL_CODE.replace(":8081", ":8181"),
                ScanRejectRung.Recognized, ScanCodeFamily.IClass,
                host = "iclass.buaa.edu.cn", port = "8181", paramNames = listOf("courseschedid", "timestamp"),
            ),
            Case(
                "缺 courseSchedId", NO_SCHED_ID,
                ScanRejectRung.MissingParams, ScanCodeFamily.IClass,
                host = "iclass.buaa.edu.cn", port = "8081", paramNames = listOf("timestamp"),
                wanted = listOf("courseSchedId"),
            ),
            Case(
                "courseSchedId 值不合形状", BAD_SCHED_ID,
                ScanRejectRung.BadParamValue, ScanCodeFamily.IClass,
                host = "iclass.buaa.edu.cn", port = "8081", paramNames = listOf("courseschedid"),
                flagged = listOf("courseSchedId"),
            ),
            Case(
                "路由不是扫码签到", AUTO_SIGN_ROUTE,
                ScanRejectRung.WrongRoute, ScanCodeFamily.IClass,
                host = "iclass.buaa.edu.cn", port = "8081", paramNames = listOf("courseschedid"),
            ),
            Case(
                "已经带着 id（发过的请求）", "$REAL_CODE&id=$USER_ID",
                ScanRejectRung.RequestUrlNotCode, ScanCodeFamily.IClass,
                host = "iclass.buaa.edu.cn", port = "8081",
                paramNames = listOf("courseschedid", "timestamp", "id"), flagged = listOf("id"),
            ),
            Case(
                "别家的链接", "https://weixin.qq.com/r/abc?x=1",
                ScanRejectRung.ForeignHost, ScanCodeFamily.Foreign,
                host = "weixin.qq.com", paramNames = listOf("x"),
            ),
            Case(
                "后缀伪装（authority 读不出）", SUFFIX_SPOOF,
                ScanRejectRung.ForeignHost, ScanCodeFamily.Foreign,
                paramNames = listOf("courseschedid"),
            ),
            Case(
                "空 host 的畸形链接", "http://:8081/app/course/stu_scan_sign.action?courseSchedId=1",
                ScanRejectRung.ForeignHost, ScanCodeFamily.Foreign,
                paramNames = listOf("courseschedid"),
            ),
            Case("就是一段文本", "智慧教室 3 号楼", ScanRejectRung.NotACode, ScanCodeFamily.None),
            Case("空字符串", "", ScanRejectRung.NotACode, ScanCodeFamily.None),
            Case("null", null, ScanRejectRung.NotACode, ScanCodeFamily.None),
            Case(
                "裸 ID 形状不对", "1AA9A5", ScanRejectRung.NotACode, ScanCodeFamily.None,
            ),
            Case(
                "智学北航的域名现在算别家", FOREIGN_SPOC_CODE,
                ScanRejectRung.ForeignHost, ScanCodeFamily.Foreign,
                host = "spoc.buaa.edu.cn",
            ),
            Case(
                "另一个 buaa 子域", "https://jw.buaa.edu.cn/xk/qr?foo=1",
                ScanRejectRung.ForeignHost, ScanCodeFamily.Foreign,
                host = "jw.buaa.edu.cn", paramNames = listOf("foo"),
            ),
            Case(
                "只有 SPOC 的路由片段（从前那一族认它）", "/pages/table/signIn",
                ScanRejectRung.NotACode, ScanCodeFamily.None,
            ),
            Case(
                "路由片段带着 qdid 也一样不是链接", "/pages/table/signIn?qdid=abc",
                ScanRejectRung.NotACode, ScanCodeFamily.None, paramNames = listOf("qdid"),
            ),
            Case(
                "裸 32 位 ID（SPOC 收得下的第三种形态，如今是纯文本）", "1AA9A5D7F4295A0DE0630211FE0AB83E",
                ScanRejectRung.NotACode, ScanCodeFamily.None,
            ),
            Case(
                "iClass 的 host 但路径是根", "https://iclass.buaa.edu.cn/",
                ScanRejectRung.WrongRoute, ScanCodeFamily.IClass,
                host = "iclass.buaa.edu.cn",
            ),
            Case(
                "iClass 的 host、参数名齐但值全空", "$SCAN_SIGN?courseSchedId=&timestamp=",
                ScanRejectRung.MissingParams, ScanCodeFamily.IClass,
                host = "iclass.buaa.edu.cn", port = "8081", wanted = listOf("courseSchedId"),
            ),
        )

        // ---- 全矩阵四轴 ----
        val SCHEMES = listOf("http", "https", "HTTP", "ftp", "")
        val AUTHORITIES = listOf(
            "://iclass.buaa.edu.cn:8081", "://iclass.buaa.edu.cn", "://ICLASS.BUAA.EDU.CN:8081",
            "://spoc.buaa.edu.cn", "://jw.buaa.edu.cn", "://example.com", "://iclass.buaa.edu.cn:8081.evil.com",
        )
        val PATHS = listOf(
            "", "/app/course/stu_scan_sign.action", "/app/course/stu_auto_sign.action",
            "/bhspoc/#/pages/table/signIn", "/other",
        )
        val QUERIES = listOf(
            "", "?timestamp=1790247391994", "?courseSchedId=2488752", "?courseSchedId=a%20b",
            "?courseSchedId=ok&id=7777", "?zjdm=ZJ11&czid=CZ22", "?qdid=1AA9A5D7F4295A0DE0630211FE0AB83E",
            "?qdid=ab", "?qdid=1AA9A5",
        )
    }
}
