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

/** 一栏最多画几行，与 `widget_two_day.xml` 的 `maxLines` 保持一致（两处不一致会显成"只有 4 节课"） */
internal const val TWO_DAY_MAX_LINES = 4

/** 一栏的字数预算：4 格宽约 250dp，两栏各 110dp 出头，扣掉 5 位时刻还能放 4 个汉字 */
internal const val TWO_DAY_NAME_CHARS = 4

/**
 * 一栏的正文：每节课一行「上课时刻 + 短名」，装不下时最后一行换成「＋N」。
 *
 * [courses] 由调用方按当天过滤、按节次排好序传进来；空列表返回空串，
 * 让调用方决定写「今天没有课」还是「假期中」——这一栏该说哪一句取决于有没有学期，
 * 而不是这里。
 *
 * 正在上的那一节行首加一个 ▸。没课的行前留一个空格而不是什么都不加：
 * 两栏是并排扫读的，时刻那一列对不齐就会一路歪下去。
 */
internal fun twoDayColumnLines(
    courses: List<Course>,
    slotTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    date: LocalDate,
    now: LocalDateTime,
): String = foldDayLines(
    courses.map { course ->
        val clock = slotTimes[course.startPeriod]?.first?.let { TWO_DAY_CLOCK.format(it) }
            ?: "第${course.startPeriod}节"
        val mark = if (widgetRowStatus(course.periods, date, slotTimes, now) == WidgetRowStatus.ONGOING) {
            "▸"
        } else {
            " "
        }
        "$mark$clock ${weekGridShortName(course.displayName, TWO_DAY_NAME_CHARS)}"
    },
    TWO_DAY_MAX_LINES,
)

/** 数字必须是 ASCII：与 [com.buaa.schedule.reminder.clockOf] 同一条理由（部分 locale 会输出非 ASCII 数字） */
private val TWO_DAY_CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
