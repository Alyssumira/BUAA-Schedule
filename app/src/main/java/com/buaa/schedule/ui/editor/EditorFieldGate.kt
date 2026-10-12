package com.buaa.schedule.ui.editor

import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.WeekParser

/**
 * 编辑器「哪一格判红」与「保存按不按得下去」的**同一把尺**（T321，台账 #499 = #379 升档）。
 *
 * ## 病灶（改前的两本账）
 *
 * `CourseEditorScreen` 过去把六枚判红就地算成六枚 Boolean 递给 `isError`，而闸门
 * `editorCanSave(weeks, periods, saving)` 的形参里没有一枚是判红字段。前两格（开始/结束节次）
 * 与周次那一格恰好被 `parsePeriods`/`WeekParser` 的"解析为空"顺带拦住了，剩下三格压根没人拦：
 *
 * - 额外节次：`parsePeriods` 那句 `extra.any { it < 1 || it > MAX_PERIOD }` 对**解析得出的空表恒为
 *   false** ⇒ 交回的只剩首段 ⇒ 框子红了、`periods` 非空、按钮亮着、用户看到的那段被整段丢掉。
 * - 学分：`creditText = "3,5"` 判红，`performSave` 递 `normalizeCredit(null)` ⇒ 落库是
 *   "没有学分数据"，而框子里写着 3,5。
 * - 提前分钟：`"999"` 判红，`saveCourseDraft` 递 `normalizeAdvanceMinutes` ⇒ 落库 240。
 *
 * 同段那句「判定与 parsePeriods 一一对应，这样"红框"和"存不存得进去"永远同步」在改前是**假话**，
 * `#379` 的"三处注释与代码相反"就包括它 —— 本文件把它变成真话。
 *
 * ## 第⓪问裁的是甲档（红 ⇒ 按不下去），代价由出声道 satisfy
 *
 * 三枚新入闸的红都带 `isNotBlank()` 前件：**留空不判红**（:211/:217/:222 三条原注释逐字写着），
 * 所以收进来的没有一枚是"这格没填"，不会造出"按不下去却没人说为什么"的新静默；
 * 每一格红时 `fieldError` 的 supportingText 出声，顶部汇总横幅改吃 [EditorFieldVerdict.anyRed]
 * （原先会亮的那两档仍然亮：`periods` 空 ⇒ 开始/结束至少一格红，`weeks` 空 ⇔ 周次红）。
 *
 * ## 纯 JVM 边界
 *
 * 零 android / androidx import：闸门吃的状态必须是调用点**已经提升好的** state 值（六格文本 +
 * 解析得出的 `weeks`），本文件不读钟、不读权限、不读设备度量、不碰 Compose。
 * 交给调用点的不是"一枚布尔然后各判一次"，而是**那一本红名单**：`isError` 与闸门都消费同一份
 * [EditorFieldVerdict]，谁也不许在体外再算第二遍红。
 */

/** 编辑器里会判红的格子。红 ⇒ 既标框又按不下去，两问同源于 [EditorFieldVerdict.redFields]。 */
internal enum class EditorRedField {
    /** 「开始节次」：非数字／空白／越出 1..MAX_PERIOD */
    StartSection,

    /** 「结束节次」：非数字／空白／越界／比开始还早 */
    EndSection,

    /** 「额外节次」（可选）：填了解析不出可用的节次 */
    ExtraPeriods,

    /** 「周次」：解析为空 */
    Weeks,

    /** 「学分」（可选）：填了且 `normalizeCredit` 判为「没有学分数据」 */
    Credit,

    /** 「提前分钟」（可选）：填了且非整数或越出 0..MAX_ADVANCE_MINUTES */
    Advance,
}

/**
 * 一次编辑器组合的逐格读数：红名单 + 保存链要用的两枚派生值。
 *
 * 三个字段全是**同表参数的函数**（类体属性，无 copy 站点），值只由 [editorFieldVerdict] 算一次。
 */
