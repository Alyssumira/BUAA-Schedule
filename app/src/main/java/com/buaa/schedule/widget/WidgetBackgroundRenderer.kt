package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import com.buaa.schedule.core.designsystem.Personalization

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
 * 圆角是**按实例**烘的：画布会被背景层 `scaleType="fitXY"` 非等比拉到组件真实尺寸上，
 * 所以半径得按两轴各自的倍数反推（[WidgetCornerRadii]），否则用户选的 20dp
 * 到桌面上会变成 53dp，并且是个椭圆。
 *
 * **同参数的实例共用同一张烘焙结果**（[glassCache]，LRU ≤ [GLASS_CACHE_MAX_ENTRIES]）：
 * 改前每个实例每次刷新都新造一张位图，桌面上 N 个组件在同一轮刷新里把同一张壁纸
 * 糊 N 遍 —— 与真机 `dumpsys appwidget` 报的 `views_bitmap_memory=614400`
 * （正好是 480x320 ARGB_8888）逐字吻合的那条路。键的构造见 [glassBakeKey]，
 * 出图尺寸怎么从实例尺寸推出来见 [bakeSizePx]。
 */
object WidgetBackgroundRenderer {

    private const val TAG = "WidgetBackgroundRenderer"

    /**
     * 烘焙画布的**上限**（px）。
     *
     * 改前这里是"固定尺寸"，现在是"最大尺寸"：一轴上按这个实例真实尺寸出图
     * （见 [bakeSizePx]），只有真实尺寸比它还大时才仍然停在 480 / 320。
     *
     * 上限这个数本身不许往上抬：这张位图要经 RemoteViews 走 binder 事务，
     * ARGB_8888 下约 0.6 MB，放大到 900x550 就是 1.9 MB，有 TransactionTooLargeException 的风险。
     * 往小走没有这条风险，而且走的仍然是同一条管线。
     *
     * internal 是给圆角烘焙（[WidgetCornerRadii]）和它的单测用的：换算必须按**真实**画布算。
     */
    internal const val TARGET_WIDTH = 480
    internal const val TARGET_HEIGHT = 320

    /** 缓存里那位（见 [glassCache]）。见 [GLASS_CACHE_MAX_ENTRIES] 的论证。 */
    private val glassCache = GlassBakeCache<Bitmap>()

    /** 应用内背景的 prefs 与组件唯一读的那枚全局壁纸键（与 Personalization.load 同一份） */
    private const val SETTINGS_PREFS = "schedule_settings"
    private const val KEY_USE_SYSTEM_WALLPAPER = "wallpaper_use_system"

