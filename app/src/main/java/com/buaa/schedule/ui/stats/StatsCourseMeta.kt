package com.buaa.schedule.ui.stats

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.joinMeta
import com.buaa.schedule.domain.model.peProjectOf
import com.buaa.schedule.domain.model.teacherOrNull

/**
 * 统计页「课程学分」那一列的明细行判据（T81）。
 *
 * 零 android import（本仓口径）：输入是 [SemesterStats.creditsByCourse] 已经归并好的
 * 那**一组片段**（`CourseCredit.fragments`），这里只做"去重 + 折哨兵 + 拼一行"，
 * 不 groupBy —— 一门课的定义在 SemesterStats 文件头只有一份，
 * 这里再数一遍"哪些片段算同一门课"就会和总学分那本账分岔。
 *
 * 三项的顺序 教师 → 地点 → 校区 与详情 Sheet 的行序同一条（T58 钉过的那套），
 * 用户在两处对着读不用换脑子。全都没得说时返回 null，**整段不画**：
 * 与 Sheet 那三条 `DetailRow` 的区别在于这里是"一行里的三段"，
 * 缺项由 [joinMeta] 连分隔符一起丢，三段全缺就一行都不留。
 */

/**
 * 一项最多列几个值：一门课的实验段可以分布在十来间教室，
 * 全列出来会把这一行撑成一段话（这一页在 verticalScroll 里，折叠线以下的账 T81 卡面已经点过）。
 * 超了就折成「前两个 等N处」，数字仍是真的。
 */
private const val MAX_META_VALUES = 2

/** 一个片段级字段的去重取值；全空 → null（缺项整段跳过）。[unit] 是折"等 N"时的量词 */
private fun valuesOf(fragments: List<Course>, pick: (Course) -> String?, unit: String): String? {
    val distinct = fragments.mapNotNull { pick(it)?.trim() }.filter { it.isNotEmpty() }.distinct()
    return when {
        distinct.isEmpty() -> null
        distinct.size <= MAX_META_VALUES -> distinct.joinToString("、")
        else -> "${distinct.take(MAX_META_VALUES).joinToString("、")} 等${distinct.size}$unit"
    }
}

/**
 * 「由 N 段排课合并」：N == 1 时返回 null（一段就是这门课本来那一面，不值得单说一句）。
 *
 * N 吃的是 [CourseCredit.fragmentCount]，而它就是 fragments.size（SemesterStats 里派生的），
 * 所以这一句永远是逐门课的片段数、不是全页的 22 段那枚总数。
 */
internal fun fragmentNote(fragmentCount: Int): String? =
    if (fragmentCount > 1) "由 $fragmentCount 段排课合并" else null

/**
 * 一门课的明细行：`张三 · J3-101 · 沙河校区 · 由 2 段排课合并`。
 *
 * 教师走 [teacherOrNull]：教务解析在没给教师的那一行上写的是字面量 `"未知教师"`
 * （`BuaaScheduleParser.kt:78`），照抄 `course.teacher` 就会把"没有教师"印成一位叫
 * 「未知教师」的人。地点与校区没有这类哨兵（`"教室未定"` 是界面自己造的占位，
 * 不在数据里），所以只 trim 不折。
 */
internal fun courseRowNote(fragments: List<Course>): String? = joinMeta(
    valuesOf(fragments, { teacherOrNull(it.teacher) }, "位"),
    valuesOf(fragments, { it.location }, "处"),
    valuesOf(fragments, { it.campus }, "个"),
    // 体育项目一律从 course.name 判、从 course.name 剥：displayName 是别名优先
    // （Course.kt:84），用户把别名改成「体育课」项目就被自己的显示层弄丢了 ——
    // 这条口径在详情 Sheet 上有同样的注释钉着（CourseDetailSheet.kt:81-84）。
    // 三项里只有它是"从名字里剥出来的"，所以带着标签出场，免得被读成又一处校区。
    valuesOf(fragments, { peProjectOf(it.name) }, "项")?.let { "体育项目 $it" },
    fragmentNote(fragments.size),
).takeIf { it.isNotEmpty() }
