package com.buaa.schedule.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T278②：把「撤销链重插那一手该把哪一枚主键交给 DAO」钉成表驱动单测（纯 JVM，#520 F-06）。
 *
 * 判据本体是 [undoWritePrimaryKey]（`UndoReinsertIdentity.kt`），完整账 —— 含第⓪问甲/乙/丙三解的
 * 取舍、以及「为什么不能把 `id = 0L` 写进 `CourseConstraints.normalize` 本体」那四枚连带伤害 ——
 * 写在那枚文件的段首。本文件只管逐格取值。
 *
 * ## 这张表量四件事
 *
 * 1. **重插那一族交出去的必须是库里任何一行都拿不到的号**（[FRESH_ROW_PRIMARY_KEY] = 0L，
 *    `CourseEntity` 的 `@PrimaryKey(autoGenerate = true)` 认它 = 「别用显式主键」⇒ Room 另号）——
 *    带着快照那一枚旧 id 去 `INSERT OR REPLACE` 就是本卡治的那道病（见第 ③ 层那格占用对撞）；
 * 2. **原地回写那一族必须原样带回旧 id**：那一族走 `courseDao.update`，`@Update` 按主键匹配，
 *    主键被顺手抹平 ⇒ 撤销「原地那一档」一个字也不写（比现状更坏，且静默）；
 * 3. **唯一的尺是那一枚档位旗**：快照 id 的大小、正负、是否已经等于哨兵，都不许参与判定
 *    （[第 ⑤ 层] 与 [第 ⑥ 层] 各钉一侧）；
 * 4. **结论域只有那两档**：[Handed] 是密封档位，第三档 [Handed.OffTier] 在整张表的任何一格出现都算红
 *    （卡面红线：判据不许折成 Boolean —— 这里交出去的是 DAO 直接吃的那枚主键，档位由它往回升出来，
 *    而不是把两档压成「抹了 / 没抹」一枚旗）。
 *
 * ## 两侧都有格子（这张表自己不许被改窄）
 *
 * **朝宽扭**一次（把重插那一档改成 `else -> snapshotId`，也就是本卡之前的盘面；或去 `normalize`
 * 里抹主键、让这一族拿不到值）⇒ 表 ① 的 `landsOnSnapshotRow = false` 那一半与第 ③ 层整层红；
 * **朝窄扭**一次（把 `if (landsOnSnapshotRow)` 的两侧对调，或把抹平扩成两档一律抹）⇒ 表 ① 里
 * 「原地回写」那一半与第 ④ 层红。两型都恰好是下一个读这段代码的人最容易顺手做出的改动。
 * 边界另有六类（哨兵本身 / 哨兵上侧一枚 / 哨兵下侧一枚 / 中段 / 号顶 / 号底），**每类两档各一枚格子**，
 * 由第 ② 层那格按类名机器复核（不许靠人肉眼数表）。
 *
 * ## 档位（如实写明）
 *
 * `:app` 的 JVM 测试面只有 junit，没有 Robolectric、也没有能在 JVM 上跑 Room 的缝 ⇒
 * 「Room 拿 0L 到底另不另号」这一手不在本表的钉范围内（它由 `CourseEntity` 的实体声明 +
 * 同文件两处 `copy(id = 0L)` 先例供证，并被 `UndoReinsertIdentityGuardTest` 按源码形状钉住）；
 * 本表钉的是**判据交出哪一枚主键、以及那一枚会不会撞上库里已有的行**。
 * 零 adb、零设备、零时钟：真重导一次学期再点撤销看那一格回没回，本卡没做。
 */
class UndoReinsertIdentityTest {

    // ─────────────── 档位（结论域只有两档，第三档 = 红）───────────────

    /**
     * 把「交给 DAO 的那枚主键」升回档位：不是 Boolean，而是 DAO 会怎么落库的三档结论。
     *
     * [FreshRow] 与 [SnapshotRow] 是判据仅有的两枚合法结论；[OffTier] 是哨兵与快照之外的任何值
     * （例如 `snapshotId + 1` 那种「自己造一枚号」的写法）—— 它一旦出现，本卡的红线就断了。
     *
     * 取值口径写在明处：`snapshotId == FRESH_ROW_PRIMARY_KEY` 那一格里两档**同值**（都交 0L），
     * 分类器按 DAO 真正吃到的那枚值读成 [FreshRow]；那一格的分量在**值断言**上（第 ① 层逐格钉值），
     * 不在档位名上。这也是这张表不许改成「抹了没抹」一枚旗的理由：两档在同一条输入上会重合。
     */
    private sealed interface Handed {
        /** 交出 0L ⇒ Room 走 `@PrimaryKey(autoGenerate = true)` 另号，谁也不碰 */
        data object FreshRow : Handed

