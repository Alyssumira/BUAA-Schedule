package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color

val BluePrimary = Color(0xFF1A73E8)
val BlueOnPrimary = Color(0xFFFFFFFF)
val BluePrimaryContainer = Color(0xFFD7E3FF)
val BlueOnPrimaryContainer = Color(0xFF001A41)
val BlueSecondary = Color(0xFF565E71)
val BlueOnSecondary = Color(0xFFFFFFFF)
val BlueSecondaryContainer = Color(0xFFDAE2F9)
val BlueOnSecondaryContainer = Color(0xFF131C2B)
val BlueBackground = Color(0xFFF8F9FF)
val BlueOnBackground = Color(0xFF1A1B20)
val BlueSurface = Color(0xFFFFFFFF)
val BlueOnSurface = Color(0xFF1A1B20)
val BlueSurfaceVariant = Color(0xFFE1E2EC)
val BlueOnSurfaceVariant = Color(0xFF44474F)
val BlueOutline = Color(0xFF74777F)

val DarkBluePrimary = Color(0xFFAAC7FF)
val DarkBlueOnPrimary = Color(0xFF002F66)
val DarkBluePrimaryContainer = Color(0xFF004494)
val DarkBlueOnPrimaryContainer = Color(0xFFD7E3FF)
val DarkBlueSecondary = Color(0xFFBEC6DC)
val DarkBlueOnSecondary = Color(0xFF283141)
val DarkBlueSecondaryContainer = Color(0xFF3E4759)
val DarkBlueOnSecondaryContainer = Color(0xFFDAE2F9)
val DarkBlueBackground = Color(0xFF111318)
val DarkBlueOnBackground = Color(0xFFE2E2E9)
val DarkBlueSurface = Color(0xFF111318)
val DarkBlueOnSurface = Color(0xFFE2E2E9)
val DarkBlueSurfaceVariant = Color(0xFF44474F)
val DarkBlueOnSurfaceVariant = Color(0xFFC5C6D0)
val DarkBlueOutline = Color(0xFF8F9099)

val CourseColors = listOf(
    Color(0xFF5B8DEF),
    Color(0xFFF2994A),
    Color(0xFF27AE60),
    Color(0xFFEB5757),
    Color(0xFF9B51E0),
    Color(0xFF00B8D4),
    Color(0xFFF2C94C),
    Color(0xFF219653),
)

/** 课程卡片颜色：自定义色优先，否则按 colorIndex 从调色板取（floorMod 容忍负索引） */
fun courseColor(course: com.buaa.schedule.domain.model.Course): Color =
    course.customColorArgb
        // 防御性校验：只接受 32 位 ARGB，越界值退回调色板，
        // 避免历史/外部来源的非法数值渲染出随机颜色
        ?.takeIf { it in 0L..0xFFFFFFFFL }
        ?.let { Color(it) }
        ?: CourseColors[Math.floorMod(course.colorIndex, CourseColors.size)]

/** 背景亮度（相对亮度，0..1），用于自动选择黑/白前景 */
fun Color.readableLuminance(): Float {
    fun channel(v: Float): Float = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

/**
 * 根据背景亮度自动选择可读前景色（黑或白），
 * 保证课程色块（如浅黄）上的文字始终满足对比度。
 */
fun contentOn(background: Color): Color = contentOnLuma(background.readableLuminance())

/**
 * [contentOn] 的亮度版本：手头的底色是"半透明色叠在别的东西上"合成出来的，
 * 只有亮度没有 Color（周视图课程卡：课程色 tint 叠在壁纸上），
 * 阈值判断仍然一样——所以拆出来复用，不要各写一套 0.45。
 */
fun contentOnLuma(luma: Float): Color =
    if (luma > 0.45f) Color(0xFF1A1B20) else Color.White
