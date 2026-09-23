package com.buaa.schedule.core

/**
 * 「这一次启动要不要认 Intent 上那三枚一次性导航请求」的判据内核（T71，台账 #112）。
 *
 * 纯判据：零 android import、零时钟读取（仓库口径见 `ui/home/DayBrowsePolicy.kt` 的文件头）——
 * `Intent` 的解析与 `savedInstanceState` 的读取全留在 `MainActivity` 的调用点，
 * 内核只吃已经抽出来的**原始整数 / 可空字符串 / 布尔**。
 *
 * ## 用户报的是效果
 *
 * 「我没点任何东西，它自己跳到某一天」。账在 `MainActivity.onCreate` 那三行
 * （基点 `3e4ae8a` 的 :151-153）：它们**无条件**把启动 Intent 上的请求抄进状态。
 * 而 `MainActivity` 是 launcher 页（`launchMode=singleTop`），三枚请求的生产方**全部**是
 * `PendingIntent.getActivity`：
 * - 星期几：4×2 网格每一格的 fillInIntent（`widget/WeekGridWidgetService.kt:173`，`position + 1`），
 *   模板缺省 0（`widget/WidgetCommon.kt:770`，PI 见 :774）；
 * - 课程 id：组件每一行的 fillInIntent（`widget/CourseListWidgetService.kt:265`，模板 :315-326）
 *   与通知按钮（`reminder/ReminderNotifications.kt:384`）；
 * - 页内路由：通知按钮（`reminder/ReminderNotifications.kt:385`，PI 见 :387-392）。
 * 非 Activity 上下文发 Activity 意图时框架会补上 `FLAG_ACTIVITY_NEW_TASK` —— 装机实测
 * `dumpsys activity activities com.buaa.schedule` 打出的任务根 intent 是
 * `Intent { flg=0x10000000 cmp=com.buaa.schedule/.MainActivity (has extras) }` 且 `rootOfTask=true`，
 * 也就是说**用户那一次点击的 intent 从此留在任务栈根上**。进程被杀、任务还活着的时候，
 * 用户点桌面图标进来，系统重放那枚根 intent ⇒ `onCreate` 又把那一天（或那个编辑页、
 * 那页扫码）设一遍。锚定日（T68）只把这一笔压到"次日作废"，没修根。
 *
 * ## 判据
 *
 * 取「这个 Activity 实例是不是一个**已经跑过的**实例的重建」，而不是"该认哪一枚 extra"：
 * `savedInstanceState != null` 意味着系统手里存着本实例先前那次生命周期的状态 —— 那次生命周期里
 * 这三枚请求要么已经落地并被消费方一次性取走（`onDayRequestConsumed` 那一族），
 * 要么落到一半进程就死了；两种情况下"再落一次"都不是用户此刻按下的事。
 * 三档请求（日子 / 编辑页 / 页内路由）**同这一档**：差别只在生产方是谁，不在新鲜度语义，
 * 所以三档共用同一个 `hasSavedState` 闸门，而各自的取值域判据仍然分开设（下表后三行）。
 *
 * 装机实测（buaa36 / emulator-5556 / debug 包，`onCreate` 与 `onNewIntent` 各打一行探针）：
 * | 场景 | onCreate | onNewIntent |
 * |---|---|---|
 * | 冷启动点组件格子（第 5 格） | `saved=false day=5` | —— |
 * | 进程被杀后点桌面图标 | `saved=true day=5`（重放的根 intent） | `day=null`，`act=MAIN cats={LAUNCHER}` |
 * | 进程被杀后再点格子（第 1 格） | `saved=true day=5`（陈旧） | `day=1`（这一次点击） |
 * | 转屏 | `saved=true day=1`（同一枚 intent） | —— |
 * 于是这一道闸同时保住"点格子→落到那天"（第一、三行都靠 `saved=false` 或 `onNewIntent`），
 * 又堵掉"图标进来自己跳"（第二行）与"转一次屏又跳回组件那天"（第四行，实测：点过格子 →
 * 按「回到今天」→ 转屏 → 又回到 9月21日）。
 *
 * ## 为什么需要内核而不是一行 `if`
 *
 * 因为这条判据要在**两个入口**上说同一句话，而基点上这两个入口本来就不一致
 * （`onCreate` 无条件赋值 vs `onNewIntent` 的 `?.let`）—— 那处自相矛盾就是本卡的病。
 * 一行 `if (savedInstanceState == null)` 只能治 `onCreate` 那一处，取值域那三档判据
 * （缺省哨兵 -1 / 0、白名单、1..7）仍散在 `courseIdFrom` / `routeFrom` / `dayOfWeekFrom`
 * 里，`onNewIntent` 再抄一遍。收进 [launchRequestsOf] 之后，两个调用点唯一能差别的地方
 * 就只剩传进去的那个布尔，守卫也才有的可钉（见 `core/LaunchRequestWiringGuardTest`）。
 */

/** [rawCourseId] 的缺省哨兵：与 `Intent.getLongExtra` 的缺省值、组件模板的缺省值同源（`WidgetCommon.kt:316`） */
internal const val NO_COURSE_ID = -1L

/**
 * [rawDayOfWeek] 的缺省哨兵：4×2 网格模板 intent 里预置的那枚 0（`WidgetCommon.kt:770`）。
 * 非组件进来的普通启动根本没有这个键，`getIntExtra` 同样给出 0 —— 两者都算「无请求」。
 */
internal const val NO_DAY_OF_WEEK = 0

/** 星期序号的取值域：ISO `1`=周一 … `7`=周日；越界（含缺省 0）一律按「无请求」 */
internal val DAY_OF_WEEK_RANGE = 1..7

/** 一次启动的结论：三枚一次性请求各自要落地成什么；null = 这一档不落地 */
internal data class LaunchRequests(
    val courseId: Long?,
    val route: String?,
    val dayOfWeek: Int?,
)

/**
 * 判据本体。输入全是调用点抽好的原始值 —— 内核不碰 `Intent`，也不接 `savedInstanceState` 本身，
 * 只接「系统有没有存着上一次生命周期的状态」这一枚布尔。
 *
 * @param rawCourseId `getLongExtra(EXTRA_COURSE_ID, NO_COURSE_ID)` 的原值
 * @param rawRoute `getStringExtra(EXTRA_ROUTE)` 的原值（可为 null）
 * @param rawDayOfWeek `getIntExtra(EXTRA_DAY_OF_WEEK, NO_DAY_OF_WEEK)` 的原值
 * @param routableRoutes 白名单：`MainActivity` 是 launcher 页，外部应用能直接带 extra 进来，
 *   所以路由只认通知链路真正会发的那几条（判据在调用点的集合里，内核只做成员判定）
 * @param hasSavedState `savedInstanceState != null`：为真就是重建，三档一起不认
 */
internal fun launchRequestsOf(
    rawCourseId: Long,
    rawRoute: String?,
    rawDayOfWeek: Int,
    routableRoutes: Set<String>,
    hasSavedState: Boolean,
): LaunchRequests {
    // 重建：Intent 上那枚 extra 是上一次生命周期玩剩下的，用户这一次按的是桌面图标
    if (hasSavedState) return LaunchRequests(courseId = null, route = null, dayOfWeek = null)
    return LaunchRequests(
        courseId = rawCourseId.takeIf { it >= 0L },
        route = rawRoute?.takeIf { it in routableRoutes },
        dayOfWeek = rawDayOfWeek.takeIf { it in DAY_OF_WEEK_RANGE },
    )
}
