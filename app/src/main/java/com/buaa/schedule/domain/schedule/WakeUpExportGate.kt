package com.buaa.schedule.domain.schedule

/**
 * WakeUp 导出那一站对用户选中的文件**动几手**（落盘模式两档，卡面不许把它折成一枚 Boolean）。
 *
 * 与同目录 [IcsTargetFileMutation] **同形而各册一本**：本仓口径是"一条落盘链一本册子"
 * （`IcsExportGate` 那本已经被 ICS 链的守卫逐字节钉着，把它借给第二条链读 = 两条链的守卫互相绊倒），
 * 两档的语义、枚数、命名后缀都与那本一致。
 *
 * [TRUNCATE_AND_WRITE] 是唯一会毁掉既有内容的那一档：`ContentResolver.openOutputStream(uri)`
 * 不给模式参数时走的是框架默认的截断写 —— **流一开，原文件内容当场没了**，
 * 后面那句 `write(text)` 哪怕写了 0 个字符，用户选中的那份 `.json` 也已经是 0 字节的空课表了。
 * 所以这一档只许跟着"真的写得出排课片段"那一格出场。
 */
enum class WakeUpTargetFileMutation { TRUNCATE_AND_WRITE, LEAVE_UNTOUCHED }

/**
 * 「这一次 WakeUp 导出该不该落盘 / 用哪种落盘模式 / 该念哪一句」那三件事的**同一本册子**
 * （T277 审计 N4 → T279①），形状逐手照 [IcsExportGate] 抄（四档、判据顺序、正文只由 WriteTruncating 交回）。
 *
 * ## 病在哪
 *
 * 改前 `exportWakeUpTo` 的门只有一枚，且判的是**入参**：`if (courses.isEmpty())`。
 * 而 [ScheduleExporters.toWakeUpJson] 的 `mapNotNull` 会把「一条节次都没有」（T27）与
 * 「一条周次都没有」（T203/#237）两档**整条丢掉** —— 课表里全是这类课时，交回的是
 * `{"name":"…","courses":[]}`，而下一手仍是默认的截断写：用户挑的那份 `.json` 当场被换成
 * 一张空课表，屏幕念的还是 `WakeUp JSON 已导出（0 段排课；另有 N 段…未写入）`
 * （那句逐字长在 `ui/settings/WakeUpExportCopy.kt`）。
 * 「0 段」这一格是**可达**的，不是理论分支：`WakeUpExportDropQuadrantTest` 已证库的读路径
 * 与备份恢复链都能交回一批"缺节次 / 缺周次"的课，而 `EmptyPeriodPlacementTest` 证的是同一形输入。
 *
 * ## 在册裁定：这条链没跟上自家兄弟那条
 *
 * `IcsExportGate.kt` 的 KDoc 里，"截断写会把用户文件清空"这一手**就是拿本链那句
 * 「实际可能写出一个空文件」当自证的** —— 裁定落到了 ICS 一条链，第二条落盘链（本链）没跟上。
 * 本文件就是补那半步，四档、顺序、"正文只由 WriteTruncating 交回"三件形状一并对齐，不另立第二套约定。
 *
 * ## 四档为什么不并成两档（与 ICS 同一本册子的同一条理由）
 *
 * · [RefuseEmpty] —— 一段都写不出去，这是**课表排不出课**，用户能做的是回去补节次/周次；
 * · [RefuseInconsistent] —— 段数报得出、导出件却是空的，这是**导出器自己两格打架**，
 *   用户做什么都没用。并进前者的文案等于让用户去修一个没有坏的地方。
 * 再加上 [NoFileSelected]（选择器取消）与 [WriteTruncating]（有事可写），合起来四档。
 *
 * ## 判据顺序（顺序即语义，与 [IcsExportGate.decide] 逐手同形）
 *
 * 1. 没选中文件 ⇒ [WakeUpExportDecision.NoFileSelected]，先于其它一切。
 *    ⚠️ 今天唯一的调用点在设置页的 `CreateDocument` 回调里（`uri?.let { … }` 已把 null 滤掉），
 *    这一档是替第二枚调用点兜的门，本卡不拿它去要设备证据。
 * 2. 写出去的段数 ≤ 0 ⇒ [WakeUpExportDecision.RefuseEmpty]。**这一条排在文本判据之前**：
 *    按"有没有文本"判永远不等于"有没有课可写" —— 那份导出件即便哪天被改成交回一枚合法的
 *    空骨架，"有文本"也不再等于"有课可写"，只有按段数判才钉得住第 ⓪ 问的裁定。
 * 3. 段数 > 0 而导出件空白 ⇒ [WakeUpExportDecision.RefuseInconsistent]（同样不开流）。
 * 4. 其余 ⇒ [WakeUpExportDecision.WriteTruncating]，把导出件原样交回调用点去写。
 *
 * ## 纯度与证据档位
 *
 * 纯判据：零 android / androidx import、不读时钟、不读时区、不读权限、不读设备度量 ——
 * 四枚事实（写出去的段数、丢下的段数、导出件文本、用户是否真选中了文件）全部由调用点
 * （`ui/ScheduleViewModel.exportWakeUpTo`，段数取自已算好的 `WakeUpDropTally`）当参数递进来。
 * 表驱动单测 `WakeUpExportGateTest`（四档逐格 + 每道边界两侧 + 两把错尺并排复算）；
 * 源码形状守卫 `WakeUpExportGateWiringGuardTest`（开流那一手只长在 WriteTruncating 支里、
 * 判据排在开流之前、改前那一形在代码里 0 处而只留在注释里）。
 */
