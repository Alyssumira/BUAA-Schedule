package com.buaa.schedule.widget

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import com.buaa.schedule.R
import com.buaa.schedule.core.designsystem.readableLuminance

/**
 * 单个桌面组件实例的外观配置。
 *
 * 桌面组件运行在 Launcher 进程，只能通过 [android.widget.RemoteViews] 改属性，
 * 因此这里把可调项收敛到 **RemoteViews 真正支持的几种操作**：
 * - 背景色 → `ImageView.setColorFilter(int)`（布局里用纯白圆角 shape 做底）
 * - 不透明度 → `View.setAlpha(float)`
 * - 圆角 → `ImageView.setImageResource(int)`（6 档预置圆角 drawable）
 * - 文字颜色 → `TextView.setTextColor(int)`（自动 / 强制浅色 / 强制深色）
 *
 * 配色来源有两种：
 * - [COLOR_MODE_CUSTOM]：使用下面 `backgroundColor` 的自定义色；
 * - [COLOR_MODE_SYSTEM]：Material You 动态取色（见 [resolveSystemWidgetColor]），
 *   从壁纸派生的系统色自动适配浅/深色桌面。
 *
 * 真实背景模糊在 RemoteViews 下无法实现（拿不到壁纸位图），故不做。
 */
data class WidgetAppearance(
    /** 背景色（ARGB，不含透明度信息，透明度走 [alphaPercent]）；仅 [COLOR_MODE_CUSTOM] 生效 */
    val backgroundColor: Int = DEFAULT_BACKGROUND,
    /** 背景不透明度 0..100（0 完全透明，100 完全不透明） */
    val alphaPercent: Int = DEFAULT_ALPHA_PERCENT,
    /** 圆角档位下标，见 [CORNER_LABELS] */
    val cornerBucket: Int = DEFAULT_CORNER_BUCKET,
    /** 文字颜色模式，见 [TEXT_AUTO] / [TEXT_LIGHT] / [TEXT_DARK] */
    val textMode: Int = TEXT_AUTO,
    /** 是否显示标题行（"今日课程" 等） */
    val showTitle: Boolean = true,
    /** 配色来源：[COLOR_MODE_CUSTOM] 自定义 / [COLOR_MODE_SYSTEM] 跟随系统取色 */
    val colorMode: Int = COLOR_MODE_CUSTOM,
    /** 是否使用壁纸模糊玻璃感背景（生成静态 Bitmap，非实时模糊） */
    val blurBackground: Boolean = false,
) {

    val cornerDrawableRes: Int
        get() = CORNER_DRAWABLES[cornerBucket.coerceIn(0, CORNER_DRAWABLES.lastIndex)]

    val alphaFraction: Float
        get() = alphaPercent.coerceIn(0, 100) / 100f

    /**
     * 外观指纹，喂给 `RemoteViewsFactory.getItemId`。
     *
     * `hasStableIds() = true` 时宿主按 id 复用已经绑定好的行视图：只改外观
     * （文字颜色 / 背景 / 圆角）而不改课程时，每行的 id 完全不变，于是配置页
     * 保存后组件仍然是旧配色。把指纹加进 id（同一批行共用同一个偏移量，
     * 行间唯一性不受影响），换外观即换 id，宿主只能重新向工厂取行。
     */
    fun viewIdStamp(): Long = hashCode().toLong()

    /**
     * 实际用于着色的背景色。
     * 跟随系统取色时解析 Material You 动态色；API < 31 或解析失败回退自定义色。
     */
    fun resolvedBackground(context: Context): Int = when (colorMode) {
        COLOR_MODE_SYSTEM -> resolveSystemWidgetColor(context) ?: backgroundColor
        else -> backgroundColor
    }

    /** 标题字色（ARGB）。[background] 必须传"实际着色后的背景色"。 */
    fun titleColorFor(background: Int): Int = when (textMode) {
        TEXT_LIGHT -> COLOR_TEXT_LIGHT
        TEXT_DARK -> COLOR_TEXT_DARK
        else -> if (autoShouldUseDarkText(background, alphaPercent)) COLOR_TEXT_DARK
        else COLOR_TEXT_LIGHT
    }

    fun bodyColorFor(background: Int): Int = when (textMode) {
        TEXT_LIGHT -> COLOR_TEXT_LIGHT_SUBTLE
        TEXT_DARK -> COLOR_TEXT_DARK_SUBTLE
        else -> if (autoShouldUseDarkText(background, alphaPercent)) COLOR_TEXT_DARK_SUBTLE
        else COLOR_TEXT_LIGHT_SUBTLE
    }

    companion object {
        const val DEFAULT_BACKGROUND = 0xFF16203A.toInt()
        const val DEFAULT_ALPHA_PERCENT = 80
        const val DEFAULT_CORNER_BUCKET = 3 // 20dp，与原静态素材一致

        const val TEXT_AUTO = 0
        const val TEXT_LIGHT = 1
        const val TEXT_DARK = 2

        const val COLOR_MODE_CUSTOM = 0
        const val COLOR_MODE_SYSTEM = 1

        val TEXT_MODE_LABELS = listOf("自动", "浅色文字", "深色文字")
        val COLOR_MODE_LABELS = listOf("自定义配色", "跟随系统取色")

        /** 圆角档位对应的实际 dp（配置页预览与 RemoteViews 素材一一对应） */
        val CORNER_RADII_DP = listOf(0, 8, 16, 20, 24, 28)
        val CORNER_LABELS = CORNER_RADII_DP.map { if (it == 0) "直角" else "${it}dp" }
        val CORNER_DRAWABLES = listOf(
            R.drawable.widget_bg_r0,
            R.drawable.widget_bg_r8,
            R.drawable.widget_bg_r16,
            R.drawable.widget_bg_r20,
            R.drawable.widget_bg_r24,
            R.drawable.widget_bg_r28,
        )

        /** 预设背景色：前三个是玻璃/中性底，其余取课程调色板同族，便于和课表呼应 */
        val PRESET_COLORS = listOf(
            0xFF16203A.toInt() to "深蓝玻璃",
            0xFF101014.toInt() to "墨黑",
            0xFF2A2F3A.toInt() to "石墨",
            0xFFF3F5FA.toInt() to "云白",
            0xFF1A73E8.toInt() to "北航蓝",
            0xFF5B4BD6.toInt() to "靛紫",
            0xFF1F6F5C.toInt() to "松绿",
            0xFF8E3B3B.toInt() to "砖红",
        )

        /**
         * 一键外观预设（Sleepy 的 themeKey 思路）：
         * 一次点选同时确定配色来源 / 底色 / 透明度 / 圆角 / 文字模式，
         * 显著扩展现成观感的数量，之后仍可在配置页逐项微调。
         */
        data class StylePreset(val label: String, val appearance: WidgetAppearance)

        val STYLE_PRESETS = listOf(
            // 跟随系统：Material You 动态取色，深浅桌面自动适配
            StylePreset("自动跟随", WidgetAppearance(colorMode = COLOR_MODE_SYSTEM, alphaPercent = 55, cornerBucket = 4)),
            StylePreset("浅玻璃", WidgetAppearance(backgroundColor = 0xFFEDF1F8.toInt(), alphaPercent = 52, cornerBucket = 4, textMode = TEXT_DARK)),
            StylePreset("深玻璃", WidgetAppearance(backgroundColor = 0xFF161B28.toInt(), alphaPercent = 62, cornerBucket = 4, textMode = TEXT_LIGHT)),
            StylePreset("墨黑磨砂", WidgetAppearance(backgroundColor = 0xFF0C0C10.toInt(), alphaPercent = 84, cornerBucket = 3, textMode = TEXT_LIGHT)),
            StylePreset("云白卡片", WidgetAppearance(backgroundColor = 0xFFFAFBFF.toInt(), alphaPercent = 95, cornerBucket = 3, textMode = TEXT_DARK)),
            StylePreset("北航蓝", WidgetAppearance(backgroundColor = 0xFF1A73E8.toInt(), alphaPercent = 88, cornerBucket = 3, textMode = TEXT_LIGHT)),
            StylePreset("靛紫流光", WidgetAppearance(backgroundColor = 0xFF5B4BD6.toInt(), alphaPercent = 82, cornerBucket = 3, textMode = TEXT_LIGHT)),
            StylePreset("松绿暗调", WidgetAppearance(backgroundColor = 0xFF123530.toInt(), alphaPercent = 78, cornerBucket = 3, textMode = TEXT_LIGHT)),
            StylePreset("砖红暗调", WidgetAppearance(backgroundColor = 0xFF3B1F1F.toInt(), alphaPercent = 78, cornerBucket = 3, textMode = TEXT_LIGHT)),
            StylePreset("护眼米灰", WidgetAppearance(backgroundColor = 0xFFE7E2D6.toInt(), alphaPercent = 90, cornerBucket = 3, textMode = TEXT_DARK)),
            StylePreset("浅蓝通透", WidgetAppearance(backgroundColor = 0xFFD6E7FF.toInt(), alphaPercent = 45, cornerBucket = 4, textMode = TEXT_DARK)),
            StylePreset("深蓝夜航", WidgetAppearance(backgroundColor = 0xFF0B1F3A.toInt(), alphaPercent = 80, cornerBucket = 4, textMode = TEXT_LIGHT)),
            StylePreset("暖阳米黄", WidgetAppearance(backgroundColor = 0xFFF5E9D6.toInt(), alphaPercent = 88, cornerBucket = 2, textMode = TEXT_DARK)),
            StylePreset("薄荷清透", WidgetAppearance(backgroundColor = 0xFFD9F2E8.toInt(), alphaPercent = 55, cornerBucket = 4, textMode = TEXT_DARK)),
        )

        private const val COLOR_TEXT_LIGHT = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_LIGHT_SUBTLE = 0xFFE8F0FE.toInt()
        private const val COLOR_TEXT_DARK = 0xFF14161C.toInt()
        private const val COLOR_TEXT_DARK_SUBTLE = 0xFF3A3F4B.toInt()

        /**
         * 自动文字色判定：按"背景色叠加到中性背板后的实际亮度"决定黑/白文字。
         *
         * 桌面组件的实际背景是壁纸，无法读取；这里用中灰（0.5）折算，
         * 使低不透明度时也能给出稳定的可读结果，而不是只看背景色本身。
         */
        fun autoShouldUseDarkText(background: Int, alphaPercent: Int): Boolean {
            val a = alphaPercent.coerceIn(0, 100) / 100f
            val backdrop = 0.5f
            val r = channel(background, 16) * a + backdrop * (1f - a)
            val g = channel(background, 8) * a + backdrop * (1f - a)
            val b = channel(background, 0) * a + backdrop * (1f - a)
            val composited = (0xFF shl 24) or
                (((r * 255f).toInt().coerceIn(0, 255)) shl 16) or
                (((g * 255f).toInt().coerceIn(0, 255)) shl 8) or
                ((b * 255f).toInt().coerceIn(0, 255))
            return Color(composited.toLong()).readableLuminance() > 0.45f
        }

        /** 取某条通道的 0..1 归一化值（shift = 16/8/0） */
        private fun channel(argb: Int, shift: Int): Float =
            ((argb shr shift) and 0xFF) / 255f
    }
}

