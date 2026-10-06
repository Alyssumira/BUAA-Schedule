package com.buaa.schedule.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

/**
 * T183①：把「撤销写库失败之后，这条撤销记录该怎么办、那句提示该说什么」钉成表驱动单测（纯 JVM）。
 *
 * 用户报的是效果：**点一次撤销、失败一句「撤销失败」，那条撤销就再也点不回来了** ——
 * 条目在落库之前已被 `UndoManager.pop()` 消费掉，而进程内那枚栈是那份快照的唯一副本
 * （编辑器删掉一整组时 `groupBeforeRows` 那一族行尤其贵）。完整账见 `UndoFailureRecovery.kt` 段首。
 *
 * 档位（如实写明）：`:app` 的 JVM 测试面没有 Robolectric、也没有能在 JVM 上跑 Room 的缝，
 * 而且 `android.database.sqlite.*` 那些类名**在 JVM 里根本造不出实例** ⇒ 本文件钉的是
 * 「按类名查表」这一判本身；"VM 那一步真的读了它、真的按返回值定文案"由
 * `UndoFailureRestoreWiringGuardTest` 钉，**真机上 SQLiteBusyException / 磁盘满那一档没验到**。
 *
 * 两侧都有格子（本仓口径）：
 * - 朝**严**扭（把 `RejectedBySnapshot` 也改成回推、或把 `retryInvitation` 全档打开）
 *   红在第 ③ 组的互锁格与第 ④ 组的整句；
 * - 朝**松**扭（把表清空、或把 `SQLException` 这类泛化名收进可重试档）
 *   红在第 ① 组那十五枚类名格子（表里每一枚都有格子）与第 ② 组那五枚次序格。
 */
class UndoFailureRecoveryTest {

    // ─────────────── ① 表本体：点名的类名逐枚判对，表外的一律兜底档 ───────────────

    @Test
    fun `异常类名逐枚查表 认不出的落进兜底档`() {
        for (cell in classNameCells) {
            assertEquals(
                "类名格子「${cell.className}」应当判成 ${cell.expected} —— ${cell.why}\n" +
                    "整表被清空 / 这一行被摘掉 ⇒ 判成 Unexplained（兜底档），" +
                    "于是那条撤销虽然还留着、提示却念成「原因没认出来」；" +
                    "把它写成可重试那一档 ⇒ 红了这一格就是拿不可恢复的失败许诺用户「再点一次」",
                cell.expected,
                undoFailureKindOf(listOf(cell.className)),
            )
        }
    }

