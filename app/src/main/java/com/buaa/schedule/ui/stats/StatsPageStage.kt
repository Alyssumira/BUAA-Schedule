package com.buaa.schedule.ui.stats

/**
 * 统计页「加载中 / 真的空 / 有内容」三态判定的内核（T74，台账 #115）。
 *
 * 零 android import、零时钟读取（仓库口径，同
 * [com.buaa.schedule.ui.home.statsEntryBudgetPx] 那一族纯 JVM 判据）：
 * **就绪与否**与**课程条数**这两件事实由调用点当参数递进来 ——
 * 前者是 `ScheduleUiState.loading` 取反，后者是 `SemesterStats.summarize` 归并出的整门课数。
 * 内核自己去读 `LocalDate.now()` / `SystemClock` 的话，这张表在 JVM 里就打不开，
 * 而这一页栽的恰好就是"没读到的那一刻被当成了事实"这笔账。
 *
 * ## 病因（不是"仓库先回了空"，是首帧拿的是 `initialValue`）
 *
 * 改前 `StatsScreen` 的 `viewModel` 默认参数自己在 `"stats"` 这条路由的 `ViewModelStore` 上
 * **新造一枚** `ScheduleViewModel`（MainActivity 里其它页都是把 Activity 那枚传进去的），
 * 于是这一页的 `uiState` 从 `stateIn(WhileSubscribed(5_000), initialValue = ScheduleUiState())`
 * 的 `initialValue` 起步：`courses = emptyList()`、`loading = true`。
 * 那一档分支判据又是 `summary.courseCount == 0`，它不看 `loading`，
 * 就把"这一枚 VM 还没查到东西"说成了"你一门课都没有"。
 * T75 把默认参数摘了、这一页改吃 Activity 那枚 VM（台账 #116），**但这一档不跟着摘**：
 * 进程被杀后重建、磁盘慢的时候首帧照样可能是 `initialValue`，那时它还是唯一一句真话。
 *
 * 装机探针（release 包、pid 19917、`logcat -s T74PROBE`，原始序列存在 `.tmp/t74xml/T74-probe-logcat.txt`）
 * 量到的是这条：统计页那枚新 VM 的**上游三条流一条都没有先回过空** ——
 * `src courses n=22` 是 `repository.courses` 的第一发，`src semester=2026-2027-1`、
 * `src timeSlots n=14` 同理；`combine` 第一次发射就是 `courses=22 loading=false`。
 * 也就是说"空"从来不是数据库或仓库说的话，只有 `initialValue` 说过。
 * 同一次进入里，从这一页第一帧（abs=18552981，`loading=true courses=0`）
 * 到第一次拿到非空数据（abs=18556061）实测 **3080ms**（这台镜像 CPU 饥饿，真机窗口会更短）。
 *
 * ## 为什么就绪信号用现成的 `loading`，不新加一枚
 *
 * `ScheduleUiState.loading` 的语义就是"combine 还没发射过"：初值为 true，
 * 唯一写 false 的地方是 combine 的那次发射（与 `courses` 同帧到达）。
 * 首页早就是这么用的（`HomeScreen` 的 `showFirstRunEmpty = !state.loading && state.courses.isEmpty()`），
 * `MainActivity` 等深链也等的是它。本卡的红线是不许改 `uiState` 的对外形状与 Sharing 策略，
 * 而**读**一枚已经存在的只读字段既不换形状也不换 Sharing，所以这里不另起一炉。
 *
 * ## 取向：就绪信号没到就一律不说"空"
 *
 * `(ready = false, courseCount > 0)` 这一格在现下的 `uiState` 里到不了（`loading` 与
 * `courses` 同帧），留着是因为它**必须有个确定的答案**：数据先到、就绪信号后到那种流一旦
 * 出现，这一页宁可再画一帧加载，也不许把"有课"和"没课"两句话在同一秒里说两遍。
 * 负数按 0 处理，不抛 —— 这一页抛出去代价是整张统计没了，而 `courseCount` 是上游算出来的。
 */

/**
 * 这一页此刻站在哪一档。**声明顺序就是时间顺序**：还没读到 → 读到了、真的没课 → 读到了、有课。
 *
 * 三档互斥且穷尽 —— 调用点 `when` 不带 `else`，将来加一档而没画对应那一面，编译期就红。
 */
internal enum class StatsPageStage {
    /** 上游还没发过第一帧：这一档**不许**说任何关于"你有没有课"的断言 */
    Loading,

    /** 读到了，而且读到的确实是零门：此时那句"还没有课程可统计"才是真话 */
    Empty,

    /** 读到了，而且有课：画学分、每周负载、空档那整套 */
    Ready,
}

/**
 * 三态判定的全部算式：就绪与否先看，再看条数。
 *
 * @param ready 这一页的课表数据到齐了没有。调用点传 `!uiState.loading`，
 *   不许在这里换成"课程列表非空"—— 那正是改前把两档并成一档的那个写法。
 * @param courseCount **归并到整门课之后**的条数（`SemesterStats.SemesterSummary.courseCount`），
 *   不是排课片段数：18 门课在库里是 22 段，用片段数判会把档走对、把数说错。
 */
internal fun statsPageStageOf(ready: Boolean, courseCount: Int): StatsPageStage = when {
    !ready -> StatsPageStage.Loading
    courseCount <= 0 -> StatsPageStage.Empty
    else -> StatsPageStage.Ready
}
