package com.buaa.schedule.ui.home

import java.time.LocalDate

/**
 * 「日视图这一帧画哪一天」的判据内核（T68）。纯判据，零 android import
 * （仓库口径，见 [topBarDateLabel] 与 `ScanCameraAidPolicy`）：真实今天、
 * 存着的浏览日、这一笔浏览是哪一天按下的 —— 一律由调用点读出来当参数传进来，
 * 本文件不读时钟、不碰快照、不认设备。
 *
 * ⚠️ 本枚提交里的三个函数体是 `HomeScreen` **现状的逐字转录**（缺陷照抄），
 * 不是修好的判据：三个测试先红一次，才证明「凌晨还是显示前一天的课表」这句话
 * 落在下面这三行算术上，而不是落在别处。下一枚换成真判据。
 */

/**
 * 用户手动翻到的那一天还在不在用。
 *
 * 现状：`browseDate` 非空就用，[anchoredOn]（这一笔浏览是按下的哪一天）根本没参与。
 */
internal fun inUseBrowseDate(
    today: LocalDate,
    anchoredOn: LocalDate?,
    browseDate: LocalDate?,
): LocalDate? {
    val date = browseDate ?: today
    return if (date == today) null else date
}

/** body 这一帧画哪一天：顶栏第二行与「回到今天」的可见性都该从这一个值算。 */
internal fun dayViewDate(
    today: LocalDate,
    anchoredOn: LocalDate?,
    browseDate: LocalDate?,
): LocalDate = inUseBrowseDate(today, anchoredOn, browseDate) ?: today

/** 今日页顶栏第一行的标题。现状：与 body 那一天无关，恒为「今日课表」。 */
internal fun dayTabHeadline(today: LocalDate, dateOnScreen: LocalDate): String = "今日课表"
