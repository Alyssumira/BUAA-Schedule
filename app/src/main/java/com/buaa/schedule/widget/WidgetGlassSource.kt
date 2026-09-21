package com.buaa.schedule.widget

/*
 * 「玻璃感壁纸背景」这一次到底有没有图源、图源给到哪一档 —— 纯判定，不 import 任何 android 类型。
 *
 * 抽取理由与 `WidgetCornerRadii` 同一套：这条判据决定配置页上那句"把壁纸糊成组件的底图"
 * 是不是在骗人，而本模块的 JVM 单测没有 Robolectric —— 任何碰 `Canvas` /
 * `WallpaperManager` / `Build.VERSION` 的写法都测不到它，只能测到"返回了 null"。
 *
 * 它同时是渲染侧与配置页共用的那**一个**答案：两边各判一次，就会出现
 * 开关照旧能拨、拨完照旧没反应（真机实测：无源时整块 tile 的像素差 0/258258）。
 *
 * T46 换掉的是这格答案的**来历**：以前「这台设备读不读得到桌面壁纸」由一道一刀切的
 * SDK 闸门判（≥34 一律答"读不到"），T46 把它换成每轮刷新一次的运行时实测。
 * ⚠️ 而 T46 顺手写下的那句订正 ——「装机实测把平台断言证伪了：API 36 上 `getDrawable()`
 * 仍返回真实桌面壁纸，关掉闸门后 tile 立刻跟着桌面的亮暗两区走（亮区 (43,57,88)、
 * 暗区 (21,29,51)）」—— **本领也是错的**：那两组数量的是无源时那块半透明板透出来的
 * 桌面像素（板本身半透明、RemoteViews 宿主窗口透明），量的是桌面而不是玻璃。真因是
 * 「运行时权限 + app-op」两道闸：本应用从未声明 `READ_EXTERNAL_STORAGE`，四个读图入口
 * 因此在 API 36 上一律抛 SecurityException；补上那枚权限还有 `READ_MEDIA_IMAGES` 那一层，
 * 两枚相册权限齐全平台才真的给位图。⇒ 组件这条链**从来没有**糊到过桌面壁纸。
 * 逐条读数与本卡取舍（不去要相册权限）记在 `docs/KNOWN_ISSUES.md` §1 与
 * `WidgetWallpaperProbe` 的头注释里，这里只留两张能在 JVM 里逐格钉住的纯判据。
 */

/**
 * 这一次玻璃背景的图源结论：能不能成、成到哪一档；不成，是因为哪一条。
 *
 * 这个类型存在的意义仍是**让配置页那句说明有出处**。口径是五格：
 * 组件侧的图源分两档 —— **位图档**（实测到一张能用的系统桌面壁纸，糊的就是它）与
 * **主色档**（位图拿不到，但零权限问得到桌面的主色/副色，那块半透明板的底色由它推导）。
 * App 内自选那张在两档之外都不占一格（它不是桌面，把它当不透明底铺满 tile 就是那块
 * "与壁纸毫无关系的死板"，装机实测的 (69,77,97) 恒值板就是这么来的），
 * 所以「糊自选那张」这一档连同它的两格"先挑一张图"文案一起作废。
 *
 * 三个「没有图源 / 还不知道」要分开给：一条是设备的事实（说"这台设备实测读不到壁纸位图，
 * 也没问到主色"，组件侧没有出路，别再挂那颗骗人的挑图按钮），一条是用户自己的选择
 * （指回那枚开关，出路就在手边），一条是"这一句还没有出处"（说"正在确认，稍后再看这一句"）。
 * 合并成任意一格都会对某一头许诺它兑现不了的出路、或者把猜测当事实说给用户。
 *
 * **主色档单独占一格，而不是并进 `SystemWallpaper`**：那一格的用户话是"底图糊的是壁纸"，
 * 而主色档**没有糊任何东西**，只是让那块板跟着桌面的颜色走。混起来就是本卡要订正的那类
 * 许诺（T46 的配置页正是这么写的，而它当时一个像素都不多吃桌面）。
 *
 * **这里刻意不再有一枚 `usable` 布尔**：五格压成一格就必然要把「还没测」折进
 * "有"或"没有"，而这条链上修的就是这类代答。调用点（配置页那句说明、渲染侧那块板）
 * 因此必须对五格各自说话 —— `when` 不带 else，将来再加一格会在编译期就被点名，
 * 而不是悄悄落到某个布尔的某一头。
 */
