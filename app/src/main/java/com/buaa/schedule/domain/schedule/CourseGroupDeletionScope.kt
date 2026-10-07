package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course

/**
 * 「删除整门课」这一手到底删哪几段 —— 课表管理页那颗按钮与那句确认文案的**唯一**判据（T253）。
 *
 * ## 它补的是哪半句话
 *
 * 管理页顶部有一枚搜索框，`groupCourses(courses, query)` 从前是**先过滤再归并**
 * （`SemesterStats.creditsByCourse(filtered)`），于是过滤态下交回页面的 `CourseGroup.fragments`
 * 装的是「命中片段」而不是「组内全部片段」—— 与 `SemesterStats` 给
 * [SemesterStats.CourseCredit.fragments] 写的那句「组内**全部**排课片段」直接相违。
 * 那一手带出的三句同屏矛盾（登记的说法，盘面已证实）：
 * - 按钮念「删除整门课」（`contentDescription`），
 * - 确认框念「将删除这门课的全部 N 个片段」，
 * - 真正交给仓储层的却是命中的那几段 —— 搜索命中 2 段而整组 5 段时，**删掉 2 段、留下 3 段**，
 *   而屏幕上那两句都说的是"整门课"。删除范围被 UI 的过滤状态悄悄改了。
 *
 * ## 第 ⓪ 问裁定：甲档（删整组，过滤只决定看得见看不见）
 *
 * 理由有三，枚枚都在盘面上量得出：
 * 1. **句子与执行同源**：本仓一贯的口径。按钮那句「删除整门课」与提示条那句「已删除「X」」
 *    说的都是"这门课"，乙档要它们改口去迁就一次子集删除，改的是**一族的句子**；甲档改的是
 *    **一格的取数**，且甲档才是 T224 换源（`SemesterStats.courseGroupKey` 那把尺）本来许诺的东西。
 * 2. **隔壁那颗按钮已经走整组**：同一张卡上的「更改颜色」那一手是
 *    `updateCourse(primary, CourseSaveOptions(applyToGroup = true))`，VM 那一判按
 *    `sourceGroupKey` 在**库里**铺整组 —— 它从来不吃卡面上挂着的那几段。删除若只删命中片段，
 *    同一张卡上两颗按钮的"作用域"就是两把尺，比现在的病更难解释。
 * 3. **撤销救得回来的那一手必须是一整组**：`deleteCourseGroup` 把整组压成**一条**撤销记录
 *    （T127 的账），删一半留一半之后点「撤销」，回来的是那半截，屏上看不出这门课被拆过。
 *
 * ⚠️ [MatchedFragmentsOnly] 这一档在今天的 `app/src/main` 里**零调用方** —— 它不是漏删的接线，
 * 是卡面红线点名不许折掉的那一半：两档必须两档形状地在判据里存着（返回值也不许折成一枚
 * Boolean），而 `ui/course/CourseManagementScreen.kt` 的 `deleteGroupConfirmText` 备着乙档那句
 * 实话，谁把调用点换成乙档，屏幕上那句「全部 N 个片段」不会继续谎称整门课。
 *
 * ## 纯度（本仓明文规矩）
 *
 * 函数体零 android import、不读时钟 / 时区 / 权限 / 设备度量，也不碰 `Build.VERSION`：
 * 过滤态（搜索框里的原文）与"用户按的是哪一种删除"都由调用点当参数递进来。
 *
 * 「一门课」这把尺不在这里定义：组键与组内片段仍只由 [SemesterStats.creditsByCourse] 交回
 * （`domain/schedule/SemesterStats.kt` 文件头口径 1 那句禁令照守），本文件只对**已经归好的那一组**
 * 做范围判定，一处都不 groupBy。
 */
enum class GroupDeletionRequest {

    /**
     * 甲档：删**整组**，与过滤态无关 —— 管理页那颗「删除整门课」走的就是这一档。
     */
    WholeCourse,

    /**
     * 乙档：只删过滤命中的那些片段，界面上必须同时改口（见枚举头的第 3 条理由）。
     */
    MatchedFragmentsOnly,
}

