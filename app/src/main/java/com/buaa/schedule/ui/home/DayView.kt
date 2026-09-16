package com.buaa.schedule.ui.home

import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.core.designsystem.contentOnLuma
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.core.designsystem.performTick
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
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
        semesterStart == null -> courses.filter { it.dayOfWeek == date.dayOfWeek.value }
        week == null -> emptyList()
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
    // 按课程 id 建索引：此前每个列表项都 firstOrNull 线性扫一遍 plan.slots（O(N²)），
    // 一天十几门课没问题，但翻周/每分钟 tick 都会整列重算，白烧 CPU。
    val slotByCourseId = remember(plan) { plan?.slots?.associateBy { it.course.id } }

    // 节次 → 墙钟时间。今日计划（plan）只有**今天**才有，而日视图可以翻到任何一天，
    // 所以列表卡上的上课时间必须直接从节次表算：此前只有时间轴内部解析了节次时间，
    // 列表卡干脆没有时间可显示（用户反馈「今日界面课程不显示对应时间」）。
    val periodTimes = remember(timeSlots) { parsePeriodTimes(timeSlots) }
    fun clockOf(course: Course): Pair<String, String>? {
        val start = periodTimes[course.startPeriod]?.first ?: return null
        val end = periodTimes[course.endPeriod]?.second ?: return null
        return hhmm(start) to hhmm(end)
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
            TextButton(
                onClick = { timelineMode = !timelineMode },
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(if (timelineMode) "列表" else "时间轴")
            }
            if (!isToday) {
                Spacer(modifier = Modifier.width(DesignTokens.spaceS))
                TextButton(
                    onClick = { onDateChange(today) },
                    modifier = Modifier.weight(1f, fill = false),
                ) { Text("回到今天") }
            }
        }

        if (isToday && plan != null) {
            TodayHero(plan)
        }

        if (dayCourses.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (semesterStart != null && week == null) "假期里没有课程安排" else "这一天没有课",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isToday) {
                    TextButton(onClick = { onDateChange(date.plusDays(1)) }) {
                        Text("查看明天")
                    }
                }
            }
        } else {
            // 列表 / 时间轴切换也做淡入淡出，保持与首页周/日切换一致的操作质感
            Crossfade(
                targetState = timelineMode,
                modifier = Modifier.padding(top = DesignTokens.spaceM),
            ) { timeline ->
                if (timeline) {
                    DayTimelineCourseList(
                        courses = dayCourses,
                        periodTimes = periodTimes,
                        onClick = onCourseClick,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                    ) {
                        items(dayCourses, key = { it.id }) { course ->
                            val slot = slotByCourseId?.get(course.id)
                            val clock = clockOf(course)
                            CourseTimelineCard(
                                course = course,
                                status = slot?.status,
                                startTime = clock?.first,
                                endTime = clock?.second,
                                onClick = { onCourseClick(course) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 日视图时间网格：按真实时间线性定位的课程时间轴 */
@Composable
private fun DayTimelineCourseList(
    courses: List<Course>,
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
    // 0.6dp/分钟时一节 45 分钟的课只有 27dp，第二行文字根本放不下，
    // 时间轴于是退化成"一排只有课程名的色块"（用户反馈信息太少）。
    // 抬到 0.9dp/分钟：45 分钟 40dp 能带时间，90 分钟 81dp 还能带教室。
    val heightPerMinute = 0.9.dp
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
            courses.forEach { course ->
                val start = periodTimes[course.startPeriod]?.first
                val end = periodTimes[course.endPeriod]?.second
                if (start != null && end != null) {
                    val y = heightPerMinute *
                        java.time.Duration.between(minStart, start).toMinutes().toFloat()
                    val blockHeight = heightPerMinute *
                        java.time.Duration.between(start, end).toMinutes().toFloat().coerceAtLeast(0.5f)
                    val blockColor = courseColor(course)
                    // 文字要看**合成后**的亮度：0.72 的课程色叠在卡片底色上。
                    // 用主题的 onSurface 时，深色主题下那是近白色，
                    // 压在亮黄/亮绿的时间块上几乎看不见。
                    val blockLuma = blockColor.luminance() * 0.72f +
                        MaterialTheme.colorScheme.surface.luminance() * 0.28f
                    val onBlock = contentOnLuma(blockLuma)
                    Box(
                        modifier = Modifier
                            .offset(y = y)
                            .height(blockHeight)
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(blockColor.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                            .clickable { onClick(course) }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
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
                                    text = "${hhmm(start)}–${hhmm(end)} · ${periodLabel(course.periods)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onBlock,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (blockHeight >= 58.dp) {
                                Text(
                                    text = course.location ?: "教室未定",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onBlock.copy(alpha = 0.85f),
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
                    "${ongoing.course.location ?: ""} · ${ongoing.start}–${ongoing.end}",
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
                    "${next.course.location ?: ""} · ${next.start}–${next.end}",
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
        modifier = Modifier.fillMaxWidth(),
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
                        style = MaterialTheme.typography.labelSmall,
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
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status == SlotStatus.ONGOING) contentOn(accent) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                course.location?.let {
                    Text(
                        text = it,
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
