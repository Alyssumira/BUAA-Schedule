package com.buaa.schedule.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.EmptyState
import com.buaa.schedule.core.designsystem.GlassGovernance
import com.buaa.schedule.core.designsystem.LiquidGlassMaterial
import com.buaa.schedule.core.designsystem.LocalReduceMotion
import com.buaa.schedule.core.designsystem.LocalSceneBackdrop
import com.buaa.schedule.core.designsystem.LocalSharedCourseBackdrop
import com.buaa.schedule.core.designsystem.ModalTransition
import com.buaa.schedule.core.designsystem.MotionTokens
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.core.designsystem.contentOnLuma
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.core.designsystem.coursePlateSceneLuma
import com.buaa.schedule.core.designsystem.degradedPlate
import com.buaa.schedule.core.designsystem.degradedPlateAlpha
import com.buaa.schedule.core.designsystem.innerShadow
import com.buaa.schedule.core.designsystem.legibleTintPlate
import com.buaa.schedule.core.designsystem.liquid.LiquidMenu
import com.buaa.schedule.core.designsystem.liquid.LiquidMenuItem
import com.buaa.schedule.core.designsystem.liquid.LiquidMenuWidth
import com.buaa.schedule.core.designsystem.liquid.liquidMenuHeight
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.core.designsystem.motionSpring
import com.buaa.schedule.core.designsystem.outerShadow
import com.buaa.schedule.core.designsystem.performTick
import com.buaa.schedule.core.designsystem.sheetExit
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.WEEKDAY_LABELS
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.ui.courseSharedElementModifier
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropRenderOptions
import com.kyant.backdrop.backdrops.SharedBlurSampleScale
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 星期名取自领域层唯一一份（R3-2：这里曾经是「周一…周日」的第三份副本） */
private val dayNames = WEEKDAY_LABELS
private val baseRowHeight = DesignTokens.weekRowHeight

/**
 * 表头里的日期（参考稿是「周二 / 9/15」两行）。
 * Locale 钉死为 US：默认 locale 的 DecimalStyle 可能输出非 ASCII 数字。
 */
private val dayOfMonthFormatter =
    java.time.format.DateTimeFormatter.ofPattern("M/d", java.util.Locale.US)

private val timeColumnWidth = DesignTokens.weekTimeColumnWidth
/** 紧凑模式下可见的日期列数（5 日视口），取自 [DesignTokens.weekCompactVisibleDays] */
private const val COMPACT_VISIBLE_DAYS = DesignTokens.weekCompactVisibleDays

/**
 * 周视图。信息密度优先：课程格不使用实时模糊，仅低透明色块 + 细描边。
 *
 * 响应式视口：窄屏（列宽不足时）按 5 日宽度设定列宽并支持横向滑动，
 * 时间列固定、星期栏与日期列共享同一滚动状态并初始定位到“今天”；
 * 宽屏仍 7 列满宽。
 *
 * @param displayWeek 当前展示的教学周；null 表示没有周次信息（展示全部课程）
 * @param today 真实"今天"：由 ViewModel 的跨午夜滴答给，不在这里 `LocalDate.now()` ——
 *   组合期读时钟不是快照订阅，跨过零点没有任何东西会因此重组，
 *   今日列高亮与紧凑视口的初始定位会一起停在昨天。
 * @param onBrowseWeekChange 用户翻周时回调，null 表示“回到本周”
 * @param conflictCourseIds 存在时间冲突的课程 id（红色边框 + 警示图标）
 */
