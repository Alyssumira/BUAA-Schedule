package com.buaa.schedule.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T257②：[ScanFlightAdmission] 的表驱动格子（纯 JVM，零 android、零假时钟）。
 *
 * 这一颗管的是「一次提交正在飞的时候，又有一手递进来了」那一档，所以表的两端都要扭：
 * - **朝宽扭一次**（少挡一道）：后果是同一发请求与正在飞的那一发并成两发，长出第二次签到；
 * - **朝窄扭一次**（多挡一道）：后果是这一手被丢掉而屏上一个字都不说，就是本卡的病灶本身。
 *
 * 重点格是 `#9`：在飞 + payload 变了 + 冷却未过 —— 闸门先把这一枚记进账，状态机才丢它，
 * 于是用户扫到了、系统记下了、什么都没发生。修完之后这一格走
 * [ScanFlightAdmission.DropNewCodeWhileDispatching]：仍然不发那一发，但卡面与日志各有一句。
 */
class ScanFlightAdmissionTest {

    private fun judge(
        dispatching: Boolean,
        isRetryHand: Boolean = false,
        samePayload: Boolean = false,
        alreadyNoticed: Boolean = false,
        cooldownRemainingMillis: Long = RescanCooldownMillis,
    ): ScanFlightAdmission = scanFlightAdmission(
        dispatching = dispatching,
        isRetryHand = isRetryHand,
        samePayloadAsDispatching = samePayload,
        alreadyNoticedForThisDispatch = alreadyNoticed,
        cooldownRemainingMillis = cooldownRemainingMillis,
    )

    private data class Row(
        val dispatching: Boolean,
        val isRetryHand: Boolean,
        val samePayload: Boolean,
        val alreadyNoticed: Boolean,
        val expected: ScanFlightAdmission,
        val why: String,
    )

