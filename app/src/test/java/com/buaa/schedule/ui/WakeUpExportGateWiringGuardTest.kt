package com.buaa.schedule.ui

import com.buaa.schedule.domain.schedule.WakeUpExportDecision
import com.buaa.schedule.domain.schedule.WakeUpExportGate
import com.buaa.schedule.domain.schedule.WakeUpJsonText
import com.buaa.schedule.domain.schedule.WakeUpTargetFileMutation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T279③：钉两件事的**源码形状**（卡面点名的那两枚），钉法是四种各按本格说的这一种：
 *
 * ## 一、WakeUp 那条写盘链不开流，除非闸门说这一趟有正文可写
 *
 * 主尺是**读形状**（花括号配平取块 + 索引顺序），不是逐字节。理由与 `IcsExportGateWiringGuardTest`
 * 同一条：本卡的病灶不是某一行写错，而是**三行的先后** —— 算内容 → 开流（截断）→ 报数。
 * 逐字节只能钉住"这一行今天长这样"，把开流那一手往上挪两行、判空留在下面，
 * 每一行都逐字对得上而危险形状整块回来 ⇒ 只有索引顺序钉得住它。于是 ① 那格判的是：
 * `exportWakeUpTo` 体内 **`WakeUpExportGate.decide(` 的索引 < `writeTextToUri(` 的索引**，
 * 且 `writeTextToUri(` 那一枚**落在 `is WakeUpExportDecision.WriteTruncating -> {` 的花括号块里**，
 * 体内它恰好 **1** 枚、写出去的正文必须是判据交回的 `decision.json`。
 * 辅钉：**数出现次数（抹注释）**钉病灶行 `writeTextToUri(uriString, json)` 在代码里 0 处、
 * 在含注释的原文里恰好 1 处（VM 的 KDoc 逐字引用着改前那一手 ⇒ 那个 0 不是"注释删干净"换来的）。
 *
 * ## 二、转义只有一处落点（内核），导出器里没有第二把尺
 *
 * 判据是**数出现次数（抹注释）+ 逐字节**：`ScheduleExporters.kt` 里改前那两行的形状
 * （`jsonStr` 与 `replace("\\", "\\\\")`）在代码里各 **0** 处、在注释里各恰好 **1** 处（取证）；
 * 内核 `WakeUpJsonText.literal(` 在 `toWakeUpJson` 体内恰好 **6** 枚（教师、教室、课程名、
 * 顶层学期名、tableInfo 学期名五枚用户字段，另加一枚**装着 JSON 的 `time` 串** —— 它里面的 `"`
 * 改前一枚都没转义，整份文件在那一格就非法，本卡端到端那一格把它量了出来），
 * 全文件剩下的那一枚 `replace(`
 * 必须**逐字节**是 `compact` 那一行（它摘的是时间里的冒号，不是转义落点：机器生成的 ASCII HH:mm）。
 * N6 那一格同用两种钉法：`course.name` 代码 0 处 / 注释 1 处，`course.displayName` 在体内恰好 1 枚。
 *
 * ## 反套套逻辑（不许拿"grep 没匹配到"当"全绿"）
 *
 * ① 病灶行、② `jsonStr`、② `replace("\\", "\\\\")`、② `course.name` 四枚都另有"含注释原文里
 * 恰好 1 处"的反证格；③ 档位枚数（` : WakeUpExportDecision {` 恰 4、两种落盘模式各 1/3 枚）
 * 另有运行时反证（真跑一遍判据交回四枚不同档位）；④ 两张转义表的覆盖面另有运行时反证
 * （真扫一遍码位，产出的字面量里裸 C0 必须 0 枚）。
 *
 * ## 两侧的红臂（本守卫自己也得是活的）
 *
 * - **朝宽**（把内核 `escaped` 里控制字符那一支摘掉 ⇒ 只剩 `"` 与 `\`）⇒ ④ 的运行时反证格先红，
 *   表 ② 那 32 格（`WakeUpJsonTextTest`）跟着整片红；
 * - **朝窄**（把 `exportWakeUpTo` 体内那一手改回判据之前 ⇒ `writeTextToUri` 落在 when 之外）⇒
 *   ① 的顺序格、"开流只在 WriteTruncating 支里"那格、体内恰 1 枚那格**同时红**。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。输入面：本守卫读 `src/main/java`
 * 下的工作树文本，已由 `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 整体声明成
 * `testDebugUnitTest` 的输入。⚠️ 未装机：`openOutputStream` 的截断行为与 Snackbar 的实屏呈现
 * 都没有设备读数（本卡零 adb）。
 */