@Composable
fun WeekView(
    courses: List<Course>,
    semester: Semester?,
    timeSlots: List<TimeSlot>,
    currentWeek: Int?,
    displayWeek: Int?,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onBrowseWeekChange: (Int?) -> Unit = {},
    onCourseClick: (Course) -> Unit = {},
    conflictCourseIds: Set<Long> = emptySet(),
    specialDays: List<com.buaa.schedule.domain.model.SpecialDay> = emptyList(),
    /** 刚从编辑器保存返回的课程：这一张做一次定位脉冲（④机会#4）；-1 = 无 */
    pulseCourseId: Long = -1L,
    onCourseMove: ((course: Course, newDayIndex: Int, newStartPeriod: Int, thisWeekOnly: Boolean) -> Unit)? = null,
    onCourseResize: ((course: Course, newPeriods: List<Int>) -> Unit)? = null,
    onCourseDelete: ((course: Course) -> Unit)? = null,
) {
    // 整块网格把「列表下标」当可视名次用：行序、时间线、拖拽取整、纵向度量都依赖
    // 节次自上而下的顺序，而教务数据与用户自定义作息都不保证有序。在唯一入口处排好，
    // 而不是每处各自兜底。必须包在 remember 里：sortedBy 每次重组都换新列表实例，
    // 下游那串 remember(slots, …) 会整片失效。
    val slots = remember(timeSlots) {
        (if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT).sortedBy { it.number }
    }
    // 兼容历史脏数据：总周数限幅，避免跳周列表物化超大列表
    val totalWeeks = (semester?.totalWeeks ?: 20).coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)
    // 每分钟对齐的 tick：作为 State 传入，只有“当前课高亮/时间线”读取该状态，
    // 普通课程格不会随每分钟 tick 全量重组；后台（低于 STARTED）自动停表。
    // 与日视图同一个算式，只差步长：醒来第一件事是发布、第二件事才是等下一个边界，
    // 所以从锁屏/后台回到前台的那一帧就是当下的时刻，不用等到下一次 tick 才翻面。
    val nowTickState = remember { mutableStateOf(LocalTime.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // 一次读取同时用于发布与算延时；15 秒一档只为红线有点实时感，
                // 节次翻面靠的是整分钟边界（下课时间都落在整分钟上）
                val current = LocalTime.now()
                nowTickState.value = current
                delay(nextTickDelayMillis(current, TIMELINE_TICK_MS))
            }
        }
    }
    val isBrowsingCurrentWeek = displayWeek == currentWeek

    // 假期/调休标注：按当前浏览周推算每天的日期，再匹配「学习日程」数据
    val displayWeekNumber = displayWeek ?: currentWeek ?: 1
    val weekStartDate = semester?.startLocalDate?.plusDays((displayWeekNumber - 1).toLong() * 7L)
    val specialDaysOfWeek: Map<Int, com.buaa.schedule.domain.model.SpecialDay> = remember(
        weekStartDate, specialDays,
    ) {
        if (weekStartDate == null) {
            emptyMap()
        } else {
            (0..6).mapNotNull { index ->
                val date = weekStartDate.plusDays(index.toLong())
                specialDays.firstOrNull { it.date == date }?.let { index to it }
            }.toMap()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 手机常规宽度下 7 列满宽（SleepDown 式整周总览）；
        // 仅极窄屏（列宽 < 48dp）才退化为 5 日视口横向滚动
        val daysViewportWidth = maxWidth - timeColumnWidth - DesignTokens.spaceS
        val sevenDayWidth = daysViewportWidth / dayNames.size
        val compactScroll = sevenDayWidth < 48.dp
        val compactDayWidth = if (compactScroll) {
            (daysViewportWidth / COMPACT_VISIBLE_DAYS).coerceAtLeast(64.dp)
        } else {
            sevenDayWidth
        }
        val dayScrollState = rememberScrollState()
        val density = LocalDensity.current
        if (compactScroll) {
            // 初始滚动到“今天”所在列并居中
            LaunchedEffect(Unit) {
                with(density) {
                    val todayIndex = (today.dayOfWeek.value - 1).coerceIn(0, dayNames.size - 1)
                    val target = (compactDayWidth * todayIndex).toPx() -
                        (daysViewportWidth.toPx() - compactDayWidth.toPx()) / 2
                    dayScrollState.scrollTo(target.toInt().coerceAtLeast(0))
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 周次导航 / 学期 / 校区 / 时间模式这一行由 HomeScreen 统一渲染
            // （放在页面级而不是周视图里，切到「今日」页签时这些入口也要在）
            DayHeader(
                highlightToday = isBrowsingCurrentWeek || currentWeek == null,
                today = today,
                compactScroll = compactScroll,
                compactDayWidth = compactDayWidth,
                dayScrollState = dayScrollState,
                specialDays = specialDaysOfWeek,
                weekStartDate = weekStartDate,
            )
            // 横滑翻周：有周次信息时走 HorizontalPager，手势、惯性与无障碍滚动语义都由
            // foundation 提供；没有周次信息（学期未设置）时退回单页网格，
            // 不凭空造出“第 1 周”误导用户。
            val pagerWeek = displayWeek ?: currentWeek
            if (pagerWeek != null) {
                val pagerState = rememberPagerState(
                    initialPage = (pagerWeek - 1).coerceIn(0, totalWeeks - 1),
                    pageCount = { totalWeeks },
                )
                // 外部改周（顶部翻周按钮 / 跳周弹窗 / 回到本周）时同步 Pager。
                // T52⑤（可打断）：这里此前第一行就是 `if (isScrollInProgress) return@LaunchedEffect`
                // ——那一次翻周被**整个丢掉**：这个 effect 只在 displayWeek / currentWeek 变化时重启，
                // 提前 return 之后没有任何东西补这一次同步；而下面的落定回写又会把用户刚点的那一周
                // 当成"用户自己滑到的"去对照，结果是顶栏写着第 20 周、屏幕停在惯性滑到的第 12 周。
                // 现在：手势没停就先把目标记下，等这一次自己落定立刻续上（见下面的 settle 监听）。
                var pendingSyncTarget by remember(pagerState) { mutableIntStateOf(-1) }
                LaunchedEffect(displayWeek, currentWeek) {
                    val target = ((displayWeek ?: currentWeek ?: 1) - 1).coerceIn(0, totalWeeks - 1)
                    if (pagerState.isScrollInProgress) {
                        pendingSyncTarget = target
                        return@LaunchedEffect
                    }
                    pendingSyncTarget = -1
                    // animateScrollToPage 的契约本身就是"从当前值接着走"：
                    // 已有程序化滚动在飞时新的调用会接管它、从此刻的位置继续，不回到上一页重来
                    if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
                }
                // 只在滑动**落定**后回写：一次长距离惯性滑动会连着跨过好几页，
                // 按 currentPage 逐页回写等于每跨一页就把整张课表重新过滤一遍、
                // 还跟着震一次（P2）。isScrollInProgress 变 false 才是"这一滑完了"。
                // displayWeek / onBrowseWeekChange 走 rememberUpdatedState：这个 effect
                // 不再随 displayWeek 重启，直接捕获到的是首帧的旧值，会把顶部翻周按钮
                // 那次改动也读成"用户滑到了别的周"，白震一下还多回写一次。
                val haptics = LocalHapticFeedback.current
                val latestDisplayWeek by rememberUpdatedState(displayWeek)
                val latestOnBrowseWeekChange by rememberUpdatedState(onBrowseWeekChange)
                LaunchedEffect(pagerState) {
                    // snapshotFlow 一订阅就先发当前值（false），首帧那次"落定"不是用户滑的；
                    // displayWeek 为 null 时它会把浏览周次凭空钉死在今天这一周、还震一下。
                    // 只认真正的 滚动中 → 已落定 这条边。
                    var wasScrolling = false
                    snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
                        if (scrolling) {
                            wasScrolling = true
                            return@collect
                        }
                        if (!wasScrolling) return@collect
                        wasScrolling = false
                        val pending = pendingSyncTarget
                        if (pending >= 0) {
                            // 这一滑是被"外部改周"打断的：先把人送到该去的那一周，
                            // 不把中途的落点当成用户自己选的周次回写（那会把顶栏刷回第 12 周）
                            pendingSyncTarget = -1
                            if (pagerState.currentPage != pending) pagerState.animateScrollToPage(pending)
                            return@collect
                        }
                        val week = pagerState.currentPage + 1
                        if (week != latestDisplayWeek) {
                            latestOnBrowseWeekChange(week)
                            haptics.performTick()
                        }
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    WeekGrid(
                        courses = courses,
                        slots = slots,
                        weekForContent = page + 1,
                        currentWeek = currentWeek,
                        totalWeeks = totalWeeks,
                        onReturnToThisWeek = { onBrowseWeekChange(null) },
                        today = today,
                        nowTickState = nowTickState,
                        compactScroll = compactScroll,
                        compactDayWidth = compactDayWidth,
                        dayScrollState = dayScrollState,
                        conflictCourseIds = conflictCourseIds,
                        onCourseClick = onCourseClick,
                        onCourseMove = onCourseMove,
                        onCourseResize = onCourseResize,
                        onCourseDelete = onCourseDelete,
                        pulseCourseId = pulseCourseId,
                    )
                }
            } else {
                WeekGrid(
                    courses = courses,
                    slots = slots,
                    weekForContent = null,
                    // 「没有浏览周次」有两种含义：学期真的没设（只能整学期一起看），
                    // 与学期在、只是今天不落在任何教学周（放假 / 超周 / 还没开学）。
                    // 后者不能照旧画全部课程，详见 [WeekGrid.coursesForContent]（P1）。
                    showAllCoursesWithoutWeek = semester == null,
                    currentWeek = currentWeek,
                    totalWeeks = totalWeeks,
                    today = today,
                    nowTickState = nowTickState,
                    compactScroll = compactScroll,
                    compactDayWidth = compactDayWidth,
                    dayScrollState = dayScrollState,
                    conflictCourseIds = conflictCourseIds,
                    onCourseClick = onCourseClick,
                    onCourseMove = onCourseMove,
                    onCourseResize = onCourseResize,
                    onCourseDelete = onCourseDelete,
                    pulseCourseId = pulseCourseId,
                )
            }
        }
    }
}

/**
 * 单周课表网格。被 [WeekView] 的翻周 Pager 与“无周次信息”分支复用。
 *
 * @param weekForContent 本页对应的教学周；null 表示这一页没有对应的周次
 * @param showAllCoursesWithoutWeek [weekForContent] 为 null 时是否照旧画出全部课程：
 *   只有**真没有学期**（不存在周次概念）时才该这样；学期在、只是当前浏览周次取不到
 *   （放假 / 已超周 / 还没开学）时必须给空网格，否则第 1-8 周与 9-16 周的卡叠在同一格
 * @param onCourseMove 课程拖拽落点的回调（dayIndex 0..6，newStartPeriod 是**整门课**的
 *   新起始节，拖非首段时由网格侧换算好）；传 null 时禁用拖拽
 */
@Composable
private fun WeekGrid(
    courses: List<Course>,
    slots: List<TimeSlot>,
    weekForContent: Int?,
    currentWeek: Int?,
    /** 学期总周数：课程 weeks 比它短就是「非每周都有」，卡角要画角标（T-30 / ③C-02） */
    totalWeeks: Int,
    today: LocalDate,
    nowTickState: State<LocalTime>,
    compactScroll: Boolean,
    compactDayWidth: Dp,
    dayScrollState: ScrollState,
    conflictCourseIds: Set<Long>,
    /** 刚从编辑器保存返回的课程：这一张做一次定位脉冲（④机会#4）；-1 = 无 */
    pulseCourseId: Long = -1L,
    onCourseClick: (Course) -> Unit,
    onCourseMove: ((course: Course, newDayIndex: Int, newStartPeriod: Int, thisWeekOnly: Boolean) -> Unit)? = null,
    onCourseResize: ((course: Course, newPeriods: List<Int>) -> Unit)? = null,
    onCourseDelete: ((course: Course) -> Unit)? = null,
    /** 空态里的「回到本周」：翻到别的周看到空白时，光靠一句提示找不到回家的路 */
    onReturnToThisWeek: () -> Unit = {},
    showAllCoursesWithoutWeek: Boolean = true,
) {
    val coursesForContent = remember(courses, weekForContent, showAllCoursesWithoutWeek) {
        when {
            weekForContent != null -> courses.filter { it.weeks.contains(weekForContent) }
            showAllCoursesWithoutWeek -> courses
            // 学期存在却取不到浏览周次 = 今天不在教学周里（放假 / 已超周 / 还没开学）。
            // 以前这里退回"画全部课程"，于是 1-8 周与 9-16 周的卡叠在同一格里，
            // 还和同屏顶栏那句「假期中」自相矛盾（P1）—— 宁可给一张空网格。
            else -> emptyList()
        }
    }
    // 每列取数预聚合一次：此前是在 7 列的循环里各 filter 一遍，
    // 每次重组（15s tick、拖拽每一帧）都要重做 8 次过滤。
    val coursesByDay = remember(coursesForContent) { coursesForContent.groupBy { it.dayOfWeek } }
    // 卡片进场（T52②）：整屏一条驱动（见 rememberCourseEntrance），名次逐卡走
    // "天列 → 列内课程 → 连续段"——渲染顺序即显示顺序；CourseCell 自带按压/脉冲
    // 的缩放层，进场变换并进那同一层（applyTo），不叠第二层、不添离屏代价
    val entrance = rememberCourseEntrance(EntrancePlaybook.WEEK_GRID)
    // 节次起止时间预解析：slotRange 每次调用是 2 次线性扫描 + 2 次 LocalTime.parse，
    // 而它被每张卡的「当前节课」判断在绘制期调用（R5 F-23）
    val slotIndex = remember(slots) { TimeSlotIndex(slots) }
    // 连续节次段同样只切一次：此前在「7 列 × 每门课 × 每次重组」里各算一遍，
    // 拖动期间约 40 个 list+IntRange/帧（R5 F-23）
    // 切段按墙钟间隔而不是节次号相邻，否则第 5、6 节会画成一张横跨午休的卡（R5 F-30）
    val segmentsByCourse = remember(coursesForContent, slotIndex) {
        // 课表里不存在的节次（缩了作息后的旧课程、外部导入的超表节次）没有对应的行：
        // 以前会退到按比例兜底，末端被拉到网格底部，一张卡纵向盖住整张课表（D4）。
        // 与编辑器「缺节次时间即不可排课」同一口径 —— 这些节次直接不画，
        // 课程本身仍从日视图 / 课表管理进编辑器修正。
        coursesForContent.associate { course ->
            course.id to course.periods
                .filter { it in slotIndex.numbered }
                .toPeriodSegments(slotIndex::gapMinutes)
        }
    }
    // 进场名次表（T52②）：显示序号按「天 → 节次」排——列内课程列表是 DAO 的
    // (dayOfWeek, id) 顺序而不是节次顺序，直接拿循环下标会排错先后。这里对
    // **已聚合好的** coursesByDay/segmentsByCourse 走一次 remember 建表（改动课表
    // 才重算），渲染循环里逐卡只是一次哈希查找：不为排名次在每次重组重走数据，
    // 也不给每张卡各起一条动画。key 不含天：一门课只属于一天，(id, 段起点) 唯一。
    val (entranceRanks, entranceCardCount) = remember(coursesByDay, segmentsByCourse) {
        val ranks = HashMap<Pair<Long, Int>, Int>()
        var rank = 0
        dayNames.indices.forEach { day ->
            coursesByDay[day + 1].orEmpty()
                .flatMap { course ->
                    segmentsByCourse[course.id].orEmpty().map { course.id to it.first }
                }
                .sortedBy { (_, startPeriod) -> startPeriod }
                .forEach { key -> ranks[key] = rank++ }
        }
        ranks to rank
    }
    // 玻璃档位与渲染能力探测在网格层算一次：effectiveTier 内部读 Runtime，
    // isRenderEffectSupported 走系统能力查询，逐卡各算一次 = 拖动时每帧几十次（R5 F-22）
    val glassReady = remember(Personalization.glassTier) {
        GlassGovernance.effectiveTier(Personalization.glassTier) >= DesignTokens.GLASS_TIER_STANDARD &&
            com.kyant.backdrop.isRenderEffectSupported()
    }
    // 用户可调行高：默认 64dp，缩放 0.75–1.5；24h 模式的时高同步缩放
    val rowHeight = if (Personalization.weekFitViewport) {
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        val headerSpace = 96
        ((screenHeightDp - headerSpace).coerceAtLeast(200) / slots.size.coerceAtLeast(1)).dp
    } else {
        baseRowHeight * Personalization.weekRowScale
    }
    // —— 24 小时连续时间轴模式（拾光式）：纵轴按真实时间线性映射 ——
    val timeMode = Personalization.weekGridMode == Personalization.WEEK_GRID_TIME_24H
    val hourHeight = DesignTokens.weekHourHeight * Personalization.weekRowScale
    val timeWindow: Pair<Int, Int> = remember(coursesForContent, slotIndex, timeMode) {
        if (timeMode) timeWindowOf(coursesForContent, slotIndex) else 0 to 0
    }
    // 基准布局**不含动画值**：animatedGapHeight 每秒变化 60 次，
    // 把它放进 remember key 会让 300ms 动画期间每帧重建整份 periodLayouts，
    // 进而让下面每门课的 periodSegTop/periodSegHeight 全部重算 → 整片网格重组。
    // 空档只影响「它下面那些行」的位置，所以这里留空、在 placement 阶段叠加偏移。
    val periodLayouts = remember(slots, rowHeight, timeMode) {
        if (!timeMode) {
            buildPeriodLayouts(slots, rowHeight)
        } else {
            emptyList()
        }
    }
    // 课间空档只与"现在落在哪两节之间"有关，和 tick 的具体取值无关。
    // 直接在组合期读 nowTickState.value 会让整个网格每 15 秒全量重组一次
    // （8 次 filter + buildPeriodLayouts + 全部卡片的 modifier 重算）。
    // derivedStateOf 把读取关进派生状态：tick 变了但空档没变时不会触发重组。
    // 空档检测复用上面那份布局：以前它自己再把节次时间 parse 一遍。
    val targetGap by remember(periodLayouts, rowHeight) {
        derivedStateOf {
            findIntervalGap(periodLayouts, nowTickState.value, rowHeight)
        }
    }
    var lastGapNumber by remember { mutableStateOf<Int?>(targetGap?.first) }
    // 取到局部变量再判空：targetGap 是委托属性（derivedStateOf），无法智能转换
    val gap = targetGap
    LaunchedEffect(gap?.first) {
        if (gap != null) lastGapNumber = gap.first
    }
    // 不做 `by` 委托：委托等于在网格作用域里读值，300ms 动画期间
    // 每秒 60 次让整片卡片树重组（R5 F-22）。这里只把状态对象传下去，
    // 由消费方在自己的作用域里取值（卡片则干脆只在布局期取）。
    val animatedGapHeight = animateDpAsState(
        targetValue = targetGap?.second ?: 0.dp,
        animationSpec = motionSpec<Dp>(),
    )
    val gapAfterNumber = targetGap?.first ?: lastGapNumber
    val gridHeight = if (timeMode) {
        hourHeight * ((timeWindow.second - timeWindow.first) / 60)
    } else if (periodLayouts.isNotEmpty()) {
        // 用空档的**目标**高度而不是动画中间值：读动画值等于每帧重组整块网格。
        // 动画期间容器高度提前到位，越界的卡片仍会绘制（Box 不裁剪），只是那一瞬
        // 滚动范围略大于内容。
        val settledGapHeight = targetGap?.second ?: 0.dp
        periodLayouts.last().let { it.top + it.height + gapShiftFor(it.number, gapAfterNumber, settledGapHeight) }
    } else {
        rowHeight * slots.size
    }

    // —— 拖拽状态：长按卡片拿起 → 跟手 → 落点取整到格子 → 底部确认条 ——
    var drag by remember { mutableStateOf<CourseDragState?>(null) }
    var pendingMove by remember { mutableStateOf<CourseMoveRequest?>(null) }
    // 非拖拽的调课入口（U-08）：先选目标时间，选完照样走上面那个确认框
    var movePickerFor by remember { mutableStateOf<CourseMovePickerRequest?>(null) }
    // —— 长按菜单 / 缩放改节次 / 详情底部弹层 ——
    var menuFor by remember { mutableStateOf<CourseMenuRequest?>(null) }
    // 关闭动画的退出期不再重组内容：菜单要停在最后那一格上淡掉，
    // 而不是在收起的一瞬间跳回第一列第一行（同落点高亮那一处的手法）
    var lastMenu by remember { mutableStateOf<CourseMenuRequest?>(null) }
    if (menuFor != null) lastMenu = menuFor
    var resizeFor by remember { mutableStateOf<ResizeState?>(null) }
    var pendingDelete by remember { mutableStateOf<Course?>(null) }
    var detailFor by remember { mutableStateOf<Course?>(null) }
    var daysAreaWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val dayWidthPx = if (compactScroll) {
        with(density) { compactDayWidth.toPx() }.toInt()
    } else {
        (daysAreaWidthPx / dayNames.size).coerceAtLeast(1)
    }
    // 纵向度量的唯一出口：卡片排布、拖拽取整、边缘自动滚动、落点高亮框、缩窄改节次
    // 全部从这里换算「第几节 ↔ 多少像素」（P1-3）。以前这几处各拿各的节距——
    // 24h 模式的卡片按小时高排布，拖拽却除以行高，手指停的位置和确认框里的节次对不上。
    // 空档取**目标**高度而不是动画中间值，理由同上面算 gridHeight 的那处注释。
    val gridMetric = remember(
        slots, periodLayouts, rowHeight, hourHeight, timeMode, timeWindow, gapAfterNumber, targetGap?.second,
    ) {
        buildWeekGridMetric(
            slots = slots,
            layouts = periodLayouts,
            rowHeight = rowHeight,
            density = density,
            timeMode = timeMode,
            hourHeight = hourHeight,
            windowStartMin = timeWindow.first * 60,
            gapAfterNumber = gapAfterNumber,
            gapHeight = targetGap?.second ?: 0.dp,
        )
    }
    // 网格自身的纵向滚动：菜单要贴在按压点上，得知道内容被卷走了多少
    val gridScrollState = rememberScrollState()
    var gridViewportPx by remember { mutableStateOf(IntSize.Zero) }
    val sceneBackdrop = LocalSceneBackdrop.current

    // —— 拖拽时的边缘自动滚动（①I-01）——
    // 卡片伸进视口上下缘 56dp 内就按越界深度滚动，一次按住能从第 1 节拖到第 12 节；
    // 以前必须"拖到底 → 松手 → 取消确认框 → 滚屏 → 再长按拖一次"。
    // 窄屏七天挤一屏，横向对 dayScrollState 再来一份。
    //
    // 滚掉的量必须**同时**叠回 totalOffset 并重新取整目标格子：卡片黏在手指上，
    // 前进的是"目标节次"，而不是让卡片被内容拖离手指。因此只认实际生效的滚动量——
    // 已经滚到内容尽头时原地停着，卡片不该继续攒位移。
    // 循环里读 drag / scrollState 都在协程内，不构成组合期读取，不会牵动整片网格重组。
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        val edgePx = with(density) { dragEdgeZone.toPx() }
        val maxStepPx = with(density) { dragEdgeMaxSpeed.toPx() }
        val timeColPx = with(density) { timeColumnWidth.toPx() }
        // scrollTo 只吃整像素，浅越界时不足 1px 的部分攒到下一帧，否则永远滚不动
        var carryY = 0f
        var carryX = 0f
        while (true) {
            val d = drag ?: break
            val viewport = gridViewportPx
            val cardTop = d.originTopPx + d.totalOffset.y - gridScrollState.value
            val overY = when {
                viewport.height == 0 -> 0f
                cardTop < edgePx -> cardTop - edgePx
                cardTop + d.heightPx > viewport.height - edgePx ->
                    cardTop + d.heightPx - (viewport.height - edgePx)
                else -> 0f
            }
            val overX = if (!compactScroll || viewport.width == 0) 0f else {
                val left = timeColPx + d.originDayIndex * dayWidthPx + d.totalOffset.x - dayScrollState.value
                when {
                    left < edgePx -> left - edgePx
                    left + dayWidthPx > viewport.width - edgePx ->
                        left + dayWidthPx - (viewport.width - edgePx)
                    else -> 0f
                }
            }
            if (overX == 0f && overY == 0f) {
                delay(DRAG_IDLE_TICK_MS)
                continue
            }
            val wantY = edgeDragScrollStep(overY, edgePx, maxStepPx) + carryY
            val beforeY = gridScrollState.value
            gridScrollState.scrollTo((beforeY + wantY).roundToInt())
            val appliedY = (gridScrollState.value - beforeY).toFloat()
            carryY = wantY - appliedY
            var appliedX = 0f
            if (overX != 0f) {
                val wantX = edgeDragScrollStep(overX, edgePx, maxStepPx) + carryX
                val beforeX = dayScrollState.value
                dayScrollState.scrollTo((beforeX + wantX).roundToInt())
                appliedX = (dayScrollState.value - beforeX).toFloat()
                carryX = wantX - appliedX
            }
            if (appliedX == 0f && appliedY == 0f) {
                delay(DRAG_IDLE_TICK_MS)
                continue
            }
            drag = drag?.advancedBy(
                Offset(appliedX, appliedY), dayWidthPx, dayNames.size, gridMetric,
            )
            withFrameMillis { }
        }
    }

    // —— 进入本周时把「现在」滚进视野（③P3#10）——
    // 一屏只装得下 6~8 节，默认停在第 1 节等于白天打开课表先看到一片空行。
    // 只定位一次：翻到别的周、旋转屏幕之后都尊重用户自己滚到的位置。
    var scrolledToNow by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(gridViewportPx.height) {
        if (scrolledToNow || gridViewportPx.height == 0 || weekForContent != currentWeek) {
            return@LaunchedEffect
        }
        scrolledToNow = true
        val nowMin = nowTickState.value.let { it.hour * 60 + it.minute }
        val nowTopPx = if (timeMode) {
            val span = (timeWindow.second - timeWindow.first).coerceAtLeast(1)
            ((nowMin - timeWindow.first).toFloat() / span).coerceIn(0f, 1f) *
                with(density) { gridHeight.toPx() }
        } else {
            // 课间空档就停在上一节的行首：上面留几行，看得到刚下课的那一节
            with(density) { (periodLayouts.lastOrNull { it.startMin <= nowMin }?.top ?: 0.dp).toPx() }
        }
        gridScrollState.scrollTo(
            (nowTopPx - gridViewportPx.height * NOW_VIEWPORT_FRACTION).roundToInt(),
        )
    }

    val overscrollEffect = rememberOverscrollEffect()
    Column(modifier = Modifier.fillMaxSize()) {
    // 视口层：长按菜单不能坐在滚动容器里（会被 verticalScroll 的裁剪切掉，
    // 也要自己补偿滚动量），所以这里多套一层 Box 同时装「可滚动的网格」和菜单。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .onSizeChanged { gridViewportPx = it },
    ) {
    // 网格整体可纵向滚动，晚间节次不被屏幕裁掉；显式挂 overscroll 物理（拉伸/回弹由系统效果实现）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .overscroll(overscrollEffect)
            .verticalScroll(gridScrollState),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 时间列固定，不随日期横向滚动
                Box(modifier = Modifier.width(timeColumnWidth).fillMaxHeight()) {
                    if (timeMode) {
                        HourLabels(timeWindow.first / 60, timeWindow.second / 60, hourHeight)
                    } else {
                        TimeLabels(
                            slots = slots,
                            layouts = periodLayouts,
                            rowHeight = rowHeight,
                            gapAfterNumber = gapAfterNumber,
                            gapHeight = { animatedGapHeight.value },
                        )
                    }
                }
                val daysAreaModifier = if (compactScroll) {
                    Modifier
                        .weight(1f)
                        .horizontalScroll(dayScrollState)
                } else {
                    Modifier.weight(1f)
                }
                Box(
                    modifier = daysAreaModifier
                        .fillMaxHeight()
                        .onSizeChanged { daysAreaWidthPx = it.width },
                ) {
                    // 「现在」线画在七天列**之下**（T55）：它此前是这个 Box 的最后一个子项，
                    // 于是 2dp 的红线整条横穿网格——装机实测（buaa36，周一 17:41）第 10 节
                    // 那三张卡（思想政治 / 算法竞赛训练 / 思想政治）的名字全被划了一道。
                    // Box 的子项按声明顺序叠放，挪到列之前就是卡片盖住线：没有一张卡上的字
                    // 被压，而空着的列（这一行里的周二三四日）照旧把线整段露出来，
                    // "现在"读得出。玻璃卡画的是整屏烘焙的那张壁纸前缀（LocalSharedCourseBackdrop
                    // 录的是 SceneBackground，壁纸/主题变才重录），所以玻璃档下线在卡下不透出来；
                    // 降级档的板是 0.92，透出的那点已经读不出来。
                    // 24h 模式与节次行模式共用这一处落位，两条线不该各修一次。
                    if (timeMode) {
                        NowLine(
                            visible = weekForContent == currentWeek,
                            startMin = timeWindow.first,
                            endMin = timeWindow.second,
                            totalHeight = gridHeight,
                            nowTickState = nowTickState,
                        )
                    } else {
                        NowLinePeriod(
                            visible = weekForContent == currentWeek,
                            layouts = periodLayouts,
                            totalHeight = gridHeight,
                            nowTickState = nowTickState,
                        )
                    }
                    Row(modifier = Modifier.fillMaxHeight()) {
                        dayNames.forEachIndexed { index, _ ->
                            val day = index + 1
                            val dayCourses = coursesByDay[day].orEmpty()
                            // ③C-03：本列里每张卡的重叠名次，渲染顺序即 z 序
                            val overlapRanks = remember(dayCourses, segmentsByCourse) {
                                conflictOverlapRanks(dayCourses, segmentsByCourse)
                            }
                            val columnModifier = if (compactScroll) {
                                Modifier.width(compactDayWidth)
                            } else {
                                Modifier.weight(1f)
                            }
                            Box(
                                modifier = columnModifier
                                    .fillMaxHeight()
                                    .padding(end = if (compactScroll) DesignTokens.spaceXS else 0.dp),
                            ) {
                                // 拖拽落点高亮（T-37）：画在卡片原本所在列，横向按格子宽度平移到候选格。
                                // 此前它瞬时出现、换格瞬时跳、松手瞬时抽走，"引导感"就没了。
                                val dropForColumn = drag?.takeIf {
                                    it.originDayIndex == index && dayWidthPx > 0
                                }
                                // 松手后 drag 立刻变 null，而淡出的退出期内容不再重组：
                                // 记住最后一个落点，让框停在最后一格上淡掉，而不是滑回原点。
                                // 组合期直接写（LaunchedEffect 要晚一帧，拖拽首帧会画在上一轮落点上）
                                var lastDrop by remember(index) { mutableStateOf(dropForColumn) }
                                if (dropForColumn != null) lastDrop = dropForColumn
                                val slideDays by animateFloatAsState(
                                    targetValue = lastDrop?.let {
                                        (it.targetDayIndex - it.originDayIndex).toFloat()
                                    } ?: 0f,
                                    animationSpec = motionSpring(
                                        dampingRatio = 1f,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                    label = "dropHighlightDay",
                                )
                                val slidePeriods by animateFloatAsState(
                                    // 名次而不是节次号：节次表被用户删过几节时，
                                    // 「编号 - 1」和「可视第几行」根本不是一回事（P1-3）
                                    targetValue = (lastDrop?.let {
                                        gridMetric.rankOfPeriod(it.targetStartPeriod)
                                    } ?: 0).toFloat(),
                                    animationSpec = motionSpring(
                                        dampingRatio = 1f,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                    label = "dropHighlightPeriod",
                                )
                                // 淡入淡出走 alpha，不走 AnimatedVisibility：后者在网格里有两套
                                // 重载（animation 的顶层版 / foundation.layout 的 RowScope 版），
                                // 列容器同时拿着 Row 和 Column 的隐式接收者，编译器两边都不敢用。
                                val dropAlpha by animateFloatAsState(
                                    targetValue = if (dropForColumn != null) 1f else 0f,
                                    animationSpec = motionSpec<Float>(),
                                    label = "dropHighlightAlpha",
                                )
                                (dropForColumn ?: lastDrop)?.let { drop ->
                                    Box(
                                        modifier = Modifier
                                            .offset {
                                                IntOffset(
                                                    (slideDays * dayWidthPx).roundToInt(),
                                                    // 小数名次插值：弹簧动画照旧平滑，
                                                    // 但落点像素不再来自第二个节距（P1-3）
                                                    gridMetric.topOfRank(slidePeriods).roundToInt(),
                                                )
                                            }
                                            .size(
                                                width = with(density) { dayWidthPx.toDp() },
                                                // 与手上那张卡等高：框和卡不同高会让人以为
                                                // 落点比卡片多占（或少占）一节
                                                height = with(density) { drop.heightPx.toDp() },
                                            )
                                            // alpha 读在绘制期：淡出这 260ms 不牵动组合
                                            .graphicsLayer { alpha = dropAlpha }
                                            .border(
                                                width = 2.dp,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                                shape = RoundedCornerShape(DesignTokens.cornerCourse),
                                            )
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                    )
                                }
                                dayCourses.forEach { course ->
                                    if (timeMode) {
                                        // 24h 时间轴：卡片按真实时间定位，高度=时长
                                        segmentsByCourse[course.id].orEmpty().forEach { segment ->
                                            val (s, e) = slotIndex.range(segment)
                                            val startMin = s.hour * 60 + s.minute
                                            val endMin = e.hour * 60 + e.minute
                                            val yDp = with(density) {
                                                ((startMin - timeWindow.first) / 60f * hourHeight.toPx()).toDp()
                                            }
                                            val hDp = with(density) {
                                                ((endMin - startMin) / 60f * hourHeight.toPx()).toDp()
                                            }
                                            val isDragged = drag?.let {
                                                    it.course.id == course.id && it.segment == segment
                                                } == true
                                            CourseCell(
                                                course = course,
                                                segment = segment,
                                                entrance = entrance,
                                                entranceSlot = entranceRanks[course.id to segment.first] ?: 0,
                                                entranceSlotCount = entranceCardCount,
                                                cardHeight = hDp,
                                                inConflict = course.id in conflictCourseIds,
                                                notEveryWeek = course.weeks.size < totalWeeks,
                                                isCurrentProvider = {
                                                    weekForContent == currentWeek &&
                                                        course.dayOfWeek == today.dayOfWeek.value &&
                                                        nowTickState.value >= s && nowTickState.value < e
                                                },
                                                glassReady = glassReady,
                                                pulsing = course.id == pulseCourseId,
                                                dragged = isDragged,
                                                dragOffset = drag?.takeIf { isDragged }?.totalOffset ?: Offset.Zero,
                                                onDragStart = if (onCourseMove != null && weekForContent != null) {
                                                    {
                                                        menuFor = null
                                                        drag = CourseDragState(
                                                            course = course,
                                                            segment = segment,
                                                            originDayIndex = index,
                                                            originStartPeriod = segment.first,
                                                            totalOffset = Offset.Zero,
                                                            targetDayIndex = index,
                                                            targetStartPeriod = segment.first,
                                                            originTopPx = with(density) { yDp.toPx() },
                                                            heightPx = with(density) { hDp.toPx() },
                                                        )
                                                    }
                                                } else null,
                                                onDragDelta = if (onCourseMove != null) { amount ->
                                                    drag = drag?.advancedBy(
                                                        amount, dayWidthPx, dayNames.size, gridMetric,
                                                    )
                                                } else null,
                                                onDragEnd = if (onCourseMove != null) {
                                                    {
                                                        val d = drag
                                                        drag = null
                                                        if (d != null &&
                                                            (d.targetDayIndex != d.originDayIndex ||
                                                                d.targetStartPeriod != d.originStartPeriod)
                                                        ) {
                                                            pendingMove = CourseMoveRequest(
                                                                course = d.course,
                                                                newDayIndex = d.targetDayIndex,
                                                                newStartPeriod = d.targetStartPeriod,
                                                                draggedSegmentStart = d.originStartPeriod,
                                                                week = weekForContent,
                                                            )
                                                        }
                                                    }
                                                } else null,
                                                modifier = Modifier
                                                    .zIndex(if (isDragged) 3f else 0f)
                                                    .offset {
                                                        if (isDragged) {
                                                            IntOffset(
                                                                drag?.totalOffset?.x?.roundToInt() ?: 0,
                                                                drag?.totalOffset?.y?.roundToInt() ?: 0,
                                                            )
                                                        } else {
                                                            IntOffset.Zero
                                                        }
                                                    }
                                                    .fillMaxWidth()
                                                    .height(hDp)
                                                    .offset(y = yDp)
                                                    // ③C-03：重叠卡按名次错开；拖起来的那张已经抬高一层，回到满宽
                                                    .padding(
                                                        start = if (isDragged) 0.dp else conflictStagger(
                                                            overlapRanks[course.id to segment] ?: 0,
                                                        ),
                                                    )
                                                    .padding(horizontal = 1.dp, vertical = 1.dp),
                                                onClick = { onCourseClick(course) },
                                                onLongPress = { pos ->
                                                    menuFor = CourseMenuRequest(
                                                        course = course,
                                                        segment = segment,
                                                        dayIndex = index,
                                                        // 没有手指位置（读屏动作）时锚在卡片右下角
                                                        anchorX = index * dayWidthPx +
                                                            (pos?.x ?: dayWidthPx.toFloat()),
                                                        anchorY = with(density) {
                                                            yDp.toPx() + (pos?.y ?: hDp.toPx())
                                                        },
                                                    )
                                                },
                                                onMoveViaDialog = if (onCourseMove != null && weekForContent != null) {
                                                    {
                                                        movePickerFor =
                                                            CourseMovePickerRequest(course, segment, index)
                                                    }
                                                } else null,
                                            )
                                        }
                                    } else {
                                    // 非连续节次按连续段渲染为多张卡片
                                    segmentsByCourse[course.id].orEmpty().forEach { segment ->
                                        val isDragged = drag?.let {
                                            it.course.id == course.id && it.segment == segment
                                        } == true
                                        // 卡高只算一次：既要给 .height()，也要给卡片做文本行数预算，
                                        // 两者必须一致，否则又会回到"内容比卡片高、被 clip 裁掉"的老问题
                                        val segHeight = periodSegHeight(periodLayouts, segment, rowHeight)
                                        // 那 2dp 是行间隙。行高够容纳 48dp 时把它还给点击区
                                        // （紧凑档原本只有 46dp）；行本身不到 48dp 时不硬撑——
                                        // 压住下一节课比少几个 dp 的触控目标更糟。
                                        val cardHeight =
                                            if (segHeight >= DesignTokens.minTouchTarget) {
                                                (segHeight - 2.dp).coerceAtLeast(DesignTokens.minTouchTarget)
                                            } else {
                                                segHeight - 2.dp
                                            }
                                        // 静态基准 top 只算一次，空档的动画偏移留到布局期再叠加
                                        val baseTopPx = with(density) {
                                            (periodSegTop(periodLayouts, segment, rowHeight) + 1.dp).roundToPx()
                                        }
                                        CourseCell(
                                            course = course,
                                            segment = segment,
                                            entrance = entrance,
                                            entranceSlot = entranceRanks[course.id to segment.first] ?: 0,
                                            entranceSlotCount = entranceCardCount,
                                            cardHeight = cardHeight,
                                            inConflict = course.id in conflictCourseIds,
                                            notEveryWeek = course.weeks.size < totalWeeks,
                                            isCurrentProvider = {
                                                weekForContent == currentWeek &&
                                                    course.dayOfWeek == today.dayOfWeek.value &&
                                                    slotIndex.range(segment).let { (s, e) ->
                                                        nowTickState.value >= s && nowTickState.value < e
                                                    }
                                            },
                                            glassReady = glassReady,
                                            pulsing = course.id == pulseCourseId,
                                            dragged = isDragged,
                                            dragOffset = drag?.takeIf { isDragged }?.totalOffset ?: Offset.Zero,
                                            onDragStart = if (onCourseMove != null && weekForContent != null) {
                                                {
                                                    menuFor = null
                                                    drag = CourseDragState(
                                                        course = course,
                                                        segment = segment,
                                                        originDayIndex = index,
                                                        originStartPeriod = segment.first,
                                                        totalOffset = Offset.Zero,
                                                        targetDayIndex = index,
                                                        targetStartPeriod = segment.first,
                                                        originTopPx = (baseTopPx + with(density) {
                                                            gapShiftFor(
                                                                segment.first,
                                                                gapAfterNumber,
                                                                animatedGapHeight.value,
                                                            ).roundToPx()
                                                        }).toFloat(),
                                                        heightPx = with(density) { cardHeight.toPx() },
                                                    )
                                                }
                                            } else null,
                                            onDragDelta = if (onCourseMove != null) { amount ->
                                                drag = drag?.advancedBy(
                                                    amount, dayWidthPx, dayNames.size, gridMetric,
                                                )
                                            } else null,
                                            onDragEnd = if (onCourseMove != null) {
                                                {
                                                    val d = drag
                                                    drag = null
                                                    if (d != null &&
                                                        (d.targetDayIndex != d.originDayIndex ||
                                                            d.targetStartPeriod != d.originStartPeriod)
                                                    ) {
                                                        pendingMove = CourseMoveRequest(
                                                            course = d.course,
                                                            newDayIndex = d.targetDayIndex,
                                                            newStartPeriod = d.targetStartPeriod,
                                                            draggedSegmentStart = d.originStartPeriod,
                                                            week = weekForContent,
                                                        )
                                                    }
                                                }
                                            } else null,
                                            modifier = Modifier
                                                .zIndex(if (isDragged) 3f else 0f)
                                                .offset {
                                                    if (isDragged) {
                                                        IntOffset(
                                                            drag?.totalOffset?.x?.roundToInt() ?: 0,
                                                            drag?.totalOffset?.y?.roundToInt() ?: 0,
                                                        )
                                                    } else {
                                                        IntOffset.Zero
                                                    }
                                                }
                                                .fillMaxWidth()
                                                .height(cardHeight)
                                                .offset {
                                                    IntOffset(
                                                        0,
                                                        baseTopPx + gapShiftFor(
                                                            segment.first,
                                                            gapAfterNumber,
                                                            animatedGapHeight.value,
                                                        ).roundToPx(),
                                                    )
                                                }
                                                // ③C-03：重叠卡按名次错开；拖起来的那张已经抬高一层，回到满宽
                                                .padding(
                                                    start = if (isDragged) 0.dp else conflictStagger(
                                                        overlapRanks[course.id to segment] ?: 0,
                                                    ),
                                                )
                                                .padding(horizontal = 1.dp, vertical = 1.dp),
                                            onClick = { onCourseClick(course) },
                                            onLongPress = { pos ->
                                                menuFor = CourseMenuRequest(
                                                    course = course,
                                                    segment = segment,
                                                    dayIndex = index,
                                                    anchorX = index * dayWidthPx +
                                                        (pos?.x ?: dayWidthPx.toFloat()),
                                                    // 空档偏移只在按下那一刻取一次当前值：
                                                    // 这是事件回调，不在组合期读动画值
                                                    anchorY = with(density) {
                                                        baseTopPx + gapShiftFor(
                                                            segment.first,
                                                            gapAfterNumber,
                                                            animatedGapHeight.value,
                                                        ).roundToPx().toFloat() +
                                                            (pos?.y ?: cardHeight.toPx())
                                                    },
                                                )
                                            },
                                            onMoveViaDialog = if (onCourseMove != null && weekForContent != null) {
                                                {
                                                    movePickerFor =
                                                        CourseMovePickerRequest(course, segment, index)
                                                }
                                            } else null,
                                            resizeHandleVisible = resizeFor?.course?.id == course.id &&
                                                resizeFor?.segment == segment,
                                            onResizeDelta = if (resizeFor?.course?.id == course.id && resizeFor?.segment == segment) {
                                                { dy -> resizeFor?.let { resizeFor = it.copy(deltaY = it.deltaY + dy) } }
                                            } else null,
                                            onResizeEnd = if (resizeFor?.course?.id == course.id && resizeFor?.segment == segment) {
                                                {
                                                    val r = resizeFor
                                                    resizeFor = null
                                                    if (r != null) {
                                                        val newEnd = r.newEnd(gridMetric)
                                                        val merged = resizeCoursePeriods(r.course, r.segment, r.originalStart, newEnd)
                                                        if (merged != r.course.periods) {
                                                            onCourseResize?.invoke(r.course, merged)
                                                        }
                                                    }
                                                }
                                            } else null,
                                        )
                                    }
                                    } // else（节次行模式）
                                }
                            }
                        }
                    }
                    // 周次有值但本周没有课时，在课程区域给出明确空态，
                    // 避免只剩时间轴空白让人以为渲染坏了
                    if (weekForContent != null && coursesForContent.isEmpty()) {
                        Box(
                            modifier = Modifier.matchParentSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyState(
                                icon = Icons.Filled.EventBusy,
                                title = "本周没有课",
                                description = "这一周没有排课，可以左右滑动看看其他周。",
                                modifier = Modifier.padding(horizontal = DesignTokens.spaceL),
                            ) {
                                if (weekForContent != currentWeek) {
                                    Button(
                                        onClick = onReturnToThisWeek,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("回到本周") }
                                }
                            }
                        }
                    }
                    // 学期在、但今天不落在任何教学周（放假 / 已超周 / 还没开学）：上面那处
                    // 刚把这一屏渲染成空网格，这里得说清"为什么是空的"，否则只剩一片空白，
                    // 与顶栏那句「假期中」一起读起来像渲染坏了。不给「回到本周」——
                    // 本周本来就取不到，按钮点了也没有可回的地方。
                    if (weekForContent == null && !showAllCoursesWithoutWeek) {
                        Box(
                            modifier = Modifier.matchParentSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyState(
                                icon = Icons.Filled.EventBusy,
                                title = "假期中",
                                description = "今天不在本学期的教学周里，所以这一屏没有课；开学后会自动回到课表。",
                                modifier = Modifier.padding(horizontal = DesignTokens.spaceL),
                            )
                        }
                    }
                }
            }
        }
    }

        // —— 长按快捷菜单（①C-03 统一浮层语言 / ①I-02 锚点跟手）——
        // 整张网格只渲染这一个菜单，而不是每张卡里各挂一个 DropdownMenu：
        // 玻璃层是这款 App 的识别度，长按卡片不该掉回 M3 默认浮层。
        lastMenu?.let { menu ->
            // DropdownMenu 免费给的「返回键关闭」要自己补上
            BackHandler(enabled = menuFor != null) { menuFor = null }
            val items = remember(
                menu, onCourseMove, onCourseResize, onCourseDelete, weekForContent, timeMode,
            ) {
                buildList {
                    add(LiquidMenuItem(Icons.Default.Info, "详情 · 周次/教师") { detailFor = menu.course })
                    add(LiquidMenuItem(Icons.Default.Edit, "编辑") { onCourseClick(menu.course) })
                    if (onCourseMove != null && weekForContent != null) {
                        add(
                            LiquidMenuItem(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                "移动到…",
                            ) {
                                movePickerFor = CourseMovePickerRequest(
                                    menu.course,
                                    menu.segment,
                                    menu.dayIndex,
                                )
                            },
                        )
                    }
                    // 24h 时间轴那张卡不接 resize 回调，挂出来就是个点不动的死项
                    if (onCourseResize != null && !timeMode) {
                        add(
                            LiquidMenuItem(Icons.Default.Schedule, "调整时长") {
                                resizeFor = ResizeState(
                                    menu.course,
                                    menu.segment,
                                    menu.segment.first,
                                    menu.segment.last,
                                )
                            },
                        )
                    }
                    if (onCourseDelete != null) {
                        add(LiquidMenuItem(Icons.Default.Delete, "删除") { pendingDelete = menu.course })
                    }
                }
            }
            CourseMenuOverlay(
                menu = menu,
                items = items,
                visible = menuFor != null,
                onDismiss = { menuFor = null },
                viewportPx = gridViewportPx,
                density = density,
                dayScrollState = dayScrollState,
                gridScrollState = gridScrollState,
                backdrop = sceneBackdrop,
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(5f),
            )
        }
        }

        // U-08：不依赖拖拽的调课入口（长按菜单「移动到…」与读屏自定义动作都走这里）。
        // 选完目标时间交给下面那个确认弹窗，两条入口共用同一套 所有周 / 仅本周 语义。
        // 走 payload 版而不是 `movePickerFor?.let`：后者在清空的那一刻就把子树摘走，收场播不出来。
        ModalTransition(payload = movePickerFor) { request, modal ->
            val span = request.segment.last - request.segment.first + 1
            CourseMovePickerDialog(
                request = request,
                dayNames = dayNames,
                // 上界向度量要，而不是拿 slots.size 当节次号用：
                // 这条路与拖拽那条路必须收在同一个格子上（P1-3）
                maxStartPeriod = gridMetric.periodAtRank(gridMetric.maxStartRank(span)),
                modifier = modal,
                onDismiss = { movePickerFor = null },
                onConfirm = { dayIndex, startPeriod ->
                    movePickerFor = null
                    // 选择框收的也是"那一段"的目标起始节，与拖拽同一口径，
                    // 段基准交给请求，落库前换算成整课起点
                    pendingMove = CourseMoveRequest(
                        course = request.course,
                        newDayIndex = dayIndex,
                        newStartPeriod = startPeriod,
                        draggedSegmentStart = request.segment.first,
                        week = weekForContent,
                    )
                },
            )
        }

        // 拖拽落点确认弹窗：确认后才真正改课
        ModalTransition(payload = pendingMove) { request, modal ->
            AlertDialog(
                modifier = modal,
                onDismissRequest = { pendingMove = null },
                title = { Text("确认移动课程？") },
                text = {
                    Text(
                        "将「${request.course.displayName}」移到 ${dayNames[request.newDayIndex]} 第${request.newStartPeriod}节起。"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = request
                            pendingMove = null
                            // 回调的是**整课**新起始节：非首段被拖过时段差已经在这里换算掉
                            onCourseMove?.invoke(
                                target.course,
                                target.newDayIndex,
                                target.courseStartPeriod,
                                false,
                            )
                        },
                    ) { Text("所有周") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { pendingMove = null }) { Text("取消") }
                        TextButton(
                            onClick = {
                                val target = request
                                pendingMove = null
                                onCourseMove?.invoke(
                                    target.course,
                                    target.newDayIndex,
                                    target.courseStartPeriod,
                                    true,
                                )
                            },
                        ) { Text("仅本周") }
                    }
                },
            )
        }

        ModalTransition(payload = pendingDelete) { course, modal ->
            AlertDialog(
                modifier = modal,
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除课程？") },
                text = {
                    Text("将删除「${course.displayName}」及其提醒；本会话内可通过撤销恢复。")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = course
                            pendingDelete = null
                            onCourseDelete?.invoke(target)
                        },
                        // 破坏性确认统一用 error 字色：与课表管理页的「删除」「清空」同一口径
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                },
            )
        }

        // 弹层开在自己的窗口里，`detailFor?.let` 一撤整块瞬间消失；外壳只当挂载闸门。
        // 进场给 None：ModalBottomSheet 自带的下滑就是它的进场，再叠一层缩放是两股动画。
        ModalTransition(
            payload = detailFor,
            enter = androidx.compose.animation.EnterTransition.None,
            exit = sheetExit(),
        ) { course, modal ->
            CourseDetailSheet(
                course = course,
                timeSlots = slots,
                modifier = modal,
                onDismiss = { detailFor = null },
                onEdit = {
                    detailFor = null
                    onCourseClick(course)
                },
                onDelete = {
                    detailFor = null
                    if (onCourseDelete != null) pendingDelete = course
                },
            )
        }
    }
}

