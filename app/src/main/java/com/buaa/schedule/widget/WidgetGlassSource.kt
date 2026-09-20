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
 */

/** 这一次玻璃背景的图源结论：能不能成；不成，是因为哪一条。 */
internal enum class GlassSource {

    /** 优先糊系统桌面壁纸，App 内自选那张兜底 */
    SystemWallpaperThenPicked,

    /** 只认用户在 App 内「外观 → 选择壁纸图片」自选的那张 */
    PickedImage,

    /** 没有图源：Android 14 起平台不再允许第三方应用读桌面壁纸，而用户还没挑图 */
    NoSourceWallpaperReadBlocked,

    /** 没有图源：用户关掉了 App 内「使用桌面壁纸」，而自选那张还是空的 */
    NoSourceSystemWallpaperOff,
    ;

    /** 这张玻璃画得出来吗？画不出来时 `WidgetCommon.applyAppearance` 落到纯色那条分支 */
    val usable: Boolean get() = this == SystemWallpaperThenPicked || this == PickedImage
}

internal object WidgetGlassSource {

    /**
     * 从这一级 API 起 `WallpaperManager.getDrawable()` 对普通应用不再可读
     * （需要 `READ_WALLPAPER_INTERNAL` 或 `MANAGE_EXTERNAL_STORAGE`，都拿不到）。
     *
     * 写成裸数字而不是 `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`：这个文件不 import android，
     * 而它恰恰是"版本闸门"这件事的唯一出处（渲染侧经 [systemWallpaperReadable] 用它）。
     */
    const val MIN_SDK_SYSTEM_WALLPAPER_UNREADABLE = 34

    /** 这台设备读得到系统桌面壁纸吗。 */
    fun systemWallpaperReadable(sdkInt: Int): Boolean =
        sdkInt < MIN_SDK_SYSTEM_WALLPAPER_UNREADABLE

    /**
     * 自选那张算不算一个图源。
     *
     * 空白串与 null 同一格：它进解码那头只会拿不到位图，
     * 把它当成"有图"就等于让配置页继续承诺一张糊不出来的玻璃。
     */
    fun hasPickedImage(wallpaperUri: String?): Boolean = !wallpaperUri.isNullOrBlank()

    /**
     * 取源顺序：
     * - 用户没关掉「使用桌面壁纸」时**优先**系统源（这是既有语义，不许反过来）；
     * - 关掉了就**只**认自选那张 —— 他刚说不想用桌面壁纸，组件却还在糊桌面壁纸，两边就对不上了；
     * - 两条都没有就是没有，配置页必须如实说出来，而不是把开关留给用户拨。
     *
     * @param systemWallpaperReadable 这台设备读得到系统桌面壁纸吗（见 [systemWallpaperReadable]）
     */
    fun decide(
        systemWallpaperReadable: Boolean,
        useSystemWallpaper: Boolean,
        hasPickedImage: Boolean,
    ): GlassSource = when {
        // ① 用户关掉「使用桌面壁纸」→ 只认自选那张。撞空的原因是他自己的选择，与 API 档次无关：
        //    指回"平台读不到"会让他去反抗一个不用反抗的东西，出路就在这两枚设置里。
        !useSystemWallpaper ->
            if (hasPickedImage) GlassSource.PickedImage else GlassSource.NoSourceSystemWallpaperOff
        // ② 开关开着、这台设备又读得到桌面壁纸 → 系统源优先，自选那张只是兜底。
        //    这条先后是既有语义，不许反过来（App 内挑图的优先级本来就排在系统源之后）。
        systemWallpaperReadable -> GlassSource.SystemWallpaperThenPicked
        // ③ Android 14+ → 系统源那一头已经空了，只剩自选那张；一张都没有就是实测那一格：
        //    把开关从关拨到开，整块组件 tile 的像素差 0 / 258258。以前这里照样答
        //    SystemWallpaperThenPicked，于是配置页那句话开始骗人。
        hasPickedImage -> GlassSource.PickedImage
        else -> GlassSource.NoSourceWallpaperReadBlocked
    }

    /**
     * 组件读的全局键只有 `wallpaper_uri` / `wallpaper_use_system` 这两个（见
     * `WidgetBackgroundRenderer.wallpaperSource`）：只有它们真的变了，
     * 才值得把桌面上所有已绑定的实例重绘一遍。
     *
     * `Personalization.save()` 是被十几个调用点共用的落盘口（透明度、模糊、周视图行高……
     * 每一处都在写同一份 prefs），不区分键的话每拖一次滑块就要重绘六个组件。
     */
    fun wallpaperKeysChanged(
        savedUri: String?,
        savedUseSystem: Boolean,
        currentUri: String?,
        currentUseSystem: Boolean,
    ): Boolean =
        savedUseSystem != currentUseSystem || pickedImageChanged(savedUri, currentUri)

    /**
     * 自选那张换没换。两边都先按"是不是一个图源"归一（与 [hasPickedImage] 同一口径），
     * 否则 `null` 与空白串会被判成"变了"，白重绘六个组件。
     *
     * 认不出的一种：URI 没变、但那张图的内容被外部改过。那条只能靠设置页那颗
     * 手动「立即刷新」按钮，不在这一格的承诺里。
     */
    private fun pickedImageChanged(savedUri: String?, currentUri: String?): Boolean =
        savedUri.asPickedImage() != currentUri.asPickedImage()

    /** 把 URI 归一成"图源 or 没有" */
    private fun String?.asPickedImage(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
