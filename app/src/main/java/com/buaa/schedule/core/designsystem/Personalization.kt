package com.buaa.schedule.core.designsystem

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * 全局个性化状态（内存态），修改后自动触发 Compose 重组。
 * 持久化通过 SharedPreferences 保存。
 *
 * 壁纸相关的三个参数（模糊 / 亮度 / 缩放）只影响"看得见的背景"，
 * 不参与玻璃层的反射采样（玻璃始终采样原始壁纸，见 SceneBackground 的注释）。
 */
object Personalization {
    /**
     * 玻璃档位：0 关闭（大面积面板退化为普通卡片，仅留小面积玻璃）/ 1 标准 / 2 增强。
     * 设置页只暴露「关闭 / 开启」两项（旧数据里的增强档在读入时收敛到标准）。
     *
     * 默认关闭：设置页与导入页整屏都是逐条 item 的 PANEL 玻璃，
     * 几十个 AGSL 表面的开销全花在大块面板上，而观感上反而是小面积玻璃更好看。
     */
    var glassTier by mutableIntStateOf(DesignTokens.GLASS_TIER_OFF)
    var cardAlpha by mutableFloatStateOf(0.88f)
    var wallpaperUri: String? by mutableStateOf(null)

    /**
     * 未选择自定义壁纸时，是否把**系统桌面壁纸**提取出来做课表背景（默认开）。
     * 用户拾取的壁纸优先级更高；关闭后回退到北航蓝渐变。
     */
    var useSystemWallpaper by mutableStateOf(DEFAULT_USE_SYSTEM_WALLPAPER)

    /** Material You 动态取色（Android 12+）：开关后整个主题色随壁纸变化 */
    var useDynamicColor by mutableStateOf(DEFAULT_USE_DYNAMIC_COLOR)

    /** 手动种子色（ARGB Int）。null = 使用默认北航蓝（动态取色开启时优先于它） */
    var seedColorArgb: Int? by mutableStateOf(null)

    /** 周视图网格：0 = 节次行（默认），1 = 24 小时连续时间轴（拾光式） */
    var weekGridMode by mutableIntStateOf(WEEK_GRID_PERIOD)

    /** 周视图行高缩放：0.75 = 紧凑，1.0 = 默认，1.5 = 宽松 */
    var weekRowScale by mutableFloatStateOf(1f)

    /** 周视图视口均分行高：开启后按屏幕高度自动分配行高，替代固定行高 */
    var weekFitViewport by mutableStateOf(false)

    /** 周视图卡片圆角（dp）：小屏 10，宽屏/大卡片 14；用户滑块在 0–24 间覆盖 */
    var weekCornerRadiusDp by mutableFloatStateOf(0f)

    /** 壁纸模糊强度（dp，0 = 不模糊）。API 31 以下由 Compose 自动降级为无模糊。 */
    var wallpaperBlurDp by mutableFloatStateOf(DEFAULT_BLUR_DP)

    /** 壁纸亮度系数（0.35 = 压得很暗，1.0 = 原始亮度） */
    var wallpaperBrightness by mutableFloatStateOf(DEFAULT_BRIGHTNESS)

    /** 壁纸缩放（1.0 = 铺满裁切，2.0 = 放大两倍；等价"取景"的拉近） */
    var wallpaperZoom by mutableFloatStateOf(DEFAULT_ZOOM)

    const val MIN_BLUR_DP = 0f
    const val MAX_BLUR_DP = 20f
    const val MIN_BRIGHTNESS = 0.35f
    const val MAX_BRIGHTNESS = 1f
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 2.5f
    const val MIN_WEEK_ROW_SCALE = 0.75f
    const val MAX_WEEK_ROW_SCALE = 1.5f
    const val MIN_WEEK_CORNER = 0f
    const val MAX_WEEK_CORNER = 24f

    const val DEFAULT_BLUR_DP = 0f
    const val DEFAULT_BRIGHTNESS = 1f
    const val DEFAULT_ZOOM = 1f
    const val DEFAULT_USE_SYSTEM_WALLPAPER = true
    const val DEFAULT_USE_DYNAMIC_COLOR = false
    const val WEEK_GRID_PERIOD = 0
    const val WEEK_GRID_TIME_24H = 1

    /** 背景是否为真实壁纸（自定义拾取 或 提取的系统桌面壁纸） */
    val hasWallpaperBackdrop: Boolean
        get() = wallpaperUri != null || useSystemWallpaper