    /**
     * 反套套逻辑：格子表与内核那两张册子**逐枚对齐**。
     *
     * 只数"格子有几枚"是假尺（表退化到零行时第 ① 组已经会红，但那一红容易被当成"格子写错了"），
     * 更躲不过的是**方向**：删一行表 ⇒ 第 ① 组红，但**添一行表却不写格子**今天谁都看不见
     * （那一行落在兜底档上、静默生效）。所以这里比的是两集的**相等**，不是枚数。
     */
    @Test
    fun `格子表的类名册子与内核那张表逐枚相等 榜上的档序也钉死`() {
        val celled = classNameCells.filter { it.expected != UndoFailureKind.Unexplained }.map { it.className }.toSet()
        val tabled = UNDO_FAILURE_TABLE.keys.toSet()
        val explained = classNameCells.filter { it.expected != UndoFailureKind.Unexplained }
        val distribution = UndoFailureKind.entries.toList().associateWith { kind ->
            classNameCells.count { it.expected == kind }
        }

        assertEquals(
            "内核表里点名的类名与格子表**判得出具体档**的那些必须同一集（表里每一枚都有格子、" +
                "格子里每一枚判得出的都在表上）。" +
                "\n  只在表上（没有格子那一枚的档位无人验）：" + (tabled - celled) +
                "\n  只在格子上（表里没点名 ⇒ 只能判成兜底档）：" + (celled - tabled),
            tabled,
            celled,
        )
        classNameCells.filter { it.expected == UndoFailureKind.Unexplained }.forEach { cell ->
            assertFalse(
                "「${cell.className}」判成兜底档的根据必须就是**表里没点名它**：" +
                    "表里一旦点名下它却仍判兜底，这一格就成了假尺（兜底档不该有在册的类名）",
                UNDO_FAILURE_TABLE.containsKey(cell.className),
            )
        }
        assertEquals(
            "表里不许有「把某枚类名直接判成兜底档」的行：兜底档的根据是**表里没点名**，" +
                "点名了就该说清它是哪一档（实到 " +
                UNDO_FAILURE_TABLE.values.count { it == UndoFailureKind.Unexplained } + " 行）",
            0,
            UNDO_FAILURE_TABLE.values.count { it == UndoFailureKind.Unexplained },
        )
        assertEquals(
            "判得出具体档的格子枚数 = 那张表的行数（对不上就是哪一行表没被判到）：" +
                "表 " + UNDO_FAILURE_TABLE.size + " 行 / 格子 " + explained.size + " 枚",
            UNDO_FAILURE_TABLE.size,
            explained.size,
        )
        assertEquals(
            "册子上点名的枚数常量（改了表不改这里 ⇒ 本格红，这是「新增/删除一行表」唯一的抓手）",
            EXPLAINED_CLASS_NAMES,
            UNDO_FAILURE_TABLE.size,
        )
        assertEquals(
            "四档各吃几枚格子是这张表的形状（锁 2 / 环境 4 / 快照写不进 6 / 兜底 3）；任何一档的枚数变了" +
                "都要回来重判「该不该回推」那两列 —— 实到 $distribution",
            mapOf(
                UndoFailureKind.WriteLockBusy to 2,
                UndoFailureKind.StorageUnavailable to 4,
                UndoFailureKind.RejectedBySnapshot to 6,
                UndoFailureKind.Unexplained to 3,
            ),
            distribution,
        )
        assertEquals(
            "榜上的档序恰好是那三档、且 RejectedBySnapshot 在最前（这一序就是第 ② 组那五枚次序格的根据；" +
                "退化成「取链上第一个命中」是写不出榜的，所以钉在这里）：实到 " + UNDO_FAILURE_PRIORITY,
            listOf(
                UndoFailureKind.RejectedBySnapshot,
                UndoFailureKind.StorageUnavailable,
                UndoFailureKind.WriteLockBusy,
            ),
            UNDO_FAILURE_PRIORITY,
        )
    }

    // ─────────────── ② 一趟链上两档并现：取最具体那一档，与链上次序无关 ───────────────

    @Test
    fun `同一趟链上两档并现时取最具体的 与链次序无关`() {
        for (cell in priorityCells) {
            val forward = cell.chain
            val reversed = cell.chain.reversed()
            assertEquals(
                "链 ${forward}（外层在前）应当判成 ${cell.expected} —— ${cell.why}",
                cell.expected,
                undoFailureKindOf(forward),
            )
            assertEquals(
                "同一枚链倒过来读 ${reversed} 必须是**同一个**档：按「链上第一个命中」取就把" +
                    "「快照本身写不进去」读成「库正忙」⇒ 回推 + 许诺重试 ⇒ 用户反复点、反复同一个约束错",
                cell.expected,
                undoFailureKindOf(reversed),
            )
        }
    }

    // ─────────────── ③ 两枚布尔 + 那句半句：逐档钉死，再钉三行互锁 ───────────────

    /**
     * 四档的三列逐档逐字（朝严 / 朝松两个方向的红都落在这里）：
     * - `restoreEntry` 朝严扭（全档 `false`）⇒ 回到本卡的病：失败就把条目吃掉；
     * - `restoreEntry` 朝松扭（`RejectedBySnapshot` 改 `true`）⇒ 一枚永远失败的条目压在栈顶，
     *   栈只有 pop 栈顶一条出路 ⇒ 压在它下面的条目**全都**撤不回来；
     * - `retryInvitation` 朝松扭（磁盘满那一档改 `true`）⇒ 环境不变它永远失败，那句"可以再点一次"
     *   就是本卡红线不许的那种承诺。
     */
    @Test
    fun `四档的回推与承诺两列逐档判对`() {
        val grid = UndoFailureKind.entries.toList().map { kind ->
            Triple(kind.name, "${kind.restoreEntry}/${kind.retryInvitation}", kind.heldSuffix)
        }
        assertEquals(
            "四档的「回不回推 / 许不许再点」与那半句本体逐字对齐（任何一列单独改动都得回来重判整张表）：" +
                grid.joinToString(separator = "\n  ", prefix = "\n  "),
            listOf(
                Triple("WriteLockBusy", "true/true", "；这条撤销还留着，可以再点一次"),
                Triple("StorageUnavailable", "true/false", "；这条撤销还留着，腾出能写的空间后再点一次"),
                Triple("RejectedBySnapshot", "false/false", ""),
                Triple("Unexplained", "true/false", "；这条撤销还留着，但失败的原因没认出来"),
            ),
            grid,
        )
    }

