package com.buaa.schedule.domain.schedule

/**
 * WakeUp 导出件的 JSON 文本层（T277 审计 N2 → T279①）：把一枚**用户字段**折成带引号的合法 JSON 字面量。
 *
 * ## 病在哪
 *
 * 改前那一手长在 `ScheduleExporters` 的私有 `jsonStr` 两行里，只认两枚字符：反斜杠与双引号。
 * RFC 8259 §7 明文要求 U+0000–U+001F 那 32 枚控制字符**不许以裸字符出现在字符串里**，
 * 而课程名 / 教师 / 教室 / 学期名几条字段全从用户手里来（群里粘贴带 CRLF、教务表格单元格带出制表符、
 * 别名框里什么都打得出来）。一枚裸换行就够让整份 `.json` 在那一处判死 —— 不是丢一行而是丢全表：
 * 对方 App 一条课都收不到，而屏幕上念的是「WakeUp JSON 已导出」。
 * **这条链落的盘是用户挑的那个文件**，所以它是导出家族里代价最大的一枚。
 *
 * ## 第 ⓪ 问裁的是丙档：sanitize 与 total escaper 两头都做
 *
 * 同族那把尺在本仓有现成的形状：`data/export/IcsExporter.kt` 的 `escape` 为了同一档输入
 * 写了**两**手 —— 先把 CRLF 与 CR 归一成 LF（它自己的注释明说"必须先统一换行再转义"），
 * 再把 `\ ; , 换行` 逐个转义。前一手的产物是"给对方看的干净文本"，后一手的产物是"这份文件永远合法"。
 * 本文件照同一形状，且**归一化排在转义之前**那一手逐字沿用（连"先折 CR 再谈别的"这个先后一起沿用）：
 * - [sanitize]（乙）：行结束符与 C0 折成一枚空格、连续空格并一枚、首尾去空 ⇒ 对方看到的是人打的字；
 * - [escaped]（甲）：判据**总括**（total），不依赖调用方先过 [sanitize] ⇒ 任何输入都产不出非法 JSON。
 * 只做甲档交回的是"合法但带着乱码"；只做乙档就把合法性押在"上游永远先过 sanitize"这一手，
 * 序列化器本身仍是偏的（下一枚调用点忘过 sanitize 就当场破）。
 *
 * ## 总括判据多挡的那一类：孤立代理码元
 *
 * 一枚**配不上对**的代理码元（半枚 emoji：粘贴被截断、旧库里的脏行）在 UTF-8 里根本编码不出来，
 * 落盘那一步的 UTF-8 编码器会把它折成一枚 `?` 并一句话不说 —— 那是审计 N3 在 ICS 链上登记过的
 * 同一族污染，只是这一条链上我们**能**在同一处收口：折成 `udXXX` 式转义以后文件仍是合法 JSON
 * （RFC 8259 的 `char` 产生式认 `\u` 四位十六进制，不要求它配对），对方解析回来还是那枚码元而不是 `?`。
 * **配对**代理（emoji、CJK 扩展 B 的 𐍈）原样出场：那是合法的 UTF-8，也是用户打出来的那几个字。
 * U+007F（DEL）不在 §7 强制的那一段里 ⇒ 不动它，也不折成空格：动它 = 把用户的数据改到规范之外。
 *
 * ## 十六进制为什么不用格式化函数
 *
 * 默认 locale 一旦是 ar/fa 那一档，格式化出来的数字就不是 ASCII 数字，那份 JSON 在对方解析器眼里
 * 就又坏了 —— `IcsExporter` 把它的 formatter 钉成 `Locale.US` 就是同一条理由。这里更省事：
 * 一张十六进制数码表按下标取字，零 locale 面。
 *
 * 纯 JVM 判据（本仓口径）：零 android / androidx import、不读时钟、不读时区、不读权限、
 * 不读设备度量 —— 输入只是一枚已经由调用方（`ScheduleExporters.toWakeUpJson` 的字段表）递进来的字符串。
 *
 * 证据档位：表驱动单测 `WakeUpJsonTextTest`（每张表逐格 + 每道边界两侧 + 端到端解析回来）；
 * 源码形状守卫 `WakeUpExportGateWiringGuardTest` 钉"导出器里只剩这一处转义落点、
 * 改前那两行在代码里 0 处而只留在注释里"。
 */
object WakeUpJsonText {

