package com.buaa.schedule.domain.model

import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.WeekParser

/**
 * 课程元信息的显示判据内核：学分格式化 + 体育课项目剥取。
 *
 * 零 android/compose import 是硬边界（本仓口径：判据函数必须能在纯 JVM 上测，
 * 设备事实只能当参数传进来）。学分的数据链路（解析/Room/备份/快照）早就全有了，
 * 这里只管"怎么把已有的数露出来"，不动任何存储口径。
 */

/**
 * 学分的人类可读数值：`5.0` -> "5"，`3.5` -> "3.5"，`0.0` -> "0"，没数据 -> null。
 *
 * 两条要紧的语义分界：
 * - **null 返回 null**，不给 "—"、"?"、"0" 之类的占位串：调用方拿 null 整行不画。
 *   "没有学分数据"（手动/文本/ICS 导入）与"教务明说 0 学分"（入学教育类）必须分得开
 *   ——见 [Course.credit] 的注释；把缺失印成 "0" 是往统计页好不容易划清的那条线上抹泥。
 * - `0.0` 走的是真值分支，返回 "0"。
 *
 * 非法值（负数 / NaN / Infinity / 超 [CourseConstraints.MAX_CREDIT]）一律判 null：
 * 上界判断**复用** [CourseConstraints.normalizeCredit] 而不是重写一份 —— 写入路径与
 * 显示路径必须共用同一把尺子，否则会出现"库里存得下、界面上该隐身"的裂缝。
 *
 * 不用 `java.text.DecimalFormat` / `NumberFormat`：它们跟着 locale 走，
 * 小数点可能是逗号，JVM 单测与设备行为可能对不上——这函数要在纯 JVM 上钉死，
 * 字符串只能自己拼。最多两位小数（学分实际口径最多 .5），非整数去尾随 0：
 * `2.20` -> "2.2"；四舍五入到百分位后成整的按整数档（`99.999` -> "100"）。
 */
fun formatCredit(credit: Double?): String? {
    val value = CourseConstraints.normalizeCredit(credit) ?: return null
    return formatScaledCredits(kotlin.math.round(value * 100).toLong())
}

/**
 * 学分合计的格式化：`43.0` -> "43"、`6.7` -> "6.7"、`120.0` -> "120"。
 *
 * ⚠️ 这一枚**不走** [CourseConstraints.normalizeCredit]，是有意的：那把尺子的
 * [CourseConstraints.MAX_CREDIT] = 100 是"一门课"的上界，而这里是多门课相加的结果 ——
 * 一个 43 学分的学期是真的，手动堆课的用户堆出 120 学分也是真的，
 * 拿单体量程去判它 null 会让统计页那个唯一的大字号空着（比偏大的数字更糟）。
 * 数字的**拼法**与 [formatCredit] 共用 [formatScaledCredits] 这一份，
 * 统计页不许再手抄一份 `trimCredits`：两份手抄迟早一条带 .0、另一条不带。
 *
 * 脏值只收非有限值（NaN / Infinity 来自被写坏的库行求和），负数按 0 画 ——
 * 求和的每一项都已经被 normalizeCredit 夹在 0..100，"负的总学分"这件事不存在。
 */
fun formatCreditTotal(credits: Double): String =
    if (!credits.isFinite()) "0" else formatScaledCredits(kotlin.math.round(credits.coerceAtLeast(0.0) * 100).toLong())

/**
 * 已经放大 100 倍的学分数 → 文案（[formatCredit] 与 [formatCreditTotal] 共用的一步）。
 *
 * 不用 `DecimalFormat` / `NumberFormat`：它们跟着 locale 走（小数点可能是逗号），
 * 这个函数要在纯 JVM 单测里钉死、又要与设备上完全一致，字符串只能自己拼。
 * 最多两位小数（学分实际口径最多 .5），非整数去尾随 0：`2.20` -> "2.2"；
 * 四舍五入到百分位后成整的按整数档（`99.999` -> "100"）。
 */
private fun formatScaledCredits(scaled: Long): String {
    val whole = scaled / 100
    val frac = scaled % 100
    return when {
        frac == 0L -> whole.toString()
        frac % 10 == 0L -> "$whole.${frac / 10}"
        else -> "$whole.${frac.toString().padStart(2, '0')}"
    }
}

/**
 * 带单位的学分文案："3.5" -> "3.5学分"；null 透传 null（整行不画，见 [formatCredit]）。
 *
 * 单位并进内核而不是留在各调用点拼：三处界面（详情 Sheet、管理页摘要、日视图卡片行）
 * 措辞必须一字不差，调用点各写一遍"学分"后缀是将来某处悄悄改口的开始。
 */
