package com.buaa.schedule.ui.importing

/**
 * 导入确认落库之后那一句「导入完成：…」的措辞（T181①）。
 *
 * 零 android / androidx import、零时钟与设备读取（本仓口径）：四枚计数都由调用点
 * （`com.buaa.schedule.ui.ScheduleViewModel.confirmPendingImport`）递进来，这里只把它们说成话。
 *
 * ## 病在哪
 *
 * 此前这一句长在 `confirmPendingImport` 里，而那句念的是**预览选中的条数**：
 * 落库那一刀（`ScheduleRepository.replaceSemesterCoursesWithHistory`）在此之后还要过一次
 * [com.buaa.schedule.domain.schedule.CourseConstraints.normalize]，被它判死的行当场蒸发
 * —— 星期几不在 `1..7`、周次全在 `1..MAX_WEEK` 之外、一条节次都没有、名称空白。
 * 于是课表上少一门课，屏幕上那句「新增 N」还是多的，而且**没有任何人念过少的那一门**。
 * 备份恢复那一路早就念这一笔（`RestoreResult.Applied.skippedInvalid` ⇒「跳过无效数据 N 条」），
 * 这一枚姊妹落点一直没扫到。
 *
 * ## 为什么这一句搬进纯内核文件
 *
 * 1. 只有把它抽成 `f(四枚 Int) -> String`，"喂 5 条、其中一条星期越界 ⇒ dropped=1 ⇒ 那句必须
 *    出现跳过"这一格才能在世界两端之外**由 JVM 单测跑出来**（留在 ViewModel 里就得装机）；
 * 2. 那句"什么时候出场"（`droppedCount > 0`）从此与那句"说什么"在同一张表里，
 *    漏念与多念都只有一枚判据可以错，接线守卫（`ImportCompletionSkippedCountGuardTest`）钉的就是这一枚。
 */
internal fun importCompletionMessage(
    addedCount: Int,
    changedCount: Int,
    keptCount: Int,
    droppedCount: Int,
): String = buildString {
    append("导入完成：新增 $addedCount、更新 $changedCount")
    if (keptCount > 0) append("、保留 $keptCount")
    append("（含拆行片段）")
    if (droppedCount > 0) append("（跳过 $droppedCount 条无效）")
}
