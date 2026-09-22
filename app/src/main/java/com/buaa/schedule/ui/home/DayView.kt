package com.buaa.schedule.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
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
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.performTick
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.core.designsystem.motionSpecFor
import com.buaa.schedule.core.designsystem.motionSpringFor
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.creditLabel
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
import com.buaa.schedule.ui.courseSharedElementModifier
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
 * @param specialDays 「学习日程」的假期/调休标注（T62②）：页头那一行日期跟着
 *   正在看的那一天挂徽标。今日页以前完全不认这份数据，用户在今天=中秋那天
 *   看到的只有"没有课"，看不出这是节假日。
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
    specialDays: List<com.buaa.schedule.domain.model.SpecialDay> = emptyList(),
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
    // 标注折算成判据要的形态，一次一份（键只有 specialDays：它换了才要重算，
    // 与在看哪一天无关 —— 那一维留给徽标自己去问）
    val specialDayMarks = remember(specialDays) { specialDayMarksOf(specialDays) }
    // 「列表 / 时间轴」的选择落在 Personalization（同 weekGridMode 一条链）：
    // 此前它是 rememberSaveable，旋转/返回能活，但杀进程冷启动就回到「列表」——
    // 用户明确点出来的模式被当成一次性界面状态丢掉了。
    val context = LocalContext.current
    val timelineMode = Personalization.dayTimelineMode

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
                        // 徽标挂在副行而不是日期那一行：titleMedium 下
                        // 「9月25日 · 星期五」+「休· 中秋节」在 360dp 屏上会挤掉日期本身
                        // （页头左右各有一个 48dp 箭头，中间只剩 ~230dp），
                        // 而 labelMedium 那一行放得下，且周次与节假日本来就是同一句话的两半。
                        // 键用 `day`：这一格在 Crossfade 里，翻页过程中新旧两天并存，
                        // 读外层 `date` 就会让滑出去那一屏先换成新日期的徽标（同 AnimatedContent 的理由）。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (day == today) "今天 · ${weekTextFor(semester, semesterStart, day)}"
                                else weekTextFor(semester, semesterStart, day),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            SpecialDayBadgeText(
                                date = day,
                                marks = specialDayMarks,
                                showNote = true,
                            )
                        }
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
                onSelect = {
                    Personalization.dayTimelineMode = it == 1
                    Personalization.save(context)
                },
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

/**
 * 列表 ↔ 时间轴的转场（T52①）：一次性的交叉 + 轻微纵向位移，方向由"哪一档进来"决定。
 *
 * 口径是"同一张表换了画法"：列表是逐行的、时间轴是沿时间往下铺的，
 * 所以往时间轴走时新内容从下方 1/20 屏高处进场、旧的往上让；反向整体调转。
 * 位移取内容高度的分数而不是 dp：两档正文都几乎一屏高，写死 dp 会在矮屏上读成"抖了一下"。
 *
 * 时长取 [MotionTokens.DURATION_MEDIUM]（260ms）：换画法改动的是一整块正文，
 * 不是一枚控件（那一档是 180），也不是翻整一天那种手势级的 140。
 * 转场 lambda 在组合期外求值，读不到 `LocalReduceMotion`，开关由调用方读出来传进来（§2.7）。
 *
 * `sizeTransform(clip = false)`：两侧正文都是 `fillMaxSize`，容器高度由外层
 * `Crossfade(weight(1f))` 钉死，尺寸本来就不变，所以这一条不影响任何静止像素；
 * 关裁剪是因为带着位移的那 260ms 里正文底部会被默认的矩形裁掉一截，
 * 而"换了个画法"不该读成"内容被切了一刀"。
 */
