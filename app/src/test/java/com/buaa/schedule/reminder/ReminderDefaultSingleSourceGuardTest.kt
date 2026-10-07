package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.ReminderSetting
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T214 病灶 B（#264）的**枚数册子**守卫：课前提醒那两枚默认值（提前几分钟 / 没有行时开不开）
 * 在全仓只许有一处字面量，其余每一处都必须引用它。纯 JVM 源码核对（无 Robolectric、无设备、不读时钟）。
 *
 * ## 这枚守卫为什么存在（本仓最贵的一种假绿）
 *
 * `domain/model/ReminderSetting.kt`、`data/local/ReminderEntity.kt`、`ui/editor/CourseEditorScreen.kt`
 * 与测试里的 `ReminderSettingResolutionTest`、`EditorReminderSectionSummaryTest` **五处原文**都在念
 * 「枚数册子由 `reminder/ReminderDefaultSingleSourceGuardTest` 钉住」——而 T214 崩卡落到盘上时
 * 这枚文件根本不存在（`find app/src/test -name 'ReminderDefaultSingleSource*'` = 0）。
 * 后果不是少一个用例，是**那五句话全在替一枚不存在的钉子作证**：谁把 `DEFAULT_ADVANCE_MINUTES`
 * 改成 5、再在别处补一枚裸 `10`，五句里没有一句会红。本文件补上那枚钉子，并在第 ④ 层把
 * 「点名者必在册」本身也钉住（点名在册、被点名者不在盘上 ⇒ 红）。
 *
 * ## 钉法四家逐格点名
 *
 * 1. **数出现次数（册子）** —— 第 ① 层：`const val DEFAULT_ADVANCE_MINUTES = 10` 与
 *    `const val DEFAULT_ENABLED = true` 在全 `app/src/main` **各恰一处**，且落点册子只有
 *    `ReminderSetting.kt`；第 ② 层按行扫全树数「绑在 `advanceMinutes` 上的裸字面量」；
 *    第 ③ 层数的是**消费者册子**（谁在引那两枚常量，多一处少一处都红）。
 * 2. **逐字节** —— 第 ① 层那两枚声明、第 ②b 层那两枚载体默认值行
 *    （`val enabled: Boolean = ReminderSetting.DEFAULT_ENABLED,`）、第 ④ 层那五处点名的类名本身。
 * 3. **读形状** —— 第 ②b 层只许提醒那一族的**载体文件**里的 `enabled` 默认值走常量；
 *    `enabled: Boolean = true` 在 `core/designsystem/` 那四枚 Composable 上是**另一枚符号**
 *    （控件的可交互旗标），所以那一把尺按载体册子取，不做全树裸扫（全树扫会在既有无关代码上假红）。
 * 4. **抹注释找锚点** —— 第 ② 层两侧一起钉：`reminder/ReminderScheduler.kt` 那段「改前是
 *    `setting?.advanceMinutes ?: 10`」的历史说明**含注释**在原文里数得到、**抹注释后** 0 处；
 *    只数原文会把散文当证据，只数抹注释后的会放过「把裸字面量搬回代码」那一手。
 *
 * ## 两侧的红臂
 *
 * - **朝宽（必须红，且红名点名是哪枚尺）**：把编辑器那枚初值换回裸 `"10"` ⇒ 第 ② 层
 *   `advanceMinutes-bound` 那格红；把 `BackupReminder`/`ReminderEntity` 的默认值换回裸 `true`/`10`
 *   ⇒ 第 ②b 层与第 ① 层同时红（第 ① 层红在「消费者册子少一枚」，第 ②b 层红在「载体上有裸 true」）；
 *   把 domain 那枚常量改成 5 并在别处补一枚 `= 10` ⇒ 第 ① 层的落点册子红。
 * - **朝窄（不许进账）**：以上每一形写在注释里时，抹完必须 0 处 —— 第 ⑤ 层内联样例两头都摆。
 *
 * ⚠️ 本守卫**不修行为**，钉的是「默认值只此一处」这一件静态事实。
 * `ui/ScheduleViewModel.kt` 的 `CalendarSyncUiState.reminderMinutes` 那三枚 `10`
 * （`= "10"` / `getInt(..., 10)` / `?: 10`）是**日历同步**那一族的另一枚字段（自己的偏好键
 * `calendar_sync_reminder_minutes`，写进 Calendar provider，不进本 app 的排铃链），
 * 名字里没有 `advanceMinutes`，因此既不在第 ② 层的行绑范围里，也不该被它误伤 ——
 * 已写进回执的「需要跨卡配合」，不在本卡顺手改（改它要动白名单外的文件）。
 */
