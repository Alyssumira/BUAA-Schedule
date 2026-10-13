package com.buaa.schedule.domain.importing

/**
 * 事实的**载体**有哪几档（T336①，在册 #412）。
 *
 * 这三档的差别不是"存不存得下来"，而是**寿命长在哪一段**：
 * - [COMPOSITION]：组合活着它活着，组合一毁它归零 —— 转屏（本仓 Manifest 零枚 `configChanges`，
 *   复算尺 `grep -c configChanges app/src/main/AndroidManifest.xml` → 0）就是"毁"；
 * - [SNAPSHOT]：`rememberSaveable`，读数跨得过重建，**但它的读写点还是长在组合上**；
 * - [PROCESS_HOLDER]：进程内 holder（本仓那一族的先例：`data/import/BuaaWebSession.kt` 持会话 WebView、
 *   `update/UpdateCheck.kt` 的 `downloadScope`）—— 寿命与组合无关。
 */
enum class FactCarrier {
    COMPOSITION,
    SNAPSHOT,
    PROCESS_HOLDER,
}

/**
 * 「把这枚事实放进这枚载体」的结论（[FetchJobCarrierPolicy.verdict] 的答案档）。
 */
enum class CarrierVerdict {
    /** 载体寿命 ≥ 该事实的应有寿命，放这儿是对的 */
    Supported,

    /** 在飞事实 + 组合载体：读数与作业同归于重建 —— 盘上今天那一形 */
    JobDiesWithDigits,

    /** 在飞事实 + 快照载体：数字活下来、作业仍死在组合上 ⇒ 屏上拿旧数字念一轮新作业（甲档单独用的病） */
    DigitsLie,

    /** WebView 那一枚 + 快照载体：WebView 不可快照，把它塞进 savedInstanceState 是错的 */
    Unsnapshotable,

    /** 名单外的事实：本格无从判 —— 名单闭合由 `*WiringGuardTest` 钉，不在这里静默放行 */
    NotOnRoster,
}

/**
 * 一次点火请求的结论档（[FetchJobCarrierPolicy.ignition]）。
 */
enum class Ignition {
    /** 没人要求抓取 ⇒ 一条请求都不发 */
    Idle,

    /** 作业正跑在**这一枚**平台上 ⇒ 只把读数挂上去，绝不从头再抓一次 */
    AttachOnly,

    /** 没有作业在飞 ⇒ 点一次火 */
    Start,

    /** 作业在飞、但它那一枚平台已经不在了（重建时 factory 造的新 WebView）⇒ 换新平台再点一次 */
    StartOnReplacedPlatform,
}

/**
 * 「抓取作业的在飞事实该住在哪、重建之后该出哪一手」的全仓唯一判据（T336①）。
 *
 * ## 病（在册 #412，升档自 `t334-audit.md` T334-03）
 * 教务登录页按下「抓取课表」后逐周请求要跑几十秒（量尺是同段注释自己陈的：
 * 「19 周逐周请求在网络差时要跑几十秒，用户需要一个"还在走"的证据（M5）」），而那六枚在飞状态
 * 全长在组合上（`by remember { mutable… }`）⇒ 用户转屏 ⇒ Activity 重建 ⇒ 六枚归零 ⇒
 * `LaunchedEffect` 的键被清零 ⇒ **抓取作业被取消**，进度条退回 0/N、结论句消失。
 * 用户读作「点了没反应」或「刚才白等了」。
 *
 * ## 裁的是什么
 * 两问，各自一张档位表，谁也不许折成 Boolean（本仓硬口径）：
 * 1. **载体寿命**：[verdict] —— 一枚事实该不该活过组合，由它属不属于「在飞那一族」决定；
 *    在飞事实的载体寿命必须 ≥ 作业寿命，于是 `COMPOSITION` 判死、`SNAPSHOT` 也判死
 *    （甲档单独用只保住数字、救不了作业，还额外造出"旧数字念新作业"这一档谎），
 *    只有 `PROCESS_HOLDER` 放行。WebView 那一枚反过来：它的合法载体是**进程内 holder**
 *    （`BuaaWebSession` 那一族），`SNAPSHOT` 判死 —— 不可快照的东西塞进 savedInstanceState 是错的。
 * 2. **重建之后是恢复还是重新点火**：[ignition] —— 「作业已在飞 + 平台还是那一枚」必须是
 *    [AttachOnly]（把几十秒作废后从头再抓不是恢复），「已在飞但平台被换枚」才许
 *    [StartOnReplacedPlatform]，而 [Start] 只许长在"压根没有作业在飞"那一档。
 *
 * [progressResetsOnIgnition] 是第 2 问的下半句：真要重新点火，进度数字必须显式归零 ——
 * 屏上念的分数必须属于脚下这一轮，这正是甲档单独落地时会被造出来的那枚谎。
 *
 * ## 纯 JVM 边界（本仓硬规则）
 * 零 android import、零时钟、零设备读数、不读盘。WebView / Activity / 权限那一族事实**由调用点当参数递进来**
 * （这里只收三枚 Boolean）。「返回布尔后调用点各判一次 SDK_INT」也算违反 ⇒ 答案一律是具名枚举档。
 */
