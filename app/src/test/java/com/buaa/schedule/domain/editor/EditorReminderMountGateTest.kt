package com.buaa.schedule.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T391①：`EditorReminderMountGate` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、不读钟、不开文件）。
 *
 * 钉的是在册 #660 主体那一张三档表：两枚事实（这一页开的是不是一门已在库上的课、提醒列表发过第一行了没有）
 * 一共 2² = 4 行，每一行都要落到三档里的一档，而三档里**只有**「编辑已有课且列表还没发过第一行」
 * 那一档欠一帧。内核本体在 `EditorReminderMountGate.kt`，闸门吃它的地方是 `MainActivity.kt` 的
 * `reminderGateOpen` 那一行。
 *
 * 为什么这张表值得单独表驱动（而不是就在闸门那行写两半 `if`）：
 * - 题眼是**两档都放行、但理由不同**：新建那一路压根不写提醒行（`CourseEditorScreen.kt` 保存链外面那层
 *   `if (isEditingExisting)` 是「新建的课没有提醒行」这个常态的第三个出处），列表到没到都不必等它；
 *   编辑已有课那一档放行，是因为递给编辑器的那枚 `initialReminder` 现在读到的是那一行的真值。
 *   把两档折成一行，就是拿「列表到了」去盖「这趟不写库」—— 下一张卡读到折过的那一行时会以为
 *   新建那一路也被就绪位保护着。所以 `editingExistingCourse = false` 的两格必须**分列**
 *   （本文件各钉一格），且各自看得出放行理由是「这一趟压根不写提醒行」而不是「列表到了」。
 * - 「空列表」与「还没读到第一行」是两档，不是一档：库里一行提醒都没有是合法常态（教务导入链对 reminders
 *   表只删不建），拿前者当后者就是把尺读反 —— 那样「什么都没设」那一档会被永久当成「还没到」，
 *   编辑器对没有提醒行的课再也不挂载。这一寸在本文件里的影子是：拦住的那一格，输入的
 *   `reminderRowsListed` 必须是 false，而放行那一头的理由必须分两档写。
 * - 闸门那一枚布尔不许有第二套判据：`allowsMount` 只许是「`decisionOf(...) != WaitReminderRows`」那一手。
 *   本格按 `decisionOf` 的读数给四格分组，组内布尔不许异值、组值不许偏离那一档 —— 谁绕过 `decisionOf`
 *   就地再读一枚输入布尔，这一格当场露。
 *
 * 变异自证的两侧（卡 T391 第 ③ 步）：
 * - 朝宽（W）：闸门那一行摘掉 `&& reminderGateOpen` —— 本文件一字不动，红落在
 *   `ui/RemindersEagerlySharingGuardTest` 与同批四枚按那一行钉的在册守卫。
 * - 朝窄（N）：内核 `decisionOf` 里摘掉「新建那一路」那一档，让新建也吃 `reminderRowsListed` ——
 *   红落在本文件：`editingExistingCourse = false` 那两格分列的格与「放行两档」那一格。
 *
 * 覆盖面边界（如实写，别拿上面的绿当这些的绿）：本文件只钉判据表。Room 第一次返回之前那枚 `StateFlow`
 * 的当前值是不是真的 `emptyList()`、`_reminderRowsListed` 翻真与根组合那一次读谁先谁后、
 * 保存那一趟对已存在的课无条件写回在屏上是否真的落成默认值 —— 三格全在运行期，零枚 JVM 单测切不到，
 * 本卡零设备零 adb。
 */
class EditorReminderMountGateTest {

    private data class Cell(
        val label: String,
        val editingExistingCourse: Boolean,
        val reminderRowsListed: Boolean,
        val expected: ReminderMountDecision,
    )