class WakeUpExportGateWiringGuardTest {

    private companion object {
        const val GATE = "com/buaa/schedule/domain/schedule/WakeUpExportGate.kt"
        const val TEXT = "com/buaa/schedule/domain/schedule/WakeUpJsonText.kt"
        const val EXPORTERS = "com/buaa/schedule/domain/schedule/ScheduleExporters.kt"
        const val COPY = "com/buaa/schedule/ui/settings/WakeUpExportCopy.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"

        const val EXPORT_CALL = "fun exportWakeUpTo(uriString: String) {"
        const val TO_WAKE_UP = "fun toWakeUpJson("
        const val DECIDE_ANCHOR = "fun decide("

        const val GATE_DECIDE_CALL = "WakeUpExportGate.decide("
        const val OPEN_STREAM = "writeTextToUri("
        const val WRITE_BRANCH = "is WakeUpExportDecision.WriteTruncating -> {"

        /** 改前那一手（病灶行）：VM 的 KDoc 里逐字留着，代码里必须一枚都不许有 */
        const val DANGEROUS_LINE = "writeTextToUri(uriString, json)"

        const val ARM_NO_FILE = "WakeUpExportDecision.NoFileSelected -> Unit"
        const val ARM_EMPTY = "is WakeUpExportDecision.RefuseEmpty ->"
        const val ARM_INCONSISTENT = "is WakeUpExportDecision.RefuseInconsistent ->"
        const val MESSAGE_CALL = "showMessage("
        const val SUCCESS_MESSAGE_CALL = "wakeUpExportMessage(courses.size, drops.noPeriodCourses, drops.noWeekCourses)"
        const val REFUSED_MESSAGE_CALL = "wakeUpExportRefusedMessage(decision)"
        const val INCONSISTENT_MESSAGE_CALL = "wakeUpExportInconsistentMessage(decision)"
        const val INPUT_GATE_KEPT = "当前没有可导出的课程"

        /** 判据四条 + 签名一条 + 档位/模式枚数 */
        const val DECIDE_SIGNATURE = "): WakeUpExportDecision = when {"
        const val RULE_NOT_SELECTED = "!fileSelected -> WakeUpExportDecision.NoFileSelected"
        const val RULE_ZERO_FRAGMENTS = "writtenFragments <= 0 -> WakeUpExportDecision.RefuseEmpty(droppedFragments)"
        const val RULE_BLANK_JSON = "json.isBlank() -> WakeUpExportDecision.RefuseInconsistent(writtenFragments)"
        const val RULE_WRITE = "else -> WakeUpExportDecision.WriteTruncating(json, writtenFragments, droppedFragments)"
        const val TIER_SUPER = " : WakeUpExportDecision {"
        const val MUTATION_ENUM_LINE = "enum class WakeUpTargetFileMutation { TRUNCATE_AND_WRITE, LEAVE_UNTOUCHED }"
        const val MUTATION_TRUNCATE = "get() = WakeUpTargetFileMutation.TRUNCATE_AND_WRITE"
        const val MUTATION_UNTOUCHED = "get() = WakeUpTargetFileMutation.LEAVE_UNTOUCHED"

        /** 转义落点：改前两枚病灶形 + 内核调用枚数 + 那一枚合法的 replace */
        const val LEGACY_HELPER_NAME = "jsonStr"
        const val LEGACY_ESCAPE_HAND = "replace(\"\\\\\", \"\\\\\\\\\")"
        const val KERNEL_CALL = "WakeUpJsonText.literal("
        const val COMPACT_LINE = "fun compact(time: String): String = time.replace(\":\", \"\") + \"00\""
        const val OLD_NAME_FIELD = "course.name"
        const val DISPLAY_NAME_FIELD = "course.displayName"

        /** 文案两枚新函数（逐字节签名）与那句实话 */
        const val COPY_REFUSED =
            "internal fun wakeUpExportRefusedMessage(decision: WakeUpExportDecision.RefuseEmpty): String ="
        const val COPY_INCONSISTENT =
            "internal fun wakeUpExportInconsistentMessage(decision: WakeUpExportDecision.RefuseInconsistent): String ="
        const val HONEST_TAIL = "未写出文件"
        const val SUCCESS_HEAD = "WakeUp JSON 已导出"

        /** 纯度：本仓口径 —— 两枚判据文件里这些读取一枚都不许有 */
        val FORBIDDEN_READS = listOf(
            "Instant.now",
            "System.currentTimeMillis",
            "LocalDate.now",
            "LocalDateTime",
            "TimeZone",
            "ZoneId",
            "Build.VERSION",
            "Calendar.getInstance",
        )
        const val PURITY_PROMISE = "零 android / androidx import"
    }

