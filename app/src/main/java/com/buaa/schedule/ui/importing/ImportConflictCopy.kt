package com.buaa.schedule.ui.importing

/**
 * 导入预览那几处「冲突」措辞的判据（T94①）。
 *
 * 零 android / androidx import、零时钟与设备读取（本仓口径）：这里只把已经归并好的**组数**
 * 与已经数好的**配对条数**说成话，一个判据都不新写 ——
 * "谁和谁算一组"由 [com.buaa.schedule.domain.schedule.CourseConflictResolution.groupConflicts]
 * 答（首页横幅 T82、统计页冲突卡都吃它），"哪两两撞"由
 * [com.buaa.schedule.domain.schedule.ConflictDetector.findConflicts] 答。
 * 这一件文件只接两个 Int，所以调用点负责把数据递进来。
 *
 * 病在哪：这一族四处（确认卡的标题、三条解析完成提示）念的都是 `conflicts.size`，
 * 那是**两两配对的条数**，而句子里写的字是"组"。三门课挤在同一格会给出 3 条配对
 * （A-B、A-C、B-C）、归并后只有 1 组 —— 于是界面说"存在 3 组时间冲突"，
 * 用户按 [conflictGroupCount] 去对，怎么都对不上。首页那句在 T82 已经换成组数，
 * 这一卡把同一把尺子量到导入这一族上。
 *
 * 为什么**不**复用统计页那枚 `conflictHeadlineNote(groupCount)`（`ui/stats/StatsConflictCopy.kt`）：
 * 1. 句子形状不同 —— 那一句是"有 N 组时间冲突"（卡片正文里的一行陈述），
 *    这一族要的是"存在 N 组时间冲突"（红字横幅）与"冲突 N 组，请确认导入。"（提示句），
 *    三处没有一处能直接吃它；
 * 2. 它的零组那一档**必须返回一句话**（"这学期没有撞课的时段"），那是统计页"这一块不许整块消失"
 *    的卡面要求；这一族在零组时整段不渲染（`ImportScreen` 走"无时间冲突"那一支），
 *    复用就得给它加一个它不该有的"零组不许调用"前提，或者让界面去嗅一个永远走不到的分支。
 * 所以这里新写的是**措辞**，不是第二套判据：两件文件吃的都是同一枚 `groupConflicts` 的结果，
 * 归并怎么算只有一处真相。
 */

/** 确认卡里最多列几对相撞的课程：再多就不是"看一眼"而是"读一段"了 */
internal const val MAX_INLINE_CONFLICT_PAIRS = 3

/**
 * 确认卡那一句红字标题：`存在 2 组时间冲突`。
 *
 * 入参必须是**归并后的组数**，不是配对条数 —— 参数名叫 groupCount 就是为了把这件事
 * 写在签名上，调用点（`ImportScreen`）读 `PendingImport.conflictGroupCount`，
 * 与那三条解析完成提示读的是同一枚已经算好的数。
 */
internal fun importConflictBanner(groupCount: Int): String = "存在 $groupCount 组时间冲突"

/**
 * 配对明细那几行的小标题：把"几组"与"两两相撞几对"分开报，别让用户拿标题的数去数行数。
 *
 * 为什么明细还按**对**列而不改成按组：一组里的课不一定两两都撞 ——
 * A 与 B 撞在第 1-8 周、B 与 C 撞在第 9-16 周，`groupConflicts` 按传递闭包把 A/B/C 归成一组，
 * 而 A 与 C 其实谁也没占谁的时间（归并件自己都从不承诺"组内两两皆撞"）。
 * 所以"哪两门真的撞在一起"这件事只有配对那一层说得出，删掉它就是删用户判断依据。
 *
 * [shownCount] 由调用点把它真正画出来的那几行传进来（不是这里重新 `take` 一遍），
 * 标题与行数因此不可能各说各的。
 */
internal fun importConflictPairNote(pairCount: Int, shownCount: Int): String =
    if (pairCount <= shownCount) "两两相撞 $pairCount 对，逐一列出："
    else "两两相撞 $pairCount 对，这里只列前 $shownCount 对："

/**
 * 三条"解析完成"提示共用那一句的形状：`ICS 解析完成：新增 3，更新 1，冲突 2 组，请确认导入。`
 *
 * 教务刷新 / ICS / 文本三条路原先各写一遍、字面完全相同（只差前缀），所以这里只做**同一句话**
 * 的形状，不合并那三条 `_importMessage` 的赋值时机 —— 三条路的触发条件与前后步骤各不相同
 * （buaa 那一路还带着 `warnings`），合并成一条路就会改动行为。
 *
 * 末段的"冲突 N 组"吃 [groupCount]（归并后的组数），与确认卡标题同一个数。
 */
internal fun importParseSummary(
    prefix: String,
    addedCount: Int,
    changedCount: Int,
    groupCount: Int,
): String {
    // ⚠️ 这里必须写 ${prefix}：Kotlin 标识符允许汉字，"$prefix新增" 会被读成一枚
    // 叫 prefix新增 的引用（实测 e: Unresolved reference 'prefix新增'）
    return "${prefix}新增 $addedCount，更新 $changedCount，冲突 $groupCount 组，请确认导入。"
}
