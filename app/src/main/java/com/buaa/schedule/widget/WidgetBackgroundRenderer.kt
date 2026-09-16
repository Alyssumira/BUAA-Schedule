package com.buaa.schedule.widget

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withClip

/**
 * 小组件“玻璃化”背景渲染：把系统壁纸采样/模糊成一张静态位图。
 *
 * 不是实时模糊；只在组件更新时生成一次，之后由 Launcher 静态显示，因此耗电可控。
 * RemoteViews 通过 `setImageViewBitmap` 把该位图设为根背景。
 */
object WidgetBackgroundRenderer {

    private const val TARGET_WIDTH = 480
    private const val TARGET_HEIGHT = 320

    /**
     * ⚠️ Android 14（API 34）起 `WallpaperManager.getDrawable()` 需要
     * `MANAGE_EXTERNAL_STORAGE` 或签名级的 `READ_WALLPAPER_INTERNAL`，普通应用不再能读系统壁纸。
     * 因此 API 34+ 直接返回 null，组件回退纯色圆角底 —— 配置页的"壁纸模糊"开关
     * 在这些系统上不再生效，这是平台隐私限制而不是渲染失败。
     */
    @SuppressLint("MissingPermission") // API 34+ 已提前返回；更低版本读取壁纸无需该权限
    fun render(context: Context, appearance: WidgetAppearance): Bitmap? {
        if (!appearance.blurBackground) return null
        if (Build.VERSION.SDK_INT >= 34) return null
        return runCatching {
            val wallpaper = WallpaperManager.getInstance(context).drawable
                ?: return@runCatching null
            // base 可能是系统 WallpaperManager 持有的那张（BitmapDrawable 直接返回），
            // 也可能是我们自己新建的。只有后者归我们回收，用 ownedBase 记下来。
            var ownedBase: Bitmap? = null
            val base = if (
                wallpaper is android.graphics.drawable.BitmapDrawable &&
                wallpaper.bitmap != null &&
                !wallpaper.bitmap.isRecycled
            ) {
                wallpaper.bitmap
            } else {
                drawableToBitmap(wallpaper).also { ownedBase = it }
            }
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

                        val cornerRadius = appearance.cornerRadiusPx(output.width)
                        val rect = RectF(0f, 0f, output.width.toFloat(), output.height.toFloat())
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

                        canvas.withClip(android.graphics.Path().apply {
                            addRoundRect(rect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                        }) {
                            drawBitmap(blurred, 0f, 0f, paint)

                            // 叠加用户背景色与透明度，保持与普通配色一致的观感
                            val tint = appearance.resolvedBackground(context)
                            paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_OVER)
                            paint.alpha = (appearance.alphaFraction * 255).toInt().coerceIn(0, 255)
                            drawRect(rect, paint)
                        }

                        // output 要交给 RemoteViews，不能回收；
                        // small / blurred / ownedBase 是纯中间产物，必须回收 ——
                        // 一张 1080x2400 的壁纸 = 约 10MB，组件每次刷新都漏一份，
                        // 几次之后就会把进程推到 OOM 边缘。
                        output
                    } finally {
                        blurred.recycle()
                    }
                } finally {
                    small.recycle()
                }
            } finally {
                ownedBase?.recycle()
            }
        }.getOrNull()
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

private fun WidgetAppearance.cornerRadiusPx(targetWidth: Int): Float {
    val cornerDp = WidgetAppearance.CORNER_RADII_DP[cornerBucket.coerceIn(0, WidgetAppearance.CORNER_RADII_DP.lastIndex)]
    // 粗略按 targetWidth 换算：480dp -> 4x 密度，实际由 Launcher 缩放，够用即可
    return cornerDp * 4f
}