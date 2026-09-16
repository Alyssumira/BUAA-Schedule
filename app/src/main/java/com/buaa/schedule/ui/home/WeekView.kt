package com.buaa.schedule.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.ExperimentalSharedTransitionApi
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassGovernance
import com.buaa.schedule.core.designsystem.LocalAnimatedVisibilityScope
import com.buaa.schedule.core.designsystem.LocalSharedCourseBackdrop
import com.buaa.schedule.core.designsystem.LocalSharedTransitionScope
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.performTick
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.kyant.backdrop.BackdropRenderOptions
import com.kyant.backdrop.backdrops.SharedBlurSampleScale
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

private val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val baseRowHeight = 64.dp

/**
 * 表头里的日期（参考稿是「周二 / 9/15」两行）。
 * Locale 钉死为 US：默认 locale 的 DecimalStyle 可能输出非 ASCII 数字。
 */
private val dayOfMonthFormatter =
    java.time.format.DateTimeFormatter.ofPattern("M/d", java.util.Locale.US)

/** 节次行模式下按真实时间排布的单个节次布局 */
private data class PeriodLayout(
    val number: Int,
    val startMin: Int,
    val endMin: Int,
    val top: Dp,
    val height: Dp,
)

/** 连续段顶部：按真实时间布局定位 */
private fun periodSegTop(layouts: List<PeriodLayout>, segment: IntRange, fallbackRowHeight: Dp): Dp =
    layouts.firstOrNull { it.number == segment.first }?.top ?: (fallbackRowHeight * (segment.first - 1))

/**
 * 课间空档造成的纵向偏移：空档插在 [gapAfterNumber] 之后，因此只有编号更大的行会下移。
 *
 * 这是 [buildPeriodLayouts] 之外的 placement 叠加量 —— 布局本身按「无空档」计算，
 * 动画值只在这里生效，避免每帧重建布局。
 */
private fun gapShiftFor(periodNumber: Int, gapAfterNumber: Int?, gapHeight: Dp): Dp =
    if (gapAfterNumber != null && periodNumber > gapAfterNumber) gapHeight else 0.dp

/** 连续段高度：末端底部 - 首端顶部 */
private fun periodSegHeight(layouts: List<PeriodLayout>, segment: IntRange, fallbackRowHeight: Dp): Dp {
    val bottom = layouts.firstOrNull { it.number == segment.last }
        ?.let { it.top + it.height }
        ?: (fallbackRowHeight * segment.last)
    val top = periodSegTop(layouts, segment, fallbackRowHeight)
    return (bottom - top).coerceAtLeast(18.dp)
}

/** 按“节次行均高 + 可选课间空档”计算布局；默认保持原状（每节等高），只有当前时间落在空档时插入对应 gap */
private fun buildPeriodLayouts(
    slots: List<TimeSlot>,
    rowHeight: Dp,
    gapAfterNumber: Int? = null,
    gapHeight: Dp = 0.dp,
): List<PeriodLayout> {
    val parsed = slots.mapNotNull { slot ->
        runCatching {
            Triple(slot.number, LocalTime.parse(slot.startTime), LocalTime.parse(slot.endTime))
        }.getOrNull()
    }
    if (parsed.isEmpty()) return emptyList()

    var cursor = 0.dp
    return parsed.map { (number, start, end) ->
        val startMin = start.hour * 60 + start.minute
        val endMin = end.hour * 60 + end.minute
        val layout = PeriodLayout(number, startMin, endMin, cursor, rowHeight)
        cursor += rowHeight
        if (gapAfterNumber == number) cursor += gapHeight
        layout
    }
}
/** 当前时间是否落在某两个节次之间的真实空档；若是，返回要展开 gap 的上一节次号和 gap 高度 */
private fun findIntervalGap(
    slots: List<TimeSlot>,
    now: LocalTime,
    rowHeight: Dp,
): Pair<Int, Dp>? {
    val parsed = slots.mapNotNull { slot ->
        runCatching {
            Triple(slot.number, LocalTime.parse(slot.startTime), LocalTime.parse(slot.endTime))
        }.getOrNull()
    }
    if (parsed.size < 2) return null
    val nowMin = now.hour * 60 + now.minute
    for (i in 0 until parsed.size - 1) {
        val prev = parsed[i]
        val next = parsed[i + 1]
        val prevEnd = prev.third.hour * 60 + prev.third.minute
        val nextStart = next.second.hour * 60 + next.second.minute
        if (nowMin >= prevEnd && nowMin < nextStart) {
            val gapMinutes = (nextStart - prevEnd).coerceAtLeast(1)
            val gapHeight = rowHeight * (gapMinutes / 45f)
            return prev.first to gapHeight
        }
    }
    return null
}