    // ─────────────── ① 主尺（读形状）：判据排在开流之前 ───────────────

    @Test
    fun `开流那一手排在判据之后 且只在写出那一档里`() {
        val code = blankComments(source(VIEW_MODEL))
        val body = balancedBlock(code, EXPORT_CALL, "ScheduleViewModel.exportWakeUpTo")

        val decideAt = body.indexOf(GATE_DECIDE_CALL)
        val openAt = body.indexOf(OPEN_STREAM)
        assertTrue(
            "**钉法 = 读形状**：exportWakeUpTo 体内必须找到判据那一手（「$GATE_DECIDE_CALL」）：" +
                "找不到就是整条链换载体了，本守卫的锚点要跟着重钉",
            decideAt >= 0,
        )
        assertTrue(
            "**钉法 = 读形状**：exportWakeUpTo 体内必须还有一处开流（「$OPEN_STREAM」）：一枚都没有" +
                "就是 WakeUp 这条路整个没了（那不是本卡裁的方向）",
            openAt >= 0,
        )
        assertTrue(
            "**钉法 = 读形状**：**判据必须排在开流之前**（decide 的索引 $decideAt、开流的索引 $openAt，" +
                "前者必须更小）。开流挪到判据前面 = 文件已经先被截成空课表、才发现这一趟没东西可写 —— " +
                "那正是审计 N4 的形状（三行逐字节都对得上，错的先后）",
            decideAt < openAt,
        )

        val writeBranch = balancedBlock(code, WRITE_BRANCH, "exportWakeUpTo 里的 WriteTruncating 那一支")
        assertEquals(
            "**钉法 = 数出现次数（抹注释）**：体内「$OPEN_STREAM」恰好 **1** 枚，而它必须整个长在 " +
                "WriteTruncating 那一支里（那一支里读到 " + occurrences(writeBranch, OPEN_STREAM) + " 枚）：" +
                "两支都有 = 不落盘的档位也在开用户的文件；一支都没有 = 写出那一档被顺手摘了路",
            1,
            occurrences(body, OPEN_STREAM),
        )
        assertEquals("开流那一手必须发生在 WriteTruncating 支内（数出现次数）", 1, occurrences(writeBranch, OPEN_STREAM))
        assertEquals(
            "**钉法 = 数出现次数**：写出去的正文必须是判据交回的那一枚（「decision.json」），实到 " +
                occurrences(writeBranch, "decision.json") + " 枚：读回上面那个 json 局部量 = " +
                "判据与写盘两头各拿一份真相（闸门说没正文也照样写）",
            1,
            occurrences(writeBranch, "decision.json"),
        )

        assertEquals(
            "**钉法 = 抹注释找锚点**：改前那一手「$DANGEROUS_LINE」在代码里必须 **0** 处" +
                "（它是本卡病灶：把「没有内容可写」折成「截断用户的文件」）。\n" +
                "  复算：把 $VIEW_MODEL 的 // 与块注释抹成空格后数这一行的枚数" +
                "\n  实到枚数：" + occurrences(code, DANGEROUS_LINE),
            0,
            occurrences(code, DANGEROUS_LINE),
        )
        // 反套套逻辑：含注释的原文里它恰好 1 处（VM 的 KDoc 逐字引用着改前那一手）
        assertEquals(
            "**钉法 = 数出现次数（含注释原文）**：原文里「$DANGEROUS_LINE」必须恰好 **1** 处 —— 那是 KDoc 里的" +
                "改前取证。读到 0 = 取证被搬走，上面那个 0 就成了「注释删干净」的假绿；" +
                "读到 >1 = 病灶回潮或被引用第二遍。实到：" + occurrences(source(VIEW_MODEL), DANGEROUS_LINE),
            1,
            occurrences(source(VIEW_MODEL), DANGEROUS_LINE),
        )
    }

