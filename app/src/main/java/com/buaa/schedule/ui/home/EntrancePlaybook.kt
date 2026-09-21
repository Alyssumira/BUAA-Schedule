package com.buaa.schedule.ui.home

/**
 * 课次卡片进场（T52②）的判据内核：**零 android / androidx import**，纯 JVM 可测。
 *
 * 这里只回答两件事：
 * 1. 「这一轮到底该不该播」——[EntrancePlaybook]，进程内一次性记忆；
 * 2. 「全局驱动走到 d 时，第 i 格该落定到几分」——[entranceSlotProgress]。
 *
 * 时长、缓动、reduce-motion 一律不在这里出现：那套口径全站只有 `Motion.kt` 一份，
 * 这里连一个毫秒数都不写（见下面「总长封顶由构造保证」）。
 */

/**
 * 进场的一次性记忆：**同一个键在整个进程里只播一遍**。
 *
 * 为什么不是 `remember` / `rememberSaveable`：
 * - `remember` 跟着组合走，周/日页签来回切一次就把标记清掉一次，于是每切一次重播一遍
 *   ——那正是「来回切页面不该反复播」要排除的行为；
 * - `rememberSaveable` 会跨进程存活，冷启动后这套界面永远不再有进场，而且它落的是
 *   "用户看过一次课表"这种一次性事实，不该污染可恢复的界面状态。
 *
 * 仓库里同族的口径是 [com.buaa.schedule.core.designsystem.Personalization] 那种
 * 「object + 内存态」的一次性记忆（本卡不许改 Personalization.kt，所以状态放这里）。
 *
 * 线程：Compose 的读取全在 UI 线程，但这是个进程级可变对象，加锁的成本在
 * 「一次进场问一次」这个频率上等于零，换一个无锁的隐患不值当。
 */
internal object EntrancePlaybook {

    private val played = mutableSetOf<String>()

    /** 这一轮该播吗：true = 本进程里第一次有人为 [key] 讨要进场。 */
    fun claim(key: String): Boolean = synchronized(this) {
        if (key in played) false else { played += key; true }
    }

    /**
     * 进场记忆的键：全站只有这三个，且**一律是常量**。
     *
     * 键里一旦掺进日期、课程数这类会变的值，改一次数据就是一个新键，
     * 「只播一次」立刻退化成「改一次重播一遍」——那正是本卡要排除的行为。
     * 三种模式各算一次进场：从列表切到时间轴确实换了一张表画法。
     */
    const val DAY_LIST = "day-list"
    const val DAY_TIMELINE = "day-timeline"
    const val WEEK_GRID = "week-grid"

    /** 播过了没有：只读，判据测试与调试用。 */
    fun hasPlayed(key: String): Boolean = synchronized(this) { key in played }

    /** 清空记忆。生产代码不许调用它——它存在的全部理由是单测要能重复跑。 */
    fun resetForTests() = synchronized(this) { played.clear() }
}

/**
 * 一格进场的窗口长度（占整条驱动的分数）。
 *
 * 0.45 是让两件事同时成立的最小值：末格恰好在全局驱动收尾的那一刻落定（见
 * [entranceSlotProgress] 的构造），而相邻两格仍有 55% 的时长重叠——
 * 读起来是"一路铺开"，不是"逐条排队"。
 */
internal const val ENTRANCE_WINDOW_FRACTION = 0.45f

/**
 * 全局驱动 [driver]（0f..1f，一条动画，不是每格一条）时，第 [slot] 格（共 [slotCount] 格）
 * 自己该落到几分。
 *
 * **总时长封顶是构造保证的，不是算出来的**：第 i 格从 `i/(n-1)·(1-w)` 起步、走 `w` 那么长，
 * 末格正好在 driver=1 那一刻落定——于是"多少格"只改变步长、不改变收尾时刻。
 * 20 节课一起进场与 2 节课一起进场，末条落定的时刻相同（= 那条驱动的时长），
 * 条目多时自动压步长，不需要"排队"，也就不需要一个随条数增长的时长常数。
 *
 * 边界都是要命的地方，逐条写死：
 * - [slotCount] ≤ 1：整条驱动就是这一格自己的进度（没有可错开的对象，也绝不能出现除零）；
 *   注意 **slot=0 不是这种退化情形**，它就是"第 1 格"，窗口从 0 起、长度为 `w`——
 *   把它特判成整条驱动，第一格会比后面所有格慢一倍多，读起来像"卡在第一张了"；
 * - [slot] 越界（数据变了而驱动还在跑）：两端都夹进 `[0, slotCount-1]`，
 *   比让它按 `n-1=0` 除零或算出负起点开好；
 * - 输入驱动越界：先夹 0..1 再算，输出的仍是 0..1。
 */
internal fun entranceSlotProgress(
    driver: Float,
    slot: Int,
    slotCount: Int,
    windowFraction: Float = ENTRANCE_WINDOW_FRACTION,
): Float {
    val d = driver.coerceIn(0f, 1f)
    if (slotCount <= 1) return d
    val lastSlot = (slotCount - 1).toFloat()
    val w = windowFraction.coerceIn(0.05f, 1f)
    val start = (slot.coerceIn(0, slotCount - 1).toFloat() / lastSlot) * (1f - w)
    return ((d - start) / w).coerceIn(0f, 1f)
}

/**
 * 第 [slot] 格落定时刻占整条驱动的分数（任一格都 ≤ 1，末格正好 = 1 = 总长不随格数增长）。
 * 单列一个函数是因为这条性质就是「总时长封顶」的可执行定义，值得被钉在测试里。
 */
internal fun entranceSettleFraction(
    slot: Int,
    slotCount: Int,
    windowFraction: Float = ENTRANCE_WINDOW_FRACTION,
): Float {
    val w = windowFraction.coerceIn(0.05f, 1f)
    if (slotCount <= 1) return 1f
    val lastSlot = (slotCount - 1).toFloat()
    val start = (slot.coerceIn(0, slotCount - 1).toFloat() / lastSlot) * (1f - w)
    return (start + w).coerceAtMost(1f)
}
