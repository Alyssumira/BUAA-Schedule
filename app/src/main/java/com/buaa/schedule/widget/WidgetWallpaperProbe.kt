package com.buaa.schedule.widget

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get

/**
 * 「这台设备这一次实测读不读得到系统桌面壁纸」的唯一实测点 + 进程内 memo。
 *
 * 它替代的是那道一刀切的 SDK 闸门（原 `MIN_SDK_SYSTEM_WALLPAPER_UNREADABLE = 34`，
 * T46 起删除）。那道闸门断言"Android 14 起第三方应用读不到桌面壁纸"，而装机实测
 * 把它证伪了：API 36 的镜像上 `getDrawable()` 仍返回真实桌面壁纸 —— 关掉闸门后
 * 组件 tile 立刻跟着桌面的亮暗两区走（亮区 (43,57,88)、暗区 (21,29,51)，都是
 * tint 复合在局部桌面像素上的算法预期值）。闸门白关了一半设备，还把配置页那句
 * 说明钉成一句谎话。现在 34+ 也去问一次，用「这次问到了什么」决定有没有系统源。
 *
 * 判据本体不在这里 —— [WidgetGlassSource.wallpaperLooksUsable] 是纯 JVM 的，
 * 这里只负责把设备事实（位图宽高、9 枚采样像素）翻译成它的入参，与
 * `WidgetCornerRadii` 那套"设备度量在调用点交参数"的分工同一份。
 *
 * memo 的失效边界（三条红线：配置页不许在主线程解全屏壁纸；「读不到」不许永久记死；
 * 探针与 memo 的线程安全）：
 * - 每次 [measure] 都整格覆写结论。组件每轮刷新都实测一次（桌面换壁纸没有任何
 *   广播能进到我们进程，靠的就是下一轮刷新重问），所以换壁纸、换深浅色后的
 *   下一次组件刷新就会翻面 —— 这一条同时是「读不到」那一格的失效边界：
 *   [memoized] 说 false 时渲染侧**照样**问一次，不许拿它当短路；
 * - 结论带 [SystemClock.elapsedRealtime] 时间戳，超过 [MEMO_TTL_MS] 按「没判过」
 *   交回 null，由要答案的一方决定去后台重测 —— 一轮只开配置页、不刷组件的进程里，
 *   陈旧账的上限就是这一条 TTL；
 * - 交回 null 是"不知道"，不是"读不到"：配置页那一格要按三态说话
 *   （[WidgetGlassSource.decide] 的 `NotMeasuredYet`），把 null 折成任一布尔值
 *   都会造出一句没有出处的说明；
 * - 「使用桌面壁纸」那枚开关不进阶验输入：翻面由 [WidgetGlassSource.decide]
 *   当场吃开关完成，memo 只管设备事实这一格。
 *
 * 线程：[measure] 里有一次 binder 调用 + 9 枚 getPixel（非位图 drawable 还要画一张
 * 整屏的渲染目标），不许上主线程 —— 调用点全在 IO 协程上：组件刷新走
 * `WidgetCommon.launchRefresh` 的 `Dispatchers.IO`，配置页走 LaunchedEffect 里的
 * `withContext(Dispatchers.IO)`。[memoized] 是纯读，主线程安全。两个写点走同一次
 * @Volatile 单格覆写，不需要更多同步：谁后写谁的结论生效，而两份结论出自同一个
 * [measure] + 同一个判据，最坏是下一拍就对齐的一格不一致 —— 换来的是
 * 「配置页与渲染侧同一个答案」（T31 的规矩）在这里只有**一个**出处。
 */
internal object WidgetWallpaperProbe {