    // ─────────────── ② 四档在调用点各有落点（不许并进 else） ───────────────

    @Test
    fun `四档各有落点 调用点不兜底`() {
        val code = blankComments(source(VIEW_MODEL))
        val body = balancedBlock(code, EXPORT_CALL, "ScheduleViewModel.exportWakeUpTo")
        for (anchor in listOf(ARM_NO_FILE, ARM_EMPTY, ARM_INCONSISTENT, WRITE_BRANCH)) {
            assertEquals(
                "**钉法 = 数出现次数（抹注释）**：体内档位锚点「$anchor」必须恰好一枚（实到 " +
                    occurrences(body, anchor) + "）。少一枚 = 那一档在屏幕上没有自己的句子；" +
                    "多一枚 = 同一档被两处分别处置，用户读到的取决于走哪条路",
                1,
                occurrences(body, anchor),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：体内「$MESSAGE_CALL」恰好 4 枚 = 入参那一等（没课）+ 不落盘两档各一句实话 " +
                "+ 写出那一档的成功/失败回执。实到 " + occurrences(body, MESSAGE_CALL) + " 枚 —— " +
                "涨到 5 枚就是有人给「取消」那一档编了句假失败；掉下来就是某一档没了自己的句子",
            4,
            occurrences(body, MESSAGE_CALL),
        )
        for (anchor in listOf(SUCCESS_MESSAGE_CALL, REFUSED_MESSAGE_CALL, INCONSISTENT_MESSAGE_CALL)) {
            assertEquals(
                "**钉法 = 数出现次数**：文案函数在体内恰好一枚调用点「$anchor」（实到 " +
                    occurrences(body, anchor) + " 枚）。三枚并报数/实话的落点各归各档",
                1,
                occurrences(body, anchor),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：改前那枚入参门必须**留着**（「$INPUT_GATE_KEPT」恰 1 枚，实到 " +
                occurrences(body, INPUT_GATE_KEPT) + " 枚）：「课表里一枚都没有」与「有课但一段都写不出去」" +
                "是两格、两句不同的话，并成一格就有一格没人念",
            1,
            occurrences(body, INPUT_GATE_KEPT),
        )
        assertEquals(
            "**钉法 = 数出现次数（抹注释）**：句子只有一处真相，在 $COPY ⇒ VM 体内「已导出」必须 0 处（实到 " +
                occurrences(body, "已导出") + " 枚）。搬回 VM = 把它移出 JVM 单测可达的范围，" +
                "也是 T181② 那句破括号的产地",
            0,
            occurrences(body, "已导出"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：段数只能从已算好的那本账来（「drops.droppedCourses」在体内 2 枚 = " +
                "writtenFragments 与 droppedFragments 各递一次，不另数第三遍）。实到 " +
                occurrences(body, "drops.droppedCourses") + " 枚",
            2,
            occurrences(body, "drops.droppedCourses"),
        )
    }

    // ─────────────── ③ 判据内核：四条规则 + 顺序 + 档位枚数 ───────────────

    @Test
    fun `判据内核四条规则一枚都不许并掉`() {
        val code = blankComments(source(GATE))
        val body = balancedBlock(code, DECIDE_ANCHOR, "WakeUpExportGate.decide")
        assertEquals("**钉法 = 逐字节**：decide 的签名逐字节一枚在场", 1, occurrences(code, DECIDE_SIGNATURE))
        for (rule in listOf(RULE_NOT_SELECTED, RULE_ZERO_FRAGMENTS, RULE_BLANK_JSON, RULE_WRITE)) {
            assertEquals(
                "**钉法 = 逐字节**：判据「$rule」必须恰好一枚（实到 " + occurrences(body, rule) + "）。" +
                    "少任何一枚就有两档要借同一句用户回执说话 —— 屏幕上「没东西可写」与「写出去了却报 0」" +
                    "就会念成同一句话",
                1,
                occurrences(body, rule),
            )
        }
        assertTrue(
            "**钉法 = 读形状**：「段数≤0」必须排在「正文空白」之前（实到索引 " +
                "${body.indexOf(RULE_ZERO_FRAGMENTS)} 与 ${body.indexOf(RULE_BLANK_JSON)}，" +
                "前者必须更小且都非负）。颠倒过来 = 导出器哪天交回合法空骨架（0 段却有文本），" +
                "就又开始截用户的文件 —— 这一枚就是第 ⓪ 问甲档的门",
            body.indexOf(RULE_ZERO_FRAGMENTS) in 0 until body.indexOf(RULE_BLANK_JSON),
        )
        assertEquals(
            "**钉法 = 数出现次数**：档位恰好 4 枚（实到 " + occurrences(code, TIER_SUPER) + "）：" +
                "3 枚 = 有一档被折掉（Boolean 那一折），5 枚 = 先补文案与表驱动格子再来涨",
            4,
            occurrences(code, TIER_SUPER),
        )
        assertEquals("**钉法 = 逐字节**：落盘模式那本册子逐字节一枚", 1, occurrences(code, MUTATION_ENUM_LINE))
        assertEquals(
            "**钉法 = 数出现次数**：整个判据族里只有 WriteTruncating 一档交截断写（实到 " +
                occurrences(code, MUTATION_TRUNCATE) + " 枚）",
            1,
            occurrences(code, MUTATION_TRUNCATE),
        )
        assertEquals(
            "**钉法 = 数出现次数**：另外三档各交 LEAVE_UNTOUCHED（实到 " + occurrences(code, MUTATION_UNTOUCHED) +
                " 枚）—— 少一枚就是有一档「不落盘」其实偷偷开了流",
            3,
            occurrences(code, MUTATION_UNTOUCHED),
        )
        // 反证（跑在 JVM 侧，不靠 grep）：四档真交得出四枚不同结论
        val decisions = listOf(
            WakeUpExportGate.decide(18, 0, """{"courses":[{"day":1}]}""", true),
            WakeUpExportGate.decide(0, 3, """{"courses":[]}""", true),
            WakeUpExportGate.decide(2, 0, "  ", true),
            WakeUpExportGate.decide(18, 0, """{"courses":[{"day":1}]}""", false),
        )
        assertEquals(
            "运行时反证：四枚输入交出四枚不同档位（实到 " + decisions.map { it.javaClass.simpleName } + "）",
            4,
            decisions.map { it.javaClass.simpleName }.distinct().size,
        )
        assertEquals(
            "运行时反证：落盘模式两档（与 IcsTargetFileMutation 同数不同册）",
            2,
            WakeUpTargetFileMutation.entries.size,
        )
        assertEquals(WakeUpTargetFileMutation.TRUNCATE_AND_WRITE, decisions[0].mutation)
        assertEquals(WakeUpTargetFileMutation.LEAVE_UNTOUCHED, decisions[1].mutation)
        assertEquals(WakeUpTargetFileMutation.LEAVE_UNTOUCHED, decisions[2].mutation)
        assertEquals(WakeUpTargetFileMutation.LEAVE_UNTOUCHED, decisions[3].mutation)
    }

    // ─────────────── ④ 转义唯一落点：改前两枚病灶形在代码里零处 ───────────────

    @Test
    fun `转义只有一处落点 导出器里没有第二把尺`() {
        val raw = source(EXPORTERS)
        val code = blankComments(raw)
        val body = balancedBlock(code, TO_WAKE_UP, "ScheduleExporters.toWakeUpJson")

        for (needle in listOf(LEGACY_HELPER_NAME, LEGACY_ESCAPE_HAND, OLD_NAME_FIELD)) {
            assertEquals(
                "**钉法 = 抹注释找锚点**：$EXPORTERS 抹注释后「$needle」必须 **0** 处 —— 那是本卡的两枚病灶" +
                    "（改前那两行只认反斜杠与双引号，另加进不了别名的那枚字段取法）。\n" +
                    "  复算：把 $EXPORTERS 的 // 与块注释抹成空格后数「$needle」的枚数" +
                    "\n  实到枚数：" + occurrences(code, needle),
                0,
                occurrences(code, needle),
            )
            // 反套套逻辑：含注释的原文里逐枚恰好 1 处（KDoc 逐字引用着改前那一手）
            assertEquals(
                "**钉法 = 数出现次数（含注释原文）**：原文里「$needle」必须恰好 **1** 处（取证在注释里）。" +
                    "读到 0 = 取证被搬走，上面那个 0 就成了假绿；读到 >1 = 病灶回潮。实到：" +
                    occurrences(raw, needle),
                1,
                occurrences(raw, needle),
            )
        }

        assertEquals(
            "**钉法 = 数出现次数（抹注释）**：五枚用户字段（课程名 / 教师 / 教室 / 顶层学期名 / tableInfo 学期名）" +
                "加一枚装着 JSON 的 `time` 串，六枚全走内核「$KERNEL_CALL」⇒ `toWakeUpJson` 体内恰好 6 枚、" +
                "整枚文件也恰好 6 枚（体内 6 = 没有别的函数在转义，整文件 6 = 没有第二处转义落点）。" +
                "`time` 那一枚是本卡端到端格量出来的第二手病灶：串里的 `"` 没转义 ⇒ 整份文件在那一格就非法。" +
                "实到体内 " + occurrences(body, KERNEL_CALL) + " 枚 / 全文件 " + occurrences(code, KERNEL_CALL) + " 枚",
            6 to 6,
            occurrences(body, KERNEL_CALL) to occurrences(code, KERNEL_CALL),
        )
        assertEquals(
            "**钉法 = 数出现次数**：$EXPORTERS 里 `replace(` 在代码中恰好 **1** 枚（实到 " +
                occurrences(code, "replace(") + " 枚）—— 那一枚必须是下面这行逐字节：\n  " + COMPACT_LINE,
            1,
            occurrences(code, "replace("),
        )
        assertEquals(
            "**钉法 = 逐字节**：剩下那枚 `replace` 只能是 `compact` 那一手（摘时间里的冒号，" +
                "机器生成的 ASCII HH:mm，不是转义落点）：实到 " + occurrences(code, COMPACT_LINE) + " 枚",
            1,
            occurrences(code, COMPACT_LINE),
        )
        assertEquals(
            "**钉法 = 数出现次数**：N6 的落点逐字节在场 —— `toWakeUpJson` 体内「$DISPLAY_NAME_FIELD」恰好 1 枚" +
                "（实到 " + occurrences(body, DISPLAY_NAME_FIELD) + " 枚）：WakeUp 的课程名与 ICS 的 `title`" +
                "（`ScheduleOccurrences:29` 同一枚出处）同源，别名这才三条链都出场",
            1,
            occurrences(body, DISPLAY_NAME_FIELD),
        )
    }

    // ─────────────── ⑤ 内核自身的形状与纯度 ───────────────

    @Test
    fun `内核是表驱动的 且两枚判据文件都不读库不读钟`() {
        val files = listOf(
            Triple("JSON 文本层", TEXT, blankComments(source(TEXT))),
            Triple("落盘闸门", GATE, blankComments(source(GATE))),
        )
        for ((label, path, code) in files) {
            val offenders = source(path).lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .filter { it.contains("android.") || it.contains("androidx.") }
                .toList()
            assertEquals("$path 必须零 android / androidx import。实到：" + offenders, emptyList<String>(), offenders)
            for (needle in FORBIDDEN_READS) {
                assertEquals(
                    "**钉法 = 数出现次数（抹注释）**：$label 文件里「$needle」必须 0 处：读了它，" +
                        "「这份文本合不合法 / 这一趟该不该动盘」就不再是纯函数 —— 同一次导出的两次点击" +
                        "可能给出不同结论（本仓口径：这些留在调用点当参数递进来）。" +
                        "\n  实到枚数：" + occurrences(code, needle),
                    0,
                    occurrences(code, needle),
                )
            }
        }
        // 反套套逻辑：纯度那句承诺在两份判据文件的注释里都留痕
        assertEquals(
            "**钉法 = 数出现次数（含注释原文）**：闸门文件里那句纯度承诺恰好 1 处（读到 0 = 上面那串 0 没人负责）",
            1,
            occurrences(source(GATE), PURITY_PROMISE),
        )
        assertEquals(
            "**钉法 = 数出现次数（含注释原文）**：JSON 文本层文件里那句纯度承诺恰好 1 处",
            1,
            occurrences(source(TEXT), PURITY_PROMISE),
        )
        // 内核的表是公开的，枚数就是判据覆盖面（运行时反证，不靠 grep）
        assertEquals(7, WakeUpJsonText.SHORT_ESCAPES.size)
        assertEquals(32, WakeUpJsonText.CONTROL_RANGE.count())
        assertEquals(
            "sanitize 的折空格档与强制转义档必须是**同一枚区间**（一把尺，不另立一档）",
            WakeUpJsonText.CONTROL_RANGE,
            WakeUpJsonText.FOLD_TO_SPACE_RANGE,
        )
        assertEquals("十六进制数码表 16 枚且全小写（零 locale 面）", 16, WakeUpJsonText.HEX_DIGITS.length)
        assertTrue(WakeUpJsonText.HEX_DIGITS == WakeUpJsonText.HEX_DIGITS.lowercase())
        // 运行时反证（朝宽那臂就是死在这一格）：码位扫一遍，产出的字面量里裸 C0 必须 0 枚
        var swept = 0
        for (code in 0x00..0x1FF) {
            val hex = "0x" + code.toString(16)
            val literal = WakeUpJsonText.literal(code.toChar().toString())
            val inner = literal.substring(1, literal.length - 1)
            assertEquals(
                "$hex 的字面量正文里有裸控制字符（转义那一支被摘掉就是这个形状）：$literal",
                0,
                inner.count { it.code <= 0x1F },
            )
            swept++
        }
        assertEquals("扫过的码位枚数（0x00..0x1FF = 512 枚）", 512, swept)
    }

    // ─────────────── ⑥ 文案：三句各归各档（逐字节签名） ───────────────

    @Test
    fun `三句回执各归各档`() {
        val code = blankComments(source(COPY))
        for (signature in listOf(COPY_REFUSED, COPY_INCONSISTENT)) {
            assertEquals(
                "**钉法 = 逐字节**：文案函数签名逐字节一枚在场（恰好一枚）：「$signature」。" +
                    "改签名 = 那一档的句子不再由档位类型决定，档位与文案两头就会分家",
                1,
                occurrences(code, signature),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：「$HONEST_TAIL」恰好 3 枚（RefuseEmpty 两支 + RefuseInconsistent 一支）。" +
                "实到 " + occurrences(code, HONEST_TAIL) + " 枚。少一枚 = 有一档不动盘却不说这一手，" +
                "用户会以为屏幕后头那份文件已经换了内容",
            3,
            occurrences(code, HONEST_TAIL),
        )
        assertEquals(
            "**钉法 = 数出现次数**：成功句「$SUCCESS_HEAD」在本文件里仍恰好 4 枚（T181② 两枚 + T203 两档）。" +
                "实到 " + occurrences(code, SUCCESS_HEAD) + " 枚 —— 涨到 5 枚就是有人给「没写出去」那一档" +
                "又编了一句成功回执（审计 N4 后半句的形状）",
            4,
            occurrences(code, SUCCESS_HEAD),
        )
        assertEquals(
            "**钉法 = 数出现次数**：三句回执里「已导出」只许长在成功那一支 ⇒ 本文件「已导出」恰 4 枚、" +
                "一支不落盘的句子都不带它（实到 " + occurrences(code, "已导出") + " 枚）",
            4,
            occurrences(code, "已导出"),
        )
    }

    // ---------------- helpers（与 IcsExportGateWiringGuardTest 同写法） ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 从锚点那一句起按花括号配平取整块（含锚点自己）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到函数体的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /** 把 `//` 与块注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
    private fun blankComments(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
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

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}