/**
 * Material You 动态取色（官方做法：Android 12+ 让组件根主题使用
 * `@android:style/Theme.DeviceDefault.DayNight`，即可从壁纸提取 65 色动态色）。
 *
 * 这里在**更新时**用同一主题解析 `?android:attr/colorBackground`，得到壁纸派生的
 * 背景色后交给既有的 colorFilter 管线——因此不透明度 / 圆角 / 文字对比等配置全部继续生效，
 * 也不需要为动态色单独再做一套布局。
 *
 * API < 31 或解析失败时返回 null（调用方回退到自定义配色）。
 */
fun resolveSystemWidgetColor(context: Context): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return runCatching {
        val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_DayNight)
        val value = TypedValue()
        themed.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        value.data
    }.getOrNull()
}

/** 组件外观的持久化：按 `appWidgetId` 实例级保存，与 Launcher 上的每个实例一一对应 */
object WidgetAppearanceStore {

    private const val PREFS_NAME = "widget_appearance"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_ALPHA = "alpha"
    private const val KEY_CORNER = "corner"
    private const val KEY_TEXT_MODE = "text_mode"
    private const val KEY_SHOW_TITLE = "show_title"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_BLUR = "blur"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(appWidgetId: Int, field: String) = "w$appWidgetId.$field"

