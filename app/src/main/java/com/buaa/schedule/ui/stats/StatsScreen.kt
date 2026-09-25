package com.buaa.schedule.ui.stats

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.CourseWeekGantt
import com.buaa.schedule.core.designsystem.DayLoadBars
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.EmptyState
import com.buaa.schedule.core.designsystem.GanttRow
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.HeatGridDay
import com.buaa.schedule.core.designsystem.LocalSemanticPlate
import com.buaa.schedule.core.designsystem.MiniBar
import com.buaa.schedule.core.designsystem.ModalTransition
import com.buaa.schedule.core.designsystem.SectionHeader
import com.buaa.schedule.core.designsystem.WeekFreeHeatGrid
import com.buaa.schedule.core.designsystem.WeeklyLoadTrendChart
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.core.designsystem.coursePlateSceneLuma
import com.buaa.schedule.core.designsystem.legibleTintPlate
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.formatCredit
import com.buaa.schedule.domain.model.formatCreditTotal
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import com.buaa.schedule.domain.schedule.CourseWeekSpans
import com.buaa.schedule.domain.schedule.SemesterStats
import com.buaa.schedule.domain.schedule.WeekDaySchedule
import com.buaa.schedule.domain.schedule.WeekFreeGrid
import com.buaa.schedule.domain.schedule.WeeklyLoadTrend
import com.buaa.schedule.ui.ScheduleViewModel
import com.buaa.schedule.ui.home.ConflictWizardDialog
import com.buaa.schedule.ui.home.ScheduleHeaderBand
import com.buaa.schedule.ui.home.applyConflictShift
import com.buaa.schedule.ui.home.conflictCourseLine

