package com.buaa.schedule.data.calendar

import android.provider.CalendarContract
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.MarkerRead

/**
 * Events 表那一枚**所有权标记**的查询契约（T265：把只写不读的 `CUSTOM_APP_PACKAGE` 变成读得回来的判据）。
 *
 * ## 为什么这一枚列值得当判据
 *
 * 写侧每一枚我们创建的事件都带着 `Events.CUSTOM_APP_PACKAGE = context.packageName`
 * （`CalendarSyncManager.kt` 的 `eventValues`），而 CalendarProvider 对这一枚列的语义是
 * 「**只有该包名对应的那颗应用能写它**」—— 别的 App 给用户建的日程不会带着我们的包名，
 * 我们也不会给用户自己建的日程打上它（写它的那一手只在 `eventValues` 里）。
 * 于是"等值匹配本包名"就是**唯一一枚既不漏（我们建的一定带）也不宽（别人建的不带）的判据**。
 *
 * ## 这一枚契约里刻意没有的东西
 *
 * 1. **没有兜底第二遍。** `CalendarQuery` 那一边有"严格 selection 查空就退成全量 + 代码侧筛"，
 *    这里**不许**照抄：那一手在 Calendars 表上是"多列几枚可写日历"，在本文件里等于
 *    "把整个 Events 表都当成本应用创建的"，删掉的会是用户自己的日程。
 *    查询抛异常就交回 [MarkerRead.Refused]，答了空集就交回 [MarkerRead.Answered] + 空集 ——
 *    两者都不扩大范围（[com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.planReclaim]
 *    在非 Answered 那一族里只看映射表）。
 * 2. **投影里没有 TITLE / 日历名 / 描述。** 回收只需要 `_ID`；任何"看着像我们建的"的模糊判据
 *    （按标题前缀、按日历归属）都是把范围放宽到用户日程的把手，宁缺勿宽。
 * 3. **selection 里没有 OR、没有第二枚列、没有按 calendar_id 收窄。** 收窄到当前目标日历会让
 *    换过目标日历的用户永远删不掉旧日历上的那些（映射表里的行本来就是跨日历的）。
 *
 * 真正那一趟 `query` 住在调用点（`CalendarSyncManager.queryOwnedEventIdsOnce()`），
 * 与同文件 `queryCalendarsOnce` 同一形状：`runCatching` 包住、失败留 `Log.w` 带原因、
 * 交回 null 表示"这一次没问到答案"。本文件只留契约（投影 / selection / 实参表 / 折叠档位），
 * 为的是这四枚东西能在 JVM 里被钉住 —— `CalendarQuery` 抽出来同一个理由。
 *
 * ⚠️ **只能装机验的那一格**（本卡零设备、零 adb，如实登记）：`custom_app_package` 能否作为
 * selection 条件参与查询、以及各家 ROM 的 provider 是否会为它建索引/返回预期行集，
 * 在本仓的 JVM 判据层证不了 —— 这里能证的只有「机制 + 源码形状 + 折叠档位」。
 * 真机要量的是：同步 N 枚事件后按标记枚回来的枚数是否等于 N；把标记列改成不可查时是否落进
 * [MarkerRead.Refused]（而不是静默答空集被当成"我们没建过任何事件"）。
 * 后者那一档即便发生，[fold] 交回的 `Answered + 空集` 在 planReclaim 里也会因"映射表却有货"
 * 被折进 `MarkerBlindReason.AnsweredEmptyAgainstMappings`，屏上另念一句 —— 不会静默降级成只删映射。
 */
internal object CalendarOwnershipQuery {

    /** 投影：只有 [_ID]。见类 KDoc「刻意没有的东西」第 2 条。 */
    val projection: Array<String> = arrayOf(CalendarContract.Events._ID)

    /** selection：逐枚按所有权标记等值匹配，实参只递本包名（不含 OR、不含第二枚列）。 */
    val selection: String = "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ?"

    /** 实参表：恰好一枚，就是本包名。枚数本身是判据（多一枚就是在放宽范围）。 */
    fun selectionArgs(packageName: String): Array<String> = arrayOf(packageName)

    /**
     * 把"问没问 / 问回来了什么"折成内核要的档（纯 JVM，被 `CalendarOwnershipQueryTest` 逐格钉）。
     *
     * @param rows provider 交回的 id 列表；**null 表示这一次问失败了**（抛异常，或 cursor 都没给）
     * @param attempted 有没有去问（没有读权限时压根不该问）
     *
     * `Answered + 空集` 与 `Refused` 是分开的两档：前者是一次可信的回答（"没有带我们标记的事件"），
     * 后者是"这台机器的日历不肯答"，屏上念的话不一样，而**两档都不许扩大删除范围**。
     */
    fun fold(rows: List<Long>?, attempted: Boolean): Pair<MarkerRead, Set<Long>> = when {
        !attempted -> MarkerRead.NotAttempted to emptySet()
        rows == null -> MarkerRead.Refused to emptySet()
        else -> MarkerRead.Answered to rows.toSet()
    }
}
