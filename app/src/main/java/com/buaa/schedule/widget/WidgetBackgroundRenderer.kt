package com.buaa.schedule.widget

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.decodeSampledWallpaper

/**
 * 小组件“玻璃化”背景渲染：把壁纸采样/模糊成一张静态位图。
 *
 * 不是实时模糊；只在组件更新时生成一次，之后由 Launcher 静态显示，因此耗电可控。
 * RemoteViews 通过 `setImageViewBitmap` 把该位图设为根背景。
 *
 * 产出的位图**已经把圆角、用户底色与透明度烘进去**，所以调用方（WidgetCommon）
 * 在位图成功时不能再对同一个 ImageView 下纯色 `setColorFilter` —— 那会把这张图
 * 重新糊成一块实色板，开了开关却和纯色底一模一样。
 *
 * 圆角是**按实例**烘的：画布是固定的 480x320，而背景层 `scaleType="fitXY"` 会把它
 * 非等比拉到组件真实尺寸上，所以半径得按两轴各自的倍数反推（[WidgetCornerRadii]），
 * 否则用户选的 20dp 到桌面上会变成 53dp，并且是个椭圆。
 */
object WidgetBackgroundRenderer {

    /**
     * 烘焙画布的尺寸（px）。
     *
     * 刻意保持 480x320 不变：这张位图要经 RemoteViews 走 binder 事务，
     * ARGB_8888 下约 0.6 MB，放大到 900x550 就是 1.9 MB，有 TransactionTooLargeException 的风险；
     * 模糊的观感本来就来自下面那次「先缩到 1/4 再放回」的廉价模糊。
     *
     * internal 是给圆角烘焙（[WidgetCornerRadii]）和它的单测用的：换算必须按**真实**画布算。
     */
    internal const val TARGET_WIDTH = 480
    internal const val TARGET_HEIGHT = 320

    /** 应用内背景的 prefs 与自选壁纸的键（与 Personalization.load 同一份） */
    private const val SETTINGS_PREFS = "schedule_settings"
    private const val KEY_WALLPAPER_URI = "wallpaper_uri"
    private const val KEY_USE_SYSTEM_WALLPAPER = "wallpaper_use_system"

