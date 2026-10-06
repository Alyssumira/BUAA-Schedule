package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.mondayOfWeekAnchor
import java.time.LocalDate

/**
 * 「教务报上来的首周日期到底认不认得出来」那一判的内核（T180）。
 *
 * 纯判据：零 android import、**零时钟读取**、不碰 WebView 也不碰数据库 —— 输入的
 * 那枚字符串就是 `BuaaInPageFetcher.SemesterInfo.firstWeekMonday` 本身（教务
 * `getTermWeeks` 第 1 周 `startDate` 去掉时间尾巴之后的原样），结论由调用点
 * `ui/importing/BuaaLoginScreen` 的 `LaunchedEffect` 体拿去决定"建学期"还是"不动学期"
 * （仓库口径，见 `data/repository/CourseDeletionPolicy.kt` 与 `ui/home/DayBrowsePolicy.kt` 那一族）。
 *
 * ## 病（用户看得见的那一串：导入一次，周课表整个变成空网格）
 *
 * 改之前那一段把两枚 val 登在 `BuaaLoginScreen` 的 `LaunchedEffect` 体内（`rawStart` 与
 * `startDate`），形状是「`runCatching { mondayOf(LocalDate.parse(rawStart)) }`
 * 的兜底值 `getOrDefault(rawStart)`」—— **兜底值正是那一枚 `LocalDate.parse` 没能解析
 * 的原串**。教务把首周写成 `2026/9/7`（斜杠单位）、写成 `2026年9月7日`、带上 BOM、
 * 或者干脆 `startDate` 缺席留成一枚空串时，坏日期就原样进了 `Semester.startDate` 并落库。
 * 落库之后 `domain/model/Semester.kt` 的 `startLocalDate` 每次读都 parse 失败 ⇒ **恒 null**，
 * 沿这一枚 null 铺开的是整条**静默**链：周课表那一格 `SemesterWeekDates.mondayOf` 拿不到
 * 学期起点而返回 null、`TodayPlanner.plan` 起手 `?: return EMPTY`（今日页空）、
 * `ReminderScheduler.rescheduleAll` 在 `semesterStart == null` 那一支直接 `cancelAll(context)`
 * 连带 `ClassProgressScheduler.cancelAll`（提醒与上下课铃整个撤掉）、桌面组件按读不到起点的
 * 那一档停在「假期中」，而 `ui/settings/SettingsScreen` 里 `startDate` 那枚
 * `remember(semester)` 状态把这串垃圾**显示出来并能回写**进库 —— 用户看到的是
 * "我明明导入了 18 门课，课表却整个是空的"，没有任何一句报错。
 *
 * 这条路**没有第二道闸门**：写库那一手 `ScheduleRepository.replaceSemesterCoursesInTx`
 * 是 `semesterDao.deleteByTermCode(...)` + `semesterDao.insert(semester.toEntity())`，
 * 对 `startDate` 一个字都不判（只有备份恢复那一路
 * `ScheduleRepository.restoreBackupDataInTx` 里的 `fallbackStartDate` 会过一遍
 * `WeekCalculator.mondayOf(LocalDate.parse(s.startDate))`，导入链不走它）。所以坏串一旦
 * 进了库，就只剩下读侧那枚 null —— 那正是本卡要消灭的状态。
 *
 * ## 为什么选「认不出来就不动学期」，不选「换成今天所在周的周一」
 *
 * 同仓的姊妹实现 `BuaaWebSession.refreshSchedule` 早就判过这一案，它的链是
 * `firstWeekMonday → runCatching parse → mondayOf → ?: existingStartDate → ?: 整趟判失败`，
 * KDoc 写得很明白：「写一个猜出来的日期会让整张课表的日期与周次编号整体偏移
 * （课次日期 = startDate.plusWeeks(w-1).plusDays(dow-1)），而调用方紧接着就删旧插新，
 * 没有回滚路径」。首次导入这一路手里**没有 `existingStartDate`**（这正是"首次建学期"），
 * 所以对它的唯一忠实复刻就是最后那一等：**判不出来就不写、就报错、就让原课表原地不动**。
 * 另一头，`BuaaLoginScreen` 同一个 `when` 里紧邻的那一枚分支（`outcome.failedWeeks`
 * 非空）早就用同一条口径说话：「宁可让用户重试，也不能用不完整的结果覆盖既有课表」。
 *
 * 「今天所在周的周一」那一等看着无害，实际是把一枚**猜**的日期写成整张表的锚点：提前
 * 选完下学期再来导入（北航每学期都有这一档用户）时，今天离开学还有好几周，猜出来的
 * 起点会让每一节课的日期与周次编号整体前移，而用户完全读不出来 —— 周课表照样画得满，
 * 只是每一节课都在错误的星期几上。这比"导入没成功、请重试"坏得多：后者是可恢复的。
 *
 * ## 为什么 `today` 不进内核（卡面把 `today` 列在输入里，这里如实交代偏离）
 *
 * 选定「不动学期」那一等之后，本判据**不需要**任何时钟输入：`today` 在旧代码里唯一的
 * 用处就是供给那一等被本卡删掉的猜测。把它留在签名上只会诱使后来人再算一次
 * 「今天所在周的周一」；真要给它加一枚"离今天太远就拒收"的合理性窗口，反而会打掉合法
 * 用例（补录**历史学期**是正常操作，起点在过去是应该的）。单测里
 * `认得出的 ISO 一律照收 不许加日期合理性窗口` 那一格钉的就是这条：任何拿 `today`
 * 参与的判据一落地，那一格立刻红。顺带旧表达式里读两次 `LocalDate.now()` 那一手
 * （`rawStart` 那行）也跟着整个消失 —— 调用点现在一枚时钟都不读。
 *
 * ## 证据档位（如实写明）
 *
 * JVM 表驱动单测 `BuaaSemesterStartPolicyTest`（判据两侧各摆格子）+ 源码核对守卫
 * `BuaaSemesterStartWiringGuardTest`（钉住旧那一形在 `app/src/main` 里 0 处、内核零 android
 * 与零时钟、调用点接线与顺序）。`:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric**，
 * 所以"真的落库一条再看周课表"这一档**没做**（红线零设备）：装机要复核的是「教务返回
 * 非 ISO 首周时，用户看到的是那一句新报错而不是空课表」，见回执的未验到清单。
 */