object FetchJobCarrierPolicy {

    /**
     * 在飞事实名单（本卡的量尺就是这六枚的名字）：抓取作业跑着的时候它们有意义、
     * 作业死了还念着它们就是谎话。载体寿命必须 ≥ 作业寿命。
     */
    val IN_FLIGHT_FACTS: Set<String> = setOf(
        "fetchState",
        "fetchWeek",
        "fetchTotal",
        "fetchStarted",
        "fetchCancelled",
        "importPrepared",
    )

    /** 抓取平台那一枚：WebView 不可快照，跨销毁的合法载体是 `BuaaWebSession` 那一族进程内 holders */
    val PLATFORM_FACTS: Set<String> = setOf("webViewRef")

    /** 登录页自己的加载读数：与抓取作业无关 ⇒ 留在组合上是对的，本卡不搬 */
    val PAGE_LOAD_FACTS: Set<String> = setOf(
        "loadProgress",
        "loadError",
        "loadStarted",
        "pageVisible",
        "timedOut",
        "retryToken",
    )

    /** 这枚事实属不属于「在飞那一族」 ⇒ 它的载体寿命必须 ≥ 作业寿命 */
    fun isInFlightFact(name: String): Boolean = name in IN_FLIGHT_FACTS

    /**
     * 载体裁定。
     *
     * 名单外一律 [CarrierVerdict.NotOnRoster]：静默放行会让"新增一枚在飞状态却忘了进名单"
     * 这一档永远红不到，名单闭合由接线守卫逐枚点名。
     */
    fun verdict(name: String, carrier: FactCarrier): CarrierVerdict {
        if (name in PLATFORM_FACTS) {
            return if (carrier == FactCarrier.SNAPSHOT) CarrierVerdict.Unsnapshotable else CarrierVerdict.Supported
        }
        if (name !in IN_FLIGHT_FACTS && name !in PAGE_LOAD_FACTS) return CarrierVerdict.NotOnRoster
        return when (carrier) {
            FactCarrier.PROCESS_HOLDER -> CarrierVerdict.Supported
            FactCarrier.COMPOSITION ->
                if (name in IN_FLIGHT_FACTS) CarrierVerdict.JobDiesWithDigits else CarrierVerdict.Supported
            // 快照救不了作业：在飞事实放这儿 = 数字活、作业死 ⇒ 旧数字念新作业
            FactCarrier.SNAPSHOT ->
                if (name in IN_FLIGHT_FACTS) CarrierVerdict.DigitsLie else CarrierVerdict.Supported
        }
    }

    /**
     * 点火裁定。三枚输入全部由调用点递进来（平台在不在、作业在不在飞、这一枚是不是作业那一枚）。
     *
     * 次序即优先级：先问「有没有人要求抓」（不许数据还没到就误点火），
     * 再问「有没有作业在飞」，最后才问「在飞的那一枚平台是不是手上这枚」。
     */
    fun ignition(
        askedToFetch: Boolean,
        jobRunning: Boolean,
        platformIsJobPlatform: Boolean,
    ): Ignition = when {
        !askedToFetch -> Ignition.Idle
        !jobRunning -> Ignition.Start
        platformIsJobPlatform -> Ignition.AttachOnly
        else -> Ignition.StartOnReplacedPlatform
    }

    /** 重新点火这两档必须把进度数字显式归零；[Ignition.AttachOnly] 归零就是把还在走的作业抹成 0/N */
    fun progressResetsOnIgnition(ignition: Ignition): Boolean =
        ignition == Ignition.Start || ignition == Ignition.StartOnReplacedPlatform
}
