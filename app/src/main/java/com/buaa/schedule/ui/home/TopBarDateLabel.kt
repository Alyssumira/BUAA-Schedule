package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.schedule.SemesterWeekDates
import com.buaa.schedule.domain.schedule.WeekCalculator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 顶栏第二行的日期。
 *
 * 单独成文件的理由是可测：装机实测「第3周（浏览）」配「9月21日 星期一」，
 * 而 9/21 是第 4 周的周一——第一行跟着 browseWeek 走、第二行永远写今天，
 * 两行自相矛盾，用户读成 app 算错了周。周→日期的换算不在这里做，
 * 走 [SemesterWeekDates]（全仓唯一一份）。
 */

private val TopBarDateFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

/**
 * 三档，优先级从高到低：
 *
 * ① [dateOnScreen] —— 日视图**真的在画**的那一天（T68 新加的一档）。它非今天、
 *    且落在第一行说的那一周里时，第二行就说它：顶栏与 body 同源。
 *    落在别的那一周时不跟（见下方 [dayInsideDisplayedWeek] 的取舍）。
 * ② 浏览别的周时显示**那一周的周一**——网格表头自己就是这么排的，两行必须同源（T56）。
 * ③ 跟随模式（没浏览、或翻回来正好是当前周）显示今天，一字不变。
 *
 * 学期读不到（未设置学期 / 开学日期写坏）时退回今天：这一行宁可停在旧口径，
 * 也不该显示一个凭空算出来的日期。
 */
internal fun topBarDateLabel(
    semesterStart: LocalDate?,
    currentWeek: Int?,
    browseWeek: Int?,
    today: LocalDate,
    dateOnScreen: LocalDate = today,
): String {
    val browsingWeek = browseWeek?.takeIf { it != currentWeek }
    val displayWeek = browseWeek ?: currentWeek
    val mondayOfDisplayedWeek = if (semesterStart != null && displayWeek != null) {
        SemesterWeekDates.mondayOf(semesterStart, displayWeek)
    } else {
        null
    }
    val weekMonday = if (browsingWeek != null) mondayOfDisplayedWeek else null
    return (dayInsideDisplayedWeek(dateOnScreen, today, mondayOfDisplayedWeek)
        ?: weekMonday
        ?: today)
        .format(TopBarDateFormat)
}

/**
 * body 那一天能不能拿来讲给顶栏听：只认「与第一行那一周不冲突」的这一种。
 *
 * 越出该周就不认，是因为顶栏第一行写的是「第 N 周（浏览）」——第二行改口报别的一周的某天，
 * 就又是 T56 要治的那处自相矛盾（那时治的是"第二行永远写今天"，方向不能反着偏）。
 * 而这一档在窄屏上根本不会出现：日视图翻到的那天多半就在本周。
 */
private fun dayInsideDisplayedWeek(
    dateOnScreen: LocalDate,
    today: LocalDate,
    mondayOfDisplayedWeek: LocalDate?,
): LocalDate? {
    if (mondayOfDisplayedWeek == null || dateOnScreen == today) return null
    // 日期 → 所在自然周周一的换算走 WeekCalculator（全仓唯一一份锚点公式）
    return dateOnScreen.takeIf { WeekCalculator.mondayOf(it) == mondayOfDisplayedWeek }
}