        /** 交出快照那一枚 id 原样 ⇒ `@Update` 按主键命中它自己那一行 */
        data class SnapshotRow(val id: Long) : Handed

        /** 第三档：既不是哨兵也不是快照的号（绝不该出现，出现即红） */
        data class OffTier(val id: Long) : Handed
    }

    private fun tierOf(snapshotId: Long, handed: Long): Handed = when {
        snapshotId != FRESH_ROW_PRIMARY_KEY && handed == snapshotId -> Handed.SnapshotRow(handed)
        handed == FRESH_ROW_PRIMARY_KEY -> Handed.FreshRow
        else -> Handed.OffTier(handed)
    }

    // ─────────────── ① 主表：15 格逐格钉值 + 档位 ───────────────

    @Test
    fun `主表十五格交出的主键与档位逐格钉住`() {
        for (cell in cells) {
            val handed = undoWritePrimaryKey(cell.snapshotId, cell.landsOnSnapshotRow)
            assertEquals(
                "判据格子「${cell.name}」（${cell.reading}）：${cell.why}\n" +
                    "  交出的主键实到 $handed / 应为 ${cell.expected}\n" +
                    "复算：grep -n \"internal fun undoWritePrimaryKey\" " +
                    "app/src/main/java/com/buaa/schedule/data/repository/UndoReinsertIdentity.kt",
                cell.expected,
                handed,
            )
            assertEquals(
                "同一格的**档位**（值对了不等于档位对：第三档也能碰巧等于哨兵）：" +
                    "实到 " + tierOf(cell.snapshotId, handed) + " / 应为 ${cell.expectTier}",
                cell.expectTier,
                tierOf(cell.snapshotId, handed),
            )
        }
    }

    // ─────────────── ② 这张表自己：两档 × 六类边界两侧各有格子 ───────────────

    /**
     * 表驱动的表驱动：第 ① 层吃的是这张表的格子，本层吃的是**格子的覆盖**。
     * 有人删一格、或把某一类边界的格子并成一格，第 ① 层照样绿，本层红。
     */
    @Test
    fun `主表自己覆盖两档与六类边界的两侧`() {
        val bothTiers = cells.map { it.landsOnSnapshotRow }.distinct().sorted()
        assertEquals(
            "主表必须两档都有格子（实到档位旗：" + bothTiers + "）—— 少一枚档位旗 = 那一族的格子整段消失",
            listOf(false, true),
            bothTiers,
        )
        for (boundary in Boundary.entries) {
            val onEachSide = cells.filter { it.boundary == boundary }
                .map { it.landsOnSnapshotRow }.distinct().sorted()
            assertEquals(
                "边界类「${boundary.label}」必须在**两档**上各有格子（实到 " + onEachSide +
                    "）：卡面要求「每一枚边界两侧各一枚格子」，只在重插那一族有格子就等于没钉住原地那一族",
                listOf(false, true),
                onEachSide,
            )
        }
        val duplicated = cells.groupBy { it.snapshotId to it.landsOnSnapshotRow }
            .filterValues { it.size > 1 }.keys.toList()
        assertEquals(
            "同一枚 (snapshotId, landsOnSnapshotRow) 只许一格（实到重复：" + duplicated +
                "）：拿重复格子凑数会让「枚数」这一格看着够而覆盖其实没涨",
            emptyList<Pair<Long, Boolean>>(),
            duplicated,
        )
        assertEquals(
            "主表枚数现值 15（实到 ${cells.size}）：10 枚边界格（哨兵本身 / 上侧一枚 / 下侧一枚 / 号顶 / 号底 " +
                "这五类 × 两档）+ 5 枚点名到撤销链具体那一支的语义格（Delete / RestoreAsNewRow / " +
                "removed 片段 / RestoreInPlace / 兄弟行，都落在中段那一类上 ⇒ MID 那类不再另配边界格）。" +
                "这一格按**枚数**钉，删一格就红，不管删的是哪一格",
            15,
            cells.size,
        )
    }