    /**
     * 三行互锁：列与列之间不许自相矛盾（**枚举里任何一档单独改一列都会撞到这里**）。
     *
     * 这一组是把「文案不许撒谎」写成结构判据的地方：`retryInvitation` 只能出现在真的回推的档上，
     * 「还留着」只能出现在真的留下的档上。
     */
    @Test
    fun `三列之间互锁 不许念出一句和判据相反的话`() {
        for (kind in UndoFailureKind.entries) {
            if (kind.retryInvitation) {
                assertTrue(
                    "${kind}：邀请「再点一次」的档必须先回推条目（restoreEntry = true），" +
                        "否则那句邀请指着一枚栈里已经没有的条目",
                    kind.restoreEntry,
                )
            }
            assertEquals(
                "${kind}：那半句「还留着」只能长在真的回推的档上（restoreEntry = ${kind.restoreEntry}，" +
                    "heldSuffix = 「${kind.heldSuffix}」）—— 两者不一致就是文案与判据分家",
                kind.restoreEntry,
                kind.heldSuffix.isNotEmpty(),
            )
            assertEquals(
                "${kind}：整句里出现「可以再点一次」⇔ retryInvitation（实到 " +
                    kind.heldSuffix.contains("可以再点一次") + "）",
                kind.retryInvitation,
                kind.heldSuffix.contains("可以再点一次"),
            )
        }
    }

    // ─────────────── ④ 文案本体：整句逐字 + 原因是空的那一档 ───────────────

    @Test
    fun `回推成功那支的整句逐字判对 而没回推那档不宣称留着`() {
        for (kind in UndoFailureKind.entries) {
            val copy = kind.copyForRestored("database is locked")
            assertEquals(
                "${kind}：原因必须原样念出来（前缀「撤销失败：」+ 原因 + 那半句），实到「$copy」",
                "撤销失败：database is locked${kind.heldSuffix}",
                copy,
            )
            if (!kind.restoreEntry) {
                assertFalse(
                    "${kind}：这一档压根不回推条目，整句里不许出现「还留着」——" +
                        "出现了就是拿一句谎盖住「这条撤销点不回来了」",
                    copy.contains("还留着"),
                )
            }
        }
    }

    @Test
    fun `异常没带原因时那一句仍然成句 不念出 null 也不念出空`() {
        for (reason in listOf<String?>(null, "", "   ", "\n")) {
            val copy = UndoFailureKind.WriteLockBusy.copyForRestored(reason)
            assertEquals(
                "异常没带 message 时（`SQLException()` 这种就一句没有）不许念出 " +
                    "「撤销失败：；这条…」那种破句，实到「$copy」",
                "撤销失败：数据库没有接受这次撤销；这条撤销还留着，可以再点一次",
                copy,
            )
            assertFalse("不许把 null 念进用户可见那句：$copy", copy.contains("null"))
        }
    }

    // ─────────────── ⑤ 调用点交上来的那条类名链本身 ───────────────

    @Test
    fun `类名链从外层往里取 真实异常端到端判得出档`() {
        assertEquals(
            "单枚异常：链上就是它自己一枚（FQN，不是 simple name —— 表按 FQN 查）",
            listOf("java.io.IOException"),
            exceptionClassNamesOf(IOException("disk full")),
        )
        val wrapped = IllegalStateException("transaction failed", IOException("no space left"))
        assertEquals(
            "外层在前、内层在后：Room 常把真正的原因包一层递上来，榜次反了第 ② 组那判就无从谈起",
            listOf("java.lang.IllegalStateException", "java.io.IOException"),
            exceptionClassNamesOf(wrapped),
        )
        assertEquals(
            "端到端（真造得出的 java 那两档）：IllegalStateException 包着 IOException ⇒ " +
                "取最具体的 RejectedBySnapshot ⇒ 不回推条目（重试同一个结果）",
            UndoFailureKind.RejectedBySnapshot,
            undoFailureKindOf(exceptionClassNamesOf(wrapped)),
        )
        assertEquals(
            "没包东西的 IOException ⇒ StorageUnavailable：留条目，但不许诺再点",
            UndoFailureKind.StorageUnavailable,
            undoFailureKindOf(exceptionClassNamesOf(IOException("disk full"))),
        )
    }

