package com.buaa.schedule.domain.update

import java.util.concurrent.CancellationException

/**
 * 「检查更新」出网那一手的**收场判据**（T365，在册 #536 后半／T363 交回的 C-1，编排侧 #631）。
 *
 * ## 它补的是哪一句假话
 *
 * 调用点 `update/UpdateCheck.kt` 把唯一的出网那一手泡在 `runCatching` 里。标准库那一手兜的是
 * `Throwable`，于是**协程取消也被兜成一枚失败**：用户转屏（更新弹窗的宿主在主题层，
 * `LaunchedEffect(Unit)` 随组合一起摘掉）或离开设置页（`rememberCoroutineScope()` 撤销）都落在这一档。
 * 兜下来的取消在原盘面上同时造出两句假话：
 * - 屏上弹「检查更新失败」——`present` 看见非 null 的 `failureReason` 就交回 `CheckFailed`，
 *   而 `readable` 的 else 那一档是「message 或一句兜底话」，对任何异常都交出非 null；
 * - 偏好里落一枚失败档（`OUTCOME_FAILED`）＋ 一枚从没发生过的检查时刻（`KEY_LAST_AT`）＋
 *   一枚失败时间戳（`KEY_FAILURE_AT`）——杀进程之后设置页那一行念的是「上次检查失败」，
 *   而它根本没失败过；上一手真成功的时刻也被盖掉，盖不回来。
 *
 * ## 为什么取消必须自己占一档，而不是并进失败
 *
 * 取消**不是**「查过了、没查到」，也**不是**「查挂了」：它压根没拿到答复。折进失败那一档就同时要
 * 替它编一句失败原因、替它落一枚失败档；折进成功那一档（原样交回旧状态后走落盘的 else 分支）就更狠——
 * 会把「今天已经成功查过」写进偏好，于是当天剩下的每次启动都再也查不到更新。两档都是假话，
 * 所以这里给它单独一档：不落盘、把内存里那一档收回 Idle、取消原样交回上层。
 *
 * ## 纯度口径（本仓明文规矩）
 *
 * 零 android / androidx import、**不读时钟、不读时区、不读权限、不读 `Build.VERSION`**，不读偏好、
 * 不碰网络、不碰协程作用域。输入只有两枚：调用点 `runCatching` 交回的那枚异常本体（没有就是 null），
 * 以及调用点自己的**措辞函数**——异常怎么念给用户听不在本卡范围（在册 #369 是另一档），
 * 这里只管它算哪一类。归档那一手（`fateOf`）是全仓唯一一处：调用点不许再自己分第二把尺。
 *
 * ## 三格结论为什么是三格而不是 Boolean
 *
 * 调用点要分三件不同的事：**落不落盘**、**内存里摆哪一档**、**取消要不要交回上层**。
 * 原盘面上这三件被一枚「有没有异常」压成了同一件，于是取消那一手同时做错了三件事。
 * 折成一枚 Boolean 就退回同一个病灶：`persistsRun` 与 `rethrowsCancellation` 在同一次取消上
 * 确实是同一句话，但它们在真失败那一档上正相反（落盘、不复投）——分不开就总有一档要说谎。
 */

/** 出网那一手的归档。取消是控制流，不是错误，所以它有自己的一档（`Cancelled`）。 */
enum class UpdateFetchFate {
    /** 拿到了答复（列表空不空、新版本是谁不由本档定，那是 `UpdateCheckGate.present` 那一手的事） */
    Answered,

    /** 协程被摘掉：既没拿到答复，也不是失败 */
    Cancelled,

    /** 真的失败（网络挂了、HTTP 不是 2xx、正文解析不出来） */
    Failed,
}

/** 收场时内存里那枚状态摆成什么。只有取消那一手需要调用点自己收尾。 */
enum class UpdateRestingTier {
    /** 交给 `UpdateCheckGate.present` 定档（答复与真失败都走这一档，本文件不另起第二把尺） */
    LeaveToPresentation,

    /** 收回 Idle：转圈那一档不许留在内存里（留在原地会把之后每一次检查都顶成静默 no-op） */
    RestInIdle,
}

/**
 * 收场结论：三格各管一件事，调用点逐格照办。
 *
 * [reason] 只有 `Failed` 那一档是非 null 的，它直接递给 `present` 的 `failureReason`——
 * 这样「谁算失败」与「失败要说哪一句」在本文件里同源，调用点拿不到第二处归档的机会。
 */
data class UpdateFetchSettlement(
    val fate: UpdateFetchFate,
    val reason: String?,
    val persistsRun: Boolean,
    val restingTier: UpdateRestingTier,
    val rethrowsCancellation: Boolean,
) {
    companion object {
        /**
         * 归档那一手：全仓唯一一处「谁算取消」。
         *
         * `java.util.concurrent.CancellationException` 与 `kotlinx.coroutines.CancellationException`
         * 在 JVM 上是同一枚类（后者是 typealias），`is` 判据连带它的每一枚子类
         * （`JobCancellationException`、`TimeoutCancellationException`）一起收进 `Cancelled` 那一档。
         */
        fun fateOf(error: Throwable?): UpdateFetchFate = when {
            error == null -> UpdateFetchFate.Answered
            error is CancellationException -> UpdateFetchFate.Cancelled
            else -> UpdateFetchFate.Failed
        }

        /**
         * 归档 + 定档一次做完。[readable] 是调用点自己的措辞函数（只在 `Failed` 那一档才被调用）。
         */
        fun of(error: Throwable?, readable: (Throwable) -> String): UpdateFetchSettlement {
            val fate = fateOf(error)
            val reason = if (fate == UpdateFetchFate.Failed && error != null) readable(error) else null
            return when (fate) {
                // 取消：一次都不落盘、内存收回 Idle、取消原样交回上层
                UpdateFetchFate.Cancelled -> UpdateFetchSettlement(
                    fate = fate,
                    reason = null,
                    persistsRun = false,
                    restingTier = UpdateRestingTier.RestInIdle,
                    rethrowsCancellation = true,
                )

                // 真失败与拿到答复：都要落盘，档位都交给 present（失败要说的那一句由 present 定，不在这里折）
                UpdateFetchFate.Failed, UpdateFetchFate.Answered -> UpdateFetchSettlement(
                    fate = fate,
                    reason = reason,
                    persistsRun = true,
                    restingTier = UpdateRestingTier.LeaveToPresentation,
                    rethrowsCancellation = false,
                )
            }
        }
    }
}