    // ─────────────── ③ 本卡的靶子：旧号被一门无关的课占着 ───────────────

    /**
     * 卡面点名要有的那一格：撤销重插时，快照那一枚 id **此刻正被一门无关的课持有**。
     *
     * 成因（逐字见 `UndoReinsertIdentity.kt` 段首）：教务整学期重导是 `deleteBySemester` 之后
     * `insertAll` ⇒ 号全换。判据交出 0L 时，`INSERT OR REPLACE` 撞不到任何既有行；
     * 交出旧 id 时，SQLite 先**删掉占号那一行**再让快照顶号进来，而 `reminders` 的主键就是
     * `courseId` ⇒ 那门课的提醒行原样归给冒进来的那一门（铃按别人的点响）。
     */
    @Test
    fun `旧号被无关课程占着时重插交的主键不在任何一门课手里`() {
        val occupied = library.map { it.id }.toSet()
        val handedByFamily = library.map { it.id to undoWritePrimaryKey(it.id, landsOnSnapshotRow = false) }
        val collided = handedByFamily.filter { (_, handed) -> handed in occupied }
        assertEquals(
            "重插那一族对**库里每一枚在册的号**交出的主键，都不许落在在册号集合里（实到撞号：" +
                collided + "／交出的号：" + handedByFamily + "／在册号：" + occupied +
                "）：撞上一枚 = INSERT OR REPLACE 先把持有那一枚的课整行删掉再顶替它",
            emptyList<Pair<Long, Long>>(),
            collided,
        )
        // 靶子那一格单独念一遍：12L 此刻的主人是「概率统计」，不是这次撤销要复活的「数据结构」。
        val impostor = library.first { it.id == OCCUPIED_SNAPSHOT_ID }
        val handed = undoWritePrimaryKey(OCCUPIED_SNAPSHOT_ID, landsOnSnapshotRow = false)
        assertEquals(
            "靶子格：快照 id ${OCCUPIED_SNAPSHOT_ID}L 现在被「${impostor.courseKey}」占着，" +
                "而这次撤销复活的是「${SNAPSHOT_COURSE_KEY}」⇒ 交出的主键必须是哨兵 $FRESH_ROW_PRIMARY_KEY" +
                "（实到 $handed / 档位 " + tierOf(OCCUPIED_SNAPSHOT_ID, handed) + "）",
            FRESH_ROW_PRIMARY_KEY,
            handed,
        )
        assertTrue(
            "这一格不是空转：占号那门课的 id 与快照的 id 必须真的相等（实到 " +
                "${impostor.id} == $OCCUPIED_SNAPSHOT_ID），否则「撞号」那一格永远红不起来",
            impostor.id == OCCUPIED_SNAPSHOT_ID,
        )
        assertNotEquals(
            "本卡之前的盘面交的就是快照那一枚旧号（$OCCUPIED_SNAPSHOT_ID），它**正好**在在册号集合里 ⇒ " +
                "改前那一手确实顶号。今天它必须与快照 id 不同",
            OCCUPIED_SNAPSHOT_ID,
            handed,
        )
    }

    /**
     * 第 ③ 层的自检：改前那一形（直接交 `before.id`）在这张占用表上**必须**被判成撞号。
     * 少了这一格，第 ③ 层就只是「今天恰好绿」的一条断言，说不出探测器认不认得病灶。
     */
    @Test
    fun `探测器认得改前那一形 交旧号就是撞号`() {
        val occupiedIds = library.map { it.id }.toSet()
        // 改前那一形：把 normalize 原样带回的快照 id 直接交给 INSERT OR REPLACE
        val legacyCollisions = library.filter { it.id in occupiedIds }
        assertEquals(
            "把「原样交回快照 id」那一形喂给同一把尺，必须**每一枚**都撞号（实到 " +
                "${legacyCollisions.size} / 应 5）：0 枚就说明第 ③ 层那句「不许落在在册号集合里」" +
                "是假的绿（表里根本没有号，或尺根本没在比）",
            5,
            legacyCollisions.size,
        )
        val todayCollisions = library
            .map { it.id to undoWritePrimaryKey(it.id, landsOnSnapshotRow = false) }
            .filter { (_, handed) -> handed in occupiedIds }
        assertEquals(
            "同一把尺喂今天那一形 ⇒ 撞号必须归零（实到 " + todayCollisions + " 枚）：" +
                "这一格与上一格是同一条判据的两侧 —— 一侧证明探测器认得病灶，一侧证明今天没有病灶",
            0,
            todayCollisions.size,
        )
    }

