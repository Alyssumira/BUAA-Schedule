package com.buaa.schedule.ui.home

/**
 * 周课表**表头**那一枚「今天」胶囊该不该亮的判据内核（T182）。
 *
 * 纯判据：零 android import、零时钟读取（本仓收单硬口径）—— 真实今天由调用点拿着 `today`
 * 递给 [DayHeader]，浏览周次 / 当前教学周 / 有没有学期这三样事实一律当参数传进来。
 * 内核自己去读 `LocalDate.now()` 就与 ViewModel 的跨午夜滴答脱钩了（T41/T43 同一族坑：
 * 跨过零点没有任何东西因此重组，今日列就此停在昨天）。
 *
 * ## 病（与同屏顶栏那句「假期中」自相矛盾的那一格）
 *
 * 改前那一句长在 [WeekView] 的组合期里：
 * `highlightToday = isBrowsingCurrentWeek || currentWeek == null`，
 * 而 `isBrowsingCurrentWeek = displayWeek == currentWeek`。寒暑假（或学期结束后）
 * `currentWeek` 取不到，于是同一个 null 被放行了两次：
 * ① `null == null` 让 `isBrowsingCurrentWeek` 自己就成立；
 * ② 析取支 `|| currentWeek == null` 再成立一次。
 * 胶囊就此扣在**学期第 1 周**里同星期几的那一格上 —— 表头那一格印的日期来自
 * [DayHeader] 的 `cellDate = weekStartDate?.plusDays(index)`，而 `weekStartDate` 由
 * `displayWeekNumber = displayWeek ?: currentWeek ?: 1` 派生，这一档正好退成 1 ⇒
 * 那一格印的是第 1 周某天的日期，**不是今天**，却被标成"今天"。
 * 同一屏顶栏第一行此时写着「假期中」（[weekHeadline]），两处各说各话。
 *
 * ## 口径对齐：同一枚函数早就把网格改成空的了
 *
 * [WeekGrid.coursesForContent] 里 `else -> emptyList()` 那一支，理由是「学期存在却取不到
 * 浏览周次 = 今天不在教学周里」，当时的原话就是"还和同屏顶栏那句「假期中」自相矛盾（P1）——
 * 宁可给一张空网格"。**表头这一侧当时没跟着改**：网格不再谎报某一天的课，
 * 表头却还在谎报哪一天是今天。本文件把那一侧的判据收到同一条口径上。
 *
 * ## 方向：只朝窄收，摘掉析取支里那枚参照物
 *
 * 不是翻分支（把 `A || B` 换成一堆 if-else 只算朝宽），而是**把 `currentWeek == null`
 * 这一枚参照物整个摘掉**，剩下的唯一依据是"表头这一格印的日期真的就是今天"。
 * 于是新判据在改前的真值集上是**严格子集**，只削掉会说谎的那几格，一格不加：
 * - `hasSemester && currentWeek == null`（假期、学期结束后，含用户手动跳到第 N 周那一档）⇒ false；
 * - 其余各格与改前逐格同值（正常当周 true、浏览往周 false、无学期 true）。
 *
 * ⚠️ **`hasSemester == false` 那一档是刻意留的方向感，不许一起关掉**：
 * 没有学期时 `weekStartDate` 就是 null ⇒ [DayHeader] 的 `cellDate` 全为 null，
 * 表头只印星期名不印日期，胶囊断言的只是"今天星期几"——与第几周无关，不构成谎话。
 * 那一档改成 false 会让人在没设学期时连"今天排在第几列"都找不到。
 *
 * ## 已知且接受的两格保守
 *
 * - 学期行在、`startDate` 是历史脏值解析不出来：`weekStartDate` 同样是 null，但本判据
 *   按 `hasSemester` 说话 ⇒ 这里给 false，只是少一枚方向感、不会说假话。要收回这一档
 *   得先把"表头到底印不印日期"单独递成一枚参数，不在本卡范围。
 *   **现状（T190 落地之后）**：日期那一半已由下面 [headerWeekNumberOrNull] 收回（无周次可显示时
 *   表头不印日期），但本档说的不是"无周次"，是"有周次而 `startLocalDate` 解析不出来"——
 *   那一格的入参两枚都非 null，[headerWeekNumberOrNull] 照原样给出周号，`weekStartDate` 仍由
 *   `semester?.startLocalDate` 自己算出 null ⇒ 表头不印日期、胶囊不亮，与 T182 当时逐格同值，
 *   这一档没有被 T190 收回（要收它得把"印不印"递成第三枚参数，仍不在那张卡的射程里）。
 * - `displayWeek == null && currentWeek != null`：唯一的调用点（HomeScreen 传
 *   `browseWeek ?: state.currentWeek`）造不出这一格，判 false 与改前同值，不放宽。
 */
