package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import com.buaa.schedule.domain.model.Course
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 4×2 两栏组件：左栏今天、右栏明天，并排对照。
 *
 * 与「今日课程」「明日课程」的分工：那两个各自是一整块列表、能显示教室与节次；
 * 这一族回答的是"今天还剩几节、明天要不要早起"，靠把两天摆在一起才有价值。
 */
class TwoDayWidgetProvider : ScheduleAppWidgetProvider() {

    /** 宿主重绑后按「今明两栏」口径重绘（见 [ScheduleAppWidgetProvider]） */
    override fun rerenderAfterRebind(
        context: Context,
        appWidgetIds: IntArray,
        pendingResult: BroadcastReceiver.PendingResult?,
    ) {
        WidgetCommon.goAsyncUpdateTwoDay(context, appWidgetIds, pendingResult)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 与 TodayWidgetProvider 同口径：onEnabled 在广播主线程上跑，而
        // hasAnyWidget 的 binder 调用与 WorkManager.getInstance 都可能抛异常。
        WidgetCommon.bootstrapBackgroundSync(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetCommon.goAsyncUpdateTwoDay(context, appWidgetIds, goAsync())
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetAppearanceStore.remove(context, appWidgetIds)
        WidgetBindingStore.remove(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        BackgroundSync.cancelWidgetMidnightIfNoWidgets(context)
    }

    companion object {
        suspend fun updateAll(context: Context) =
            WidgetCommon.updateAllOfProviderTwoDay(context, TwoDayWidgetProvider::class.java)
    }
}

/**
 * 宿主没给出有效高度时的兜底行档。
 *
 * 正常路径不走这里：行数由 [twoDayMaxLines] 按组件实际高度算出来。
 */
internal const val TWO_DAY_MAX_LINES = 4

/** 行数上限，与 `widget_two_day.xml` 里正文的 `maxLines` 逐字一致 */
internal const val TWO_DAY_MAX_LINES_CEILING = 8

/** 上表头（标题 13sp + 栏头 11sp）加上下内边距与栏间距，合计约 52dp */
private const val TWO_DAY_CHROME_DP = 52f

/** 一行正文的行盒高度：11sp 文字约 13dp，再加 lineSpacingExtra 的 2dp */
private const val TWO_DAY_LINE_DP = 13f
private const val TWO_DAY_LINE_SPACING_DP = 2f

/**
 * 一栏画几行：由组件当前高度和系统字号决定。
 *
 * 写死 4 行是这次「显示有点问题」的另一半：4×2 只是名义尺寸，用户把它拉高、
 * 或宿主给的就是 5 行的空间时，多出来的那一行永远画不出来；反过来在矮机器上，
 * 4 行会顶到组件底边被裁成 3 行半。
 * [fontScale] 是系统字号倍数——大字号下同样的行数会把正文挤出底边。
 */
internal fun twoDayMaxLines(heightDp: Int, fontScale: Float): Int {
    if (heightDp <= 0) return TWO_DAY_MAX_LINES
    val lineHeight = TWO_DAY_LINE_DP * fontScale.coerceIn(0.8f, 2.4f) + TWO_DAY_LINE_SPACING_DP
    return ((heightDp - TWO_DAY_CHROME_DP) / lineHeight).toInt()
        .coerceIn(1, TWO_DAY_MAX_LINES_CEILING)
}

/** 一栏的字数预算：4 格宽约 250dp，两栏各 110dp 出头，扣掉 5 位时刻还能放 4 个汉字 */
internal const val TWO_DAY_NAME_CHARS = 4

/**
 * 一栏的正文：每节课一行「上课时刻 + 短名」，装不下时最后一行换成「＋N」。
 *
 * [courses] 由调用方按当天过滤、按节次排好序传进来；空列表返回空串，
 * 让调用方决定写「今天没有课」还是「假期中」——这一栏该说哪一句取决于有没有学期，
 * 而不是这里。
 *
 * 正在上的那一节在**行尾**加一个 ▸，其余行什么都不加。
 *
 * 记号不放行首：▸ 与空格的宽度并不相等，放行首等于每节课的时刻各歪一点，
 * 两栏并排扫读时时刻那一列就一路歪下去。行尾不存在这个问题——短名是定长截断的
 * （[TWO_DAY_NAME_CHARS]），▸ 只会贴在名字后面，顶不掉任何内容。
 */
internal fun twoDayColumnLines(
    courses: List<Course>,
    slotTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    date: LocalDate,
    now: LocalDateTime,
    maxLines: Int = TWO_DAY_MAX_LINES,
): String = foldDayLines(
    courses.map { course ->
        val clock = slotTimes[course.startPeriod]?.first?.let { TWO_DAY_CLOCK.format(it) }
            ?: "第${course.startPeriod}节"
        val mark = if (widgetRowStatus(course.periods, date, slotTimes, now) == WidgetRowStatus.ONGOING) {
            "▸"
        } else {
            ""
        }
        "$clock ${weekGridShortName(course.displayName, TWO_DAY_NAME_CHARS)}$mark"
    },
    maxLines,
)

/** 数字必须是 ASCII：与 [com.buaa.schedule.reminder.clockOf] 同一条理由（部分 locale 会输出非 ASCII 数字） */
private val TWO_DAY_CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
