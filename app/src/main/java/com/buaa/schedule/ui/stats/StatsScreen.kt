package com.buaa.schedule.ui.stats

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.DayLoadBars
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.EmptyState
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassTopBar
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.MiniBar
import com.buaa.schedule.core.designsystem.SectionHeader
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.domain.schedule.SemesterStats
import com.buaa.schedule.ui.ScheduleViewModel

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
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(LocalContext.current.applicationContext as android.app.Application),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val summary = remember(state.courses, state.semester, state.timeSlots) {
        SemesterStats.summarize(state.courses, state.semester, state.timeSlots)
    }
    // 柱状图吃的是 7 项平均分钟数；busiest 是 ISO 星期序号（1 = 周一），下标要退一格
    val dayMinutes = remember(summary) { summary.dayLoads.map { it.averageMinutes } }
    val busiestIndex = remember(summary) { summary.busiestDayOfWeek?.minus(1) }
    val maxCredit = remember(summary) { summary.perCourse.mapNotNull { it.credit }.maxOrNull() ?: 0.0 }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            GlassTopBar(
                title = "学期统计",
                onBack = onBack,
            )
        },
    ) { padding ->
        // 导入第一门课后这一页整版换血，硬切像重开了一遍；淡入淡出与日视图空态同源
        Crossfade(
            targetState = summary.courseCount == 0,
            animationSpec = motionSpec<Float>(),
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = DesignTokens.spaceL),
        ) { isEmpty ->
            if (isEmpty) {
                // 空态居中（与管理页同一口径）：贴在页顶会让这一页看起来"还没加载完"
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyStatsCard()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
                ) {
                    CreditHeadline(summary)
                    DayLoadCard(dayMinutes, busiestIndex, summary)
                    CreditListCard(summary.perCourse, maxCredit)
                    FreeSlotsCard(summary)
                    Spacer(modifier = Modifier.height(DesignTokens.spaceXL))
                }
            }
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

/** 总学分：页面上唯一一个大字号，其余卡片都不该和它抢 */
@Composable
private fun CreditHeadline(summary: SemesterStats.SemesterSummary) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceXS)) {
            Text(
                text = "本学期总学分",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = trimCredits(summary.totalCredits),
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
        }
    }
}

/** 课程色圆点：行内的颜色标记，不是图标——图标刻度最小档 [DesignTokens.iconSmall] 落在正文行里偏重 */
private val CourseDotSize = 10.dp

/** 每门课的学分：名字 + 一条占比条，占比条用课程自己的颜色，和课表上的色块对得上 */
@Composable
private fun CreditListCard(perCourse: List<SemesterStats.CourseCredit>, maxCredit: Double) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
            SectionHeader("课程学分")
            perCourse.forEach { item ->
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
                        MiniBar(
                            fraction = if (maxCredit <= 0.0) 0f
                            else (item.credit ?: 0.0).toFloat() / maxCredit.toFloat(),
                            color = courseColor(item.course),
                            modifier = Modifier.padding(top = DesignTokens.spaceXS),
                        )
                    }
                    Spacer(modifier = Modifier.width(DesignTokens.spaceS))
                    Text(
                        text = item.credit?.let { trimCredits(it) } ?: "—",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

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

// ---- 文案换算 ----

private fun weekdayChar(dayOfWeek: Int): String =
    "一二三四五六日".getOrElse(dayOfWeek - 1) { '?' }.toString()

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

/** 6.0 → "6"，3.5 → "3.5"：学分是 Double，但整数不该带着一串 .0 上桌 */
private fun trimCredits(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