    /** 与默认值是否有差异（设置页用来决定是否显示"恢复默认"） */
    val hasWallpaperTuning: Boolean
        get() = wallpaperBlurDp != DEFAULT_BLUR_DP ||
            wallpaperBrightness != DEFAULT_BRIGHTNESS ||
            wallpaperZoom != DEFAULT_ZOOM

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
        val hasTier = prefs.contains("glass_tier")
        val hasLegacy = prefs.contains("glass_enabled")
        val legacyEnabled = prefs.getBoolean("glass_enabled", false)
        val tier = prefs.getInt("glass_tier", Int.MIN_VALUE)
        // 设置项只有「关闭 / 开启」两态，读档即收敛：
        // 老数据里的增强档（2）落到标准，非法值落到关闭。
        // 全新安装（两个键都没有）必须是关闭——不能拿 legacyEnabled 的 true 当默认，
        // 否则新用户一上手就是「开启」，与 DesignTokens.surfaceUsesGlass 的默认取向相反。
        glassTier = when {
            hasTier -> tier.coerceIn(
                DesignTokens.GLASS_TIER_OFF,
                DesignTokens.GLASS_TIER_STANDARD,
            )
            hasLegacy && !legacyEnabled -> DesignTokens.GLASS_TIER_OFF
            hasLegacy -> DesignTokens.GLASS_TIER_STANDARD
            else -> DesignTokens.GLASS_TIER_OFF
        }
        cardAlpha = prefs.getFloat("glass_alpha", 0.88f)
        wallpaperUri = prefs.getString("wallpaper_uri", null)
        // 老版本没有这个键：默认改为"跟随桌面壁纸"，与新版一致
        useSystemWallpaper = prefs.getBoolean("wallpaper_use_system", DEFAULT_USE_SYSTEM_WALLPAPER)
        useDynamicColor = prefs.getBoolean("use_dynamic_color", DEFAULT_USE_DYNAMIC_COLOR)
        seedColorArgb = prefs.getInt("theme_seed_color", Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
        // 切换时间轴的唯一入口曾是一颗无字面图标，落盘的 1 多半是误触，
        // 不代表偏好：新版首次读盘时统一收敛回节次行视图，此后正常读盘。
        weekGridMode = if (prefs.getBoolean("week_grid_mode_declared", false)) {
            prefs.getInt("week_grid_mode", WEEK_GRID_PERIOD)
        } else {
            prefs.edit {
                putBoolean("week_grid_mode_declared", true)
                putInt("week_grid_mode", WEEK_GRID_PERIOD)
            }
            WEEK_GRID_PERIOD
        }
        weekRowScale = prefs.getFloat("week_row_scale", 1f)
            .coerceIn(MIN_WEEK_ROW_SCALE, MAX_WEEK_ROW_SCALE)
        weekCornerRadiusDp = prefs.getFloat("week_corner_radius_dp", 0f)
            .coerceIn(MIN_WEEK_CORNER, MAX_WEEK_CORNER)
        weekFitViewport = prefs.getBoolean("week_fit_viewport", false)
        wallpaperBlurDp = prefs.getFloat("wallpaper_blur_dp", DEFAULT_BLUR_DP)
            .coerceIn(MIN_BLUR_DP, MAX_BLUR_DP)
        wallpaperBrightness = prefs.getFloat("wallpaper_brightness", DEFAULT_BRIGHTNESS)
            .coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        wallpaperZoom = prefs.getFloat("wallpaper_zoom", DEFAULT_ZOOM)
            .coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
        prefs.edit {
            putInt("glass_tier", glassTier)
            putFloat("glass_alpha", cardAlpha)
            putString("wallpaper_uri", wallpaperUri)
            putBoolean("wallpaper_use_system", useSystemWallpaper)
            putBoolean("use_dynamic_color", useDynamicColor)
            putInt("theme_seed_color", seedColorArgb ?: Int.MIN_VALUE)
            putInt("week_grid_mode", weekGridMode)
            putFloat("week_row_scale", weekRowScale)
            putFloat("week_corner_radius_dp", weekCornerRadiusDp)
            putBoolean("week_fit_viewport", weekFitViewport)
            putFloat("wallpaper_blur_dp", wallpaperBlurDp)
            putFloat("wallpaper_brightness", wallpaperBrightness)
            putFloat("wallpaper_zoom", wallpaperZoom)
        }
    }

    /** 恢复壁纸调参默认值（不动壁纸本身与玻璃设置） */
    fun resetWallpaperTuning(context: Context) {
        wallpaperBlurDp = DEFAULT_BLUR_DP
        wallpaperBrightness = DEFAULT_BRIGHTNESS
        wallpaperZoom = DEFAULT_ZOOM
        save(context)
    }
}