    /**
     * 生成一张玻璃背景。
     *
     * 圆角要按**这一个实例**的真实尺寸来烘焙，所以得把 appWidgetId 和宿主句柄传进来：
     * 背景层是 fitXY，画布会被拉到组件尺寸上，半径不除回去就不是用户选的那一档
     * （详见 [WidgetCornerRadii]）。
     */
    fun render(
        context: Context,
        appearance: WidgetAppearance,
        appWidgetId: Int,
        appWidgetManager: AppWidgetManager,
    ): Bitmap? {
        if (!appearance.blurBackground) return null
        // 尺寸在 runCatching 之外取：取不到尺寸不等于取不到壁纸，前者有明写的兜底口径
        // （[WidgetCornerRadii.bake]），而后者才是"这张背景画不出来"。两步 IPC 各自吞异常，
        // 见 [widgetSizePx]。
        val widgetSize = widgetSizePx(context, appWidgetManager, appWidgetId)
        return runCatching {
            // base 可能是系统 WallpaperManager 持有的那张（不归我们），也可能是我们自己
            // 解码新建的。只有后者要回收 —— 一张 1080x1920 = 约 8MB，每次刷新都漏一份，
            // 几次之后就会把进程推到 OOM 边缘。
            val source = wallpaperSource(context) ?: return@runCatching null
            val base = source.bitmap
            if (base.isRecycled) return@runCatching null

            try {
                // 先缩小再放大，得到廉价的模糊效果；后续如需更强可换成 RenderEffect/高斯模糊
                val small = base.scale(TARGET_WIDTH / 4, TARGET_HEIGHT / 4, true)
                try {
                    val blurred = small.scale(TARGET_WIDTH, TARGET_HEIGHT, true)
                    try {
                        val output =
                            createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(output)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                        val radii = WidgetCornerRadii.bake(
                            cornerDp = appearance.cornerRadiusDp(),
                            density = context.resources.displayMetrics.density,
                            canvasWidthPx = output.width,
                            canvasHeightPx = output.height,
                            size = widgetSize,
                        )
                        val rect = RectF(0f, 0f, output.width.toFloat(), output.height.toFloat())
                        canvas.drawRoundRect(rect, radii.radiusX, radii.radiusY, paint)

                        canvas.withClip(android.graphics.Path().apply {
                            addRoundRect(
                                rect,
                                floatArrayOf(
                                    radii.radiusX, radii.radiusY,
                                    radii.radiusX, radii.radiusY,
                                    radii.radiusX, radii.radiusY,
                                    radii.radiusX, radii.radiusY,
                                ),
                                android.graphics.Path.Direction.CW,
                            )
                        }) {
                            drawBitmap(blurred, 0f, 0f, paint)

                            // 叠加用户背景色与透明度，保持与普通配色一致的观感
                            val tint = appearance.resolvedBackground(context)
                            paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_OVER)
                            paint.alpha = (appearance.alphaFraction * 255).toInt().coerceIn(0, 255)
                            drawRect(rect, paint)
                        }

                        // output 要交给 RemoteViews，不能回收
                        output
                    } finally {
                        blurred.recycle()
                    }
                } finally {
                    small.recycle()
                }
            } finally {
                if (source.owned) base.recycle()
            }
        }.getOrNull()
    }

    /**
     * 这一个组件实例的真实尺寸（px）。
     *
     * 两条来源各自吞异常：`getAppWidgetOptions` 对没绑定的 id 会抛，
     * `getAppWidgetInfo` 对失效的 id 直接返回 null —— 那都不是"画不出背景"，
     * 只是少了尺寸信息，落到 [WidgetCornerRadii.resolveSizePx] 的下几档口径。
     * 取数顺序与判据都在那边，这里只负责把它变成两个 Bundle 读取。
     */
    private fun widgetSizePx(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ): WidgetSizePx? {
        var optionsWidthDp = 0
        var optionsHeightDp = 0
        runCatching {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            optionsWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            optionsHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        }
        var infoWidthDp = 0
        var infoHeightDp = 0
        runCatching {
            val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
            if (info != null) {
                infoWidthDp = info.minWidth
                infoHeightDp = info.minHeight
            }
        }
        return WidgetCornerRadii.resolveSizePx(
            optionsWidthDp = optionsWidthDp,
            optionsHeightDp = optionsHeightDp,
            infoWidthDp = infoWidthDp,
            infoHeightDp = infoHeightDp,
            density = context.resources.displayMetrics.density,
        )
    }

    /** 一张壁纸位图 + 它是不是我们新建的（新建的才归 [render] 回收） */
    private class Source(val bitmap: Bitmap, val owned: Boolean)

    /**
     * 取一张可糊的壁纸。走哪一条由 [WidgetGlassSource.decide] 定，
     * 而那个答案与配置页上那句话说的是**同一件事**（判据与理由都写在那儿）：
     * - 开关开着时优先系统源、读不到再兜底自选；
     * - 用户关掉「使用桌面壁纸」时**只**认自选那张；
     * - 两条都没有时返回 null，调用方回退纯色圆角底。
     *
     * 这里只负责把判据落成两次取图，不再自己重排先后 —— 各判一次就会出现
     * "开关能拨、拨完没反应"（真机实测无源时整块 tile 像素差 0/258258）。
     */
    private fun wallpaperSource(context: Context): Source? {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val picked: () -> Source? = {
            prefs.getString(KEY_WALLPAPER_URI, null)?.let { uri ->
                decodeSampledWallpaper(context, uri)?.let { Source(it, owned = true) }
            }
        }
        return when (
            WidgetGlassSource.decide(
                systemWallpaperReadable = WidgetGlassSource.systemWallpaperReadable(Build.VERSION.SDK_INT),
                useSystemWallpaper = prefs.getBoolean(
                    KEY_USE_SYSTEM_WALLPAPER,
                    Personalization.DEFAULT_USE_SYSTEM_WALLPAPER,
                ),
                hasPickedImage = WidgetGlassSource.hasPickedImage(
                    prefs.getString(KEY_WALLPAPER_URI, null),
                ),
            )
        ) {
            GlassSource.SystemWallpaperThenPicked -> systemSource(context) ?: picked()
            GlassSource.PickedImage -> picked()
            GlassSource.NoSourceWallpaperReadBlocked,
            GlassSource.NoSourceSystemWallpaperOff,
            -> null
        }
    }

    @SuppressLint("MissingPermission") // API 34+ 已提前返回；更低版本读取壁纸无需该权限
    private fun systemSource(context: Context): Source? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val wallpaper = WallpaperManager.getInstance(context).drawable ?: return null
        return if (wallpaper is android.graphics.drawable.BitmapDrawable &&
            wallpaper.bitmap != null &&
            !wallpaper.bitmap.isRecycled
        ) {
            // 这张是系统持有的原图，不归我们回收
            Source(wallpaper.bitmap, owned = false)
        } else {
            Source(drawableToBitmap(wallpaper), owned = true)
        }
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: TARGET_WIDTH
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: TARGET_HEIGHT
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}

/** 用户选的那一档圆角（dp）。越界下标夹到最近档位，与 `cornerDrawableRes` 同一口径。 */
private fun WidgetAppearance.cornerRadiusDp(): Int =
    WidgetAppearance.CORNER_RADII_DP[cornerBucket.coerceIn(0, WidgetAppearance.CORNER_RADII_DP.lastIndex)]
