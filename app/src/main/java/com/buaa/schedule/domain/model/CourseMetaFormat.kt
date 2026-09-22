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
    // 按百分位取整后拼字面量：value 已被 normalizeCredit 夹在 0..100 的有限值内，
    // ×100 取整不会溢出，也不需要任何 locale 敏感的格式化器
    val scaled = kotlin.math.round(value * 100).toLong()
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