private val timeColumnWidth = 48.dp
/** 紧凑模式下可见的日期列数（5 日视口） */
private const val COMPACT_VISIBLE_DAYS = 5

/**
 * 周视图。信息密度优先：课程格不使用实时模糊，仅低透明色块 + 细描边。
 *
 * 响应式视口：窄屏（列宽不足时）按 5 日宽度设定列宽并支持横向滑动，
 * 时间列固定、星期栏与日期列共享同一滚动状态并初始定位到“今天”；
 * 宽屏仍 7 列满宽。
 *
 * @param displayWeek 当前展示的教学周；null 表示没有周次信息（展示全部课程）
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
    modifier: Modifier = Modifier,
    onBrowseWeekChange: (Int?) -> Unit = {},
    onCourseClick: (Course) -> Unit = {},
    conflictCourseIds: Set<Long> = emptySet(),
    specialDays: List<com.buaa.schedule.domain.model.SpecialDay> = emptyList(),
    onCourseMove: ((course: Course, newDayIndex: Int, newStartPeriod: Int, thisWeekOnly: Boolean) -> Unit)? = null,
    onCourseResize: ((course: Course, newPeriods: List<Int>) -> Unit)? = null,
    onCourseDelete: ((course: Course) -> Unit)? = null,
) {
    val slots = if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT
    // 兼容历史脏数据：总周数限幅，避免跳周列表物化超大列表
    val totalWeeks = (semester?.totalWeeks ?: 20).coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)
    val today = LocalDate.now()
    // 每分钟对齐的 tick：作为 State 传入，只有“当前课高亮/时间线”读取该状态，
    // 普通课程格不会随每分钟 tick 全量重组；后台（低于 STARTED）自动停表。
    val nowTickState = remember { mutableStateOf(LocalTime.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // 15 秒一更，当前时间线更接近实时；只触发时间线/当前课区域重组
                delay(15_000L)
                nowTickState.value = LocalTime.now()
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
                // 外部改周（顶部翻周按钮 / 跳周弹窗 / 回到本周）时同步 Pager
                LaunchedEffect(displayWeek, currentWeek) {
                    if (pagerState.isScrollInProgress) return@LaunchedEffect
                    val target = ((displayWeek ?: currentWeek ?: 1) - 1).coerceIn(0, totalWeeks - 1)
                    if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
                }
                // 只在滑动落定后回写，避免拖拽过程中每帧回调触发整表过滤
                val haptics = LocalHapticFeedback.current
                LaunchedEffect(pagerState.currentPage) {
                    val week = pagerState.currentPage + 1
                    if (week != displayWeek) {
                        onBrowseWeekChange(week)
                        haptics.performTick()
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    WeekGrid(
                        courses = courses,
                        slots = slots,
                        weekForContent = page + 1,
                        currentWeek = currentWeek,
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
                    )
                }
            } else {
                WeekGrid(
                    courses = courses,
                    slots = slots,
                    weekForContent = null,
                    currentWeek = currentWeek,
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
                )
            }
        }
    }
}

/**
 * 单周课表网格。被 [WeekView] 的翻周 Pager 与“无周次信息”分支复用。
 *
 * @param weekForContent 本页对应的教学周；null 表示不过滤周次（学期未设置时展示全部课程）
 * @param onCourseMove 课程拖拽落点的回调（dayIndex 0..6，newStartPeriod 为新的起始节）；
 *   传 null 时禁用拖拽
 */
