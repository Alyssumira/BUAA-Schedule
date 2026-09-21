package com.buaa.schedule.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.buaa.schedule.core.designsystem.LocalAnimatedVisibilityScope
import com.buaa.schedule.core.designsystem.LocalSharedTransitionScope

/**
 * 课程卡的共享元素修饰符：**同一条 `course_${id}` 键的唯一写法**。
 *
 * 键形如 `course_12` 以前在四处各抄一遍（周视图课程格、今日课表的两种模式、
 * 课程管理页、编辑器）。抄了四份的代价不是行数，是"少接一处"：日视图的
 * 列表模式与时间轴模式就是这么漏掉的——从周课表点开一节课，卡片会飞到编辑器上；
 * 从今日课表点同一节课，只有整页淡入淡出，共享元素那条路根本没接上（T52④）。
 *
 * 两个 Local 任缺其一就退成空修饰符：引导页那一支不是 NavHost 的 destination
 * （见 MainActivity 的 setContent），拿不到 `AnimatedVisibilityScope`，
 * 此时不注册共享元素而不是崩在那里。
 *
 * @param courseId null = 这一屏还没有对应的课程（新增课程的编辑器），同样退成空修饰符
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun courseSharedElementModifier(courseId: Long?): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return Modifier
    val animScope = LocalAnimatedVisibilityScope.current ?: return Modifier
    val id = courseId ?: return Modifier
    return with(sharedScope) {
        Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(key = "course_$id"),
            animatedVisibilityScope = animScope,
        )
    }
}