    /**
     * 判据表 ①（恰 7 枚）：RFC 8259 §7 给了短名的字符，含 `"` 与 `\` 两枚结构性字符。
     *
     * 这张表是**公开的**：表驱动单测按它逐枚打格子，守卫按它的枚数判"有没有第二处转义口径"。
     */
    val SHORT_ESCAPES: Map<Char, String> = mapOf(
        '"' to "\\\"",
        '\\' to "\\\\",
        '\u0008' to "\\b",
        '\u0009' to "\\t",
        '\u000A' to "\\n",
        '\u000C' to "\\f",
        '\u000D' to "\\r",
    )

    /** 判据表 ②：RFC 8259 §7 强制转义的那一段码位（U+0000–U+001F，闭区间；表 ① 之外的走 `\uxxxx`）。 */
    val CONTROL_RANGE: IntRange = 0x00..0x1F

    /** 判据表 ③：sanitize 折成一枚空格的那一段码位 = 与表 ② 同一段（一枚尺，不另立一档）。 */
    val FOLD_TO_SPACE_RANGE: IntRange = CONTROL_RANGE

    /** 判据表 ④：`\u` 后面那四位的数码表（小写，与常见 JSON 库的产出同形；不取默认 locale 的数字）。 */
    const val HEX_DIGITS: String = "0123456789abcdef"

    /** 行结束符归一：CRLF 与 CR 都先折成 LF（与 ICS 那条链同一手，**必须排在别的手之前**）。 */
    const val LF: Char = '\u000A'

    /**
     * 乙档那一手：用户可见文本进导出件之前的清洗。
     *
     * 1. 先归一行结束符（CRLF、CR ⇒ LF），与 `IcsExporter.escape` 逐字同一手；
     * 2. 再把 C0 全档（含 LF 与 TAB）与空格并成**一枚**空格；
     * 3. 首尾的那枚空格不出场（`trim` 的形状，但由同一张表判，不另调 `trim`）。
     *
     * 辅助平面字符（emoji）与 U+007F 一律原样交回：本函数只动"看不见的排版字符"。
     */
    fun sanitize(raw: String): String {
        val normalized = raw.replace("\r\n", "\n").replace('\r', LF)
        val out = StringBuilder(normalized.length)
        var pendingSpace = false
        for (ch in normalized) {
            if (ch.code in FOLD_TO_SPACE_RANGE || ch == ' ') {
                // 开头的空白还没有内容可押 ⇒ 直接丢掉（这一手就是 trim 的前半）
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    out.append(' ')
                    pendingSpace = false
                }
                out.append(ch)
            }
        }
        // pendingSpace 只留在末尾 ⇒ 后半的 trim 由"没人再 append"天然完成
        return out.toString()
    }

    /**
     * 甲档那一手：不带引号的转义正文，**总括判据** —— 任何输入都产不出非法 JSON。
     *
     * 表 ① 的七枚走短名（`\"` `\\` `\b` `\t` `\n` `\f` `\r`），表 ② 剩下的 C0 走 `\u00xx`，
     * 孤立代理码元走 `\udxxx`；配对代理与其它一切字符原样出场。
     * 本函数**不做** sanitize：它是最后一道，不假设前面有人洗过。
     */
    fun escaped(raw: String): String {
        val out = StringBuilder(raw.length + 8)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            val short = SHORT_ESCAPES[ch]
            when {
                short != null -> {
                    out.append(short)
                    index++
                }

                ch.code in CONTROL_RANGE -> {
                    appendUnicodeEscape(out, ch.code)
                    index++
                }

                // 配对代理（emoji / CJK 扩展 B）：合法的 UTF-8，原样两枚码元一起走
                ch.isHighSurrogate() && raw.getOrNull(index + 1)?.isLowSurrogate() == true -> {
                    out.append(ch).append(raw[index + 1])
                    index += 2
                }

                // 孤立代理：UTF-8 编码不出来 ⇒ 落盘会被折成 `?`，只能转义交回
                ch.isSurrogate() -> {
                    appendUnicodeEscape(out, ch.code)
                    index++
                }

                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString()
    }

    /**
     * 丙档那一手，也是生产调用点唯一该用的那一枚：先 [sanitize] 再 [escaped]，交回**带引号**的字面量。
     *
     * 次序即语义：反过来（先 escape 再 sanitize）会把 `\n` 那两枚 ASCII 字符当成控制字符折掉，
     * 转义当场被自己拆台。
     */
    fun literal(raw: String): String = "\"" + escaped(sanitize(raw)) + "\""

    /** `\u` + 四位十六进制（按下标从 [HEX_DIGITS] 取字，零 locale 面）。 */
    private fun appendUnicodeEscape(out: StringBuilder, code: Int) {
        out.append("\\u")
        var shift = 12
        while (shift >= 0) {
            out.append(HEX_DIGITS[(code shr shift) and 0xF])
            shift -= 4
        }
    }
}