    // ─────────────── ④ 反向格子：原地回写那一族不许被顺手抹平 ───────────────

    /**
     * `RestoreInPlace` 与兄弟行回写那一族走的是 `courseDao.update`，`@Update` 按主键匹配。
     * 主键一抹平 ⇒ UPDATE 匹配不到任何行 ⇒ **撤销那一次什么都没写、屏上照样念「已撤销」**
     * （比本卡治的那道病更安静）。所以修法只准落在重插那一手，`normalize` 本体也不许加 `id = 0L`
     * （连带伤害四枚，逐枚写在 `UndoReinsertIdentity.kt` 段首，并由守卫第 ③ 层按形状钉住）。
     */
    @Test
    fun `原地回写那一族的主键必须原样带回`() {
        for (snapshotId in listOf(1L, 7L, 12L, 34L, 99L, Long.MAX_VALUE)) {
            val handed = undoWritePrimaryKey(snapshotId, landsOnSnapshotRow = true)
            assertEquals(
                "按原 id 落回自己那一行时，交出的主键必须就是那一枚 id（snapshotId=$snapshotId 实到 " +
                    "$handed）：@Update 拿主键当唯一身份，抹了一个字也写不进去",
                snapshotId,
                handed,
            )
            assertEquals(
                "同一格的档位实到 " + tierOf(snapshotId, handed) + " / 应为 " + Handed.SnapshotRow(snapshotId),
                Handed.SnapshotRow(snapshotId),
                tierOf(snapshotId, handed),
            )
        }
        assertNotEquals(
            "原地那一族绝不能交出哨兵 $FRESH_ROW_PRIMARY_KEY（那等于把「按 id 原地回写」改成「另插一行」" +
                "⇒ 屏幕上多出一张重复卡，而那一行原本还是这次编辑留下的）",
            FRESH_ROW_PRIMARY_KEY,
            undoWritePrimaryKey(12L, landsOnSnapshotRow = true),
        )
    }

    // ─────────────── ⑤ 那一枚档位旗是唯一参与判定的输入 ───────────────

    @Test
    fun `唯一的尺是那枚档位旗 id 的大小正负都不参与判定`() {
        for (snapshotId in listOf(0L, 1L, -1L, 12L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(
                "snapshotId=$snapshotId 时重插那一档必须交出 $FRESH_ROW_PRIMARY_KEY（实到 " +
                    undoWritePrimaryKey(snapshotId, landsOnSnapshotRow = false) +
                    "）：id 的大小与符号都不该影响判定",
                FRESH_ROW_PRIMARY_KEY,
                undoWritePrimaryKey(snapshotId, landsOnSnapshotRow = false),
            )
            assertEquals(
                "snapshotId=$snapshotId 时原地那一档必须原样带回（实到 " +
                    undoWritePrimaryKey(snapshotId, landsOnSnapshotRow = true) + "）",
                snapshotId,
                undoWritePrimaryKey(snapshotId, landsOnSnapshotRow = true),
            )
        }
        val distinctFresh = listOf(0L, 1L, -1L, 12L, 99L, Long.MIN_VALUE, Long.MAX_VALUE)
            .map { undoWritePrimaryKey(it, landsOnSnapshotRow = false) }.distinct()
        assertEquals(
            "重插那一族交出的号**只有一种**（实到 " + distinctFresh + "）：交出好几种就说明 id 的值真的参与了判定",
            listOf(FRESH_ROW_PRIMARY_KEY),
            distinctFresh,
        )
    }

    // ─────────────── ⑥ 哨兵那一格：id 已经是新行身份时两档同值 ───────────────

    /**
     * 快照 id 本来就是 [FRESH_ROW_PRIMARY_KEY]（编辑器新建那一路的身份）：两档都交 0L。
     * 这一格钉住「本内核不许把『这一枚 id 是不是哨兵』翻成第三种处置」——
     * 「要不要按 id 写这一行」是调用点闸门的事（`MainRowUndoPolicy` 那一族），不是这里的第二把尺。
     */
    @Test
    fun `快照 id 本就是哨兵时两档同值 不生出第三档`() {
        val asNewRow = undoWritePrimaryKey(FRESH_ROW_PRIMARY_KEY, landsOnSnapshotRow = false)
        val inPlace = undoWritePrimaryKey(FRESH_ROW_PRIMARY_KEY, landsOnSnapshotRow = true)
        assertEquals(
            "snapshotId=0L 时两档都得交 0L（实到 重插=$asNewRow / 原地=$inPlace）：" +
                "这里两档**同值**，所以更要把值钉住 —— 顺手加一句 `if (snapshotId == 0L) ...` 就是第二把尺",
            listOf(FRESH_ROW_PRIMARY_KEY, FRESH_ROW_PRIMARY_KEY),
            listOf(asNewRow, inPlace),
        )
    }

    // ─────────────── ⑦ 结论域只有两档 ───────────────

    @Test
    fun `结论域只有两档 任何一格都不许落到第三档上`() {
        val offenders = mutableListOf<Pair<Long, Long>>()
        for (snapshotId in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 7L, 12L, 34L, 99L, Long.MAX_VALUE)) {
            for (lands in listOf(false, true)) {
                val handed = undoWritePrimaryKey(snapshotId, lands)
                if (tierOf(snapshotId, handed) is Handed.OffTier) offenders += snapshotId to handed
            }
        }
        assertEquals(
            "18 枚组合（9 个 id × 2 档）交出的主键只能落在那两档里（实到第三档：" + offenders +
                "）：出现「自己造一枚号」那种写法（例如 snapshotId + 1）就是第三档，" +
                "而它恰好是 INSERT OR REPLACE 顶号的另一种形状",
            emptyList<Pair<Long, Long>>(),
            offenders,
        )
    }

