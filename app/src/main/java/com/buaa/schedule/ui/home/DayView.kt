package com.buaa.schedule.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.core.designsystem.courseColor
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

    val weekText = when {
        semester == null -> "未设置学期"
        semesterStart == null -> "学期开学日期无效，请在设置中修正"
        week == null -> if (date.isBefore(semesterStart)) "未开学" else "假期中"
        else -> "第 $week 周"
    }
    var timelineMode by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = DesignTokens.spaceL)) {
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
                    text = if (isToday) "今日 · ${weekdayName(date.dayOfWeek.value)}"
                    else "${date.monthValue}月${date.dayOfMonth}日 · ${weekdayName(date.dayOfWeek.value)}",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = weekText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { timelineMode = !timelineMode }) {
                Text(if (timelineMode) "列表" else "时间轴")
            }
            IconButton(onClick = { onDateChange(date.plusDays(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "后一天")
            }
        }

        if (isToday && plan != null) {
            TodayHero(plan)
        }
        if (!isToday) {
            TextButton(
                onClick = { onDateChange(today) },
                modifier = Modifier.padding(top = 2.dp).align(Alignment.CenterHorizontally),
            ) { Text("回到今天") }
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
                        timeSlots = timeSlots,
                        onClick = onCourseClick,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                    ) {
                        items(dayCourses, key = { it.id }) { course ->
                            val slot = slotByCourseId?.get(course.id)
                            CourseTimelineCard(
                                course = course,
                                status = slot?.status,
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
    timeSlots: List<TimeSlot>,
    onClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = timeSlots.ifEmpty { com.buaa.schedule.domain.model.TimeSlotProfile.DEFAULT }
    // 节次时间解析（含 LocalTime.parse）只在 timeSlots 变化时做一次；
    // 此前每次重组都对整表重跑，且下面每门课还要线性扫两遍查起止时间。
    val parsed = remember(slots) {
        slots.mapNotNull { slot ->
            runCatching {
                Triple(slot.number, LocalTime.parse(slot.startTime), LocalTime.parse(slot.endTime))
            }.getOrNull()
        }
    }
    if (parsed.isEmpty()) {
        Text("节次时间未配置", modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val startByPeriod = remember(parsed) { parsed.associate { it.first to it.second } }
    val endByPeriod = remember(parsed) { parsed.associate { it.first to it.third } }
    val minStart = parsed.minOf { it.second }
    val maxEnd = parsed.maxOf { it.third }
    val totalMinutes = java.time.Duration.between(minStart, maxEnd).toMinutes().toInt().coerceAtLeast(1)
    val heightPerMinute = 0.6.dp
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
                val start = startByPeriod[course.startPeriod]
                val end = endByPeriod[course.endPeriod]
                if (start != null && end != null) {
                    val y = heightPerMinute * java.time.Duration.between(minStart, start).toMinutes().toFloat()
                    val h = heightPerMinute * java.time.Duration.between(start, end).toMinutes().toFloat().coerceAtLeast(0.5f)
                    Box(
                        modifier = Modifier
                            .offset(y = y)
                            .height(h)
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp)
                            .background(courseColor(course).copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                            .clickable { onClick(course) }
                            .padding(4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = course.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