/**
 * 学期统计页。
 *
 * 这一页回答的是"这学期我到底要上多少课"——学分总数、哪天最忙、哪几格是空的、
 * 每门课各占多少学分。此前这些量一个都没有露出来过：ViewModel 早就算好了
 * `currentWeek / totalWeeks`，界面上却只有一行"第 N 周"。
 *
 * 数字一律来自 [SemesterStats.summarize]（纯 Kotlin，已在 JVM 单测里钉住口径），
 * 本页只负责画与说，不负责算——尤其**不在这里对片段求和算学分**：
 * 一条 Course 只是排课片段，学分开在整门课上，归并口径见 SemesterStats 文件头。
 * T51 的三张增密图同理：判据在 [CourseWeekSpans] / [WeekFreeGrid] / [WeeklyLoadTrend]
 * 三个内核里算完，本页只把结果映射成图形入参。
 *
 * T74 起这一页**先判档再开口**：加载中 / 真的空 / 有内容三档由 [statsPageStageOf] 定，
 * 就绪之前只画 [StatsLoadingCard] 那一面——"还没有课程可统计"是一句断言，
 * 没读到东西的时候没有资格说它（台账 #115）。
 *
 * T75 起 `viewModel` 是**必传**参数、吃 MainActivity 那枚 Activity 作用域的 VM：
 * 改前的默认参数按 nav entry 的 ViewModelStore 取 VM，于是每次进入这一页都新造一枚，
 * `uiState` 从 `stateIn` 的 `initialValue` 重走一遍加载链（台账 #116）。默认值会让
 * 调用点"忘了接线"静默通过，这条口径同 T69 对 `onOpenStats` 收的口。
 * 复用之后 Loading 那一档在热路径上基本看不见，但**那一档留着**——它是进程被杀后重建、
 * 磁盘慢时唯一不说"没有课"的防线。
 *
 * T80 起这一页**不再自带页头**：页头条换成与首页第一行同一个容器
 * [com.buaa.schedule.ui.home.ScheduleHeaderBand]，板上画的是「返回 + 学期统计」。
 * 改前这里是 `Scaffold(topBar = GlassTopBar(...))`，那条玻璃板与首页裸文字带的上下沿
 * 差 1px、衬底也不同，而 `"stats"` 走 `NavMotion.SLIDE` 的 260ms 里两页同时在场 ——
 * 同一块板上摆出两套文字，用户读作"页头文字跳动""跳转前后割裂"。
 * 页头也不再挂进 `Scaffold` 的 topBar 槽：装机量下来那个槽自己排了一次边距，
 * 同一枚容器在里面的 children 落在 y=147 而首页那一行在 y=158（见函数体那段注释）。
 *
 * T82 补的是这一页剩下的两块——「算好了没说」与「说不清的那半句」：
 * 1. **[ConflictCard]**：`state.conflicts` 一直在 ViewModel 里（首页那颗横幅就是吃它的），
 *    这一页一个字没提。归并走 `CourseConflictResolution.groupConflicts`，处置入口挂的是
 *    首页那枚 [com.buaa.schedule.ui.home.ConflictWizardDialog] 本体加同一份
 *    [com.buaa.schedule.ui.home.applyConflictShift] —— 这一页既不产第二套冲突判据，
 *    也不画第二套处置 UI，两页念的是同一个组数；
 * 2. **[WeekDrillCard]**：「每周负载」说的是全学期平均与并集，[WeekFreeGrid] 说的是格子，
 *    谁都没回答"那一周、那一天到底排了哪几门课、第几节到第几节、在哪个教室"。
 *    判据在 [WeekDaySchedule]，与 T51 那三件共用同一个分母 [SemesterStats.weekAxisLength]。
 *
 * 两块加进来会让折下更多，所以同一次改动里做了一件排布调整：**「课程学分」那一列移到列尾**
 * （它实测 3183px 高，比折下那三块的总和还多），钻取默认收起。内容一块都没删、
 * 一句都没省 —— 改前/改后的折叠账见 [WeekDrillCard] 里那段注释。
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScheduleViewModel,
    /**
     * 这一页此刻是不是页头条的主人（T80，口径同 [com.buaa.schedule.ui.home.HomeScreen]）。
     *
     * ⚠️ **不许给默认值**：这一枚的默认值买不到"少一行参数"，买的是跳转期间两页页头
     * 同时在场 —— 那正是本卡要消除的"页头文字跳动"。主人由 `AppNavHost` 按当前路由
     * 答一次（[com.buaa.schedule.ui.home.headerBandOwnerOf]），两个调用点各判各的迟早走岔。
     */
    headerBandOnScreen: Boolean,
    /**
     * 首页那条带量到的实高（px，null = 首页还没量到过），本页的带按它**托底**（T80-C）。
     *
     * ⚠️ **不许给默认值**：默认 null = 这一页永远按 48dp 下限排，而首页那一支里立着分段控件、
     * 它那层玻璃衬里（COMPACT 上下各 4dp）把带子垫到 148px —— 两页的带差 22px，
     * 带里的可点件被居中之后差 11px：用户读作"跳进统计页页头往上跳一格"。
     * 这一枚是设备侧事实（实高），按仓库口径由调用点（`AppNavHost`，那里同时看得到两页）
     * 当参数传进来；取的是"下限"而不是"照抄"，所以字号调到极大、这一支自己长高时不会被裁。
     */
    headerBandReferenceHeightPx: Int?,
) {
    val state by viewModel.uiState.collectAsState()
    val summary = remember(state.courses, state.semester, state.timeSlots) {
        SemesterStats.summarize(state.courses, state.semester, state.timeSlots)
    }
    // 三态判定在纯 JVM 内核里（[statsPageStageOf]），调用点只递两件事：
    // 就绪与否 = `uiState.loading` 取反（改前首帧吃的是 stateIn 的 initialValue，那里 loading=true；
    // T75 复用 Activity 那枚 VM 之后首帧一般已是热值，这一档只剩进程重建与磁盘慢时会亮），
    // 条数 = 归并到整门课之后的 courseCount（18 门课在库里是 22 段，用片段数会把档走对、数说错）。
    // 改前这一档只看 `summary.courseCount == 0`，于是"这一页还没读到"被说成了"你一门课都没有"。
    val stage = remember(state.loading, summary.courseCount) {
        statsPageStageOf(ready = !state.loading, courseCount = summary.courseCount)
    }
    // T51 三件套的判据内核：memoize 键与 summary 同一口径（courses/semester 必带，
    // 吃节次表的再带 timeSlots），外加各内核自己声明的设备事实 currentWeek ——
    // 全学期重算只在这几个输入真的变了一次时发生，不在组合期反复跑
    val ganttBoard = remember(state.courses, state.semester, state.currentWeek) {
        CourseWeekSpans.board(state.courses, state.semester, state.currentWeek)
    }
    val freeGrid = remember(state.courses, state.semester, state.timeSlots, state.currentWeek) {
        WeekFreeGrid.gridOf(state.courses, state.semester, state.timeSlots, state.currentWeek)
    }
    val loadTrend = remember(state.courses, state.semester, state.timeSlots, state.currentWeek) {
        WeeklyLoadTrend.trendOf(state.courses, state.semester, state.timeSlots, state.currentWeek)
    }
    // 这一页此前一个字都没提"是哪一学期"：termName 一直在 state 里，
    // 但它在两条写入路径上都会退化成学期代码或占位名，所以措辞由内核判（见文件内注释）
    val semesterTitle = remember(state.semester) {
        semesterTitleOf(state.semester?.termName, state.semester?.termCode)
    }
    // 冲突：判据在 ConflictDetector（哪两两撞）与 groupConflicts（把两两撞归并成组）那两份里，
    // 这一页**一次都不许多算**。归并结果同时喂给卡片与首页那一枚向导，两处看到同一批组。
    // state.conflicts 是两两配对的条数：三门课互撞是 3 条配对、1 组，所以卡片上那句「几组」
    // 读的是这一枚 groups 的 size —— 与首页横幅、与点开向导看到的行数同一个数。
    val conflictGroups = remember(state.conflicts) {
        CourseConflictResolution.groupConflicts(state.conflicts)
    }
    var showConflictWizard by rememberSaveable { mutableStateOf(false) }
    // 柱状图吃的是 7 项平均分钟数；busiest 是 ISO 星期序号（1 = 周一），下标要退一格
    val dayMinutes = remember(summary) { summary.dayLoads.map { it.averageMinutes } }
    val busiestIndex = remember(summary) { summary.busiestDayOfWeek?.minus(1) }
    val maxCredit = remember(summary) { summary.perCourse.mapNotNull { it.credit }.maxOrNull() ?: 0.0 }

    // 页头条自己的实高（T80）：不在台上的那一页按它占位，带子保持同样的高度、板上不画字。
    // 与首页那一行各自量各自的那一帧，共用一个常数就是"拿一枚没量过的宽度向同行要地方"的
    // T48 老账换个维度重演。⚠️ 这一枚只服务**占位**：它已经被下面的参照托过一次底，
    // 再把它喂回首页当参照就成了只涨不落的棘轮（T80-C 的账），所以它到此为止。
    var statsBandHeightPx by remember { mutableStateOf<Int?>(null) }

    // 这一页不再用 Scaffold（T80）。装机量下来：同一枚 ScheduleHeaderBand 挂进 Scaffold 的
    // topBar 槽时，板上的 children 落在 y=147..273，而首页那一行落在 y=158..284 ——
    // 那 11px 就是"跳转期间页头上下沿在动"的实底，与两页各自写内衬是同一类账。
    // 改成"Column 的第一个子节点"之后，两页的带由同一个容器的同一份内衬摆位，
    // 槽差从结构上消失，而不是靠这里再补一层 padding 把它抹平（补的那一层下次换壳又会漏）。
    //
    // T82 在这同一个 Column 的**末尾**挂了冲突向导那一格：根 Column 没有 spacedBy，
    // 而它前面的 Crossfade 已经按 fillMaxSize 把剩余高度吃满，所以那一个不占布局的
    // AlertDialog 节点排进来是 0 高 —— 不会像挂进正文那列（每两张卡之间 spaceM）那样，
    // 弹窗开着的每一帧都给页面底下多撑出一截空隙来。
    Column(modifier = modifier.fillMaxSize()) {
        // 与首页第一行同一个容器（T80）：带的内衬、下限高同一个来源 ⇒ 跳转期间那条带的
        // 上下沿像素位置一动不动，而板上永远只有一套字（谁在台上由 headerBandOwnerOf 答）。
        // T80-C 补的那一半：下限还要吃首页那一支量到的实高（referenceHeightPx）——
        // 首页那一支里立着分段控件，它那层玻璃衬里比这里最高的「返回」(48dp) 多出一截，
        // 不托这一下，两页的可点件就差 11px（装机：首页 158..284 / 统计页 147..273）。
        ScheduleHeaderBand(
            drawnOnScreen = headerBandOnScreen,
            measuredHeightPx = statsBandHeightPx,
            referenceHeightPx = headerBandReferenceHeightPx,
            modifier = Modifier.onSizeChanged { statsBandHeightPx = it.height },
        ) {
            // ⚠️ 「返回」保持文字按钮 + `Text("返回")` 这个字面量：它是 Baseline Profile
            // 交互 CUJ 的 uiautomator 锚点（InteractionBaselineProfileGenerator.LABEL_BACK），
            // 换成箭头图标那条跳不会红，只会静默点空。
            TextButton(
                onClick = onBack,
                modifier = Modifier.defaultMinSize(minHeight = DesignTokens.minTouchTarget),
            ) { Text("返回") }
            Text(
                text = "学期统计",
                // 页头主名要压过页内 SectionHeader 的 titleSmall，否则标题与组标题同档（§3）
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // 导入第一门课后这一页整版换血，硬切像重开了一遍；淡入淡出与日视图空态同源。
        // T74：targetState 从"空不空"两档换成三档——加载中 → 有内容那一跳同样要淡入，
        // 动画规格仍是 motionSpec（reduce-motion 下 snap 成硬切，不另起一炉）
        Crossfade(
            targetState = stage,
            animationSpec = motionSpec<Float>(),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = DesignTokens.spaceL),
        ) { current ->
            when (current) {
                StatsPageStage.Loading -> CenteredStatsCard { StatsLoadingCard() }
                StatsPageStage.Empty -> CenteredStatsCard { EmptyStatsCard() }
                StatsPageStage.Ready -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
                ) {
                    CreditHeadline(summary, semesterTitle)
                    DayLoadCard(dayMinutes, busiestIndex, summary)
                    // 钻取紧跟「每周负载」：那一块说的是全学期平均，这一块说的是某一週。
                    // 默认收起（装机量的账见 WeekDrillCard 的注释），展开才占地方
                    WeekDrillCard(
                        courses = state.courses,
                        semester = state.semester,
                        timeSlots = state.timeSlots,
                        totalWeeks = summary.weekCount,
                        currentWeek = state.currentWeek,
                        weeksUnknownCount = ganttBoard.unknownCount,
                    )
                    // 冲突卡在首屏内：这件事系统早就算完了，只差一句给人听的话
                    ConflictCard(groups = conflictGroups, timeSlots = state.timeSlots) {
                        showConflictWizard = true
                    }
                    // 趋势紧跟「每周负载」：那张是全学期平均、这张是按周摊开，同一个问题的两半
                    LoadTrendCard(loadTrend)
                    WeekCoverageCard(ganttBoard)
                    FreeSlotsCard(summary)
                    // 逐周的热力格紧跟全学期并集口径的空档卡，两张对着读才知道"这周真空没空"
                    FreeSlotsGridCard(freeGrid)
                    // 「课程学分」那一列挪到了最后（T82，内容一个字没删）。理由是装机的账：
                    // 这一列 18 门课实测占 3183px，是折下那三块的总和还多，摆在第四格
                    // 就等于把后面所有块都推到两屏之外。它又是全页唯一"要往下找某一门课"
                    // 才会去读的一块 —— 首屏该留给一眼能扫完的那几块。
                    CreditListCard(summary.perCourse, maxCredit)
                    Spacer(modifier = Modifier.height(DesignTokens.spaceXL))
                }
            }
        }
        // 处置入口**复用**首页那一枚向导本体（同一个 composable、同一份落库回调），
        // 统计页不重画第二套处置 UI：那套 UI 里"移到第几节"的判据、"只改这些周"的写法、
        // 重复位移的保护全都得再来一遍，就是四件能各自算错的事。
        // 挂在根 Column 末尾而不是正文那一列：见上面那段 0 高的说明。
        ModalTransition(open = showConflictWizard) { modal ->
            ConflictWizardDialog(
                groups = conflictGroups,
                allCourses = state.courses,
                timeSlots = state.timeSlots,
                modifier = modal,
                onApplyShift = { target, newPeriods -> applyConflictShift(viewModel, target, newPeriods) },
                onDismiss = { showConflictWizard = false },
            )
        }
    }
}

