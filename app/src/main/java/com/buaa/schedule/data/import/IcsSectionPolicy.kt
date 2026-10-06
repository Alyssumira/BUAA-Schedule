package com.buaa.schedule.data.import

import java.time.LocalTime

/**
 * 「ICS 里的一枚钟点到底该落成第几节」那一判的内核（T192）。
 *
 * 纯判据：零 android import、**零时钟读取** —— 要判的那枚钟点由调用点
 * （`IcsParser.parse` 里 DTSTART/DTEND 换算出来的 `LocalTime`）当参数递进来，
 * 节次表由调用点从 `TimeSlot` 转成 [IcsSlot]（仓库口径，见
 * `data/import/BuaaSemesterStartPolicy.kt` 与 `domain/schedule/CourseDeletionPolicy.kt` 那一族）。
 *
 * ## 病（用户看得见的那一串：课表上多出一节根本没上过的「第 1 节」）
 *
 * 改之前这一判长在 `IcsParser.sectionOf`，最后那一行是 `return previous?.number ?: 1`：
 * 早于**所有**节次开始的活动（早自习、晨训、7:00 的实验室开放）走到 `previous == null` 那一支，
 * 被凭空安成**第 1 节**。它不是显示错误就完了 —— 第 1 节真的进 `courses`、真的写库、
 * 真的出现在课表上，连 `colorIndex = (startSection + dow.value) % 8` 都按那枚假节次号算。
 * 而它自己的注释只授权了「找不到则用最近的前一个节次」，**没有授权凭空造第 1 节**。
 *
 * 同族先例判定：T27「`startPeriod` 的 `?: 1` 兜底在定位/导出链上造出不存在的课」。
 * 那一族需要区分「第 1 节」与「根本没有节次」时给出的出口是可空的
 * `Course.firstPeriodOrNull`；本卡同一口径 —— 判不出来就**明说判不出来**，
 * 由调用点决定「这一条事件整条跳过」，而不是由判据替用户编一节。
 *
 * ## 三条判据为什么按这个顺序（顺序即语义）
 *
 * 1. 表空 ⇒ [IcsPeriodResolution.NoSlots]。`IcsParser.slotTable` 用 `mapNotNull` 过
 *    `LocalTime.parse`，一整表节次时间全被写坏的库里只剩零行是可达的，那时任何节次号都是编的。
 * 2. 落在某节次 `[start, end)` 内 ⇒ [Via.InsideSlot]。**区间右开**：上一节的下课铃那一刻
 *    不属于上一节（`08:45` 下课的活动不算第 1 节在内，走第 3 条折算），这与改之前那一支的
 *    `time.isBefore(it.end)` 逐字同口径，幂等性（导出 → 再导入）就靠它。
 * 3. 都不命中 ⇒ 在 `start <= time` 的节次里取 **start 最晚**的那一枚 ⇒ [Via.NearestPrevious]。
 *    这一等是**有意的**（旧注释「找不到则用最近的前一个节次」授权的就是它）：晚于最后一节的
 *    钟点（21:00 以后的延时至 23:00）折进最后一节，本卡不改它的方向，只在表驱动里给它钉一格，
 *    免得后来人当成 bug 顺手改掉。
 * 4. 连 `start <= time` 的节次都不存在 ⇒ [IcsPeriodResolution.BeforeFirstSlot]。
 *    这一等就是旧 `?: 1` 吃掉的那一档，新内核在这里交回「不猜」。
 *
 * ## 为什么「最近的」必须按时间序而不是列表序
 *
 * 旧写法是 `slots.lastOrNull { !time.isBefore(it.start) }` —— 取的是**列表里最后一个**满足条件
 * 的元素，而不是**开始最晚**的那一枚。两者只在节次表按 start 升序时才重合，而这条链不保证：
 * `data/local/TimeSlotDao.getAll()` 的 SQL 是 `SELECT * FROM time_slots ORDER BY number`
 * （**节次号**升序），`ui/settings/SettingsScreen` 的节次草稿又是逐格自由填 `HH:mm`
 * （校验只有 `domain/schedule/TimeSlotValidation.isValidTimeSlot`：时刻真实存在且 `start < end`），
 * **没有任何一处做跨行的单调性校验** —— 把第 1 节填成 09:00、第 2 节填成 08:00 是存得进去的。
 * 于是「列表里最后一个」与「start 最晚」会分家，前一节就取错。
 * 本内核只按 `start`（并列时按 `number`）判，[IcsSlot] 的列表顺序**不参与任何判定**；
 * 表驱动里「乱序输入逐格等于升序输入」那一格钉的就是这一条。
 *
 * ## 并列怎么破
 *
 * 两枚节次 `start` 相同（重叠表 / 库里存了同一起点的两节）时取 **number 最小**的一枚：
 * `start` 相同则「哪一枚更晚」这个问题没有答案，能定的只剩节次号，而节次号小的是课表上排在
 * 前面的那一节 —— 拿它当「最近的前一节」用户读得出来；取列表序的第一个则把刚拆掉的
 * 列表依赖又请回来。同一枚破法也用在 [Via.InsideSlot] 的重叠命中上（两枚同时罩住这枚钟点时
 * 取 start 最晚、再同则 number 最小），否则那一等就要看喂进来的顺序，本卡「不许依赖列表顺序」
 * 的硬要求当场破。
 *
 * ## 证据档位（如实写明）
 *
 * JVM 表驱动单测 `IcsSectionPolicyTest`（每条判据两侧都有格子）+ 端到端行为单测
 * `IcsParserSectionWiringTest`（早于第一节的事件不再被安成第 1 节、乱作息下两遍同一张表逐字节相同）
 * + 源码核对守卫 `IcsSectionPolicyGuardTest`（旧那一形在 `app/src/main` 里 0 处、内核只有一份、
 * 调用点两处、null 闸长在 `..` 之前、探测器自己认得旧原文）。
 * `:app` 的 JVM 测试面只有 `junit`（无 Robolectric、未开 `returnDefaultValues`），
 * 「真机导入一份含早自习的 .ics，用户看不见那几条被跳过」这一档**没做**（红线零设备），
 * 见回执的未验到清单。
 */