internal enum class GlassSource {

    /** 位图档：实测到了一张能用的系统桌面壁纸 —— 玻璃糊的就是这张图 */
    SystemWallpaper,

    /** 主色档：位图拿不到，但零权限问到了桌面主色 —— 半透明板的底色由桌面主色推导，没有糊任何图 */
    SystemWallpaperPaletteOnly,

    /** 还没实测过（或上一次实测已过采信期）：这一句说明目前没有出处，不许替设备回答 */
    NotMeasuredYet,

    /** 两档都没有：这台设备实测既读不到壁纸位图、也问不到桌面主色 */
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

    // ---- 主色档的三条线（数值来历全部写在 [palettePlateArgb] 那一条里）----

    /** 组件那两支墨（白 [0xFFFFFFFF] 与近黑 [0xFF14161C]）等对比度的那一格：换不换墨的一侧按它判 */
    const val PLATE_LUMA_CROSSOVER = 0.197f

    /** 浅色墨那一侧要保证 AA，板色的相对亮度顶到这条为止 */
    const val PLATE_LIGHT_INK_MAX_LUMA = 0.183f

    /** 深色墨那一侧要保证 AA，板色的相对亮度地板 */
    const val PLATE_DARK_INK_MIN_LUMA = 0.212f

    /** `WallpaperColors.HINT_SUPPORTS_DARK_TEXT`：平台认为这块桌面适合**深色文字**（= 桌面偏亮） */
    const val HINT_SUPPORTS_DARK_TEXT = 1

    /** `WallpaperColors.HINT_SUPPORTS_DARK_THEME`：平台认为这块桌面适合**深色主题**（= 桌面偏暗） */
    const val HINT_SUPPORTS_DARK_THEME = 2

    /** 主色与副色的相对亮度差到这条以内，就当成"两色分不出明暗"，此时才让 hints 投票 */
    const val PALETTE_TIE_LUMA_GAP = 0.02f

    /** 向黑/白压亮度时对分的最大步数：撞满了还没进带就整格退回用户预设（理由见 [palettePlateArgb] 第 4 步） */
    private const val MAX_PLATE_PUSH_STEPS = 6