    /** 四格 = 两枚布尔的全部组合（2²），没有第二把尺参与。 */
    private val table = listOf(
        Cell(
            "新建那一路 + 列表没发过第一行 ⇒ 放行，理由是「这一趟压根不写提醒行」，不是「列表到了」",
            editingExistingCourse = false, reminderRowsListed = false,
            expected = ReminderMountDecision.MountForNewCourse,
        ),
        Cell(
            "新建那一路 + 列表发过第一行 ⇒ 仍是 MountForNewCourse 那一档（不许被折成 MountWithReminderLoaded）",
            editingExistingCourse = false, reminderRowsListed = true,
            expected = ReminderMountDecision.MountForNewCourse,
        ),
        Cell(
            "编辑已有课 + 列表发过第一行 ⇒ 放行，这一档读到的就是那一行的真值",
            editingExistingCourse = true, reminderRowsListed = true,
            expected = ReminderMountDecision.MountWithReminderLoaded,
        ),
        Cell(
            "编辑已有课 + 列表没发过第一行 ⇒ 拦住，这是四格里唯一欠一帧的那一档",
            editingExistingCourse = true, reminderRowsListed = false,
            expected = ReminderMountDecision.WaitReminderRows,
        ),
    )

    /** 三档表逐行一格：四行输入全铺，逐格对期望档。 */
    @Test
    fun `逐格 两枚布尔到三档的全覆盖账`() {
        assertEquals("表必须把 2² 个输入组合数满（缺一行就是有一行没人钉）", 4, table.size)
        for (cell in table) {
            assertEquals(
                cell.label,
                cell.expected,
                EditorReminderMountGate.decisionOf(
                    editingExistingCourse = cell.editingExistingCourse,
                    reminderRowsListed = cell.reminderRowsListed,
                ),
            )
        }
    }

    /**
     * 表第一行单独一格（朝宽那一头的边界）：新建那一路遇上「列表还没发过第一行」照旧挂载。
     * 摘掉内核「新建那一路」那一档 ⇒ 这一格红。
     */
    @Test
    fun `新建那一路 列表没到也放行 因为这一趟压根不写提醒行`() {
        assertEquals(
            buildString {
                append("新建 + 列表没到 ⇒ 必须是 MountForNewCourse。这一档若被折成 WaitReminderRows，")
                append("就是拿一枚闸门去关一扇不写库的门：白帧归零的代价换 0 收益。")
                append("放行理由是「这一趟压根不写提醒行」，与列表到没到无关。")
            },
            ReminderMountDecision.MountForNewCourse,
            EditorReminderMountGate.decisionOf(
                editingExistingCourse = false,
                reminderRowsListed = false,
            ),
        )
        assertTrue(
            "新建 + 列表没到 ⇒ 闸门必须开（这一趟不写提醒行，首帧读到什么都不进用户的库）",
            EditorReminderMountGate.allowsMount(
                editingExistingCourse = false,
                reminderRowsListed = false,
            ),
        )
    }

    /**
     * 表第二行单独一格，与上一格**分列**（内核 KDoc 明写「两档放行、不能折成一行」）：
     * 新建那一路即便列表到了，落的也是 MountForNewCourse 而不是 MountWithReminderLoaded ——
     * 放行理由仍是「这一趟压根不写提醒行」，与「列表到了」不同源。
     */
    @Test
    fun `新建那一路 列表到了 放行理由仍不是列表到了`() {
        assertEquals(
            buildString {
                append("新建 + 列表到了 ⇒ 仍必须是 MountForNewCourse，不许被折成 MountWithReminderLoaded。")
                append("折成一行就是拿同一个理由盖两种事实：下一张卡会以为新建那一路也被就绪位保护着，")
                append("而它压根不写提醒行。")
            },
            ReminderMountDecision.MountForNewCourse,
            EditorReminderMountGate.decisionOf(
                editingExistingCourse = false,
                reminderRowsListed = true,
            ),
        )
        assertFalse(
            "新建那一档不许被读成「列表到了才放行」那一档：两档放行各写各的理由",
            EditorReminderMountGate.decisionOf(
                editingExistingCourse = false,
                reminderRowsListed = true,
            ) == ReminderMountDecision.MountWithReminderLoaded,
        )
        assertTrue(
            "新建 + 列表到了 ⇒ 闸门照旧开（开的原因与上一格同一个：这一趟不写提醒行）",
            EditorReminderMountGate.allowsMount(
                editingExistingCourse = false,
                reminderRowsListed = true,
            ),
        )
    }

