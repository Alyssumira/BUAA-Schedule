package com.buaa.schedule.widget

import android.annotation.SuppressLint
import android.app.WallpaperManager
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

    fun render(context: Context, appearance: WidgetAppearance): Bitmap? {
        if (!appearance.blurBackground) return null
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
                            // 旧口径本来就不看组件真实尺寸，这里先占位传 null，接线在下一步
                            size = null,
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

    /** 一张壁纸位图 + 它是不是我们新建的（新建的才归 [render] 回收） */
    private class Source(val bitmap: Bitmap, val owned: Boolean)

    /**
     * 取一张可糊的壁纸。两条来源，先后顺序跟着 App 内那项「使用桌面壁纸」：
     * - 系统桌面壁纸——Android 13 及以下可读；
     * - Android 14 起（API 34）`WallpaperManager.getDrawable()` 需要 `MANAGE_EXTERNAL_STORAGE`
     *   或签名级的 `READ_WALLPAPER_INTERNAL`，普通应用不再能读，于是只剩用户在
     *   App 内「背景」里自己挑的那张图（SAF 授权长期有效）。
     *
     * 用户关掉「使用桌面壁纸」时**只**认自选那张：他刚说不想用桌面壁纸，
     * 组件却还在糊桌面壁纸，两边就对不上了。
     * 两条都拿不到时返回 null，调用方回退纯色圆角底 —— 配置页对此有说明。
     */
    private fun wallpaperSource(context: Context): Source? {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val picked: () -> Source? = {
            prefs.getString(KEY_WALLPAPER_URI, null)?.let { uri ->
                decodeSampledWallpaper(context, uri)?.let { Source(it, owned = true) }
            }
        }
        return if (prefs.getBoolean(KEY_USE_SYSTEM_WALLPAPER, Personalization.DEFAULT_USE_SYSTEM_WALLPAPER)) {
            systemSource(context) ?: picked()
        } else {
            picked()
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
