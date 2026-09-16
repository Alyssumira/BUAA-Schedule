package com.buaa.schedule.domain.model

/**
 * 课程编辑保存选项。
 *
 * @param partialWeeks true 表示本次编辑只作用于编辑器里选中的周次
 *   （原课程保留剩余周次，选中周次拆成新行）
 * @param applyToGroup true 表示把名称/地点/校区/颜色同步到同组全部片段
 */
data class CourseSaveOptions(
    val partialWeeks: Boolean = false,
    val applyToGroup: Boolean = false,
)