class ReminderDefaultSingleSourceGuardTest {

    // ─────────────── ① domain 那两枚常量：字面量赋值各恰一处 ───────────────

    @Test
    fun domainHoldsExactlyOneLiteralForEachDefault() {
        val main = readMainSources()
        val domain = source(DOMAIN_FILE)

        for ((needle, expectedRegister) in listOf(
            ADVANCE_CONST to listOf(DOMAIN_NAME),
            ENABLED_CONST to listOf(DOMAIN_NAME),
        )) {
            assertEquals(
                "**钉法 = 数出现次数（全 main 册子）｜尺 ①**：`$needle` 在全 app/src/main 恰好 1 处" +
                    "（实到 " + occurrencesOfAll(main, needle) + " 处，落点 " + filesHolding(main, needle) +
                    "）。多出来的那一枚要么在 `data/local/ReminderEntity.kt`、`data/backup/BackupModels.kt`、" +
                    "`ui/editor/CourseEditorScreen.kt` 里（改前的六处来源就是这么散的），要么是又长了" +
                    "一枚第二常量（值分叉的起点）。复算：" +
                    "grep -rnF '$needle' app/src/main --include=*.kt",
                1,
                occurrencesOfAll(main, needle),
            )
            assertEquals(
                "**钉法 = 数出现次数（落点册子）｜尺 ①**：`$needle` 只许长在 `$DOMAIN_NAME`（实到 " +
                    filesHolding(main, needle) + "）—— 改个名把裸字面量挪去别处，第 ① 层的枚数还是 1，" +
                    "这一格专门管「挪了家」",
                expectedRegister,
                filesHolding(main, needle),
            )
        }

        for (needle in listOf(ADVANCE_CONST, ENABLED_CONST)) {
            assertEquals(
                "**钉法 = 逐字节｜尺 ①**：`$needle` 在 `$DOMAIN_NAME` 恰一处（实到 " +
                    occurrences(domain, needle) + " 处）：逐字节钉的是那行**声明本体**，" +
                    "所以「另起一枚同值别名常量」也要在这里露出来",
                1,
                occurrences(domain, needle),
            )
        }

        // 值本身也得是那句口径说的数（册子成立但常量被改成 5 的话，上面三格全都还是绿的）
        assertEquals(
            "**钉法 = 读形状（值）｜尺 ①**：`DEFAULT_ADVANCE_MINUTES` 运行时取值必须是 10 —— " +
                "五处点名它的原文念的是「提前 10 分钟」，改了值那些句子全在说谎",
            10,
            ReminderSetting.DEFAULT_ADVANCE_MINUTES,
        )
        assertEquals(
            "**钉法 = 读形状（值）｜尺 ①**：`DEFAULT_ENABLED` 运行时取值必须是 true（同上，" +
                "「没有行 = 按默认开着」那条产品口径的取值面）",
            true,
            ReminderSetting.DEFAULT_ENABLED,
        )
        assertEquals(
            "**钉法 = 逐字节（构造默认值引的是常量）｜尺 ①**：`val advanceMinutes: Int = DEFAULT_ADVANCE_MINUTES,` " +
                "在 `$DOMAIN_NAME` 恰一处（实到 " + occurrences(domain, DOMAIN_CTOR_ADVANCE) + " 处）—— " +
                "这一格保证「枚数 1」不是一枚孤儿常量，而是真的在给构造器当默认值",
            1,
            occurrences(domain, DOMAIN_CTOR_ADVANCE),
        )
        assertEquals(
            "**钉法 = 逐字节｜尺 ①**：`val enabled: Boolean = DEFAULT_ENABLED,` 在 `$DOMAIN_NAME` 恰一处" +
                "（实到 " + occurrences(domain, DOMAIN_CTOR_ENABLED) + " 处）",
            1,
            occurrences(domain, DOMAIN_CTOR_ENABLED),
        )
    }

