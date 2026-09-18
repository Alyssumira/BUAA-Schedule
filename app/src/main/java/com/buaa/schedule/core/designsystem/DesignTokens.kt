package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计 Token：圆角、间距、玻璃材质档位。
 * 业务页面不再各自传透明度/描边/模糊参数，统一从这里取。
 */
object DesignTokens {

    /** 手机悬浮底栏的栏体高度（LiquidBottomTabs containerHeight，悬浮让位共用这个口径） */
    val bottomBarHeight = 64.dp

    /** 页面大容器圆角 */
    val cornerPage = 24.dp

    /** 普通面板圆角 */
    val cornerPanel = 18.dp

    /** 课程格圆角 */
    val cornerCourse = 10.dp

    /**
     * 内联小胶囊圆角：顶栏「课次/时间」这类包着单行小字的块。
     *
     * 不复用 cornerCourse(10dp)：那档服务的是一整张课程卡（几十 dp 高），
     * 同一个半径落在 24dp 高的胶囊上会读成"药丸"，而这里的意图是"一块标签"。
     * 之前这里是裸 8.dp——V-13 立"圆角上刻度"规矩时漏掉的最后一处。
     */
    val cornerChip = 6.dp

    /** 胶囊控件圆角（50%） */
    val cornerPill = 50

    /**
     * 行距级微间距：多行小字之间的缝、胶囊里的上下内衬。
     *
     * 它不在下面那套版面刻度里，因为管的不是"两块内容隔多远"，而是
     * "同一块内容内部两行字会不会粘连"——再小下去降部就要互相咬住了。
     */
    val spaceMicro = 2.dp

    /** 间距刻度 */
    val spaceXS = 4.dp
    val spaceS = 8.dp
    val spaceM = 12.dp
    val spaceL = 16.dp
    val spaceXL = 24.dp

    /** 最小触控区域 */
    val minTouchTarget = 48.dp

    /**
     * 图标尺寸刻度。此前全站有 9 种尺寸，其中 21/22 与 13/14 各差 1dp 且毫无意图
     * （审查②V-13）。统一到 4 档，选档按"它在版面里承担什么角色"，不是按"原来是多少"。
     */
    /** 行内辅助、状态标记（原 13/14/16dp） */
    val iconSmall = 16.dp

    /** 行首图标、箭头、菜单项（原 18/20/21/22dp） */
    val iconMedium = 20.dp

    /** 主操作、导航（原 24/28dp） */
    val iconLarge = 24.dp

    /** 空态与首启的标题级图标（原 40dp） */
    val iconHero = 40.dp

    /**
     * 用户「卡片透明度」滑条 → 玻璃表面 alpha 倍率。
     *
     * 四类玻璃表面（面板 / 底栏 / FAB / 菜单）必须共用这一个口径：此前 FAB 与菜单的
     * 下限写死 0.5、面板是 0.18，同一根滑条拉到最左时面板明显变透、FAB 和菜单只动了
     * 一半幅度，而 `LiquidMenu` 的注释还写着"与 GlassSurface 口径一致"（审查②V-12）。
     *
     * 0.88 是滑条的默认值，"默认 = 1.0 倍"由此而来；1.25 封顶让滑到最右也不会实心。
     * 各表面在这之上仍可再加自己的上限（菜单要一直看得见底下的内容），
     * 但**下限不再各写一套**。
     */
    fun cardAlphaScale(userAlpha: Float): Float = (userAlpha / 0.88f).coerceIn(0.18f, 1.25f)

    /**
     * 周视图网格的几何基准。这几个值互相耦合，改一个要同时看另一堆：
     * 时间列 + 7 列 = 屏宽，行高 × 节次数 = 网格总高。
     */
    /** 左侧节次/小时列宽。与 7 列课程网格共享屏宽，调大必然挤压卡片宽度（曾按 48dp 与卡片 43dp 取舍过） */
    val weekTimeColumnWidth = 48.dp