sealed interface BuaaSemesterStart {

    /**
     * 认得出来：已经过 [mondayOfWeekAnchor] 归一到该日期所在自然周的那一枚**周一**。
     *
     * 归一是必须的，不是装饰：教务 `getTermWeeks` 第 1 周的 `startDate` 偶尔是报到日
     * （周日/周六），不归一会让首次导入的整张表偏移、下一次静默刷新又把它挪回来。
     */
    data class Resolved(val monday: LocalDate) : BuaaSemesterStart

    /**
     * 认不出来（`null` / 空 / 非 ISO / 带中文 / 带 BOM / 日期不成立）。
     *
     * 这一枚结论**不许**被折算成任何一个日期：调用点只能走「不动学期」那一路
     * （报错 + 返回，不建 `Semester`、不进导入预览、更不写库）。
     * [raw] 只用于把原样报给用户（诊断用），不参与任何判定。
     */
    data class Undetermined(val raw: String?) : BuaaSemesterStart
}

/**
 * 判据本体：教务报上来的首周日期，认得出就归一成周一，认不出来就给出「不动学期」信号。
 *
 * 全仓**唯一一份**这一判的本体（首次导入路径）。静默刷新那一路
 * （`BuaaWebSession.refreshSchedule`）多一级 `?: existingStartDate` 是因为它手里有库里
 * 既有的日期可沿用，两路共用的只有「parse 不出来就算不出来」这一条；把两路并成一枚
 * 函数会丢掉 `existingStartDate` 那一等 —— 那等于让刷新去猜，本卡明令不许。
 *
 * @param firstWeekMonday 教务 `getTermWeeks` 第 1 周 `startDate` 的原样（可能为 null）。
 *   允许首尾空白（`trim`，不改语义）；**不许**在它上面做任何"再宽松一点"的抢救
 *   （斜杠、中文年月、BOM 一律算认不出来 —— 抢救 = 猜）。
 */
internal fun resolveBuaaSemesterStart(firstWeekMonday: String?): BuaaSemesterStart {
    val text = firstWeekMonday?.trim()?.takeIf { it.isNotEmpty() }
        ?: return BuaaSemesterStart.Undetermined(firstWeekMonday)
    val parsed = runCatching { LocalDate.parse(text) }.getOrNull()
        ?: return BuaaSemesterStart.Undetermined(firstWeekMonday)
    return BuaaSemesterStart.Resolved(mondayOfWeekAnchor(parsed))
}