    /**
     * 成环的 `cause` 图不许把提示链变成死循环。
     *
     * `initCause` 拦得住自指（a→a），拦不住两枚互指（a→b→a）—— 那一型在真实代码里是
     * 异常包装写错了的样子。读满 [depth] 那一上限就停，所以这一手**必然**返回。
     */
    @Test
    fun `cause 成环时读到上限就停 不许死循环`() {
        val first = RuntimeException("甲")
        val second = RuntimeException("乙")
        first.initCause(second)
        second.initCause(first)

        val names = exceptionClassNamesOf(first)
        assertEquals(
            "环形的 cause 图（甲→乙→甲…）必须被上限截住；实到 " + names.size + " 枚（应为上限 " + CHAIN_DEPTH + " 枚）：" +
                names,
            CHAIN_DEPTH,
            names.size,
        )
        assertEquals(
            "读到上限为止**一直在走链**（每一枚都是那两个环里的类名 ⇒ 说明它确实进进出出地数到了第八枚才停，" +
                "而不是第一枚就断）：实到 " + names,
            List(CHAIN_DEPTH) { "java.lang.RuntimeException" },
            names,
        )
        assertEquals("外层仍然是第一个", "java.lang.RuntimeException", names.first())
    }

    @Test
    fun `浅链不许被上限误伤 深链只取前八枚`() {
        val shallow = InterruptedIOException()
        assertEquals(
            "一枚没有 cause 的异常 ⇒ 链长 1（上限那道闸门不许把浅链也截成 8 枚）",
            listOf("java.io.InterruptedIOException"),
            exceptionClassNamesOf(shallow),
        )
        var deep: Throwable = RuntimeException("最里")
        repeat(11) { deep = IllegalStateException("外层", deep) }
        val names = exceptionClassNamesOf(deep)
        assertEquals(
            "12 枚深的链只读到前 " + CHAIN_DEPTH + " 枚（实到 " + names.size + " 枚）—— " +
                "再往里的档本卡不认，读全它们只是把提示链拖长",
            CHAIN_DEPTH,
            names.size,
        )
        assertEquals("第一个仍然是最外层", "java.lang.IllegalStateException", names.first())
        assertEquals(
            "判据仍然落得下来（这一族最外层就是 RejectedBySnapshot）",
            UndoFailureKind.RejectedBySnapshot,
            undoFailureKindOf(names),
        )
    }

    // ─────────────── ⑥ 枚举域本身：四档，不许悄悄长第五档 ───────────────

    @Test
    fun `结论域恰好四档`() {
        assertEquals(
            "档数恰好 4（WriteLockBusy / StorageUnavailable / RejectedBySnapshot / Unexplained）：" +
                "长出第五枚 = 新增了一档却没给它写表行、没写两列、没写文案，" +
                "而 undo() 那一步会把它当兜底档念（实到 " + UndoFailureKind.entries.toList() + "）",
            4,
            UndoFailureKind.entries.size,
        )
    }

    // ─────────────── 格子表 ───────────────

    private class NameCell(
        val className: String,
        val expected: UndoFailureKind,
        val why: String,
    )

    private class PriorityCell(
        val chain: List<String>,
        val expected: UndoFailureKind,
        val why: String,
    )