internal class EditorFieldVerdict(
    /** 学分那格的数值读数：`performSave` 递给 `normalizeCredit` 的就是这一枚，不再自己 parse 第二遍 */
    val creditNumber: Double?,
    /** 结束早于开始（「结束节次」那一格的措辞要分档：「结束需晚于开始」） */
    val orderReversed: Boolean,
    /** 判红的格子名单：**唯一**那本账 */
    val redFields: Set<EditorRedField>,
) {
    /** 顶部汇总横幅吃这一枚：它必须与闸门的红判据同源，不许再拼一遍 `periods.isEmpty() || weeks.isEmpty()` */
    val anyRed: Boolean
        get() = redFields.isNotEmpty()

    /** 六枚 `isError` 与三句 supportingText 的唯一取法 */
    fun isRed(field: EditorRedField): Boolean = field in redFields
}

/**
 * 逐格判红本体：六条判据与改前 `CourseEditorScreen` 里就地算的那六枚 Boolean **逐条同口径**
 * （本卡改的是"红不进闸门"，不是任何一格"什么算红"）。
 *
 * `weeks` 取调用点已经解析好的那一份（`WeekParser.parse(weeksText)` 全仓只许有一枚答案），
 * 这里不再 parse 第二遍。
 */
internal fun editorFieldVerdict(
    startSection: String,
    endSection: String,
    extraPeriods: String,
    weeks: List<Int>,
    creditText: String,
    advanceMinutes: String,
): EditorFieldVerdict {
    val startPeriodNumber = startSection.trim().toIntOrNull()
    val endPeriodNumber = endSection.trim().toIntOrNull()
    val orderReversed = startPeriodNumber != null && endPeriodNumber != null &&
        endPeriodNumber < startPeriodNumber
    val creditNumber = creditText.trim().toDoubleOrNull()
    val advanceNumber = advanceMinutes.trim().toIntOrNull()
    val redFields = listOf(
        EditorRedField.StartSection to (
            startPeriodNumber == null || startPeriodNumber !in 1..CourseConstraints.MAX_PERIOD
            ),
        EditorRedField.EndSection to (
            endPeriodNumber == null ||
                endPeriodNumber !in 1..CourseConstraints.MAX_PERIOD ||
                orderReversed
            ),
        EditorRedField.ExtraPeriods to (
            extraPeriods.isNotBlank() &&
                WeekParser.parse(extraPeriods).let { list ->
                    list.isEmpty() || list.any { it !in 1..CourseConstraints.MAX_PERIOD }
                }
            ),
        EditorRedField.Weeks to weeks.isEmpty(),
        EditorRedField.Credit to (
            creditText.isNotBlank() && CourseConstraints.normalizeCredit(creditNumber) == null
            ),
        EditorRedField.Advance to (
            advanceMinutes.isNotBlank() &&
                (advanceNumber == null || advanceNumber !in 0..CourseConstraints.MAX_ADVANCE_MINUTES)
            ),
    ).filter { it.second }.map { it.first }.toSet()
    return EditorFieldVerdict(
        creditNumber = creditNumber,
        orderReversed = orderReversed,
        redFields = redFields,
    )
}

/**
 * 保存闸门。
 *
 * - `periods.isNotEmpty()` 这一项不许放宽：节次解析为空时点保存会写出一门没有节次的课
 *   （或被解析器吞掉），而这两条都该由界面先拦住（T28 那一族，`EmptyPeriodEditorBackfillTest` 打表钉着）。
 * - `redFields` 这一项不许省：它就是 [editorFieldVerdict] 那本红名单。`isError` 吃它、
 *   顶部横幅吃它、闸门也吃它 —— 三处一旦各算各的，"标了红却能保存"就回来了（台账 #499）。
 *   默认值是 `emptySet()`，即"没有一格判红"：**唯一的用处是让只问三项判据的格子打表**
 *   （T28 那张网格）不必跟着改形；生产调用点必须把实账递进来，那一格由
 *   `CourseEditorRedFieldGateWiringGuardTest` 钉死 —— 判据写得再对，调用点不递就是第二本账。
 */
internal fun editorCanSave(
    weeks: List<Int>,
    periods: List<Int>,
    saving: Boolean,
    redFields: Set<EditorRedField> = emptySet(),
): Boolean = weeks.isNotEmpty() && periods.isNotEmpty() && !saving && redFields.isEmpty()
