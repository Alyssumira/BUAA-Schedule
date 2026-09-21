package com.buaa.schedule.widget

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get

/**
 * 「这台设备这一次实测读不读得到系统桌面壁纸」的唯一实测点 + 进程内 memo。
 *
 * 它替代的是那道一刀切的 SDK 闸门（原 `MIN_SDK_SYSTEM_WALLPAPER_UNREADABLE = 34`，
 * T46 起删除）。那道闸门断言"Android 14 起第三方应用读不到桌面壁纸"；T46 的提交里
 * 写着它**已被装机实测证伪** —— 那句订正本领也是错的，它量的是「无源时那块半透明板
 * 透出来的桌面像素」（板本身半透明、RemoteViews 的宿主窗口透明），不是"壁纸位图进到
 * 过组件"的证据。同一台设备（buaa36 / API 36 / 1080×2400）上重新取证跑出的真因是
 * **两道闸**：
 * - 四个读图入口（`getDrawable()` / `peekDrawable()` / `getBitmap()` /
 *   `peekDrawable(displayId)`）全部抛
 *   `SecurityException: Permission android.permission.READ_EXTERNAL_STORAGE denied for package com.buaa.schedule`
 *   —— 本应用**从来没有声明过**这枚权限
 *   （`git log -S READ_EXTERNAL_STORAGE -- app/src/main/AndroidManifest.xml` 零条提交）；
 * - 只声明 + 授予那枚权限之后改抛 `SecurityException: Op READ_MEDIA_IMAGES ignore for package com.buaa.schedule`
 *   —— app-op 那一层还在拦；两枚相册权限都补齐才真的返回一张 922×1024 ARGB_8888 的位图。
 *
 * ⇒ 平台在 API 36 仍然把桌面壁纸位图发给三方应用，挡路的只有我们自己不肯要相册权限。
 * 所以"闸门白关了一半设备"这句也要收口：它关掉的是一条本来就撞在权限上的路；
 * 而它留下的结论（34+ 也要问一次、由实测而不是断言决定有没有源）是对的，这一条留着。
 *
 * **为什么不申请那两枚权限**（本卡的取舍，写在这里是为了下一个人别顺手加上）：
 * 为一块背景板去要相册权限，代价大于收益 —— 用户对一颗日历 App 的合理预期里不包含
 * "读我相册"，而权限一旦声明就是安装页上永久可见的一行；换到的只是"糊上去一张真壁纸"
 * 这一格观感。下面的第二档零权限就能给同一块板上桌面的颜色。
 *
 * 两档图源，按优先级：
 * 1. **位图档** —— [capture] 里那条 `getDrawable()`，本卡一个字没动。留着它是给
 *   "某台设备真的给了权限"那一形的优先源（API ≤33 那档平台不查这枚权限，但**本卡没有
 *   那种设备、未实测**，所以这一条只是不把猜测写成结论，不是已验证的出路）；
 *   判到没有位图时才轮到第二档。
 * 2. **主色档** —— [paletteOf] 问的 `getWallpaperColors(FLAG_SYSTEM)`（API 27 起才有这枚方法，
 *   minSdk 26 那一档机器上这一格交 null、走第三档），零权限拿得到桌面主色/副色与 colorHints。
 *   装机实测读数：primary = sRGB(0.204, 0.243, 0.396)
 *   = (52, 62, 101)、secondary = (16, 15, 25)、colorHints = 6。它给不了一张图，
 *   但给得出一块板的颜色 —— 「玻璃感壁纸背景」在拿不到位图的设备上第一次有可见效果，
 *   靠的就是这一档（板色怎么从这三格推出来是 [WidgetGlassSource.palettePlateArgb] 的事）。
 *   注意一次只能问一种壁纸：`FLAG_SYSTEM or FLAG_LOCK` 抛
 *   `IllegalArgumentException: Must specify exactly one kind of wallpaper to read`。
 *
 * 判据本体不在这里 —— [WidgetGlassSource] 那两张表（位图档 [WidgetGlassSource.wallpaperLooksUsable]、
 * 主色档 [WidgetGlassSource.palettePlateArgb]）都是纯 JVM 的，这里只负责把设备事实
 * （位图宽高、9 枚采样像素、三格颜色）翻译成 Int / Float 参数交进去，与
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
 * 线程：[measure] 里有一次 binder 问图 + 9 枚 getPixel（非位图 drawable 还要画一张
 * 整屏的渲染目标）+ 一次 binder 问色（只在判到位图不可用时才付），不许上主线程 ——
 * 调用点全在 IO 协程上：组件刷新走 `WidgetCommon.launchRefresh` 的 `Dispatchers.IO`，
 * 配置页走 LaunchedEffect 里的 `withContext(Dispatchers.IO)`。[memoized] 与
 * [memoizedPalette] 是纯读，主线程安全。写点走同一次 @Volatile 逐格覆写 + 一个时间戳，
 * 不需要更多同步：谁后写谁的结论生效，而两份结论出自同一个 [measure] + 同一个判据，
 * 最坏是下一拍就对齐的一格不一致 —— 换来的是「配置页与渲染侧同一个答案」（T31 的规矩）
 * 在这里只有**一个**出处。
 */