    /** 该实例是否已被用户配置过（用于区分"默认外观"与"自定义外观"） */
    fun isConfigured(context: Context, appWidgetId: Int): Boolean =
        prefs(context).getBoolean(key(appWidgetId, KEY_CONFIGURED), false)

    fun load(context: Context, appWidgetId: Int): WidgetAppearance {
        val p = prefs(context)
        val defaults = WidgetAppearance()
        if (!p.getBoolean(key(appWidgetId, KEY_CONFIGURED), false)) return defaults
        return WidgetAppearance(
            backgroundColor = p.getInt(key(appWidgetId, KEY_BACKGROUND), defaults.backgroundColor),
            alphaPercent = p.getInt(key(appWidgetId, KEY_ALPHA), defaults.alphaPercent),
            cornerBucket = p.getInt(key(appWidgetId, KEY_CORNER), defaults.cornerBucket),
            textMode = p.getInt(key(appWidgetId, KEY_TEXT_MODE), defaults.textMode),
            showTitle = p.getBoolean(key(appWidgetId, KEY_SHOW_TITLE), defaults.showTitle),
            colorMode = p.getInt(key(appWidgetId, KEY_COLOR_MODE), defaults.colorMode),
            blurBackground = p.getBoolean(key(appWidgetId, KEY_BLUR), defaults.blurBackground),
        )
    }