    /**
     * 生成一张玻璃背景。
     *
     * 圆角要按**这一个实例**的真实尺寸来烘焙，所以得把 appWidgetId 和宿主句柄传进来：
     * 背景层是 fitXY，画布会被拉到组件尺寸上，半径不除回去就不是用户选的那一档
     * （详见 [WidgetCornerRadii]）。
     *
     * 同一枚 [GlassBakeKey] 第二次进来时直接回缓存里那张位图：不重采样、不画图。
     * 命中路径上仍然要跑的是那三步尺寸探测（理由见 [widgetSizePx]）与一次图源实测
     * （[WidgetWallpaperProbe.measure]）——后者在改前的"闸门放行"那一档设备上也每轮
     * 要问一次，省掉的是烘焙管线本身。T46 起图源只剩一种（实测可用的系统桌面壁纸），
     * 它的身份从同一次实测里顺手算出，不再有"命中时省一趟解码"那条路 ——
     * 那条路省的是自选那张的解码，而它从本卡起不再是组件的图源。
     */
    fun render(
        context: Context,
        appearance: WidgetAppearance,
        appWidgetId: Int,
        appWidgetManager: AppWidgetManager,
    ): Bitmap? {
        if (!appearance.blurBackground) return null
        return runCatching {
            val measured = wallpaperForRender(context) ?: return@runCatching null
            // 位图档：实测到一张能用的桌面壁纸才走下面那条管线（T37/T42 的账一个字不动）。
            // 判到位图不可用时本枚仍回 null（= 改前的兜底），主色档在下一枚接上。
            val wallpaper = measured.captured ?: return@runCatching null
            try {
                // 尺寸探测自己吞每一层异常（[widgetSizePx]）：取不到尺寸不等于取不到壁纸，
                // 前者有明写的兜底口径（[WidgetCornerRadii.bake]），后者才是"这张背景画不出来"。
                // 现在它得排在取图源之后一步 —— 图源判到没有时连这两次 IPC 都不必付。
                val widgetSize = widgetSizePx(context, appWidgetManager, appWidgetId)
                val size = bakeSizePx(widgetSize, TARGET_WIDTH, TARGET_HEIGHT)
                val radii = WidgetCornerRadii.bake(
                    cornerDp = appearance.cornerRadiusDp(),
                    density = context.resources.displayMetrics.density,
                    canvasWidthPx = size.widthPx,
                    canvasHeightPx = size.heightPx,
                    size = widgetSize,
                )
                // 底色只在这里解析一次：它既进缓存键、又进烘焙管线。动态取色那一路读的是
                // 主题，两处各解析一次就可能拿到两个值，于是"键"和"像素"对不上、
                // 换桌面深浅色时缓存会把旧颜色的图发给新颜色该在的那一格。
                val glass = glassTintArgb(
                    tintArgb = appearance.resolvedBackground(context),
                    alphaPercent = appearance.alphaPercent,
                )
                val key = glassBakeKey(
                    source = wallpaper.identity,
                    size = size,
                    radii = radii,
                    glassArgb = glass,
                    nightMode = isNightMode(context),
                )
                // 键给不出来（壁纸内容签名取不到）就照改前的样子现烤一张，不入库
                if (key == null) {
                    bakeGlass(wallpaper, size, radii, glass)
                } else {
                    glassCache.obtain(key) { bakeGlass(wallpaper, size, radii, glass) }
                }
            } finally {
                // 图源位图归这一次调用管：只有我们新建的那张（探针画的渲染目标）才回收，
                // 一次 1080x1920 = 约 8MB，每次刷新都漏一份，几次之后就会把进程推到
                // OOM 边缘。系统持有的一张都不许碰。缓存命中的路径上它也握了一次，
                // 照样随这一次调用收尾 —— 实测问图本来就每轮付一次，省的不是它。
                wallpaper.recycleIfOwned()
            }
        }.onFailure {
            // 这里以前什么都不留：玻璃背景画不出来会静默回退纯色底，一整轮定位全靠
            // 装机量像素。语义不变（返回 null = 回退纯色底），只补证据。
            Log.w(TAG, "玻璃背景这次画不出来，回退纯色底（appWidgetId=$appWidgetId）", it)
        }.getOrNull()
    }