    private companion object {
        /** 表里点名在册、判得出具体档的类名枚数（与内核那张表的行数同值） */
        const val EXPLAINED_CLASS_NAMES = 12

        /** 异常链读取上限，与内核里那一枚同值（改了内核不改这里 ⇒ 第 ⑤ 组当场红） */
        const val CHAIN_DEPTH = 8

        val classNameCells = listOf(
            NameCell(
                "android.database.sqlite.SQLiteBusyException",
                UndoFailureKind.WriteLockBusy,
                "库正忙：同一条链再跑一遍就成了 ⇒ 留条目 + 可以许诺再点",
            ),
            NameCell(
                "android.database.sqlite.SQLiteDatabaseLockedException",
                UndoFailureKind.WriteLockBusy,
                "库被别的写占着 ⇒ 同一档",
            ),
            NameCell(
                "android.database.sqlite.SQLiteFullException",
                UndoFailureKind.StorageUnavailable,
                "磁盘写满：条目要留着，但环境不变再点也是失败 ⇒ 不许诺",
            ),
            NameCell(
                "android.database.sqlite.SQLiteDiskIOException",
                UndoFailureKind.StorageUnavailable,
                "I/O 失败：同一档（都是环境写不下）",
            ),
            NameCell(
                "java.io.IOException",
                UndoFailureKind.StorageUnavailable,
                "底层 I/O 出错：同一档",
            ),
            NameCell(
                "java.lang.OutOfMemoryError",
                UndoFailureKind.StorageUnavailable,
                "堆不够：写不下的是环境，条目那一族行尤其贵 ⇒ 留着但不许诺",
            ),
            NameCell(
                "android.database.sqlite.SQLiteConstraintException",
                UndoFailureKind.RejectedBySnapshot,
                "撞约束：快照本身写不进去，重试是同一个结果 ⇒ 不回推（压在栈顶会钉死整栈）",
            ),
            NameCell(
                "java.lang.IllegalArgumentException",
                UndoFailureKind.RejectedBySnapshot,
                "参数越界：同一档",
            ),
            NameCell(
                "java.lang.IllegalStateException",
                UndoFailureKind.RejectedBySnapshot,
                "状态不对（库已关 / 事务重入）：同一档",
            ),
            NameCell(
                "java.lang.NullPointerException",
                UndoFailureKind.RejectedBySnapshot,
                "我们自己的 bug：再点一百次也是同一个 bug ⇒ 条目不留，栈要让给下面那些",
            ),
            NameCell(
                "java.lang.ClassCastException",
                UndoFailureKind.RejectedBySnapshot,
                "同上：bug 那一档",
            ),
            NameCell(
                "java.lang.IndexOutOfBoundsException",
                UndoFailureKind.RejectedBySnapshot,
                "越界同样是 bug：重试同一个结果 ⇒ 条目不留，栈要让给下面那些",
            ),
            NameCell(
                "android.database.SQLException",
                UndoFailureKind.Unexplained,
                "泛化的 SQL 失败：**不许**读成「锁问题、再点一次」",
            ),
            NameCell(
                "android.database.sqlite.SQLiteException",
                UndoFailureKind.Unexplained,
                "父类通吃会把具体档冲成泛化档 ⇒ 表里不点名的都落兜底档",
            ),
            NameCell(
                "kotlinx.coroutines.CancellationException",
                UndoFailureKind.Unexplained,
                "表外名一律兜底（真机上取消压根走不到这一支：suspendCatching 原样抛出）",
            ),
        )

        val priorityCells = listOf(
            PriorityCell(
                listOf("android.database.SQLException", "android.database.sqlite.SQLiteConstraintException"),
                UndoFailureKind.RejectedBySnapshot,
                "泛化包装 + 具体约束 ⇒ 取约束：不回推（取第一个命中就把谎念给用户了）",
            ),
            PriorityCell(
                listOf("android.database.sqlite.SQLiteBusyException", "java.io.IOException"),
                UndoFailureKind.StorageUnavailable,
                "锁 + 环境写不下 ⇒ 取环境：留条目、不许诺",
            ),
            PriorityCell(
                listOf("java.io.IOException", "android.database.sqlite.SQLiteConstraintException"),
                UndoFailureKind.RejectedBySnapshot,
                "榜上两枚都在 ⇒ 恒取 RejectedBySnapshot",
            ),
            PriorityCell(
                listOf("android.database.SQLException", "android.database.sqlite.SQLiteBusyException"),
                UndoFailureKind.WriteLockBusy,
                "泛化包装 + 锁 ⇒ 取锁：留条目 + 许诺再点",
            ),
            PriorityCell(
                listOf("android.database.SQLException", "android.database.SQLException"),
                UndoFailureKind.Unexplained,
                "链上重复也不改变结论",
            ),
        )
    }
}