internal sealed interface IcsPeriodResolution {

    /**
     * 落得进节次表：[number] 就是那一节的节次号。
     *
     * [via] 说清这一枚结论是哪条判据给的 —— [Via.InsideSlot] 是正面命中，
     * [Via.NearestPrevious] 是**折算**（跨节次或晚于最后一节）。调用点只判 `is Resolved`，
     * 但守卫与单测要能钉住「折算」这一等确实是有意的那一格。
     */
    data class Resolved(val number: Int, val via: Via) : IcsPeriodResolution

    /**
     * 这枚钟点早于表里**所有**节次的开始（早自习、晨训、7:00 的实验室开放）：**不猜**。
     *
     * 这一等就是旧 `?: 1` 吃掉的那一档。它**不许**被折算成任何一个节次号 ——
     * 编出来的第 1 节会写库、会上课表、还会参与 `colorIndex`。
     */
    data object BeforeFirstSlot : IcsPeriodResolution

    /**
     * 节次表为空（未配置且默认表也拿不到，或整表时间都被 `mapNotNull` 掉）：**不猜**。
     *
     * 与 [BeforeFirstSlot] 分成两枚而不并成一枚，是因为调用点留痕要说得出「是你没配节次表」
     * 还是「这个活动真赶在第一课之前」——两档用户能做的事完全不同。
     */
    data object NoSlots : IcsPeriodResolution
}

/** [IcsPeriodResolution.Resolved] 的来路：正面命中节次区间，还是按「最近的前一节」折算。 */
internal enum class Via { InsideSlot, NearestPrevious }

/**
 * 内核吃的那枚节次表行：只有节次号与两段钟点，没有 `TimeSlot` 的 `id`/字符串形态。
 *
 * 转换只长在调用点（`IcsParser` 里一处 `map`），本文件因此不必认识 `TimeSlot` 与它的
 * `HH:mm` 字符串，也就没有任何一条路能把「parse 得出来吗」这一等拖进判据里。
 */
internal data class IcsSlot(
    val number: Int,
    val start: LocalTime,
    val end: LocalTime,
)

/**
 * 判据本体：这枚钟点落在哪一节；落不进去就明说落不进去。
 *
 * 判定**只看** [IcsSlot.start]/[IcsSlot.end] 与 [IcsSlot.number]，与 [slots] 的排列无关
 * （任意两份同内容的表给出同一个结论）。任何「按列表位置取」的写法
 * （`firstOrNull` / `lastOrNull` / `indexOf`）都会把 `TimeSlotDao` 那一条
 * `ORDER BY number` 当成「按开始时间升序」用，而那条 SQL 保证的是节次号升序 ——
 * 这一格的红归表驱动里「乱序 == 升序」那一格管。
 *
 * @param time 已经换算到本地时区的钟点（UTC/TZID 的折算归 `IcsParser.extractDateTime`，不在本判据里）。
 * @param slots 节次表；空表直接给 [IcsPeriodResolution.NoSlots]。
 */
internal fun resolveIcsPeriod(time: LocalTime, slots: List<IcsSlot>): IcsPeriodResolution {
    if (slots.isEmpty()) return IcsPeriodResolution.NoSlots

    pickLatestStart(slots.filter { !time.isBefore(it.start) && time.isBefore(it.end) })
        ?.let { return IcsPeriodResolution.Resolved(it.number, Via.InsideSlot) }

    // 跨节次（含晚于最后一节）按开始时间最靠后的那一节处理
    pickLatestStart(slots.filter { !time.isBefore(it.start) })
        ?.let { return IcsPeriodResolution.Resolved(it.number, Via.NearestPrevious) }

    // 表里没有任何一节在这枚钟点之前开始过：这一等旧写法造第 1 节，新内核交回「不猜」
    return IcsPeriodResolution.BeforeFirstSlot
}

/**
 * 候选里挑 **start 最晚**的一枚，并列（两枚同 start）挑 **number 最小**的一枚。
 *
 * 这是「不依赖列表顺序」的唯一依赖：比较是定义在 `(start 降序, number 升序)` 上的一个
 * 全序，扫法只是把它算出来，喂进来的 [candidates] 怎么排都一样。
 */
private fun pickLatestStart(candidates: List<IcsSlot>): IcsSlot? {
    var best: IcsSlot? = null
    for (candidate in candidates) {
        val current = best
        if (current == null ||
            candidate.start.isAfter(current.start) ||
            (candidate.start == current.start && candidate.number < current.number)
        ) {
            best = candidate
        }
    }
    return best
}
