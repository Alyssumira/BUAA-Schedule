package com.buaa.schedule.ui.home

/**
 * 页头那一条（首页第一行 / 统计页页头）此刻归谁。
 *
 * 为什么需要这一枚判据（T80）：`"stats"` 不是一级页签，`navMotionFor` 给它配的是
 * [NavMotion.SLIDE]（`MainActivity.kt` 的 else 分支），而 SLIDE 的进出场两侧都是
 * 260ms（`MotionTokens.DURATION_MEDIUM`）—— 这 260ms 里**新旧两页同时在场**。
 * 装机量过：两页的页头条落在同一条带上（首页 `第4周 / 9月24日 / 学期统计胶囊 / 周课表|今日`
 * 占 y=158..284，统计页 `返回 / 学期统计` 占 y=157..283，实高都是 126px = 48dp 触控下限），
 * 于是过渡期同一块板上叠着两套文字 —— 用户读作"页头文字跳动""跳转前后割裂"。
 *
 * 收口的形状是：**一条带、一个主人**。带的几何写在 `ScheduleHeaderBand`（只有一份），
 * 板上画谁家的字由这里答一次。答"不在台上的那一页不许画字"，而不是"画了但透明"：
 * 后者在语义树里仍是两套文字，读屏与 uiautomator 都拿得到。
 *
 * 判据本身**零 android import、零时钟与设备读取**（仓库口径，见 [StatsEntryPolicy]）：
 * "当前是哪条路由"这一枚事实由调用点（`AppNavHost`，那里有 `currentBackStackEntryAsState()`）
 * 当参数传进来。这里不许出现 `BuildConfig` / `Locale` / 任何导航对象。
 */
enum class HeaderBandOwner {
    /** 首页那一套：周次 + 日期 + 学期统计胶囊 + 分段控件 */
    Home,

    /** 统计页那一套：返回 + 学期统计 */
    Stats,
}

/**
 * 当前路由 → 页头条的主人。
 *
 * 除 `"stats"` 之外一律归 [HeaderBandOwner.Home]：这是**保守**的那一侧 —— 首页之外的那些
 * 页面（编辑器 / 导入 / 设置 …）自带各自的 `GlassTopBar`，页头条在它们跳转期间画不画字
 * 都影响不到它们；而把默认判成"首页在台上"意味着除了明确去统计页之外，首页的页头行为
 * 与改前一个字都不差。
 */
fun headerBandOwnerOf(route: String?): HeaderBandOwner =
    if (route == STATS_ROUTE_NAME) HeaderBandOwner.Stats else HeaderBandOwner.Home

/**
 * 这一页此刻该不该在页头条上画字。
 *
 * 单独抽出来是为了让"只有一家在画"这句话在**源码层**就成立：两个调用点各自写一遍
 * `owner == Home` / `owner == Stats` 的话，哪天加第三种主人就会两家同时画（或同时不画）。
 */
fun headerBandDrawnOnScreen(owner: HeaderBandOwner, of: HeaderBandOwner): Boolean = owner == of

/**
 * `"stats"` 这条路由名。
 *
 * ⚠️ 这里**故意不**给 `MainActivity` 用：那条 `composable("stats")` 与 `openStats` 里的
 * `navigate("stats")` 都由 `StatsEntryWiringGuardTest` ①-b / ③-a 按字面量钉着（全仓一份、
 * 只此一处），把路由名换成常量会让那两档红在"锚点找不到"上，而不是红在真缺陷上。
 * 这一枚常数只服务本页头条的判定。
 */
const val STATS_ROUTE_NAME: String = "stats"

/**
 * 页头条不在台上时的占位高度（px）。
 *
 * 量到过就用上一次量到的实高（系统字号调大时首页那一列两行字会高过 48dp 下限，
 * 用下限会把正文往上抬），没量到过、或量到一枚不是高度的数才退到 [fallbackHeightPx]
 * （48dp 触控下限 = 装机实高 126px）。非正数一并走兜底：一枚 0 高的占位会把正文顶到屏沿，
 * 那正是这一枚参数要防的那件事。两枚输入都是调用点量好的整数，判据只做一次挑选。
 */
fun headerBandPlaceholderHeightPx(measuredHeightPx: Int?, fallbackHeightPx: Int): Int =
    measuredHeightPx?.takeIf { it > 0 } ?: fallbackHeightPx
