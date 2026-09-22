package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.schedule.SemesterWeekDates
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
 * 跟随模式（[browseWeek] 为 null，或翻回来正好是当前周）显示今天，一字不变；
 * 浏览别的周时显示**那一周的周一**——网格表头自己就是这么排的，两行必须同源。
 *
 * 学期读不到（未设置学期 / 开学日期写坏）时退回今天：这一行宁可停在旧口径，
 * 也不该显示一个凭空算出来的日期。
 *
 * [browseDate] 是日视图**真正在画的那一天**（已经过 T68 的过期判定，没在浏览就传 null）。
 * ⚠️ 本枚提交里这个入参还没被函数体读 —— 那是 `HomeScreen` 现状的逐字转录，
 * 顶栏那一行与 body 那一天各说一天正是 T68 要修的第二处，测试先红一次。
 */
internal fun topBarDateLabel(
    semesterStart: LocalDate?,
    currentWeek: Int?,
    browseWeek: Int?,
    today: LocalDate,
    browseDate: LocalDate? = null,
): String {
    val browsingWeek = browseWeek?.takeIf { it != currentWeek }
    val monday = if (browsingWeek != null && semesterStart != null) {
        SemesterWeekDates.mondayOf(semesterStart, browsingWeek)
    } else {
        null
    }
    return (monday ?: today).format(TopBarDateFormat)
}