/**
 * 「移动到其他时间」的步进选择框（审查 U-08）。
 *
 * 改时间此前只有长按拖拽一条路，而拖拽对读屏用户和精细动作受限的用户都不存在
 * （WCAG 2.2 · 2.5.7 Dragging Movements）。这里用两组加减按钮收集目标时间，
 * 确认后交给拖拽用的同一个确认弹窗 —— 入口不同，落库语义必须相同。
 */
@Composable
private fun CourseMovePickerDialog(
    request: CourseMovePickerRequest,
    dayNames: List<String>,
    maxStartPeriod: Int,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onConfirm: (dayIndex: Int, startPeriod: Int) -> Unit,
) {
    var dayIndex by remember(request) { mutableIntStateOf(request.dayIndex.coerceIn(0, dayNames.lastIndex)) }
    var startPeriod by remember(request) {
        mutableIntStateOf(request.segment.first.coerceIn(1, maxStartPeriod))
    }
    val span = request.segment.last - request.segment.first + 1
    AlertDialog(
        // 调用方 ModalTransition 给的进出场修饰符要挂在 AlertDialog 自己身上：
        // 弹窗是另一个窗口，父级 graphicsLayer 进不去。
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("移动课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                Text(
                    text = "将「${request.course.displayName}」移到：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                MovePickerStepper(
                    label = "星期",
                    value = dayNames[dayIndex],
                    onDecrement = { dayIndex = (dayIndex - 1 + dayNames.size) % dayNames.size },
                    onIncrement = { dayIndex = (dayIndex + 1) % dayNames.size },
                )
                MovePickerStepper(
                    label = "起始节次",
                    value = "第 $startPeriod 节",
                    onDecrement = { startPeriod = (startPeriod - 1).coerceAtLeast(1) },
                    onIncrement = { startPeriod = (startPeriod + 1).coerceAtMost(maxStartPeriod) },
                )
                Text(
                    text = "共 $span 节：第 $startPeriod-${startPeriod + span - 1} 节",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dayIndex, startPeriod) }) { Text("下一步") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun MovePickerStepper(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp),
        )
        // IconButton 本身 48dp，不要再套 size 压小它
        IconButton(onClick = onDecrement) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "减小$label")
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onIncrement) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "增大$label")
        }
    }
}