    // ─────────────── ⑧ 纯：同一输入给同一值（没读钟、没读 DAO、没读设备）───────────────

    @Test
    fun `纯函数 同一枚输入连给十次都是同一个值`() {
        val inPlace = (1..10).map { undoWritePrimaryKey(12L, landsOnSnapshotRow = true) }.distinct()
        assertTrue(
            "本内核是判据不是取数：同一枚输入十次调用必须同值（12L / 原地那一档实到 " + inPlace + "）",
            listOf(12L) == inPlace,
        )
        val fresh = (1..10).map { undoWritePrimaryKey(12L, landsOnSnapshotRow = false) }.distinct()
        assertTrue(
            "重插那一档同理（实到 $fresh）：读钟 / 读权限 / 读 Build.VERSION / 读 DAO 都会在这里露出来",
            listOf(FRESH_ROW_PRIMARY_KEY) == fresh,
        )
    }

    // ─────────────── 格子表 ───────────────

    /** 边界类：第 ② 层按类名复核「每一类两侧各有格子」，不许靠肉眼数表。 */
    private enum class Boundary(val label: String) {
        /** 哨兵本身（两档在此同值，最容易被顺手特判的一格） */
        SENTINEL("哨兵 0L 本身"),

        /** 哨兵上侧一枚：库里最小的真实号 */
        JUST_ABOVE("哨兵上侧一枚（1L）"),

        /** 哨兵下侧一枚：Room 造不出、但类型允许的负号 */
        JUST_BELOW("哨兵下侧一枚（-1L）"),

        /** 中段：真实使用的那一档 */
        MID("中段（12L / 34L 那种在册号）"),

        /** 号顶 */
        TOP("号顶 Long.MAX_VALUE"),

        /** 号底 */
        BOTTOM("号底 Long.MIN_VALUE"),
        ;
    }

    private data class Cell(
        val name: String,
        val snapshotId: Long,
        val landsOnSnapshotRow: Boolean,
        val boundary: Boundary,
        val expected: Long,
        val expectTier: Handed,
        val why: String,
        val reading: String,
    )