    // ─────────────── ② 全树：绑在 advanceMinutes 上的裸字面量 0 处 ───────────────

    @Test
    fun noBareAdvanceMinutesLiteralAnywhereInMain() {
        val main = readMainSources()

        val hits = bareLiteralLines(main, ADVANCE_BIND, BARE_TEN)
        assertEquals(
            "**钉法 = 数出现次数（按行绑符号，全 main）｜尺 ②**：任何**一行里同时出现 `advanceMinutes` " +
                "与一枚裸 10／\"10\"**（`= 10` / `: Int = 10` / `?: 10` / `= " + "\"10\"" + "` / `?: \"10\"" +
                "）都算「又写了一遍默认值」，全 app/src/main（抹注释后）必须 0 处。实到 " + hits.size +
                " 处：\n" + hits.joinToString(separator = "") { "    $it\n" } +
                "红了 = 病灶 B 的那六处来源里有人重新补了一枚裸字面量，于是「设置页念 10、实际响 5」" +
                "那一族偏差回来了。复算：grep -rn 'advanceMinutes' app/src/main | grep -E '= 10|\\?: 10|\"10\"'",
            0,
            hits.size,
        )

        // 抹注释那一族的老账：两侧一起钉。改前形状今天**只活在注释里**。
        val raw = bareLiteralLines(readMainSourcesRaw(), ADVANCE_BIND, BARE_TEN)
        assertEquals(
            "**钉法 = 抹注释找锚点（两侧）｜尺 ②**：同一把尺在**含注释**原文里数到 1 处（实到 " + raw.size +
                " 处：\n" + raw.joinToString(separator = "") { "    $it\n" } +
                "），抹注释后 0 处 —— 那一处正是 `reminder/ReminderScheduler.kt` 交代「改前是 " +
                "`setting?.advanceMinutes ?: 10`」的历史说明。" +
                "含注释不为 0 而抹完为 0，才同时证明「针是活的（不是空锚点）」与「盘上代码里没有裸字面量」；" +
                "把这句注释删掉来骗过第 ② 层的话，这一格会先红",
            1,
            raw.size,
        )
    }

    // ─────────────── ②b enabled 那一格：载体只许引常量 ───────────────