/**
 * 节次起止时间的预解析索引。
 *
 * 取代此前的 `slotRange`：每次调用要 2 次线性扫描 + 2 次 `LocalTime.parse`，
 * 而「当前节课」判断是**在每张卡片的绘制期**跑的 —— 40 张卡 × 每次重绘 =
 * 160 次/帧的解析与扫描（R5 F-23）。构造一次即可整帧复用。
 *
 * internal 而非 private：[timeWindowOf] 的守卫由 JVM 单测把守，测试要拿到的是
 * 这份真实索引（含"节次缺失退回 08:00–22:15"那条兜底），而不是在测试里另拼一遍。
 */
internal class TimeSlotIndex(slots: List<TimeSlot>) {
    private val starts: Map<Int, LocalTime> = slots.mapNotNull { slot ->
        runCatching { slot.number to LocalTime.parse(slot.startTime) }.getOrNull()
    }.toMap()
    private val ends: Map<Int, LocalTime> = slots.mapNotNull { slot ->
        runCatching { slot.number to LocalTime.parse(slot.endTime) }.getOrNull()
    }.toMap()

    /** 与原实现同口径：节次缺失或时间非法时退回 08:00–22:15 */
    fun range(segment: IntRange): Pair<LocalTime, LocalTime> =
        (starts[segment.first] ?: LocalTime.of(8, 0)) to (ends[segment.last] ?: LocalTime.of(22, 15))