    private companion object {
        const val OCCUPIED_SNAPSHOT_ID = 12L
        const val SNAPSHOT_COURSE_KEY = "数据结构(这次撤销要复活的那一门)"

        /** 教务整学期重导之后的盘面：号全换过，12L 此刻的主人是一门**无关的课**。 */
        val library = listOf(
            LibraryRow(1L, "高等数学|1|1-2"),
            LibraryRow(7L, "线性代数|3|1-2"),
            LibraryRow(OCCUPIED_SNAPSHOT_ID, "概率统计|5|3-4"),
            LibraryRow(99L, "体育|2|1-2"),
            LibraryRow(Long.MAX_VALUE, "毛概|4|1-2"),
        )

        val cells = listOf(
            // ── 语义格：三支重插 + 两支原地，逐支点名到撤销链上那一支 ──
            Cell(
                name = "撤销一门被删的课（UndoManager 的 Delete 那一支：撤销 = 以新 id 重新入库）",
                snapshotId = 99L,
                landsOnSnapshotRow = false,
                boundary = Boundary.MID,
                expected = FRESH_ROW_PRIMARY_KEY,
                expectTier = Handed.FreshRow,
                why = "落进 insertWithReminders 的那一手，带 99L 去 INSERT OR REPLACE 就是把正持有 99 的" +
                    "那门课整行删掉顶替；抹平之后 Room 走 autoGenerate 另号",
                reading = "snapshotId=99 / landsOnSnapshotRow=false",
            ),
            Cell(
                name = "整组换外观之后撤销主行、那一 id 上已是别的课（MainRowUndoDisposition.RestoreAsNewRow）",
                snapshotId = 12L,
                landsOnSnapshotRow = false,
                boundary = Boundary.MID,
                expected = FRESH_ROW_PRIMARY_KEY,
                expectTier = Handed.FreshRow,
                why = "内核段首那句「绝不原地覆盖」的执行面：结论域判的是换号，写侧就得真的换号，" +
                    "否则 RestoreAsNewRow 与 RestoreInPlace 在库上是同一只手",
                reading = "snapshotId=12（被无关课程占着）/ landsOnSnapshotRow=false",
            ),
            Cell(
                name = "撤销「编辑课程」时补回被这次编辑顺手清掉的片段（undoUpdate 第 4 步 removed）",
                snapshotId = 34L,
                landsOnSnapshotRow = false,
                boundary = Boundary.MID,
                expected = FRESH_ROW_PRIMARY_KEY,
                expectTier = Handed.FreshRow,
                why = "同一枚口子：补回的片段是新行，旧号可能已被整学期重导派给别人（在册 F-07 说的" +
                    "重复撤销是另一账，本卡不收）",
                reading = "snapshotId=34 / landsOnSnapshotRow=false",
            ),
            Cell(
                name = "最常见的那一次撤销：那一行还是这次编辑留下的（RestoreInPlace，走 courseDao.update）",
                snapshotId = 12L,
                landsOnSnapshotRow = true,
                boundary = Boundary.MID,
                expected = 12L,
                expectTier = Handed.SnapshotRow(12L),
                why = "这一族主键就是它的全部身份：抹平 ⇒ @Update 匹配不到行 ⇒ 撤销静默 no-op，" +
                    "所以修法只准落在重插那一手",
                reading = "snapshotId=12 / landsOnSnapshotRow=true",
            ),
            Cell(
                name = "组写改掉的兄弟行按 id 回写改前版（GroupRowUndoDisposition.Restore 那一族）",
                snapshotId = 7L,
                landsOnSnapshotRow = true,
                boundary = Boundary.MID,
                expected = 7L,
                expectTier = Handed.SnapshotRow(7L),
                why = "兄弟行那一族今天也走 update(snapshot.toEntity())，同一格判据共用一次取法：" +
                    "这一族一律不许被顺手抹平（normalize 本体加 id = 0L 会连它一起改坏）",
                reading = "snapshotId=7 / landsOnSnapshotRow=true",
            ),
            // ── 边界格：六类 × 两档，两侧都在 ──
            Cell(
                name = "边界格 哨兵本身 / 重插那一档（编辑器新建那一路的身份）",
                snapshotId = 0L,
                landsOnSnapshotRow = false,
                boundary = Boundary.SENTINEL,
                expected = FRESH_ROW_PRIMARY_KEY,
                expectTier = Handed.FreshRow,
                why = "与第一格同一结论，而且这里交出 0L 不是「顺手」：新行身份就是 0L",
                reading = "snapshotId=0 / landsOnSnapshotRow=false",
            ),
            Cell(
                name = "边界格 哨兵本身 / 原地那一档（边界另一侧，两档在此同值）",
                snapshotId = 0L,
                landsOnSnapshotRow = true,
                boundary = Boundary.SENTINEL,
                expected = 0L,
                expectTier = Handed.FreshRow,
                why = "id 是不是哨兵不参与判定 ⇒ 两档同值；「这一枚 id 要不要按 id 写」是调用点闸门的事，" +
                    "在这里加第二遍判断就是给同一格长出第二把尺",
                reading = "snapshotId=0 / landsOnSnapshotRow=true",
            ),
            Cell(
                name = "边界格 哨兵上侧一枚（1L = 库里最小的真实号）/ 重插",
                snapshotId = 1L,
                landsOnSnapshotRow = false,
                boundary = Boundary.JUST_ABOVE,
                expected = FRESH_ROW_PRIMARY_KEY,
                expectTier = Handed.FreshRow,
                why = "哨兵与「最小真实号」之间那一枚台阶必须翻档：把判据写成 `snapshotId <= 1` 之类的" +
                    "比较，红的就是这一格",
                reading = "snapshotId=1 / landsOnSnapshotRow=false",
            ),
            Cell(
                name = "边界格 哨兵上侧一枚（1L）/ 原地",
                snapshotId = 1L,
                landsOnSnapshotRow = true,
                boundary = Boundary.JUST_ABOVE,
                expected = 1L,
                expectTier = Handed.SnapshotRow(1L),
                why = "1L 是一枚**真实在册号**，不是哨兵：把它当哨兵抹平 ⇒ @Update 一个字节也写不进",
                reading = "snapshotId=1 / landsOnSnapshotRow=true",
            ),
            Cell(
                name = "边界格 哨兵下侧一枚（-1L）/ 重插",
                snapshotId = -1L,
                landsOnSnapshotRow = false,
                boundary = Boundary.JUST_BELOW,
                expected = FRESH_ROW_PRIMARY_KEY,
                expectTier = Handed.FreshRow,
                why = "Room 造不出负号，但类型允许 ⇒ 判据只认档位旗，负数也得走同一结论",
                reading = "snapshotId=-1 / landsOnSnapshotRow=false",
            ),
            Cell(
                name = "边界格 哨兵下侧一枚（-1L）/ 原地",
                snapshotId = -1L,
                landsOnSnapshotRow = true,
                boundary = Boundary.JUST_BELOW,
                expected = -1L,
                expectTier = Handed.SnapshotRow(-1L),
                why = "同上一格另一侧：不许在这里做「看着不像合法号就抹平」那种好心特判",
                reading = "snapshotId=-1 / landsOnSnapshotRow=true",
            ),
            Cell(
                name = "边界格 号顶 Long.MAX_VALUE / 重插",
                snapshotId = Long.MAX_VALUE,
                landsOnSnapshotRow = false,
                boundary = Boundary.TOP,
                expected = FRESH_ROW_PRIMARY_KEY,
                expectTier = Handed.FreshRow,
                why = "号顶那一枚最容易被「先加一再插」那类写法翻车（溢出成负号 ⇒ 第三档，第 ⑦ 层也拦）",
                reading = "snapshotId=MAX / landsOnSnapshotRow=false",
            ),
            Cell(
                name = "边界格 号顶 Long.MAX_VALUE / 原地",
                snapshotId = Long.MAX_VALUE,
                landsOnSnapshotRow = true,
                boundary = Boundary.TOP,
                expected = Long.MAX_VALUE,
                expectTier = Handed.SnapshotRow(Long.MAX_VALUE),
                why = "不许在这里做任何算术",
                reading = "snapshotId=MAX / landsOnSnapshotRow=true",
            ),
            Cell(
                name = "边界格 号底 Long.MIN_VALUE / 重插",
                snapshotId = Long.MIN_VALUE,
                landsOnSnapshotRow = false,
                boundary = Boundary.BOTTOM,
                expected = FRESH_ROW_PRIMARY_KEY,
                expectTier = Handed.FreshRow,
                why = "符号位最极端的那一枚：判据不许因为「它是负数」就走另一支",
                reading = "snapshotId=MIN / landsOnSnapshotRow=false",
            ),
            Cell(
                name = "边界格 号底 Long.MIN_VALUE / 原地",
                snapshotId = Long.MIN_VALUE,
                landsOnSnapshotRow = true,
                boundary = Boundary.BOTTOM,
                expected = Long.MIN_VALUE,
                expectTier = Handed.SnapshotRow(Long.MIN_VALUE),
                why = "同上一格另一侧",
                reading = "snapshotId=MIN / landsOnSnapshotRow=true",
            ),
        )
    }

    /** 库里的行（只用来表达「那一枚号此刻有主人」这一事实，不模拟 Room 的落库行为） */
    private data class LibraryRow(val id: Long, val courseKey: String)
}
