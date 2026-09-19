package com.buaa.schedule.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.EmptyState
import com.buaa.schedule.core.designsystem.GlassSegmentedControl
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.TodayTimelineStrip
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.core.designsystem.coursePlateSceneLuma
import com.buaa.schedule.core.designsystem.dayFractionOfMinute
import com.buaa.schedule.core.designsystem.dayTimelineSegments
import com.buaa.schedule.core.designsystem.legibleTintPlate
import com.buaa.schedule.core.designsystem.LocalReduceMotion
import com.buaa.schedule.core.designsystem.MotionTokens
import com.buaa.schedule.core.designsystem.performTick
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.core.designsystem.motionSpecFor
import com.buaa.schedule.core.designsystem.motionSpringFor
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.joinMeta
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.model.weekdayLabel
import com.buaa.schedule.domain.schedule.SlotStatus
import com.buaa.schedule.domain.schedule.TodayPlanner
import com.buaa.schedule.domain.schedule.WeekCalculator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 日视图：顶部 Hero 显示当前/下一节课，下方为时间轴课程列表。
 *
 * @param date 当前展示的日期
 * @param today 真实"今天"：由 ViewModel 的跨午夜滴答给，不在这里 `LocalDate.now()` ——
 *   组合期读时钟不是快照订阅，跨过零点没有任何东西会因此重组，
 *   「回到今天」与日/周两视图的今日高亮会一起停在昨天。
 * @param onDateChange 用户翻页时回调
 */