    @Test
    fun reminderCarriersNeverWriteABareEnabledDefault() {
        val main = readMainSources()

        for (carrier in CARRIERS) {
            val text = source(carrier)
            assertEquals(
                "**钉法 = 读形状（按载体册子）｜尺 ②b**：`$BARE_ENABLED_SHAPE` 在提醒那一族的载体 " +
                    "`$carrier` 必须 0 处（实到 " + occurrences(text, BARE_ENABLED_SHAPE) +
                    " 处）。这一族今天有四枚载体（domain / Room 实体 / 备份模型 / 编辑器初值），" +
                    "改前各写一遍 `true` 就是「没设过」被读成「设了、且开着」的出处之一。\n" +
                    "⚠️ 尺按载体取而不做全树裸扫：`enabled: Boolean = true` 在 `core/designsystem/` 那几枚 " +
                    "Composable 上是**控件可交互旗标**，与提醒无关，全树裸扫会在既有代码上假红。",
                0,
                occurrences(text, BARE_ENABLED_SHAPE),
            )
        }

        // 正向那一手：两枚载体必须逐字节引常量（换成第二枚常量、或换成裸 true，这两格都会红）
        val expected = listOf(BACKUP_FILE, ENTITY_FILE)
        val holding = expected.map { source(it) }
        for ((needle, holders) in listOf(
            ENTITY_ENABLED_DEFAULT_LINE to holding,
            ENTITY_ADVANCE_DEFAULT_LINE to holding,
        )) {
            assertEquals(
                "**钉法 = 逐字节 + 数出现次数（落点册子）｜尺 ②b**：`$needle` 全 app/src/main 恰 2 处，" +
                    "且只许落在 `" + BACKUP_NAME + "` 与 `" + ENTITY_NAME + "`（实到 " +
                    occurrencesOfAll(main, needle) + " 处，落点 " + filesHolding(main, needle) +
                    "）。枚数与落点一起钉：挪去第三枚文件、或原地退回裸字面量，这一格都红",
                listOf(BACKUP_NAME, ENTITY_NAME).sorted(),
                filesHolding(main, needle).sorted(),
            )
        }

        val editor = source(EDITOR_FILE)
        for ((needle, meaning) in listOf(
            EDITOR_ENABLED_INIT to "编辑器开关初值走 `?: ReminderSetting.DEFAULT_ENABLED`",
            EDITOR_ADVANCE_INIT to "编辑器提前量初值走 `?: ReminderSetting.DEFAULT_ADVANCE_MINUTES.toString()`",
            EDITOR_SAVE_FALLBACK to "保存那一路的空值兜底也引同一枚常量",
        )) {
            assertEquals(
                "**钉法 = 逐字节｜尺 ②b**：$needle 在 `$EDITOR_NAME` 恰一处（实到 " +
                    occurrences(editor, needle) + " 处）—— $meaning。" +
                    "卡面「朝宽」那一臂（把初值换回裸 \"10\"）红的就是这一格与第 ② 层那格",
                1,
                occurrences(editor, needle),
            )
        }

        // 「新建课不建行」那一路仍由 isEditingExisting 关着 —— 它是默认值之所以必须同源的前提
        assertEquals(
            "**钉法 = 数出现次数｜尺 ②b（前提）**：`if (isEditingExisting) {` 在 `$EDITOR_NAME` 恰一处（实到 " +
                occurrences(editor, "if (isEditingExisting) {") + " 处）。新建那一路不写提醒行，" +
                "「没有行」才是常态；哪天这一格变成 0（新建也建行），第 ②b 层这几把尺的前提就变了，" +
                "要回来重判而不是让它绿着",
            1,
            occurrences(editor, "if (isEditingExisting) {"),
        )
    }

    // ─────────────── ③ 消费者册子：谁在引那两枚常量 ───────────────

