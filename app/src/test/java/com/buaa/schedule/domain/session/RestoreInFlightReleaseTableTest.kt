package com.buaa.schedule.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T390②：表驱动打表 `domain/session/RestoreInFlightReleaseGate`（乙档新内核）。
 *
 * ## 这一族问的是什么
 *
 * 「用户点退出登录那一刻，恢复链还在飞的那一枚 WebView 归谁拆」—— 两枚事实（在不在手、
 * 是不是会话槽那一枚本身）折成三档。它**不是** `SessionRetainGate.decide` 的续集：
 * 那枚管「这枚实例配不配占会话槽」，本枚只管「要不要当场动手拆」。
 *
 * ## 为什么值得单独打表
 *
 * 这四行里有一行是**陷阱行**：`inFlightHeld == false` 时 `inFlightIsSession` 是无意义的一维
 * （调用点递的是 `inFlight === web`，两枚都是 `null` 就比出 `true`）。判据顺序若反成
 * 「先问是不是同一枚、再问在不在手」，那一行会落进 [InFlightRelease.LeaveToSessionTeardown]
 * ——「交给会话那一手拆」，而那一刻会话槽也是空的，于是**谁都不拆**：本卡要修的「没人拆」
 * 原样留在盘上，而且带着「内核已经答过了」的外衣。第 3 格拿一枚手写的反序探针把那一行钉死。
 *
 * ## 输入面
 *
 * 纯 JVM：四行输入全是布尔，零设备、零时钟、零工作树读取（形状与纯度归
 * `data/import/BuaaWebSessionLifecycleGuardTest` 第⑪格，本文件不重复读盘）。
 *
 * ## 钉不住的行为（明说，不许用「覆盖了」这个词）
 *
 * 本文件钉的是**内核的档位表**。它不证明 `clear()` 真按档分了派、不证明拆实例那四手
 * （换委托／摘树／stopLoading／destroy）真在 `clear()` 里跑了、更不证明真机上退出登录那一瞬间
 * 到底有没有一枚在飞的 WebView —— 那三件都只能装机判（本卡零 adb，已登记给编排者）。
 */
class RestoreInFlightReleaseTableTest {

    /** 四行全表：每行「事实 → 该给的档」，逐枚点名场景，不许并成一句「看情况」 */
    @Test
    fun `四行输入各给一档 三档都被命中过`() {
        val rows = listOf(
            Row("恢复没在跑、会话槽也是空的（冷启动即退出）", false, false, InFlightRelease.NothingHeld),
            Row("两枚都空 —— 此刻「是不是同一枚」是无意义的一维", false, true, InFlightRelease.NothingHeld),
            Row("在飞的那一枚就是会话槽那一枚：会话那一手会拆它", true, true, InFlightRelease.LeaveToSessionTeardown),
            Row("闸正由本次退出登录拆掉、实例另有其枚 —— 本卡修的那一档", true, false, InFlightRelease.ReapNow),
        )
        for (row in rows) {
            assertEquals(
                row.label + "：decide(inFlightHeld=" + row.inFlightHeld +
                    ", inFlightIsSession=" + row.inFlightIsSession + ")",
                row.expected,
                RestoreInFlightReleaseGate.decide(row.inFlightHeld, row.inFlightIsSession),
            )
        }
        val hit = rows.map { it.expected }.distinct()
        assertEquals(
            "三档都得被这张表命中过（实到 " + hit.size + " 档）：少一档就是有一档在表里没有自己的格子，" +
                "它的语义只活在 KDoc 里",
            listOf(
                InFlightRelease.NothingHeld,
                InFlightRelease.LeaveToSessionTeardown,
                InFlightRelease.ReapNow,
            ).sortedBy { it.name },
            hit.sortedBy { it.name },
        )
    }

    /** 题眼格：在飞且另有其枚 ⇒ ReapNow（这一行就是改前那枚孤儿的形状） */
    @Test
    fun `题眼格 在飞而实例另有其枚必须给当场拆而不是留给闸`() {
        assertNotEquals(
            "本卡的病根就是「在飞那枚只被超时闸的闭包握着，而 clear() 把闭包拆了」：\n" +
                "如果这一行给出 LeaveToSessionTeardown 或 NothingHeld，就等于把「留给闸」重新写成一句空话 ——\n" +
                "闸已经由同一次 clear() 拆掉了，留给它的是零秒。\n" +
                "实到：" + RestoreInFlightReleaseGate.decide(true, false),
            InFlightRelease.NothingHeld,
            RestoreInFlightReleaseGate.decide(true, false),
        )
        assertEquals(
            "实到 " + RestoreInFlightReleaseGate.decide(true, false),
            InFlightRelease.ReapNow,
            RestoreInFlightReleaseGate.decide(true, false),
        )
    }