    /** 课表里有合法起止时间的节次号；网格只为这些节次画行 */
    val numbered: Set<Int> get() = starts.keys

    /** 前一节下课 → 后一节上课的间隔分钟数；任一节缺时间时返回 null */
    fun gapMinutes(from: Int, to: Int): Long? {
        val end = ends[from] ?: return null
        val begin = starts[to] ?: return null
        return ChronoUnit.MINUTES.between(end, begin)
    }
}

/**
 * 24 小时连续时间轴的上下界（分钟，取整到点）：门上界、点下界。
 *
 * 一门**没有节次**的课整门不贡献 mins（T27）：这里此前取 `course.startPeriod`
 * 与 `course.endPeriod`，而它们在空表时兜底成 1 —— 于是这门在网格里一格都画不出来的课
 * （`segmentsByCourse` 为空，压根没有卡片）能把整条时间轴的上界拉到第 1 节的下课时间，
 * 用户滚到的那一片空白就是它撑出来的。空节次时"这一项不出场"，与导出/组件那两处
 * 定位链同口径：宁可少一门，不替它编一个上课时间。
 *
 * 一门课都贡献不出 mins 时落到下面那句 08:00–22:00 的默认窗口：它说的是"这一天没有
 * 可排的课"，此前空节次的课会顶掉这个默认值，冒充成一节真的 08:00 开始的课。
 */
internal fun timeWindowOf(
    courses: List<Course>,
    slotIndex: TimeSlotIndex,
): Pair<Int, Int> {
    val mins = buildList {
        courses.forEach { course ->
            val first = course.firstPeriodOrNull ?: return@forEach
            val last = course.lastPeriodOrNull ?: return@forEach
            slotIndex.range(first..first).first.let { add(it.hour * 60 + it.minute) }
            slotIndex.range(last..last).second.let { add(it.hour * 60 + it.minute) }
        }
        if (isEmpty()) {
            add(8 * 60)
            add(22 * 60)
        }
    }
    return (mins.min() / 60) * 60 to ((mins.max() + 59) / 60) * 60
}