sealed interface WakeUpExportDecision {

    /** 对用户选中那个文件的处置：四档里只许 [WriteTruncating] 交截断写。 */
    val mutation: WakeUpTargetFileMutation

    /**
     * 有事可写：段数与导出件文本都齐。[json] **只由这一档交回** —— 调用点想开流，
     * 就得先拿到这一档，于是"不落盘"那两支在代码里没有可写的正文可递。
     * 这不是编译器级别的锁（谁都能自己 new 一枚 WriteTruncating），钉住它的是
     * `WakeUpExportGateWiringGuardTest`：`exportWakeUpTo` 体内开流那一手恰好一枚、
     * 且长在 WriteTruncating 那一支的花括号里。
     */
    data class WriteTruncating(
        val json: String,
        val writtenFragments: Int,
        val droppedFragments: Int,
    ) : WakeUpExportDecision {
        override val mutation: WakeUpTargetFileMutation get() = WakeUpTargetFileMutation.TRUNCATE_AND_WRITE
    }

    /**
     * 一段排课都写不出去：不开流、不动用户选中的文件，把这句实话念出来。
     *
     * [droppedFragments] 原样留着：0 段而丢下的段数非零时，"为什么没有"正是用户唯一能动手的地方
     * （那几门课没有节次或没有周次，在对方 App 里本来也定位不了），文案要说得出这一格。
     */
    data class RefuseEmpty(
        val droppedFragments: Int,
    ) : WakeUpExportDecision {
        override val mutation: WakeUpTargetFileMutation get() = WakeUpTargetFileMutation.LEAVE_UNTOUCHED
    }

    /**
     * 段数报得出、导出件却是空的：导出器内部两格打架（写出去几段与交回什么文本不同源）。
     * 同样**不开流**，但句子必须与 [RefuseEmpty] 分开 —— 这一等不是"你的课表排不出课"。
     */
    data class RefuseInconsistent(
        val writtenFragments: Int,
    ) : WakeUpExportDecision {
        override val mutation: WakeUpTargetFileMutation get() = WakeUpTargetFileMutation.LEAVE_UNTOUCHED
    }

    /** 用户没选中任何文件（选择器取消）：不落盘，也一句不说 —— 没有失败可言。 */
    data object NoFileSelected : WakeUpExportDecision {
        override val mutation: WakeUpTargetFileMutation get() = WakeUpTargetFileMutation.LEAVE_UNTOUCHED
    }
}

/** [WakeUpExportGate] 的入口：把四枚已算好的事实折成一档处置。 */
object WakeUpExportGate {

    fun decide(
        writtenFragments: Int,
        droppedFragments: Int,
        json: String,
        fileSelected: Boolean,
    ): WakeUpExportDecision = when {
        !fileSelected -> WakeUpExportDecision.NoFileSelected
        writtenFragments <= 0 -> WakeUpExportDecision.RefuseEmpty(droppedFragments)
        json.isBlank() -> WakeUpExportDecision.RefuseInconsistent(writtenFragments)
        else -> WakeUpExportDecision.WriteTruncating(json, writtenFragments, droppedFragments)
    }
}
