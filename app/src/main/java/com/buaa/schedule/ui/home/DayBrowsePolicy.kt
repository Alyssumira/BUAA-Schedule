package com.buaa.schedule.ui.home

import java.time.LocalDate

/**
 * 「日视图这一帧画哪一天」的判据内核（T68）。纯判据，零 android import、零时钟读取
 * （仓库口径，见 [topBarDateLabel] 与 `ScanCameraAidPolicy`）：真实今天、存着的浏览日、
 * 以及**这一笔浏览是按下的哪一天** —— 一律由调用点读出来当参数传进来。
 * 内核自己读当下就与 ViewModel 的跨午夜滴答脱钩了（T41/T43 那一类坑）。
 *
 * 用户报的是效果：「为什么凌晨还是显示前一天的课表」。账在这里：
 * `HomeScreen` 把「浏览到哪一天」存在 `rememberSaveable` 的槽位里（转屏、进程被杀都得活），
 * 而决定 body 的那一天只有 `browseDate ?: today` 这一行算术 ——
 * **没有任何东西在 today 前进时把它作废**。于是昨天翻过一天（或点过桌面组件的某一格），
 * 今早冷启动日视图仍停在那一天。光看这一句还能理解成"app 算错了"：
 * 顶栏/标题那一行讲的是今天，body 画的是用户翻到的那一天，两行各说一天
 * （与 T56 那一族同一条因果链：那次修的是周，日没修）。
 *
 * 判据取「锚定日」而不是「上一次见过的今天」：一笔浏览只在**它被按下的那一天之内**有效，
 * 与恢复次序、进程死活、滴答迟不迟到都无关 —— 槽位是从 saved-state 里回来的两个整数，
 * 只要锚定日与今天不是同一个日子就作废。方向一律偏「跟随今天」：
 * 锚定日缺失、锚定日落在未来（改过系统时间、跨国往西）都不复活旧浏览日。
 *
 * ⚠️ 只让**日**视图的浏览位置让位。「浏览到哪一**周**」不在本卡范围里：那一维在顶栏第一行
 * 明写着「（浏览）」、并有「跳到本周」这个显式入口，读到的是"我在看第 3 周"而不是"今天在第 3 周"，
 * 不构成谎话；跨午夜把它一起清了反而会让人半夜查下节课时莫名丢回本周。
 */

/**
 * 用户手动翻到的那一天还在不在用：null = 不作数，跟随今天。
 *
 * @param today 真实今天（`state.today`，由 ViewModel 的跨午夜滴答推进）
 * @param anchoredOn 这一笔浏览是按下的那一天；null = 从没手动选过
 * @param browseDate 存着的浏览日；null = 从没手动选过
 */
internal fun inUseBrowseDate(
    today: LocalDate,
    anchoredOn: LocalDate?,
    browseDate: LocalDate?,
): LocalDate? {
    if (browseDate == null || anchoredOn == null) return null
    // 跨午夜（或改时钟）以后这笔浏览就过期了：翻日子不是模式，它只活一天
    if (anchoredOn != today) return null
    return browseDate.takeIf { it != today }
}

/**
 * body 这一帧画哪一天 —— 全页唯一一份真相：顶栏第二行、今日页标题、
 * 「回到今天」的可见条件、屏上月份都从这一个值算，不再各读一次 `browseDate`。
 */
internal fun dayViewDate(
    today: LocalDate,
    anchoredOn: LocalDate?,
    browseDate: LocalDate?,
): LocalDate = inUseBrowseDate(today, anchoredOn, browseDate) ?: today

/**
 * 今日页顶栏第一行：body 画着别的日子时不许自称「今日」。
 *
 * 措辞沿用本仓「（浏览）」这一记号（见 [weekHeadline] 的「第3周（浏览）」），
 * 不写具体日期 —— 日视图页头紧挨着下面就写着「9月22日 · 星期二」，
 * 顶栏再摆一遍日期是以前那张「三个第 N 周 + 两个日期」的返工单要治的东西。
 */
internal fun dayTabHeadline(today: LocalDate, dateOnScreen: LocalDate): String =
    if (dateOnScreen == today) "今日课表" else "课表（浏览）"