/** 拖拽时卡片伸进视口边缘多深就开始滚动（①I-01）：一截拇指宽度，够得着也不会误触 */
private val dragEdgeZone = 56.dp

/** 完全压到边缘时每帧的最大滚动量；越界越浅滚得越慢，比例线性 */
private val dragEdgeMaxSpeed = 14.dp

/** 卡片没压在边缘上时的轮询间隔：手指可能随时把它移过去，但不用每帧都问 */
private const val DRAG_IDLE_TICK_MS = 32L

/** 进入本周时把「现在」停在视口的这个位置——0.35 = 上三分之一，而不是紧贴顶边。
 *  internal：日视图时间轴的自动滚动（T49-C）落点要和周视图同一个口径 */
internal const val NOW_VIEWPORT_FRACTION = 0.35f

/** 每一档重叠让出的横向距离：够看见前面那张卡的边缘，又不至于把卡压成一条 */
private val conflictStaggerStep = 8.dp

/** 让位封顶档数：第 5 张起不再继续缩，否则窄屏紧凑列里卡片会没有可读宽度 */
private const val conflictStaggerMaxRank = 4

/**
 * 冲突卡片的横向错开量（审查③C-03）。
 *
 * 同一格里的两张卡以前**完全重叠**：看到的只是"一块颜色叠着另一块"，
 * 两张各自的 error 描边还互相盖住，于是卡片上唯一稳定的冲突信号只剩那个警告图标。
 * 让后渲染的那张往右让一档，前一张就从左缘露出来——"这里有两门课"不用点开任何提示。
 */
private fun conflictStagger(rank: Int): Dp =
    conflictStaggerStep * rank.coerceAtMost(conflictStaggerMaxRank)

/** 按渲染顺序算出每张卡在本列里的重叠名次，键为 (课程 id, 连续节次段) */
private fun conflictOverlapRanks(
    dayCourses: List<Course>,
    segmentsByCourse: Map<Long, List<IntRange>>,
): Map<Pair<Long, IntRange>, Int> {
    val cards = ArrayList<Pair<Long, IntRange>>()
    dayCourses.forEach { course ->
        segmentsByCourse[course.id].orEmpty().forEach { cards += course.id to it }
    }
    val ranks = HashMap<Pair<Long, IntRange>, Int>(cards.size)
    for (i in cards.indices) {
        val seg = cards[i].second
        var rank = 0
        for (j in 0 until i) {
            val other = cards[j].second
            // 渲染顺序按节次起始排，所以"更早开始"= 左缘露出来的那一张
            if (other.first <= seg.last && seg.first <= other.last) rank++
        }
        ranks[cards[i]] = rank
    }
    return ranks
}

/**
 * 越界深度 → 本帧滚动像素（①I-01）。符号跟着越界方向走：压上缘为负、压下缘为正。
 *
 * 只要还在边缘区内就保底 1px/帧——线性比例在刚进区时会算出 0.2px 这种值，
 * 取整后是 0，用户会觉得"明明停在边上却一点不动"。
 */
private fun edgeDragScrollStep(overPx: Float, edgePx: Float, maxStepPx: Float): Float {
    if (overPx == 0f) return 0f
    val step = (overPx / edgePx).coerceIn(-1f, 1f) * maxStepPx
    return if (overPx > 0f) step.coerceAtLeast(1f) else step.coerceAtMost(-1f)
}

/**
 * 拖拽松手后的待确认移动请求。
 *
 * [newStartPeriod] 记的是**被拖那一段**的目标起始节 —— 确认框里念给用户听的、
 * 以及屏幕上那张卡实际落到的格子，都是这一段。而 [Course] 的 `periods` 可能有好几段
 * （1-2 + 9-10 连排），`onCourseMove` 约定的又是**整门课**的新起始节次，所以落库前
 * 必须按 [draggedSegmentStart] 换算成 [courseStartPeriod]：拿段目标直接当整课起点，
 * 拖 9-10 那一段会把 1-2 那一段也推到 8-9 去（P0 位移错位）。
 */
private data class CourseMoveRequest(
    val course: Course,
    val newDayIndex: Int,
    val newStartPeriod: Int,
    /** 被拖那一段原来的起始节（段内拖拽的基准，非整课起始节） */
    val draggedSegmentStart: Int,
    val week: Int?,
) {
    /** 交给 [onCourseMove] 的整课新起始节 */
    val courseStartPeriod: Int
        get() = course.startPeriod + (newStartPeriod - draggedSegmentStart)
}

/**
 * 「移动到其他时间」的待选请求（审查 U-08）。
 *
 * 拖拽是这张卡片唯一的改时间入口，而拖拽对 TalkBack、对手指精细动作受限的用户
 * 都不存在（WCAG 2.5.7 Dragging Movements）。这里记下"用户想动哪一段"，
 * 由选择框收目标时间，选完仍走 [CourseMoveRequest] 那个确认弹窗。
 */
private data class CourseMovePickerRequest(
    val course: Course,
    val segment: IntRange,
    val dayIndex: Int,
)

/**
 * 长按菜单请求：一门课 + 当前被长按的连续节次段 + 锚点。
 *
 * 锚点记的是**格内坐标**（x 相对日期区左缘、y 相对网格内容顶部），不是屏幕坐标：
 * 屏幕坐标要自己减掉滚动量，一旦菜单打开时用户滚动就对不上；格内坐标只要减去
 * 当前滚动值就能贴回原位，卡片滚动时菜单跟着走（①I-02）。
 */
private data class CourseMenuRequest(
    val course: Course,
    val segment: IntRange,
    /** 卡片所在的日期列（0..6）：「移动到…」要知道从哪一列出发 */
    val dayIndex: Int,
    val anchorX: Float,
    val anchorY: Float,
)

/**
 * 课程卡长按菜单：贴在按压点上的那一层（①C-03 统一浮层语言 / ①I-02 锚点跟手）。
 *
 * 单独成一个组合函数是因为这里要在组合期读两个方向的滚动量。放在网格作用域里，
 * 等于用户每滚一格就重组一次整片卡片树（R5 F-22 那一类问题）。
 */
@Composable
private fun CourseMenuOverlay(
    menu: CourseMenuRequest,
    items: List<LiquidMenuItem>,
    visible: Boolean,
    onDismiss: () -> Unit,
    viewportPx: IntSize,
    density: Density,
    dayScrollState: ScrollState,
    gridScrollState: ScrollState,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val menuWpx = with(density) { LiquidMenuWidth.toPx() }
    val menuHpx = with(density) { liquidMenuHeight(items.size).toPx() }
    // 格内坐标 → 视口坐标：横向减日期区的滚动、纵向减网格的滚动，
    // 于是卡片滚走时菜单跟着一起走，不会留在原地指着别的课。
    val x = (with(density) { timeColumnWidth.toPx() } + menu.anchorX - dayScrollState.value)
        .coerceIn(0f, (viewportPx.width - menuWpx).coerceAtLeast(0f))
    val rawY = menu.anchorY - gridScrollState.value
    // 往下放不下就翻到按压点上方：贴底的卡片不该把菜单顶出屏幕
    val flipUp = rawY + menuHpx > viewportPx.height
    val y = (if (flipUp) rawY - menuHpx else rawY)
        .coerceIn(0f, (viewportPx.height - menuHpx).coerceAtLeast(0f))

    Box(modifier = modifier) {
        if (visible) {
            // 点外面关闭：DropdownMenu 在 Popup 里免费做掉的事，组合内要自己拦一层。
            // 透明、无涟漪——它只是个拦截区，不是一颗按钮。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        LiquidMenu(
            items = items,
            visible = visible,
            onDismiss = onDismiss,
            backdrop = backdrop,
            // 展开动画从按压点那个角长出来（组件默认的右下角是给 FAB 用的）
            menuOrigin = TransformOrigin(0f, if (flipUp) 1f else 0f),
            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
        )
    }
}

/** 节次行模式的时间指示线：按行号 + 行内时间比例定位，和左侧每节一行对齐。
 *  连续时间轴那一条与小时刻度列已提取到 TimelineAxis.kt（T49：日视图时间轴要用同一套）。 */
