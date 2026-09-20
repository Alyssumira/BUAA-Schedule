package com.buaa.schedule.widget

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Log
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

    private const val TAG = "WidgetBackgroundRenderer"

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
                        // 这里以前先拿默认黑色不透明 Paint 垫了一遍整块圆角矩形：画布出生就是全透明，
                        // 四角的留空本来就由下面的 clip 负责，那一步唯一的效果是让带 alpha 通道的
                        // 壁纸透出黑色而不是桌面。

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

                            // 叠加用户背景色与透明度。透明度**必须折进这层的颜色本身**：
                            // 以前这里是 PorterDuffColorFilter(tint, SRC_OVER) —— 颜色滤波器的输出
                            // *替换*被画像素的颜色（含 alpha），SRC_OVER 模式下 tint 自身 alpha=255
                            // 时复合结果恒不透明，paint.alpha 只是喂进滤波器的 src alpha，
                            // 会被滤波结果整个顶掉。装机实测（id=8）：w8.alpha 从 80 拨到 0，
                            // tile 内部像素一动不动，始终是底板色 (22,32,58)，糊过的壁纸被盖死。
                            // 所以改走 drawRect 的默认 SRC_OVER 光栅管线，alpha 经 [glassTintArgb]
                            // 进最终颜色。期望（自选壁纸是纯白，底面色 (22,32,58)）：
                            // alpha=80% -> 0.8*(22,32,58) + 0.2*(255,255,255) ≈ (69,77,97)；
                            // alpha=0%  -> 看到糊过的壁纸本身。逐格钉在 WidgetBackgroundRendererTest。
                            val tint = appearance.resolvedBackground(context)
                            paint.colorFilter = null
                            paint.color = glassTintArgb(tint, appearance.alphaPercent)
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
        }.onFailure {
            // 这里以前什么都不留：玻璃背景画不出来会静默回退纯色底，一整轮定位全靠
            // 装机量像素。语义不变（返回 null = 回退纯色底），只补证据。
            Log.w(TAG, "玻璃背景这次画不出来，回退纯色底（appWidgetId=$appWidgetId）", it)
        }.getOrNull()
    }

    /**
     * 这一个组件实例的真实尺寸（px）。
     *
     * 两条来源各自吞异常：`getAppWidgetOptions` 对没绑定的 id 会抛，
     * `getAppWidgetInfo` 对失效的 id 直接返回 null —— 那都不是"画不出背景"，
     * 只是少了尺寸信息，落到 [WidgetCornerRadii.resolveSizePx] 的下几档口径。
     * 取数顺序与判据都在那边，这里只负责把它变成两个 Bundle 读取。
     *
     * options 的 MIN/MAX 四枚都要读：真实绘制尺寸落在 `[MIN, MAX]` 区间里，只读 MIN
     * 会把纵轴的拉伸倍数低估（真机 id=8 实测 MIN_HEIGHT=137dp 而真实高 224dp，
     * 20dp 那一档烘出来纵轴 32.4dp），判据那边取 MAX 优先就是为这个。
     */
    private fun widgetSizePx(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ): WidgetSizePx? {
        var optionsMaxWidthDp = 0
        var optionsMaxHeightDp = 0
        var optionsMinWidthDp = 0
        var optionsMinHeightDp = 0
        runCatching {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            optionsMaxWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            optionsMaxHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            optionsMinWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            optionsMinHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
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
            optionsMaxWidthDp = optionsMaxWidthDp,
            optionsMaxHeightDp = optionsMaxHeightDp,
            optionsMinWidthDp = optionsMinWidthDp,
            optionsMinHeightDp = optionsMinHeightDp,
            infoWidthDp = infoWidthDp,
            infoHeightDp = infoHeightDp,
            density = context.resources.displayMetrics.density,
        )
    }

    /** 一张壁纸位图 + 它是不是我们新建的（新建的才归 [render] 回收） */
    private class Source(val bitmap: Bitmap, val owned: Boolean)

    /**
     * 这一次「玻璃感壁纸背景」到底有没有图源。
     *
     * 判据本体在 [WidgetGlassSource]（不 import 任何 android 类型，所以那张表能在 JVM 单测里
     * 逐格钉住）；这里只把 prefs 与这台设备的 API 档次翻译成它的三个入参。
     *
     * 配置页那句说明读的也是这个函数 —— 两边各判一次就会出现"开关能拨、拨完没反应"
     * （真机实测：无源时整块 tile 的像素差 0 / 258258）。
     */
    internal fun availability(context: Context): GlassSource {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        return WidgetGlassSource.decide(
            systemWallpaperReadable = WidgetGlassSource.systemWallpaperReadable(Build.VERSION.SDK_INT),
            useSystemWallpaper = prefs.getBoolean(
                KEY_USE_SYSTEM_WALLPAPER,
                Personalization.DEFAULT_USE_SYSTEM_WALLPAPER,
            ),
            hasPickedImage = WidgetGlassSource.hasPickedImage(
                prefs.getString(KEY_WALLPAPER_URI, null),
            ),
        )
    }

    /**
     * 取一张可糊的壁纸。走哪一条由 [availability] 定：
     * - 开关开着且这台设备读得到桌面壁纸时**优先**系统源、自选那张兜底；
     * - 用户关掉「使用桌面壁纸」时**只**认自选那张 —— 他刚说不想用桌面壁纸，
     *   组件却还在糊桌面壁纸，两边就对不上了；
     * - 判到没有图源时直接 null，调用方回退纯色圆角底。
     *
     * 这里只负责把判据落成两次取图，不再自己重排先后。
     */
    private fun wallpaperSource(context: Context): Source? {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val picked: () -> Source? = {
            prefs.getString(KEY_WALLPAPER_URI, null)?.let { uri ->
                decodeSampledWallpaper(context, uri)?.let { Source(it, owned = true) }
            }
        }
        return when (availability(context)) {
            GlassSource.SystemWallpaperThenPicked -> systemSource(context) ?: picked()
            GlassSource.PickedImage -> picked()
            GlassSource.NoSourceWallpaperReadBlocked,
            GlassSource.NoSourceSystemWallpaperOff,
            -> null
        }
    }

    @SuppressLint("MissingPermission") // 版本闸门在 [WidgetGlassSource] 那一头；更低版本读取壁纸无需该权限
    private fun systemSource(context: Context): Source? {
        // 闸门不在这个文件里再写一遍 34：配置页那句"这台设备读不读得到桌面壁纸"
        // 与这里必须同进同退，两处各写一个数字迟早对不上。
        if (!WidgetGlassSource.systemWallpaperReadable(Build.VERSION.SDK_INT)) return null
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

/**
 * 玻璃背景 tint 层的最终 ARGB：把透明度百分比折进底色的高字节。
 *
 * 这是「alpha 到底进没进颜色」唯一该问的地方，也是 [render] 里那次修复的全部算式 ——
 * 抽成本函数只因为它得能在 JVM 单测里逐格钉住（没有 Robolectric，碰 `Canvas`/`Paint`
 * 的路径根本画不了，见 WidgetBackgroundRendererTest）。位运算手写、不 import
 * `android.graphics.Color`：与 [WidgetGlassSource] 同一套理由。
 *
 * 为什么非要把 alpha 折进颜色、而不是 `PorterDuffColorFilter` + `paint.alpha`：
 * 颜色滤波器的输出**替换**被画像素的颜色（含 alpha），SRC_OVER 模式是「常量色 tint
 * 盖在 src 上」，tint 的 alpha=255 时结果恒不透明，`paint.alpha` 只是喂进滤波器的
 * src alpha，被滤波结果整个顶掉 —— 透明度滑杆在这条分支上从来就没生效过。
 * 交回默认的 SRC_OVER 光栅管线后，本函数的 alpha 字节才是真正参与复合的那个数：
 * alpha=80%、纯白壁纸、底色 (22,32,58) 时显示 0.8*(22,32,58)+0.2*(255,255,255)
 * ≈ (69,77,97)；alpha=0% 时壁纸原样透出。
 *
 * @param tintArgb 底色（[WidgetAppearance.resolvedBackground]，高字节按不透明对待）
 * @param alphaPercent 背景不透明度 0..100；越界夹到端点，与 `alphaFraction` 同一口径
 */
internal fun glassTintArgb(tintArgb: Int, alphaPercent: Int): Int =
    ((alphaPercent.coerceIn(0, 100) * 255 / 100) shl 24) or (tintArgb and 0x00FFFFFF)

/** 用户选的那一档圆角（dp）。越界下标夹到最近档位，与 `cornerDrawableRes` 同一口径。 */
private fun WidgetAppearance.cornerRadiusDp(): Int =
    WidgetAppearance.CORNER_RADII_DP[cornerBucket.coerceIn(0, WidgetAppearance.CORNER_RADII_DP.lastIndex)]
