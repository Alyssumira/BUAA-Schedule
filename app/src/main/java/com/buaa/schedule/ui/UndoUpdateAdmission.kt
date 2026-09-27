package com.buaa.schedule.ui

import com.buaa.schedule.data.repository.GroupAppearanceEdit
import com.buaa.schedule.domain.model.Course

/**
 * 「这一次保存该不该往撤销栈里压一条『编辑课程』条目」的判据内核（T128，收的是
 * `docs/derived-field-audit.md` §9.5② 那一格）。
 *
 * 纯判据：零 android import、零时钟读取、不碰 ViewModel 也不碰数据库 —— 写库**之前**从库里读回来的
 * 那一行、调用点要写进去的那一份、仓储层给的最终行 id、被顺手清掉的片段条数、以及组那一支自己交回的
 * 结论，一律由 [ScheduleViewModel.updateCourse] 量好了当参数递进来（本仓口径，
 * 同 `data/repository/CourseGroupAppearancePolicy.kt`、`ui/home/ManualTimeOverridePolicy.kt` 那一族）。
 * "有没有真的用上它"由源码核对守卫 `UndoUpdateEntryGuardTest` 第 ①③⑥ 层钉，本文件只管判据本身。
 *
 * ## 病与修法
 *
 * 旧压栈点只闸在「原文读没读回来」上（`if (original != null) {`），写完库**无条件**压一条 ⇒
 * 同值重写也压 ⇒ 栈里躺着一枚净效果为零的条目（那笔账在第 ⑤ 层：`afterId == before.id` 时不删行、
 * 主行只被 `normalize(before)` 原地回写、`removed` 空 ⇒ 一趟都不补）。用户在删除之后那条提示条上
 * 点掉唯一那颗「撤销」时，捞到的可能就是这样一枚（`undo()` 无参、`pop()` 捞栈顶）。
 *
 * ## 四把维度，一枚都不许少（T122 驳回"只判主行"的那三条真凭据）
 *
 * 1. `savedId != original.id`：部分周次拆行会**另发一行**，两个值对象逐字段一样也说明不了库里没动；
 * 2. `removedCount > 0`：拆行顺手清掉的同组兄弟片段（R5 F-35）—— 丢了它撤销之后那些行永久消失；
 * 3. `original != after`：主行自己那一维；
 * 4. `group.beforeRows` 非空：组外观那一支写的是**别的行**，主行同值连主行都保不住整次保存。
 *
 * 第 4 维今天是**可判**的了 —— 那正是 T122 判 deferred 时唯一缺的东西（那时那枚组写返回 `Unit`）。
 * 前三维合起来还有一层含义：早退**不丢**任何可复原的东西。库里那一行若不是归一化后的形状时，
 * 同值条目也回写不出旧形状（`undoUpdate` 落库的是 `normalize(before)`），
 * 所以"同值 + 另三把维度全空"这一格的净效果本来就是零，压它只是白占一枚栈位、
 * 并让用户点掉一次唯一的「撤销」。
 *
 * ## 残余的那一半（本卡没修，如实登记）
 *
 * 第 4 维命中而第 3 维不命中时（管理页给整组换色、主行本来就是这个色），条目**照压**（不许丢），
 * 但 `UndoAction.Update` 今天只带得回主行，撤销回去的是"主行原样"，兄弟片段留着新色 ⇒
 * 用户看得见的一次改色撤不干净。那是撤销条目的**容量**问题，与这一判（要不要有条目）是两件事，
 * 已由本卡的 [GroupAppearanceEdit.beforeRows] 把内容交回调用点，补它在下一张卡
 * （条目带得下组写改掉的行 + `undoUpdate` 按 id 复原）。
 */
internal fun updateUndoWorthRecording(
    original: Course,
    after: Course,
    savedId: Long,
    removedCount: Int,
    group: GroupAppearanceEdit?,
): Boolean = savedId != original.id ||
    removedCount > 0 ||
    original != after ||
    (group != null && group.beforeRows.isNotEmpty())