@Composable
private fun NowLinePeriod(
    visible: Boolean,
    layouts: List<PeriodLayout>,
    totalHeight: Dp,
    nowTickState: State<LocalTime>,
) {
    if (!visible) return
    val now = nowTickState.value
    if (layouts.isEmpty()) return

    val nowMin = now.hour * 60 + now.minute
    val fraction = when {
        nowMin < layouts.first().startMin -> 0f
        nowMin >= layouts.last().endMin -> 1f
        else -> {
            val index = layouts.indexOfFirst { nowMin >= it.startMin && nowMin < it.endMin }
            if (index >= 0) {
                val layout = layouts[index]
                val passed = (nowMin - layout.startMin).toFloat().coerceIn(0f, (layout.endMin - layout.startMin).toFloat())
                val within = passed / (layout.endMin - layout.startMin).coerceAtLeast(1)
                val slotTopFraction = layout.top / totalHeight
                val slotHeightFraction = layout.height / totalHeight
                slotTopFraction + slotHeightFraction * within
            } else {
                // 当前时间在课间空档：按真实时间比例定位到空档内
                val prevIndex = layouts.indexOfLast { nowMin >= it.endMin }
                val nextIndex = (prevIndex + 1).coerceAtMost(layouts.lastIndex)
                if (prevIndex >= 0 && nextIndex <= layouts.lastIndex) {
                    val prev = layouts[prevIndex]
                    val next = layouts[nextIndex]
                    val gapStart = prev.endMin
                    val gapEnd = next.startMin
                    val gapFraction = if (gapEnd > gapStart) {
                        (nowMin - gapStart).toFloat() / (gapEnd - gapStart)
                    } else 0f
                    ((prev.top + prev.height) + (next.top - (prev.top + prev.height)) * gapFraction) / totalHeight
                } else if (prevIndex >= 0) {
                    val last = layouts[prevIndex]
                    ((last.top + last.height) / totalHeight).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }

    // 画线与"一分钟一跳补成一段滑行"都由共享件负责（理由见 TimelineAxis.kt 的 NowGlideLine）：
    // 24h 模式与日视图时间轴此前各画一条、各跳一次，收口只收在一处。
    NowGlideLine(fraction, totalHeight)
}

/** 固定星期标题栏，与网格日期列共用同一横向滚动状态；今天高亮 */
@Composable
private fun DayHeader(
    highlightToday: Boolean,
    today: LocalDate,
    compactScroll: Boolean,
    compactDayWidth: Dp,
    dayScrollState: ScrollState,
    specialDays: Map<Int, com.buaa.schedule.domain.model.SpecialDay> = emptyMap(),
    /** 当前展示周的周一；用于在表头显示每一天的日期（参考稿是「周二 / 9/15」两行） */
    weekStartDate: LocalDate? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spaceXS)
            // 参考稿：整行是一个浅色圆角容器，「节次」一格 + 7 天
            .clip(RoundedCornerShape(DesignTokens.cornerPanel))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            // 上下留白从 4dp 收到 2dp：这一格本身已有两行 16sp 文字，
            // 再叠上每格自己的留白，顶栏就一层层垫厚了（真机反馈）
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧与时间列同宽，标出这一列是"节次"
        Box(
            modifier = Modifier.width(timeColumnWidth),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "节次",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val headerAreaModifier = if (compactScroll) {
            Modifier
                .weight(1f)
                .horizontalScroll(dayScrollState)
        } else {
            Modifier.weight(1f)
        }
        Box(modifier = headerAreaModifier) {
            Row(modifier = Modifier.fillMaxWidth()) {
                dayNames.forEachIndexed { index, dayName ->
                    val isToday = highlightToday && today.dayOfWeek.value == index + 1
                    val special = specialDays[index]
                    val cellModifier = if (compactScroll) {
                        Modifier.width(compactDayWidth)
                    } else {
                        Modifier.weight(1f)
                    }
                    // 参考稿：一格两行「周二 / 9/15」，今天用填充胶囊高亮
                    val cellDate = weekStartDate?.plusDays(index.toLong())
                    Box(
                        modifier = cellModifier.padding(end = if (compactScroll) DesignTokens.spaceXS else 0.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(vertical = 1.dp)
                                .background(
                                    if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(DesignTokens.cornerPill),
                                )
                                // 横向内边距取小值：窄屏 7 列时每格只有 ~43dp，
                                // 太大底边距会把"周一"挤到换行（高度跳变）
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = dayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isToday) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                // 假期（休）/ 调休（班）标注：只做视觉提示，不影响任何计算
                                special?.let { day ->
                                    val badge = com.buaa.schedule.domain.model.SpecialDay.badgeOf(day)
                                    Text(
                                        text = badge,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isToday) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else if (day.isHoliday) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.padding(start = 2.dp),
                                    )
                                }
                            }
                            if (cellDate != null) {
                                Text(
                                    text = cellDate.format(dayOfMonthFormatter),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isToday) {
                                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeLabels(
    slots: List<TimeSlot>,
    layouts: List<PeriodLayout>,
    rowHeight: Dp,
    /** 空档插在哪一节之后（null = 当前无空档） */
    gapAfterNumber: Int? = null,
    /** 空档高度的取值回调：只在标签列里读，动画不会波及整块网格 */
    gapHeight: () -> Dp,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        slots.forEach { slot ->
            val layout = layouts.firstOrNull { it.number == slot.number }
            val height = layout?.height ?: rowHeight
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height),
                contentAlignment = Alignment.Center,
            ) {
                val labelLineHeight = with(LocalDensity.current) {
                    MaterialTheme.typography.labelMedium.lineHeight.takeIf { it.isSp }?.toDp() ?: 16.dp
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 行高够时补上节次号：参考稿左侧是「1 / 08:00 / 08:45」三行。
                    // 行高不够（视口适配 / 节次很多）时只显示起止时间，避免又出现文字溢出。
                    if (height >= labelLineHeight * 3) {
                        Text(
                            text = "${slot.number}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = "${slot.startTime}\n${slot.endTime}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 空档靠一个 Spacer 撑开，而不是改 layout.height / layout.top：
            // 这样节次行本身的高度与位置保持恒定，只有空档在动。
            if (gapAfterNumber == slot.number) {
                Spacer(modifier = Modifier.height(gapHeight()))
            }
        }
    }
}

/**
 * 课程卡副信息（T-29 / ③C-01）：一行预算只够放一个字段，按偏好挑教师或教室，
 * 首选为空时回落到另一个——教室未定的课显示教师，没登记教师的课显示教室。
 */
private fun courseCardMeta(course: Course): String? {
    val location = course.location?.takeIf { it.isNotBlank() }
    val teacher = course.teacher?.takeIf { it.isNotBlank() }
    return if (Personalization.courseCardMetaPreference == Personalization.META_TEACHER_FIRST) {
        teacher ?: location
    } else {
        location ?: teacher
    }
}

/** 按压反馈的两级缩放（T-33）：按下"捏住"，长按抬起"拿起" */
private const val COURSE_PRESS_SCALE = 0.97f
private const val COURSE_LIFT_SCALE = 1.06f
/** 定位脉冲峰值：比「拿起」的 1.06 小，只够看出「就是这张」，不像被点住了 */
private const val COURSE_PULSE_SCALE = 1.04f

/**
 * 课程卡内缩：卡在间距刻度 spaceXS(4dp) 与 spaceS(8dp) 之间，两个都不对——
 * 一节高的矮卡里 8dp 会把第二行字挤出预算，4dp 又让文字贴住描边。
 * 它算的是"这张卡里还剩多少地方给字"，不是两块内容之间的距离，故不进版面刻度。
 */
private val CourseCellContentPadding = 5.dp

@Composable
private fun CourseCell(
    course: Course,
    segment: IntRange,
    /** 整屏共享的进场驱动（T52②）：变换并进下面那张按压/脉冲的 graphicsLayer，不叠第二层 */
    entrance: CourseEntrance,
    /** 本卡在屏内的显示名次与总卡数，由网格层的名次表一次建好（见 entranceRanks） */
    entranceSlot: Int,
    entranceSlotCount: Int,
    /** 卡片实际高度（与 modifier 上的 .height() 一致），用于按可用空间分配文本行数 */
    cardHeight: Dp,
    inConflict: Boolean,
    isCurrentProvider: () -> Boolean,
    /** 玻璃档位与渲染能力：由网格层算一次传下来，不在每张卡里各查一遍 */
    glassReady: Boolean,
    modifier: Modifier = Modifier,
    /** 这门课的 weeks 比学期短（单周课 / 只上半程）：右上角标一个极小三角（T-30） */
    notEveryWeek: Boolean = false,
    /** 刚从编辑器保存返回：这一次组合播一次定位脉冲（④机会#4） */
    pulsing: Boolean = false,
    onClick: () -> Unit = {},
    dragged: Boolean = false,
    dragOffset: Offset = Offset.Zero,
    onDragStart: (() -> Unit)? = null,
    onDragDelta: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    /**
     * 长按拿起卡片时上报按下点（卡片本地像素），菜单由网格层统一渲染（①C-03/①I-02）。
     * null = 读屏的「打开快捷菜单」动作，没有手指位置，由调用方按卡片右下角锚定。
     */
    onLongPress: ((Offset?) -> Unit)? = null,
    /** 「移动到其他时间」：不依赖拖拽的改时间入口（审查 U-08） */
    onMoveViaDialog: (() -> Unit)? = null,
    resizeHandleVisible: Boolean = false,
    onResizeDelta: ((Float) -> Unit)? = null,
    onResizeEnd: (() -> Unit)? = null,
) {
    val background = courseColor(course)
    // 共享元素转场：课程卡与编辑器使用同一 key，由 MainActivity 的 SharedTransitionLayout 驱动。
    // 键的写法收口在 courseSharedElementModifier（T52④：日视图两种模式漏接的就是这份重复）。
    val sharedModifier: Modifier = Modifier.courseSharedElementModifier(course.id)
    // 手势：单击打开课程；长按打开快捷菜单；长按后继续移动且超过触摸阈值才进入拖拽。
    // 这样“长按菜单”和“长按拖移”可以共存：原地松手=菜单，移动=拖拽。
    val viewConfiguration = LocalViewConfiguration.current
    val haptics = LocalHapticFeedback.current
    val hasDrag = onDragStart != null && onDragDelta != null && onDragEnd != null
    val hasCustomGesture = onLongPress != null || hasDrag
    // 按压反馈（T-33）：这条分支没有 clickable，此前从按下到长按震动的那 500ms
    // 屏幕上什么都不会变——用户点得最多的东西反而最没反馈。两级语义：
    // 按下 0.97「捏住了」，长按抬起 1.06「拿起来了」。
    var pressed by remember(course.id) { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = when {
            dragged -> COURSE_LIFT_SCALE
            pressed -> COURSE_PRESS_SCALE
            else -> 1f
        },
        animationSpec = motionSpring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "courseCellPress",
    )
    // 定位脉冲（T-36）：保存返回后，几十格里要一眼认出刚才改的是哪一张。
    // ⚠️ pulse.value 只在下面的 graphicsLayer / drawWithContent lambda 里读：
    // 它是快照状态，在组合期读等于让这张卡每帧重组（网格里还有几十张兄弟卡）。
    val pulse = remember { Animatable(0f) }
    // 减少动效时整段脉冲不播：缩放线索此前已经撤掉，但两段 alpha 是裸 tween，
    // 关了动画描边还会闪一下——"关了动画还有东西在动"不成立。
    // 规格也换成 motionSpec：开关的判定只写在 Motion.kt 一份里，调用点只管不启动它。
    val reduceMotion = LocalReduceMotion.current
    val pulseScales = !reduceMotion
    // 规格在组合期取好：LaunchedEffect 的挂起块不在组合里求值，读不到 LocalReduceMotion
    val pulseSpec = motionSpec<Float>(MotionTokens.DURATION_SHORT)
    LaunchedEffect(pulsing, reduceMotion) {
        if (pulsing && !reduceMotion) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, pulseSpec)
            pulse.animateTo(0f, pulseSpec)
        } else if (pulse.value != 0f) {
            // T52⑤（可打断）：撤掉脉冲此前是 snapTo(0)——描边正淡到一半被瞬间抽走，
            // 读成"闪一下就没了"。改成从**当前值**接着淡出（Animatable 的 animateTo 天生如此）。
            // 上面那条启动路径保留 snapTo(0)：重新要一次定位，就得看到一次完整的脉冲，
            // 从半截接着亮第二次读不出"这是新的一次"。
            pulse.animateTo(0f, pulseSpec)
        }
    }
    val dragGestureModifier = if (hasCustomGesture) {
        Modifier.pointerInput(course.id, segment, onLongPress != null, hasDrag) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                pressed = true
                val longPress = awaitLongPressOrCancellation(down.id)
                if (longPress == null) {
                    pressed = false
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (up != null && up.changedToUpIgnoreConsumed() && !up.isConsumed) {
                        up.consume()
                        onClick()
                    }
                    return@awaitEachGesture
                }
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress?.invoke(longPress.position)
                var accumulated = Offset.Zero
                var dragging = false
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == longPress.id } ?: break
                        if (!change.pressed) {
                            released = change.changedToUpIgnoreConsumed()
                            change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        accumulated += delta
                        change.consume()
                        if (!dragging && hasDrag && accumulated.getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDragStart()
                            onDragDelta?.invoke(delta)
                        } else if (dragging) {
                            onDragDelta?.invoke(delta)
                        }
                    }
                } finally {
                    pressed = false
                    if (dragging) onDragEnd?.invoke()
                }
            }
        }
    } else {
        Modifier
    }
    // 缩放只有一个写入者（pressScale）；dragged 只额外负责抬层、跟手与投影。
    val dragVisualModifier = Modifier
        .then(
            if (dragged) {
                Modifier
                    .zIndex(3f)
                    .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            } else {
                Modifier
            },
        )
        .graphicsLayer {
            // pressScale 是交互缩放的唯一写入者；脉冲只是叠在上面的临时乘数，
            // 且只在绘制期读 pulse.value（见上面的注释）。
            val pulseBoost =
                if (pulseScales) 1f + (COURSE_PULSE_SCALE - 1f) * pulse.value else 1f
            scaleX = pressScale * pulseBoost
            scaleY = pressScale * pulseBoost
            if (dragged) shadowElevation = 18f
            // 进场（T52②）：alpha/translationY 并进这同一层——两张图层各持一份 alpha
            // 就没法合账，落定后也各自恒等，静止像素与改前逐位一致。
            entrance.applyTo(this, entranceSlot, entranceSlotCount)
        }

    // 液态玻璃课程卡：共享模糊前缀（整屏一次烘焙）+ 卡片自身折射，
    // 课程色 tint 透明度跟随用户的卡片透明度偏好
    val sharedBackdrop = LocalSharedCourseBackdrop.current
    val wantsGlass = sharedBackdrop != null && glassReady
    // 课程卡**不占** GlassRegistry 配额：模糊走整屏共享的 LocalSharedCourseBackdrop
    // （一次烘焙全体复用，成本极低）。此前逐卡 acquire 会把 32 个配额全部吃光
    // （周视图 40+ 张卡），排在后面的卡片连同顶栏/Hero/FAB 被静默降级成纯色，
    // 同一门课换一周显示效果就不一致。
    val glassEnabled = wantsGlass
    val tintAlpha = if (glassEnabled) {
        // 按滑条**实际定义域**线性映射：cardAlpha 可取 0.3f..1f，
        // 此前写成 (cardAlpha * 0.62f).coerceIn(0.42f, 0.72f)，
        // 于是 0.3~0.68 一整段都算出同一个 0.42，往左拖到底卡片毫无变化。
        val fraction = ((Personalization.cardAlpha - 0.3f) / 0.7f).coerceIn(0f, 1f)
        0.26f + (0.68f - 0.26f) * fraction
    } else {
        0.92f
    }

    // 玻璃卡前景由设计系统反解：浅色主题下半透明课程色叠浅背景会明显偏亮，
    // 固定白字对比度不足。这两个数按现行 `compositeLuma`（sRGB 编码通道）重量过：默认档
    // tintAlpha=0.608 × 八个课程色 × 浅色基色三段，白字 1.42–2.50:1、近黑 6.87–12.11:1
    //（旧线性光口径写的 1.3–2.2 与"7:1 以上"量的就是这族格子，只是把板算亮了）。
    // 但"深色文字"也不是恒达标：全 alpha 定义域（0.26–0.92）× 五档浅色场景里仍有 5/240 格
    // 近黑不到 AA（都在 alpha=0.92 压在最亮那团光斑上），所以这里要的是"取读数更高的那支"，
    // 不是把深色写死。
    // ⚠️ 候选必须是**固定的黑/白**，不能是 `onSurface`：深色主题下 onSurface 本身
    // 就是近白，于是"亮底用 onSurface、暗底用 White"两支都是浅色，
    // 一张亮黄色的课在深色模式下变成白字压白底（用户反馈的"黑字看不清"即此）。
    //
    // 达标路径也交给它：选墨与压实同解——两支候选墨各解一次"读到 AA 所需的最小 alpha"、
    // 取便宜的那支（与 GlassSurface / 分段控件 / 底栏共用 glassAlphaFloor 这一个解），
    // 连不透明都读不出才动课程色本身。
    // 此前这里自己写了个 0.45 的亮度阈值 + 0.78 的次级文字 alpha，两个口径都没校验结果。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val plate = legibleTintPlate(background, tintAlpha, coursePlateSceneLuma(background, darkTheme))
    val foreground = plate.foreground
    val foregroundSubtle = plate.secondaryForeground
    val plateModifierColor = plate.tint.copy(alpha = plate.alpha)
    val cornerRadius = if (Personalization.weekCornerRadiusDp > 0f) {
        Personalization.weekCornerRadiusDp.dp
    } else {
        DesignTokens.cornerCourse
    }
    val cornerShape = RoundedCornerShape(cornerRadius)
    // ⚠️ 「当前节课」高亮的判断**不能**在组合期读：
    // isCurrentProvider() 背后是每 15 秒~1 分钟跳一次的 nowTick，
    // 在组合期读它，等于每次 tick 都让屏幕上几十张课程卡全部重组一遍
    // （实测一次全量重组要 10 秒级）。改为在**绘制期**读：
    // tick 变化只让 drawWithContent 重绘一次描边，组合树完全不动。
    val errorColor = MaterialTheme.colorScheme.error
    val primaryColor = MaterialTheme.colorScheme.primary
    val deferredBorderModifier = Modifier.drawWithContent {
        drawContent()
        val isCurrent = isCurrentProvider()
        val borderColor = when {
            inConflict -> errorColor
            isCurrent -> primaryColor
            else -> null
        }
        if (borderColor != null) {
            // 用 drawRoundRect 而不是 Shape.createOutline：后者每次绘制都要新建
            // 一个 Outline 对象，几十张卡 × 每帧 = 不必要的分配。
            // cornerShape 是等半径 RoundedCornerShape，行为与 Modifier.border 一致。
            drawRoundRect(
                color = borderColor,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // 脉冲描边与缩放是两条独立线索：减少动效时只剩这一条，
        // 「就是这张」仍然要说得出。与冲突/当前节课描边叠加只是看起来更实。
        val pulseAmount = pulse.value
        if (pulseAmount > 0.001f) {
            drawRoundRect(
                color = primaryColor.copy(alpha = pulseAmount),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx()),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }

    // 材质取自 DesignTokens 之外唯一的课程卡真源 LiquidGlassMaterial.courseCard()（②V-10）：
    // 以前这里手抄了一份 lens/innerShadow/shadow，改令牌不会作用到卡片。
    // blur 参数不在卡片侧生效——它已烘焙进共享前缀，卡片只画折射。
    val cardMaterial = remember { LiquidGlassMaterial.courseCard() }
    // effect 的输入只有形状与这份常量材质，与课程色 / tint 无关，
    // 因此 effectKey 取 glassEnabled 即可：nowTick 每分钟唤起重组时缓存命中，
    // blur/lens 不会重新求值（effectKey 为 null 会让这套缓存永久失效）。
    val renderOptions = remember(glassEnabled) {
        BackdropRenderOptions(
            sampleScale = SharedBlurSampleScale,
            effectKey = { glassEnabled },
        )
    }
    // 没有壁纸（纯渐变背景）时把高光提一点，否则玻璃在平滑渐变上看不出层次
    // —— 参数取自 SleepDown 的课程卡做法（无壁纸时 max(highlight, 0.10)），
    // 这是对材质 highlightAlpha 的**有意抬高**，不是漏抄。
    val hasWallpaperBackdrop = Personalization.hasWallpaperBackdrop
    val cardHighlightAlpha =
        if (hasWallpaperBackdrop) cardMaterial.highlightAlpha else cardMaterial.highlightAlpha.coerceAtLeast(0.10f)
    // plateModifierColor 只由 cardAlpha 与课程色决定（对比度兜底是纯函数），
    // 两者都不随 nowTick 变化，因此 remember 命中时整条玻璃节点链不会被 update
    // （既不重算 effect，也不重绘）。
    val glassModifier = remember(
        sharedBackdrop, glassEnabled, renderOptions, cornerShape, plateModifierColor,
        cardMaterial, cardHighlightAlpha,
    ) {
        if (glassEnabled && sharedBackdrop != null) {
            Modifier.drawBackdrop(
                backdrop = sharedBackdrop,
                shape = { cornerShape },
                effects = {
                    // blur + vibrancy 已烘焙进共享前缀，卡片只保留折射。
                    // depthEffect 关掉（与 SleepDown 课程卡一致）：几十张卡同屏时更省，边缘也更利落
                    lens(
                        cardMaterial.lensHeight.toPx(),
                        cardMaterial.lensAmount.toPx(),
                        depthEffect = cardMaterial.depthEffect,
                    )
                },
                highlight = { Highlight.Default.copy(alpha = cardHighlightAlpha) },
                shadow = { cardMaterial.outerShadow() },
                innerShadow = { cardMaterial.innerShadow() },
                onDrawSurface = { drawRect(plateModifierColor) },
                renderOptions = renderOptions,
            )
        } else {
            // ②V-11：三条降级路径统一成"底板 + 1dp 分界描边"，别再各画各的质感。
            // 卡片没有 ColorScheme 可用，描边按底板亮度取黑白两侧。
            Modifier.degradedPlate(
                shape = cornerShape,
                tint = plateModifierColor,
                borderColor = contentOnLuma(plateModifierColor.luminance())
                    .copy(alpha = degradedPlateAlpha(cardMaterial.highlightAlpha)),
            )
        }
    }

    // 无障碍：卡片主手势是自己实现的 pointerInput（长按菜单 + 拖拽移位），
    // 这条路径不产生任何语义节点，TalkBack 既念不出内容也点不开。
    // 走 clickable 的那条分支本身带 click 语义，不要重复叠加。
    val a11yModifier = if (!hasCustomGesture) {
        Modifier
    } else {
        val description = buildString {
            append(course.displayName)
            append("，${segment.first}-${segment.last}节")
            courseCardMeta(course)?.let { append("，$it") }
            if (inConflict) append("，与其他课程时间冲突")
            if (notEveryWeek) append("，不是每周都有")
        }
        Modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = description
            onClick(label = "查看课程") { onClick(); true }
            if (onLongPress != null) {
                // 读屏没有"按下点"，传 null 让网格层按卡片右下角定位
                onLongClick(label = "打开快捷菜单") { onLongPress(null); true }
            }
            // 拖拽不是唯一的改时间方式（WCAG 2.2 · 2.5.7）：读屏用户的操作列表里
            // 需要一条能走到同一确认弹窗的替代路径。
            onMoveViaDialog?.let { move ->
                customActions = listOf(
                    CustomAccessibilityAction("移动到其他时间") { move(); true },
                )
            }
        }
    }

    Box(
        modifier = modifier
            .then(sharedModifier)
            .clip(cornerShape)
            .then(dragGestureModifier)
            .then(a11yModifier)
            .then(dragVisualModifier)
            .then(glassModifier)
            // 描边改由 deferredBorderModifier 在绘制期决定（见其注释）
            .then(deferredBorderModifier)
            .then(
                if (!hasCustomGesture) Modifier.clickable(onClick = onClick) else Modifier
            ),
    ) {
        Column(
            modifier = Modifier.padding(CourseCellContentPadding),
            verticalArrangement = Arrangement.Center,
        ) {
            // —— 文本行数预算 ——
            // 卡片高度由节次几何决定（默认 62dp，视口适配 / 24h 模式下更矮），
            // 而内容此前固定按「最多 3 行标题 + 节次 + 地点」排版：总高远超可用空间，
            // Column 居中后上下溢出，被外层的 clip 裁掉 —— 表现就是课程描述/地点
            // 显示不全、或文字像互相压住。这里按**实际卡高**逐行分配预算，
            // 并用系统字体缩放后的真实行高计算，字体放大时也不会溢出。
            val density = LocalDensity.current
            val titleStyle = MaterialTheme.typography.labelMedium
            val metaStyle = MaterialTheme.typography.labelMedium
            val titleLineHeight = with(density) {
                titleStyle.lineHeight.takeIf { it.isSp }?.toDp() ?: 16.dp
            }
            val metaLineHeight = with(density) {
                metaStyle.lineHeight.takeIf { it.isSp }?.toDp() ?: 14.dp
            }
            // 必须与上面那个 Column 的 padding 同源：写死 5.dp 的话，
            // 调内缩只调了一处，行高预算就会和真实可用高度分叉（文字再次被裁）。
            val available = (cardHeight - CourseCellContentPadding * 2).coerceAtLeast(0.dp)
            val metaText = courseCardMeta(course)
            // —— 副信息行先占位，课名才用剩下的空间 ——
            // 旧口径反过来：三行预算先给课名，默认行高下单节课卡只有 52dp 可用，
            // 三行课名正好占满，于是**教室/教师那一行在任何卡上都出不来**——
            // 而"这节课去哪上"恰恰是这张卡最该当场回答的即时信息。
            // 该砍的是课名的第三行（下面还有省略号兜着），不是副信息。
            val showMeta = metaText != null && available - titleLineHeight >= metaLineHeight
            val afterMeta = if (showMeta) available - metaLineHeight else available
            // 节次行排在副信息之后：卡片本身已经按节次跨了那么几行高，它是三者里最冗余的一行
            val showSegmentLabel = segment.last > segment.first &&
                afterMeta - titleLineHeight >= metaLineHeight
            val titleBudget = afterMeta - if (showSegmentLabel) metaLineHeight else 0.dp
            val titleLines = (titleBudget / titleLineHeight).toInt().coerceIn(1, 3)

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inConflict) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "时间冲突",
                        tint = foreground,
                        modifier = Modifier.size(DesignTokens.iconSmall),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = course.displayName,
                    style = titleStyle,
                    fontWeight = FontWeight.SemiBold,
                    color = foreground,
                    maxLines = titleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showSegmentLabel) {
                Text(
                    text = "${segment.first}-${segment.last}节",
                    style = metaStyle,
                    color = foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showMeta) {
                Text(
                    text = metaText.orEmpty(),
                    style = metaStyle,
                    color = foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 非每周课角标（T-30 / ③C-02）：这行文字在卡片里放不下，
        // 用一枚极小的实心三角传达布尔信息——它画在 3 行文本预算之外，不吃内容空间。
        if (notEveryWeek) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(7.dp),
            ) {
                drawPath(
                    path = Path().apply {
                        moveTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, 0f)
                        close()
                    },
                    color = foreground,
                )
            }
        }

        // 缩放改节次：底部拖拽把手，纵向拖动调整结束节
        if (resizeHandleVisible && onResizeDelta != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 热区 20dp：可见把手只有 10dp，拇指按在它上下沿就滑进卡片的点击区了。
                    // 只放大热区、不改可见条，卡片外观一像素不变；再高会吃掉拖动课程用的区域。
                    .height(20.dp)
                    .pointerInput(course.id, segment) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            var lastDistance = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    val distance = (pressed[1].position - pressed[0].position).getDistance()
                                    if (lastDistance > 0f && distance > 0f) {
                                        val zoom = distance / lastDistance
                                        if (zoom != 1f) onResizeDelta((zoom - 1f) * 160f)
                                    }
                                    lastDistance = distance
                                } else {
                                    val move = pressed.first().positionChange()
                                    onResizeDelta(move.y)
                                }
                                event.changes.forEach { if (it.changedToUpIgnoreConsumed()) it.consume() }
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onResizeEnd?.invoke()
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(foreground.copy(alpha = 0.35f)),
                )
            }
        }
    }
}