    /**
     * 把一张壁纸糊成最终要交给 RemoteViews 的那张位图：廉价模糊（先缩到 1/4 再放回）
     * + 圆角裁剪 + 用户底色与透明度。
     *
     * 从 [render] 里拆出来只为让"这一步很贵、值得缓存"这件事在调用点看得出来：
     * 它是缓存未命中时才跑的那一段，[render] 负责算键、管图源的生命周期。
     * 管线本身一个字没改（改前它在 [render] 里连着解码一起写）。
     *
     * 位图交给 RemoteViews 后不能回收，所以缓存里那位、以及这里 return 出去的这张，
     * 都不在源图的回收范围内 —— 这里回收的只有两张中间图。
     */
    private fun bakeGlass(
        wallpaper: WidgetWallpaperProbe.Captured,
        size: BakeSize,
        radii: BakedCornerRadius,
        glass: Int,
    ): Bitmap? {
        val base = wallpaper.bitmap
        if (base.isRecycled) return null
        val width = size.widthPx
        val height = size.heightPx
        // 先缩小再放大，得到廉价的模糊效果；后续如需更强可换成 RenderEffect/高斯模糊。
        // 缩小的是**这张画布**的 1/4，不是固定的 120x80：糊的强度按画面内容算始终
        // 是 25%，出图尺寸跟着组件走时观感不动，只是采样更密。
        val small = base.scale(width / 4, height / 4, true)
        return try {
            val blurred = small.scale(width, height, true)
            try {
                val output = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
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
                    // 进最终颜色。期望那一格按纯白底面算（当时图源恰被一张纯白测试图顶替 ——
                    // 它就是 T46 追的那块 (69,77,97) 恒值板的原料；算式本身不挑图源）：
                    // alpha=80% -> 0.8*(22,32,58) + 0.2*(255,255,255) ≈ (69,77,97)；
                    // alpha=0%  -> 看到糊过的壁纸本身。逐格钉在 WidgetBackgroundRendererTest。
                    paint.colorFilter = null
                    paint.color = glass
                    drawRect(rect, paint)
                }
                output
            } finally {
                blurred.recycle()
            }
        } finally {
            small.recycle()
        }
    }

    /**
     * 这台设备当前是深色桌面吗。
     *
     * 只有 `UI_MODE_NIGHT_MASK` 这一格进缓存键（[glassBakeKey] 的 nightMode）：
     * 「跟随系统取色」那条分支的底色是经 `Theme_DeviceDefault_DayNight` 解析出来的
     * （[resolveSystemWidgetColor]），深浅色翻面时它翻面。这里判的是 uiMode，
     * 不是"底色这次解析出了什么"，因为底色是 [render] 里现算的一个数、本来就是键的分量 ——
     * 这一枚是给"底色没变、但桌面翻了面"那种以后可能出现的口径留的余量。
     */
    private fun isNightMode(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

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
     *
     * 同一份 options 还得配一个**屏幕上限**：装机反推（同那一格）launcher 报的
     * `MAX_WIDTH` ≥ 509dp，而整块屏只有 411.4dp 宽，组件不可能比屏幕还大 ——
     * 那是"能拖到多大"而不是"现在多大"。为什么要这道闸、以及它为什么不会把方向
     * 从"偏方"推成"偏圆"，判据全在 [WidgetCornerRadii.resolveSizePx] 那边；
     * 这里只负责交出一个**物理屏幕**的 dp（拿不到就交 0，那一轴等于不夹）。
     *
     * **这三步探测一份都不缓存**（本卡刻意少做的那一条）：`getAppWidgetOptions` +
     * `getAppWidgetInfo` + WindowManager 仍然每实例每次刷新各问一遍。抄的是
     * `BackgroundSync.refreshWidgets` 对"结论"立的同一份口径 —— 结论当参数传下去、
     * 而不是缓存起来，没有缓存就没有陈旧问题；组件刚被拖上/拆掉那两条路
     * （`onEnabled` / `onDisabled`）当场问到自己那份数，靠的正是这里也没留一份旧的尺寸。
     * 要缓存尺寸就得给出一条"这个实例被拉大/缩小了"的失效边界，而那个信号只有宿主的
     * `onAppWidgetOptionsChanged` 给得起（本卡不许动那几个文件）。
     * 少做这一条的代价不大：命中路径上真正贵的三样（壁纸解码、两张中间位图的重采样、
     * 那张 614,400 B 的画布）都已经收在 [glassCache] 那一头了。
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
        val (displayWidthDp, displayHeightDp) = displaySizeDp(context)
        return WidgetCornerRadii.resolveSizePx(
            optionsMaxWidthDp = optionsMaxWidthDp,
            optionsMaxHeightDp = optionsMaxHeightDp,
            optionsMinWidthDp = optionsMinWidthDp,
            optionsMinHeightDp = optionsMinHeightDp,
            infoWidthDp = infoWidthDp,
            infoHeightDp = infoHeightDp,
            density = context.resources.displayMetrics.density,
            displayWidthDp = displayWidthDp,
            displayHeightDp = displayHeightDp,
        )
    }

    /**
     * 这块屏幕的物理尺寸（dp），给 options 上报的尺寸当上限。
     *
     * 刻意取**整个物理屏幕**，不是本 app 的窗口：分屏/小窗下
     * `resources.displayMetrics.widthPixels` 与 `currentWindowMetrics` 都会缩成窗口尺寸，
     * 那时上限可能掉到组件真实尺寸以下 —— 那就会低估拉伸倍数、把圆角烘得偏**圆**，
     * 正是唯一不许出现的那一侧（判据见 [WidgetCornerRadii.resolveSizePx]）。
     * API 30+ 用 `maximumWindowMetrics`：它给的是"这块屏在最大窗口态下的边界"，
     * 与当前是不是分屏无关；30 以下退回 `defaultDisplay.getRealMetrics`，那是那条版本线上
     * 唯一读得到整块屏（含系统栏那圈）的口径。px -> dp 用 `resources.displayMetrics.density`，
     * 与 options 报 dp 用的、以及 `bake` 里 `cornerDp -> px` 用的都是同一个 density。
     *
     * 任何一步拿不到（服务为 null、值 ≤0、抛异常）就交 0f：那一轴不做这道夹取。
     */
    private fun displaySizeDp(context: Context): Pair<Float, Float> {
        val density = context.resources.displayMetrics.density
        if (density <= 0f) return 0f to 0f
        val sizePx = runCatching {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return@runCatching 0 to 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = manager.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                @Suppress("DEPRECATION")
                DisplayMetrics().also { manager.defaultDisplay.getRealMetrics(it) }
                    .let { it.widthPixels to it.heightPixels }
            }
        }.getOrDefault(0 to 0)
        val (widthPx, heightPx) = sizePx
        if (widthPx <= 0 || heightPx <= 0) return 0f to 0f
        return widthPx / density to heightPx / density
    }

    /**
     * 这一次「玻璃感壁纸背景」到底有没有图源。
     *
     * 判据本体在 [WidgetGlassSource]（不 import 任何 android 类型，所以那张表能在 JVM 单测里
     * 逐格钉住）；这里只把两格事实翻译成它的入参 —— 「使用桌面壁纸」那枚开关（prefs）与
     * 探针上一次的实测结论（[WidgetWallpaperProbe.memoized]）。改前的第三枚入参是 API 档次，
     * 已被装机实测证伪，换成实测（论证在探针那文件的 KDoc）。
     *
     * memo 交 null（从没测过、或过了 60s 采信期）时**原样交 null**，不许在这里折成
     * "读得到"或"读不到"里的任何一头：这一格的答案唯一的读者是配置页那句说明，
     * 而说明有三态可说（"正在确认这台设备读不读得到，稍后再看这一句"）。
     * 折成"有"是给了一句没有出处的承诺，折成"没有"是拿一个没问过的问题当已否认的回答。
     * 渲染侧不靠这一格画玻璃 —— [wallpaperForRender] 每轮自己实测，
     * 所以"没测过"从来不会让桌面上少一张图，只会让这一句说明暂时不说死。
     *
     * 配置页那句说明读的也是这个函数 —— 两边各判一次就会出现「开关能拨、拨完没反应」
     * （真机实测：无源时整块 tile 的像素差 0 / 258258）。
     */
    internal fun availability(context: Context): GlassSource = WidgetGlassSource.decide(
        systemWallpaperUsable = WidgetWallpaperProbe.memoized(),
        useSystemWallpaper = usesSystemWallpaper(context),
    )

    /** 组件读的唯一那枚壁纸键。「有没有图源」的口径收在 [availability]，这里只交开关本身。 */
    private fun usesSystemWallpaper(context: Context): Boolean =
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_SYSTEM_WALLPAPER, Personalization.DEFAULT_USE_SYSTEM_WALLPAPER)

    /**
     * 这一次要糊的壁纸：实测到手才回那一份收获（位图连同它的身份、以及位图判空时
     * 问到的主色），否则 null，调用方（`WidgetCommon.applyAppearance`）落到纯色半透明
     * 那条分支 —— 装机实测里那一形反而是桌面真的透得过来。
     *
     * 只有「用户关掉了开关」那一格许在实测之前拦 —— [WidgetGlassSource.decide] 里它
     * 判的是用户自己的选择，是任何实测都翻不动的一格（这也省掉那次 binder 问图）。
     * 其余情形**每轮都跑一次 [WidgetWallpaperProbe.measure]**，memo 说「上次读不到」
     * 也照问 —— 组件刷新就是「读不到」那一格的失效边界（桌面换壁纸没有任何广播能进
     * 到我们进程），信了 memo 就会把它锁死到配置页翻页为止，正撞红线。
     */
    private fun wallpaperForRender(context: Context): WidgetWallpaperProbe.Measurement? = when {
        !usesSystemWallpaper(context) -> null
        else -> WidgetWallpaperProbe.measure(context)
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

/*
 * 下面这一整段是「玻璃背景烘焙结果缓存」的判据本体：键怎么构造、出图尺寸怎么从实例尺寸
 * 推出来、缓存容器本身。**一个 android 类型都不 import**，设备度量（真实尺寸、density、
 * uiMode、壁纸像素）全部由 [WidgetBackgroundRenderer.render] 当参数交进来 ——
 * 与 [glassTintArgb]、[WidgetGlassSource]、[WidgetCornerRadii] 同一套理由：
 * 本模块没有 Robolectric，碰 `Bitmap` / `Build.VERSION` 的写法在 JVM 里全是抛
 * "not mocked" 的桩，只有这条纯函数路能在单测里逐格钉住，而它恰恰决定
 * 「第二次进来到底复不复用」和「这一张画布到底多大」这两件会改变画面的事。
 */

/**
 * 缓存容量上限：两条。
 *
 * 为什么两条够，而不是"每实例一条"或者一个不限增长的 map：
 * - 同一屏上的多个组件实例**绝大多数是同壁纸、同外观参数**的（用户拖三个组件出来，
 *   配色往往一套），它们只差尺寸档 —— 于是真正会同时活着的键只有一个；
 * - 第二条留给"同一轮里两档尺寸"那一形：2×1「下一节课」与 4×2「今日课程」并排，
 *   两档尺寸两张图，这一格必须不互相逐出，否则每轮还是烤两遍；
 * - 剩下的形（用户给两个实例配了不同底色）本来就该各烤一张：位图字节不一样，
 *   缓存救不了，多留格子只是把 1.2 MB 变成 1.8 MB 挂在这个既要跑 Compose 首页、
 *   又要跑 Room 同步的进程上。缓存要救的是**重复**，不是**不同**。
 *
 * 代价就写在这儿：两条各 ≤ 480x320 ARGB_8888 = 614,400 B，进程内驻留上限 1,228,800 B。
 */
internal const val GLASS_CACHE_MAX_ENTRIES = 2

/**
 * 一轴短到这条以下就不按真实尺寸出图（见 [bakeSizePx]）。
 *
 * 这条线是为廉价模糊那一步画的：管线把画布缩到 1/4 再放回，短边一旦掉到 64 以下，
 * 缩完就只剩 16px 的一栏，糊出来的东西与那张壁纸已经没关系了。现实里撞不上这一档
 * （最小的 2×1「下一节课」短边是 40dp，density 2.625 的机上是 105px），
 * 真撞上就退回改前那一档固定画布 —— 行为与今天一致，不会更差。
 */
internal const val MIN_BAKE_AXIS_PX = 64

/**
 * 一张壁纸的身份，全部是能在 JVM 里比的数 —— 而 T46 起组件侧只有一种壁纸：
 * 实测到手的系统桌面壁纸。改前的另一格「自选那张（身份 = URI）」连同它的
 * "命中时省一次解码"红利一起作废：把一张与桌面无关的图当不透明底铺上去，
 * 正是 (69,77,97) 恒值板那起事故的图源半边（论证在 [WidgetGlassSource]）。
 *
 * 身份 = 宽高 + 9 枚采样像素（[wallpaperSamplePoints]，取样与拒缓存的账在
 * [glassBakeKey]）。桌面换壁纸没有任何广播进到我们进程，
 * 只有把内容本身编进键才不会糊出一张旧壁纸。
 */
internal data class GlassSourceIdentity(
    val widthPx: Int,
    val heightPx: Int,
    val samples: List<Int>,
)

/** 这一次要烘的画布尺寸（px）。 */
internal data class BakeSize(val widthPx: Int, val heightPx: Int)

/**
 * 烘焙结果的缓存键：**凡是能让最终像素变的输入，都必须在这里有一枚分量**。
 *
 * 逐枚对上管线（[WidgetBackgroundRenderer.bakeGlass]）：
 * - [source] —— `drawBitmap(blurred, …)` 的那个 src，壁纸换了像素就换了；
 * - [bakeWidthPx] / [bakeHeightPx] —— 两次 `scale` 的目标尺寸与画布本身；
 * - [radiusX] / [radiusY] —— 裁剪 Path 用的半径。这里放的是**换算完的半径**而不是
 *   `cornerDp` / density / 组件尺寸那三枚原始输入：换算在 [WidgetCornerRadii.bake] 里，
 *   只要它输出的数相同，画出来的形状就相同。放终点数而不是放起点数，键就永远不会
 *   比管线"多判"或"少判"一档 —— 少判那一格是陈旧画面，多判那一格是白烤。
 * - [glassArgb] —— drawRect 那层的最终颜色，透明度已经折进高字节（[glassTintArgb]）；
 * - [nightMode] —— 「跟随系统取色」的底色来自 DayNight 主题（[resolveSystemWidgetColor]）。
 *
 * 刻意**不进键**的：appWidgetId（它是"谁在用"，不是"长什么样"，进键等于逐实例一条，
 * N 个实例永远命中不了）、textMode / showTitle / gridMaxLines（不碰这张背景位图）。
 */
internal data class GlassBakeKey(
    val source: GlassSourceIdentity,
    val bakeWidthPx: Int,
    val bakeHeightPx: Int,
    val radiusX: Float,
    val radiusY: Float,
    val glassArgb: Int,
    val nightMode: Boolean,
)

/**
 * 键的构造点，同时是"这一次能不能用缓存"的那道闸。
 *
 * 返回 null = 这次别用缓存（照改前一样现烤，也不入库）。T46 收口后组件侧只剩
 * 系统源这一种壁纸，所以这一道闸也就是全部：内容签名取不到（硬件位图
 * `getPixel` 抛了、或刚被系统回收）时身份上只剩宽高，用户换一张**同尺寸**的
 * 壁纸会被判成同一个键 —— 组件会一直糊着旧壁纸，而这正是缓存唯一不能被原谅的
 * 那一形。宁可白烤，不可糊错。
 *
 * 「无源」那一格不留陈旧状态：判到没有源时 [wallpaperForRender] 直接回 null，
 * render 在这条键构造之前就返回了，缓存里从来不会有一格「无源」可记；
 * [GlassBakeCache.obtain] 不缓存 null 的口径因此仍然够 —— 它兜的是"烤失败"，
 * 与"没图可烤"是两条路。换壁纸翻面的那一格由 [GlassSourceIdentity] 的内容签名负责。
 */
internal fun glassBakeKey(
    source: GlassSourceIdentity,
    size: BakeSize,
    radii: BakedCornerRadius,
    glassArgb: Int,
    nightMode: Boolean,
): GlassBakeKey? {
    if (source.samples.isEmpty()) return null
    return GlassBakeKey(
        source = source,
        bakeWidthPx = size.widthPx,
        bakeHeightPx = size.heightPx,
        radiusX = radii.radiusX,
        radiusY = radii.radiusY,
        glassArgb = glassArgb,
        nightMode = nightMode,
    )
}

/**
 * 从这一个实例报上来的真实尺寸推出要烘的画布尺寸（每一轴各推各的）。
 *
 * 改前这里是"一律 480x320"：一个 2×1「下一节课」真身 110x40dp，在 density 2.625 的机上
 * 是 288x105px，却还是先烤 480x320（614,400 B）、再由 fitXY 缩回 288x105 显示 ——
 * 白多分配五倍的像素（153,600px 对 30,240px）、白多采样五倍，而且 Launcher 那一步
 * 缩放还把模糊又糊了一层。现在按真实尺寸出一张
 * 288x105（120,960 B），1:1 显示。
 *
 * 每一轴三档，方向只有一个：**出图尺寸永远不超过该轴的真实尺寸**（除了退回上限那一档，
 * 那是尺寸缺失/退化时的改前口径）：
 * - 该轴缺数（[size] 为 null，或这一轴 ≤ 0）→ 用 [maxWidthPx]/[maxHeightPx]，与改前逐字一致；
 * - 真实尺寸落在 `[minUsefulPx, max]` → 就用它（向下取整，宁可少一像素）；
 * - 短到 [minUsefulPx] 以下 → 退回上限，理由见 [MIN_BAKE_AXIS_PX]。
 *
 * 为什么出图尺寸变了而**屏幕上看到的圆角不变**（这条是本函数敢动的立论）：
 * [WidgetCornerRadii.bake] 烘的半径是 `cornerDp * density / (组件px / 画布px)`，
 * Launcher 再按 `组件px / 画布px` 把它拉回去，两个数在屏幕上互为逆运算，
 * 乘出来恒等于 `cornerDp * density` —— 画布取 480 还是取 288 根本不参与这个结果。
 * 同理，模糊强度是"缩到本画布的 1/4 再放回"，是画面内容的 25%，也不随画布绝对尺寸变。
 * 所以这一档动的只是位图字节数与采样密度，动的都不是刚验收过的那三件收口项
 * （圆角、透明度、取色）。
 */
internal fun bakeSizePx(
    size: WidgetSizePx?,
    maxWidthPx: Int,
    maxHeightPx: Int,
    minUsefulPx: Int = MIN_BAKE_AXIS_PX,
): BakeSize = BakeSize(
    widthPx = bakeAxisPx(size?.widthPx, maxWidthPx, minUsefulPx),
    heightPx = bakeAxisPx(size?.heightPx, maxHeightPx, minUsefulPx),
)

private fun bakeAxisPx(axisPx: Float?, maxPx: Int, minUsefulPx: Int): Int {
    if (axisPx == null || axisPx <= 0f) return maxPx
    // 向下取整：出图尺寸宁可少一像素也不许多一像素 —— 多出真实尺寸的那一列
    // 会在 fitXY 下被压掉，压掉哪一列是浮点误差说了算，不是判据。
    val actual = axisPx.toInt()
    return when {
        actual > maxPx -> maxPx
        actual < minUsefulPx -> maxPx
        else -> actual
    }
}

/**
 * 壁纸内容签名的取样点：3x3，落在 1/8 到 7/8 那一带。
 *
 * 为什么不取边缘一圈：边缘有状态栏压暗、有启动器的圆角遮罩、还有整块的黑边，
 * 那些东西换了壁纸也不动，取它们等于给两幅不同的壁纸做出同一个签名。
 * 为什么不取中心一个：一张"上暗下亮"的壁纸换成另一张同样上暗下亮的，
 * 中心那一点撞上的概率不低，而 9 个点全撞上的概率已经没有工程意义了。
 *
 * 宽高不是正数（拿到位图却没尺寸）时交空表 —— 调用方据此放弃缓存，见 [glassBakeKey]。
 */
internal fun wallpaperSamplePoints(widthPx: Int, heightPx: Int): List<Pair<Int, Int>> {
    if (widthPx <= 0 || heightPx <= 0) return emptyList()
    val xs = listOf(widthPx / 8, widthPx / 2, (widthPx * 7) / 8).distinctClamp(widthPx)
    val ys = listOf(heightPx / 8, heightPx / 2, (heightPx * 7) / 8).distinctClamp(heightPx)
    return ys.flatMap { y -> xs.map { x -> x to y } }
}

/** 保证每个坐标都落在 `0 until dimension` 里（1xN 这种退化尺寸上 1/8 与 1/2 会重合到 0，没问题，但 7/8 可能越界）。 */
private fun List<Int>.distinctClamp(dimension: Int): List<Int> =
    distinct().map { it.coerceIn(0, dimension - 1) }

/**
 * 玻璃烘焙结果的有界 LRU。
 *
 * 为什么用泛型而不是直接持有 `Bitmap`：这件容器是"键 → 至多一份昂贵结果"，
 * 与里面放的是什么无关。把它做成泛型，本卡的判据（同键第二次不重复烤、逐出边界）
 * 就能在纯 JVM 单测里用一个计数假件钉住 —— 真位图在这个模块的单测里根本造不出来。
 * 生产与测试跑的是**同一个** [obtain]，测的即是用的。
 *
 * 线程：刷新全在 `goAsync()` 的 IO 协程上（`WidgetCommon.launchRefresh`），
 * 六个 Provider 各自起协程，所以 [obtain] 整体加锁 —— 锁的是查表与写入这两次
 * 哈希操作，不锁烘焙：两个线程同时未命中同一个键时各自烤一份，写进去后一份赢，
 * 结果是同一张画（同键同管线），多花一次 CPU 而不会出错。反过来若把烘焙圈进锁里，
 * 一次 8MB 位图的重采样会把同轮后面的实例堵在锁上等，那就是把省下来的钱换个地方花。
 *
 * 逐出时**不回收**位图：这张图可能已经交给 RemoteViews、还在 binder 事务里排着，
 * 或者正被 Launcher 显示 —— 回收一张别人还引用的位图，下一次用到它就是
 * Canvas/`drawBitmap` 上的 IllegalStateException。丢掉引用就够了：API 26 起
 * 位图像素就在 Java 堆上，GC 自己收（这仓库 minSdk 26，没有"像素在 native 堆、
 * 不 recycle 就漏"那一档要照顾）。
 */
internal class GlassBakeCache<V>(private val maxEntries: Int = GLASS_CACHE_MAX_ENTRIES) {

    /** accessOrder = true：读取也算使用，被逐出的永远是最久没被碰过的那一条。 */
    private val entries = object : LinkedHashMap<GlassBakeKey, V>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlassBakeKey, V>?): Boolean =
            size > maxEntries
    }

    /**
     * 命中就回缓存那份；未命中跑一次 [bake] 并入库。
     *
     * [bake] 交回 null（图源撞空、尺寸算崩）时**不入库**：失败多半下一刻就好了
     * （用户刚挑了张图），把"画不出来"缓存下来就等于把一次瞬时失败变成一整轮的纯色底。
     */
    @Synchronized
    fun obtain(key: GlassBakeKey, bake: () -> V?): V? {
        entries[key]?.let { return it }
        val baked = bake() ?: return null
        entries[key] = baked
        return baked
    }

    /** 单测与将来的排障入口用：生产路径上没有"清缓存"这一步，键自己会翻面。 */
    @Synchronized
    fun entryCount(): Int = entries.size
}