internal object WidgetWallpaperProbe {

    /**
     * 一次位图实测的完整收获：那张位图（连同它归不归我们回收）与它交出的身份。
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
     * 一次主色实测的收获：`getWallpaperColors(FLAG_SYSTEM)` 交回的三格事实，
     * 全部翻译成能在 JVM 里比的数（Int / Int?）。
     *
     * 这里不出现 `WallpaperColors` / `Color` 这两个类型 —— 与 [Captured] 同一套分工：
     * 设备类型只活在这一层，判据那边（[WidgetGlassSource.palettePlateArgb]）收的是
     * Int，于是那张表能逐格钉在零 android import 的 JVM 单测里。
     *
     * @param primaryArgb 主色（`Color.toArgb()`，高字节按不透明对待）
     * @param secondaryArgb 副色，平台允许为 null（有些桌面只算得出主色）
     * @param colorHints `WallpaperColors.getColorHints()` 的原始位掩码。
     *        装机实测到的是 6：本卡只认得清两枚公开位
     *        （`HINT_SUPPORTS_DARK_TEXT` = 1、`HINT_SUPPORTS_DARK_THEME` = 2），
     *        其余位在判据那头一律不参与 —— 语义未取证的位不当判据用。
     */
    class Palette(
        val primaryArgb: Int,
        val secondaryArgb: Int?,
        val colorHints: Int,
    )

    /**
     * 一次 [measure] 的收获：**两档图源各自问到了什么**。
     *
     * 两格都要在这里，而不是让调用方去 memo 里补第二枪 —— 理由与 T31 那条「配置页与
     * 渲染侧同一个答案」是同一份：一轮刷新里先问图、再回手读 memo 的颜色的话，
     * 六家 Provider 并发写 memo 时读到的可能是别人那一轮的数（同一条设备事实、
     * 最坏下一拍对齐，所以不算错图），但**这一次调用**交出的答案就不出自自己了。
     * 位图档判成可用时 [palette] 刻意为 null —— 次选档在拿到图的设备上永远不会被读到，
     * 不必为它多付一次 binder。
     */
    class Measurement(
        val captured: Captured?,
        val palette: Palette?,
    ) {
        /** 位图档的回收账原样转给 [Captured.recycleIfOwned]；没有位图时这是一个空操作。 */
        fun recycleIfOwned() {
            captured?.recycleIfOwned()
        }
    }

    /**
     * memo 的采信期限。取 60s 的理由：实测的全部成本就是一次 binder 问图 + 9 枚
     * 取点（外加位图判空时的一次 binder 问色），重测便宜到没必要为省它付更长的陈旧账；
     * 而 60s 又长过配置页一屏说明停留的正常时间 —— 打开配置页不触发重测，重开一次才可能触发。
     */
    private const val MEMO_TTL_MS = 60_000L