    /**
     * 把实测拿到的事实翻译成「这是不是一张真的桌面壁纸」。
     *
     * 三格判「没有源」，各钉一种坏形：
     * - 拿不到位图 / 取不到像素：调用点交 (0, 0, 空表) 进来（`getDrawable()` 返回 null、
     *   采样整条崩掉 —— 空表就是那条兜底口径的出口；本机 API 36 上是**抛 SecurityException**，
     *   真因见文件头那段，`WidgetWallpaperProbe.measure` 把它整条吞成这一格）；
     * - 尺寸退化：任一根轴 ≤0 或短到 [MIN_WALLPAPER_EDGE_PX] 以下，理由见那条；
     * - **9 枚采样全同**：那是一张纯色位图，不是壁纸。平台在壁纸不可读时塞给应用的
     *   正是这种纯色占位位图，装机实测也见过它的另一张面孔 —— 一块与桌面无关的
     *   (69,77,97) 恒值死板，原料就是一张纯白占位图被当成壁纸糊了上去。
     *   判它「没有源」的代价是一台真纯色桌面的机器走主色档（T47 之前是走纯色半透明），
     *   而这一格两头都站得住：纯色糊底与纯色半透明在像素上几乎同形（装机实测后者反而是
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
     * 取源口径：组件侧的图源两档，优先级「位图 > 主色」，而"实测"那一格有三态。
     *
     * 顺序上「用户先说不」：关掉了那枚开关就当场无源，连实测都不必付（渲染侧
     * 因此可以跳过那次 binder 问图与问色）。开关开着才轮到设备说话 —— 而设备的话
     * 分三种：测过说有位图、测过说没有、**还没测**。最后这一种不许折成前两种里的
     * 任何一头：折成"有"就是拿一句没有出处的承诺给用户，折成"没有"就是把一个还没
     * 问过的问题当成已经否认的回答。
     *
     * @param systemWallpaperUsable 位图档这次实测问到的答案 ——「有没有一张能用的桌面壁纸」，
     *        出处是 `WidgetWallpaperProbe` 的运行时实测（经 [wallpaperLooksUsable]）；
     *        **null = 还没实测过（或已过采信期）**，不再由 API 档次代答
     * @param wallpaperPaletteAvailable 主色档这次实测问到了没有 ——「`getWallpaperColors(FLAG_SYSTEM)`
     *        给没给出主色」。它只在位图档判成"没有"之后才被读到，所以**没测过那一格不必三态**：
     *        [NotMeasuredYet] 已经排在它前面短路掉了。位图档赢的那一轮探针刻意不去问色
     *        （见 `WidgetWallpaperProbe.measure`），那一格交 false 与"问不到"同侧，不影响答案。
     * @param useSystemWallpaper App 内「使用桌面壁纸」那枚开关
     */
    fun decide(
        systemWallpaperUsable: Boolean?,
        wallpaperPaletteAvailable: Boolean,
        useSystemWallpaper: Boolean,
    ): GlassSource = when {
        // ① 撞空的原因是他自己的选择，与设备读不读得到无关：指回设备会让他去
        //    反抗一个不用反抗的东西，出路就在这枚开关。
        !useSystemWallpaper -> GlassSource.NoSourceSystemWallpaperOff
        // ② 还没测过这一格必须自己站一格（配置页那句"正在确认"的出处）
        systemWallpaperUsable == null -> GlassSource.NotMeasuredYet
        // ③ 开关开着、这一次实测问到了位图 → 糊的就是它，主色档不参与
        systemWallpaperUsable -> GlassSource.SystemWallpaper
        // ④ 位图档撞空、但零权限问到了主色 → 板色跟着桌面走。这一格是本卡的全部目的：
        //    在拿不到位图的设备上，「玻璃感壁纸背景」第一次有可见效果。
        wallpaperPaletteAvailable -> GlassSource.SystemWallpaperPaletteOnly
        // ⑤ 两档都撞空：这台设备的事实，那句话就得原样说给用户，
        //    而不是塞一颗挑图按钮骗人。
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

    // ==================== 主色档：三格颜色怎么变成一块板 ====================

    /**
     * 把 `getWallpaperColors(FLAG_SYSTEM)` 交回的三格事实翻译成**一块板的颜色**。
     *
     * 这一步的全部目的：本机（API 36）实测读不到壁纸位图，于是「玻璃感壁纸背景」在
     * T46 之后仍然是一枚静默 no-op（`w8.blur` 在 true/false 之间来回拨，tile 像素差
     * 0 / 530100）。零权限问得到的主色是这一块板上唯一还活着的桌面信息 —— 用它，
     * 开关第一次拨得出可见变化；而它给的是**颜色**不是一张图，所以配置页那句也不许再
     * 说"糊的是壁纸"（口径在 [GlassSource.SystemWallpaperPaletteOnly] 那一格）。
     *
     * 四步，每一步都钉一格：
     *
     * 1. **primary 定色相，两色里较暗的那格定明度**：`base = 逐通道 (primary + darker + 1) / 2`。
     *    为什么不让 primary 自己当板色：主色是那张壁纸"出现最多的那一档颜色"，
     *    一张白壁纸的主色接近纯白，直接铺上去就是一块洗掉字的亮板（装机实测里
     *    `getWallpaperColors` 给的另一半信息正是干这个用的 —— 副色通常是桌面里
     *    压暗的那一档）。取平均而不是取较暗那格本身：整块换成 (16, 15, 25) 那种
     *    近黑色就把桌面的**色相**也丢了，那是"跟着桌面的明暗走"而不是"跟着桌面的颜色走"，
     *    而后者才是这块玻璃要卖的东西。副色为 null（平台只算得出主色）时 `darker = primary`，
     *    第 1 步因此是空操作，明暗那一格整个交给第 3 步的 hints 投票。
     * 2. **两色分不出明暗时才听 hints**：`|luma(primary) - luma(secondary)| < [PALETTE_TIE_LUMA_GAP]`
     *    才算平局。平局时平台那枚建议位是唯一的来历：
     *    [HINT_SUPPORTS_DARK_THEME]（适合深色主题 = 桌面偏暗）单独置位 → 向黑压一档
     *    （通道取 `(c*3)/4`）；[HINT_SUPPORTS_DARK_TEXT]（适合深色文字 = 桌面偏亮）单独置位
     *    → 向白提一档（`c + (255-c)*3/4`）；两枚都置或都不置 = 平台自己没话说 → 原样交回。
     *    非平局时 hints 一个通道都不碰：实测两色比一枚建议位可信，而本机量到的
     *    colorHints = 6 里第三格（bit 4）语义根本没取证，拿它当判据就是编。
     * 3. **不许换墨的一侧**：[presetArgb] 是这一格本来要画的那块板（用户自己选的配色，
     *    已经过 `resolvedBackground` 解析）。它的亮度落在 [PLATE_LUMA_CROSSOVER]
     *    （组件那两支墨等对比度的那一格）哪一侧，推导色就必须留在哪一侧 —— 跨过去了就
     *    **整格退回 [presetArgb]**。理由：用户那支墨可能是在 `textMode` 里**强制**选定的
     *    （`usesDarkInk` 的 TEXT_LIGHT / TEXT_DARK 两档），把板换到另一侧等于白字压白板；
     *    而"跟着桌面的颜色"从来不含"跟着桌面的明暗"这一项承诺。这一格不是摆设：
     *    一张接近纯白的壁纸（primary 亮）碰上"深玻璃"那种暗板预设时，第 1 步的混合色
     *    会亮过交点，就在这一步被拦下。
     * 4. **夹进既有的亮度地板**：同侧之内再要求 AA —— 暗侧顶到 [PLATE_LIGHT_INK_MAX_LUMA]
     *    为止，亮侧不低于 [PLATE_DARK_INK_MIN_LUMA]。做法是向黑/白对分压（每步通道取一半
     *    靠近极端色，最多 [MAX_PLATE_PUSH_STEPS] 步）。压满了还没进带（数学上到不了这一格，
     *    留着是给浮点与未来改数值兜底）就退回 [presetArgb]，宁可不跟桌面也不许糊字。
     *
     * 数值来历（都是 sRGB 相对亮度下的 WCAG 对比度，与 App 内 `legibleTintPlate` 那条链
     * 同一个亮度定义；组件那两支墨是白 `0xFFFFFFFF`（亮度 1.0）与近黑 `0xFF14161C`
     * （亮度 ≈0.008））：
     * - 交点：`1.05/(L+0.05) = (L+0.05)/0.058` → `L = 0.197`；
     * - 白字 AA：`1.05/(L+0.05) ≥ 4.5` → `L ≤ 0.183`；
     * - 黑字 AA：`(L+0.05)/0.058 ≥ 4.5` → `L ≥ 0.211`，取整到 [PLATE_DARK_INK_MIN_LUMA]
     *   的 0.212（往安全侧多留一格：0.211 代入是 4.495，差的那一点正是浮点末位会丢的）。
     * 三条数各自留了余量，于是**同侧之内**不会出现"改了底色反而把字洗掉"；
     * 卡末那两个改前/改后对比度就是按这三条线量的。
     *
     * 高字节恒 0xFF：这一档只交 RGB，那块板画多浓仍由既有的 `alphaFraction` 那条链管
     * （T37 修好的 alpha 复合口径不许在这里动第二次）。
     *
     * 这里不 import `Color` / `WallpaperColors`：三格颜色由 `WidgetWallpaperProbe.Palette`
     * 在调用点翻成 Int / Int? 交进来，于是这张表整个能在 JVM 单测里逐格钉住。
     *
     * @param primaryArgb 桌面主色（高字节忽略）
     * @param secondaryArgb 桌面副色，平台允许为 null
     * @param colorHints `WallpaperColors.getColorHints()` 的原始位掩码（本卡只认两枚公开位）
     * @param presetArgb 这一格本来要画的那块板（用户自己选的配色），定"墨在哪一侧"与安全兜底
     */
    fun palettePlateArgb(
        primaryArgb: Int,
        secondaryArgb: Int?,
        colorHints: Int,
        presetArgb: Int,
    ): Int {
        val primary = primaryArgb and 0xFFFFFF
        val secondary = secondaryArgb?.and(0xFFFFFF)
        // 第 1 步：primary 定色相，两色里较暗的那格定明度
        val darker = if (secondary == null || srgbLuminance(primary) <= srgbLuminance(secondary)) {
            primary
        } else {
            secondary
        }
        val lighter = if (darker == primary && secondary != null) secondary else primary
        var plate = mixRgb(primary, darker)
        // 第 2 步：两色分不出明暗时才让 hints 投票（非平局时一个通道都不碰）。
        // 副色为 null 也算平局 —— 只有一格颜色时没有"实测的明暗"可看，建议位是唯一来历。
        if (secondary != null && kotlin.math.abs(srgbLuminance(lighter) - srgbLuminance(darker)) >= PALETTE_TIE_LUMA_GAP) {
            return clampPlateToInkSide(plate, presetArgb)
        }
        val darkTheme = colorHints and HINT_SUPPORTS_DARK_THEME != 0
        val darkText = colorHints and HINT_SUPPORTS_DARK_TEXT != 0
        plate = when {
            darkTheme && !darkText -> nudgeRgb(plate, 0x000000)
            darkText && !darkTheme -> nudgeRgb(plate, 0xFFFFFF)
            else -> plate
        }
        return clampPlateToInkSide(plate, presetArgb)
    }

    /**
     * 第 3、4 步：先把推导色夹在用户那块板的**同一侧**（跨侧就整格退回预设），
     * 再在同侧之内压进 AA 带。两者都失败都退回 [presetArgb] —— 退回是安全侧，
     * 那一格的行为与改前逐字一致（画用户自己选的色）。
     *
     * 三处出口都带高字节 0xFF：这一档只交 RGB，浓淡仍由既有的 `alphaFraction` 管。
     */
    private fun clampPlateToInkSide(plateRgb: Int, presetArgb: Int): Int {
        val preset = 0xFF000000.toInt() or (presetArgb and 0xFFFFFF)
        val presetRgb = preset and 0xFFFFFF
        val presetIsDarkSide = srgbLuminance(presetRgb) < PLATE_LUMA_CROSSOVER
        if ((srgbLuminance(plateRgb) < PLATE_LUMA_CROSSOVER) != presetIsDarkSide) return preset
        var plate = plateRgb
        var steps = 0
        while (
            (plateIsTooBrightForLightInk(plate, presetIsDarkSide) ||
                plateIsTooDarkForDarkInk(plate, presetIsDarkSide)) &&
            steps < MAX_PLATE_PUSH_STEPS
        ) {
            steps++
            plate = mixRgb(plate, if (presetIsDarkSide) 0x000000 else 0xFFFFFF)
        }
        return if (
            plateIsTooBrightForLightInk(plate, presetIsDarkSide) ||
            plateIsTooDarkForDarkInk(plate, presetIsDarkSide)
        ) {
            preset
        } else {
            0xFF000000.toInt() or plate
        }
    }

    /** 暗侧那一格：板亮过白字的 AA 天花板（[PLATE_LIGHT_INK_MAX_LUMA]）就该再压暗 */
    private fun plateIsTooBrightForLightInk(rgb: Int, presetIsDarkSide: Boolean): Boolean =
        presetIsDarkSide && srgbLuminance(rgb) > PLATE_LIGHT_INK_MAX_LUMA

    /** 亮侧那一格：板暗过黑字的 AA 地板（[PLATE_DARK_INK_MIN_LUMA]）就该再提亮 */
    private fun plateIsTooDarkForDarkInk(rgb: Int, presetIsDarkSide: Boolean): Boolean =
        !presetIsDarkSide && srgbLuminance(rgb) < PLATE_DARK_INK_MIN_LUMA

    /** 逐通道对分取中点（含 +1 的进位，让 0..255 的整数不系统性偏暗一格） */
    private fun mixRgb(left: Int, right: Int): Int {
        fun channel(shift: Int): Int =
            (((left ushr shift) and 0xFF) + ((right ushr shift) and 0xFF) + 1) / 2
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** 一通道向极端色压一档：`(c*3 + extreme)/4`，用于 hints 那一票（比第 4 步的对分轻一半） */
    private fun nudgeChannel(channelValue: Int, extreme: Int): Int =
        (channelValue * 3 + extreme) / 4

    /** [nudgeChannel] 的三通道版：只用于 hints 那一票，所以不并给第 4 步那条对分循环用 */
    private fun nudgeRgb(rgb: Int, extremeRgb: Int): Int {
        fun channel(shift: Int): Int =
            nudgeChannel((rgb ushr shift) and 0xFF, (extremeRgb ushr shift) and 0xFF) shl shift
        return channel(16) or channel(8) or channel(0)
    }

    /**
     * sRGB 相对亮度（WCAG 定义）：逐通道线性化后按 0.2126 / 0.7152 / 0.0722 加权。
     *
     * 与 App 内 `Color.readableLuminance()` 同一条式子 —— 那条链用的是 Compose 的
     * `Color`，这里要的是一枚 Int，而**位运算手写、不 import android 类型**是这条
     * 边界存在的理由（同 [glassTintArgb]）。两边同式子，所以这里算出来的亮度可以直接
     * 与 `contentOnLuma` 那一条链对照，不必担心两套口径漂开。
     */
    fun srgbLuminance(rgb: Int): Float {
        fun linear(shift: Int): Float {
            val c = ((rgb ushr shift) and 0xFF) / 255f
            return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        return 0.2126f * linear(16) + 0.7152f * linear(8) + 0.0722f * linear(0)
    }

    /** 两块亮度之间的 WCAG 对比度：(亮 + 0.05) / (暗 + 0.05)，与 App 内 `contrastRatio` 同一条式子 */
    fun paletteContrastRatio(first: Float, second: Float): Float {
        val lighter = maxOf(first, second)
        val darkerSide = minOf(first, second)
        return (lighter + 0.05f) / (darkerSide + 0.05f)
    }
}

private fun Float.pow(exponent: Float): Float {
    // 只用到 2.4 这一支指数（sRGB 线性化），所以不引 Double 往返：
    // 这里要的是一枚 Float，且这条判据的每一格都在 JVM 单测里钉，浮点路径短一分少一分漂移。
    // `ln` / `exp` 都取 Float 重载（返回值本就是 Float，多写一次 toFloat 只是个会被编译器
    // 点名的冗余转换），于是整条链一次都没进 Double。
    val log = kotlin.math.ln(this) * exponent
    return kotlin.math.exp(log)
}