    /** ① 十六格全表：每一格的预期都手写，不拿判据自己算预期（那就是套套逻辑） */
    @Test
    fun everyBooleanCellIsHandWritten() {
        val rows = listOf(
            Row(false, false, false, false, ScanFlightAdmission.Dispatch, "#1 空着、第一帧：投"),
            Row(false, false, false, true, ScanFlightAdmission.Dispatch, "#2 念过一句不影响投"),
            Row(false, false, true, false, ScanFlightAdmission.Dispatch, "#3 同一枚也一样投：没有东西在飞就没什么可重"),
            Row(false, false, true, true, ScanFlightAdmission.Dispatch, "#4 同上"),
            Row(false, true, false, false, ScanFlightAdmission.Dispatch, "#5 相册那一手空着时照投"),
            Row(false, true, false, true, ScanFlightAdmission.Dispatch, "#6 同上"),
            Row(false, true, true, false, ScanFlightAdmission.Dispatch, "#7 同上"),
            Row(false, true, true, true, ScanFlightAdmission.Dispatch, "#8 不投是唯一能让其余四枚起作用的前提"),
            Row(true, false, false, false, ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis), "#9 重点格：在飞＋换了一枚＋冷却未过"),
            Row(true, false, false, true, ScanFlightAdmission.DropAlreadyNoticed, "#10 同一次在飞不重复念"),
            Row(true, false, true, false, ScanFlightAdmission.DropSameCodeWhileDispatching, "#11 在飞＋同一枚：故意不重投"),
            Row(true, false, true, true, ScanFlightAdmission.DropAlreadyNoticed, "#12 「念过没有」排在「同一枚」之前"),
            Row(true, true, false, false, ScanFlightAdmission.DropUserHandWhileDispatching, "#13 按次那一下必须自己回答"),
            Row(true, true, false, true, ScanFlightAdmission.DropUserHandWhileDispatching, "#14 用户那一手排在「已经念过」之前"),
            Row(true, true, true, false, ScanFlightAdmission.DropUserHandWhileDispatching, "#15 同上"),
            Row(true, true, true, true, ScanFlightAdmission.DropUserHandWhileDispatching, "#16 同上"),
        )
        assertEquals("表的格数应当是 16（四枚布尔的全排列）：", 16, rows.size)
        for (row in rows) {
            assertEquals(
                row.why + " —— d=" + row.dispatching + " r=" + row.isRetryHand + " n=" + row.alreadyNoticed + " s=" + row.samePayload,
                row.expected,
                judge(row.dispatching, row.isRetryHand, row.samePayload, row.alreadyNoticed),
            )
        }
        // 五档全部被表打到：少一档就是内核里长出一格永不进入的死支路
        assertEquals(
            "表里出现的档位不是五档：",
            setOf(
                ScanFlightAdmission.Dispatch::class.java,
                ScanFlightAdmission.DropNewCodeWhileDispatching::class.java,
                ScanFlightAdmission.DropSameCodeWhileDispatching::class.java,
                ScanFlightAdmission.DropUserHandWhileDispatching::class.java,
                ScanFlightAdmission.DropAlreadyNoticed::class.java,
            ),
            rows.map { it.expected.javaClass }.toSet(),
        )
    }

    /** ② 重点格两侧：每扭一枚输入都要落进**另一档**；落不动的那一枚就是没进档位判据的装饰参数 */
    @Test
    fun theKeyCellMovesToADifferentTierWhicheverWayEachInputIsTwisted() {
        assertEquals(
            "重点格本身：在飞＋换了一枚＋冷却未过",
            ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis),
            judge(dispatching = true),
        )
        // 朝宽：把「在飞」扭掉 ⇒ 这一手投得出去。这一档一松，同一发请求就会与正在飞的那一发
        // 并成两发（双重签到），所以「在飞与否」坐在判据的第一位。
        assertEquals(
            "扭掉 dispatching 之后不是投：重点格的闸门不管用了",
            ScanFlightAdmission.Dispatch,
            judge(dispatching = false),
        )
        // 朝窄：这一枚就是正在飞的那一枚 ⇒ 换成「故意不重投」那一档（另一句话）
        assertEquals(
            "扭成同一枚 payload 之后还在念「换了一张码」：那句话就成了假话",
            ScanFlightAdmission.DropSameCodeWhileDispatching,
            judge(dispatching = true, samePayload = true),
        )
        // 朝窄：屏幕上已经挂着这一档的一句话 ⇒ 不再重复念（否则同一张卡按帧重写）
        assertEquals(
            "扭成「已经念过」之后还要念第二句：按帧路径会把那句话刷成噪音",
            ScanFlightAdmission.DropAlreadyNoticed,
            judge(dispatching = true, alreadyNoticed = true),
        )
        // 朝窄：这一手是用户按下去的 ⇒ 必须换成按次那一句，不能被「已经念过」吞掉
        assertEquals(
            "扭成按次那一手之后不说话了：我刚点的那一下又没人回答",
            ScanFlightAdmission.DropUserHandWhileDispatching,
            judge(dispatching = true, isRetryHand = true),
        )
        // 朝宽（措辞那一侧）：冷却剩余是被**带着走**的事实，不是档位的分支
        assertEquals(
            "冷却剩余换了数 ⇒ 档位也跟着换：这一枚不该进档位判据",
            ScanFlightAdmission.DropNewCodeWhileDispatching(1L),
            judge(dispatching = true, cooldownRemainingMillis = 1L),
        )
        // 而"在飞"那一侧永远扭不回 Dispatch：任何一枚其余输入都开不出补投的口子
        for (cell in listOf(
            judge(true, false, false, false, 0L),
            judge(true, false, false, true, 0L),
            judge(true, false, true, true, RescanCooldownMillis * 4),
            judge(true, true, false, false, NoLedgerCooldownRemainingMillis),
        )) {
            assertNotEquals(
                "在飞的时候有一格能劝回「投」：那等于在这一页开了自动补投的口子",
                ScanFlightAdmission.Dispatch,
                cell,
            )
        }
    }

    /** ③ 冷却剩余只许被带走，不许把负数念进句子（时钟倒退那一档与 [shouldSubmitScan] 同方向） */
    @Test
    fun cooldownRemainingTravelsButNeverGoesNegativeOnTheTraceLine() {
        val skewed = droppedArrivalTraceText(ScanFlightAdmission.DropNewCodeWhileDispatching(-800L))
        assertTrue("冷却倒退的读数没夹到零：$skewed", !skewed.contains("-800") && skewed.contains("0 毫秒"))
        val normal = droppedArrivalTraceText(ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis))
        assertTrue(
            "正常档没把调用点给的冷却剩余念出来（那一枚就成了装饰参数）：$normal",
            normal.contains("1500 毫秒"),
        )
        assertNotEquals(
            "「没有账」与「冷却已过完」必须是同一个取值以外的两回事——这一枚只许是 0：",
            RescanCooldownMillis,
            NoLedgerCooldownRemainingMillis,
        )
        assertEquals("相册那一路的「没有账」应当是 0：", 0L, NoLedgerCooldownRemainingMillis)
    }

    /** ④ 卡面三句逐字钉：每句都要说清「这一手没投出去」，并且不许糊成一句「签到失败」 */
    @Test
    fun eachSpokenTierSaysWhereTheHandWent() {
        val newCode = droppedArrivalCardText(ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis))
        assertEquals(
            "换了一枚码那一句：",
            "上一次签到还在提交，这一张码没有投出去。它已经记进重扫冷却的账，冷却期内不会再自动补投。" +
                "等屏幕上的提交结果出来之后按「重新扫码」，再对准一次。",
            newCode,
        )
        val sameCode = droppedArrivalCardText(ScanFlightAdmission.DropSameCodeWhileDispatching)
        assertEquals(
            "同一枚码那一句：",
            "上一次签到还在提交，屏幕上这一张与正在提交的那一张是同一枚，所以这一手没有投出去。" +
                "重复提交一次签到比这一手被吃掉更糟。",
            sameCode,
        )
        val userHand = droppedArrivalCardText(ScanFlightAdmission.DropUserHandWhileDispatching)
        assertEquals(
            "相册那一手那一句：",
            "上一次签到还在提交，你刚递进来的这一次没有投出去。等屏幕上的提交结果出来之后，再选一次那张图。",
            userHand,
        )
        // 三档三句：糊成同一句就是分档白分，用户与编排者都归不了因
        assertEquals(3, setOf(newCode, sameCode, userHand).size)
        for (text in listOf(newCode, sameCode, userHand)) {
            assertTrue("那一句没说明白「还在提交」：$text", text.contains("还在提交"))
            assertTrue("那一句没说明白「没有投出去」：$text", text.contains("没有投出去"))
            assertFalse("那一句糊成了网络故障的说法：$text", text.contains("网络异常"))
        }
        // 只有真的还有一步可走的那两句给落点；「同一枚」那一档要等的就是屏幕上那一发，
        // 给它一个按钮就是把用户支去按一颗并不需要按的钮（那一条路本来就投不出去第二次）
        assertTrue("换一枚那一句没给下一步的落点：", newCode.contains("等屏幕上的提交结果出来之后按「重新扫码」"))
        assertTrue("相册那一句没给下一步的落点：", userHand.contains("等屏幕上的提交结果出来之后，再选一次那张图"))
        assertFalse("「同一枚」那一句把用户指向了「重新扫码」（这一档不需要按任何东西）：", sameCode.contains("重新扫码"))
        assertFalse("「同一枚」那一句把用户指向了相册（这一档根本没有图的事）：", sameCode.contains("再选一次"))
        assertTrue("「同一枚」那一句没交代这是故意不重投：", sameCode.contains("重复提交一次签到比这一手被吃掉更糟"))
    }

    /** ⑤ 反指：六句都不许把用户指向一条本仓已经删掉的退路，也不许带出一个字符的原文 */
    @Test
    fun theSixLinesPointAtNothingThatDoesNotExistOnThisPage() {
        val all = listOf(
            droppedArrivalCardText(ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis)),
            droppedArrivalCardText(ScanFlightAdmission.DropSameCodeWhileDispatching),
            droppedArrivalCardText(ScanFlightAdmission.DropUserHandWhileDispatching),
            droppedArrivalTraceText(ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis)),
            droppedArrivalTraceText(ScanFlightAdmission.DropSameCodeWhileDispatching),
            droppedArrivalTraceText(ScanFlightAdmission.DropUserHandWhileDispatching),
        )
        for (banned in listOf("手输", "输入签到码", "手动输入", "粘贴", "重输", "刷新页面")) {
            for (text in all) assertFalse("句子把用户指向不存在的退路（$banned）：$text", text.contains(banned))
        }
        // 滚动码的原文与参数值一个都不许进句子：这两句话的听众里有拿到这台设备的人
        for (leaked in listOf("stu_scan_sign", "courseSchedId", "timestamp", "http", "action?")) {
            for (text in all) assertFalse("句子里漏出了原文的形状（$leaked）：$text", text.contains(leaked))
        }
        for (text in all) {
            assertEquals("全角括号不配平：$text", 0, text.count { it == '（' } - text.count { it == '）' })
        }
    }

    /** ⑥ 不念的那两档根本没有句子可念 —— 类型层面就拿不到话（调用点写不出"念一句空话"那一支） */
    @Test
    fun theSilentTiersAreNotInTheSpokenFamily() {
        val spoken = listOf<ScanFlightAdmission>(
            ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis),
            ScanFlightAdmission.DropSameCodeWhileDispatching,
            ScanFlightAdmission.DropUserHandWhileDispatching,
        )
        assertTrue("会念的三档里有一档不是 Dropped：", spoken.all { it is ScanFlightAdmission.Dropped })
        // 把十六格全跑一遍，落在"会念"那一族的档位必须恰好三档，"不念"的必须恰好两档：
        // 多一档 = 内核里长出一格永不进入的死支路，少一档 = 有一句实话没人念。
        val reached = mutableListOf<ScanFlightAdmission>()
        for (d in listOf(false, true)) {
            for (r in listOf(false, true)) {
                for (n in listOf(false, true)) {
                    for (s in listOf(false, true)) reached.add(judge(d, r, s, n))
                }
            }
        }
        assertEquals("全表走到的档位不是五档：", 5, reached.distinct().size)
        assertEquals("会念的档不是三档：", 3, reached.filterIsInstance<ScanFlightAdmission.Dropped>().distinct().size)
        assertEquals(
            "不念的档不是两档（Dispatch 与 DropAlreadyNoticed 之外都不许不念）：",
            2,
            reached.filter { it !is ScanFlightAdmission.Dropped }.distinct().size,
        )
        for (silent in listOf<ScanFlightAdmission>(ScanFlightAdmission.Dispatch, ScanFlightAdmission.DropAlreadyNoticed)) {
            assertFalse("$silent 落在会念的那一族里（调用点就会给它写一句空话）：", silent is ScanFlightAdmission.Dropped)
        }
    }

    /** ⑦ 卡面与取证行同档同真相：同一档的两句话必须说的是同一件事，措辞各写一份就是第二把尺 */
    @Test
    fun cardAndTraceAgreeOnWhichHappeningTheyDescribe() {
        val pairs = listOf<ScanFlightAdmission.Dropped>(
            ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis),
            ScanFlightAdmission.DropSameCodeWhileDispatching,
            ScanFlightAdmission.DropUserHandWhileDispatching,
        )
        for (pair in pairs) {
            val card = droppedArrivalCardText(pair)
            val trace = droppedArrivalTraceText(pair)
            assertTrue("取证行没交代「在飞」这一前提，与卡面对不上：$trace", trace.contains("在飞"))
            assertTrue("卡面短到读不出这是一次丢弃：$card", card.length >= 30)
            assertTrue("取证行塌成了与卡面同句（两句话各有听众）：", trace != card && trace.length >= 20)
        }
        // 相册那一档的取证行不许冒充按帧：那一次根本没有码，只有一张图
        assertTrue(
            "相册档的取证行没说清是相册：",
            droppedArrivalTraceText(ScanFlightAdmission.DropUserHandWhileDispatching).contains("相册"),
        )
        assertTrue(
            "按帧两档的取证行没说是「这一张码」：",
            droppedArrivalTraceText(ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis)).contains("这一张码") &&
                droppedArrivalTraceText(ScanFlightAdmission.DropSameCodeWhileDispatching).contains("这一张码"),
        )
    }
}
