package com.buaa.schedule.ui.stats

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.model.weekdayLabel
import com.buaa.schedule.domain.schedule.ConflictDetector
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import com.buaa.schedule.domain.schedule.CourseWeekSpans

/**
 * 统计页「课程冲突」那一块的措辞判据（T82）。
 *
 * 零 android import、零时钟读取（本仓口径）：这里只把 [CourseConflictResolution.groupConflicts]
 * 已经归并好的组**说成话**，一个判据都不新写 ——
 * 哪些课算同一组、撞在哪几天、重叠的是哪几周，全部由那一件内核答；
 * [ConflictDetector.findConflicts] 早就排除掉的那些形态（同一 id 自己撞自己、
 * 周次不相交、不同天）在这里也**不重新解释一遍**，那是第二套能算错的真相。
 *
 * 「组」这个字在两处（首页那条横幅、这一页）指的是同一个数：
 * `groupConflicts(state.conflicts).size`，也就是**冲突向导里一屏摆几档**。
 * 改前首页那句写的是 `state.conflicts.size`（两两配对的条数），三门课互相撞会给出
 * 三个配对、却只有一组 —— 于是首页横幅说「3 组」、点开向导只见 1 档。
 * 这一卡把两处都收到组数上，别再让用户对着两个不同的冲突数猜哪个是真的。
 */

/** 一行里最多列几门课：三组以上再往下堆就不是"看一眼"而是"读一段"了 */
private const val MAX_INLINE_COURSES = 3

/** 卡片里最多摆几组：剩下的由「按建议处理」那一处（首页同一个向导）一次看全 */
internal const val MAX_INLINE_CONFLICT_GROUPS = 2

/**
 * 那一句总数：`有 2 组时间冲突`。
 *
 * 零组那一档**不许返回 null** —— 这一块不能整块消失后又不告诉用户它为什么不在
 * （卡面要求），所以"没有撞课"是一句要说的话，不是一个可以省的分支。
 */
internal fun conflictHeadlineNote(groupCount: Int): String =
    if (groupCount <= 0) "这学期没有撞课的时段" else "有 $groupCount 组时间冲突"

/**
 * 一组的标题：`周三 · 第 1-8、10 周`。
 *
 * 周次的切段走 [CourseWeekSpans.spansOf] —— 那正是「周次覆盖」那张图把
 * `1,2,3,…,8,10` 折成 `1-8、10` 用的同一件内核。这里再写一遍怎么折段，
 * 两张图上"第 8 周结束"就会一个写成 `1-8`、一个写成 `1,2,3,4,5,6,7,8`。
 * 脏星期序号（教务给过 0 / 8）退回 `周?`，不抛也不编一个周一出来。
 */
internal fun conflictGroupTitle(group: CourseConflictResolution.ConflictGroup): String {
    val day = weekdayLabel(group.dayOfWeek) ?: "周?"
    val spans = CourseWeekSpans.spansOf(group.weeks)
    if (spans.isEmpty()) return day
    val weeks = spans.joinToString("、") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
    return "$day · 第 $weeks 周"
}

/**
 * 一组里被摆进卡片的那几门课（每行仍是首页向导那句 `• 课名（节次，教室）`）。
 *
 * 逐条的措辞直接吃 [com.buaa.schedule.ui.home.conflictCourseLine]，这一件里只做
 * "取前几门 + 数出没画的那几门"，不重排、不去重、不重新判谁和谁撞。
 * 返回的是**片段**本身，措辞留给界面调那件函数 —— 这里把它抄一遍就是两处各说一遍。
 */
internal fun inlineConflictCourses(group: CourseConflictResolution.ConflictGroup): List<Course> =
    group.courses.take(MAX_INLINE_COURSES)

/** 「另有 N 门课也在这一组里」；没有隐藏的那些时 null（缺项整段跳过） */
internal fun hiddenCourseNote(group: CourseConflictResolution.ConflictGroup): String? {
    val hidden = group.courses.size - MAX_INLINE_COURSES
    return if (hidden > 0) "另有 $hidden 门课也在这一组里" else null
}

/** 「还有 N 组没有列出，点下面的按钮一次看全」；全都列出来了就 null */
internal fun hiddenGroupNote(hiddenGroups: Int): String? =
    if (hiddenGroups > 0) "还有 $hiddenGroups 组没有列出，点「按建议处理」一次看全" else null

/**
 * 一组的节次概览（标题后面那点补充信息）。
 *
 * 只在**组内所有片段的节次完全一样**时才说这一句 —— 也就是"两门课都排在第 3-4 节"
 * 那种最常见的撞法。节次不同（理论课 1-2 节撞实验课 3-4 节）时返回 null，
 * 因为"把每门课的节次折成一个区间"这件事没有任何一处内核定义过，
 * 在这里现造一个就是第二套真相；那种组下面逐条列出的那几行本来就把节次说清了。
 */
internal fun sharedPeriodNote(
    group: CourseConflictResolution.ConflictGroup,
    timeSlots: List<TimeSlot>,
): String? {
    if (group.courses.isEmpty()) return null
    val labels = group.courses.map { periodLabelOf(it.periods, timeSlots) }
    val first = labels.first()
    if (first.isEmpty() || labels.distinct().size != 1) return null
    return first
}
