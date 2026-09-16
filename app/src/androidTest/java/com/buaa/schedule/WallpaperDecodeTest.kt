package com.buaa.schedule

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.core.designsystem.decodeSampledWallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 自定义壁纸解码测试。
 *
 * 这里钉的是「选了一张图，背景到底能不能拿到位图」这条主路径：
 * 曾经 `inJustDecodeBounds` 那次解码的返回值被当成流是否打开成功的判据，
 * 而它按设计恒为 null，于是函数在任何输入上都返回 null ——
 * 界面表现为「选了壁纸但背景仍是渐变」，且编译与单元测试全都发现不了。
 */
@RunWith(AndroidJUnit4::class)
class WallpaperDecodeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun writeSamplePng(name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.parseColor("#3A6BD8"))
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Test
    fun validImageUriDecodesToBitmap() {
        val file = writeSamplePng("wallpaper_decode_ok.png", 900, 1600)
        val decoded = decodeSampledWallpaper(context, file.toUri().toString())
        assertNotNull("可读取的图片 URI 必须解出位图，否则自定义壁纸永远不生效", decoded)
        assertEquals(900, decoded!!.width)
        assertEquals(1600, decoded.height)
        decoded.recycle()
        file.delete()
    }

    @Test
    fun oversizedImageIsSampledDownNotRejected() {
        // 目标分辨率 1080x1920：2160x3840 应被采样成 1080x1920 而不是整图进内存
        val file = writeSamplePng("wallpaper_decode_large.png", 2160, 3840)
        val decoded = decodeSampledWallpaper(context, file.toUri().toString())
        assertNotNull(decoded)
        assertEquals(1080, decoded!!.width)
        assertEquals(1920, decoded.height)
        decoded.recycle()
        file.delete()
    }

    @Test
    fun missingFileReturnsNullWithoutThrowing() {
        val ghost = File(context.cacheDir, "wallpaper_decode_missing.png").toUri().toString()
        assertNull(decodeSampledWallpaper(context, ghost))
    }

    @Test
    fun malformedUriReturnsNullWithoutThrowing() {
        assertNull(decodeSampledWallpaper(context, "not-a-uri-at-all"))
    }
}
