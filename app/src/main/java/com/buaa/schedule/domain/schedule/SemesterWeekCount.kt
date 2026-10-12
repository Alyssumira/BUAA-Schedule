package com.buaa.schedule.domain.schedule

/**
 * 「这学期有多少周」的全仓唯一**具名读数**（T331，合卡口径 = 台账 #452 + #398 + #397）。
 *
 * 纯 JVM 判据：零 android import、零体内读钟、零设备读数（本仓硬口径）——学期行那一枚
 * `totalWeeks` 由调用点当参数递进来，本文件一颗钟、一张表、一个 `Context` 都不碰。
 *
 * ## 病（同一枚脏学期行，四把尺给四种答案）
 *
 * 同一件事实「这学期有多少周」在盘上原本有四把各自夹取的尺，`totalWeeks = 0` 那一档答四种话：
 *
 * 1. [SemesterStats.weekAxisLength] 展示尺：`totalWeeks <= 0` 就**不用**它，退到课程周号的最大值；
 * 2. [TermWeekGate.isInTermWeek] 判据尺：0 原样当上界使 ⇒ 一周一都不放行（`WeekCalculator`
 *    那一枚比较式对 `bound = 0` 恒答 null）；
 * 3. `ui/home/HomeScreen.kt` 顶栏：`0.coerceIn(1, MAX)` ⇒ 对屏幕念出「这学期 1 周」；
 * 4. `ui/home/WeekNavigationAvailability.kt` 的跳周清单：同样折成 1 ⇒ 清单真能点出「第 1 周」，
 *    而第 2 把尺说这一周不存在 —— 那就是 #452 那扇门。
 *
 * ## 可达性（第 0 问的裁定，别按想象读这一段）
 *
 * **丙·部分可达**。今天 master 的写侧一条都不产 ≤ 0：教务那一路交 `weeks.size` 或写死 20
 * （`data/import/BuaaInPageFetcher.kt` 的 `fetchSemesterInfo`，空列表在取数之前就早返回了）、
 * 设置页过 [CourseConstraints.normalizeTotalWeeks]、恢复那两等也过它
 * （`data/backup/BackupSemesterRestorePolicy.kt` 的 `InsertFromBackup` 与 `Rebuild`）。
 * 唯一进得来的路是**存量学期行**：已发版构建（tag `v0.1.0` / `v0.1.1`）的
 * `restoreBackupDataInTx` 主学期那一路把备份里的裸值直接写库（那一版是
 * `replaceSemesterCoursesInTx(backupSemester, ...)`，限幅排在 T204/T223 之后才补上），
 * 而备份文件与分享口令两枚入口都汇到那一跳。`data/local/SemesterEntity.kt` 没有 CHECK 也没有
 * DEFAULT，`data/local/AppDatabase.kt` 的迁移链一枚都没动过 `semesters` ⇒ 那行脏值今天就躺在那儿，
 * 经 `data/local/Mappers.kt` 的裸映射进 `state.semester`，四把尺逐条读它。
 *
 * ## 读数本体怎么定
 *
 * `null` 说的是**这一档压根没有「这学期多少周」这枚事实**：没有学期行，或那一行的周数是脏的
 * （0 / 负数）。把脏值折成 1 就是替用户宣称"这学期只上一周"，并把跳周清单的门开在一周都不放行的
 * 判据尺上（#452）。非 null 恒落在 `1..CourseConstraints.MAX_TOTAL_WEEKS`：上界那一夹不是装饰 ——
 * 它是"兼容历史脏数据：总周数限幅，避免跳周列表物化超大列表"那笔账的唯一落点，
 * 本卡把原来分散在调用点的两枚 `coerceIn(1, MAX_TOTAL_WEEKS)` 收到这里，只剩这一处。
 *
 * ⚠️ [TermWeekGate] **不读**这一枚读数，这是刻意的、不是漏掉：它买的是调用点递来的裸上界，
 * 而 `TermWeekGate.UNBOUNDED_TOTAL_WEEKS`（= `Int.MAX_VALUE`）那一等"明说不判上界"的输入
 * 一旦经过夹取就变成 30，展示侧那三处历史包袱（`widget/WidgetCommon.kt` 两处、
 * `reminder/LiveClassResyncer.kt` 一处）当场改口。那一族的对账归 #398 / #397，见下面册子第 2 行。
 *
 * ## 差异册子（同一批输入，四把尺该答什么；每一格都能在盘上复算）
 *
 * 输入统一取「开学日 = 2026-09-07 的学期行 + 一批只上到第 8 周的课」，由
 * `domain/schedule/SemesterWeekCountTest` 逐格现算并对照：
 *
 * | 档位 | ① 展示尺 weekAxisLength | ② 判据尺 TermWeekGate | ③ 顶栏读数 | ④ 跳周清单 |
 * |------|------------------------|----------------------|-----------|-----------|
 * | 没有学期行 | 8（退到课程周号） | 递 null ⇒ 一周都不放行 | null | 空清单 |
 * | 学期行 = 0（脏） | 8（同上，退路同源） | 0 当上界 ⇒ 一周都不放行 | **null（T331 前是 1）** | **空（T331 前是 [1]）** |
 * | 学期行 = 16 | 16 | 1..16 放行 | 16 | 1..16 |
 * | 学期行 = 999 | 30（上界夹取） | 1..999 都放行 ⇒ **不夹上界** | 30 | 1..30 |
 *
 * 两格**故意**不同向，册子登记的就是这两格，而不是把它们抹平：
 * ① 有课程周号兜底、② 没有 —— 展示侧画横轴时"没学期行也要画得出这 8 周"是真需求
 * （`ui/home/WeekView.kt` 的 `showAllCoursesWithoutWeek = semester == null` 把课全画出来），
 * 判据侧宁可判不出也不放行才是对的口径（`TermWeekGate` 的「判不出就不放行」第三条）。
 * ② 不夹上界那一格是 #395 / #404 那一族的既有口径，本卡不动它，理由见上面那段 ⚠️。
 *
 * 统计页那排能点的周胶囊（`ui/stats/StatsScreen.kt` 的 `WeekDrillCard`）吃的是
 * `summary.weekCount` = 册子第①列，所以它跟着展示尺走、不跟着清单走 —— 那一格的钻取
 * 不需要 gate 放行（它数的是"这批课的周次信息里有没有第 N 周"），这也是同一枚兜底的下游。
 */
object SemesterWeekCount {

    /**
     * 读数本体。
     *
     * @param totalWeeks 学期行里那一枚周数；null = 没有学期行可问，0 / 负数 = 那一行是脏的
     * @return null = 「这学期多少周」这枚事实不存在，调用方一律按"没有学期"那一档说话；
     *         非 null 恒在 `1..CourseConstraints.MAX_TOTAL_WEEKS`
     */
    fun readable(totalWeeks: Int?): Int? =
        totalWeeks?.takeIf { it > 0 }?.coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)
}
