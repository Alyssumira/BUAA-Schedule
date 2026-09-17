package com.buaa.schedule.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.EmptyState
import com.buaa.schedule.core.designsystem.GlassSegmentedControl
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.core.designsystem.coursePlateSceneLuma
import com.buaa.schedule.core.designsystem.legibleTintPlate
import com.buaa.schedule.core.designsystem.performTick
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.schedule.SlotStatus
import com.buaa.schedule.domain.schedule.TodayPlanner
import com.buaa.schedule.domain.schedule.WeekCalculator
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

/**
 * 日视图：顶部 Hero 显示当前/下一节课，下方为时间轴课程列表。
 *
 * @param date 当前展示的日期
 * @param onDateChange 用户翻页时回调
 */
@Composable
fun DayView(
    courses: List<Course>,
    semester: Semester?,
    timeSlots: List<TimeSlot>,
    date: LocalDate,
    modifier: Modifier = Modifier,
    onDateChange: (LocalDate) -> Unit = {},
    onCourseClick: (Course) -> Unit = {},
) {
    val today = LocalDate.now()
    val isToday = date == today
    // startLocalDate 的 getter 每次访问都跑一次 LocalDate.parse；
    // 组合期每帧都会走到这里，必须缓存（学期开学日期只在 semester 变化时才变）。
    val semesterStart = remember(semester?.startDate) {
        semester?.run { startLocalDate }
    }
    val week = semester?.let { s ->
        semesterStart?.let { start ->
            WeekCalculator.currentWeekOrNull(start, s.totalWeeks, date)
        }
    }
    val dayCourses = when {
        // 完全没设学期：按星期几降级展示，手动课程对新用户仍可见
        semester == null -> courses.filter { it.dayOfWeek == date.dayOfWeek.value }
        // 学期在、开学日期却解析失败（旧库脏数据）：TodayPlanner 此时返回 EMPTY，
        // 列表不能再放行全部周次，否则"列表有课、Hero 与进度条全空"同屏打架
        semesterStart == null || week == null -> emptyList()
        else -> courses.filter { it.dayOfWeek == date.dayOfWeek.value && it.weeks.contains(week) }
    }.sortedBy { it.startPeriod }

    // Hero 与倒计时每分钟刷新；后台（低于 STARTED）自动停表，避免不可见时继续跑协程
    val nowTickState = remember { mutableStateOf(LocalTime.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = LocalTime.now()
                delay((60_000L - (current.second * 1000L + current.nano / 1_000_000L)).coerceAtLeast(1_000L))
                nowTickState.value = LocalTime.now()
            }
        }
    }
    val plan = remember(date, courses, semester, timeSlots, nowTickState.value) {
        if (isToday) TodayPlanner.plan(courses, semester, timeSlots, date, nowTickState.value) else null
    }
    // 一行一段：一门跨午休的课（第1-2、9-10节）在列表与时间轴里是两块，
    // 每块的时间、状态、色块高度都以**自己那一段**为准。
    // 此前按 startPeriod..endPeriod 取区间，显示出来是 08:00–18:15 这种横跨整个白天的假区间；
    // 而按课程 id 建的槽位索引会被后一段覆盖，上午正在上课的那张卡写着"未开始"。
    val periodTimes = remember(timeSlots) { parsePeriodTimes(timeSlots) }
    val gapMinutes = remember(timeSlots) { periodGapMinutesOf(periodTimes) }
    val rows = remember(dayCourses, plan, periodTimes, gapMinutes) {
        buildDayRows(dayCourses, plan, periodTimes, gapMinutes)
    }

    val weekText = when {
        semester == null -> "未设置学期"
        semesterStart == null -> "学期开学日期无效，请在设置中修正"
        week == null -> if (date.isBefore(semesterStart)) "未开学" else "假期中"
        else -> "第 $week 周"
    }
    var timelineMode by rememberSaveable { mutableStateOf(false) }

    // 左右滑动翻日期：此前只有 ‹ › 两个箭头可点，
    // 而周视图早已支持横滑翻周——日视图没有对应手势会被当成 bug。
    val haptics = LocalHapticFeedback.current
    var swipeDrag by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = DesignTokens.spaceL)
            .pointerInput(date) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            swipeDrag <= -swipeThreshold -> {
                                haptics.performTick()
                                onDateChange(date.plusDays(1))
                            }
                            swipeDrag >= swipeThreshold -> {
                                haptics.performTick()
                                onDateChange(date.minusDays(1))
                            }
                        }
                        swipeDrag = 0f
                    },
                    onDragCancel = { swipeDrag = 0f },
                ) { _, dragAmount ->
                    swipeDrag += dragAmount
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onDateChange(date.minusDays(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天")
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${date.monthValue}月${date.dayOfMonth}日 · " +
                        weekdayName(date.dayOfWeek.value),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isToday) "今天 · $weekText" else weekText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { onDateChange(date.plusDays(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "后一天")
            }
        }
        // 视图切换与「回到今天」收进同一行：此前它们各占一行，
        // 加上页头已有的周次/日期，今日页顶部一共吃掉四行（用户反馈"顶栏太乱"）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 与顶栏「周课表/今日」同一件控件：此前这里是两个 TextButton，
            // 选中态只差一个颜色、也没有选中语义，而同样的切换在别处是分段控件（①C-02）
            GlassSegmentedControl(
                options = listOf("列表", "时间轴"),
                selectedIndex = if (timelineMode) 1 else 0,
                onSelect = { timelineMode = it == 1 },
            )
            Spacer(modifier = Modifier.weight(1f))
            // 「回到今天」是动作不是模式，不该混进分段里
            if (!isToday) {
                TextButton(onClick = { onDateChange(today) }) { Text("回到今天") }
            }
        }

        if (isToday && plan != null) {
            TodayHero(plan)
        }

        if (dayCourses.isEmpty()) {
            val onBreak = semesterStart != null && week == null
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyState(
                    icon = Icons.Filled.EventBusy,
                    title = if (onBreak) "假期里没有课程安排" else "这一天没有课",
                    description = if (onBreak) {
                        "学期尚未开始或已经结束，这段时间没有排课。"
                    } else {
                        "用页头两侧的箭头或者直接横滑就能换一天看。"
                    },
                    modifier = Modifier.padding(horizontal = DesignTokens.spaceL),
                ) {
                    if (isToday) {
                        Button(
                            onClick = { onDateChange(date.plusDays(1)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("查看明天") }
                    }
                }
            }
        } else {
            // 列表 / 时间轴切换也做淡入淡出，保持与首页周/日切换一致的操作质感
            Crossfade(
                targetState = timelineMode,
                // 与首页周/日切换同源：默认 1000ms 且不读 reduce-motion
                animationSpec = motionSpec<Float>(),
                modifier = Modifier.padding(top = DesignTokens.spaceM),
            ) { timeline ->
                if (timeline) {
                    DayTimelineCourseList(
                        rows = rows,
                        periodTimes = periodTimes,
                        onClick = onCourseClick,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                    ) {
                        items(rows, key = { "${it.course.id}-${it.segment.first}" }) { row ->
                            CourseTimelineCard(
                                course = row.course,
                                status = row.status,
                                startTime = row.startTime,
                                endTime = row.endTime,
                                onClick = { onCourseClick(row.course) },
                                // 换日期/删课/挪课时整批行不会瞬间替换（items 有稳定 key 才能生效）
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 日视图的一行：一门课的一个**节次段**（跨午休的课会有两行） */
private data class DayCourseRow(
    val course: Course,
    val segment: IntRange,
    val status: SlotStatus?,
    val startTime: String?,
    val endTime: String?,
)

/**
 * 把当天的课按节次表切成「课程 + 段」的行。
 *
 * 今日计划（plan）已经按段展开过每门课，但它只有**今天**才有，而日视图可以翻到任何
 * 一天；所以段在这里自己切，状态与精确到段的墙钟时间再回查 plan。
 * 同一门课的多个段各自成一行 —— 一段对应一张卡、一个色块。
 */
private fun buildDayRows(
    courses: List<Course>,
    plan: com.buaa.schedule.domain.schedule.TodayPlan?,
    periodTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    gapMinutes: (Int, Int) -> Long?,
): List<DayCourseRow> = courses.flatMap { course ->
    course.periods.toPeriodSegments(gapMinutes).map { segment ->
        val slot = plan?.slots?.firstOrNull {
            it.course.id == course.id && it.segment.first == segment.first
        }
        DayCourseRow(
            course = course,
            segment = segment,
            status = slot?.status,
            startTime = slot?.start?.let(::hhmm) ?: periodTimes[segment.first]?.first?.let(::hhmm),
            endTime = slot?.end?.let(::hhmm) ?: periodTimes[segment.last]?.second?.let(::hhmm),
        )
    }
}

/**
 * 日视图时间轴色块的课程色叠色比例：剩下的部分透出页面背景（壁纸/渐变），
 * 所以文字对比度要按合成后算。取值与推导见 [DesignTokens.dayBlockTintAlpha]。
 */
private const val BlockTintAlpha = DesignTokens.dayBlockTintAlpha

/** 日视图时间网格：按真实时间线性定位的课程时间轴 */
@Composable
private fun DayTimelineCourseList(
    rows: List<DayCourseRow>,
    periodTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    onClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (periodTimes.isEmpty()) {
        Text("节次时间未配置", modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val minStart = periodTimes.values.minOf { it.first }
    val maxEnd = periodTimes.values.maxOf { it.second }
    val totalMinutes = java.time.Duration.between(minStart, maxEnd).toMinutes().toInt().coerceAtLeast(1)
    // 1.05dp/分钟：45 分钟 ≈47dp，刚好贴着 48dp 触控下限——再矮就得靠补齐高度，
    // 而补齐会让相邻两节课互相压住。推导见 [DesignTokens.dayHeightPerMinute]。
    val heightPerMinute = DesignTokens.dayHeightPerMinute
    val totalHeight = heightPerMinute * totalMinutes.toFloat()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight),
        ) {
            rows.forEach { row ->
                val course = row.course
                val start = periodTimes[row.segment.first]?.first
                val end = periodTimes[row.segment.last]?.second
                if (start != null && end != null) {
                    val y = heightPerMinute *
                        java.time.Duration.between(minStart, start).toMinutes().toFloat()
                    val blockHeight = (heightPerMinute *
                        java.time.Duration.between(start, end).toMinutes().toFloat().coerceAtLeast(0.5f))
                        // 短节次按真实比例只有十几 dp，补到触控下限。
                        // 因为比例已经是 1.05dp/分钟，正常的 45 分钟课只多出不到 1dp，
                        // 不会出现"上一节的色块压住下一节"。
                        .coerceAtLeast(DesignTokens.minTouchTarget)
                    val blockColor = courseColor(course)
                    // 文字要看**合成后**的亮度：BlockTintAlpha 的课程色透出了页面背景。
                    // 时间轴并没有坐在卡片上——它直接挂在正文里，背后是壁纸/内置渐变，
                    // 所以场景亮度与周视图课程卡取同一个口径（最不利分块，不是 surface）。
                    // 用主题的 onSurface 时，深色主题下那是近白色，
                    // 压在亮黄/亮绿的时间块上几乎看不见。
                    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val block = legibleTintPlate(
                        blockColor,
                        BlockTintAlpha,
                        coursePlateSceneLuma(blockColor, darkTheme),
                    )
                    val onBlock = block.foreground
                    // 圆角与周视图课程格同一令牌：同一个"课程"在两个视图里不该是两种形状
                    val blockShape = RoundedCornerShape(DesignTokens.cornerCourse)
                    val blockModifier = Modifier
                        .offset(y = y)
                        .height(blockHeight)
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp)
                        .clip(blockShape)
                        .background(block.tint.copy(alpha = block.alpha), blockShape)
                        .clickable { onClick(course) }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                    Box(modifier = blockModifier, contentAlignment = Alignment.TopStart) {
                        // 色块按真实时长定位，装不下就不画那一行：
                        // 挤出去的第三行会把课程名顶没，反而更看不清。
                        Column {
                            Text(
                                text = course.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = onBlock,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (blockHeight >= 38.dp) {
                                Text(
                                    text = "${hhmm(start)}–${hhmm(end)} · ${periodLabel(row.segment)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = onBlock,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (blockHeight >= 58.dp) {
                                Text(
                                    text = course.location ?: "教室未定",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = block.secondaryForeground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 当前/下一节课 Hero 摘要卡（仅今日显示） */
@Composable
private fun TodayHero(plan: com.buaa.schedule.domain.schedule.TodayPlan) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        shape = RoundedCornerShape(DesignTokens.cornerPanel),
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth().padding(top = DesignTokens.spaceS),
    ) {
        val ongoing = plan.ongoing
        val next = plan.next
        // GlassSurface 的内容容器是 Box：多行文本必须包一层 Column，
        // 否则「正在上课 / 课程名 / 地点时段 / 还剩几分钟」会全部叠在左上角（倒计时卡文字重叠的根因）。
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
        when {
            ongoing != null -> {
                Text(
                    "正在上课",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    ongoing.course.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    placeTimeLine(ongoing.course.location, ongoing.start, ongoing.end),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                plan.minutesRemaining?.let {
                    Text(
                        "还有 $it 分钟下课",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            next != null -> {
                Text(
                    "下一节",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    next.course.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    placeTimeLine(next.course.location, next.start, next.end),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                plan.minutesToNext?.let {
                    Text(
                        "$it 分钟后开始",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            else -> {
                Text(
                    "今天没有更多课了",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }
}

/** 时间轴课程卡：左侧时间列 + 课程色带 + 信息区 */
@Composable
private fun CourseTimelineCard(
    course: Course,
    status: SlotStatus?,
    startTime: String?,
    endTime: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = courseColor(course)
    val statusLabel = when (status) {
        SlotStatus.PAST -> "已结束"
        SlotStatus.ONGOING -> "进行中"
        SlotStatus.UPCOMING -> "未开始"
        null -> null
    }
    GlassSurface(
        variant = GlassVariant.PANEL,
        shape = RoundedCornerShape(DesignTokens.cornerPanel),
        onClick = onClick,
        contentPadding = DesignTokens.spaceM,
        // 地点/状态行从无到有时整卡高度平滑长出来，而不是把下面的卡片猛地顶一下
        modifier = modifier.fillMaxWidth().animateContentSize(motionSpec<IntSize>()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 上课时间：这一列以前根本没有，卡片只写"第 1-2 节"，
            // 用户看不出到底是几点上课（用户反馈「今日界面课程不显示对应时间」）。
            // 节次表缺失时整列不画，不留一条空槽。
            if (startTime != null && endTime != null) {
                Column(
                    modifier = Modifier.width(48.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = startTime,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (status == SlotStatus.PAST) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                    )
                    Text(
                        text = endTime,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.width(DesignTokens.spaceS))
            }
            // 课程色带（进行中的课程加粗提示）
            Box(
                modifier = Modifier
                    .size(
                        width = 5.dp,
                        height = if (status == SlotStatus.ONGOING) 56.dp else 48.dp,
                    )
                    .background(accent, shape = RoundedCornerShape(3.dp)),
            )
            Spacer(modifier = Modifier.width(DesignTokens.spaceM))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = course.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (status == SlotStatus.PAST) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (statusLabel != null) {
                        Spacer(modifier = Modifier.width(DesignTokens.spaceS))
                        Box(
                            modifier = Modifier
                                .background(
                                    if (status == SlotStatus.ONGOING) accent else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(50),
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (status == SlotStatus.ONGOING) contentOn(accent) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // ③C-05：地点为空时以前整行消失，同一天的卡片于是高低不齐，
                // 而且和时间轴模式的「教室未定」是两套口径。统一成"永远有一行"，
                // 占位用更淡的墨色，别让它读起来像真有一个叫未定的教室。
                val locationText = course.location?.takeIf { it.isNotBlank() }
                Text(
                    text = locationText ?: "教室未定",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (locationText != null) 1f else 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 备注（"带实验报告""第 3 次课考试"）属于强提醒信息，
                // 以前只有点开详情 Sheet 才看得到（③C-05）
                course.remark?.takeIf { it.isNotBlank() }?.let { remark ->
                    Text(
                        text = remark,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = buildString {
                        append(periodLabel(course.periods))
                        course.teacher?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 节次 + 教师可能很长（教师名多、带职称）：限一行省略，
                    // 否则会把卡片撑高、把下面的内容挤出可视区
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 节次表 → 每节的起止墙钟时间。空表回退到默认节次；
 * 单条时间解析失败只丢这一节，不牵连整张日视图。
 */
private fun parsePeriodTimes(timeSlots: List<TimeSlot>): Map<Int, Pair<LocalTime, LocalTime>> {
    val slots = timeSlots.ifEmpty { com.buaa.schedule.domain.model.TimeSlotProfile.DEFAULT }
    return slots.mapNotNull { slot ->
        runCatching {
            slot.number to (LocalTime.parse(slot.startTime) to LocalTime.parse(slot.endTime))
        }.getOrNull()
    }.toMap()
}

/** LocalTime → "08:00"：节次表只到分钟，秒位显示出来只会让时间轴更挤 */
private fun hhmm(time: LocalTime): String =
    time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString()

/**
 * 「地点 · 08:00–09:40」一行摘要。地点为空（教务系统没抓到、走读课）时要连分隔符一起丢掉：
 * 拼成 " · 08:00–09:40" 那种悬空前缀，看着像文本被截断了（审查①V-02）。
 */
private fun placeTimeLine(location: String?, start: LocalTime, end: LocalTime): String =
    listOfNotNull(
        location?.takeIf { it.isNotBlank() },
        "${hhmm(start)}–${hhmm(end)}",
    ).joinToString(" · ")

private fun weekdayName(day: Int): String = when (day) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> ""
}
