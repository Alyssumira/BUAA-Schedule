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
 * - `displayWeek == null && currentWeek != null`：唯一的调用点（HomeScreen 传
 *   `browseWeek ?: state.currentWeek`）造不出这一格，判 false 与改前同值，不放宽。
 */
internal fun shouldHighlightToday(
    displayWeek: Int?,
    currentWeek: Int?,
    hasSemester: Boolean,
): Boolean = !hasSemester || (currentWeek != null && displayWeek == currentWeek)