    /** 表第三行单独一格：编辑已有课而列表到了，放行且落的是「读到真值」那一档。 */
    @Test
    fun `编辑已有课 列表到了 放行 读的是那一行的真值`() {
        assertEquals(
            "已有课 + 列表到了 ⇒ 必须是 MountWithReminderLoaded（这一档的放行理由才是「列表到了」）",
            ReminderMountDecision.MountWithReminderLoaded,
            EditorReminderMountGate.decisionOf(
                editingExistingCourse = true,
                reminderRowsListed = true,
            ),
        )
        assertTrue(
            "已有课 + 列表到了 ⇒ 闸门必须开，否则编辑器对「已经设过提醒行」的课再也不挂载",
            EditorReminderMountGate.allowsMount(
                editingExistingCourse = true,
                reminderRowsListed = true,
            ),
        )
    }

    /**
     * 表第四行单独一格（朝窄那一头的边界）：编辑已有课而列表还没发过第一行 ⇒ 拦住。
     * 这是四格里唯一欠一帧的一档，也是本卡在册 #660 那笔病唯一的入口。
     */
    @Test
    fun `编辑已有课 列表没到 拦住 这是四格里唯一欠一帧的那一档`() {
        assertEquals(
            buildString {
                append("已有课 + 列表没到 ⇒ 必须是 WaitReminderRows。现在挂上去，首帧的空读数会被两格")
                append(" rememberSaveable 固化成用户设置，再由保存那一趟无条件写回。")
            },
            ReminderMountDecision.WaitReminderRows,
            EditorReminderMountGate.decisionOf(
                editingExistingCourse = true,
                reminderRowsListed = false,
            ),
        )
        assertFalse(
            "已有课 + 列表没到 ⇒ 闸门必须关（关的就是这一扇，别处不许跟着关）",
            EditorReminderMountGate.allowsMount(
                editingExistingCourse = true,
                reminderRowsListed = false,
            ),
        )
    }

    /**
     * `allowsMount` 与 `decisionOf` 同源那一格：闸门那一枚布尔只许是「`decisionOf(...) != WaitReminderRows`」
     * 那一手，不许有第二套判据。切法是把四格输入按 `decisionOf` 的读数分组 —— 同一组里 `allowsMount`
     * 必须同值（异值就是闸门不看档、另有一把尺），且那一组的读数必须恰好等于「这一组不是 WaitReminderRows」。
     * 接线形状那一半不在本文件：`MainActivity.kt` 里 `reminderGateOpen` 那一行由
     * `ui/RemindersEagerlySharingGuardTest` 逐字钉着。
     */
    @Test
    fun `allowsMount 只许是 decisionOf 不等于 WaitReminderRows 那一手`() {
        val rows = table.map { cell ->
            val decision = EditorReminderMountGate.decisionOf(
                editingExistingCourse = cell.editingExistingCourse,
                reminderRowsListed = cell.reminderRowsListed,
            )
            val gate = EditorReminderMountGate.allowsMount(
                editingExistingCourse = cell.editingExistingCourse,
                reminderRowsListed = cell.reminderRowsListed,
            )
            RowOf(cell.label, decision, gate)
        }
        for ((decision, group) in rows.groupBy { it.decision }) {
            val gateValues = group.map { it.gate }.toSet()
            assertEquals(
                buildString {
                    append("同一档的 allowsMount 必须同值（异值 = 闸门另起了一把不看档的尺）：档 ")
                    append(decision.name)
                    append(" 实到读数 ")
                    append(gateValues.joinToString(separator = "/"))
                },
                1,
                gateValues.size,
            )
            assertEquals(
                buildString {
                    append("闸门对档 ")
                    append(decision.name)
                    append(" 的读数必须等于「这一档不是 WaitReminderRows」那一手（不许就地再读一枚输入布尔）")
                },
                decision != ReminderMountDecision.WaitReminderRows,
                group.first().gate,
            )
        }
        for (row in rows) {
            assertEquals(
                row.label,
                row.decision != ReminderMountDecision.WaitReminderRows,
                row.gate,
            )
        }
    }

