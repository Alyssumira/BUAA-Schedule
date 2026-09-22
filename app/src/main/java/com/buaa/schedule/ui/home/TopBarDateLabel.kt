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
 * 这一行**实际显示的那一天**（T62②）：顶栏那枚「休/班」徽标只能挂在**这行字说的那一天**上，
 * 而不是挂在 `today` 上——浏览别的周时两行都在说那一周的周一（T56），
 * 徽标若自己去读 `today` 就成了第三份真相（"9月25日 星期一"旁边挂着中秋的休、
 * 而那个休其实属于今天）。日期与文案因此共用下面这一份解析，只可能同源。
 *
 * 跟随模式（[browseWeek] 为 null，或翻回来正好是当前周）取今天，一字不变；
 * 浏览别的周时取**那一周的周一**——网格表头自己就是这么排的，两行必须同源。
 *
 * 学期读不到（未设置学期 / 开学日期写坏）时退回今天：这一行宁可停在旧口径，
 * 也不该显示一个凭空算出来的日期。
 */
internal fun topBarDisplayDate(
    semesterStart: LocalDate?,
    currentWeek: Int?,
    browseWeek: Int?,
    today: LocalDate,
): LocalDate {
    val browsingWeek = browseWeek?.takeIf { it != currentWeek }
    val monday = if (browsingWeek != null && semesterStart != null) {
        SemesterWeekDates.mondayOf(semesterStart, browsingWeek)
    } else {
        null
    }
    return monday ?: today
}

/** 文案：[topBarDisplayDate] 那一句的格式化，格式化只在这一处（口径见 `TopBarDateWiringGuardTest`） */
internal fun topBarDateLabel(
    semesterStart: LocalDate?,
    currentWeek: Int?,
    browseWeek: Int?,
    today: LocalDate,
): String = topBarDisplayDate(semesterStart, currentWeek, browseWeek, today).format(TopBarDateFormat)