private fun dayModeTransition(reduceMotion: Boolean, toTimeline: Boolean): ContentTransform {
    if (reduceMotion) {
        return ContentTransform(
            targetContentEnter = EnterTransition.None,
            initialContentExit = ExitTransition.None,
            sizeTransform = SizeTransform(clip = false),
        )
    }
    // +1 = 新内容从下方进场、旧的往上退；反向整体取负
    val enterShift = if (toTimeline) 1 else -1
    val fadeSpec = motionSpecFor<Float>(reduceMotion, MotionTokens.DURATION_MEDIUM)
    val slideSpec = motionSpecFor<IntOffset>(reduceMotion, MotionTokens.DURATION_MEDIUM)
    return ContentTransform(
        targetContentEnter = fadeIn(fadeSpec) +
            slideInVertically(slideSpec, initialOffsetY = { enterShift * it / 20 }),
        initialContentExit = fadeOut(fadeSpec) +
            slideOutVertically(slideSpec, targetOffsetY = { -enterShift * it / 20 }),
        sizeTransform = SizeTransform(clip = false),
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
    // 转场 lambda 不在组合期求值，读不到 LocalProvide，开关在这里读一次传进去（§2.7）
    val reduceMotion = LocalReduceMotion.current
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
                // 列表 / 时间轴切换：一次性的交叉 + 轻微纵向位移，读成"同一张表换了画法"（T52①）
                AnimatedContent(
                    targetState = timelineMode,
                    transitionSpec = { dayModeTransition(reduceMotion, toTimeline = targetState) },
                    label = "DayViewMode",
                    modifier = Modifier.fillMaxSize().padding(top = DesignTokens.spaceM),
                ) { timeline ->
                    if (timeline) {
                        DayTimelineCourseList(
                            rows = rows,
                            periodTimes = periodTimes,
                            isToday = isToday,
                            now = now,
                            onClick = onCourseClick,
                        )
                    } else {
                        // 卡片进场（T52②）：一条驱动按行次切窗口，第一次进入今日列表播一遍
                        val entrance = rememberCourseEntrance(EntrancePlaybook.DAY_LIST)
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                        ) {
                            itemsIndexed(
                                items = rows,
                                key = { _, row -> "${row.course.id}-${row.segment.first}" },
                            ) { index, row ->
                                CourseTimelineCard(
                                    course = row.course,
                                    status = row.status,
                                    startTime = row.startTime,
                                    endTime = row.endTime,
                                    timeSlots = timeSlots,
                                    onClick = { onCourseClick(row.course) },
                                    // 换日期/删课/挪课时整批行不会瞬间替换（items 有稳定 key 才能生效）
                                    // 进场变换自持一层 graphicsLayer、挂在 animateItem 之前：
                                    // 两者各自持有一层，谁在外面对最终像素没有影响（落定后都是恒等变换）
                                    modifier = Modifier.graphicsLayer {
                                        entrance.applyTo(this, index, rows.size)
                                    }.animateItem(),
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

/**
 * 色块的三层内边距：块本体两侧 2dp（描边/共享元素那层）、文字两侧 6dp、文字上下 3dp。
 * 上下的那一层要从块高里先扣掉才是文字容量，所以它被钉成名字而不是散在 modifier 里——
 * 行预算与摆放读的是同一枚数，改一处不会漏改另一处。
 */
private val BlockSidePadding = 2.dp
private val BlockTextSidePadding = 6.dp
private val BlockTextVerticalPadding = 3.dp

/** 日视图时间网格：按真实时间线性定位的课程时间轴（T49 重做）
 *
 * 真机镜像实测（buaa36，周一 10:44、当天 5 节课）此前的问题逐条对位：
 * 左侧小时刻度列＋整点网格线（"看不出这是时间轴"）、NowLine 与 15 秒链
 * （"全天看不出此刻在哪"）、进入时自动把"现在"滚进视野（"先看到 08:00 的空档"）、
 * 课间虚线＋「课间 N 分钟」（"一两小时的空白像渲染坏了"）。
 * 判据（窗口吸附/刻度位置/滚动落点/课间分段/当前块命中）全部在 DayTimelineAxis.kt，
 * 零 android、纯 JVM 可测；这里只做摆放。
 */
@Composable
private fun DayTimelineCourseList(
    rows: List<DayCourseRow>,
    periodTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    isToday: Boolean,
    now: LocalTime,
    onClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (periodTimes.isEmpty()) {
        Text("节次时间未配置", modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // 窗口整点吸附的推导与理由见 [dayTimelineWindow]：刻度列是每整点一格，不吸附刻度对不上。
    val window = remember(periodTimes) {
        dayTimelineWindow(periodTimes.values.minOf { it.first }, periodTimes.values.maxOf { it.second })
    }
    // 1.40dp/分钟：正常 45 分钟的一节课 63dp，装得下实测三行（54.1dp）＋上下各 3dp 内边距，
    // 装机复量到的真余量 2.71dp（哪几行装得下由 planDayTimelineBlockLines 按量到的行高算，
    // 不再是 38/58 两档魔法数）。推导那笔账的是量出来的行高，不是排版表标称，见
    // [DesignTokens.dayHeightPerMinute]。
    // 块高与分钟数仍严格成正比，minTouchTarget 那道补齐只在 34.3 分钟以下的短块上生效，
    // 比 1.35 档（35.6 分钟）与 1.05 档（45.7 分钟）吃掉同样的溢出 ⇒ 相邻两节互相压住的空间是收窄的。
    val heightPerMinute = DesignTokens.dayHeightPerMinute
    // 时高/总高都从 hourHeight 出发：刻度格、网格线、块定位共用同一把尺，
    // 周视图 24h 模式就是这么算的（WeekView 的 gridHeight 与卡片 top 同一来源）
    val hourHeight = heightPerMinute * 60f
    val totalHeight = hourHeight * ((window.endMin - window.startMin) / 60f)

    // 块的起止分钟：刻度、课间、当前态、滚动落点都吃这一份，
    // 不再各自把 periodTimes 解析一遍（周视图为此专门立了 TimeSlotIndex，同一动机）
    val blocks = remember(rows, periodTimes) {
        rows.mapNotNull { row ->
            val start = periodTimes[row.segment.first]?.first ?: return@mapNotNull null
            val end = periodTimes[row.segment.last]?.second ?: return@mapNotNull null
            DayTimelineBlock(
                row = row,
                start = start,
                end = end,
                startMin = start.hour * 60 + start.minute,
                endMin = end.hour * 60 + end.minute,
            )
        }
    }
    // 卡片进场（T52②）：与列表模式同一口径、另一把键——切到时间轴确实是"换了一张表画法"，
    // 值得各播一次；但进程内只播一次，翻回来翻回去都不会再来一遍（见 EntrancePlaybook）
    val entrance = rememberCourseEntrance(EntrancePlaybook.DAY_TIMELINE)
    val hourLineOffsets = remember(window) {
        dayTimelineHourLineOffsets(window, heightPerMinute.value.toDouble())
    }
    val gaps = remember(blocks) { dayTimelineGaps(blocks.map { IntRange(it.startMin, it.endMin) }) }
    // 分钟级"现在"：与 Hero/倒计时同源（DayScreen 传下来的 now），块的当前态
    // 只在整分钟边界翻面，跟着分钟就够，不必蹭 15 秒节奏
    val nowMinuteOfDay = timelineMinuteOfDay(now)

    // 「现在」线的实时度走 15 秒一档（TIMELINE_TICK_MS），且只喂给 NowLine 一个组合作用域：
    // 周视图同族做法（WeekView 把 nowTickState 作为 State 传下去，普通卡片不读它）。
    // 若在列表本体读这个 State，每 15 秒整条时间轴连同全部色块一起重组。
    // 链只在线真被画的时候起（判据与口径见 nowLineNeedsLiveTick）：此前无条件起链，
    // 真机实测（buaa36）翻到周二看时间轴，屏幕每 15 秒白醒一次写一个没人读的 State——
    // NowLine 在非今天直接 return，块高亮走分钟级 now 参数、不吃这个 tick。
    // 「回到今天」不受害：isToday 翻转 → key 变 → effect 重启，第一拍先发布再等边界。
    val nowLineTick = remember { mutableStateOf(LocalTime.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val liveTickWanted = nowLineNeedsLiveTick(isToday, nowMinuteOfDay, window)
    LaunchedEffect(lifecycle, liveTickWanted) {
        if (!liveTickWanted) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // 先发布再等边界、一次读取两处共用——理由见 NowTick.kt 的 nextTickDelayMillis
                val current = LocalTime.now()
                nowLineTick.value = current
                delay(nextTickDelayMillis(current, TIMELINE_TICK_MS))
            }
        }
    }

    // —— 进入模式即把"现在"滚进视野 ——
    // scrollTo 挂在 effect 里、先于首帧呈现执行（Compose 首组：applyChanges → effects → 绘制），
    // 所以没有"先跳顶再滚"的闪烁；周视图 :623 那整条链就是这样落地的。
    // scrolledToNow 用 remember 而非 rememberSaveable：Crossfade 在切模式时会销毁另一侧，
    // 标记天然随"这一次进入时间轴"重置——隔一会儿再切回来，课已经上了一截，
    // 理应重新对齐现在，而不是尊重一个上一轮的停留位。
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var scrolledToNow by remember { mutableStateOf(false) }
    LaunchedEffect(viewportHeightPx) {
        if (scrolledToNow || viewportHeightPx == 0) return@LaunchedEffect
        scrolledToNow = true
        // 别的日子没有"现在"可对：留在顶部，从第一节看起（全 past/全 future 的落点判据见锚点函数）
        if (!isToday) return@LaunchedEffect
        val anchor = dayTimelineAnchorMinute(
            nowMinuteOfDay,
            window,
            blocks.map { IntRange(it.startMin, it.endMin) },
        )
        scrollState.scrollTo(
            dayTimelineScrollTargetPx(
                anchor,
                window,
                with(density) { heightPerMinute.toPx() },
                viewportHeightPx,
            ),
        )
    }

    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val gapTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    // —— 行预算要用的两份事实，全部在这里量好，内核只算账（见 DayTimelineBlockLines.kt）——
    // 行高**实测**而非抄排版表：lineHeight 只是下限，字体自带行框更高时以字体为准。
    // 装机量到的是 labelLarge 52px=19.81dp、labelMedium 45px=17.14dp（标称 20/16），
    // 三行 54.10dp 而不是 52dp —— T61b① 退回的就是这一截：按标称记账时 1.35 那档的
    // 54.75dp 容量只剩 1px 真余量，换一枚没量过的字体就穿。
    // 一枚样式量一次（按 style + fontScale + density 记忆），不是每块量一次：
    // 一次测量 ≈ 一次文本排版，块数乘上去就是白付的首帧账。fontScale 留在键上是必须的——
    // 换系统字号时 Measurer 会换、量到的 dp 也会换，缓存不许跨它。
    val fontScale = density.fontScale
    val lineMeasurer = rememberTimelineTextMeasurer()
    val nameLineHeightDp = timelineLineHeightDp(MaterialTheme.typography.labelLarge, lineMeasurer, fontScale)
    val metaLineHeightDp = timelineLineHeightDp(MaterialTheme.typography.labelMedium, lineMeasurer, fontScale)

    Column(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            // 左侧小时刻度列：与周视图 24h 模式同一件控件（TimelineAxis.kt，T49 提取）。
            // LineTop 档把每枚文字顶边贴到自己那枚整点的网格线上（错位实测与两档口径见
            // HourLabelAnchor 的注释）；周视图调用点不传参、走默认档，那一屏逐像素不变
            Box(modifier = Modifier.width(DesignTokens.weekTimeColumnWidth)) {
                HourLabels(
                    window.startMin / 60,
                    window.endMin / 60,
                    hourHeight,
                    HourLabelAnchor.LineTop,
                )
            }
            Spacer(modifier = Modifier.width(DesignTokens.spaceS))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(totalHeight),
            ) {
                // 整点横线：走主题 outlineVariant，不写死灰
                hourLineOffsets.forEach { yDp ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .offset(y = yDp.dp)
                            .background(lineColor),
                    )
                }
                // 课间：≥20 分钟画虚线、≥30 分钟追加文字（阈值与并块理由见 dayTimelineGaps）。
                // 画在课程块之下：课间按定义没有块盖着它，但重叠容错不该依赖这个假设。
                gaps.forEach { gap ->
                    val gapMidY = hourHeight * ((gap.startMin + gap.endMin) / 2f - window.startMin) / 60f
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .offset(y = gapMidY),
                    ) {
                        drawLine(
                            color = lineColor,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                            ),
                        )
                    }
                    if (gap.minutes >= DAY_GAP_LABEL_MIN_MINUTES) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = gapMidY + 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "课间 ${gap.minutes} 分钟",
                                style = MaterialTheme.typography.labelMedium,
                                color = gapTextColor,
                                maxLines = 1,
                            )
                        }
                    }
                }
                // 「现在」线画在课程块**之下**（T55）：它此前是这个 Box 的最后一个子项，
                // 于是 2dp 的红线从课程名中间横过去——装机实测（buaa36，周一 17:41，
                // 17:30–18:15 那一节）「思想政治」四个字被划了一道，读起来像被划掉。
                // Box 的子项按声明顺序叠放，挪到块之前就是卡片盖住线：文字一处不碰，
                // 而课间、轴两端与块与块之间的空隙仍然露出它——"现在"的信号留在空档处。
                // 块是 0.72 的半透明板（取色链 T23/T25b/T29 定的口径，本卡不动），
                // 所以线在块底透出一道更淡的横印，那是叠色不是划线：它压不到字上，
                // 因为文字在同一个块里、画在自己的板之上。
                // 越界不画的边界行为仍长在 NowGlideLine 自己的 fraction≤0/≥1 判断里，
                // 与周视图共用同一个组件，两边不会再各改各的。
                NowLine(
                    visible = isToday,
                    startMin = window.startMin,
                    endMin = window.endMin,
                    totalHeight = totalHeight,
                    nowTickState = nowLineTick,
                )
                blocks.forEachIndexed { blockIndex, block ->
                    val course = block.row.course
                    val y = hourHeight * ((block.startMin - window.startMin) / 60f)
                    val blockHeight = (
                        hourHeight * ((block.endMin - block.startMin).coerceAtLeast(1) / 60f)
                        )
                        // 短节次按真实比例只有十几 dp，补到触控下限。
                        // 1.40dp/分钟下这条溢出比旧档位更小：48dp 现在只相当于 34.3 分钟
                        // （1.35 档是 35.6 分钟、1.05 档是 45.7 分钟），正常的 45 分钟课（63dp）
                        // 根本走不到这里，所以"上一节的色块压住下一节"的空间是收窄的，不是变大了。
                        .coerceAtLeast(DesignTokens.minTouchTarget)
                    val blockColor = courseColor(course)
                    // 文字要看**合成后**的亮度：BlockTintAlpha 的课程色透出了页面背景。
                    // 时间轴并没有坐在卡片上——它直接挂在正文里，背后是壁纸/内置渐变，
                    // 所以场景亮度与周视图课程卡取同一个口径（最不利分块，不是 surface）。
                    // 用主题的 onSurface 时，深色主题下那是近白色，
                    // 压在亮黄/亮绿的时间块上几乎看不见。
                    // （T23/T25b/T29 用真机数据校准出的这条推导链不许动，T49 只改形状。）
                    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val plate = legibleTintPlate(
                        blockColor,
                        BlockTintAlpha,
                        coursePlateSceneLuma(blockColor, darkTheme),
                    )
                    val onBlock = plate.foreground
                    // 圆角与周视图课程格同一令牌：同一个"课程"在两个视图里不该是两种形状
                    val blockShape = RoundedCornerShape(DesignTokens.cornerCourse)
                    // 正在上的那一节：可辨识的当前态走几何＋浓度两通道——线宽 1dp→2dp，
                    // 描边从 0.55f 浓度跳到满浓度。
                    // T49b④：描边不再借「现在」线的 error 红。真机实测（buaa36，周一 11:18）：
                    // 当前块 09:50–11:25 的 error 描边下沿 y=1673，「现在」线 y=1660，只差 13px
                    // 且两边同是 (186,26,26)——线与框读成一条发虚的加粗边，分不出"此刻几点"
                    // 和"正在上哪节"。红色收归现在线独享（它是全轴唯一的时间信号），
                    // 当前块退回自己的课程色满浓度：与所在块同族、又与邻块之间绝无雷同。
                    val isCurrent = isToday &&
                        dayTimelineBlockContains(block.startMin, block.endMin, nowMinuteOfDay)
                    val blockModifier = Modifier
                        .offset(y = y)
                        .height(blockHeight)
                        .fillMaxWidth()
                        // 进场变换自持一层 graphicsLayer、挂在共享元素之前：
                        // 驱动落定后是恒等变换，静止像素与改前一致，
                        // 而共享元素接管的是它自己那一层，两者不互相改写
                        .then(
                            Modifier.graphicsLayer {
                                entrance.applyTo(this, blockIndex, blocks.size)
                            },
                        )
                        // 共享元素：时间轴模式与列表模式是同一条链（T52④ 缺的就是这两处）。
                        // 放在 fillMaxWidth 之后、视觉层之前：共享的那块矩形=色块本体，
                        // 与周视图课程格的接法逐字一致。
                        .then(Modifier.courseSharedElementModifier(course.id))
                        .padding(horizontal = BlockSidePadding)
                        // 1dp 投影：块从"平贴网格线的色卡"变成浮在轴上的物体。
                        // 不套 GlassSurface——用户实测口径是大面积厚玻璃板丑，这里数量多、
                        // 尺寸中等，收口只做描边＋轻投影，对比度仍由 tint plate 推导链负责。
                        .shadow(elevation = 1.dp, shape = blockShape)
                        .clip(blockShape)
                        .background(plate.tint.copy(alpha = plate.alpha), blockShape)
                        .border(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = if (isCurrent) blockColor else blockColor.copy(alpha = 0.55f),
                            shape = blockShape,
                        )
                        .clickable { onClick(course) }
                        .padding(
                            horizontal = BlockTextSidePadding,
                            vertical = BlockTextVerticalPadding,
                        )
                        // T61b②：安全带。行预算是按**实测**行高算的，正常情况下这一道用不上；
                        // 它挡的是"谁换了一枚没量过的字体"那一档（MiSans、厂商自定义行框、
                        // 未来的排版表改动）。板子的轮廓上面那道 .clip(blockShape) 早就裁得住，
                        // 漏不了页面背景，但残余会压在描边与圆角那一条带上、把"这块到哪儿结束"糊掉
                        // ——那正是 T54 那一类"多出来的一行没地方去"的形状，所以这里补第二道。
                        // 位置放在 .padding 之后：裁的就是行预算那枚"内容上限"，两条边界同源。
                        // 每一行的高度是按实测行框记的账（含降部），字本身从来不出自己的行框，
                        // 所以这道裁切正常情况下一毫米都碰不到字，只会削掉真超出内容区的那一整行。
                        .clipToBounds()
                    // 装得下哪几行：把量好的两份事实（块高、两档**实测**行高）交给纯 JVM 内核，
                    // 由它按容量逐行分。这里不再有 38/58dp 两档定值——那对数是按**一种**系统字号、
                    // 一张排版表标定的：小字号下 45 分钟的课（47.25dp）永远够不到 58dp 那档，
                    // 教室那一行从此绝迹（= 用户报的"文字内容有点少"）；大字号下行高按 fontScale 长
                    // 而门槛不长，反过来把课名顶出去（T54 实测到的正是这一头）。
                    val remark = course.remark?.takeIf { it.isNotBlank() }
                    val linePlan = planDayTimelineBlockLines(
                        blockHeightDp = blockHeight.value.toDouble(),
                        contentVerticalPaddingDp = BlockTextVerticalPadding.value.toDouble(),
                        specs = listOfNotNull(
                            DayTimelineLineSpec(
                                DayTimelineBlockLine.CourseName,
                                nameLineHeightDp,
                            ),
                            DayTimelineLineSpec(
                                DayTimelineBlockLine.TimeAndTeacher,
                                metaLineHeightDp,
                            ),
                            DayTimelineLineSpec(
                                DayTimelineBlockLine.Room,
                                metaLineHeightDp,
                            ),
                            // 没有备注就连候选都不递：省下来的高度不会往后挪（后面也没东西了），
                            // 但少递一枚比递一枚画不出来的行更诚实
                            remark?.let {
                                DayTimelineLineSpec(
                                    DayTimelineBlockLine.Remark,
                                    metaLineHeightDp,
                                )
                            },
                        ),
                    )
                    Box(modifier = blockModifier, contentAlignment = Alignment.TopStart) {
                        // 色块按真实时长定位，装不下就不画那一行：挤出去的下一行会把课名顶没，
                        // 反而更看不清。课名是内核里唯一 pinned 的行，任何情况下都在。
                        Column {
                            Text(
                                text = course.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = onBlock,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (DayTimelineBlockLine.TimeAndTeacher in linePlan) {
                                Text(
                                    // 教师接在时间段后面，走列表模式同一套约定：缺项连同分隔符
                                    // 一起缺席（joinMeta 的口径），不会出现悬空的 " · "
                                    text = joinMeta(
                                        "${hhmm(block.start)}–${hhmm(block.end)}",
                                        periodLabel(block.row.segment),
                                        course.teacher,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = onBlock,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (DayTimelineBlockLine.Room in linePlan) {
                                Text(
                                    // 空白教室字段按"没有那一行"算会留下高低不齐的块，
                                    // 统一成"教室未定"占位，与列表模式 ③C-05 同一口径
                                    text = course.location?.takeIf { it.isNotBlank() } ?: "教室未定",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = plate.secondaryForeground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (remark != null && DayTimelineBlockLine.Remark in linePlan) {
                                Text(
                                    // 备注走正文墨色而不是次级色：它是"带校园卡""雨天改室内馆"这种
                                    // 不看到就会出事的字（与列表模式 ③C-05 的浓度分档同一条账）
                                    text = remark,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = onBlock,
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

/** 时间轴上的一个课程块：起止墙钟 + 当日分钟数（摆放与判据共用一份解析结果） */
private class DayTimelineBlock(
    val row: DayCourseRow,
    val start: LocalTime,
    val end: LocalTime,
    val startMin: Int,
    val endMin: Int,
)

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
        modifier = modifier
            .fillMaxWidth()
            // 共享元素：与周视图课程格、编辑器同一个 key（键的收口见 CourseSharedElement.kt）。
            // 列表模式此前根本没接这条链——从今日课表点开一节课只有整页淡入淡出。
            .then(Modifier.courseSharedElementModifier(course.id))
            .animateContentSize(motionSpec<IntSize>()),
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
                    // 改前：课名不带 weight，Row 按顺序把「剩余全宽」先递给非加权子节点，
                    // 于是长课名在自己末尾省略掉整格，轮到胶囊时 maxWidth 已经归零——
                    // 胶囊被摆到行宽之外，而 PANEL 底板 clip(shape)（LiquidGlass）把它整枚裁没。
                    // 改后：weight(1f, fill = false) 让 Row 先量胶囊与间隔的自然宽，课名只吃剩下那份。
                    // fill 必须为 false：短标题时课名保持自然宽、胶囊紧贴其后，视觉与改前逐像素一致；
                    // 若用默认的 fill=true，课名会被拉满整格，短行的胶囊会被顶到行尾去。
                    Text(
                        text = course.displayName,
                        modifier = Modifier.weight(1f, fill = false),
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
                            // 改前这里没锁行数：课名吃满整行后胶囊的 maxWidth 归零，
                            // 「未开始」被逐字断成三行竖排——这一行的高度是被**裁在卡外**的
                            // 胶囊撑起来的，所以长标题的卡在课名与地点之间多出一截空隙
                            // （列表模式实测 deformity 之三的真因）。锁一行，高度与短标题行齐平。
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (status == SlotStatus.ONGOING) contentOn(accent)
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
 * 量一行文字用的 Measurer。
 *
 * 本仓钉住的那份 ui-text 里没有 `rememberTextMeasurer`（只有 [TextMeasurer] 本体与它的
 * `measure`），所以自己造一枚、按 (resolver, density, direction) 记忆——density 里就带着
 * fontScale，系统字号一变这一枚就换，跨字号的旧测量值不会留下来。
 */
@Composable
private fun rememberTimelineTextMeasurer(): TextMeasurer {
    val resolver = LocalFontFamilyResolver.current
    val measurerDensity = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return remember(resolver, measurerDensity, direction) {
        TextMeasurer(resolver, measurerDensity, direction)
    }
}

/**
 * 量的样例：一个汉字。
 *
 * 必须是 CJK——色块里写的就是中文课程名，而拉丁样例在字体走 fallback 时会**少报**行高
 * （西文自带行框比汉字浅），少报的那一截正好是要裁掉的那一行。量谁就写谁。
 */
private val TimelineLineSample = "课"

/**
 * 一枚文字样式的**实测**行高（dp），从 MaterialTheme.typography 现取样式、不抄常量
 * （抄回来的就是下一对 38/58）。
 *
 * 记忆键 = 样式 + fontScale + density，也就是**一台设备一枚样式一次测量**，
 * 不随色块数走：一次测量 = 一次文本排版，一天十几节课乘上去就是白付的首帧账。
 */
@Composable
private fun timelineLineHeightDp(
    style: TextStyle,
    textMeasurer: TextMeasurer,
    fontScale: Float,
): Double {
    val density = LocalDensity.current
    return remember(style, fontScale, density, textMeasurer) {
        measureTimelineLineHeightDp(style, textMeasurer, density, fontScale)
    }
}

/**
 * 真正下尺的那一处：首行行框的高度，进位到整 px 再折回 dp。
 *
 * 不再拿排版表的 `lineHeight × fontScale` 当这一枚数（T61 头一版那么算，被 T61b① 退回来）：
 * Compose 的 lineHeight 是下限而不是定值，字体自带行框更高时以字体为准。装机量到的就是这两枚：
 * labelLarge 52px=19.81dp（标称 20）、labelMedium 45px=17.14dp（标称 16）——
 * 三行差出 2.1dp，而按标称挑的 1.35 那一档只留了 1px（0.38dp）真余量。
 * 换一枚 MiSans 就把这一px吃穿，多出来的那一行去压色板自己的描边与圆角（第二道裁切 T61b② 就是为它准备的）。
 *
 * 取整到 px 的这一手不是洁癖：Compose 把每个子节点的量得高落在整 px 上，
 * 三行各差不到 1px，这点累计不该由那 2dp 的真余量买单。
 *
 * 量不出来（字体没就绪、样例排版抛了）才退回**偏高**的一档：标称 lineHeight 与字号 × 1.4 取大——
 * 少承诺一行，绝不会反过来把已经画出来的行裁掉。
 *
 * @param fontScale 只用于那条退让支路（实测那条吃的是 Measurer 自己那份 density）
 */
private fun measureTimelineLineHeightDp(
    style: TextStyle,
    textMeasurer: TextMeasurer,
    density: Density,
    fontScale: Float,
): Double {
    val measuredPx = runCatching {
        val layout = textMeasurer.measure(TimelineLineSample, style)
        if (layout.lineCount > 0) layout.getLineBottom(0) - layout.getLineTop(0) else 0f
    }.getOrNull()
    if (measuredPx != null && measuredPx > 0f) {
        return with(density) { kotlin.math.ceil(measuredPx).toDp() }.value.toDouble()
    }
    val nominalSp = maxOf(
        style.lineHeight.value.takeIf { it > 0f } ?: 0f,
        style.fontSize.value * 1.4f,
    )
    return (nominalSp * fontScale).toDouble()
}

/**
 * 日视图卡片最后一行：节次 · 教师 · 学分（纯函数，可单测）。
 *
 * 以前是 `append(节次); append(" · $教师")`：教师那一跳无条件带分隔符，
 * 于是没有节次的课这一行以悬空的 " · " 开头（教师是空串时则以它结尾）。
 * 与 [placeTimeLine] 同一条约定，交给 [joinMeta] 一处实现。
 *
 * 学分排在教师之后：它是这门课的"分量"注脚，抢在教师前面会把"去哪找谁"这个
 * 主诉求挤后。没有学分数据时 [creditLabel] 给 null，joinMeta 连分隔符一起丢——
 * 这一行从不为学分留空槽，也不印「未设置」（缺失与 0 学分的分界见 CourseMetaFormat）。
 */
internal fun dayCourseMetaLine(course: Course, timeSlots: List<TimeSlot>): String =
    joinMeta(periodLabelOf(course.periods, timeSlots), course.teacher, creditLabel(course.credit))

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