    @Test
    fun constantConsumersAreRegistered() {
        val main = readMainSources()

        assertEquals(
            "**钉法 = 数出现次数（消费者册子）｜尺 ③**：`ReminderSetting.DEFAULT_ADVANCE_MINUTES` 的落点" +
                "必须是这四枚（枚数也逐枚钉）：`BackupModels.kt=1`（备份那格的默认）、" +
                "`CourseEditorScreen.kt=3`（初值 + 那句念给用户听的 supportingText + 保存那一路的空值兜底）、" +
                "`ReminderEntity.kt=1`（表那格的默认）、`ReminderSetting.kt=1`（没有行那一档的取值）。" +
                "实到册子 " + ledger(main, FULL_ADVANCE_REF) + "（共 " +
                occurrencesOfAll(main, FULL_ADVANCE_REF) + " 处）。" +
                "多一枚 = 又长了个读默认值的地方（得回来判它是不是该走 EffectiveReminderSetting.of）；" +
                "少一枚 = 有个载体改回自己造数了 —— 编辑器少的那一枚正是「用户看得见的那一句」，" +
                "它会与 `EditorReminderSectionSummaryTest` 一起红",
            mapOf(BACKUP_NAME to 1, EDITOR_NAME to 3, ENTITY_NAME to 1, DOMAIN_NAME to 1),
            ledgerMap(main, FULL_ADVANCE_REF),
        )
        assertEquals(
            "**钉法 = 数出现次数（消费者册子）｜尺 ③**：`ReminderSetting.DEFAULT_ENABLED` 的落点必须是 " +
                "`BackupModels.kt=1` / `CourseEditorScreen.kt=1` / `ReminderEntity.kt=1` / `ReminderSetting.kt=1`。" +
                "实到册子 " + ledger(main, FULL_ENABLED_REF) + "（共 " +
                occurrencesOfAll(main, FULL_ENABLED_REF) + " 处）",
            mapOf(BACKUP_NAME to 1, EDITOR_NAME to 1, ENTITY_NAME to 1, DOMAIN_NAME to 1),
            ledgerMap(main, FULL_ENABLED_REF),
        )
        assertEquals(
            "**钉法 = 数出现次数（全树 token 册子）｜尺 ③**：`DEFAULT_ADVANCE_MINUTES` 这一枚 token 全 " +
                "app/src/main 抹注释后恰 " + DEFAULT_TOTAL_EXPECTED + " 处 = 声明 1（domain 那枚 `= 10`）" +
                "+ 构造器默认值 1 + `of()`「没有行」那一档 1 + 载体与编辑器 5（备份 1 / 实体 1 / 编辑器 3）。实到 " +
                occurrencesOfAll(main, "DEFAULT_ADVANCE_MINUTES") + " 处（册子 " +
                ledger(main, "DEFAULT_ADVANCE_MINUTES") + "）。" +
                "这一枚是「声明 + 消费者」的总和尺：第 ①②b③ 层各数一头，而这里数全树 token，" +
                "所以「另起一枚同名的第二处」与「改个名躲针」两种手法都会被露头",
            DEFAULT_TOTAL_EXPECTED,
            occurrencesOfAll(main, "DEFAULT_ADVANCE_MINUTES"),
        )
        assertEquals(
            "**钉法 = 数出现次数（全树 token 册子）｜尺 ③**：`DEFAULT_ENABLED` 全 app/src/main 抹注释后恰 " +
                ENABLED_TOTAL_EXPECTED + " 处 = 声明 1（domain 那枚 `= true`）+ 构造器默认值 1 + " +
                "`of()` 那一档 1 + 载体与编辑器 3（备份 1 / 实体 1 / 编辑器那枚开关初值 1）。实到 " +
                occurrencesOfAll(main, "DEFAULT_ENABLED") + " 处，册子 " +
                ledger(main, "DEFAULT_ENABLED") + " —— 与上面那枚同一条产品口径，" +
                "两枚 token 的总枚数必须一起对得上账",
            ENABLED_TOTAL_EXPECTED,
            occurrencesOfAll(main, "DEFAULT_ENABLED"),
        )
    }

    // ─────────────── ④ 点名者必在册（假绿的根） ───────────────

    @Test
    fun everySiteNamingThisGuardStillNamesSomethingReal() {
        val main = readMainSourcesRaw()

        assertEquals(
            "**钉法 = 逐字节 + 数出现次数（点名句在册）｜尺 ④**：main 侧必须有三处原文念着 `" + GUARD_NAME +
                "`（`ReminderSetting.kt`、`ReminderEntity.kt`、`CourseEditorScreen.kt` 各一枚）—— " +
                "那三句是「有人钉着」的公开承诺。实到 " + ledger(main, GUARD_NAME) + "。" +
                "少一处 = 那句承诺被删了（要么真的不需要守卫了，要么把假绿搬进了 KDoc）；" +
                "多一处 = 有新的承诺要跟着这枚守卫一起维护。不许反过来改那三处去迁就一枚新名字",
            listOf(DOMAIN_NAME, EDITOR_NAME, ENTITY_NAME).sorted(),
            filesHolding(main, GUARD_NAME).sorted(),
        )
        assertEquals(
            "**钉法 = 数出现次数（点名句在册）｜尺 ④**：test 侧另有两处在念本守卫（" +
                "`ReminderSettingResolutionTest`、`EditorReminderSectionSummaryTest`），" +
                "合计五处与卡面一致。实到 " + ledger(readTestSourcesRaw(), GUARD_NAME) + "（枚数 " +
                occurrencesOfAll(readTestSourcesRaw(), GUARD_NAME) + "，不含本文件自己）",
            listOf("EditorReminderSectionSummaryTest.kt", "ReminderSettingResolutionTest.kt").sorted(),
            filesHolding(readTestSourcesRaw(), GUARD_NAME).sorted(),
        )

        val self = File(findRepoRoot(), "app/src/test/java/com/buaa/schedule/reminder/$GUARD_FILE")
        assertTrue(
            "**钉法 = 读形状（钉子自己在盘上）｜尺 ④**：本守卫文件必须存在于 $self.path —— " +
                "T214 崩卡落到盘上时，五处原文都在替一枚不存在的文件作证。这一格把那一种假绿钉死：" +
                "文件被删而点名句留着 ⇒ 立刻红",
            self.isFile,
        )
        val selfText = self.readText().replace("\r\n", "\n")
        assertEquals(
            "**钉法 = 读形状（点名句钉的是真符号）｜尺 ④**：`class $GUARD_CLASS ` 必须就在本文件里（实到 " +
                occurrences(selfText, "class $GUARD_CLASS ") + " 处）—— " +
                "防止「点名句还在、文件被换成一枚空壳」那一手",
            1,
            occurrences(selfText, "class $GUARD_CLASS "),
        )
    }