@Composable
private fun WeekGrid(
    courses: List<Course>,
    slots: List<TimeSlot>,
    weekForContent: Int?,
    currentWeek: Int?,
    today: LocalDate,
    nowTickState: State<LocalTime>,
    compactScroll: Boolean,
    compactDayWidth: Dp,
    dayScrollState: ScrollState,
    conflictCourseIds: Set<Long>,
    onCourseClick: (Course) -> Unit,
    onCourseMove: ((course: Course, newDayIndex: Int, newStartPeriod: Int, thisWeekOnly: Boolean) -> Unit)? = null,
    onCourseResize: ((course: Course, newPeriods: List<Int>) -> Unit)? = null,
    onCourseDelete: ((course: Course) -> Unit)? = null,
) {
    val coursesForContent = remember(courses, weekForContent) {
        if (weekForContent == null) {
            courses
        } else {
            courses.filter { it.weeks.contains(weekForContent) }
        }
    }
    // 每列取数预聚合一次：此前是在 7 列的循环里各 filter 一遍，
    // 每次重组（15s tick、拖拽每一帧）都要重做 8 次过滤。
    val coursesByDay = remember(coursesForContent) { coursesForContent.groupBy { it.dayOfWeek } }
    // 节次起止时间预解析：slotRange 每次调用是 2 次线性扫描 + 2 次 LocalTime.parse，
    // 而它被每张卡的「当前节课」判断在绘制期调用（R5 F-23）
    val slotIndex = remember(slots) { TimeSlotIndex(slots) }
    // 连续节次段同样只切一次：此前在「7 列 × 每门课 × 每次重组」里各算一遍，
    // 拖动期间约 40 个 list+IntRange/帧（R5 F-23）
    // 切段按墙钟间隔而不是节次号相邻，否则第 5、6 节会画成一张横跨午休的卡（R5 F-30）
    val segmentsByCourse = remember(coursesForContent, slotIndex) {
        coursesForContent.associate { it.id to it.periods.toPeriodSegments(slotIndex::gapMinutes) }
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
    val hourHeight = 56.dp * Personalization.weekRowScale
    val timeWindow: Pair<Int, Int> = remember(coursesForContent, slotIndex, timeMode) {
        if (timeMode) {
            val mins = buildList {
                coursesForContent.forEach { course ->
                    slotIndex.range(course.startPeriod..course.startPeriod).first.let {
                        add(it.hour * 60 + it.minute)
                    }
                    slotIndex.range(course.endPeriod..course.endPeriod).second.let {
                        add(it.hour * 60 + it.minute)
                    }
                }
                if (isEmpty()) {
                    add(8 * 60)
                    add(22 * 60)
                }
            }
            (mins.min() / 60) * 60 to ((mins.max() + 59) / 60) * 60
        } else {
            0 to 0
        }
    }
    // 课间空档只与"现在落在哪两节之间"有关，和 tick 的具体取值无关。
    // 直接在组合期读 nowTickState.value 会让整个网格每 15 秒全量重组一次
    // （8 次 filter + buildPeriodLayouts + 全部卡片的 modifier 重算）。
    // derivedStateOf 把读取关进派生状态：tick 变了但空档没变时不会触发重组。
    val targetGap by remember(slots, rowHeight, timeMode) {
        derivedStateOf {
            if (timeMode) null else findIntervalGap(slots, nowTickState.value, rowHeight)
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
        animationSpec = tween(300),
    )
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
    // —— 长按菜单 / 缩放改节次 / 详情底部弹层 ——
    var menuFor by remember { mutableStateOf<CourseMenuRequest?>(null) }
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
    val rowHeightPx = with(density) { rowHeight.toPx() }.toInt()

    val overscrollEffect = rememberOverscrollEffect()
    Column(modifier = Modifier.fillMaxSize()) {
    // 网格整体可纵向滚动，晚间节次不被屏幕裁掉；显式挂 overscroll 物理（拉伸/回弹由系统效果实现）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .overscroll(overscrollEffect)
            .verticalScroll(rememberScrollState()),
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
                    Row(modifier = Modifier.fillMaxHeight()) {
                        dayNames.forEachIndexed { index, _ ->
                            val day = index + 1
                            val dayCourses = coursesByDay[day].orEmpty()
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
                                // 拖拽落点高亮：画在被拖卡片原本所在列，横向按格子宽度平移
                                drag?.let { d ->
                                    if (d.originDayIndex == index && dayWidthPx > 0) {
                                        val span = d.segment.last - d.segment.first + 1
                                        Box(
                                            modifier = Modifier
                                                .offset(
                                                    x = with(density) { ((d.targetDayIndex - d.originDayIndex) * dayWidthPx).toDp() },
                                                    y = rowHeight * (d.targetStartPeriod - 1),
                                                )
                                                .size(
                                                    width = with(density) { dayWidthPx.toDp() },
                                                    height = rowHeight * span,
                                                )
                                                .border(
                                                    width = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                                    shape = RoundedCornerShape(DesignTokens.cornerCourse),
                                                )
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                        )
                                    }
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
                                                cardHeight = hDp,
                                                inConflict = course.id in conflictCourseIds,
                                                isCurrentProvider = {
                                                    weekForContent == currentWeek &&
                                                        course.dayOfWeek == today.dayOfWeek.value &&
                                                        nowTickState.value >= s && nowTickState.value < e
                                                },
                                                glassReady = glassReady,
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
                                                        )
                                                    }
                                                } else null,
                                                onDragDelta = if (onCourseMove != null) { amount ->
                                                    drag?.let { d ->
                                                        val span = d.segment.last - d.segment.first + 1
                                                        val maxStart = (slots.size - span + 1).coerceAtLeast(1)
                                                        val targetDay = (d.originDayIndex +
                                                            ((d.totalOffset.x + amount.x) / dayWidthPx).roundToInt())
                                                            .coerceIn(0, dayNames.size - 1)
                                                        val targetStart = (d.originStartPeriod +
                                                            ((d.totalOffset.y + amount.y) / rowHeightPx).roundToInt())
                                                            .coerceIn(1, maxStart)
                                                        drag = d.copy(
                                                            totalOffset = d.totalOffset + amount,
                                                            targetDayIndex = targetDay,
                                                            targetStartPeriod = targetStart,
                                                        )
                                                    }
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
                                                    .padding(horizontal = 1.dp, vertical = 1.dp),
                                                onClick = { onCourseClick(course) },
                                                onLongPress = { menuFor = CourseMenuRequest(course, segment) },
                                                menuOpen = menuFor?.course?.id == course.id && menuFor?.segment == segment,
                                                onMenuDismiss = {
                                                    if (menuFor?.course?.id == course.id && menuFor?.segment == segment) {
                                                        menuFor = null
                                                    }
                                                },
                                                onDetail = { detailFor = course },
                                                onEdit = { onCourseClick(course) },
                                                onResize = null,
                                                onDelete = if (onCourseDelete != null) {
                                                    { pendingDelete = course }
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
                                        val cardHeight = periodSegHeight(periodLayouts, segment, rowHeight) - 2.dp
                                        // 静态基准 top 只算一次，空档的动画偏移留到布局期再叠加
                                        val baseTopPx = with(density) {
                                            (periodSegTop(periodLayouts, segment, rowHeight) + 1.dp).roundToPx()
                                        }
                                        CourseCell(
                                            course = course,
                                            segment = segment,
                                            cardHeight = cardHeight,
                                            inConflict = course.id in conflictCourseIds,
                                            isCurrentProvider = {
                                                weekForContent == currentWeek &&
                                                    course.dayOfWeek == today.dayOfWeek.value &&
                                                    slotIndex.range(segment).let { (s, e) ->
                                                        nowTickState.value >= s && nowTickState.value < e
                                                    }
                                            },
                                            glassReady = glassReady,
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
                                                    )
                                                }
                                            } else null,
                                            onDragDelta = if (onCourseMove != null) { amount ->
                                                drag?.let { d ->
                                                    val span = d.segment.last - d.segment.first + 1
                                                    val maxStart = (slots.size - span + 1).coerceAtLeast(1)
                                                    val targetDay = (d.originDayIndex +
                                                        ((d.totalOffset.x + amount.x) / dayWidthPx).roundToInt())
                                                        .coerceIn(0, dayNames.size - 1)
                                                    val targetStart = (d.originStartPeriod +
                                                        ((d.totalOffset.y + amount.y) / rowHeightPx).roundToInt())
                                                        .coerceIn(1, maxStart)
                                                    drag = d.copy(
                                                        totalOffset = d.totalOffset + amount,
                                                        targetDayIndex = targetDay,
                                                        targetStartPeriod = targetStart,
                                                    )
                                                }
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
                                                .padding(horizontal = 1.dp, vertical = 1.dp),
                                            onClick = { onCourseClick(course) },
                                            onLongPress = { menuFor = CourseMenuRequest(course, segment) },
                                            menuOpen = menuFor?.course?.id == course.id && menuFor?.segment == segment,
                                            onMenuDismiss = {
                                                if (menuFor?.course?.id == course.id && menuFor?.segment == segment) {
                                                    menuFor = null
                                                }
                                            },
                                            onDetail = { detailFor = course },
                                            onEdit = { onCourseClick(course) },
                                            onResize = {
                                                menuFor = null
                                                resizeFor = ResizeState(course, segment, segment.first, segment.last)
                                            },
                                            onDelete = if (onCourseDelete != null) {
                                                    { pendingDelete = course }
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
                                                        val newEnd = r.newEnd(rowHeightPx, slots.size)
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
                    // 周次有值但本周没有课时，在课程区域给出明确空态，
                    // 避免只剩时间轴空白让人以为渲染坏了
                    if (weekForContent != null && coursesForContent.isEmpty()) {
                        Box(
                            modifier = Modifier.matchParentSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "本周没有课",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        }

        // 拖拽落点确认弹窗：确认后才真正改课
        pendingMove?.let { request ->
            AlertDialog(
                onDismissRequest = { pendingMove = null },
                title = { Text("确认移动课程？") },
                text = {
                    Text(
                        "将「${request.course.name}」移到 ${dayNames[request.newDayIndex]} 第${request.newStartPeriod}节起。"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = request
                            pendingMove = null
                            onCourseMove?.invoke(target.course, target.newDayIndex, target.newStartPeriod, false)
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
                                onCourseMove?.invoke(target.course, target.newDayIndex, target.newStartPeriod, true)
                            },
                        ) { Text("仅本周") }
                    }
                },
            )
        }

        pendingDelete?.let { course ->
            AlertDialog(
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
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                },
            )
        }

        detailFor?.let { course ->
            CourseDetailSheet(
                course = course,
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
 * 节次起止时间的预解析索引。
 *
 * 取代此前的 `slotRange`：每次调用要 2 次线性扫描 + 2 次 `LocalTime.parse`，
 * 而「当前节课」判断是**在每张卡片的绘制期**跑的 —— 40 张卡 × 每次重绘 =
 * 160 次/帧的解析与扫描（R5 F-23）。构造一次即可整帧复用。
 */
private class TimeSlotIndex(slots: List<TimeSlot>) {
    private val starts: Map<Int, LocalTime> = slots.mapNotNull { slot ->
        runCatching { slot.number to LocalTime.parse(slot.startTime) }.getOrNull()
    }.toMap()
    private val ends: Map<Int, LocalTime> = slots.mapNotNull { slot ->
        runCatching { slot.number to LocalTime.parse(slot.endTime) }.getOrNull()
    }.toMap()

    /** 与原实现同口径：节次缺失或时间非法时退回 08:00–22:15 */
    fun range(segment: IntRange): Pair<LocalTime, LocalTime> =
        (starts[segment.first] ?: LocalTime.of(8, 0)) to (ends[segment.last] ?: LocalTime.of(22, 15))

    /** 前一节下课 → 后一节上课的间隔分钟数；任一节缺时间时返回 null */
    fun gapMinutes(from: Int, to: Int): Long? {
        val end = ends[from] ?: return null
        val begin = starts[to] ?: return null
        return ChronoUnit.MINUTES.between(end, begin)
    }
}

/** 一次进行中的拖拽：被拿起的卡片、累计位移与取整后的目标格子 */
private data class CourseDragState(
    val course: Course,
    val segment: IntRange,
    val originDayIndex: Int,
    val originStartPeriod: Int,
    val totalOffset: Offset,
    val targetDayIndex: Int,
    val targetStartPeriod: Int,
)

/** 拖拽松手后的待确认移动请求 */
private data class CourseMoveRequest(
    val course: Course,
    val newDayIndex: Int,
    val newStartPeriod: Int,
    val week: Int?,
)

/** 长按菜单请求：一门课 + 当前被长按的连续节次段 */
private data class CourseMenuRequest(
    val course: Course,
    val segment: IntRange,
)

/** 缩放改节次中的临时状态：记录原始起止节与累计纵向位移 */
private data class ResizeState(
    val course: Course,
    val segment: IntRange,
    val originalStart: Int,
    val originalEnd: Int,
    val deltaY: Float = 0f,
) {
    /** 当前拖动对应的新结束节 */
    fun newEnd(rowHeightPx: Int, maxSection: Int): Int {
        val delta = (deltaY / rowHeightPx).roundToInt()
        return (originalEnd + delta).coerceIn(originalStart, maxSection.coerceAtLeast(originalStart))
    }

    /** 松手时生成的新 periods（连续段） */
    fun newPeriods(rowHeightPx: Int, maxSection: Int): List<Int> {
        val end = newEnd(rowHeightPx, maxSection)
        return (originalStart..end).toList()
    }
}

/** 把某段连续节次替换为新范围，保留课程其它非连续片段并去重排序 */
private fun resizeCoursePeriods(course: Course, segment: IntRange, newStart: Int, newEnd: Int): List<Int> =
    (course.periods.filter { it !in segment } + (newStart..newEnd))
        .distinct()
        .sorted()

/** 当前时间指示线：独立组合作用域，每分钟只重组这一条线 */
@Composable
private fun NowLine(
    visible: Boolean,
    startMin: Int,
    endMin: Int,
    totalHeight: Dp,
    nowTickState: State<LocalTime>,
) {
    if (!visible) return
    val total = endMin - startMin
    val fraction = if (total <= 0) 0f else {
        val now = nowTickState.value.let { it.hour * 60 + it.minute }
        ((now - startMin).toFloat() / total).coerceIn(0f, 1f)
    }
    if (fraction <= 0f || fraction >= 1f) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .offset(y = totalHeight * fraction)
            .background(Color.Red.copy(alpha = 0.7f)),
    )
}

/** 节次行模式的时间指示线：按行号 + 行内时间比例定位，和左侧每节一行对齐 */
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

    if (fraction <= 0f || fraction >= 1f) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .offset(y = totalHeight * fraction)
            .background(Color.Red.copy(alpha = 0.7f)),
    )
}

/** 24h 时间轴模式的小时刻度列 */
@Composable
private fun HourLabels(startHour: Int, endHour: Int, hourHeight: Dp) {
    Column(modifier = Modifier.fillMaxSize()) {
        (startHour until endHour).forEach { hour ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(hourHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%02d:00".format(hour),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
            .padding(horizontal = 4.dp)
            // 参考稿：整行是一个浅色圆角容器，「节次」一格 + 7 天
            .clip(RoundedCornerShape(DesignTokens.cornerPanel))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧与时间列同宽，标出这一列是"节次"
        Box(
            modifier = Modifier.width(timeColumnWidth),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "节次",
                style = MaterialTheme.typography.labelSmall,
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
                                .padding(vertical = 2.dp)
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
                                        style = MaterialTheme.typography.labelSmall,
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
                                    style = MaterialTheme.typography.labelSmall,
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

@Composable
private fun CourseCell(
    course: Course,
    segment: IntRange,
    /** 卡片实际高度（与 modifier 上的 .height() 一致），用于按可用空间分配文本行数 */
    cardHeight: Dp,
    inConflict: Boolean,
    isCurrentProvider: () -> Boolean,
    /** 玻璃档位与渲染能力：由网格层算一次传下来，不在每张卡里各查一遍 */
    glassReady: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    dragged: Boolean = false,
    dragOffset: Offset = Offset.Zero,
    onDragStart: (() -> Unit)? = null,
    onDragDelta: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    menuOpen: Boolean = false,
    onMenuDismiss: () -> Unit = {},
    onDetail: () -> Unit = {},
    onEdit: () -> Unit = {},
    onResize: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    resizeHandleVisible: Boolean = false,
    onResizeDelta: ((Float) -> Unit)? = null,
    onResizeEnd: (() -> Unit)? = null,
) {
    val background = courseColor(course)
    // 共享元素转场：课程卡与编辑器使用同一 key，由 MainActivity 的 SharedTransitionLayout 驱动
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalAnimatedVisibilityScope.current
    val sharedModifier: Modifier = if (sharedScope != null && animScope != null) {
        @OptIn(ExperimentalSharedTransitionApi::class)
        with(sharedScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = "course_${course.id}"),
                animatedVisibilityScope = animScope,
            )
        }
    } else {
        Modifier
    }
    // 手势：单击打开课程；长按打开快捷菜单；长按后继续移动且超过触摸阈值才进入拖拽。
    // 这样“长按菜单”和“长按拖移”可以共存：原地松手=菜单，移动=拖拽。
    val viewConfiguration = LocalViewConfiguration.current
    val haptics = LocalHapticFeedback.current
    val hasDrag = onDragStart != null && onDragDelta != null && onDragEnd != null
    val hasCustomGesture = onLongPress != null || hasDrag
    val dragGestureModifier = if (hasCustomGesture) {
        Modifier.pointerInput(course.id, segment, onLongPress != null, hasDrag) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val longPress = awaitLongPressOrCancellation(down.id)
                if (longPress == null) {
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (up != null && up.changedToUpIgnoreConsumed() && !up.isConsumed) {
                        up.consume()
                        onClick()
                    }
                    return@awaitEachGesture
                }
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (onLongPress != null) onLongPress()
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
                    if (dragging) onDragEnd?.invoke()
                }
            }
        }
    } else {
        Modifier
    }
    val dragVisualModifier = if (dragged) {
        Modifier
            .zIndex(3f)
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .graphicsLayer {
                scaleX = 1.06f
                scaleY = 1.06f
                shadowElevation = 18f
            }
    } else {
        Modifier
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
        // 对齐 SleepDown courseCard 0.52 基准，随用户透明度偏好缩放
        (Personalization.cardAlpha * 0.62f).coerceIn(0.42f, 0.72f)
    } else {
        0.92f
    }

    // 玻璃卡前景按“合成后亮度”决定黑/白：浅色主题下半透明课程色叠浅背景会明显偏亮，
    // 固定白字对比度不足（实测 1.3-1.9:1），改用深色文字可达 9:1+
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgLum = if (darkTheme) 0.12f else 0.86f
    val effectiveLum = background.luminance() * tintAlpha + bgLum * (1f - tintAlpha)
    val foreground = if (effectiveLum > 0.52f) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.White
    }
    val foregroundSubtle = foreground.copy(alpha = 0.78f)
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
    }

    // effect 的输入只有形状与固定的折射参数，与课程色 / tint 无关，
    // 因此 effectKey 取 glassEnabled 即可：nowTick 每分钟唤起重组时缓存命中，
    // blur/lens 不会重新求值（effectKey 为 null 会让这套缓存永久失效）。
    val renderOptions = remember(glassEnabled) {
        BackdropRenderOptions(
            sampleScale = SharedBlurSampleScale,
            effectKey = { glassEnabled },
        )
    }
    // 没有壁纸（纯渐变背景）时把高光提一点，否则玻璃在平滑渐变上看不出层次
    // —— 参数取自 SleepDown 的课程卡做法（无壁纸时 max(highlight, 0.10)）
    val hasWallpaperBackdrop = Personalization.hasWallpaperBackdrop
    val cardHighlightAlpha = if (hasWallpaperBackdrop) 0.045f else 0.10f
    // tintAlpha 只由 cardAlpha 决定、background 只由课程决定，两者都不随 nowTick 变化，
    // 因此 remember 命中时整条玻璃节点链不会被 update（既不重算 effect，也不重绘）。
    val glassModifier = remember(
        sharedBackdrop, glassEnabled, renderOptions, cornerShape, tintAlpha, background, cardHighlightAlpha,
    ) {
        if (glassEnabled && sharedBackdrop != null) {
            Modifier.drawBackdrop(
                backdrop = sharedBackdrop,
                shape = { cornerShape },
                effects = {
                    // blur + vibrancy 已烘焙进共享前缀，卡片只保留折射。
                    // depthEffect 关掉（与 SleepDown 课程卡一致）：几十张卡同屏时更省，边缘也更利落
                    lens(10.dp.toPx(), 20.dp.toPx(), depthEffect = false)
                },
                highlight = { Highlight.Default.copy(alpha = cardHighlightAlpha) },
                shadow = { Shadow.Default },
                // 内阴影半径必须小：24dp 会把整张卡糊成一团柔光，玻璃感全无
                innerShadow = { InnerShadow(radius = 5.dp, alpha = 0.10f) },
                onDrawSurface = { drawRect(background.copy(alpha = tintAlpha)) },
                renderOptions = renderOptions,
            )
        } else {
            Modifier.background(background.copy(alpha = tintAlpha))
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
            course.location?.takeIf { it.isNotBlank() }?.let { append("，$it") }
            if (inConflict) append("，与其他课程时间冲突")
        }
        Modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = description
            onClick(label = "查看课程") { onClick(); true }
            if (onLongPress != null) {
                onLongClick(label = "打开快捷菜单") { onLongPress(); true }
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
            modifier = Modifier.padding(5.dp),
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
            val metaStyle = MaterialTheme.typography.labelSmall
            val titleLineHeight = with(density) {
                titleStyle.lineHeight.takeIf { it.isSp }?.toDp() ?: 16.dp
            }
            val metaLineHeight = with(density) {
                metaStyle.lineHeight.takeIf { it.isSp }?.toDp() ?: 14.dp
            }
            var remaining = (cardHeight - 5.dp * 2).coerceAtLeast(0.dp)
            val titleLines = when {
                remaining >= titleLineHeight * 3 -> 3
                remaining >= titleLineHeight * 2 -> 2
                else -> 1
            }
            remaining -= titleLineHeight * titleLines
            val showSegmentLabel = segment.last > segment.first && remaining >= metaLineHeight
            if (showSegmentLabel) remaining -= metaLineHeight
            val locationText = course.location?.takeIf { it.isNotBlank() }
            val showLocation = locationText != null && remaining >= metaLineHeight

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inConflict) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "时间冲突",
                        tint = foreground,
                        modifier = Modifier.size(13.dp),
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
            if (showLocation) {
                Text(
                    text = locationText.orEmpty(),
                    style = metaStyle,
                    color = foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 缩放改节次：底部拖拽把手，纵向拖动调整结束节
        if (resizeHandleVisible && onResizeDelta != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(foreground.copy(alpha = 0.35f))
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
            )
        }

        if (menuOpen) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = onMenuDismiss,
            ) {
                DropdownMenuItem(
                    text = { Text("详情") },
                    onClick = {
                        onMenuDismiss()
                        onDetail()
                    },
                )
                DropdownMenuItem(
                    text = { Text("编辑") },
                    onClick = {
                        onMenuDismiss()
                        onEdit()
                    },
                )
                if (onResize != null) {
                    DropdownMenuItem(
                        text = { Text("调整时长") },
                        onClick = {
                            onMenuDismiss()
                            onResize()
                        },
                    )
                }
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            onMenuDismiss()
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
