package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course

/**
 * 「组外观那一支写到底改没改到东西、改了谁」的判据内核（T128 = T122 排的那枚前置）。
 *
 * 纯判据：零 android import、零时钟读取、不碰 DAO 也不碰数据库 —— 组里每一行**现在的值**、
 * 调用点要盖上去的那一份外观、以及已经归一化过的自定义色，一律由
 * [ScheduleRepository.updateCourseGroupAppearance] 在事务内量好了当参数递进来
 * （本仓口径，同 `CourseDeletionPolicy.kt`、`ui/home/ManualTimeOverridePolicy.kt` 那一族）。
 * 有没有真的用上它由源码核对守卫 `UndoUpdateEntryGuardTest` 第 ③ 层钉，本文件只管判据本身。
 *
 * ## 前置为什么是这一枚（T122 的账）
 *
 * `docs/derived-field-audit.md` §9.5② 想收的是「同值重写也压一枚撤销条目」，而那一判在压栈点
 * 算不出来，因为全仓唯一一枚**保证**同值的写库路径（管理页反复点当前已选中那块色板）走的正是
 * 组外观这一支 —— 它写的是**兄弟行**，旧签名又**返回 `Unit`**，调用点手上根本没有读数。
 * 本文件把那一支的结论交回去：[GroupAppearanceEdit.beforeRows] 就是"它改了谁"，
 * 空表就是"这一趟什么都没写到"。
 *
 * ## 形状为何选"改掉的那些行本身"，而不是另两型
 *
 * - **返回 Boolean**：只答得了第一问（写没写到），答不了第二问（改了谁）；撤销要复原的
 *   恰恰是第二问的内容，让调用点为了它再读一趟组 = 多取一次锁、还要另算一笔
 *   "读回来的组是不是写库前那一刻的组"的时序账（`UndoUpdateEntryGuardTest` 第 ③ 层为此明写
 *   了不许在 VM 里另起一趟读）。
 * - **返回改动的行数（Int）**：比 Boolean 多一点信息，但撤销仍然拿不到内容 —— 而"复原"要的
 *   是每一行改**之前**长什么样，行号给不出这个。
 * - **现在这一型**：带得回行本身，且带的是**改前**那一版。形状照同文件里既有的范本
 *   [ScheduleRepository.deleteCourseGroup] 那道"空快照 = 什么都没动"的口径 ——
 *   **空表就是结论**，所以这里不另立一枚 `changedAnything` 布尔：全仓"这一趟动没动"那一判
 *   在编辑链上只有 [com.buaa.schedule.ui.updateUndoWorthRecording] 一份，它读的就是这张表的
 *   空与非空，不会再长出第二把尺子。
 *
 * ## 顺手收掉的那一半：`isManualOverride` 不再由空操作新立
 *
 * T133 之后全仓的口径是"真的改了才标 manual"（拖课那一枚由 `ManualTimeOverridePolicy` 供给）。
 * 组这一支今天却是**无条件**给整组立旗：反复点同一块色板这种逐字段没改的写，也会让一门好端端的
 * 课从此退出教务刷新的匹配与覆盖。本内核把旗标改成"跟着真改动走"：
 * 改了外观 ⇒ 标（与旧行为一致，见下面那笔代价账），什么都没改 ⇒ 那一行**根本不写**、
 * 旗标也就无从被洗或被立。旗标是粘的：`original.isManualOverride ||` 那一枚析取保证
 * 一次空操作不许把已经标过的行冲回 false（同 `ui/home/ManualTimeOverridePolicy.kt` 的口径）。
 *
 * ⚠️ **没收到的一半（代价如实写明）**：「只改颜色算不算手改过这门课」本卡判成**仍算**。
 * 理由是这一枚旗标今天同时是"本地外观别被刷新抹掉"的唯一保护 ——
 * [com.buaa.schedule.domain.schedule.ImportPlanner.buildImportPlan] 命中同一 key 时交回的是
 * `course.copy(id = old.id, credit = ...)`，**外观取自导入那一版**，不标 manual 的行下次刷新
 * 就把用户挑的颜色刷没了。摘掉旗标会把这个洞打开，而补它得改 `ImportPlanner` 往回带
 * `colorIndex`/`customColorArgb`/`alias`/`remark` —— 那是另一张卡（本卡回执登记为残账）。
 * 保持现状的代价照旧在：一旦真的改了外观，那一整组从此退出匹配，而 `buildImportPlan`
 * 只交回"标了 manual 的旧行 + 本批导入行" ⇒ **一张课表变两张卡**。
 *
 * 只做**值对象**上的比较：`Course` 是 data class，逐字段相等就是"这一行不必写"，
 * 所以"动没动"这一判不另立尺子（撤销条目净效果为零的那三块结构证据在
 * `UndoUpdateEntryGuardTest` 第 ⑤ 层，它正是本卡敢在压栈点早退的根据）。
 */
data class GroupAppearanceEdit(
    /**
     * 这一趟**真的改掉了**的那些行的**改前**状态（按行 id，含主行自己 —— 组写覆盖整组）。
     * 空表 = 组里每一行都已经逐字段等于目标态，一个字节都没写。
     */
    val beforeRows: List<Course>,
)

/**
 * 组外观那一支要往**一行**上盖的那一版；与 [original] 逐字段相等时返回 **null** = 这一行不必写。
 *
 * 管的字段与旧实现一字不差（名称/地点/校区/学分/两个颜色维 + 那枚旗标），**不含**时间、教师、周次：
 * 同组片段本就可能有不同的时间安排。学分沿用"null 是这次没填"的旧口径（[com.buaa.schedule.domain.schedule.SemesterStats]
 * 按组取最大值，直接覆盖会把兄弟片段已有的学分抹掉）。
 *
 * @param original 事务内读回来的那一行**现在**的值
 * @param incoming 调用点要盖上去的那份外观（主行那一版）
 * @param customColorArgb **已经**过 `CourseConstraints.normalizeCustomColorArgb` 的自定义色：
 *                        归一化只做一次、由调用点递进来，本内核不许再判一遍（非法色不许扩散到同组）
 */
internal fun groupAppearanceRow(
    original: Course,
    incoming: Course,
    customColorArgb: Long?,
): Course? {
    val colorIndex = incoming.colorIndex.coerceAtLeast(0)
    // 只有非空学分才传播：`incoming.credit == null` 是"这次没填"，不是"填了 0 分"
    val creditChanged = incoming.credit != null && original.credit != incoming.credit
    val appearanceChanged = original.name != incoming.name ||
        original.location != incoming.location ||
        original.campus != incoming.campus ||
        original.colorIndex != colorIndex ||
        original.customColorArgb != customColorArgb ||
        creditChanged
    val written = original.copy(
        name = incoming.name,
        location = incoming.location,
        campus = incoming.campus,
        credit = incoming.credit ?: original.credit,
        colorIndex = colorIndex,
        customColorArgb = customColorArgb,
        isManualOverride = original.isManualOverride || appearanceChanged,
    )
    return written.takeIf { it != original }
}