    // ─────────────── ⑤ 探测器自检：两侧都不空转 ───────────────

    /**
     * 只报「盘上几处」而探测器数不清，等于没有守卫（本仓口径：别拿 grep 没匹配到当全绿）。
     * 两头各摆一头：改前的裸字面量形必须抓得到（否则第 ②②b 层的「0 处」是空锚点），
     * 同一形写在注释里必须不进账（否则是把散文当证据）。
     */
    @Test
    fun detectorsCatchThePreFixShapeAndIgnoreTheQuotedOne() {
        val preFixBackup = "    val advanceMinutes: Int = 10,"
        assertEquals(
            "内联样例｜朝宽：`BackupReminder` 改前那行裸 10 必须被第 ② 层那把尺抓成 1 处（实到 " +
                bareLiteralLines(mapOf("x" to preFixBackup), ADVANCE_BIND, BARE_TEN).size + " 处）",
            1,
            bareLiteralLines(mapOf("x" to preFixBackup), ADVANCE_BIND, BARE_TEN).size,
        )

        val preFixEditor = "        mutableStateOf(initialReminder?.advanceMinutes?.toString() ?: \"10\")"
        assertEquals(
            "内联样例｜朝宽：编辑器初值退回裸 \"10\" 那一形必须抓到（实到 " +
                bareLiteralLines(mapOf("x" to preFixEditor), ADVANCE_BIND, BARE_TEN).size +
                " 处）—— 卡面点名的就是这一臂",
            1,
            bareLiteralLines(mapOf("x" to preFixEditor), ADVANCE_BIND, BARE_TEN).size,
        )

        val preFixEntity = "    val enabled: Boolean = true,"
        assertEquals(
            "内联样例｜朝宽：`ReminderEntity` 改前那行裸 true 必须被第 ②b 层抓到（实到 " +
                occurrences(preFixEntity, BARE_ENABLED_SHAPE) + " 处）",
            1,
            occurrences(preFixEntity, BARE_ENABLED_SHAPE),
        )

        val elvisTen = "val advanceMinutes = setting?.advanceMinutes ?: 10"
        assertEquals(
            "内联样例｜朝宽：`?: 10` 那一形必须抓到，而且 `100` 这类值不许误伤（" +
                bareLiteralLines(mapOf("x" to elvisTen), ADVANCE_BIND, BARE_TEN).size +
                " / " + bareLiteralLines(mapOf("x" to "val advanceMinutes = 100"), ADVANCE_BIND, BARE_TEN).size +
                "）：前者必须 1、后者必须 0，否则第 ② 层要么空转要么假红",
            1,
            bareLiteralLines(mapOf("x" to elvisTen), ADVANCE_BIND, BARE_TEN).size,
        )
        assertEquals(
            "内联样例｜尺 ② 不误伤：`advanceMinutes = 100` 是用户显式设的值而不是默认值，必须 0 处",
            0,
            bareLiteralLines(mapOf("x" to "val advanceMinutes = 100"), ADVANCE_BIND, BARE_TEN).size,
        )

        val quotedInComment = """
            /**
             * 改前这两形今天只活在注释里（逐字引用，不是盘上的代码）：
             *   val advanceMinutes: Int = 10,
             *   val advanceMinutes = setting?.advanceMinutes ?: 10
             */
            fun noop() = Unit
        """.trimIndent()
        assertEquals(
            "内联样例｜朝窄：把这些形状逐字写进注释，抹完之后第 ② 层那把尺必须 0 处（实到 " +
                bareLiteralLines(mapOf("x" to blankComments(quotedInComment)), ADVANCE_BIND, BARE_TEN).size +
                " 处）—— 本仓抹注释那一族的老账，写法照抄 `TermWeekGateSingleSourceGuardTest` 第 ⑥ 层",
            0,
            bareLiteralLines(mapOf("x" to blankComments(quotedInComment)), ADVANCE_BIND, BARE_TEN).size,
        )
        assertEquals(
            "内联样例｜朝窄的另一头：未抹注释时那两行必须各抓得到一枚（这把尺数的是**行**，" +
                "两形两行 ⇒ 2 处；实到 " +
                bareLiteralLines(mapOf("x" to quotedInComment), ADVANCE_BIND, BARE_TEN).size +
                " 处）—— 抓不到的话上面那句「抹完 0 处」只是在数空气，那把尺是空锚点",
            2,
            bareLiteralLines(mapOf("x" to quotedInComment), ADVANCE_BIND, BARE_TEN).size,
        )
    }

