package com.buaa.schedule.core.designsystem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 跨页面共享元素转场的作用域。
 *
 * 由 [MainActivity] 的根 SharedTransitionLayout 提供；
 * 课程卡与课程编辑器分别读取后，用同一个 key 注册 sharedElement。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** 当前 NavHost destination 的 AnimatedVisibilityScope，供 sharedElement 使用 */
val LocalAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }