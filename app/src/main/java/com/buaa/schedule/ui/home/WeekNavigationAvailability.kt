package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.SemesterWeekCount

/**
 * 「没有学期」时首页那一排周次控件的判据内核（T197）。
 *
 * 纯判据：零 android import、零时钟读数、零设备读数（本仓收单硬口径，写法照
 * [WeekHeaderTodayPolicy] 与 `ConflictShiftWeekScope.kt` 那两枚同族内核）——
 * 页签序号、有没有学期、学期总周数、这批课的周次信息四样事实一律由调用点当参数递进来。
 *
 * ## 病（一枚硬写的 20 周在替用户过日子）
 *
 * 没设学期的那个状态（新装还没导入 / 学期行被删）在首页**不是一次空状态，是一套按假 20 周
 * 运转的界面**：`HomeScreen` 与 `WeekView` 各写了一枚兜底，原文两行是
 * `totalWeeks = (state.semester?.totalWeeks ?: 20)`（首页那一枚，递进工具条后同时喂跳周清单与进度线）
 * 与 `val totalWeeks = (semester?.totalWeeks ?: 20)`（周视图那一枚，下游是卡角旗与横滑 Pager）。于是
 * ① 「跳转到周次」的清单真列出「第 1 周 … 第 20 周」，点下去 `browseWeek` 真拿到一个周号；
 * ② `SemesterProgressLine` 拿假 20 画一条学期进度轨道（`currentWeek` 此时是 null ⇒ 填充 0f，
 *    而那条素色轨道照画 —— 它说的是"这学期多少周"，这枚事实根本不存在）；
 * ③ 课程卡「不是每周都有」那枚旗（进读屏文案「，不是每周都有」与卡角标记）的分母也是假 20。
 * 同一屏的网格因为 `showAllCoursesWithoutWeek = semester == null` 把**全部课程**画出来、与第几周
 * 无关，表头那一行日期也因 T190 落地的 [headerWeekNumberOrNull] 一枚不印。
 * ⇒ 造出来的周次被界面当成真的在用。
 *
 * ## 口径：没有学期就不给周次导航，凡吃"这学期多少周"的地方一律留空
 *
 * 清单空、进度线不画、旗不亮 —— 与 [weekHeadline] 那句「未设置学期」、
 * [headerWeekNumberOrNull] 的"零 `?: 1`"是同一条口径（本仓那族「造值 vs 说实话」的账）。
 * ⚠️ 顶栏第一行**本来就说实话**：`semester == null` 时 [weekHeadline] 念的是「未设置学期」而不是
 * 「第 N 周」，本卡收的是它下面那一排还在按假 20 运转的控件。
 *
 * ## 两枚结论同源
 *
 * [weekCountsOf] 一次给出「Pager 页数」与「不是每周都有的分母」两枚结论：两枚说的都是
 * "这学期多少周"，拆成两份表达式就是 T182/T190 那一族「同一个判据两份写法各错各的」。
 *
 * ## 为什么选 `shouldDrawSemesterProgress` 而不是把 `SemesterProgressLine` 改成吃 `Int?`
 *
 * 卡面给的是二选一，选前者三条理由：① `ScheduleCharts.kt` 是设计系统那侧的共享件（统计页与
 * 首页同吃它的画法），把"这一屏有没有学期"这枚页面级事实塞进组件签名，下一个调用点就会拿 null
 * 表达第三种含义；② 判据落在本文件里才进得了表驱动单测 —— 本模块没有 Compose 运行时
 * （无 Robolectric、无 ui-test），组件那一路只能钉源码形状；③ 改签名要连带动组件 KDoc 与它
 * `currentWeek != null && totalWeeks > 0` 那枚既有算式，而本卡只想收"假 20"那一半。
 */

/**
 * 第二行那一簇周次控件（‹ 周次 › + 「跳转到周次」清单 + 学期进度线）画不画。
 *
 * 改前只看 `selectedTab == 0`（周课表页签），"没设学期"那一档照画 ⇒ 本卡补的就是这一维。
 * ⚠️ T202 之后这一枚**只管簇**：「课次 / 时间」那颗模式胶囊已经搬出簇，改由
 * [shouldOfferTimeModeToggle] 决定画不画（它不吃 `hasSemester`）。
 *
 * ⚠️ 卡面把第三枚参数写成 `todayTab`，读码后按盘面改名 `weekTab`：调用点那一句的语义是
 * 「选中的是**周课表**页签」。这一枚序号在全仓没有任何命名常量表示它 ——
 * `selectedTab` 只有 `rememberSaveable { mutableIntStateOf(0) }` 与
 * `if (hasTodayCourses) 1 else 0` 两处字面量，没有第二处名字可传。照着 `todayTab` 写就得把比较
 * 换成 `selectedTab != todayTab` 并新写一枚 `1` —— 那是盘上本来不存在的事实。
 * 序号仍由调用点当参数递进来，内核里一枚都不写死（卡面这条约束保住了）。
 */
internal fun shouldOfferWeekNavigation(selectedTab: Int, hasSemester: Boolean, weekTab: Int): Boolean =
    hasSemester && selectedTab == weekTab