    /** 顺序格：反着问那一形会在「两枚都空」这一行改口，于是那一行钉的是先后而不是运气 */
    @Test
    fun `先问在不在手再问是不是同一枚 反序探针在这一行当场改口`() {
        val onDisk = RestoreInFlightReleaseGate.decide(inFlightHeld = false, inFlightIsSession = true)
        assertEquals(
            "盘上内核在「没在飞、而身份比较恰好为真」这一行必须给 NothingHeld（实到 " + onDisk +
                "）：给 LeaveToSessionTeardown 就是「交给会话那一手」，可那一刻会话槽也是空的 ⇒ 谁都不拆",
            InFlightRelease.NothingHeld,
            onDisk,
        )
        val reversed = reversedOrderProbe(inFlightHeld = false, inFlightIsSession = true)
        assertNotEquals(
            "反序探针必须在这同一行给出**不同**的档（实到 " + reversed +
                "）：两枚探针给同一答案就说明这一行什么都没钉住，第 3 格就成了自我实现的话",
            onDisk,
            reversed,
        )
        assertEquals(
            "反序探针在这一行给出的正是病档（实到 " + reversed + "）：",
            InFlightRelease.LeaveToSessionTeardown,
            reversed,
        )
        // 反序探针只在陷阱行与内核分岔，其余三行同答 —— 否则它钉的就不是顺序而是整张表
        for ((held, same) in listOf(Pair(false, false), Pair(true, true), Pair(true, false))) {
            assertEquals(
                "探针在非陷阱行（held=$held same=$same）必须与内核同答（实到 " +
                    reversedOrderProbe(held, same) + "）：不同答说明探针在改的是档位表本身，不是顺序",
                RestoreInFlightReleaseGate.decide(held, same),
                reversedOrderProbe(held, same),
            )
        }
    }

    /** 空转格不许吃掉另一格：NothingHeld 与 LeaveToSessionTeardown 是两件事 */
    @Test
    fun `空转与留给会话是两档 不许并`() {
        assertNotEquals(
            "「手里没有在飞的」与「有，但那枚就是会话自己」必须分档（实到都是 " +
                RestoreInFlightReleaseGate.decide(true, true) + " 与 " +
                RestoreInFlightReleaseGate.decide(false, false) + "）：" +
                "并档就等于把「同一枚只许拆一遍」那一手从调用点删掉 —— 于是 clear() 会先把在飞那枚" +
                "摘树拆掉，再让会话那一手对着同一枚 destroy 第二遍",
            RestoreInFlightReleaseGate.decide(true, true),
            RestoreInFlightReleaseGate.decide(false, false),
        )
        assertEquals(
            "内核只给三档（实到 " + InFlightRelease.values().size + " 档）：多一枚就是有人往这一族里" +
                "塞了「这枚实例配不配当会话」那一问 —— 那一问归 SessionRetainGate",
            3,
            InFlightRelease.values().size,
        )
        assertTrue(
            "三档档名逐枚在场（实到 " + InFlightRelease.values().map { it.name }.sorted().joinToString() + "）：",
            InFlightRelease.values().map { it.name }.sorted() ==
                listOf(
                    InFlightRelease.LeaveToSessionTeardown,
                    InFlightRelease.NothingHeld,
                    InFlightRelease.ReapNow,
                ).map { it.name }.sorted(),
        )
    }

    /** 纯函数格：同一行递两次给同一个档，且换序输入不换结论（本内核不读任何设备面） */
    @Test
    fun `同一行递两次同档 四行全覆盖也不多出第四档`() {
        assertEquals(
            "纯函数：decide(true, false) 递两次必须同档（实到 " +
                RestoreInFlightReleaseGate.decide(true, false) + "）",
            RestoreInFlightReleaseGate.decide(true, false),
            RestoreInFlightReleaseGate.decide(true, false),
        )
        val all = mutableListOf<InFlightRelease>()
        for (held in listOf(false, true)) {
            for (same in listOf(false, true)) {
                all += RestoreInFlightReleaseGate.decide(held, same)
            }
        }
        assertEquals(
            "四行输入折出来的档必须全在枚举那三档里（实到册子：" + all.map { it.name }.sorted().joinToString() +
                "）：冒出一枚表里没有的档，就是内核开始自己发明结论",
            InFlightRelease.values().toList().sortedBy { it.name },
            all.distinct().sortedBy { it.name },
        )
    }

    // ---------------- 探针与小工具 ----------------

    /**
     * 手写的一枚**反序探针**：同一张表，但把「是不是同一枚」排在「在不在手」之前。
     * 它不进生产码，只用来证明第 3 格那一行钉的是判据顺序（本仓 §58 那一族教训：
     * 探测器若不曾在病形上红过，它给出的绿就只是自我实现）。
     */
    private fun reversedOrderProbe(inFlightHeld: Boolean, inFlightIsSession: Boolean): InFlightRelease =
        if (inFlightIsSession) InFlightRelease.LeaveToSessionTeardown else RestoreInFlightReleaseGate.decide(
            inFlightHeld,
            inFlightIsSession,
        )

    private data class Row(
        val label: String,
        val inFlightHeld: Boolean,
        val inFlightIsSession: Boolean,
        val expected: InFlightRelease,
    )
}
