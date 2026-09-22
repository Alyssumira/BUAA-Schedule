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
 *
 * T68b 在本文件里补了第二维：[dayViewOnScreen] 回答的是「日视图此刻**在不在屏上**」。
 * 上面那一份真相讲的是"画哪一天"，讲的是**假如**画出来该画哪天 —— 而周课表页签上它根本没画，
 * 把同一个值端给只在周页签渲染的顶栏第二行，就成了「写着 9月24日、屏上没有 9月24日」这笔新账。
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

/**
 * 「今日」页签的下标：与 `HomeScreen` 分段控件 `options = listOf("周课表", "今日")` 的次序、
 * 以及内容区 `when (tab) { 0 -> WeekView ; 1 -> DayView }` 的分支同源。
 * 由调用点把 `selectedTab` 原样递进来，内核只做比较、不猜语义。
 */
internal const val DAY_TAB_INDEX = 1

/**
 * 日视图此刻**真的画在屏上**吗（T68b）——「顶栏第二行能不能讲 body 那一天」的唯一闸。
 *
 * 这笔账是 T68 自己带来的：那一卡把 [dayViewDate] 算出的 `browseDateOnScreen` 喂给四个消费方，
 * 其中顶栏第二行 `topBarDateLabel(..., dateOnScreen = browseDateOnScreen)` 接错了一档 ——
 * `dateLabel` 只活在 `Crossfade(targetState = selectedTab == 0)` 的 `isWeekTab` 那一支，
 * 人在周课表页签时**日视图根本没渲染**，那一行却报日视图翻到的那一天。
 * 真机装机实测（f128bc02）：网格里高亮的今天是 9/22 周二，第二行写「9月24日 星期四」。
 * 于是 `OnScreen` 这个名字在这一档是假的：这句文案读的是一个屏上没有的日子。
 *
 * 三档输入合起来就是"日视图在不在屏上"这件事的全部：
 * - [selectedTab] == [DAY_TAB_INDEX]：窄屏的今日页签，日视图就是 body 本身 ⇒ 在屏上；
 * - [wideSplitLayout]：≥breakpointWide 那一档是「周 | 日」并排，两个页签下日视图都画着 ⇒ 在屏上；
 *   这一档正是 T68 传 `dateOnScreen` 唯一成立的场合，必须保住。
 * - [tabDecided]：首帧页签还没定下来时内容区**什么都不画**（`if (!tabDecided) { }`），
 *   此时 `browseDateOnScreen` 可能是从 saved-state 里恢复出来的旧浏览日，屏上并没有它 ⇒ 不在屏上。
 *
 * ⚠️ 页签、宽度、首帧标志这三样全是设备/表现层事实，一律由调用点读出来当参数传进来：
 * 内核不碰 `LocalConfiguration`（宽度换算留在 HomeScreen 那一行）、不读时钟、
 * 也不许"返回布尔以后调用点再各判一次 selectedTab"——判据本体只在这里写一遍，
 * 调用点只负责递参数与消费结论（仓库口径，见 [daySwipeCommit]）。
 *
 * ⚠️ 本卡只闸顶栏第二行这一处消费方。`dayTabHeadline`（今日页签那一行）不许走这道闸：
 * 人在今日页签时那一行本来就在屏上、日视图也本来就在屏上，它是 T68 修对的那一半。
 */
internal fun dayViewOnScreen(
    selectedTab: Int,
    wideSplitLayout: Boolean,
    tabDecided: Boolean,
): Boolean {
    // 首帧页签未定：内容区是空的，屏上没有任何一份课表可讲
    if (!tabDecided) return false
    return selectedTab == DAY_TAB_INDEX || wideSplitLayout
}
