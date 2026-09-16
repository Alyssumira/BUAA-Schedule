package com.buaa.schedule.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = DarkBluePrimary,
    onPrimary = DarkBlueOnPrimary,
    primaryContainer = DarkBluePrimaryContainer,
    onPrimaryContainer = DarkBlueOnPrimaryContainer,
    secondary = DarkBlueSecondary,
    onSecondary = DarkBlueOnSecondary,
    secondaryContainer = DarkBlueSecondaryContainer,
    onSecondaryContainer = DarkBlueOnSecondaryContainer,
    background = DarkBlueBackground,
    onBackground = DarkBlueOnBackground,
    surface = DarkBlueSurface,
    onSurface = DarkBlueOnSurface,
    surfaceVariant = DarkBlueSurfaceVariant,
    onSurfaceVariant = DarkBlueOnSurfaceVariant,
    outline = DarkBlueOutline,
)

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,
    secondary = BlueSecondary,
    onSecondary = BlueOnSecondary,
    secondaryContainer = BlueSecondaryContainer,
    onSecondaryContainer = BlueOnSecondaryContainer,
    background = BlueBackground,
    onBackground = BlueOnBackground,
    surface = BlueSurface,
    onSurface = BlueOnSurface,
    surfaceVariant = BlueSurfaceVariant,
    onSurfaceVariant = BlueOnSurfaceVariant,
    outline = BlueOutline,
)

/** @see BUAAScheduleTheme */
@Composable
fun BUAAScheduleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    BUAAScheduleTheme(darkTheme = darkTheme, dynamicColor = false, content = content)
}

/**
 * 应用主题。
 *
 * [dynamicColor] 开启时（Android 12+）使用 Material You 从壁纸提取的动态配色，
 * 关闭或低版本回退到应用自带的北航蓝配色。玻璃层是半透明的，会自然跟着底色走。
 */
@Composable
fun BUAAScheduleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    seedColorArgb: Int? = null,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        seedColorArgb != null -> seedColorScheme(darkTheme, seedColorArgb)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ScheduleTypography,
    ) {
        // MaterialTheme 本身**不**提供 LocalContentColor（只有 Surface 会提供）。
        // 我们的界面刻意不用 Scaffold/Surface 包壳，于是全站每一个没写 color 的
        // Text/Icon 都退回平台默认色 = 黑色：浅色下看不出来，深色模式下就是
        // "玻璃上全是黑字"。在这里补一个默认前景色，一次性覆盖全站。
        CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface) {
            content()
        }
    }
}

/** 由用户选择的种子色派生一套主题：保留现有北航蓝次级色/背景，只替换 primary 族 */
private fun seedColorScheme(darkTheme: Boolean, argb: Int): androidx.compose.material3.ColorScheme {
    // 种子色会被当作**文字**使用（全站几十处 colorScheme.primary），而设置页给的
    // 预设全是亮度 0.2~0.35 的深蓝/深绿：原样搬到深色主题上就是"深底深字"。
    // 先按当前主题把明度拉进可读区间，再派生整族。
    val seed = toneForTheme(Color(argb), darkTheme)
    val onSeed = if (seed.luminance() > 0.5f) Color.Black else Color.White
    val container = lerp(
        start = seed,
        stop = if (darkTheme) Color.Black else Color.White,
        fraction = if (darkTheme) 0.30f else 0.78f,
    )
    val onContainer = lerp(
        start = seed,
        stop = if (darkTheme) Color.White else Color.Black,
        // 深色主题的 container 是暗的，文字必须是**亮**色调（0.85 接近纯白）；
        // 原先写 0.12f，等于把种子色本身放在同色系的暗底上，几乎读不出来。
        fraction = if (darkTheme) 0.85f else 0.88f,
    )
    val base = if (darkTheme) DarkColors else LightColors
    return base.copy(
        primary = seed,
        onPrimary = onSeed,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
    )
}

/** 把颜色压进当前主题的可读明度带：深色主题提亮，浅色主题压暗 */
private fun toneForTheme(color: Color, darkTheme: Boolean): Color {
    val luma = color.luminance()
    if (darkTheme) {
        if (luma >= 0.55f) return color
        return lerp(color, Color.White, (0.55f - luma) / (1f - luma).coerceAtLeast(0.05f))
    }
    if (luma <= 0.35f) return color
    return lerp(color, Color.Black, (luma - 0.35f) / luma.coerceAtLeast(0.05f))
}