    /**
     * 悬浮底栏的左右内缩。
     *
     * 底栏是 overlay，课表内容会一直延伸到它背后滚动 —— 内缩小于左侧时间列时，
     * 「08:00」那一列就永久压在玻璃底下（真机反馈：底栏左右收紧一点，别遮时间栏）。
     * 所以这个值必须以 [weekTimeColumnWidth] 为基准，而不是跟着页面边距走；
     * 胶囊自己还有 4dp 内边距，一并算进来。
     */
    val bottomBarHorizontalInset = weekTimeColumnWidth + spaceS

    /** 单节行的基准高度（用户缩放系数 `Personalization.weekRowScale` 乘在它上面） */
    val weekRowHeight = 64.dp

    /** 24 小时制下每小时的行高，同上受 weekRowScale 缩放 */
    val weekHourHeight = 56.dp

    /** 课间空档的展开换算：每这么多分钟撑出一个单节行高（见 WeekGridGeometry.findIntervalGap） */
    const val weekGapMinutesPerRow = 45f

    /** 连续段卡片再矮也保留这么多高度，否则两行文字会被裁成一条线 */
    val weekMinCardHeight = 18.dp

    /** 窄屏一屏放得下的天数：七天等宽挤到 48dp 以下时改成横向滚动，每屏露这么多天 */
    const val weekCompactVisibleDays = 5

    /** 日视图时间轴：每分钟占多高，与 [dayBlockTintAlpha] 一起决定时间块的可读性与触控高度 */
    val dayHeightPerMinute = 1.05.dp

    /** 日视图时间块的课程色底板浓度：低于这个值课程色会被面板灰吃掉 */
    const val dayBlockTintAlpha = 0.72f

    /** 首页 FAB 相对底栏的抬升量（悬浮底栏与 FAB 的让位几何，成对改） */
    val fabLift = 60.dp

    /**
     * 玻璃材质档位：0 关闭（大面板退化成实心卡片）/ 1 开启。
     *
     * 曾经的第三档「增强」(1.3x 强度) 已删除（审查②V-14）：设置页只暴露关闭/开启，
     * `Personalization.load()` 又把读到的档位夹回 0..1，用户无论如何到不了那一档，
     * 留着常量只会让人以为调 `glassIntensity` 能改变观感。
     * 要恢复这一档，得同时给设置页第三个选项并重新钉 [surfaceUsesGlass] 的语义。
     */
    const val GLASS_TIER_OFF = 0
    const val GLASS_TIER_STANDARD = 1

    /**
     * 某一玻璃档位下，该变体是否仍渲染真液态玻璃（AGSL 折射 + 模糊）。
     *
     * 关闭档保留小面积玻璃：顶栏 / 底栏 / 分段控件 / Chip / 提示条。
     * [GlassVariant.PANEL] 退化为普通卡片表面——设置页与导入页整屏都是
     * 逐条 item 的 PANEL（几十个 AGSL 表面），既是性能开销的主要来源，
     * 观感上也不如小面积玻璃通透，因此默认档（关闭）只留小玻璃。
     */
    fun surfaceUsesGlass(tier: Int, variant: GlassVariant): Boolean =
        tier >= GLASS_TIER_STANDARD || variant != GlassVariant.PANEL

    /** WCAG AA 正文对比度阈值 */
    const val WCAG_AA_RATIO = 4.5f

    /**
     * 占位值的淡墨浓度：「教室未定」「未设置」这类**没有真值**的文案走这一档，
     * 真值用全浓度正文。两者同屏时必须一眼分得开，否则占位串会被读成真实数据。
     *
     * 收成一个常量的原因：同一件事在日视图卡片和详情弹层里各写了一遍 0.55f，
     * 下一处占位会照着哪一个都说不清。
     */
    const val PLACEHOLDER_INK_ALPHA = 0.55f

    /** 玻璃底板 alpha 的绝对下限：再低就只剩高光/阴影在空气里，看不见"有一块板" */
    const val GLASS_HARD_MIN_ALPHA = 0.08f

