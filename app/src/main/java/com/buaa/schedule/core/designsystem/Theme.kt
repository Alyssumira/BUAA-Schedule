package com.buaa.schedule.core.designsystem

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * 两套主题**逐槽位显式赋值**。
 *
 * M3 的 `lightColorScheme()` / `darkColorScheme()` 每个参数都自带默认值，
 * 漏写一个不会报错，只会静默回落——而 baseline 的中性色是**紫调**
 * （tertiary 340°、outlineVariant 270°、surfaceContainerHigh 276°、surfaceTint = baseline primary），
 * 本项目基调是 214° 蓝，同屏就会读出"串色"。
 * [ThemeSlotTest] 把"每个槽位都必须显式定义"钉成门禁。
 *
 * 其中 [androidx.compose.material3.ColorScheme.surfaceTint] 不只是个装饰槽位：
 * 玻璃降级路径直接画它当底板（见 `LiquidGlass.liquidGlass` 的 fallback），
 * 回落紫调等于"关闭玻璃后所有面板泛紫"。
 *
 * `internal` 而不是 `private`：[ThemeSlotTest] 要读这两套表才能守住"无槽位回落"。
 */
internal val DarkColors = darkColorScheme(
    primary = DarkBluePrimary,
    onPrimary = DarkBlueOnPrimary,
    primaryContainer = DarkBluePrimaryContainer,
    onPrimaryContainer = DarkBlueOnPrimaryContainer,
    inversePrimary = BluePrimary,
    secondary = DarkBlueSecondary,
    onSecondary = DarkBlueOnSecondary,
    secondaryContainer = DarkBlueSecondaryContainer,
    onSecondaryContainer = DarkBlueOnSecondaryContainer,
    tertiary = DarkTealTertiary,
    onTertiary = DarkTealOnTertiary,
    tertiaryContainer = DarkTealContainer,
    onTertiaryContainer = DarkTealOnContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBlueBackground,
    onBackground = DarkBlueOnBackground,
    surface = DarkBlueSurface,
    onSurface = DarkBlueOnSurface,
    surfaceVariant = DarkBlueSurfaceVariant,
    onSurfaceVariant = DarkBlueOnSurfaceVariant,
    surfaceDim = Color(0xFF0C0E13),
    surfaceBright = Color(0xFF383B45),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF191C22),
    surfaceContainer = Color(0xFF1D2027),
    surfaceContainerHigh = Color(0xFF272A32),
    surfaceContainerHighest = Color(0xFF32353D),
    outline = DarkBlueOutline,
    outlineVariant = Color(0xFF44474F),
    scrim = Color(0xFF000000),
    inverseSurface = DarkBlueOnSurface,
    inverseOnSurface = Color(0xFF1A1B20),
    surfaceTint = DarkBluePrimary,
)

internal val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,
    inversePrimary = DarkBluePrimary,
    secondary = BlueSecondary,
    onSecondary = BlueOnSecondary,
    secondaryContainer = BlueSecondaryContainer,
    onSecondaryContainer = BlueOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealContainer,
    onTertiaryContainer = TealOnContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = BlueBackground,
    onBackground = BlueOnBackground,
    surface = BlueSurface,
    onSurface = BlueOnSurface,
    surfaceVariant = BlueSurfaceVariant,
    onSurfaceVariant = BlueOnSurfaceVariant,
    surfaceDim = Color(0xFFD8DAE3),
    surfaceBright = Color(0xFFFAFAFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F5FB),
    surfaceContainer = Color(0xFFEEF0F8),
    // 与 BlueSurfaceVariant(#E1E2EC) 同族蓝灰，而不是 baseline 的 #ECE6F0（H=276 紫）
    surfaceContainerHigh = Color(0xFFEDEFF7),
    surfaceContainerHighest = Color(0xFFE6E8F1),
    outline = BlueOutline,
    outlineVariant = Color(0xFFC5C7D4),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2F3038),
    inverseOnSurface = Color(0xFFECEDF5),
    surfaceTint = BluePrimary,
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
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val targetScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        seedColorArgb != null -> seedColorScheme(darkTheme, seedColorArgb)
        darkTheme -> DarkColors
        else -> LightColors
    }
    // 一帧换色是全站仅剩的几处硬切之一：内容都还在原地，底色却"啪"地跳过去。
    // 逐槽位淡入而不是 Crossfade 整棵子树——后者会把 36 个槽位之外的状态
    // （滚动位置、展开态、输入法）一起重建，代价和收益完全不成比例。
    val colorScheme = animatedColorScheme(targetScheme, reduceMotion)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ScheduleTypography,
    ) {
        // MaterialTheme 本身**不**提供 LocalContentColor（只有 Surface 会提供）。
        // 我们的界面刻意不用 Scaffold/Surface 包壳，于是全站每一个没写 color 的
        // Text/Icon 都退回平台默认色 = 黑色：浅色下看不出来，深色模式下就是
        // "玻璃上全是黑字"。在这里补一个默认前景色，一次性覆盖全站。
        //
        // 语义扩展色按 darkTheme 取，不跟随动态取色：warning/success 是"中间严重度"
        // 的固定语义，跟着壁纸变色的话同一句警告今天琥珀明天青绿，等于没有语义。
        // 它跟着走同一条淡入——否则主体在过渡、警告色已经换完，中间帧会出现
        // "新语义色压旧背景"，正是 contentOnLuma 那套判据最读不出来的时刻。
        val semanticTarget = if (darkTheme) DarkSemanticColors else LightSemanticColors
        val animatedSemantic = semanticTarget.copy(
            warning = animatedColor("semWarning", semanticTarget.warning, reduceMotion),
            onWarning = animatedColor("semOnWarning", semanticTarget.onWarning, reduceMotion),
            warningContainer = animatedColor(
                "semWarningContainer", semanticTarget.warningContainer, reduceMotion,
            ),
            onWarningContainer = animatedColor(
                "semOnWarningContainer", semanticTarget.onWarningContainer, reduceMotion,
            ),
            success = animatedColor("semSuccess", semanticTarget.success, reduceMotion),
        )
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onSurface,
            LocalSemanticColors provides animatedSemantic,
        ) {
            content()
        }
    }
}