    fun save(context: Context, appWidgetId: Int, appearance: WidgetAppearance) {
        prefs(context).edit {
            putBoolean(key(appWidgetId, KEY_CONFIGURED), true)
            putInt(key(appWidgetId, KEY_BACKGROUND), appearance.backgroundColor)
            putInt(key(appWidgetId, KEY_ALPHA), appearance.alphaPercent.coerceIn(0, 100))
            putInt(key(appWidgetId, KEY_CORNER), appearance.cornerBucket)
            putInt(key(appWidgetId, KEY_TEXT_MODE), appearance.textMode)
            putBoolean(key(appWidgetId, KEY_SHOW_TITLE), appearance.showTitle)
            putInt(key(appWidgetId, KEY_COLOR_MODE), appearance.colorMode)
            putBoolean(key(appWidgetId, KEY_BLUR), appearance.blurBackground)
        }
    }

    /** 恢复默认外观（删除本实例的配置） */
    fun reset(context: Context, appWidgetId: Int): WidgetAppearance {
        val p = prefs(context)
        p.edit {
            remove(key(appWidgetId, KEY_CONFIGURED))
            remove(key(appWidgetId, KEY_BACKGROUND))
            remove(key(appWidgetId, KEY_ALPHA))
            remove(key(appWidgetId, KEY_CORNER))
            remove(key(appWidgetId, KEY_TEXT_MODE))
            remove(key(appWidgetId, KEY_SHOW_TITLE))
            remove(key(appWidgetId, KEY_COLOR_MODE))
            remove(key(appWidgetId, KEY_BLUR))
        }
        return WidgetAppearance()
    }

    /** 实例被移除时清理配置，避免 SharedPreferences 无限增长 */
    fun remove(context: Context, appWidgetIds: IntArray) {
        prefs(context).edit {
            appWidgetIds.forEach { id ->
                remove(key(id, KEY_CONFIGURED))
                remove(key(id, KEY_BACKGROUND))
                remove(key(id, KEY_ALPHA))
                remove(key(id, KEY_CORNER))
                remove(key(id, KEY_TEXT_MODE))
                remove(key(id, KEY_SHOW_TITLE))
                remove(key(id, KEY_COLOR_MODE))
                remove(key(id, KEY_BLUR))
            }
        }
    }
}