    // ---------------- 源码核对小工具（与本仓守卫同一套写法）----------------

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun occurrencesOfAll(byFile: Map<String, String>, needle: String): Int =
        byFile.values.sumOf { occurrences(it, needle) }

    private fun filesHolding(byFile: Map<String, String>, needle: String): List<String> =
        byFile.filter { occurrences(it.value, needle) > 0 }.keys.toList().sorted()

    /** 失败消息里要能看见落在哪几份文件、各几枚 */
    private fun ledger(byFile: Map<String, String>, needle: String): String =
        if (ledgerMap(byFile, needle).isEmpty()) "{}" else ledgerMap(byFile, needle).toString()

    private fun ledgerMap(byFile: Map<String, String>, needle: String): Map<String, Int> =
        byFile.entries.toList().sortedBy { it.key }
            .map { it.key to occurrences(it.value, needle) }
            .filter { it.second > 0 }
            .toMap()

    /**
     * 尺 ② 的那把行绑尺：一行里**同时**出现 [$bindToken] 与一枚 [$shape] 裸字面量才算进账。
     * 绑符号而不是全树裸扫 `10` —— 全树扫会撞上 `CalendarSyncUiState.reminderMinutes` 那族同名不同源的
     * 数（文件顶 ⚠️ 那段已登记），那是跨卡那一格，不是本尺的对象。
     */
    private fun bareLiteralLines(byFile: Map<String, String>, bindToken: String, shape: Regex): List<String> =
        byFile.flatMap { (name, text) ->
            text.split('\n').map { it.trim() }.filter {
                it.contains(bindToken) && shape.containsMatchIn(it)
            }.map { "$name  ->  $it" }
        }

    /** 抹注释后的单份 main 源码 */
    private fun source(relativeFromMainJava: String): String =
        blankComments(File(findMainJavaDir(), relativeFromMainJava).readText().replace("\r\n", "\n"))

    private fun readMainSources(): Map<String, String> =
        readDir(findMainJavaDir()).mapValues { blankComments(it.value) }

    private fun readMainSourcesRaw(): Map<String, String> = readDir(findMainJavaDir())

    private fun readTestSourcesRaw(): Map<String, String> =
        readDir(File(findRepoRoot(), "app/src/test/java/com/buaa/schedule")).filterKeys {
            it != GUARD_FILE
        }

