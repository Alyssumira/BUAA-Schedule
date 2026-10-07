package com.buaa.schedule.ui.importing

import com.buaa.schedule.data.import.SemesterInfoReason

/**
 * 首次教务导入「开学日认不出来」那一句用户可见的话（T196④）。
 *
 * 零 android / androidx import、零时钟与设备读取（本仓口径，照 `ImportCompletionCopy.kt` 的先例）：
 * 两枚输入都由调用点（`BuaaLoginScreen` 里那一枚闸门）递进来 ——
 * [raw] 是 T180 内核 `BuaaSemesterStart.Undetermined.raw`（**有没有拿到一串**），
 * [reason] 是 `fetchSemesterInfo` 交出的 [SemesterInfoReason]（**页面到底答没答、答成什么样**）。
 *
 * ## 病
 *
 * 改前这一句长在 `BuaaLoginScreen` 的 `Undetermined` 支里，只按「raw 有没有」分两等
 * （「缺失」/「那枚串」）。而 `firstWeekMonday = null` 背后是**五种不同的事**
 * （学期代码没过白名单、页面没答、答了而一行周次都没给、答了而那份答读不出来、T193 判不出第 1 周），
 * 于是「页面根本没答」与「教务答了而日期不认识」被混成同一句「请稍后重试」——
 * 用户与取证的人都读不出这一句到底该重试、该换网络、还是该去教务处问一句。
 *
 * ## 为什么抽成纯函数而不是留在页面里
 *
 * 1. 留在 Composable 里就得装机才验得了（`:app` 的 JVM 面只有 junit、**没有 Robolectric**，零 adb 是本卡红线）；
 *    抽成 `f(raw, reason) -> String` 之后，这两维的每一格都能由表驱动单测跑出来。
 * 2. 「什么时候出场」（闸门那一支）与「说什么」（这一张表）从此分家：接线守卫
 *    `SemesterInfoReasonGuardTest` 钉那一枚调用点恰好一处，表驱动单测钉那七句各由哪一档念出。
 *    少一档就等于把某一档重新压回同一句话，也就是本卡要拆的那枚病灶复发。
 *
 * ## 句子的形状（两维各占一段，两头固定）
 *
 * `那一问（第 1 周 startDate = 缺失 / 「原样那枚串」；那一档的原因），那一尾`
 *
 * - 头与尾各**一枚** `private const`：那一尾与「抓取未完成：第 … 周失败」那一句逐字相同，
 *   而 `SemesterGateOrderingGuardTest` 在 `ui/importing` 整目录里数它恰好 2 处（这一枚 + 失败周那一句）——
 *   各档重复抄一句会让那一格假红，也会让「请稍后重试」重新变成谁都不担责的那一句。
 * - 六档原因的措辞**不带全角括号**：`UserVisibleParenBalanceGuardTest` 按括号配对取「句」，
 *   开括号押在半句、闭括号押在下一句正是要防的形状；整句的括号只有那一枚模板负责。
 * - 最后一等（[reason] 为 null 而 raw 也空白）按构造**不可达**：`missingReason` 为 null 意味着
 *   `SemesterInfo` 交出了一枚非空白串，而 [raw] 就是那枚串的原样。这一格照样给句子，
 *   因为要说的是「异常形状、去看日志」，而不是替它猜一枚原因（照本仓那一族口径，
 *   不可达支不许拿兜底顶过去 —— T193 登记的三条事实里第 2 条就是这一形）。
 */
internal fun semesterStartFailureMessage(
    raw: String?,
    reason: SemesterInfoReason?,
): String {
    val got = if (raw.isNullOrBlank()) "缺失" else "「$raw」"
    return "$FAILURE_HEAD（第 1 周 startDate = $got；${semesterStartFailureCause(raw, reason)}），$FAILURE_TAIL"
}

/** 那一问：所有档共用同一句开头（一枚字面量，别在各档里各抄一遍） */
private const val FAILURE_HEAD = "教务系统没有给出可识别的开学日期"

/** 那一尾：与「抓取未完成：第 … 周失败」共用同一句收尾，措辞本卡不许改 */
private const val FAILURE_TAIL = "已保留原课表未做改动，请稍后重试"

/**
 * 「页面到底答没答、答成什么样」那一段（[SemesterInfoReason] 五枚 + null 两等，共七句）。
 *
 * `when` 不带 `else`：五枚枚举值加 null 穷尽，将来给 [SemesterInfoReason] 添一枚而这一张表没跟上，
 * 编译当场红 —— 那正是「把新的一档压回旧句子」那一族的入口。措辞逐档对着
 * `fetchSemesterInfo` 里那一档早返回，用户可以据此分辨「该重试」「该换网络」「该去教务处问一句」。
 */
private fun semesterStartFailureCause(raw: String?, reason: SemesterInfoReason?): String = when (reason) {
    null -> if (raw.isNullOrBlank()) {
        // 按构造到不了这一等：没有 missingReason 就意味着交出了一枚串
        "抓取这一侧没带原因、也没交出日期，那是异常形状，取证看日志"
    } else {
        "教务答了，而这一枚日期认不出是哪个"
    }

    SemesterInfoReason.InvalidTermCode ->
        "学期代码没通过白名单，那一趟周次请求根本没发出去，不是网络差"

    SemesterInfoReason.PageUnanswered ->
        "那一趟周次页没有回音，重试过一次仍然超时或失败，页面根本没答"

    SemesterInfoReason.EmptyWeekList ->
        "教务答了周次页，但一行周次都没给"

    SemesterInfoReason.WeekListUnreadable ->
        "教务答了周次页，而那份答不是预期的形状，原生读不出来"

    SemesterInfoReason.FirstWeekUndetermined ->
        "教务给了周次行，而判不出哪一行是第 1 周：两条判据分家，或并列成两枚不同的日期串"
}
