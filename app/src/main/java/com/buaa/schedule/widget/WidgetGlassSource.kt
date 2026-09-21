package com.buaa.schedule.widget

/*
 * 「玻璃感壁纸背景」这一次到底有没有图源 —— 纯判定，不 import 任何 android 类型。
 *
 * 抽取理由与 `WidgetCornerRadii` 同一套：这条判据决定配置页上那句"把壁纸糊成组件的底图"
 * 是不是在骗人，而本模块的 JVM 单测没有 Robolectric —— 任何碰 `Canvas` /
 * `WallpaperManager` / `Build.VERSION` 的写法都测不到它，只能测到"返回了 null"。
 *
 * 它同时是渲染侧与配置页共用的那**一个**答案：两边各判一次，就会出现
 * 开关照旧能拨、拨完照旧没反应（真机实测：无源时整块 tile 的像素差 0/258258）。
 *
 * T46 换掉的是这格答案的**来历**，不是这格答案的地位：以前「这台设备读不读得到
 * 桌面壁纸」由一道一刀切的 SDK 闸门判（≥34 一律答"读不到"），而装机实测把那句
 * 平台断言证伪了 —— API 36 的镜像上 `getDrawable()` 仍返回真实桌面壁纸，关掉闸门
 * 后组件 tile 立刻跟着桌面的亮暗两区走。现在它是每轮刷新一次的实测结论
 * （问图、翻译事实都在 `WidgetWallpaperProbe`），这里只留把三格事实嚼成答案的
 * 纯判据 —— 设备事实进不来，判据也就永远能在 JVM 里逐格钉住。
 */

/**
 * 这一次玻璃背景的图源结论：能不能成；不成，是因为哪一条。
 *
 * 这个类型存在的意义仍是**让配置页那句说明有出处**。口径还是四格，但换的是内容：
 * 组件侧只认实测到的系统桌面壁纸，App 内自选那张不再是图源（它不是桌面，
 * 把它当不透明底铺满 tile 就是那块"与壁纸毫无关系的死板"，装机实测的
 * (69,77,97) 恒值板就是这么来的），所以「糊自选那张」这一档连同它的两格
 * "先挑一张图"文案一起作废；换来的一格是「还没实测」—— 那个默认值不许折成
 * "读得到"或"读不到"任何一头，否则第一遍进配置页的人看见的就是一个凭空造出来的答案。
 *
 * 三个「没有图源 / 还不知道」要分开给：一条是设备的事实（说"这台设备实测读不到"，
 * 组件侧没有出路，别再挂那颗骗人的挑图按钮），一条是用户自己的选择（指回那枚开关，
 * 出路就在手边），一条是"这一句还没有出处"（说"正在确认，稍后再看这一句"）。
 * 合并成任意一格都会对某一头许诺它兑现不了的出路、或者把猜测当事实说给用户。
 *
 * **这里刻意不再有一枚 `usable` 布尔**：四格压成一格就必然要把「还没测」折进
 * "有"或"没有"，而本卡修的就是这类代答。调用点（配置页那句说明）因此必须对四格
 * 各自说话 —— `when` 不带 else，将来再加一格会在编译期就被点名，而不是悄悄落到某个
 * 布尔的某一头。
 */
internal enum class GlassSource {

    /** 实测到了一张能用的系统桌面壁纸 —— 组件侧唯一的图源，玻璃画得出来 */
    SystemWallpaper,

    /** 还没实测过（或上一次实测已过采信期）：这一句说明目前没有出处，不许替设备回答 */
    NotMeasuredYet,

    /** 没有图源：这台设备实测读不到系统桌面壁纸（拿不到位图、尺寸退化、或纯色占位图） */
    NoSourceSystemWallpaperUnusable,

    /** 没有图源：用户关掉了 App 内「使用桌面壁纸」 */
    NoSourceSystemWallpaperOff,
}

internal object WidgetGlassSource {

    /**
     * 实测那张"壁纸"位图任一根轴短到这条以下，就判它不是壁纸。
     *
     * 下限取 64：烘焙管线要把图源重采样到出图尺寸的 1/4 再放回（廉价模糊），
     * 短于 64px 的源缩完只剩 16px 的一栏 —— 与 `MIN_BAKE_AXIS_PX` 同一条"糊出来的
     * 东西已经和那张图没关系了"的线。而真实壁纸位图永远在屏幕尺寸量级：
     * minSdk 26 覆盖到的机器上没有短边低于 240px 的屏，64 离最矮的真屏都还有
     * 近四倍余量，撞得到的只可能是坏掉的渲染目标或那种 1x1 的占位位图。
     */
    const val MIN_WALLPAPER_EDGE_PX = 64

