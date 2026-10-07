package com.buaa.schedule.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T229 ②（#303）静态守卫：备份提醒归并那两处的形状、内核纯度与版本号册子。
 *
 * 读的是 `app/src/main/java` 的工作树文本（那一条已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()` 的 `inputs`，T92①）；
 * 纯 JVM、零 android、零时钟、不读 res/ 与清单 ⇒ 不动构建脚本。
 * 读法与小工具抄同目录的 `BackupSemesterRestoreWiringGuardTest` 与 `IcsSectionPolicyGuardTest`。
 *
 * ## 钉法四家逐格点名
 *
 * 1. **逐字节** —— 第 ① 层：两处病灶的原文（`data.reminders.associate {` /
 *    `.associateBy { ImportPlanner.courseKey(it) }`）在全 `app/src/main` 各 0 处，
 *    而替换上的那三行接线各 1 处。
 * 2. **数出现次数（册子）** —— 第 ② 层：`BackupReminderPairing` 的引用落点册子只许三份文件；
 *    `remindersUnplaced` 那本账的册子与枚数（声明 / 交回 / 念出来）写死；
 *    旧参数名 `remindersByCourseKey` 全树 0 处（改名不留后路）。
 * 3. **抹注释找锚点** —— 第 ③ 层：`associate` 一族在恢复那两路上**含注释 / 抹注释两侧**分别钉 ——
 *    含注释那一把钉的是"旧形状必须在散文里留档"（不留档 = 下一个人不知道为什么要 groupBy），
 *    抹注释那一把钉的是"代码里 0 处"。两侧一起钉是因为本仓吃过"注释说会抓而尺不抓"的账。
 * 4. **读形状** —— 第 ④ 层：按花括号配平取 `restoreBackupDataInTx` 与 `exportBackup` 的体，
 *    断言"写侧带依据"与"读侧逐枚配对"各自长在**自己那一段体内**（搬家就红）；
 *    第 ⑤ 层取内核体数 `associate` 0 处 + 两枚确定序各 1 处。
 *
 * ## 两侧的红臂
 *
 * - **退回归并**：读侧换回 `associate` ⇒ 第 ① 层逐字节红、第 ③ 层代码侧从 0 变 2 红；
 * - **摘掉依据**：写侧 `fragmentWeeks = course.weeks,` 删掉 ⇒ 第 ④ 层体内那格红（第 ⑥ 层的
 *   字段声明格仍绿，所以两格互为备份）；
 * - **偷偷涨版本号**：`BACKUP_FORMAT_VERSION` 改成 3 ⇒ 第 ⑥ 层红（卡面要的是"照 T204 口径论证后
 *   不涨"，不是默认涨号）；
 * - **账搬家**：`remindersUnplaced` 只留声明不念出来 ⇒ 第 ② 层那把册子尺红。
 */
class BackupReminderPairingGuardTest {

    // ─────────────── ① 两处病灶逐字节 0 处、接线 1 处 ───────────────

    @Test
    fun `归并那两手逐字节零命中 替换上的接线各一枚`() {
        val byFile = readMainSources()

        assertEquals(
            "**钉法 = 逐字节**：「$OLD_SETTINGS_MERGE」在全 app/src/main 必须 0 处（实到 " +
                occurrencesOfAll(byFile, OLD_SETTINGS_MERGE) + " 处）：它就是 #303 的第一处原文 —— " +
                "同键的多枚设置后写吃前写，恢复后只有一枚片段领得到提醒",
            0,
            occurrencesOfAll(byFile, OLD_SETTINGS_MERGE),
        )
        assertEquals(
            "**钉法 = 逐字节**：「$OLD_ROWS_MERGE」在 `$REPOSITORY` 必须 0 处（实到 " +
                occurrences(source(REPOSITORY), OLD_ROWS_MERGE) + " 处）：那是 #303 的第二处原文" +
                "（同键的多枚片段只留一枚，留哪一枚由无 ORDER BY 的查询行序决定）。" +
                "尺子**收窄到这份文件**是因为同一手在 `$VIEW_MODEL_NAME` 的预览计数那三路（" +
                "add/change 的判定）是既有合法用法、不归本卡 —— 全树数会把别人的格子算成病灶，" +
                "那是「见 associate 就杀」的那一把松尺（同族教训写在 IcsSectionPolicyGuardTest 第 ③ 层）",
            0,
            occurrences(source(REPOSITORY), OLD_ROWS_MERGE),
        )
        for ((needle, path) in listOf(
            READ_WIRING to VIEW_MODEL,
            KERNEL_CALL to REPOSITORY,
            WRITE_WIRING to VIEW_MODEL,
        )) {
            assertEquals(
                "**钉法 = 逐字节**：「$needle」在 `$path` 恰好一处（实到 " + occurrences(source(path), needle) +
                    " 处）：枚数对但内容换了（比如把逐枚递进去换成先归并再递）上面那格会红，" +
                    "两格互为备份",
                1,
                occurrences(source(path), needle),
            )
        }
        assertEquals(
            "旧参数名 `remindersByCourseKey` 全树必须 0 处（实到 " +
                occurrencesOfAll(byFile, OLD_PARAM_NAME) + " 处）：那枚名字承诺的是" +
                "「一个键一条设置」，而这一卡改掉的正是那个承诺。留着名字不改行为 = 下一位读者" +
                "照着名字又把归并写回来",
            0,
            occurrencesOfAll(byFile, OLD_PARAM_NAME),
        )
    }

    // ─────────────── ② 引用与账本的落点册子 ───────────────

    @Test
    fun `内核引用只许三份文件 那本账的枚数与落点一起数`() {
        val byFile = readMainSources()

        assertEquals(
            "**钉法 = 数出现次数（落点册子）**：`$KERNEL_REF` 的引用落点必须恰好是三份文件" +
                "（内核本体 + 两份接线；实到 " + filesHolding(byFile, KERNEL_REF) + "）：\n  " +
                "多一枚 = 有人在别处又拼了一遍配对判据（两把尺）；少一枚 = 有一路被摘回了归并",
            listOf(KERNEL_NAME, REPOSITORY_NAME, VIEW_MODEL_NAME),
            filesHolding(byFile, KERNEL_REF),
        )
        assertEquals(
            "**钉法 = 数出现次数（册子）**：`$UNPLACED` 全 main 恰好 " + UNPLACED_TOTAL +
                " 处（实到 " + occurrencesOfAll(byFile, UNPLACED) + " 处，册子 " + ledger(byFile, UNPLACED) +
                "）：`$REPOSITORY_NAME` 那两枚是「Applied 里声明」与「从内核交回」，" +
                "`$VIEW_MODEL_NAME` 那两枚是「判 > 0」与「念进恢复文案」。" +
                "少任何一枚就是「账记了但没人念」或「念了但没人记」—— 两者都是本仓那一族静默病",
            UNPLACED_TOTAL,
            occurrencesOfAll(byFile, UNPLACED),
        )
        assertEquals(
            "**钉法 = 数出现次数（落点册子）**：`$UNPLACED` 只许长在这两份文件里（实到 " +
                filesHolding(byFile, UNPLACED) + "）",
            listOf(REPOSITORY_NAME, VIEW_MODEL_NAME),
            filesHolding(byFile, UNPLACED),
        )
        assertEquals(
            "`$KERNEL_CALL` 全 main 恰好一处（实到 " + occurrencesOfAll(byFile, KERNEL_CALL) +
                " 处）：配对判据只许有一枚调用点。第二枚调用点 = 第二次「归并 vs 逐枚」的口径分家",
            1,
            occurrencesOfAll(byFile, KERNEL_CALL),
        )
    }

    // ─────────────── ③ associate 一族：只许待在没被这一卡碰过的路上 ───────────────

    @Test
    fun `恢复那两路的 associate 形状零处而留档必须在`() {
        val restoreFromData = bodyAfter(source(VIEW_MODEL), READ_ANCHOR)
        assertEquals(
            "读侧 `restoreFromData` 体内 `$MERGE_NEEDLE` 必须 0 处（实到 " +
                occurrences(restoreFromData, MERGE_NEEDLE) + " 处）：#303 的原文之一就在这里。" +
                "同一份文件里那几枚**不归本卡**的 associate（按 id 建课程索引、按键做预览计数）" +
                "不许被算进来 ⇒ 所以这一格数的是**函数体**而不是整份文件",
            0,
            occurrences(restoreFromData, MERGE_NEEDLE),
        )
        val restoreTx = bodyAfter(source(REPOSITORY), RESTORE_ANCHOR)
        assertEquals(
            "写库侧 `restoreBackupDataInTx` 体内 `$MERGE_NEEDLE` 必须 0 处（实到 " +
                occurrences(restoreTx, MERGE_NEEDLE) + " 处）：另一枚原文在这里",
            0,
            occurrences(restoreTx, MERGE_NEEDLE),
        )
        for (path in listOf(VIEW_MODEL, REPOSITORY)) {
            assertTrue(
                "注释侧（含注释）：`$path` 的原文里必须**留着** #303 的说明（实到 " +
                    occurrences(rawSource(path), TALLY_ANCHOR) + " 处含「$TALLY_ANCHOR」）。" +
                    "拆掉病灶而不留理由，下一个人只会看到 pair/逐枚 upsert 而不知道为什么不能换回归并",
                occurrences(rawSource(path), TALLY_ANCHOR) >= 1,
            )
        }
    }

    // ─────────────── ④ 读形状：接线长在自己那一段体内 ───────────────

    @Test
    fun `写侧依据与读侧配对各长在自己的函数体内`() {
        val export = bodyAfter(source(VIEW_MODEL), EXPORT_ANCHOR)
        assertEquals(
            "**钉法 = 读形状**：`exportBackup` 体内 `$WRITE_WIRING` 恰好一处（实到 " +
                occurrences(export, WRITE_WIRING) + " 处）：片段依据必须在**编备份**那一段里带上。" +
                "搬到别的函数里（比如只在预览里带、导出时不带）用户拿到的文件还是那枚缺依据的老形状",
            1,
            occurrences(export, WRITE_WIRING),
        )
        assertEquals(
            "`exportBackup` 体内对 `ImportPlanner.courseKey` 的引用恰好一处（实到 " +
                occurrences(export, "ImportPlanner.courseKey(course)") +
                " 处）：键本体不许在这里重写一遍，它只许读 domain 里那唯一一枚",
            1,
            occurrences(export, "ImportPlanner.courseKey(course)"),
        )

        val restore = bodyAfter(source(REPOSITORY), RESTORE_ANCHOR)
        assertEquals(
            "**钉法 = 读形状**：`restoreBackupDataInTx` 体内 `$KERNEL_CALL` 恰好一处（实到 " +
                occurrences(restore, KERNEL_CALL) + " 处）",
            1,
            occurrences(restore, KERNEL_CALL),
        )
        assertEquals(
            "体内逐枚 upsert 那一手恰好一处（`$UPSERT_LOOP`，实到 " + occurrences(restore, UPSERT_LOOP) +
                " 处）：改前这里是 `remindersByCourseKey.forEach { (key, setting) -> courseByKey[key]…}`，" +
                "两处形状合起来才拦得住「把归并换个名字再写一遍」",
            1,
            occurrences(restore, UPSERT_LOOP),
        )
        assertEquals(
            "`$UNPLACED_HANDOVER` 必须在同一枚体内（实到 " + occurrences(restore, UNPLACED_HANDOVER) +
                " 处）：账从内核交回到 Applied 为止这一跳若断了，第 ② 层那把册子尺会绿而屏幕一个字不念",
            1,
            occurrences(restore, UNPLACED_HANDOVER),
        )
        assertEquals(
            "体内 `!!` 必须 0 处（实到 " + occurrences(restore, "!!") + " 处）",
            0,
            occurrences(restore, "!!"),
        )
    }

    // ─────────────── ⑤ 内核纯度：判据不许在 JVM 侧生根 ───────────────

    @Test
    fun `内核零 android 零时钟 自己也不许用会吃后写的键归并`() {
        val kernel = source(KERNEL)

        assertEquals(
            "内核必须**零 android / androidx import**（实到 " + occurrences(sourceRaw(KERNEL), "import android") +
                " 处）：配对判据要能在 JVM 侧逐格断言，落库与查询那些事实由调用点当参数递进来",
            0,
            occurrences(sourceRaw(KERNEL), "import android") + occurrences(sourceRaw(KERNEL), "import androidx"),
        )
        val clockHits = occurrences(kernel, "System.currentTimeMillis") + occurrences(kernel, "LocalDate.now(") +
            occurrences(kernel, "LocalDateTime.now(") + occurrences(kernel, "Calendar.")
        assertEquals(
            "内核里读时钟的面必须 0 处（合计实到 $clockHits 处）：配对只看内容与片段依据，" +
                "读一次时钟就让「恢复同一份备份两次结果不同」变成设计而不是事故",
            0,
            clockHits,
        )
        for (needle in listOf(".associate(", ".associateBy(", "associateBy(")) {
            assertEquals(
                "内核里 `$needle` 必须 0 处（实到 " + occurrences(kernel, needle) +
                    " 处）：#303 的病就是「以键建表时后写吃前写」，判据本体里再出现同一手就是自我否定",
                0,
                occurrences(kernel, needle),
            )
        }
        for (decl in listOf(
                "data class Setting(",
                "data class Row(",
                "enum class Basis {",
                "data class Assignment(",
                "data class Outcome(",
                "fun pair(settings: List<Setting>, rows: List<Row>): Outcome",
            )
        ) {
            assertEquals(
                "内核的声明形状逐字一枚：「$decl」（实到 " + occurrences(kernel, decl) + " 处）",
                1,
                occurrences(kernel, decl),
            )
        }
        for (basis in listOf("        FragmentMatched,", "        SignatureUnmatched,", "        LegacyNoSignature,")) {
            assertEquals(
                "三档结论各自一枚声明（`$basis` 实到 " + occurrences(kernel, basis) +
                    " 处）：本仓口径是「兜底不许比判据更安静」—— 少一档就是有一路退档没人知道",
                1,
                occurrences(kernel, basis),
            )
        }
        assertEquals(
            "两枚确定序一共三处使用（`sortedWith(SETTING_ORDER)` 实到 " +
                occurrences(kernel, "sortedWith(SETTING_ORDER)") + " 处 + " +
                "`sortedWith(ROW_ORDER)` 实到 " + occurrences(kernel, "sortedWith(ROW_ORDER)") +
                " 处 = 3）：#303 那句「谁活下来由无 ORDER BY 查询的行序决定」就是靠这两手拆掉的 —— " +
                "两侧都先排成与输入行序无关的确定序再配。枚数是 3 而不是 2 的原因：设置那把序" +
                "用在两处（组内配对与 unplaced 台账），少一处就是那本账也改成按文件条目序念",
            3,
            occurrences(kernel, "sortedWith(SETTING_ORDER)") + occurrences(kernel, "sortedWith(ROW_ORDER)"),
        )
    }

    // ─────────────── ⑥ 版本号与字段形状：新增可选键不涨号 ───────────────

    @Test
    fun `片段依据是带默认值的可选字段而版本号原样不动`() {
        val models = source(MODELS)

        assertEquals(
            "`$FRAGMENT_FIELD` 在 `$MODELS` 恰好一处（实到 " + occurrences(models, FRAGMENT_FIELD) +
                " 处）：**可空 + 带默认值**这两样一起才叫「老备份缺键 ⇒ null ⇒ 退档」，" +
                "换成非空或换成不带默认值就是「老备份直接解不开」",
            1,
            occurrences(models, FRAGMENT_FIELD),
        )
        assertEquals(
            "**卡面那条红线**：`const val BACKUP_FORMAT_VERSION = $EXPECTED_VERSION` 逐字节一处（实到 " +
                occurrences(models, VERSION_LINE) + " 处）。涨号的口径照 T204（`BackupData.semesters`、" +
                "`BackupCourse.credit` 两格）：**新增带默认值的可选字段不涨**，因为两边都认得这个形状 " +
                "—— 老应用靠 `ignoreUnknownKeys` 忽略新键（旧行为原样），新应用靠默认值读回老文件。" +
                "涨到 3 只换来「老应用对这份新文件直接拒绝恢复」这一条更窄的路。" +
                "真该涨号的是「改了必填字段的语义 / 删了字段 / 改了键的构成让老文件配不上新键」那一类，" +
                "这一格不是：老备份缺键时读侧有确定的退档，一枚提醒都不许多丢",
            1,
            occurrences(models, VERSION_LINE),
        )
        assertEquals(
            "整行涨号的那一形必须 0 处（实到 " + occurrences(models, "BACKUP_FORMAT_VERSION = 3") + " 处）",
            0,
            occurrences(models, "BACKUP_FORMAT_VERSION = 3"),
        )
        assertEquals(
            "`$MODELS` 里 [BackupReminder] 段首那句「兼容性判据」标题必须在（实到 " +
                occurrences(rawSource(MODELS), COMPAT_HEADING) + " 处）：卡面要的那条判据得写在盘上，" +
                "不能只活在回执里",
            1,
            occurrences(rawSource(MODELS), COMPAT_HEADING),
        )
    }

    // ─────────────── ⑦ 反套套逻辑：探测器认得改前那一形 ───────────────

    @Test
    fun `切块器与归并探测器都不是空转`() {
        val before = """
            remindersByCourseKey = data.reminders.associate {
                it.courseKey to (it.enabled to it.advanceMinutes)
            },
        """.trimIndent()
        assertEquals(
            "内联正对照｜朝松：改前那一形喂进探测器必须被抓成 1 处（实到 " +
                occurrences(blankCommentsKeepingLiterals(before), OLD_SETTINGS_MERGE) + " 处）",
            1,
            occurrences(blankCommentsKeepingLiterals(before), OLD_SETTINGS_MERGE),
        )
        val beforeRows = """
            val courseByKey = restoredCourses
                .map { it.toDomain() }
                .associateBy { ImportPlanner.courseKey(it) }
        """.trimIndent()
        assertEquals(
            "内联正对照｜同一枚尺的另一手（课程侧 associateBy）也必须被抓成 1 处（实到 " +
                occurrences(blankCommentsKeepingLiterals(beforeRows), OLD_ROWS_MERGE) + " 处）：" +
                "第 ① 层那两格对改前的盘面本来就是红的，不是套套逻辑",
            1,
            occurrences(blankCommentsKeepingLiterals(beforeRows), OLD_ROWS_MERGE),
        )
        assertTrue(
            "取块工具必须真能配平：`$REPOSITORY_NAME` 的恢复函数体非空（实到 " +
                bodyAfter(source(REPOSITORY), RESTORE_ANCHOR).length + " 个字符）",
            bodyAfter(source(REPOSITORY), RESTORE_ANCHOR).length > 2000,
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    /** 从锚点起往后找第一枚左花括号，按配平取出那一段函数体 */
    private fun bodyAfter(code: String, anchor: String): String {
        val hits = indexOfAll(code, anchor)
        assertEquals("`$anchor` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        val open = code.indexOf('{', hits.first())
        assertTrue("锚点「$anchor」之后找不到左花括号：签名形状变了，切不出一段体", open >= 0)
        return braceBodyFrom(code, open)
    }

    private fun filesHolding(byFile: Map<String, String>, needle: String): List<String> =
        byFile.filter { occurrences(it.value, needle) > 0 }.keys.toList().sorted()

    private fun occurrencesOfAll(byFile: Map<String, String>, needle: String): Int =
        byFile.values.sumOf { occurrences(it, needle) }

    private fun ledger(byFile: Map<String, String>, needle: String): String =
        byFile.entries.toList().sortedBy { it.key }
            .filter { occurrences(it.value, needle) > 0 }
            .joinToString(prefix = "{", postfix = "}") { "${it.key}=${occurrences(it.value, needle)}" }

    private fun source(relativeFromMainJava: String): String =
        blankCommentsKeepingLiterals(rawSource(relativeFromMainJava))

    private fun sourceRaw(relativeFromMainJava: String): String = rawSource(relativeFromMainJava)

    private fun rawSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun readMainSources(): Map<String, String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        val out = linkedMapOf<String, String>()
        files.forEach {
            check(!out.containsKey(it.name)) { "main 源码里有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n"))
        }
        assertEquals("扫 main 源码的文件数与建出来的册子条目数不等", files.size, out.size)
        return out
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        if (needle.isEmpty()) return hits
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') { "锚点末尾不是花括号：切不出一段体（实到「${source.getOrNull(open)}」）" }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

    private fun matchingBrace(src: String, open: Int): Int? {
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信） */
    private fun blankCommentsKeepingLiterals(source: String): String {
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

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val MODELS = "com/buaa/schedule/data/backup/BackupModels.kt"
        const val KERNEL = "com/buaa/schedule/data/backup/BackupReminderPairing.kt"
        const val KERNEL_NAME = "BackupReminderPairing.kt"

        /** 引用普查用的针（标识符本身，不是文件路径） */
        const val KERNEL_REF = "BackupReminderPairing"
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val REPOSITORY_NAME = "ScheduleRepository.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val VIEW_MODEL_NAME = "ScheduleViewModel.kt"

        /** 第 ① 层：两处病灶原文 + 三处接线 */
        const val OLD_SETTINGS_MERGE = "data.reminders.associate {"
        const val OLD_ROWS_MERGE = ".associateBy { ImportPlanner.courseKey(it) }"
        const val OLD_PARAM_NAME = "remindersByCourseKey"
        const val READ_WIRING = "backupReminders = data.reminders.map { BackupReminderPairing.settingOf(it) },"
        const val WRITE_WIRING = "fragmentWeeks = course.weeks,"
        const val KERNEL_CALL = "BackupReminderPairing.pair("

        /** 第 ② 层：账本 */
        const val UNPLACED = "remindersUnplaced"
        const val UNPLACED_TOTAL = 4
        const val UNPLACED_HANDOVER = "remindersUnplaced = pairing.unplaced.size,"

        /** 第 ③ 层 */
        const val MERGE_NEEDLE = ".associate"
        const val TALLY_ANCHOR = "#303"

        /** 第 ④ 层：取块锚点 */
        const val EXPORT_ANCHOR = "suspend fun exportBackup(): String = withContext(Dispatchers.Default) {"
        const val READ_ANCHOR = "private suspend fun restoreFromData("
        const val RESTORE_ANCHOR = "private suspend fun restoreBackupDataInTx("
        const val UPSERT_LOOP = "pairing.assignments.forEach { assignment ->"

        /** 第 ⑥ 层 */
        const val FRAGMENT_FIELD = "val fragmentWeeks: List<Int>? = null,"
        const val VERSION_LINE = "const val BACKUP_FORMAT_VERSION = 2"
        const val EXPECTED_VERSION = 2
        const val COMPAT_HEADING = "## 兼容性判据"
    }
}