    private fun readDir(root: File): Map<String, String> {
        assertTrue("找不到 ${root.path}：目录挪过家的话本守卫要跟着改路径", root.isDirectory)
        val out = linkedMapOf<String, String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach {
            check(!out.containsKey(it.name)) { "有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = it.readText().replace("\r\n", "\n")
        }
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", out.isNotEmpty())
        return out
    }

    /** **只抹注释、保留字符串字面量**，长度与换行位置不变（报错消息里的行号因此可信） */
    private fun blankComments(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

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

    private fun findMainJavaDir(): File = File(findRepoRoot(), "app/src/main/java")

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            if (dir != null && File(dir, "app/src/main/java").isDirectory) return dir
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val DOMAIN_FILE = "com/buaa/schedule/domain/model/ReminderSetting.kt"
        const val ENTITY_FILE = "com/buaa/schedule/data/local/ReminderEntity.kt"
        const val BACKUP_FILE = "com/buaa/schedule/data/backup/BackupModels.kt"
        const val EDITOR_FILE = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"

        const val DOMAIN_NAME = "ReminderSetting.kt"
        const val ENTITY_NAME = "ReminderEntity.kt"
        const val BACKUP_NAME = "BackupModels.kt"
        const val EDITOR_NAME = "CourseEditorScreen.kt"

        const val GUARD_NAME = "ReminderDefaultSingleSourceGuardTest"
        const val GUARD_CLASS = "ReminderDefaultSingleSourceGuardTest"
        const val GUARD_FILE = "ReminderDefaultSingleSourceGuardTest.kt"

        /** 提醒那一族的四枚载体（尺 ②b 的取块范围） */
        val CARRIERS = listOf(DOMAIN_FILE, ENTITY_FILE, BACKUP_FILE, EDITOR_FILE)

        /** 尺 ①：两枚常量的声明本体 */
        const val ADVANCE_CONST = "const val DEFAULT_ADVANCE_MINUTES = 10"
        const val ENABLED_CONST = "const val DEFAULT_ENABLED = true"
        const val DOMAIN_CTOR_ADVANCE = "val advanceMinutes: Int = DEFAULT_ADVANCE_MINUTES,"
        const val DOMAIN_CTOR_ENABLED = "val enabled: Boolean = DEFAULT_ENABLED,"

        /** 尺 ②：绑 `advanceMinutes` 那一行的裸 10/"10"（(?![\d.]) 防误伤 100 / 10.5） */
        const val ADVANCE_BIND = "advanceMinutes"
        val BARE_TEN = Regex("""[=:]\s*"?10(?![\d.])""")

        /** 尺 ②b：载体上的裸 true 形，与两枚载体默认值行的正向形状 */
        const val BARE_ENABLED_SHAPE = "enabled: Boolean = true"
        const val ENTITY_ENABLED_DEFAULT_LINE = "val enabled: Boolean = ReminderSetting.DEFAULT_ENABLED,"
        const val ENTITY_ADVANCE_DEFAULT_LINE = "val advanceMinutes: Int = ReminderSetting.DEFAULT_ADVANCE_MINUTES,"

        const val EDITOR_ENABLED_INIT =
            "mutableStateOf(initialReminder?.enabled ?: ReminderSetting.DEFAULT_ENABLED)"
        const val EDITOR_ADVANCE_INIT =
            "initialReminder?.advanceMinutes?.toString() ?: ReminderSetting.DEFAULT_ADVANCE_MINUTES.toString()"
        const val EDITOR_SAVE_FALLBACK = "advanceMinutesText.toIntOrNull() ?: ReminderSetting.DEFAULT_ADVANCE_MINUTES"

        /** 尺 ③：限定写法（载体与 domain 的 of() 用这一枚） */
        const val FULL_ADVANCE_REF = "ReminderSetting.DEFAULT_ADVANCE_MINUTES"
        const val FULL_ENABLED_REF = "ReminderSetting.DEFAULT_ENABLED"

        /**
         * 尺 ③ 的两枚 token 总枚数（抹注释后全 app/src/main）：
         * `DEFAULT_ADVANCE_MINUTES` = 声明 1 + domain 构造器默认值 1 + `of()` 那一档 1 + 载体与编辑器 5
         * （备份 1 / 实体 1 / 编辑器 3：初值、supportingText 文案、保存兜底）= **8**；
         * `DEFAULT_ENABLED` = 声明 1 + 构造器默认值 1 + `of()` 那一档 1 + 载体与编辑器 3
         * （备份 1 / 实体 1 / 编辑器开关初值 1）= **6**。
         * 量于 ai/T214b（盘面 `151d9ea` + 本守卫），改一处消费者就要回来重判。
         */
        const val DEFAULT_TOTAL_EXPECTED = 8
        const val ENABLED_TOTAL_EXPECTED = 6
    }
}