    /**
     * 玻璃底板 tint 的 alpha **下限**：让"文字—玻璃—壁纸"三层仍满足 [WCAG_AA_RATIO]。
     *
     * 复合亮度按线性近似 `luma = surface * a + scene * (1 - a)`，解出满足对比度的最小 a：
     * 深色底板配浅色文字时复合必须**不亮于** `(text + .05)/R - .05`，
     * 浅色底板配深色文字时复合必须**不暗于** `(text + .05) * R - .05`。
     *
     * [sceneLuma] 传的是**最不利**的区域亮度（见 [SceneLuma] 的 darkest / brightest），
     * 不是平均值——一块玻璃底下同时压着亮斑和暗斑时平均值会严重低估风险。
     * 结果只被 [GLASS_HARD_MIN_ALPHA] 托底：场景本身够安全时玻璃就该真的透
     * （内置深色渐变下约 0.34，比原来写死的 0.55 通透一档），
     * 场景很亮时才被迫压实。此前那个按主题写死的下限两头都错。
     *
     * ## 上层有两个入口，别随手挑（审查①C-05）
     *
     * 两者**都收敛到本函数**，所以数值口径永远一致；区别只在返回形态：
     *
     * | 你手上有什么 | 用哪个 |
     * |---|---|
     * | 只有底色，**前景色还要一起定**（课程色卡片、日程时间块） | [legibleTintPlate] → `TintPlate` |
     * | 前景色**已定**（主题 `onSurface`），只要知道底板至少多实（[GlassSurface]、分段控件、底栏） | [legibilityAlphaFloor] → `Float` |
     *
     * 也就是说：`legibleTintPlate` 是"选字 + 压实"的完整流程（先试黑白色、再抬 alpha、
     * 最后才动底色），`legibilityAlphaFloor` 是它的第 2 步单独拿出来用。
     * 已经定了文字色还去调 `legibleTintPlate`，它会擅自替你换成黑或白。
     */
    fun glassAlphaFloor(
        surfaceLuma: Float,
        sceneLuma: Float,
        textLuma: Float,
        hardMin: Float = GLASS_HARD_MIN_ALPHA,
    ): Float {
        if (sceneLuma.isNaN() || surfaceLuma.isNaN() || textLuma.isNaN()) return hardMin
        val darkPlate = surfaceLuma < textLuma
        val limit = if (darkPlate) {
            (textLuma + 0.05f) / WCAG_AA_RATIO - 0.05f
        } else {
            (textLuma + 0.05f) * WCAG_AA_RATIO - 0.05f
        }
        val span = if (darkPlate) sceneLuma - surfaceLuma else surfaceLuma - sceneLuma
        // 底板与场景亮度几乎相同：alpha 怎么调都不影响结果，直接给绝对下限
        if (span <= 0.001f) return hardMin
        val gap = if (darkPlate) sceneLuma - limit else limit - sceneLuma
        return (gap / span).coerceIn(hardMin, 1f)
    }

    /**
     * 变体 → 液态玻璃材质。**这是变体映射的唯一真源**。
     *
     * 此前存在两套并行映射（`glassSpec()` 与 `LiquidGlassMaterial`），
     * 改一处不会同步，测试通过但行为不变。现在 [GlassSurface] 与测试都走这里。
     *
     * 档位不参与这里的取值（②V-14）：能到得了的档位只有开/关，
     * 关的是 [surfaceUsesGlass] 决定的"要不要走玻璃"，而不是把同一块玻璃调得更折。
     */
    fun glassMaterial(
        variant: GlassVariant,
        panelBlurDp: Float = Personalization.DEFAULT_PANEL_BLUR_DP,
    ): LiquidGlassMaterial = when (variant) {
        // 底部导航 / 顶栏更强调折射，基准强度高于其他变体
        GlassVariant.CHROME -> LiquidGlassMaterial.pill(CHROME_BASE_INTENSITY)
        GlassVariant.PANEL -> panelMaterial(panelBlurDp)
        GlassVariant.COMPACT -> LiquidGlassMaterial.pill()
        GlassVariant.ALERT -> LiquidGlassMaterial.dialog()
    }