    /**
     * memo 的三个字段，**写序与读序本身是判据的一部分**（红线：探针与 memo 的线程安全）。
     *
     * 写侧：先写结论（两格结论 [memoUsable] / [memoPalette]）再写时间戳。读侧：先看
     * 时间戳再看结论。于是能看见新时间戳的那一刻必然也看得见新结论（volatile 写的
     * release 语义会把先前的写一起带出去），看不见的就退回旧时间戳那一格 —— 过不过期
     * 由它自己算，最坏读出"过期"交 null，也就是「还没测」那一格。两个方向都不会读出
     * 一对**配错**的（新戳 + 旧结论）。
     *
     * 为什么不用锁、也不把三枚装进一个不可变对象再整体换：写点只有 [measure] 一处，
     * 而它一次写的就是"这一台设备此刻读得到什么"这一个问题的两条事实答案（有没有位图、
     * 有没有主色）；六家 Provider 各自在 `goAsync()` 续命的 IO 协程上跑同一个 [measure]、
     * 走同一批判据，最坏是后写的那一份把先写的顶掉，而那两份之间没有任何
     * "必须原子地一起成立"的承诺（不像尺寸+档位那种要配对着进缓存键的数）。
     * 位图像素**从不**从 memo 里拿（缓存的是结论，不是图），所以并发最坏的后果
     * 停在配置页那一句说明上，不会画出错图。
     */
    @Volatile
    private var memoUsable: Boolean? = null

    @Volatile
    private var memoPalette: Palette? = null

    @Volatile
    private var memoAtElapsed = Long.MIN_VALUE

    /** 采信期内的上次实测结论；没判过或已过期的都交 null（=「还没测」，不是「读不到」）。 */
    fun memoized(): Boolean? {
        if (memoAtElapsed == Long.MIN_VALUE) return null
        if (SystemClock.elapsedRealtime() - memoAtElapsed > MEMO_TTL_MS) return null
        return memoUsable
    }

    /**
     * 采信期内上次实测问到的主色；没判过、已过期、以及**位图档已经赢那一轮**都交 null。
     *
     * 读侧先看时间戳的规矩与 [memoized] 逐字一致 —— 两枚结论各自配一格新鲜度判断，
     * 才会出现"结论新的、时间戳旧的"那种读法。判到 null 的两种来路（没主色 / 用不着主色）
     * 在调用点是同一侧的安全答案：[WidgetGlassSource.decide] 只在 `systemWallpaperUsable
     * == false` 之后才看这一格，位图可用那一轮它根本不被问到。
     */
    fun memoizedPalette(): Palette? {
        if (memoAtElapsed == Long.MIN_VALUE) return null
        if (SystemClock.elapsedRealtime() - memoAtElapsed > MEMO_TTL_MS) return null
        return memoPalette
    }

    /**
     * 实测一次：先问位图（[capture] + [WidgetGlassSource.wallpaperLooksUsable]），
     * 判到位图不可用才再问主色（[paletteOf]）。两格结论都覆写进 memo，
     * 并且原样交给这一次的调用方（[Measurement]）。
     *
     * 只能在 IO 线程调（类的 KDoc 那条线程账）；异常整条吞成「那一档没有源」，
     * 与改前闸门的行为口径一致，区别只是这个答案现在是问出来的而不是猜的。
     * 交判据的三格位图事实（拿不到位图 / 尺寸退化 / 采样全同的纯色占位）都在 [capture]
     * 里取得（宽高 + 9 枚采样），为什么纯色占位不算壁纸，论证在
     * [WidgetGlassSource.wallpaperLooksUsable] 那一条。
     */
    fun measure(context: Context): Measurement {
        val captured = runCatching { capture(context) }.getOrNull()
        val usable = captured != null && WidgetGlassSource.wallpaperLooksUsable(
            widthPx = captured.identity.widthPx,
            heightPx = captured.identity.heightPx,
            samples = captured.identity.samples,
        )
        val palette = if (usable) null else runCatching { paletteOf(context) }.getOrNull()
        memoUsable = usable
        memoPalette = palette
        memoAtElapsed = SystemClock.elapsedRealtime()
        if (!usable) captured?.recycleIfOwned()
        return Measurement(captured.takeIf { usable }, palette)
    }