@Composable
fun DayView(
    courses: List<Course>,
    semester: Semester?,
    timeSlots: List<TimeSlot>,
    date: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onDateChange: (LocalDate) -> Unit = {},
    onCourseClick: (Course) -> Unit = {},
) {
    // Hero 与倒计时每分钟刷新；后台（低于 STARTED）自动停表，避免不可见时继续跑协程。
    // **醒来第一件事是发布、第二件事才是等下一次边界**：顺序反过来（旧写法先 delay
    // 再赋值）时，从锁屏/后台回到前台的那一帧读到的还是 remember 那一次的旧时刻，
    // 要等到下一个整分钟才翻面 —— 最多 60 秒里 Hero 高亮着上一节课。
    val nowTickState = remember { mutableStateOf(LocalTime.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // 一次读取同时用于发布与算延时：读两次的话，
                // 两次之间正好跨过边界就会发布旧时刻、却按下一次边界算延时，翻面晚一分钟
                val current = LocalTime.now()
                nowTickState.value = current
                delay(nextTickDelayMillis(current))
            }
        }
    }
    // startLocalDate 的 getter 每次访问都跑一次 LocalDate.parse；
    // 组合期每帧都会走到这里，必须缓存（学期开学日期只在 semester 变化时才变）。
    val semesterStart = remember(semester?.startDate) {
        semester?.run { startLocalDate }
    }
    var timelineMode by rememberSaveable { mutableStateOf(false) }

    // ── 横滑翻日期 ──
    // 此前只有 ‹ › 两个箭头可点，而周视图早已支持横滑翻周——日视图没有对应手势会被当成 bug。
    // 补上手势以后又留下第二个断裂：dragAmount 只累到阈值做判定，72dp 以内画面纹丝不动、
    // 松手整页硬切（H5），而翻周是 Pager 的跟手 + 惯性。这里补齐同一条因果链的两端：
    // 手势期间正文跟手位移，换天以后新的一天从同一方向滑入（§2.4 shared axis）。
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val dragScope = rememberCoroutineScope()
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    var swipeDrag by remember { mutableFloatStateOf(0f) }
    val dragShift = remember { Animatable(0f) }
    val settleDrag: () -> Unit = {
        dragScope.launch { dragShift.animateTo(0f, motionSpringFor(reduceMotion)) }
    }

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
                        settleDrag()
                    },
                    onDragCancel = {
                        swipeDrag = 0f
                        settleDrag()
                    },
                ) { _, dragAmount ->
                    swipeDrag += dragAmount
                    // reduce-motion 下正文不做位移，阈值判定照旧
                    if (!reduceMotion) {
                        dragScope.launch { dragShift.snapTo(dampedDragOffset(swipeDrag, swipeThreshold)) }
                    }
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
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { translationX = dragShift.value },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 页头只做淡入淡出、不跟着横移：这一格左右就是两个箭头，
                // 旧日期还没退净时两行字会在 1/8 屏宽里叠在一起
                Crossfade(
                    targetState = date,
                    animationSpec = motionSpec<Float>(),
                ) { day ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 档位对齐 HomeScreen 紧凑顶栏第一行（titleMedium 标题 + labelMedium 副行）：
                        // 此前这里用 22sp 的 titleLarge，「今日课表」下面又压出一个更大的标题。
                        Text(
                            text = "${day.monthValue}月${day.dayOfMonth}日 · " +
                                weekdayName(day.dayOfWeek.value),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (day == today) "今天 · ${weekTextFor(semester, semesterStart, day)}"
                            else weekTextFor(semester, semesterStart, day),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
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
            if (date != today) {
                TextButton(onClick = { onDateChange(today) }) { Text("回到今天") }
            }
        }

        AnimatedContent(
            targetState = date,
            transitionSpec = { dayAxisTransition(reduceMotion, forward = targetState.isAfter(initialState)) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer { translationX = dragShift.value },
        ) { day ->
            DayScreen(
                date = day,
                today = today,
                courses = courses,
                semester = semester,
                semesterStart = semesterStart,
                timeSlots = timeSlots,
                now = nowTickState.value,
                timelineMode = timelineMode,
                onDateChange = onDateChange,
                onCourseClick = onCourseClick,
            )
        }
    }
}

/** 跟手只映 0.35 倍：整幅跟随会让正文与两侧的箭头脱开，读成"箭头没跟着走" */
private const val DayDragFollowRatio = 0.35f

/**
 * 手势累计位移 → 正文位移。超过阈值的部分不再增加：
 * 继续拖已经不携带新信息，而松手回弹的距离一旦跟着变大，就成了新的干扰。
 */
private fun dampedDragOffset(totalDrag: Float, threshold: Float): Float =
    totalDrag.coerceIn(-threshold, threshold) * DayDragFollowRatio

/**
 * 翻日期的共享轴：横向 1/8 屏宽 + 淡入淡出，进退方向相反（§2.4：两天之间有前后关系）。
 *
 * 时长取 [MotionTokens.DURATION_SNAP]（140ms）而不是区块级的 260：翻日期是本 App 最高频的
 * 手势，§2.6 要求这种交互"即时、极短（≤150ms）、无需注意"。
 * 转场 lambda 不在组合里求值，拿不到 `LocalReduceMotion`，所以开关由调用方读出来传进来（§2.7）。
 */
private fun dayAxisTransition(reduceMotion: Boolean, forward: Boolean): ContentTransform {
    if (reduceMotion) {
        return ContentTransform(
            targetContentEnter = EnterTransition.None,
            initialContentExit = ExitTransition.None,
        )
    }
    // 往后翻：新的一天从右侧进场、旧的退向左侧；往前翻整体反向
    val enterShift = if (forward) 1 else -1
    val fadeSpec = motionSpecFor<Float>(reduceMotion, MotionTokens.DURATION_SNAP)
    val slideSpec = motionSpecFor<IntOffset>(reduceMotion, MotionTokens.DURATION_SNAP)
    return ContentTransform(
        targetContentEnter = slideInHorizontally(slideSpec, initialOffsetX = { enterShift * it / 8 }) +
            fadeIn(fadeSpec),
        initialContentExit = slideOutHorizontally(slideSpec, targetOffsetX = { -enterShift * it / 8 }) +
            fadeOut(fadeSpec),
    )
}

/** 页头副行的周次口径：Crossfade 里的每一天都要自己算，不能沿用外层那一天的结果 */
private fun weekTextFor(semester: Semester?, semesterStart: LocalDate?, date: LocalDate): String {
    val week = semester?.let { s ->
        semesterStart?.let { start -> WeekCalculator.currentWeekOrNull(start, s.totalWeeks, date) }
    }
    return when {
        semester == null -> "未设置学期"
        semesterStart == null -> "学期开学日期无效，请在设置中修正"
        week == null -> if (date.isBefore(semesterStart)) "未开学" else "假期中"
        else -> "第 $week 周"
    }
}

/**
 * 一天版面：Hero + 课程列表 / 时间轴。
 *
 * 单独成一个可组合函数是为了 [AnimatedContent] 能按"正在进入的那一天"重算内容——
 * 换天过程中新旧两天同时存在，共用外层 `date` 会让滑出去的那一屏先变成新日期。
 */
@Composable
private fun DayScreen(
    date: LocalDate,
    today: LocalDate,
    courses: List<Course>,
    semester: Semester?,
    semesterStart: LocalDate?,
    timeSlots: List<TimeSlot>,
    now: LocalTime,
    timelineMode: Boolean,
    onDateChange: (LocalDate) -> Unit,
    onCourseClick: (Course) -> Unit,
) {
    val isToday = date == today
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
    val plan = remember(date, courses, semester, timeSlots, now) {
        if (isToday) TodayPlanner.plan(courses, semester, timeSlots, date, now) else null
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Hero 只回答"今天的课上到哪了"：这一天一门课都没有时它没有落点，
        // 而下面那张空态卡说的就是同一件事——两张同屏是重复（M2）
        if (isToday && plan != null && dayCourses.isNotEmpty()) {
            TodayHero(plan, now)
        }

        // 空态 ↔ 列表之间不做硬切：横滑换天时两层内容叠在同一格里淡入淡出。
        // 这里用 Crossfade 而不是 AnimatedVisibility——外层是 Column，收起过程中
        // 两层内容会一起把列高撑破；Crossfade 内部是 Box，只做叠加不占额外高度。
        Crossfade(
            targetState = dayCourses.isEmpty(),
            animationSpec = motionSpec<Float>(),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { isEmpty ->
            if (isEmpty) {
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
                    modifier = Modifier.fillMaxSize().padding(top = DesignTokens.spaceM),
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
                                    timeSlots = timeSlots,
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

/**
 * 当前/下一节课 Hero 摘要卡（仅今日显示）。
 *
 * 卡片底部那条时间带是这张卡唯一的图形表达：文字只说"下一节 14:00"，
 * 而"下午其实整段都空着""晚课后还有一节"这种全天的疏密，只能看形状。
 */
@Composable
private fun TodayHero(
    plan: com.buaa.schedule.domain.schedule.TodayPlan,
    now: java.time.LocalTime,
) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        shape = RoundedCornerShape(DesignTokens.cornerPanel),
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth().padding(top = DesignTokens.spaceS),
    ) {
        val ongoing = plan.ongoing
        val next = plan.next
        // GlassSurface 的内容容器是 Box，而 Box 的每个子节点都从 top-start 摆起：
        // 所以这个 lambda 里只能有**一个**顶层子节点，就是下面这个 Column——
        // 多行文本包 Column 是同一条规则，疏密带子当初被落在了外面，
        // 于是它压在首行「下一节」上（padding(top = spaceM) 只是往下推一格，推不开重叠）。
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceMicro),
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
            // 全天疏密一眼看得到：文字只能逐条报时刻，形状才回答"下午是不是空的"。
            // 时间取自 TodayPlanner 已经算好的 slot，不再读一次 LocalTime.now()——
            // 两处时钟口径分叉的话，这条带子和卡片上"还有 25 分钟下课"会互相矛盾。
            // 段间距仍由带子自己的 padding(top = spaceM) 提供：外层 Column 不设 arrangement，
            // 文本块与带子之间就正好是这一份 spaceM，不会和 spacedBy 叠成两倍。
            if (plan.slots.isNotEmpty()) {
                TodayTimelineStrip(
                    segments = dayTimelineSegments(
                        plan.slots.map {
                            Triple(
                                it.start.hour * 60 + it.start.minute,
                                it.end.hour * 60 + it.end.minute,
                                it.course.colorIndex,
                            )
                        },
                    ),
                    nowFraction = dayFractionOfMinute(
                        now.hour * 60 + now.minute,
                    ),
                    modifier = Modifier.padding(top = DesignTokens.spaceM),
                )
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
    timeSlots: List<TimeSlot>,
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
                    modifier = Modifier.width(DesignTokens.weekTimeColumnWidth),
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
                        // 非进行中两态以前是 surfaceVariant 底 + onSurfaceVariant 字：
                        // 深色档下那是一对相近的灰，压在玻璃卡上几乎看不见（普查：状态胶囊深色档近隐形）。
                        // 换成 M3 的成对容器色，两个主题下对比度都由配色本身保证。
                        Box(
                            modifier = Modifier
                                .background(
                                    if (status == SlotStatus.ONGOING) accent
                                    else MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(DesignTokens.cornerPill),
                                )
                                .padding(
                                    horizontal = DesignTokens.spaceS,
                                    vertical = DesignTokens.spaceMicro,
                                ),
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (status == SlotStatus.ONGOING) contentOn(accent)
                                else MaterialTheme.colorScheme.onSecondaryContainer,
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
                        .copy(alpha = if (locationText != null) 1f else DesignTokens.PLACEHOLDER_INK_ALPHA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 备注（"带实验报告""第 3 次课考试"）属于强提醒信息，
                // 以前只有点开详情 Sheet 才看得到（③C-05）
                course.remark?.takeIf { it.isNotBlank() }?.let { remark ->
                    Text(
                        text = remark,
                        style = MaterialTheme.typography.bodyMedium,
                        // 备注与地点以前是同档（都是 onSurfaceVariant），"带实验报告"
                        // 于是被压成了辅助信息。它俩的差别在要不要被读到，
                        // 所以用浓度分：备注走正文墨色，地点留次级色。
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = dayCourseMetaLine(course, timeSlots),
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
 * 日视图卡片最后一行：节次 · 教师（纯函数，可单测）。
 *
 * 以前是 `append(节次); append(" · $教师")`：教师那一跳无条件带分隔符，
 * 于是没有节次的课这一行以悬空的 " · " 开头（教师是空串时则以它结尾）。
 * 与 [placeTimeLine] 同一条约定，交给 [joinMeta] 一处实现。
 */
internal fun dayCourseMetaLine(course: Course, timeSlots: List<TimeSlot>): String =
    joinMeta(periodLabelOf(course.periods, timeSlots), course.teacher)

/**
 * 「地点 · 08:00–09:40」一行摘要。地点为空（教务系统没抓到、走读课）时要连分隔符一起丢掉：
 * 拼成 " · 08:00–09:40" 那种悬空前缀，看着像文本被截断了（审查①V-02）。
 */
private fun placeTimeLine(location: String?, start: LocalTime, end: LocalTime): String =
    listOfNotNull(
        location?.takeIf { it.isNotBlank() },
        "${hhmm(start)}–${hhmm(end)}",
    ).joinToString(" · ")

private fun weekdayName(day: Int): String = weekdayLabel(day) ?: ""
