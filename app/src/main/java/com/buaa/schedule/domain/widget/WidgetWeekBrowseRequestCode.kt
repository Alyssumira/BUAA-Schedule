package com.buaa.schedule.domain.widget

/**
 * 桌面组件「‹ 上周 / 下周 ›」两枚热区的 PendingIntent requestCode —— 本仓唯一一份具名读数（T329，台账 #577）。
 *
 * 纯 JVM 判据：零 android / androidx import，不读钟、不读时区、不读 `Context`、不探桌面 ——
 * 两枚事实（哪一枚实例、往哪个方向翻）由调用点（`widget/WidgetCommon.kt` 的 `applyWeekBrowse`）
 * 当参数递进来，本文件一个字都不自己去取（本仓明文口径）。
 *
 * ## 病在哪（改前那一形）
 *
 * 改前这枚 requestCode 是调用点里的一行内联算术：基准 200_000，加裸 `appWidgetId`，再按方向加 0 或 1。
 * 于是**实例 k 的「下一周」与实例 k+1 的「上一周」算出同一个数**
 * （`200_000 + k + 1 == 200_000 + (k+1) + 0`）—— 算术必然，不需要任何运行期前提。
 * 而 `PendingIntent` 的缓存键不含 extras（`Intent.filterEquals` 不比 extras，framework 文档口径），
 * 两枚热区于是落在同一枚 PendingIntent 上；下发侧带 `FLAG_UPDATE_CURRENT`，
 * 按 framework 语义，后写入那一手会把它的 extras 覆写成自己那份 `(appWidgetId, delta)`。
 * 后果（**这一档是 framework 行为推断，本卡零 adb、零设备读数**）：点 k 的「下一周」，
 * 广播里可能带着 k+1 与 -1 —— 屏上那枚组件没动，而另一枚组件的周次偏移被写进了 prefs
 * （接收侧 `goAsyncBrowseWeek` → `browseWeek` 走 `WidgetBindingStore.load/save(context, appWidgetId, …)`，
 * 键是单枚 id ⇒ 周次偏移按实例记账，写错 id 就是写进另一枚组件的账）。
 *
 * ## 修法：把 (appWidgetId, 方向) 钉成单射
 *
 * [requestCode] 给一枚实例占 [LANE_COUNT] 格：`BASE + appWidgetId * LANE_COUNT + laneOf(delta)`。
 * 相邻实例不再共享边界 —— 实例 k 的两格是 `BASE+2k` 与 `BASE+2k+1`，实例 k+1 的两格是
 * `BASE+2k+2` 与 `BASE+2k+3`，两两不交；同一枚实例的两格也由 [laneOf] 分开。
 * 域：桌面分配的 `appWidgetId` 是从小往上的小整数，本读数在 `|appWidgetId| ≤ 1_000_000_000`
 * 之内保证两两不同；再往外 Int 算术要溢出，单射不再成立（那种 id 在盘面上取不到；真到那一天
 * 是换 Long 或换分块方案的活，不是把乘号删掉的活 —— 删乘号就是本文件点名的病灶回潮）。
 *
 * ## 与另两族的关系（本卡只收单射账，不收跨族分块的账）
 *
 * 同一枚实例还有另两族热区：列表/格子点击用裸 `appWidgetId`，头部点击用 `appWidgetId + 100_000`。
 * 三族只在取值区间上分开，没有任何一处代码保证它们互不覆盖：`appWidgetId ≥ 100_000` 时
 * 三族会互相撞进同一枚数（登记进回执的遗留问题，不在本卡收）。
 * 本卡不动那两族的数值 —— 改了会把已装机的那两族 PendingIntent 全部作废一次，代价与本卡收益不成比例。
 */
object WidgetWeekBrowseRequestCode {

    /** 这一族的起点：与「列表/格子点击」（裸 id）、「头部点击」（id + 100_000）分块用 */
    const val BASE = 200_000

    /** 一枚实例在这一族里占的格数：上一周、下一周各一格 */
    const val LANE_COUNT = 2

    /** 「‹ 上周」那一格（`delta <= 0`） */
    const val PREV_LANE = 0

    /** 「下周 ›」那一格（`delta > 0`） */
    const val NEXT_LANE = 1

    /**
     * 方向 → 档位：只认符号，不认步长。
     *
     * 调用点这一趟循环递进来的是 -1 与 +1（`R.id.widget_week_prev to -1`、
     * `R.id.widget_week_next to 1`）；`delta == 0` 在接收侧 `goAsyncBrowseWeek` 就被挡掉了，
     * 落在这一档时归入「上周」那一格，与改前的 `if (delta > 0) 1 else 0` 同一方向口径。
     */
    fun laneOf(delta: Int): Int = if (delta > 0) NEXT_LANE else PREV_LANE

    /**
     * `(appWidgetId, delta)` → 该热区唯一的 requestCode。
     *
     * 单射性由「实例项乘 [LANE_COUNT]」与「方向项走 [laneOf]」两枚一起保证：
     * 少任何一枚，两个方向或两枚实例就会当场并回同一枚数（本卡点名的两种退化，
     * 由 `WidgetWeekBrowseRequestCodeWiringGuardTest` 与 `WidgetWeekBrowseRequestCodeTest` 各拦一头）。
     */
    fun requestCode(appWidgetId: Int, delta: Int): Int =
        BASE + appWidgetId * LANE_COUNT + laneOf(delta)
}