    /**
     * 大面板（设置页 / 导入页那种整屏卡片）的材质：透明底板 + 高斯模糊，**不做折射**。
     *
     * 真机反馈「大块玻璃太多了有点丑」——丑的是 dialog 材质那套 lens：几十条 item
     * 每条一块透镜，边缘被折射拉出亮暗带，叠在一起就成了"一摞玻璃板"。这里把 lens
     * 关掉、tint 压到 pill 那一档，剩下的是纯磨砂：底下透什么就是什么，糊到什么程度交给用户
     * （[Personalization.panelBlurDp]）。
     *
     * 内外阴影同步减淡：它们原本是为"厚玻璃"雕立体感的，板子薄了还按原浓度画，
     * 等于给每张卡蒙一圈灰边。
     */
    fun panelMaterial(blurDp: Float): LiquidGlassMaterial = LiquidGlassMaterial.dialog().copy(
        blur = blurDp.coerceIn(
            Personalization.MIN_PANEL_BLUR_DP,
            Personalization.MAX_PANEL_BLUR_DP,
        ).dp,
        lensHeight = 0.dp,
        lensAmount = 0.dp,
        depthEffect = false,
        surfaceAlpha = PANEL_SURFACE_ALPHA,
        // vibrancy 会把采到的背景提亮增饱和。tint 还有 0.34 压着时它是"通透"的来源；
        // 底板压到 0.18 后它就直接透到文字底下了——跟着壁纸颜色晃，还不是磨砂该有的中性灰。
        useVibrancy = false,
        shadowAlpha = 0.09f,
        innerShadowAlpha = 0.05f,
        innerShadowRadius = 3.dp,
    )

    /**
     * 大面板 tint 的基准浓度：与 pill 同档，比 dialog 的 0.34 透一档。
     * 实际还要过 [glassAlphaFloor] 的对比度下限，所以这是"能多透就多透"的意图值。
     */
    const val PANEL_SURFACE_ALPHA = 0.18f

    /** CHROME 变体相对其它变体的折射/模糊强度倍率 */
    const val CHROME_BASE_INTENSITY = 1.3f

    /**
     * 底栏栏体的表面 alpha：过高会像不透明色条，0.20 让背景能透出来。
     * 此前是 MainActivity 调用点的裸值（审查 V-顶栏/底栏方言）。
     */
    const val CHROME_SURFACE_ALPHA = 0.20f

    /**
     * 全屏模态遮罩的压暗浓度（首次引导的覆盖层）。
     * 之前 HomeScreen 裸写 0.55f，而 SpocScan 的半透明遮罩另写一套——两层遮罩浓度不一。
     */
    const val SCRIM_ALPHA = 0.55f

    /**
     * 宽屏双栏断点。审查发现 600dp 以裸值出现 3 处（主导航分栏、首页周/日并排、
     * 引导页限宽），口径恰与 M3 WindowSizeClass 的 Medium 起点一致 —— 收敛成一个值，
     * 免得改断点时要满仓找数字。
     */
    val breakpointWide = 600.dp

    /** 底栏选中指示胶囊的高度（栏体是 [bottomBarHeight]，两者之差即四周可见的缝隙） */
    val bottomBarIndicatorHeight = 56.dp

    /** 宽屏布局左侧玻璃导航栏的栏宽 */
    val navRailWidth = 84.dp

    /** 对话框内滚动区的最大可见高度（更新日志、临近课表此前各写 240/260） */
    val dialogListMaxHeight = 240.dp

    /**
     * 顶栏行基准高：玻璃顶栏与引导页头部行共用。
     * 不能低于 48dp —— 栏体高度就是内部 IconButton/TextButton 触控目标的上限，
     * 更矮会把 Material 默认的最小可点区域压掉一圈（R5 F-49）。
     * 引导页此前裸写固定 56dp，比其它页顶栏厚出一截。
     */
    val topBarHeight = 48.dp
}

/**
 * 手机悬浮底栏让出的底部滚动空隙：
 * 栏体高度 + 上下 spaceS 留白 + 一点呼吸空间 + 系统导航栏高度。
 *
 * 悬浮玻璃底栏用 overlay Box 而不是 Scaffold 排布：内容要延伸到底栏背后滚动，
 * 因此一级页面的滚动容器需要自己加上这段 clearance，保证最后一项能滚出底栏区域。
 */
@Composable
fun floatingBottomBarClearance(): Dp =
    DesignTokens.bottomBarHeight + DesignTokens.spaceS * 3 +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