    /** 放行两档、拦住一档：把两档放行的理由折成一行，这一格先断（只剩一枚放行档）。 */
    @Test
    fun `放行的档恰好两枚 拦住的档恰好一枚`() {
        val decisions = table.map { cell ->
            EditorReminderMountGate.decisionOf(
                editingExistingCourse = cell.editingExistingCourse,
                reminderRowsListed = cell.reminderRowsListed,
            )
        }
        val gates = table.map { cell ->
            EditorReminderMountGate.allowsMount(
                editingExistingCourse = cell.editingExistingCourse,
                reminderRowsListed = cell.reminderRowsListed,
            )
        }
        val blockedCells = table.filterIndexed { index, _ -> !gates[index] }
        assertEquals(
            buildString {
                append("四格里拦住的那头必须恰好一格（多一格就是新建那一路被顺带关掉了）：实到 ")
                append(blockedCells.size)
            },
            1,
            blockedCells.size,
        )
        assertEquals(
            buildString {
                append("拦住那一格必须是「编辑已有课 + 列表没到」那一档：就绪位只能由上游那一次发射翻真，")
                append("拿列表长度当就绪就是本卡明禁的那一手")
            },
            listOf(ReminderMountDecision.WaitReminderRows),
            blockedCells.map { it.expected },
        )
        val mountedDecisions = decisions.filterIndexed { index, _ -> gates[index] }
        assertEquals(
            buildString {
                append("放行那一头必须恰好三格：实到 ")
                append(mountedDecisions.size)
            },
            3,
            mountedDecisions.size,
        )
        assertEquals(
            buildString {
                append("放行那一头必须是两枚不同的档（内核 KDoc：两档放行、不能折成一行）：实到 ")
                append(mountedDecisions.toSet().joinToString(separator = "/"))
            },
            setOf(
                ReminderMountDecision.MountForNewCourse,
                ReminderMountDecision.MountWithReminderLoaded,
            ),
            mountedDecisions.toSet(),
        )
    }

    /**
     * 死档检查（本仓口径）：四格输入铺出来的档必须等于全枚举，且枚举枚数恰好三枚 ——
     * 两头一起数，才既是「全覆盖」又是「零死档」。
     */
    @Test
    fun `三档全可达且没有一档没人认领`() {
        val reached = table.map { cell ->
            EditorReminderMountGate.decisionOf(
                editingExistingCourse = cell.editingExistingCourse,
                reminderRowsListed = cell.reminderRowsListed,
            )
        }.toSet()
        assertEquals(
            buildString {
                append("表的期望档必须铺满全枚举（少一枚就是有一档在 2² 输入里到不了 = 死档）：实到 ")
                append(reached.joinToString(separator = "/"))
                append(" ／ 全枚举 ")
                append(ReminderMountDecision.entries.joinToString(separator = "/"))
            },
            ReminderMountDecision.entries.toSet(),
            reached,
        )
        assertEquals(
            buildString {
                append("三档表就三枚档，第四枚档不许悄悄长出来：实到 ")
                append(ReminderMountDecision.entries.size)
            },
            3,
            ReminderMountDecision.entries.size,
        )
    }

    private data class RowOf(
        val label: String,
        val decision: ReminderMountDecision,
        val gate: Boolean,
    )
}
