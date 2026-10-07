package com.buaa.schedule.core.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T215③-B：静态守卫 —— `Personalization.load()` 读的偏好键与 `save()` 写的偏好键**今天靠两份手抄清单
 * 配平，零守卫**（卡面病灶 B）。这里只做源码核对，不改任何 main 源码的那一半。
 *
 * ## 判据的形状
 *
 * 从 `fun load(context: Context) {` 与 `fun save(context: Context) {` 两枚函数体里各自扫出
 * 「第一枚实参是字符串字面量」的读键（`contains(` / `getString(` / `getInt(` / `getLong(` /
 * `getFloat(` / `getBoolean(`）与写键（`putString(` / `putInt(` / `putLong(` / `putFloat(` /
 * `putBoolean(` / `remove(`），再断：
 * - `save 写 ⊆ load 读`，且 `load 读 − save 写` 只能是白名单里那两枚（差集必须逐字等于白名单，
 *   多一枚少一枚都红）；
 * - `load 写`（收敛旗标那一手）与 `save 读`（壁纸开关变更判定那一手）各自也是一份封闭清单，
 *   冒出新形状同样红。
 *
 * ## 白名单（今天真实存在的两枚例外，各自为什么合法）
 *
 * - `glass_enabled`：**只读不写**。旧版本用一枚布尔存玻璃开关，`load` 读它把老档收敛成
 *   [Personalization.glassTier] 的两档（`hasLegacy` 那两支），而当前代码一律写新键 `glass_tier`。
 *   若让 `save` 把它写回去，这枚死键就永久留在偏好里，而且下一次读盘的 `hasLegacy` 会被自己造出来
 *   ——「用户从没存过玻璃设置」与「刚存过一次」两种状态就此不可分辨。
 * - `week_grid_mode_declared`：**只在 `load` 里写一次、永不由 `save` 写**。它是一枚一次性收敛旗标
 *   （切换入口曾是那颗无字面图标，落盘的 1 多半是误触 ⇒ 新版第一次读盘把网格统一收回节次行，
 *   此后正常读写 `week_grid_mode`）。它不是内存态，`save()` 的契约是「把内存态整体落盘」，
 *   给它加一枚 `putBoolean` 只会让"这枚旗标什么时候算立起来"多一个写者。
 *
 * 四家钉法逐格点名（每条断言的消息里都写自己用的是哪一种）：
 * **逐字节**（原文针，改一个字符就红）、**数出现次数**（枚数尺，含"读空文件也给 0"的凭据格）、
 * **读形状**（按函数体切块 + 集合算式）、**数出现次数（全 main 册子）**（树扫，抓"第三枚文件里
 * 冒出来的调用点"——那是前三家都看不见的形状）。
 */
class PersonalizationLoadSaveKeyPairingGuardTest {

    /**
     * 主判据（钉法 = **读形状**：按函数体切块、再对两个键集合做差集算式）：
     * `save` 写的每一枚键都被 `load` 读回，`load` 读的每一枚键要么被 `save` 写、要么在白名单里。
     *
     * 两头都钉：**漏写 `save`** = 用户改了这枚偏好、重启回到默认（静默不持久化）→ 它出现在
     * 「load 读 − save 写」差集里、又不是白名单那两枚 ⇒ 本格红；
     * **漏写 `load`** = 盘上存了却每次冷启动都不读（写进去的那一手白写）→ 它出现在
     * 「save 写 − load 读」差集里 ⇒ 第二格红。
     */
    @Test
    fun everyKeySaveWritesIsReadBackByLoadAndLoadReadsNothingUnwrittenExceptTheTwoLegacyKeys() {
        val load = functionBody(PERSONALIZATION, LOAD_HEAD)
        val save = functionBody(PERSONALIZATION, SAVE_HEAD)
        val readKeys = firstStringArgs(load, READ_CALLS).toSet()
        val writeKeys = firstStringArgs(save, WRITE_CALLS).toSet()
        val onlyRead = (readKeys - writeKeys).sorted()
        assertEquals(
            "`load` 读了、`save` 不写的键不再恰好是白名单那两枚（实测 ${onlyRead}；白名单 ${LOAD_ONLY_ALLOW_LIST.sorted()}）。" +
                "**多出来的一枚** = 新字段只写了读的那一半：用户改了它、下次冷启动直接回到默认值，" +
                "界面上看着生效、偏好文件里根本没有它 —— 这一族本仓已经为「成对字段漏清」修过 " +
                "#149 / T106–T110 / T116，偏好键的 load↔save 配对是它没盖到的另一头。" +
                "**少一枚** = 白名单里那枚例外不再成立（`glass_enabled` 收敛被删？`week_grid_mode_declared` " +
                "改成常规读写？），把这段说明一起改掉再来抬本格的档位。",
            LOAD_ONLY_ALLOW_LIST.sorted(),
            onlyRead,
        )
        val onlyWritten = (writeKeys - readKeys).sorted()
        assertEquals(
            "`save` 写了、`load` 不读的键必须一枚都没有（实测 $onlyWritten）—— 那一枚的落盘从此没人念，" +
                "每次冷启动都从默认值起，而且它 permanently 占着偏好文件：" + lineHints(save, onlyWritten),
            emptyList<String>(),
            onlyWritten,
        )
        assertEquals(
            "配平成功的那一枚数（`save` 写、`load` 也读）不是 16 —— 上面两格各自是差集，这一格是**总数**，" +
                "防的是「两枚数一起挪、差集照样为空」那种巧合",
            PAIRED_KEYS,
            (readKeys intersect writeKeys).size,
        )
    }