/** 一门课都没有时的说明卡：统计页不能只给一片空白，否则看不出是"没课"还是"算不出来" */
@Composable
private fun EmptyStatsCard() {
    EmptyState(
        icon = Icons.Filled.Insights,
        title = "还没有课程可统计",
        description = "先在首页导入或添加一门课，这里就会给出学分、每周负载和空档。",
    )
}

/**
 * 课表还没读到时的那一档：只说"正在读"，不说"没有"。
 *
 * 台账 #115 的假话就出在这一档缺席 —— 改前这一页的 `ScheduleViewModel` 是 `"stats"` 那条路由
 * 自己新造的一枚，`uiState` 起步于 `stateIn` 的 `initialValue`（`courses` 空、`loading` 真），
 * 而那一档分支把"这枚 VM 还没查到东西"直接当成了"你一门课都没有"。
 *
 * 文案两行、外壳 [EmptyState] 那一枚玻璃卡，与 [EmptyStatsCard] 同一形状同一槽位
 * （见 [CenteredStatsCard]）：**不新增一行高度**，也不给这一页添动画——
 * 全站 main 源码里没有任何一处 `rememberInfiniteTransition`，本卡不在此开第一例，
 * 一枚转不完的圈比一屏静止更容易被读成"卡死了"。
 *
 * T75 复用 Activity 那枚 VM 之后，热路径上这一档不再出现（装机探针：改前 12 次进入 12 次都先发射
 * `Loading` 再转 `Ready`、改后 12 次进入只发射 `Ready`；录屏逐帧分类另给一条弱证据：改前 49 帧里
 * 10 帧是这一面、改后 36 帧 0 帧——受帧粒度所限，后一半只当负观测看），但**这一面留着**：
 * 进程被杀后重建、磁盘慢时它是唯一还说得出口的那句话。T74 那次量到的冷路径是 3080ms
 * （CPU 饥饿的模拟器，从这一页第一帧到第一次拿到数据）。
 */
