package com.buaa.schedule.data.import

/**
 * 「`fetchSemesterInfo` 交出了 `firstWeekMonday = null`，那是**为什么**」（T196④）。
 *
 * 纯枚举：零 android import、零时钟、不 parse 日期（本仓内核口径）。落点在 `data/import/`
 * 而不是 `ui/importing/`，因为它由 `BuaaInPageFetcher.SemesterInfo` 携带 —— 谁造的这一档就是谁的名字，
 * 界面那一侧只是把它念成话（`ui/importing/SemesterStartFailureCopy.kt`）。
 *
 * ## 病
 *
 * 改前这一格只有 `firstWeekMonday = null` 一枚事实，而它背后是**五种完全不同的事**：
 * 学期代码没过白名单（那一趟请求根本没发出去）、页面没答（①重试之后仍然超时/失败）、
 * 页面答了而一行周次都没给、页面答了而那份答解不开、以及 T193 那一档（周次行拿到了而
 * 第 1 周判不出）。`ui/importing/BuaaLoginScreen.kt` 的文案今天只按「raw 有没有」分两等，
 * 于是「页面根本没答」与「教务答了而日期不认识」被混成同一句「请稍后重试」——
 * 用户与取证的人都读不出这一句该重试、该换网络、还是该去教务处问一句。
 *
 * ## 为什么是这一本表而不是第二本
 *
 * 卡面要求「照 T193 内核已有的 `UndeterminedReason` 那一族口径取名，**别再造第二本分类表**」：
 * 1. 命名同族（`…Reason` + 每一枚对应调用点里一档**早返回**，不是一种"错法"的重新分类）；
 * 2. 第五枚 `FirstWeekUndetermined` **不重述**内核那三枚 reason（分家 / 序号并列 / 最早日期并列）——
 *    那一本表已经在 `BuaaFirstWeek.UndeterminedReason` 里，日志也照旧念它；这一枚只登记
 *    「周次行拿到了、第 1 周判不出」这一**档**，等于给两本表各留一句、没有第二份判据；
 * 3. 每一枚与 `fetchSemesterInfo` 里的一档早返回**一一对应**（枚数由
 *    `SemesterInfoReasonGuardTest` 钉成 5 枚，四档 `SemesterInfo(20, null, …)` + 一档
 *    `SemesterInfo(weeks.size, null, …)`），所以「为什么没有」永远能从句子倒查回那一行代码。
 *
 * ## 两枚维度（④的文案就按这两维分档）
 *
 * - **页面有没有答**：[InvalidTermCode]（没发出去）与 [PageUnanswered]（发了没回）属「没答」；
 *   [EmptyWeekList] / [WeekListUnreadable] / [FirstWeekUndetermined] 属「答了」。
 * - **有没有拿到一枚串**：这一维不在本枚举里，是 `SemesterInfo.firstWeekMonday` 与
 *   `BuaaSemesterStart.Undetermined.raw` 的现成事实 —— 第五档之外那四档必然没串，
 *   而「有串而认不出」那一等 `missingReason` 是 null（页面上是 T180 那一判否掉的，不是抓取没拿到）。
 *
 * ⚠️ 与 `totalWeeks` 无关：前四档的周数今天仍硬写 20，那一笔账在台账 #236（还要判「没学期时该不该
 * 给周次导航」），本卡只补原因分档、不动那四格 20。
 */
enum class SemesterInfoReason {

    /** 档一：`termCode` 过不了白名单正则 ⇒ 那一趟 `getTermWeeks` **根本没发出去**（不是网络差） */
    InvalidTermCode,

    /** 档二：页面内 fetch（含 T196① 那一圈重试）**仍然**回 null ⇒ 超时 / 错误哨兵，页面没答 */
    PageUnanswered,

    /** 档三：页面答了解得开，而 `datas` 是空列表 ⇒ 一行周次都没给 */
    EmptyWeekList,

    /** 档四：页面答了而整块 `runCatching` 失败（不是预期的 JSON 形状）⇒ 那份答读不出来 */
    WeekListUnreadable,

    /**
     * 档五：周次行拿到了，而「哪一行是第 1 周」判不出 —— T193 那一档。
     * 具体是三条判据里的哪一条（[BuaaFirstWeek.UndeterminedReason]）由内核念进日志，
     * 本枚不重述（那是第二本表）。
     */
    FirstWeekUndetermined,
}