    /**
     * 一次实测的完整收获：那张位图（连同它归不归我们回收）与它交出的身份。
     * 只有判据点头的实测才会把它交出去 —— 判「没有源」时内部已把自己回收干净。
     */
    class Captured(
        val bitmap: Bitmap,
        val owned: Boolean,
        val identity: GlassSourceIdentity,
    ) {
        /** 我们新建的那张才回收；系统持有的一张都不许碰。 */
        fun recycleIfOwned() {
            if (owned && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * memo 的采信期限。取 60s 的理由：实测的全部成本就是一次 binder 问图 + 9 枚
     * 取点，重测便宜到没必要为省它付更长的陈旧账；而 60s 又长过配置页一屏说明
     * 停留的正常时间 —— 打开配置页不触发重测，重开一次才可能触发。
     */
    private const val MEMO_TTL_MS = 60_000L

    /**
     * memo 的两个字段，**写序与读序本身是判据的一部分**（红线：探针与 memo 的线程安全）。
     *
     * 写侧：先写结论再写时间戳。读侧：先看时间戳再看结论。于是能看见新时间戳的那一刻
     * 必然也看得见新结论（volatile 写的 release 语义会把先前的写一起带出去），
     * 看不见的就退回旧时间戳那一格 —— 过不过期由它自己算，最坏读出"过期"交 null，
     * 也就是「还没测」那一格。两个方向都不会读出一对**配错**的（新戳 + 旧结论）。
     *
     * 为什么不用锁、也不用把两枚装进一个不可变对象再整体换：写点只有 [measure] 一处，
     * 而它一次写的就是"这一台设备此刻能不能读到壁纸"这一个问题的事实答案；
     * 六家 Provider 各自在 `goAsync()` 续命的 IO 协程上跑同一个 [measure]、
     * 走同一个判据，最坏是后写的那一份把先写的顶掉，而那两份之间没有任何
     * "必须原子地一起成立"的承诺（不像尺寸+档位那种要配对着进缓存键的数）。
     * 位图像素**从不**从 memo 里拿（缓存的是结论，不是图），所以并发最坏的后果
     * 停在配置页那一句说明上，不会画出错图。
     */
    @Volatile
    private var memoUsable: Boolean? = null

    @Volatile
    private var memoAtElapsed = Long.MIN_VALUE

    /** 采信期内的上次实测结论；没判过或已过期的都交 null（=「还没测」，不是「读不到」）。 */
    fun memoized(): Boolean? {
        if (memoAtElapsed == Long.MIN_VALUE) return null
        if (SystemClock.elapsedRealtime() - memoAtElapsed > MEMO_TTL_MS) return null
        return memoUsable
    }

    /**
     * 问一次 `getDrawable()`，把拿到的东西翻译成事实交给判据。
     * 判成可用时回那张位图（调用方用完 [Captured.recycleIfOwned] 收尾）；
     * 判成没有源时回 null —— 无论哪一头，结论都已覆写进 memo。
     *
     * 只能在 IO 线程调（类的 KDoc 那条线程账）；异常整条吞成「拿不到位图」，
     * 与改前闸门的行为口径一致，区别只是这个答案现在是问出来的而不是猜的。
     * 交判据的三格事实（拿不到位图 / 尺寸退化 / 采样全同的纯色占位）都在 [capture]
     * 里取得（宽高 + 9 枚采样），为什么纯色占位不算壁纸，论证写在
     * [WidgetGlassSource.wallpaperLooksUsable] 那一条。
     */
    fun measure(context: Context): Captured? {
        val captured = runCatching { capture(context) }.getOrNull()
        val usable = captured != null && WidgetGlassSource.wallpaperLooksUsable(
            widthPx = captured.identity.widthPx,
            heightPx = captured.identity.heightPx,
            samples = captured.identity.samples,
        )
        memoUsable = usable
        memoAtElapsed = SystemClock.elapsedRealtime()
        if (!usable) captured?.recycleIfOwned()
        return captured.takeIf { usable }
    }

    /**
     * 取图 + 算身份。`getDrawable()` 十有八九给的是系统持有的 BitmapDrawable，
     * 那条路零拷贝；不是位图的 drawable（矢量/动态壁纸的壳）才画一张渲染目标，
     * 那种位图归我们回收。尺寸从位图本身取 —— 拿到位图却没尺寸（宽高 ≤0）
     * 交给判据的是退化那一格。
     */
    @SuppressLint("MissingPermission") // 要不要权限正是这次实测的东西，不是可以预支的假设
    private fun capture(context: Context): Captured? {
        val drawable = WallpaperManager.getInstance(context).drawable ?: return null
        val systemBitmap = (drawable as? BitmapDrawable)
            ?.bitmap?.takeIf { !it.isRecycled }
        if (systemBitmap != null) {
            return Captured(systemBitmap, owned = false, identity = identityOf(systemBitmap))
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: WidgetBackgroundRenderer.TARGET_WIDTH
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: WidgetBackgroundRenderer.TARGET_HEIGHT
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return Captured(bitmap, owned = true, identity = identityOf(bitmap))
    }

    /**
     * 内容签名：9 个点，不复制位图（取样点为什么是 3x3 落在 1/8..7/8 那一带，
     * 论证在 [wallpaperSamplePoints]）。它喂两张嘴：判据拿它认「这是不是一张真壁纸」
     * （三格拒绝理由在 [WidgetGlassSource.wallpaperLooksUsable]），
     * 缓存拿它做「桌面换了壁纸」的身份。取不到（硬件位图 getPixel 抛）就交空表 ——
     * 判据那头空表 = 没有源，缓存那头空表 = 拒绝缓存，两处都是安全侧。
     */
    private fun identityOf(bitmap: Bitmap): GlassSourceIdentity {
        val samples = runCatching {
            wallpaperSamplePoints(bitmap.width, bitmap.height).map { (x, y) -> bitmap[x, y] }
        }.getOrDefault(emptyList())
        return GlassSourceIdentity(
            widthPx = bitmap.width,
            heightPx = bitmap.height,
            samples = samples,
        )
    }
}