@Composable
private fun StatsLoadingCard() {
    EmptyState(
        icon = Icons.Filled.Insights,
        title = "正在读取本学期课表",
        description = "学分、每周负载和空档要等课表数据到位才算得出来。",
    )
}

/**
 * 加载中与真的空共用同一个居中槽位（与管理页同一口径）：贴在页顶会让这一页看起来"还没加载完"。
 *
 * 两档换面不换位：[StatsLoadingCard] 与 [EmptyStatsCard] 落在同一个居中 Box 里，
 * 从"正在读取"淡入到"还没有课程可统计"时卡片不会跳一下位置。
 */
@Composable
private fun CenteredStatsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 总学分：页面上唯一一个大字号，其余卡片都不该和它抢 */
@Composable
private fun CreditHeadline(
    summary: SemesterStats.SemesterSummary,
    title: SemesterTitle?,
) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceXS)) {
            // 「这是哪一学期」：学期名判据见 semesterTitleOf（两个降级值不当学期名吹）。
            // 放在最前面是因为它限定的是下面所有数字——没有这一行，"本学期总学分"的
            // "本学期"要靠这一页之外的记忆来补（切了学期而这一页还是同一串数字时最危险）。
            title?.let {
                Text(
                    text = when (it.kind) {
                        SemesterTitleKind.Named -> it.text
                        SemesterTitleKind.CodeOnly -> "学期代码 ${it.text}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "本学期总学分",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // 合计走 formatCreditTotal 而不是 formatCredit：100 是"一门课"的量程，
                // 多门课相加超它的学期是真的，判成 null 会让这枚大字号空着
                text = formatCreditTotal(summary.totalCredits),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = listOfNotNull(
                    "${summary.courseCount} 门课 · ${summary.fragmentCount} 段排课",
                    "共 ${summary.weekCount} 周",
                    // 缺学分的那几门必须当场说出来，否则用户会把偏小的总数当真相
                    if (summary.creditsMissing > 0) {
                        "${summary.creditsMissing} 门课没有学分数据，未计入"
                    } else {
                        null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一周负载 */
@Composable
private fun DayLoadCard(
    dayMinutes: List<Long>,
    busiestIndex: Int?,
    summary: SemesterStats.SemesterSummary,
) {
    val busiest = summary.busiestDayOfWeek?.let { iso -> summary.dayLoads.getOrNull(iso - 1) }
    val quietest = summary.quietestBusyDay?.let { iso -> summary.dayLoads.getOrNull(iso - 1) }
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
            SectionHeader("每周负载")
            DayLoadBars(
                averageMinutes = dayMinutes,
                busiestDayIndex = busiestIndex,
            )
            busiest?.let {
                Text(
                    text = listOfNotNull(
                        "最忙的是周${weekdayChar(it.dayOfWeek)}，平均每周 ${humanMinutes(it.averageMinutes)}",
                        quietest?.takeIf { q -> q.dayOfWeek != it.dayOfWeek }
                            ?.let { q -> "最轻的是周${weekdayChar(q.dayOfWeek)}" },
                    ).joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 每日课次数（T81）：口径钉在 DayLoad.courseCount 上 —— 那是**全学期并集、
            // 按门去重、不分具体哪一周**的门数（SemesterStats.kt:207 的 groupsByDay）。
            // ⚠️ 别与 WeekFreeGrid.Grid.occupiedByDay 混用：那一枚是"这一周这天占了几节"，
            // 两个数在同一天上天生不等（一门 1-8 周的课并集里算 1 门、第 12 周算 0 节），
            // 所以这句话必须自带"这学期里"与"不分周次"两个限定，否则装机一定被读成数不对。
            val courseCounts = summary.dayLoads.filter { it.courseCount > 0 }
            if (courseCounts.isNotEmpty()) {
                Text(
                    text = "这学期里，" + courseCounts.mapIndexed { index, load ->
                        val day = "周${weekdayChar(load.dayOfWeek)}"
                        if (index == 0) "${day}有 ${load.courseCount} 门不同的课" else "$day ${load.courseCount} 门"
                    }.joinToString("、") +
                        "；同一门课在同一天排成几段也只算一门，这里不分具体哪一周",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 课程色圆点：行内的颜色标记，不是图标——图标刻度最小档 [DesignTokens.iconSmall] 落在正文行里偏重 */
private val CourseDotSize = 10.dp

/**
 * 冲突卡（T82）。
 *
 * 这一块的全部内容由 [CourseConflictResolution.groupConflicts] 的归并结果决定，
 * 页面自己**一次都不算**：`state.conflicts` 是 ViewModel 里 `ConflictDetector.findConflicts`
 * 的产物（ScheduleViewModel.kt:453），再判一次"谁和谁撞"就是第二套真相。
 * 逐条课程的「• 课名（节次，教室）」也直接吃首页向导那件 [conflictCourseLine]，
 * 两页念同一句措辞。
 *
 * 没有冲突时这一整块**不隐藏**：卡头照常出、话说"这学期没有撞课的时段"。
 * 把"没事"表达成"这一栏不在"，用户下一次导入完课表就找不到它去哪了。
 *
 * 卡的尺寸是算过的：组内最多摆 [MAX_INLINE_CONFLICT_GROUPS] 组、每组最多三行课，
 * 其余的一句"还有 N 组"交给按钮后面那一屏（同一枚向导），这样这张卡在真实数据上
 * 稳定占五行以内 —— 折叠账（见 [WeekDrillCard] 那段）不容许它往上顶成一整屏。
 */
@Composable
private fun ConflictCard(
    groups: List<CourseConflictResolution.ConflictGroup>,
    timeSlots: List<TimeSlot>,
    onOpenWizard: () -> Unit,
) {
    val hasConflict = groups.isNotEmpty()
    GlassSurface(
        // 有冲突才穿 ALERT：那一档的底板与文字由 GlassSurface 配对（T23 的账），
        // "没有撞课"是好消息，披一层 error 红是在吓唬人
        variant = if (hasConflict) GlassVariant.ALERT else GlassVariant.PANEL,
        semanticTint = if (hasConflict) MaterialTheme.colorScheme.error else null,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            SectionHeader("课程冲突")
            Text(
                text = conflictHeadlineNote(groups.size),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalSemanticPlate.current?.foreground
                    ?: MaterialTheme.colorScheme.onSurface,
            )
            groups.take(MAX_INLINE_CONFLICT_GROUPS).forEach { group ->
                Column(modifier = Modifier.padding(top = DesignTokens.spaceXS)) {
                    Text(
                        text = listOfNotNull(
                            conflictGroupTitle(group),
                            sharedPeriodNote(group, timeSlots),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    inlineConflictCourses(group).forEach { course ->
                        Text(
                            text = conflictCourseLine(course, timeSlots),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalSemanticPlate.current?.foreground
                                ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    hiddenCourseNote(group)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalSemanticPlate.current?.foreground
                                ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            hiddenGroupNote(groups.size - MAX_INLINE_CONFLICT_GROUPS)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSemanticPlate.current?.foreground
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 处置入口：按钮开的是首页那一枚向导本体（同一个 composable、同一份落库回调）。
            // 没有冲突时这颗按钮不出场 —— 点开一个只会说"当前没有冲突了"的弹窗是骗一次点击。
            if (hasConflict) {
                TextButton(
                    onClick = onOpenWizard,
                    modifier = Modifier.defaultMinSize(minHeight = DesignTokens.minTouchTarget),
                ) { Text("按建议处理") }
            }
        }
    }
}

/** 整周视图里一天最多摆几堂课：一周看 7 天，每天再铺十几行就没人能扫完 */
private const val MAX_MEETINGS_IN_WEEK_VIEW = 3

/**
 * 「按周细看」：从全学期平均钻到某一週、某一周几的实排（T82）。
 *
 * ## 为什么默认收起
 *
 * 装机量的账（这一页 2400px 视口、18 门课的种子数据）：改前第一屏切在「课程学分」
 * 第 2 门课上，「周次覆盖」「空档」「空档分布」三块整块在折下。往那一列末尾再叠两块
 * 只会让"内容太少"变成"内容更多但更看不见"，所以：
 * 1. 这一块**收起时只占两行**（卡头 + 一句提示），展开才把周次胶囊与明细铺出来；
 * 2. 全页最长的那一块「课程学分」（实测 3183px）移到列尾，让上面这几块能进首屏。
 *
 * ## 选中态为什么不许惊动整页
 *
 * 三枚状态（expanded / selectedWeek / selectedDay）全部**活在这个 composable 里面**：
 * 点一下胶囊只重组这一块，[StatsScreen] 那几枚重算（summary / ganttBoard / freeGrid /
 * loadTrend）的 memoize 键里根本没有选中值，一次都不重跑。
 * 明细本身走 [WeekDaySchedule.scheduleOf]，包在 `remember(courses, semester, timeSlots, week)`
 * 里 —— 换一周只重算那一周那一条列表，全学期那张表不重来（T75 复用 VM 的收益不该被
 * "进页面重算全学期"吃回去）。
 */
@Composable
private fun WeekDrillCard(
    courses: List<Course>,
    semester: Semester?,
    timeSlots: List<TimeSlot>,
    totalWeeks: Int,
    currentWeek: Int?,
    weeksUnknownCount: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedWeek by remember { mutableStateOf<Int?>(null) }
    // null = 整周；1..7 = 只看那一天（ISO 星期序号，与内核算的是同一个数）
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    val schedule = remember(courses, semester, timeSlots, selectedWeek) {
        selectedWeek?.let { WeekDaySchedule.scheduleOf(courses, semester, timeSlots, it) }
    }
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            // 卡头那一行整行可点：收起时它是入口，展开时它是出口（Role.Button 念得出"按钮"）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        if (expanded) {
                            expanded = false
                        } else {
                            expanded = true
                            // 第一次展开落在当前周上；没有原点（假期 / 学期没锚定）就落第 1 周。
                            // 这是"用户点开想看的那一周"，不是内核去猜的今天
                            if (selectedWeek == null) {
                                selectedWeek = (currentWeek ?: 1).coerceIn(1, totalWeeks.coerceAtLeast(1))
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("按周细看", modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(DesignTokens.iconSmall),
                )
            }
            if (!expanded) {
                Text(
                    text = drillCollapsedHint(currentWeek),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 周次缺失的那几门在**任何**一周都看不到它们：这句话收起时就得说，
                // 否则用户点开了还以为这一页漏了课
                weeksUnknownNote(weeksUnknownCount)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                ) {
                    (1..totalWeeks).forEach { week ->
                        DrillChip(
                            label = "第 $week 周",
                            selected = selectedWeek == week,
                            onClick = { selectedWeek = week },
                        )
                    }
                }
                if (selectedWeek != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                    ) {
                        DrillChip(
                            label = "整周",
                            selected = selectedDay == null,
                            onClick = { selectedDay = null },
                        )
                        (1..SemesterStats.TOTAL_DAYS).forEach { day ->
                            DrillChip(
                                label = "周${weekdayChar(day)}",
                                selected = selectedDay == day,
                                onClick = { selectedDay = day },
                            )
                        }
                    }
                }
                schedule?.let { week ->
                    drillEmptyNote(week)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!week.outOfRange) {
                        Text(
                            text = drillWeekNote(week),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val days = selectedDay?.let { day -> listOfNotNull(week.days.getOrNull(day - 1)) }
                            ?: week.days.filter { it.meetings.isNotEmpty() }
                        days.forEach { day ->
                            if (day.meetings.isEmpty()) {
                                // 点了没课的那一天：这句得说，画一片空白会被读成"没渲染出来"
                                Text(
                                    text = drillDayEmptyNote(day, week.week),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                return@forEach
                            }
                            Column(
                                modifier = Modifier.padding(top = DesignTokens.spaceXS),
                                verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceMicro),
                            ) {
                                Text(
                                    text = drillDayTitle(day),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                val shown = if (selectedDay == null) {
                                    day.meetings.take(MAX_MEETINGS_IN_WEEK_VIEW)
                                } else {
                                    day.meetings
                                }
                                shown.forEach { meeting ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(CourseDotSize)
                                                .clip(CircleShape)
                                                .background(courseColor(meeting.course)),
                                        )
                                        Spacer(modifier = Modifier.width(DesignTokens.spaceS))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = meeting.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                            // 缺项整段跳过：drillMeetingLine 空串时就是一行都不画
                                            drillMeetingLine(meeting, timeSlots).takeIf { it.isNotEmpty() }?.let {
                                                Text(
                                                    text = it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                                drillHiddenMeetingsNote(day, shown.size)?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                drillSameCourseNote(day)?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        drillMinutesUnknownNote(week.minutesUnknownCount)?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一枚可点的周次 / 星期胶囊：选中态实心 primary，未选 surfaceVariant，48dp 触摸下限 */
@Composable
private fun DrillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            // 下限要在 selectable 之前（口径同 GlassSegmentedControl）：写后面撑大的是内容区，
            // 点不到的那圈还是点不到
            .defaultMinSize(minHeight = DesignTokens.minTouchTarget)
            .clip(RoundedCornerShape(DesignTokens.cornerChip))
            .background(if (selected) scheme.primary else scheme.surfaceVariant)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = DesignTokens.spaceM, vertical = DesignTokens.spaceS),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            // 实心 primary 上必须换 onPrimary，否则就是 T23 那笔 1.39:1 的账重演
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 每门课的学分：名字 + 一行明细（教师/地点/校区/几段合并）+ 一条占比条，
 * 占比条用课程自己的颜色，和课表上的色块对得上。
 *
 * T81 起这一张卡做了三件事，都不新增任何数据源：
 * 1. **按学分降序**（domain 层没有排序字段，`perCourse` 是首次出现序，所以排序是这里的事）；
 * 2. **逐门课说清它由几段排课合并**（吃 `fragmentCount`，一段的不写）；
 * 3. **把片段级的教师/地点/校区露出来**（吃 `CourseCredit.fragments`，
 *    判据在 [courseRowNote]，这里不 groupBy）。
 */
@Composable
private fun CreditListCard(perCourse: List<SemesterStats.CourseCredit>, maxCredit: Double) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
            SectionHeader("课程学分")
            perCourse.sortedByCreditDesc().forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(CourseDotSize)
                            .clip(CircleShape)
                            .background(courseColor(item.course)),
                    )
                    Spacer(modifier = Modifier.width(DesignTokens.spaceS))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.course.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        // 明细行：全都没得说（无教师/教室/校区、只有一段）时整段不画，
                        // 与详情 Sheet 的「缺项整段跳过」同一条口径
                        courseRowNote(item.fragments)?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = DesignTokens.spaceMicro),
                            )
                        }
                        MiniBar(
                            fraction = if (maxCredit <= 0.0) 0f
                            else (item.credit ?: 0.0).toFloat() / maxCredit.toFloat(),
                            color = courseColor(item.course),
                            modifier = Modifier.padding(top = DesignTokens.spaceXS),
                        )
                    }
                    Spacer(modifier = Modifier.width(DesignTokens.spaceS))
                    // 学分的格式化只有一处（CourseMetaFormat）：改前这里私自带一份 trimCredits，
                    // 与 Sheet 各自演进迟早一条带 .0、另一条不带。
                    // null 的画法也与 Sheet 对齐：**没有就不画**，不再印 "—" ——
                    // "—" 会把「教务没给」画成"这门课的学分是某个说不出口的值"，
                    // 而 0 学分是真值、会照常画成 "0"（Course.kt:41 那条分界）。
                    formatCredit(item.credit)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            // 留空容易被读成"画坏了"，所以这一档要当场说一句（数字来自 perCourse，不另取源）
            val missing = perCourse.count { it.credit == null }
            if (missing > 0) {
                Text(
                    text = "$missing 门课没有学分数据：右侧数字与占比条留空，总学分里也没算它们",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 统计页那一列的排序：学分降序，**没有学分数据的那些整档沉底**。
 *
 * 为什么不排序：`SemesterStats.creditsByCourse` 交出来的是首次出现序（LinkedHashMap），
 * 那是"库里怎么来"的次序，对这一列没有意义 —— 用户扫这一列要的是"哪门课占的分量最大"。
 * 排序放在界面这一侧，domain 层不备第二份排序字段（归并口径只有一份那条承诺）。
 *
 * 两档判据分开写，而不是一个 `compareByDescending { it.credit }`：后者靠的是
 * `compareValues` 把 null 当最小这个隐式规矩，读代码的人看不出"不知道"与"0 分"
 * 谁在前。并列的课由稳定排序保持首次出现序，结果不随传入顺序抖动。
 */
internal fun List<SemesterStats.CourseCredit>.sortedByCreditDesc(): List<SemesterStats.CourseCredit> =
    sortedWith(
        compareByDescending<SemesterStats.CourseCredit> { it.credit != null }
            .thenByDescending { it.credit },
    )

/** 空档：全学期一节都不落课的格子数 */
@Composable
private fun FreeSlotsCard(summary: SemesterStats.SemesterSummary) {
    val fraction = if (summary.totalSlotCount <= 0) 0f else summary.freeSlotCount.toFloat() / summary.totalSlotCount
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            SectionHeader("空档")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${summary.freeSlotCount}",
                    // 总学分是这一页唯一的大字号（见 CreditHeadline），空档数只到标题档
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(DesignTokens.spaceXS))
                Text(
                    text = "/ ${summary.totalSlotCount} 格全学期无课",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = DesignTokens.spaceS),
                )
            }
            MiniBar(fraction = fraction, color = MaterialTheme.colorScheme.primary)
            val freeDays = summary.dayLoads.filter { it.isFree }
            if (freeDays.isNotEmpty()) {
                Text(
                    text = "整天没课：" + freeDays.joinToString("、") { "周${weekdayChar(it.dayOfWeek)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 负载趋势：每周上多少分钟、哪周最忙、从哪周开始塌下去（判据在 WeeklyLoadTrend 内核） */
@Composable
private fun LoadTrendCard(trend: WeeklyLoadTrend.Trend) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
            SectionHeader("负载趋势")
            WeeklyLoadTrendChart(
                minutes = trend.minutes,
                totalWeeks = trend.totalWeeks,
                currentWeek = trend.currentWeek,
                peakWeek = trend.peakWeek,
                endings = trend.endings,
                dataAbsent = !trend.hasAnyWeeksData,
            )
            if (!trend.isEmpty) {
                Text(
                    text = listOfNotNull(
                        trend.peakWeek?.let { "最忙的是第 $it 周，约 ${humanMinutes(trend.peakMinutes)}" },
                        "有课的周平均 ${humanMinutes(trend.averageMinutes)}",
                        trend.freeWeeks.size.takeIf { it > 0 }?.let { "还有 $it 周整周没课" },
                        // 节次时长查不到的格贡献 0 分钟：折线只会偏低，偏低这件事得当场说
                        trend.unschedulablePeriodCellCount.takeIf { it > 0 }
                            ?.let { "另有 $it 格查不到节次时长，这条线偏低" },
                    ).joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 周次覆盖：每门课上到哪一周、中间断不断（判据在 CourseWeekSpans 内核，一行 = 一门课） */
@Composable
private fun WeekCoverageCard(board: CourseWeekSpans.Board) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val rows = remember(board, darkTheme) {
        board.rows.map { coverage ->
            val tint = courseColor(coverage.course)
            GanttRow(
                label = coverage.label,
                // 课程色块走周视图/日视图同一条推导链（T23/T25b/T29 真机校准的账），不裸铺在玻璃板上
                color = legibleTintPlate(
                    tint,
                    DesignTokens.dayBlockTintAlpha,
                    coursePlateSceneLuma(tint, darkTheme),
                ).tint,
                spans = coverage.spans,
                weeksUnknown = coverage.weeksUnknown,
                finished = coverage.finished,
                endsAtWeek = coverage.lastWeek,
            )
        }
    }
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
            SectionHeader("周次覆盖")
            CourseWeekGantt(
                rows = rows,
                totalWeeks = board.totalWeeks,
                currentWeek = board.currentWeek,
            )
            val note = listOfNotNull(
                board.nextToEnding?.let { "下一门结课的是「${it.label}」，第 ${it.lastWeek} 周" },
                board.finishedCount.takeIf { it > 0 }?.let { "已有 $it 门课结课" },
                // 周次未知的行画的是虚线轨道，不是空轨道——文字要跟上这个区分
                board.unknownCount.takeIf { it > 0 }?.let { "另有 $it 门课没有周次数据，画成虚线" },
            ).joinToString("；")
            if (note.isNotEmpty()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 空档分布：这一周每个（周几 × 节次）格空不空，逐格判定（判据在 WeekFreeGrid 内核） */
@Composable
private fun FreeSlotsGridCard(grid: WeekFreeGrid.Grid) {
    val days = remember(grid) {
        grid.dayRows.map { day ->
            HeatGridDay(
                label = "周${weekdayChar(day.dayOfWeek)}",
                occupiedPeriods = day.occupiedPeriods,
                isEmptiest = grid.freeDayOfWeek == day.dayOfWeek,
            )
        }
    }
    val periodLabels = remember(grid) { grid.rows.map { it.period.toString() } }
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
            SectionHeader("空档分布")
            WeekFreeHeatGrid(
                days = days,
                periodLabels = periodLabels,
                currentWeek = grid.week,
            )
            val scope = grid.week?.let { "第 $it 周" } ?: "全学期"
            val note = listOfNotNull(
                grid.freeDayOfWeek?.let { "${scope}最空的是周${weekdayChar(it)}" },
                grid.freePeriods.takeIf { it.isNotEmpty() }
                    ?.joinToString("、") { "第 $it 节" }
                    ?.let { "$it 整周不落课" },
                // 作息表被裁剪时确实有"有课却画不出的格"：不说的图是在撒谎
                grid.offProfilePeriodCount.takeIf { it > 0 }
                    ?.let { "另有 $it 格的节次不在作息表里，未画出" },
            ).joinToString("；")
            if (note.isNotEmpty()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- 文案换算 ----

private fun weekdayChar(dayOfWeek: Int): String =    "一二三四五六日".getOrElse(dayOfWeek - 1) { '?' }.toString()

/** 分钟数说成人话：不足一小时只说分钟，整点只说小时 */
private fun humanMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h <= 0L -> "$m 分钟"
        m <= 0L -> "$h 小时"
        else -> "$h 小时 $m 分钟"
    }
}

// 学分的格式化不在这一页手抄：全应用只有 CourseMetaFormat 那一份
// （formatCredit 管单体、formatCreditTotal 管合计）。改前这里私带过一枚 trimCredits，
// 它与 formatCredit 是同一段逻辑的手抄版，而 null → "—" 那一档还和详情 Sheet 的
// "null 整行不画" 走了两套口径 —— 两条账都在 T81 这次收掉。