/**
 * 单格色彩淡入。
 *
 * 不传"起始色"，也不走 [motionSpec]：主题位于 `LocalReduceMotion` provider 的**上游**
 * （provider 在主题子树里才建立），组合里取不到那颗开关——所以 reduce-motion 由调用方
 * 显式传进来。策略与 motionSpec 完全一致：要求减少动态效果就 snap()，否则同档 tween。
 */
@Composable
private fun animatedColor(label: String, target: Color, reduceMotion: Boolean): Color =
    animateColorAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) snap() else tween(MotionTokens.DURATION_MEDIUM),
        label = label,
    ).value

/**
 * 把 [target] 的 36 个颜色槽位逐一淡入。
 *
 * 必须**全槽位覆盖**：漏写一个槽位不会报错，只会那一格仍然硬切——
 * 而"深色模式切换"这种全局事件里，任何一格跟不上都会被读成画面撕裂。
 * 槽位名取自编译期依赖的 material3 1.3.1（36 个 Color getter），
 * 与 [ThemeSlotTest] 的"逐槽显式定义"门禁同一套口径。
 */
@Composable
private fun animatedColorScheme(
    target: androidx.compose.material3.ColorScheme,
    reduceMotion: Boolean,
): androidx.compose.material3.ColorScheme = target.copy(
    primary = animatedColor("primary", target.primary, reduceMotion),
    onPrimary = animatedColor("onPrimary", target.onPrimary, reduceMotion),
    primaryContainer = animatedColor("primaryContainer", target.primaryContainer, reduceMotion),
    onPrimaryContainer = animatedColor("onPrimaryContainer", target.onPrimaryContainer, reduceMotion),
    inversePrimary = animatedColor("inversePrimary", target.inversePrimary, reduceMotion),
    secondary = animatedColor("secondary", target.secondary, reduceMotion),
    onSecondary = animatedColor("onSecondary", target.onSecondary, reduceMotion),
    secondaryContainer = animatedColor("secondaryContainer", target.secondaryContainer, reduceMotion),
    onSecondaryContainer = animatedColor("onSecondaryContainer", target.onSecondaryContainer, reduceMotion),
    tertiary = animatedColor("tertiary", target.tertiary, reduceMotion),
    onTertiary = animatedColor("onTertiary", target.onTertiary, reduceMotion),
    tertiaryContainer = animatedColor("tertiaryContainer", target.tertiaryContainer, reduceMotion),
    onTertiaryContainer = animatedColor("onTertiaryContainer", target.onTertiaryContainer, reduceMotion),
    background = animatedColor("background", target.background, reduceMotion),
    onBackground = animatedColor("onBackground", target.onBackground, reduceMotion),
    surface = animatedColor("surface", target.surface, reduceMotion),
    onSurface = animatedColor("onSurface", target.onSurface, reduceMotion),
    surfaceVariant = animatedColor("surfaceVariant", target.surfaceVariant, reduceMotion),
    onSurfaceVariant = animatedColor("onSurfaceVariant", target.onSurfaceVariant, reduceMotion),
    surfaceTint = animatedColor("surfaceTint", target.surfaceTint, reduceMotion),
    inverseSurface = animatedColor("inverseSurface", target.inverseSurface, reduceMotion),
    inverseOnSurface = animatedColor("inverseOnSurface", target.inverseOnSurface, reduceMotion),
    error = animatedColor("error", target.error, reduceMotion),
    onError = animatedColor("onError", target.onError, reduceMotion),
    errorContainer = animatedColor("errorContainer", target.errorContainer, reduceMotion),
    onErrorContainer = animatedColor("onErrorContainer", target.onErrorContainer, reduceMotion),
    outline = animatedColor("outline", target.outline, reduceMotion),
    outlineVariant = animatedColor("outlineVariant", target.outlineVariant, reduceMotion),
    scrim = animatedColor("scrim", target.scrim, reduceMotion),
    surfaceBright = animatedColor("surfaceBright", target.surfaceBright, reduceMotion),
    surfaceDim = animatedColor("surfaceDim", target.surfaceDim, reduceMotion),
    surfaceContainer = animatedColor("surfaceContainer", target.surfaceContainer, reduceMotion),
    surfaceContainerHigh = animatedColor("surfaceContainerHigh", target.surfaceContainerHigh, reduceMotion),
    surfaceContainerHighest = animatedColor(
        "surfaceContainerHighest", target.surfaceContainerHighest, reduceMotion,
    ),
    surfaceContainerLow = animatedColor("surfaceContainerLow", target.surfaceContainerLow, reduceMotion),
    surfaceContainerLowest = animatedColor(
        "surfaceContainerLowest", target.surfaceContainerLowest, reduceMotion,
    ),
)