/**
 * 「课次 / 时间」那颗模式胶囊画不画（T202）。
 *
 * 只吃「这一屏画的是不是周课表」一枚维度，**不吃** `hasSemester`：
 * 它改的是 `Personalization.weekGridMode`（落盘偏好），与"这学期多少周"没有任何关系。
 *
 * 病（#241 / 原 T200）：这颗胶囊原先住在第二行那一簇 `AnimatedVisibility` **里面**，
 * 于是跟着 [shouldOfferWeekNavigation] 的 `hasSemester` 那一维一起进出 ——
 * 没设学期时（新装还没导入 / 学期行被删）全仓唯一一颗能改 `weekGridMode` 的入口从屏上消失，
 * 而偏好照旧落盘：`ui/home/WeekView.kt` 里那枚 `timeMode` 照旧为真 ⇒
 * **屏上画的是 24 小时时间轴，而切回来的那颗胶囊看不见**。
 * 与 [shouldOfferWeekNavigation] 分家之后，簇里只剩「‹ 周次 › + 跳周清单 + 学期进度线」三样
 * 真正吃"这学期多少周"的东西。
 *
 * ⚠️ 这一枚与 [shouldOfferWeekNavigation] 是**两枚不同的判据**，不许合成一枚：
 * 合并任何一种写法都会错 —— 跟着 `hasSemester` 走就是本病灶（入口不可达），
 * 只看页签走就是让没学期时那排假周号控件复活（T197 的账）。
 */
internal fun shouldOfferTimeModeToggle(selectedTab: Int, weekTab: Int): Boolean =
    selectedTab == weekTab

/**
 * 「跳转到周次」那枚清单。
 *
 * - 没有学期 ⇒ **空清单**（改前这里是就地物化的 `(1..totalWeeks).toList()`，而那一枚 totalWeeks
 *   就是文件头点名的假 20 ⇒ 真列出 20 行、真能点）；
 * - 有学期 ⇒ 区间由那枚具名读数 [SemesterWeekCount.readable] 给出（上界 30 那笔"兼容历史脏数据：
 *   总周数限幅，避免跳周列表物化超大列表"的账现在只长在读数里一处，本文件不再自己夹）；
 * - `hasSemester` 为真而周数取不到、**或学期行那一枚是脏的 ≤ 0**（T331 收 #452）：按空清单处理。
 *   改前 0 被 `coerceIn` 折成 1 ⇒ 清单真能点出「第 1 周」，而同一学期在判据尺 `TermWeekGate`
 *   那里一周都不在学期内 —— 清单里点得开的门与判据尺说没有的那一周必须是同一批。
 */
internal fun weekChoicesFor(hasSemester: Boolean, totalWeeks: Int?): List<Int> {
    val weeks = if (hasSemester) SemesterWeekCount.readable(totalWeeks) else null
    if (weeks == null) return emptyList()
    return (1..weeks).toList()
}

/**
 * 学期进度线画不画。
 *
 * 那条线是「第 N 周」这句话的图形化，没有学期就没有这句话、也没有这条轨道：
 * 改前 `currentWeek = null` 只把**填充**归零，96dp 的素色轨道照画，画的正是那枚不存在的学期。
 */
internal fun shouldDrawSemesterProgress(hasSemester: Boolean): Boolean = hasSemester

/**
 * 两枚「这学期多少周」的结论，一份函数：`(Pager 页数, 不是每周都有的分母)`。
 *
 * - 有学期：两枚都是具名读数 [SemesterWeekCount.readable] 的答案（它答 null = 没有学期行、
 *   或那一枚是脏的 ≤ 0 ⇒ 分母 null ⇒ 旗不亮，T331 前这一格被折成 1 周当分母使）；
 * - 没学期：分母改吃**这批课里真实出现过的最大周号**（教务/导入的周次数据本身是真的），
 *   一枚周号都没有 ⇒ 分母 null ⇒ 旗不亮（[notEveryWeekFlag]）；
 * - 没学期的页数跟着同一枚分母走（改前是假 20，于是横滑 Pager 能翻到第 20 页根本没有的周）；
 *   分母取不到时给 **1** —— `HorizontalPager` 的 `pageCount = 0` 会让调用点那两枚
 *   `(pagerWeek - 1).coerceIn(0, totalWeeks - 1)` 变成 `coerceIn(0, -1)` 直接抛，
 *   而那一档屏幕上本来就没有"第几周"可翻，单页就是它的形状。
 */
internal fun weekCountsOf(
    hasSemester: Boolean,
    semesterTotalWeeks: Int?,
    courses: List<Course>,
): Pair<Int, Int?> {
    val base = if (hasSemester) {
        SemesterWeekCount.readable(semesterTotalWeeks)
    } else {
        SemesterWeekCount.readable(courses.maxOfOrNull { course -> course.weeks.maxOrNull() ?: 0 })
    }
    return (base ?: 1) to base
}

/**
 * 「这门课不是每周都有」那枚旗（卡角标记 + 读屏文案「，不是每周都有」）。
 *
 * 分母取不到 ⇒ **旗不亮**：改前那半句念的是"这门课上的周数 < 假 20"，
 * 读屏于是替我们念出一句没有依据的话（本卡病灶 B）。
 * 分母取得到时与改前同式（`weeks.size < 分母`），一枚语义都不改。
 */
internal fun notEveryWeekFlag(weeksSize: Int, notEveryWeekBase: Int?): Boolean =
    notEveryWeekBase != null && weeksSize < notEveryWeekBase
