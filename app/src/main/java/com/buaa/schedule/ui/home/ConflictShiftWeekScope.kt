package com.buaa.schedule.ui.home

/**
 * 「只改这些周」那一次落库**到底该改哪几周**的判据内核（T131）。
 *
 * 纯判据：零 android import、零时钟读取、不碰 ViewModel 也不碰数据库 —— 那一行的**组周**
 * （`ConflictGroup.weeks`，向导行头上「第 N 周」念的就是它）与 **target 自己的周次**
 * （`target.weeks`）都由调用点 [applyConflictShift] 量好了当参数递进来（本仓口径，
 * 见 `data/repository/CourseDeletionPolicy.kt` 那一族）。"返回布尔以后调用点再各自判一次"
 * 在这一族里算违反，所以 [weeksToShift] 是全仓唯一一份判据本体，**它直接返回要写进去的那份周次**。
 *
 * ## 病（那一句承诺今天兑不了现）
 *
 * 旧 `applyConflictShift` 只 `target.copy(periods = newPeriods)`、**不动 `weeks`**，
 * 而 [com.buaa.schedule.ui.ScheduleViewModel.updateCourse] 那一记三合取
 * `options.partialWeeks && original != null && original.weeks != course.weeks`
 * 的第三项拿的是「写进去的周次 vs 库里那一行的周次」——
 * `original` 正是按 `course.id` 读回来的那一行，`course.weeks` 又原封没动，
 * 于是**那一判与课表长什么样无关，恒为假** ⇒ `partialWeeks = true` 那枚旗标走不到
 * `updateCoursePartialWeeks`，落库退成 `repository.updateCourse(course)` 整行覆盖：
 * 连不冲突的周次一起把上课时间挪走了，而按钮写着「只改这些周」。
 * 「组周 ⊊ target.weeks」这一格排不排得出来，由
 * `ConflictWizardWeekScopeReachabilityTest` 用真码（`findConflicts` / `groupConflicts` /
 * `suggestNearestFreeShift`）验，不在本文件里复述。
 *
 * ## 为什么判据是「交集」而不是「组周」本身
 *
 * [com.buaa.schedule.domain.schedule.CourseConflictResolution] 的组走并查集传递闭包，
 * `ConflictGroup.weeks` 是**组内所有两两
 * 重叠周次的并集**；target 由 `courses.firstOrNull()` 定，而组内次序来自
 * `sortedBy { it.startPeriod }` —— **谁被挪与谁的周次宽不宽毫无关系**。所以组周可能
 * 盖到 target 根本没排的那些周（A 撞 B 在 1-8、B 撞 C 在 9-16、target=A 只排 1-8）。
 * 把那样的周次塞进 `target.weeks` 等于给这门课**凭空造出它没有的上课周**，
 * 是比今天更坏的方向 ⇒ 只能取交集。
 *
 * ## 两个 no-op 分支为什么必须原样回传 [targetWeeks]
 *
 * [com.buaa.schedule.ui.ScheduleViewModel.updateCourse] 的第三判是**列表逐元素相等**，
 * 不是集合相等。判据如果返一份「重排过的同款周次」，那一判就会因为顺序而翻真，
 * 在「整门课本来就都在冲突里」这一格上**凭空拆出一行**（另发 id、清同组兄弟片段）。
 * 所以凡收窄省不下任何东西的场合，一律把调用点递进来的那份**原样**送回，
 * 让写点保持今天那条整行覆盖的路。
 */
object ConflictShiftWeekScope {

    /**
     * @param groupWeeks 那一组冲突实际涉及的周次（向导行头念的那一串），已排序去重由
     *   [com.buaa.schedule.domain.schedule.CourseConflictResolution] 负责，本判据不信任它
     * @param targetWeeks 被挪那一行自己的周次
     * @return 这一次落库真正要作用的那几周：
     *   - 交集非空且**真的排除了若干周** ⇒ 返回排好序、去过重的交集 ⇒ `updateCourse` 那一判转真
     *     ⇒ 走 `updateCoursePartialWeeks`，其余周保持原排课；
     *   - 交集就是 target 的全部周次（含组周盖到 target 外面那一格）⇒ 原样返回 [targetWeeks]，
     *     收窄是 no-op，不拆行；
     *   - 交集为空（组周为空，或两串完全不相交）⇒ 原样返回 [targetWeeks]。
     *     写空周次会被 `CourseConstraints.normalize` 判非法而整趟什么都不写（按钮只报「没存上」），
     *     比挪整门课更坏；而这一档在真码里组不出来（组周非空、target 必参与过组内某一记重叠），
     *     留这一支只做兜底，不拿它当出路。
     */
    fun weeksToShift(groupWeeks: List<Int>, targetWeeks: List<Int>): List<Int> {
        val scoped = groupWeeks.filter { it in targetWeeks }.distinct().sorted()
        // 空交集 ⇒ 不收窄（见 KDoc 第三档）
        if (scoped.isEmpty()) return targetWeeks
        // 集合上没排除掉任何一周 ⇒ 原样回传，连顺序都不重排，免得那一判被顺序翻真
        if (scoped.size == targetWeeks.distinct().size) return targetWeeks
        return scoped
    }
}