/**
 * M3 的 ColorScheme 没有 warning / success 槽位，而项目确实需要
 * （导入警告、日历里被跳过的课次、"这个版本没有安装包附件"都属于中间严重度）。
 *
 * 此前借用 `colorScheme.tertiary` —— 语义上最近的可用槽位，但 M3 baseline 的
 * tertiary 是 340° 玫紫，与本项目 214° 蓝相差 126°，读起来像串色。
 * 借用是"没有槽位"的后果，不是写错；这里把槽位补上，借用就此终止。
 */
@Immutable
data class SemanticColors(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val success: Color,
)

val LightSemanticColors = SemanticColors(
    warning = Color(0xFF8A5A00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDEA8),
    onWarningContainer = Color(0xFF2B1700),
    success = Color(0xFF1E6B3A),
)

val DarkSemanticColors = SemanticColors(
    warning = Color(0xFFFFB95C),
    onWarning = Color(0xFF442A00),
    warningContainer = Color(0xFF5E3F00),
    onWarningContainer = Color(0xFFFFDEA8),
    success = Color(0xFF7BD69B),
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/**
 * 由用户选择的种子色派生一套主题。
 *
 * primary 族与 secondary 族**都**按种子派生：此前只换 primary，于是选了绿色种子之后
 * `primaryContainer`（绿）与 `surfaceVariant`（蓝灰，chip 底、标签底）同屏是两个色系，
 * 界面读成"新主色 + 旧蓝灰"的半新半旧状态。
 */
internal fun seedColorScheme(darkTheme: Boolean, argb: Int): androidx.compose.material3.ColorScheme {
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
    // 次级族 = 降饱和的种子：与主色同色相才不成两套，但必须比主色"退一步"，
    // 否则整屏都是同一个颜色的噪声。明度带仍走 toneForTheme，保证换到深色主题不糊。
    val secondary = toneForTheme(lerp(seed, Color.Gray, 0.55f), darkTheme)
    val secondaryContainer = lerp(
        start = secondary,
        stop = if (darkTheme) Color.Black else Color.White,
        fraction = if (darkTheme) 0.55f else 0.75f,
    )
    val base = if (darkTheme) DarkColors else LightColors
    return base.copy(
        primary = seed,
        onPrimary = onSeed,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = secondary,
        onSecondary = if (secondary.luminance() > 0.5f) Color.Black else Color.White,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onContainer,
        // 玻璃降级底板画的就是 surfaceTint（见 LiquidGlass 的 fallback 路径）：
        // 不跟着种子走的话，换了主题色之后关闭玻璃仍是一块北航蓝底。
        surfaceTint = seed,
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