/**
 * [courseGroupDeletionScope] 的产物：交给仓储层的那一组 + 弹窗要念的那个数 + 这一判落在哪一档。
 *
 * @param fragments **真正交给仓储层**（`deleteCourseGroupAndAwait(…)`）的那一组片段
 * @param announcedFragmentCount 弹窗那句要念的段数。它与 [fragments] 由**同一次判定**给出：
 *   调用点不许再对另一枚集合 `.size` 一遍 —— 那正是本卡治的那味病（念的数与删的集分家）
 * @param request 这一判落在哪一档。原样端回调用点，是为了让文案与守卫都认得出档，
 *   而不是只认得出"某一枚 Boolean"
 * @param wholeGroupFragmentCount 整组段数（过滤态之前的那一枚）
 * @param matchedFragmentCount 当前过滤态命中几段（空过滤 = 全命中，见 [normalizedCourseQuery]）
 * @param narrowedByFilter 过滤态是否**真的**把范围收窄了（命中数 ≠ 整组数）；
 *   它决定那句文案要不要提「搜索只命中其中 K 段」
 */
data class GroupDeletionScope(
    val fragments: List<Course>,
    val announcedFragmentCount: Int,
    val request: GroupDeletionRequest,
    val wholeGroupFragmentCount: Int,
    val matchedFragmentCount: Int,
    val narrowedByFilter: Boolean,
)

/**
 * 搜索态的唯一归一：去首尾空白，剩下的就是关键词。
 *
 * 列表面（`groupCourses` 决定哪张卡出场）与判据面（[courseGroupDeletionScope] 决定删哪几段）
 * 都必须先过这一枚，否则「搜索框里只打了空格」那一档会在一边算过滤、在另一边算不过滤。
 */
fun normalizedCourseQuery(query: String): String = query.trim()

/**
 * 「这一枚片段算不算被搜索词命中」的唯一判据：课程原名 / 显示名（别名优先）/ 教师，三处任一命中即算。
 *
 * 关键词为空 = 过滤态不成立 = 一律命中（与 [normalizedCourseQuery] 同一条口径，
 * 所以判据面不必再写第二遍 `if (keyword.isEmpty())`）。大小写不敏感。
 *
 * 三个字段为什么都认：日常界面看到的是别名，用户到这里只会打别名；而教务原名才是这门课的
 * "真名"，按它搜也该出来（别名可以是「物理」这种无信息量的词）。
 */
fun courseFragmentMatchesQuery(course: Course, keyword: String): Boolean {
    if (keyword.isEmpty()) return true
    return course.name.contains(keyword, ignoreCase = true) ||
        course.displayName.contains(keyword, ignoreCase = true) ||
        course.teacher?.contains(keyword, ignoreCase = true) == true
}

/**
 * 判据本体：给定**整组**片段（`CourseGroup.fragments`，由 [SemesterStats.creditsByCourse] 交回）
 * + 当前过滤态 + 用户按的是哪一种删除 ⇒ 该删哪几段、弹窗该念哪个数。
 *
 * - [GroupDeletionRequest.WholeCourse]：交回整组，念整组的数 —— 过滤态只影响这张卡在不在屏上，
 *   影响不到这一手的范围。
 * - [GroupDeletionRequest.MatchedFragmentsOnly]：交回命中的那些，念命中的数。
 *
 * 顺序照传入的那一组（星期 → 起始节次那张列序由调用点排好），本函数只挑不排。
 */
fun courseGroupDeletionScope(
    wholeGroupFragments: List<Course>,
    query: String,
    request: GroupDeletionRequest,
): GroupDeletionScope {
    val keyword = normalizedCourseQuery(query)
    val matched = wholeGroupFragments.filter { courseFragmentMatchesQuery(it, keyword) }
    val chosen = when (request) {
        GroupDeletionRequest.WholeCourse -> wholeGroupFragments
        GroupDeletionRequest.MatchedFragmentsOnly -> matched
    }
    return GroupDeletionScope(
        fragments = chosen,
        announcedFragmentCount = chosen.size,
        request = request,
        wholeGroupFragmentCount = wholeGroupFragments.size,
        matchedFragmentCount = matched.size,
        narrowedByFilter = matched.size != wholeGroupFragments.size,
    )
}