    /**
     * 把实测拿到的事实翻译成「这是不是一张真的桌面壁纸」。
     *
     * 三格判「没有源」，各钉一种坏形：
     * - 拿不到位图 / 取不到像素：调用点交 (0, 0, 空表) 进来（`getDrawable()` 返回 null、
     *   或采样整条崩掉 —— 空表就是那条兜底口径的出口）；
     * - 尺寸退化：任一根轴 ≤0 或短到 [MIN_WALLPAPER_EDGE_PX] 以下，理由见那条；
     * - **9 枚采样全同**：那是一张纯色位图，不是壁纸。平台在壁纸不可读时塞给应用的
     *   正是这种纯色占位位图，装机实测也见过它的另一张面孔 —— 一块与桌面无关的
     *   (69,77,97) 恒值死板，原料就是一张纯白占位图被当成壁纸糊了上去。
     *   判它「没有源」的代价是一台真纯色桌面的机器走纯色半透明那条分支，而这一格
     *   两头都站得住：纯色糊底与纯色半透明在像素上几乎同形（装机实测后者反而是
     *   桌面透得过来的那一形），不存在"把合法状态误伤成坏形"的账。
     *
     * 这里**不**判 `Build.VERSION` / 权限：那些设备事实由 `WidgetWallpaperProbe`
     * 在调用点实测后翻译成参数交进来（与 [decide] 同一套分工）。
     */
    fun wallpaperLooksUsable(widthPx: Int, heightPx: Int, samples: List<Int>): Boolean {
        if (samples.isEmpty()) return false
        if (samples.distinct().size == 1) return false
        return widthPx >= MIN_WALLPAPER_EDGE_PX && heightPx >= MIN_WALLPAPER_EDGE_PX
    }

    /**
     * 取源口径（T46 收口）：组件侧只认**实测到的系统桌面壁纸**，而"实测"那一格有三态。
     *
     * 顺序上「用户先说不」：关掉了那枚开关就当场无源，连实测都不必付（渲染侧
     * 因此可以跳过那次 binder 问图）。开关开着才轮到设备说话 —— 而设备的话分三种：
     * 测过说有、测过说没有、**还没测**。最后这一种不许折成前两种里的任何一头：
     * 折成"有"就是拿一句没有出处的承诺给用户，折成"没有"就是把一个还没问过的
     * 问题当成已经否认的回答。
     *
     * @param systemWallpaperUsable 这次实测问到的答案 ——「有没有一张能用的桌面壁纸」，
     *        出处是 `WidgetWallpaperProbe` 的运行时实测（经 [wallpaperLooksUsable]）；
     *        **null = 还没实测过（或已过采信期）**，不再由 API 档次代答
     * @param useSystemWallpaper App 内「使用桌面壁纸」那枚开关
     */
    fun decide(systemWallpaperUsable: Boolean?, useSystemWallpaper: Boolean): GlassSource = when {
        // ① 撞空的原因是他自己的选择，与设备读不读得到无关：指回设备会让他去
        //    反抗一个不用反抗的东西，出路就在这枚开关。
        !useSystemWallpaper -> GlassSource.NoSourceSystemWallpaperOff
        // ② 还没测过这一格必须自己站一格（配置页那句"正在确认"的出处）
        systemWallpaperUsable == null -> GlassSource.NotMeasuredYet
        // ③ 开关开着、这一次实测问到了 → 就是它。改前这里还排着"自选那张兜底"，
        //    兜出来的却是那块死板 —— 兜底这一整档作废。
        systemWallpaperUsable -> GlassSource.SystemWallpaper
        // ④ 实测说没有：拿不到位图、尺寸退化、或纯色占位图。这台设备的事实，
        //    那句话就得原样说给用户，而不是塞一颗挑图按钮骗人。
        else -> GlassSource.NoSourceSystemWallpaperUnusable
    }

    /**
     * 组件读的全局键只剩 `wallpaper_use_system` 这一枚（见
     * `WidgetBackgroundRenderer.availability`）：自选那张从 T46 起不再是组件图源，
     * 换它不换组件一个像素都不该动。只有这枚开关真的变了，
     * 才值得把桌面上所有已绑定的实例重绘一遍。
     *
     * `Personalization.save()` 是被十几个调用点共用的落盘口（透明度、模糊、周视图行高……
     * 每一处都在写同一份 prefs），不分键的话每拖一次滑块就要重绘六个组件。
     *
     * 这一枚判据还兼着「读不到」那一格的**唯一一条即时**失效路径（另一条是下一轮刷新
     * 自己重测）：拨开关会走到这里 → 触发重绘 → 重绘每轮无条件重测 → memo 翻面。
     */
    fun wallpaperSwitchChanged(savedUseSystem: Boolean, currentUseSystem: Boolean): Boolean =
        savedUseSystem != currentUseSystem
}
