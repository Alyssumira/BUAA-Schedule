package com.buaa.schedule.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
        content = content,
    )
}

/** 由用户选择的种子色派生一套主题：保留现有北航蓝次级色/背景，只替换 primary 族 */
private fun seedColorScheme(darkTheme: Boolean, argb: Int): androidx.compose.material3.ColorScheme {
    val seed = Color(argb)
    val onSeed = if (seed.luminance() > 0.5f) Color.Black else Color.White
    val container = lerp(
        start = seed,
        stop = if (darkTheme) Color.Black else Color.White,
        fraction = if (darkTheme) 0.30f else 0.78f,
    )
    val onContainer = lerp(
        start = seed,
        stop = if (darkTheme) Color.White else Color.Black,
        fraction = if (darkTheme) 0.12f else 0.88f,
    )
    val base = if (darkTheme) DarkColors else LightColors
    return base.copy(
        primary = seed,
        onPrimary = onSeed,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
    )
}