    /**
     * 取图 + 算身份。`getDrawable()` 十有八九给的是系统持有的 BitmapDrawable，
     * 那条路零拷贝；不是位图的 drawable（矢量/动态壁纸的壳）才画一张渲染目标，
     * 那种位图归我们回收。尺寸从位图本身取 —— 拿到位图却没尺寸（宽高 ≤0）
     * 交给判据的是退化那一格。
     *
     * 本机（API 36、未声明相册权限）实测：这一条**必然抛** `SecurityException`
     * （READ_EXTERNAL_STORAGE 被拒），于是整条位图档在 [measure] 里判成不可用、
     * 由 [paletteOf] 那一档接手。`getDrawable()` 在 `capture` 内第一句就被调用，
     * 而抛掉的异常由 [measure] 外层的 `runCatching` 吞掉 —— 这里保留 `drawable`
     * 的整条原实现，是为了在权限给得到的设备上不必再改一次这文件。
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
     * 问一次 `getWallpaperColors(FLAG_SYSTEM)`，把三格颜色翻译成 [Palette]。
     *
     * `FLAG_SYSTEM` 单独传，**不许与 `FLAG_LOCK` 或起来** —— 平台一次只让问一种壁纸，
     * 混着传抛 `IllegalArgumentException: Must specify exactly one kind of wallpaper to read`
     * （装机实测）。这条链上没有任何一档要读锁屏壁纸：组件贴在桌面上，跟着桌面那张走
     * 才是这个问题要的答案。
     *
     * 三种出口都交 null，都按「这一档没有主色可用」处理：这台设备是 API 26（`getWallpaperColors`
     * 是 API 27 才加的方法，与"读不读得到壁纸"无关，纯粹是那条问句还不存在）、抛异常（含权限/
     * 服务拿不到的那一形）、返回 null（平台算不出颜色）、以及 primary 为 null（只剩两枚次要
     * 颜色的那一格 —— 本卡的板色以 primary 定色相，没有它就是没有，不拿 secondary 硬顶）。
     */
    @SuppressLint("MissingPermission") // 这一档立论就是"零权限也要有答案"，声明权限反而是本卡不许做的事
    private fun paletteOf(context: Context): Palette? {
        // API 26 上根本没有这一问（方法 27 才加，`NewApi` 那道 lint 就是它的凭据），交 null 之后
        // 那台设备走第三档 = 画用户自己选的纯色。注意这一格与 T46 拆掉的那道闸门**不是一类东西**：
        // 那道闸门拿 API 档次代答「读不读得到壁纸」这件实测得出的事（本机实测已证其反），
        // 这一格挡的是一枚方法在不在这个系统版本上存在 —— 问不出结果，因为问句还没被写出来。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        val colors = WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
        val primary = colors.primaryColor ?: return null
        return Palette(
            primaryArgb = primary.toArgb(),
            secondaryArgb = colors.secondaryColor?.toArgb(),
            // `getColorHints()` 是 API 31 才公开的（本机 API 36 量到 6；`primaryColor` /
            // `secondaryColor` 27 起就有）。27~30 上交 0，而 0 在判据里的语义正是
            // "平台自己没话说" —— 那一档于是只按两色的实测明暗走，第 2 步天然不投票，
            // 不需要再造一层"读不到就当没有"的兜底口径。
            colorHints = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) colors.colorHints else 0,
        )
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