    /**
     * 枚数尺（钉法 = **数出现次数**，抹注释后数调用次数与去重后的键数）：
     * 两枚清单今天各多长。这一格不是判据，是**上面那枚判据的凭据** —— 函数体切错、
     * 正则一条不中都能让差集"为空"，只有枚数对得上才证明扫到的是那两份清单。
     */
    @Test
    fun theTwoListsAreStillTheSizesTheGuardReadsAndNoCallSiteGoesUnmatched() {
        val load = functionBody(PERSONALIZATION, LOAD_HEAD)
        val save = functionBody(PERSONALIZATION, SAVE_HEAD)
        val loadReads = firstStringArgs(load, READ_CALLS)
        assertEquals(
            "`load` 体内的读键调用次数不再是 20（18 枚去重 + `glass_tier` 与 `glass_enabled` 各被读两遍：" +
                "一枚 `contains` 判有没有存过、一枚 `get` 取值）。**多一枚** = 添了字段没在本守卫挂号；" +
                "**少一枚** = 有半条链换了载体（改成 data class 整枚读写？键名改成常量引用？）——" +
                "换了载体本守卫的字面判据就跟不上了，要回来重写这一族",
            LOAD_READ_CALLS,
            loadReads.size,
        )
        assertEquals(
            "`load` 读到的**去重**键数不再是 18（16 枚配平 + 2 枚白名单）",
            LOAD_DISTINCT_KEYS,
            loadReads.toSet().size,
        )
        val saveWrites = firstStringArgs(save, WRITE_CALLS)
        assertEquals(
            "`save` 体内的写键调用次数不再是 16 —— 卡面说的「现写 16 个键」量的就是这一枚数",
            SAVE_WRITE_CALLS,
            saveWrites.size,
        )
        assertEquals(
            "`save` 写了 16 次、去重却不足 16 枚 = 同一枚键被写了两遍（后一次覆盖前一次，" +
                "中间那次落地是死代码；或者有人把新字段的落盘抄成了两行）",
            SAVE_WRITE_CALLS,
            saveWrites.toSet().size,
        )
        // 反套套逻辑：整份文件里 `put` 与 `get` 的字面调用总数要等于两枚函数体里数到的，
        // 否则"第三枚函数体里也有一份清单"就是本守卫没扫到的宇宙（save 的 wallpaperChanged 判定
        // 长在 load/save 之外的那一行，今天不存在，这条就是它的探测器）。
        val wholeFile = blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION))
        val callsAnywhere = countCallSites(wholeFile, READ_CALLS + WRITE_CALLS)
        assertEquals(
            "Personalization.kt 全文的 `get*(` / `put*(` / `contains(` / `remove(` 调用点不再是 " +
                "${LOAD_READ_CALLS + LOAD_WRITE_CALLS + SAVE_WRITE_CALLS + SAVE_READ_CALLS} 枚" +
                "（实到 $callsAnywhere）—— 多出来的一枚在 load/save 两枚函数体之外，" +
                "本守卫切的两块就不是这枚偏好的全部宇宙（例如有人新开了第三枚读写函数、" +
                "或在 helper 里直接读同一份 prefs）",
            LOAD_READ_CALLS + LOAD_WRITE_CALLS + SAVE_WRITE_CALLS + SAVE_READ_CALLS,
            callsAnywhere,
        )
    }

    /**
     * 白名单自己也得挣（钉法 = **读形状** + **数出现次数**）：两枚例外今天仍按各自的机制活着。
     *
     * - `glass_enabled` 在 `load` 里恰两枚读点（`contains` + `getBoolean`）、在 `save` 里 0 枚、
     *   并且**全 main 只有 Personalization.kt 一枚文件提到它**（组件那一路不读它）；
     * - `week_grid_mode_declared` 在 `load` 里恰一枚**写**点（`putBoolean`，长在 `prefs.edit` 那一档）
     *   与一枚读点（`getBoolean`，判要不要收敛）、在 `save` 里 0 枚。
     *
     * 这一格防的是"白名单被当成免检通道"：谁把例外转正（`save` 开始写它），本格与第一格一起红。
     */
    @Test
    fun theTwoAllowListedKeysStillEarnTheirExceptionsByTheirOwnShapes() {
        val load = functionBody(PERSONALIZATION, LOAD_HEAD)
        val save = functionBody(PERSONALIZATION, SAVE_HEAD)
        assertEquals(
            "钉法 = 数出现次数：`glass_enabled` 在 load 里的读点不再是 2 枚（contains 判有没有 + getBoolean 取值）",
            2,
            occurrencesOfKey(load, LEGACY_GLASS_ENABLED, READ_CALLS),
        )
        assertEquals(
            "钉法 = 数出现次数：`glass_enabled` 在 save 里必须继续是 0 枚 —— 它一旦被写回去，" +
                "「hasLegacy」就变成当前代码自己造的假信号（老档收敛与新档存盘不可分辨）",
            0,
            occurrencesOfKey(save, LEGACY_GLASS_ENABLED, READ_CALLS + WRITE_CALLS),
        )
        assertEquals(
            "钉法 = 数出现次数：`week_grid_mode_declared` 在 load 里写一次（那枚一次性收敛旗标）",
            1,
            occurrencesOfKey(load, DECLARED_FLAG, WRITE_CALLS),
        )
        assertEquals(
            "钉法 = 数出现次数：同一枚旗标在 load 里也读一次（`if (prefs.getBoolean(…, false))` 那判据）",
            1,
            occurrencesOfKey(load, DECLARED_FLAG, READ_CALLS),
        )
        assertEquals(
            "钉法 = 数出现次数：它在 save 里必须继续是 0 枚 —— `save()` 的契约是「把内存态整体落盘」，" +
                "这枚旗标不是内存态；写它就多一个「什么时候算立起来」的写者",
            0,
            occurrencesOfKey(save, DECLARED_FLAG, READ_CALLS + WRITE_CALLS),
        )
        assertEquals(
            "钉法 = 数出现次数（全 main 册子）：提到 `glass_enabled` 的文件应当只有 Personalization.kt 一枚 —— " +
                "第二枚文件（尤其是组件那一路）开始读同一枚老键，本卡的白名单与收敛判据都要按新读者重判",
            listOf(PERSONALIZATION),
            mainFilesMentioning("\"glass_enabled\""),
        )
        // 靶子（钉法 = 逐字节）：两枚例外各引一次原文，防止"数到 0"是因为扫的不是这块
        assertEquals("钉法 = 逐字节：老玻璃开关那枚读法原文还在（load 的 hasLegacy 那一手）：",
            1, occurrences(load, "prefs.getBoolean(\"glass_enabled\", false)"))
        assertEquals("钉法 = 逐字节：一次性收敛旗标那次写原文还在（load 的 prefs.edit 那一档）：",
            1, occurrences(load, "putBoolean(\"week_grid_mode_declared\", true)"))
        assertEquals("钉法 = 逐字节：那档里连带的网格收敛写原文还在（同一次 edit 落两枚键）：",
            1, occurrences(load, "putInt(\"week_grid_mode\", WEEK_GRID_PERIOD)"))
    }

    /**
     * `glass_alpha` 没有第二把读盘的尺子，与本守卫的**宇宙边界**（钉法 = **数出现次数（全 main 册子）** +
     * 逐字节 + 读形状）。
     *
     * 前半是本卡 A 那一半的事实凭据：那枚键今天只长在 Personalization.kt 里，组件那一路读的是
     * `WidgetGlassSource` 自己的键，不抄这枚数 ⇒ 在读盘处补的夹取盖得住所有读者。
     * 后半如实划界：同一份偏好文件 `schedule_settings` 由十几枚文件各写各的键，本卡这两份清单
     * 只覆盖 `Personalization` 自己那 16 枚，别的键的配对不归本守卫（要归就得先并清单，
     * 那时"枚数那格"会先红）。
     */
    @Test
    fun theAlphaKeyHasNoSecondReaderAndTheseTwoListsCoverOnlyPersonalizationsOwnKeys() {
        assertEquals(
            "钉法 = 数出现次数（全 main 册子）：提到 \"glass_alpha\" 的文件不再是恰好 Personalization.kt 一枚" +
                "（实到 ${mainFilesMentioning("\"glass_alpha\"")}）—— 第二枚文件就是第二把尺，" +
                "本卡在读盘处补的夹取对它不作数，必须回来按新读者重判",
            listOf(PERSONALIZATION),
            mainFilesMentioning("\"glass_alpha\""),
        )
        val save = functionBody(PERSONALIZATION, SAVE_HEAD)
        assertEquals(
            "钉法 = 逐字节：save 落那枚键的原文（把**内存态**写回去，不是把盘上的数抄一遍）：",
            1,
            occurrences(save, "putFloat(\"glass_alpha\", cardAlpha)"),
        )
        val whole = blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION))
        assertEquals(
            "钉法 = 数出现次数：`getSharedPreferences(` 在这颗文件里必须是 2 枚（load 一枚 + save 一枚），" +
                "第三枚 = 又开了一条不经这两份清单的读写通道",
            2,
            occurrences(whole, "getSharedPreferences("),
        )
        assertEquals(
            "钉法 = 数出现次数（全 main 册子）：念 `schedule_settings` 这枚**文件名**的文件枚数变了（实到 " +
                "${mainFilesMentioning("\"schedule_settings\"").size}）。这一格钉的是**本守卫的宇宙边界**：" +
                "同一份偏好文件今天由 13 枚文件各写各的键（FirstRun / MainActivity / 两枚提醒接收者 / " +
                "IslandFocusTemplate / ScheduleViewModel / SettingsScreen / UpdateCheck / 组件那三家 …），" +
                "而本卡的两份清单只覆盖 `Personalization` 自己那 16 枚 —— 别的键的 load↔save 配平不在这里，" +
                "谁把它们并进这两枚函数体（枚数那格会先红），本守卫的判据就要按新的宇宙重划",
            SCHEDULE_SETTINGS_FILES,
            mainFilesMentioning("\"schedule_settings\"").size,
        )
        assertFalse(
            "钉法 = 读形状：`save` 体内不该出现 `remove(` —— 今天这枚函数只整体落盘、不撤键；" +
                "开始撤就得问「load 那边谁把它读回来」",
            save.contains("remove("),
        )
    }

    // ---- 源码核对小工具（与 ui/CalendarSyncTargetPairingGuardTest 同一把刀）----

    /**
     * 从 [header]（**含函数体那枚开括号**）起、按花括号配平切出的函数体（含头行，止于配对的那个 `}`）。
     *
     * 在**抹注释、留字面量**的副本上切：位置与原文件逐位一致，所以报错里的行号可信。
     * 头行本身只许出现一次（两枚同名重载会把宇宙切错）；切不出就抛 —— 静默交回空串
     * 会让上面所有"差集为空"的判据一起假绿。
     */
    private fun functionBody(relative: String, header: String): String {
        val source = blankCommentsKeepingLiterals(readMainSource(relative))
        val head = source.indexOf(header)
        check(head >= 0) { "找不到函数头「$header」：$relative 改过签名或换了载体，本守卫要跟着改" }
        check(source.indexOf(header, head + 1) < 0) { "函数头「$header」在这颗文件里出现两枚：本守卫按第一枚切块" }
        var depth = 1
        var cursor = head + header.length
        var inString = false
        var escaped = false
        while (cursor < source.length) {
            val c = source[cursor]
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
                    if (depth == 0) return source.substring(head, cursor + 1)
                }
            }
            cursor++
        }
        throw IllegalStateException("「$header」的花括号没配平：函数体切不出来")
    }

    /**
     * [calls] 里每一枚「`名字(`」之后**紧跟的第一枚字符串字面量**（= 那个调用的第一实参）。
     *
     * 只认"第一枚实参"是关键：`prefs.edit { putBoolean("k", v) }` 与
     * `prefs.getFloat("k", default)` 里键名都在第一位，而第二枚实参（默认值、值本身）
     * 才是今天会写成字面量的地方（`WEEK_GRID_PERIOD` 是符号、`Int.MIN_VALUE` 是符号）——
     * 所以这里既不会把默认值误当键，也不会因为默认值换了写法而漏数。
     * 字面量前头还有空格的形状（`getFloat( "k"`）照收；名字与左括号之间有空格就不认（本仓没这种写法）。
     */
    private fun firstStringArgs(body: String, calls: List<String>): List<String> {
        val hits = mutableListOf<Pair<Int, String>>()
        for (name in calls) {
            val token = "$name("
            var from = 0
            while (true) {
                val at = body.indexOf(token, from)
                if (at < 0) break
                from = at + token.length
                check(at == 0 || !(body[at - 1].isLetterOrDigit() || body[at - 1] == '_')) {
                    "「$token」前头还接着一个标识符字符（'${body[at - 1]}'）= 匹配到了别的名字尾巴上" +
                        "（例如 getSharedPreferences( 里含 getString( 那一形）：本守卫的调用名单要重看"
                }
                var cursor = at + token.length
                while (cursor < body.length && body[cursor].isWhitespace()) cursor++
                if (cursor < body.length && body[cursor] == '"') {
                    val close = body.indexOf('"', cursor + 1)
                    check(close > cursor) { "那一处的字符串字面量没闭合：$token" }
                    hits += at to body.substring(cursor + 1, close)
                }
            }
        }
        return hits.sortedBy { it.first }.map { it.second }
    }

    /** [whole] 里所有 [calls] 的调用点枚数（不管第一实参是不是字面量）：反套套逻辑那格用 */
    private fun countCallSites(whole: String, calls: List<String>): Int =
        calls.sumOf { name -> occurrences(whole, "$name(") }

    /** [body] 里第一实参为 [key] 的调用枚数（[calls] 限定读 / 写 / 两者） */
    private fun occurrencesOfKey(body: String, key: String, calls: List<String>): Int =
        firstStringArgs(body, calls).count { it == key }

    private fun lineHints(body: String, keys: List<String>): String =
        if (keys.isEmpty()) "" else "\n" + keys.joinToString("") { "  键「$it」\n" } + "\n函数体：\n$body"

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private fun readMainSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 全仓 `app/src/main/java` 里（抹注释后）仍然提到 [needle] 的文件，相对路径、正斜杠、已排序 */
    private fun mainFilesMentioning(needle: String): List<String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，扫描路径不对", files.isNotEmpty())
        return files.filter { needle in blankCommentsKeepingLiterals(it.readText()) }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .sorted()
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

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（行号因此仍然可信）。
     * 同 `CalendarSyncTargetPairingGuardTest` 那枚同名工具：本卡 Personalization.kt 的 KDoc 里
     * 写着 `wallpaper_uri` 与 `putBoolean`，不抹注释的话全 main 册子那两格会多算一枚文件。
     */
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

    private companion object {
        const val PERSONALIZATION = "com/buaa/schedule/core/designsystem/Personalization.kt"
        const val LOAD_HEAD = "fun load(context: Context) {"
        const val SAVE_HEAD = "fun save(context: Context) {"

        /** 读键的调用名（第一实参 = 键名）。`getSharedPreferences` 不在其列：它的第一实参是文件名 */
        val READ_CALLS = listOf("contains", "getString", "getInt", "getLong", "getFloat", "getBoolean")

        /** 写键的调用名 */
        val WRITE_CALLS = listOf("putString", "putInt", "putLong", "putFloat", "putBoolean", "remove")

        const val LEGACY_GLASS_ENABLED = "glass_enabled"
        const val DECLARED_FLAG = "week_grid_mode_declared"

        /** 白名单 = 今天真实存在的两枚「只读不写」例外，理由见类头 */
        val LOAD_ONLY_ALLOW_LIST = listOf(LEGACY_GLASS_ENABLED, DECLARED_FLAG)

        // ---- 枚数档位（量于本卡盘面）----
        const val LOAD_READ_CALLS = 20
        const val LOAD_DISTINCT_KEYS = 18
        const val LOAD_WRITE_CALLS = 2
        const val SAVE_WRITE_CALLS = 16
        const val SAVE_READ_CALLS = 1
        const val PAIRED_KEYS = 16

        /** 念 `schedule_settings` 这枚文件名的文件枚数（抹注释后数；ColdStartRebuild 只在注释里提它） */
        const val SCHEDULE_SETTINGS_FILES = 12
    }
}