fun creditLabel(credit: Double?): String? = formatCredit(credit)?.let { "${it}学分" }

/**
 * 教务解析在"这一行没给教师"时写的**字面量**哨兵：见
 * `BuaaScheduleParser.kt:78`（`teacherWeekPairs.isEmpty()` 那一支整条课程都填它），
 * 解析自检也按它数"未知教师"的条数（同文件 :119）。
 *
 * 它是数据里的字符串、不是 null，所以照抄 `course.teacher` 就会把"没有教师"
 * 印成一位名叫「未知教师」的人 —— 与 [formatCredit] 不许把 null 印成 "0" 同一族账。
 */
const val UNKNOWN_TEACHER_SENTINEL = "未知教师"

/**
 * 教师 → 可显示的教师：哨兵与空白一律折成 null，真值原样返回（只 trim）。
 *
 * null 的含义是"不知道"，调用点按仓库既有的「缺项整段跳过」（[joinMeta]）处理，
 * 不要在这里补一句占位文案：详情 Sheet 那类"每一行都得有字"的界面自己决定占什么，
 * 判据只管把假教师挡掉。
 *
 * 只折**整串等于**哨兵的值：`"未知教师(代)"`、`"三位教师：未知教师"` 这类是教务真给了
 * 内容的课名，挡掉就是在删数据 —— 教务侧还有 `extractTeacherWeekPairs` 那条多教师链，
 * 那里的 "未知教师" 是真教师名单里的一项，不在这枚的射程内。
 */
fun teacherOrNull(teacher: String?): String? =
    teacher?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN_TEACHER_SENTINEL }

/**
 * [peProjectOf] 认的项目名长度上限。
 *
 * 真实项目名（篮球/体能测试/乒乓球/健美操）三五个字到边，12 已经是宽裕；
 * 再长基本是教务脏数据或用户把备注写进了括号（"体育(因伤改修其他课程说明……)"），
 * 这种整段吐到界面上会把一行撑成一段话——宁可不出场。
 */
private const val MAX_PE_PROJECT_CHARS = 12

/**
 * 从课程名里剥出体育课的项目名：`体育(田径)` -> "田径"、`体育（篮球）` -> "篮球"、
 * `体育选项(武术)` -> "武术"；不是体育课、或没有可用的项目信息 -> null。
 *
 * **这是按课程名的启发式判断，不是接口给的类型字段**：教务接口
 * （docs/BUAA_API.md 的 getMyScheduleDetail 字段清单）没有任何单独的"项目"键，
 * 项目名只活在课程名里。判"是不是体育课"看**左括号之前的主干**是否以「体育」开头，
 * 于是 `大学物理(上)`、`高等数学A(上)` 这类带括号后缀的非体育课返回 null ——
 * 绝不能把「上」当项目名吐出来。已知边界：主干不带「体育」前缀的体育课
 * （课程名直接叫「篮球」）识别不出来，也不该猜——猜错就是在界面上造数据。
 *
 * 括号口径与 `widget/WeekGridWidgetService.kt` 的 `parenInner` / `beforeFirstParen`
 * 同族（取**第一对**括号的内容，没有闭括号就取到结尾，全角括号一并认）：那两份是
 * `private` 且在 widget 包，这里按 domain 边界重写一份并注明同族关系，
 * 组件侧将来收口到这一份。全角数字/字母/空格先过 [WeekParser.normalizeWidths]。
 *
 * 返回 null 的"没有项目信息"情形都是有意为之，不是 bug：
 * 主干是「体育」但根本没括号（课名就叫"体育"）；括号里只有空白或标点；
 * 项目名超 [MAX_PE_PROJECT_CHARS] 字。脏数据不往界面上吐。
 */
fun peProjectOf(name: String): String? {
    val cleaned = WeekParser.normalizeWidths(name).replace(" ", "")
    val open = cleaned.indexOfFirst { it == '(' || it == '（' }
    // open <= 0 一并挡掉两种情况：没有括号，以及括号顶在课名最前（主干为空）
    if (open <= 0) return null
    if (!cleaned.take(open).startsWith("体育")) return null
    val tail = cleaned.substring(open + 1)
    // 闭括号中/英文都收；教务脏数据里有半截括号（对齐组件侧 parenInner 的既有口径）
    val inner = tail.substringBefore(')').substringBefore('）')
    return inner.takeIf { project ->
        project.length <= MAX_PE_PROJECT_CHARS && project.any { it.isLetterOrDigit() }
    }
}
