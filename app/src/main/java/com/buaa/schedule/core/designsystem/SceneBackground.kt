package com.buaa.schedule.core.designsystem

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdropCoordinates
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 共享场景背景层：液态玻璃表面通过它采样真实背景（壁纸 / 渐变）做折射与模糊。
 * 记录与消费必须在同一帧内先画背景后画玻璃（正常 z 序即满足）。
 */
val LocalSceneBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun rememberSceneBackdrop(): LayerBackdrop {
    val graphicsLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    return com.kyant.backdrop.backdrops.rememberManualLayerBackdrop(graphicsLayer)
}

/**
 * 全局场景背景：北航蓝渐变或用户壁纸 + 明度遮罩。
 * 由顶层承载，所有一级页面共享；同时把绘制内容录制进背景层，
 * 供 API 31+ 的液态玻璃表面做折射采样。
 *
 * 注意：明度遮罩（scrim）绘制在录制层之外——玻璃必须采样“原始壁纸”，
 * 否则降亮度会连带压暗所有玻璃表面（SleepDown 踩过的坑）。
 */
@Composable
fun SceneBackground(
    darkTheme: Boolean,
    backdrop: LayerBackdrop,
) {
    val context = LocalContext.current
    val wallpaperUri = Personalization.wallpaperUri
    val useSystemWallpaper = Personalization.useSystemWallpaper
    var wallpaper by remember { mutableStateOf<Bitmap?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            // 回到前台时若壁纸还没取到，重试一次（部分设备首次启动取壁纸可能失败/时序问题）。
            // ⚠️ 必须设重试上限：API 34+ 上 decodeSystemWallpaper **恒定返回 null**
            // （平台不再允许第三方读系统壁纸），没有上限的话每次 ON_RESUME 都会
            // retryToken++ → LaunchedEffect 重启 → 再解码一次 → 还是 null，
            // 进入"每回前台就重解一次 1080×1920 位图"的无限循环，纯粹烧内存和电。
            if (event == Lifecycle.Event.ON_RESUME && wallpaper == null &&
                retryToken < MAX_WALLPAPER_RETRY &&
                (wallpaperUri != null || useSystemWallpaper)
            ) {
                retryToken++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(wallpaperUri, useSystemWallpaper, retryToken) {
        wallpaper = when {
            // 用户拾取的壁纸优先
            wallpaperUri != null ->
                withContext(Dispatchers.IO) { decodeSampledWallpaper(context, wallpaperUri) }
            // 默认：提取系统桌面壁纸
            useSystemWallpaper ->
                withContext(Dispatchers.IO) { decodeSystemWallpaper(context) }
            else -> null
        }
    }

    // 旧壁纸位图必须显式回收：换壁纸 / 改 useSystemWallpaper / 重试都会重新解码一张
    // 1080×1920（约 8MB，且在 native 堆上，GC 回收滞后）。此前每次换图都漏一张，
    // 反复切换几次就把进程推到 OOM。DisposableEffect 在 wallpaper 变化/离开组合时
    // 回收上一张，这是 Compose 官方为原生资源推荐的清理时机。
    DisposableEffect(wallpaper) {
        val current = wallpaper
        onDispose {
            if (current != null && !current.isRecycled) current.recycle()
        }
    }

    // 壁纸调参（在组合期读取，改动即重算缓存；模糊走 Modifier.blur）
    val blurDp = Personalization.wallpaperBlurDp
    val brightness = Personalization.wallpaperBrightness
    val zoom = Personalization.wallpaperZoom

    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 只模糊"看得见的背景"。录制层在其内部，玻璃采样仍是原始壁纸，
            // 因此调模糊不会把玻璃的折射一起糊掉。
            .then(
                if (blurDp > 0f) {
                    Modifier.blur(blurDp.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                } else {
                    Modifier
                }
            )
            .layerBackdropCoordinates(backdrop)
            .drawWithCache {
                val bitmap = wallpaper
                // asImageBitmap() 每帧新建一次包装对象（底层还要做一次 JNI 绑定），
                // 提到缓存块里：只要壁纸不变就复用同一个 ImageBitmap。
                val image = bitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
                // scrim 不进录制层：只压暗可见背景，不压暗玻璃采样
                val scrim = sceneScrim(darkTheme = darkTheme, brightness = brightness)
                // 无壁纸时仅在用户主动压暗的情况下叠 scrim（默认渐变自带明暗层次，不要二次压暗）
                val needsScrim = image != null || brightness < 1f
                val layer = backdrop.graphicsLayer
                // 录制与实绘共用同一段绘制逻辑，保证玻璃采样与所见一致。
                // 以根坐标（0,0）录制：玻璃表面消费时按自身位置平移采样。
                //
                // 必须录在缓存块里而不是 onDrawBehind 里：背景每次被失效（页面动画、
                // 玻璃层重绘）都会重跑 onDrawBehind，那一趟等于重建整张壁纸的
                // DisplayList —— 而这张层正是玻璃要采样、外面要模糊的那一层（R5 F-50）。
                // 缓存块只在尺寸变化、或下面读到的壁纸/缩放/明度变化时重跑。
                layer.record(
                    density = density,
                    size = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                ) {
                    if (image != null) {
                        drawSceneBitmap(image, size.width, size.height, zoom)
                    } else {
                        drawSceneGradient(darkTheme)
                    }
                }
                onDrawBehind {
                    drawLayer(layer)
                    if (needsScrim) {
                        drawRect(scrim)
                    }
                }
            },
    )
}

/**
 * 背景明度遮罩：主题决定基础强度，用户亮度偏好在此基础上继续压暗
 * （brightness=1 时与改造前完全一致，越接近 MIN_BRIGHTNESS 越黑）。
 */
private fun sceneScrim(darkTheme: Boolean, brightness: Float): Brush {
    val baseTop = if (darkTheme) 0x66 / 255f else 0x33 / 255f
    val baseBottom = if (darkTheme) 0xB3 / 255f else 0x66 / 255f
    val extra = ((1f - brightness) / (1f - Personalization.MIN_BRIGHTNESS))
        .coerceIn(0f, 1f)
    fun dim(base: Float) = (base + (1f - base) * extra).coerceIn(0f, 1f)
    return Brush.verticalGradient(
        listOf(
            Color.Black.copy(alpha = dim(baseTop)),
            Color.Black.copy(alpha = dim(baseBottom)),
        )
    )
}

private fun DrawScope.drawSceneBitmap(
    image: androidx.compose.ui.graphics.ImageBitmap,
    width: Float,
    height: Float,
    zoom: Float,
) {
    // 等价 ContentScale.Crop：按较短边铺满并居中，再乘以用户缩放（取景拉近）
    val scale = maxOf(width / image.width, height / image.height) *
        zoom.coerceIn(Personalization.MIN_ZOOM, Personalization.MAX_ZOOM)
    val dstW = image.width * scale
    val dstH = image.height * scale
    drawImage(
        image = image,
        dstOffset = IntOffset(((width - dstW) / 2).roundToInt(), ((height - dstH) / 2).roundToInt()),
        dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
    )
}

/**
 * 默认场景：北航蓝基调渐变 + 两团柔和光斑。
 * 光斑让液态玻璃的折射 / blur 在无壁纸时也有层次可感知。
 */
private fun DrawScope.drawSceneGradient(dark: Boolean) {
    val base = if (dark) {
        listOf(Color(0xFF0D1526), Color(0xFF101A30), Color(0xFF131229))
    } else {
        listOf(Color(0xFFEAF1FD), Color(0xFFDCE9FB), Color(0xFFE6E3F6))
    }
    drawRect(Brush.verticalGradient(base))
    val spotA: Color
    val spotB: Color
    val aAlpha: Float
    val bAlpha: Float
    if (dark) {
        spotA = Color(0xFF2E6BD6); spotB = Color(0xFF7A4FD0); aAlpha = 0.30f; bAlpha = 0.24f
    } else {
        spotA = Color(0xFF9EC3F8); spotB = Color(0xFFC9B8F0); aAlpha = 0.50f; bAlpha = 0.40f
    }
    drawCircle(
        brush = Brush.radialGradient(listOf(spotA.copy(alpha = aAlpha), Color.Transparent)),
        radius = size.width * 0.75f,
        center = Offset(size.width * 0.20f, size.height * 0.16f),
    )
    drawCircle(
        brush = Brush.radialGradient(listOf(spotB.copy(alpha = bAlpha), Color.Transparent)),
        radius = size.width * 0.85f,
        center = Offset(size.width * 0.90f, size.height * 0.55f),
    )
}

private const val WALLPAPER_TARGET_WIDTH = 1080
private const val WALLPAPER_TARGET_HEIGHT = 1920

/**
 * 取壁纸失败后的最大重试次数（每次 ON_RESUME 算一次）。
 * API 34+ 读系统壁纸被平台禁用，重试注定失败，必须收敛。
 */
private const val MAX_WALLPAPER_RETRY = 3

private fun decodeSampledWallpaper(context: android.content.Context, uriString: String): Bitmap? {
    return runCatching {
        val uri = uriString.toUri()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= WALLPAPER_TARGET_WIDTH &&
            bounds.outHeight / (sampleSize * 2) >= WALLPAPER_TARGET_HEIGHT
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        // 解码失败不能静默：用户「选了图但背景没变化」时，这里是唯一可定位的证据
    }.onFailure {
        android.util.Log.w("SceneBackground", "自定义壁纸解码失败（URI 失效/已损坏？）: $uriString", it)
    }.getOrNull()
}

/**
 * 提取系统桌面壁纸：按固定目标分辨率渲染成位图（等效 cover 裁切，居中取景）。
 *
 * 这样做而不是直接拿全尺寸位图，是为了避免 10MP+ 壁纸一次性解码的内存峰值；
 * 渲染目标分辨率与 [decodeSampledWallpaper] 的采样目标一致。
 *
 * ⚠️ **Android 14（API 34）起 `WallpaperManager.getDrawable()` 需要
 * `MANAGE_EXTERNAL_STORAGE` 或签名级的 `READ_WALLPAPER_INTERNAL`**，普通应用
 * 不再能读系统壁纸（隐私收紧）。因此 API 34+ 直接返回 null，由调用方回退到
 * 渐变或用户自选图片，而不是每次都白抛一次 SecurityException。
 *
 * 动态壁纸 / 无壁纸 / 权限异常时同样返回 null，调用方回退北航蓝渐变。
 */
@SuppressLint("MissingPermission") // API 34+ 已在上方提前返回；API 34 以下读取壁纸不需要该权限
private fun decodeSystemWallpaper(context: android.content.Context): Bitmap? {
    if (Build.VERSION.SDK_INT >= 34) return null
    return runCatching {
        val drawable = android.app.WallpaperManager.getInstance(context).drawable
            ?: return@runCatching null
        val width = WALLPAPER_TARGET_WIDTH
        val height = WALLPAPER_TARGET_HEIGHT
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        // cover：等比放大铺满后居中裁切，防止拉伸变形
        val intrinsicW = drawable.intrinsicWidth.takeIf { it > 0 } ?: width
        val intrinsicH = drawable.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = maxOf(width.toFloat() / intrinsicW, height.toFloat() / intrinsicH)
        val drawW = (intrinsicW * scale).roundToInt()
        val drawH = (intrinsicH * scale).roundToInt()
        drawable.setBounds((width - drawW) / 2, (height - drawH) / 2, (width + drawW) / 2, (height + drawH) / 2)
        drawable.draw(canvas)
        bitmap
    }.getOrNull()
}