internal fun shouldHighlightToday(
    displayWeek: Int?,
    currentWeek: Int?,
    hasSemester: Boolean,
): Boolean = !hasSemester || (currentWeek != null && displayWeek == currentWeek)

/**
 * 周课表**表头那一行日期**按「学期第几周」推出来的判据内核（T190）。
 *
 * 语义就一句：`displayWeek ?: currentWeek`，**零 `?: 1`** —— 结果永远等于两枚入参之一，
 * 两枚都是 null 就是 null。纯判据：零 android import、零时钟读数、零设备读数（本仓收单硬口径），
 * 浏览周次与当前教学周两样事实由调用点当参数递进来。
 *
 * ## 它为什么与 [shouldHighlightToday] 是同一枚矛盾的两半
 *
 * T182 修的是**胶囊**（表头不许谎报哪一格是今天），病灶却长在同一个组合函数里并排的**两枚**
 * 「现在显示第几周」的派生上：一枚不带 `?: 1`（Pager 那枚 `pagerWeek`），一枚带（表头日期用的
 * `displayWeekNumber`，当时原文是 `val displayWeekNumber = displayWeek ?: currentWeek ?: 1`）。
 * 寒暑假 / 学期结束后（有学期行、`currentWeek == null`、用户没手动浏览周次）诚实的那枚回 null ⇒
 * Pager 退回单页网格，造值的那枚退成 **1** ⇒ 表头照旧印出**第 1 周那七天**的日期，
 * 而屏幕上根本没有第 1 周、同屏顶栏第一行写着「假期中」（[weekHeadline]）。
 * 胶囊不再说谎了，日期那一半还在说 —— 本文件那半句"扣在错的那一格上"当时只收了格子没收日期。
 *
 * ## 口径：Pager 之上的两行注释早就把「不凭空造第 1 周」定成这一页的规则
 *
 * [WeekView] 里 `val pagerWeek = displayWeek ?: currentWeek`（`:296`）之上的原文（`:294-295`）：
 * 「没有周次信息（学期未设置）时退回单页网格，不凭空造出“第 1 周”误导用户。」
 * 表头跟着同一条口径才算自洽 ⇒ 无周次可显示时回 null，调用点据此把 `weekStartDate` 也留成 null，
 * [DayHeader] 的 `cellDate` 全为 null，那一行只剩星期名 —— 与"没设学期"时用户已经看到的形状一致
 * （正是 [shouldHighlightToday] 的 KDoc 里 `hasSemester == false` 那一档反复说的那枚形状）。
 *
 * ⚠️ 那两行注释只点名「学期未设置」，而 `pagerWeek` 为 null 的另一条出路正是本卡的这一档
 *   （学期行在、`currentWeek` 取不到）：口径本身盖得住，注释那句说得比行为窄，别照着它反推
 *   "只有没设学期才留空"。
 *
 * ## 为什么不是把 `today` 递进来算"今天真实所在那周"
 *
 * 另一枚修法（拿调用点已有的 `today` 反查日历↔学期周数）被否掉：它给一张**刻意留空**的网格
 * （[WeekGrid.coursesForContent] 的 `else -> emptyList()` 那一支）配上一行看起来能用的日期，
 * 而假期里那一天根本不对应任何学期周 —— 印出来的日期照样是造出来的，只是换了一枚来源。
 * 留空才是说实话。
 */
internal fun headerWeekNumberOrNull(displayWeek: Int?, currentWeek: Int?): Int? = displayWeek ?: currentWeek
