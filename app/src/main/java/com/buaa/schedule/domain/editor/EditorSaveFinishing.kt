package com.buaa.schedule.domain.editor

/**
 * 编辑器**保存链收尾**那一条判据的唯一一份（T260，台账 #498 = T258-04）。
 *
 * 纯判据：零 android / androidx import、**不读时钟、不读时区、不读权限、不读 `Build.VERSION`**，
 * 也不自己去够任何一枚状态 —— 四枚事实（有没有一项写库在这页进行、这一趟写库交回了什么结论、
 * 收尾那一手所在的 scope 还在不在、这一页拿的是新建身份还是编辑身份）**全部**由调用点
 * （`ui/editor/CourseEditorScreen.kt` 的保存链 + `MainActivity` 编辑器目的地那一层）算好当参数递进来，
 * 本文件里一个都不许自己取（本仓明文口径）。
 *
 * ## 病在哪（装机口径：点保存到落库之间转一次屏）
 *
 * 写库那一半早在 T124c 就进了 `viewModelScope` 的那一枚 job（页 scope 被 dispose 也照样跑完整趟），
 * 而**收尾那一半**（`onBack()` 那一手）还挂在 `rememberCoroutineScope()` 供给的那枚 scope 上。
 * 于是「点保存 → 落库」之间转一次屏：
 *
 * 1. 配置变化整棵重建 composition ⇒ 那枚页 scope 被取消 ⇒ 挂在 `join()` 之后的收尾一行都不执行；
 * 2. 那一枚 `viewModelScope` job 不受影响，课**已经进了库**（还顺带压了撤销条目、重排了课前铃）；
 * 3. 新 composition 里编辑器照旧以**发起那一刻的身份**组合 —— 新建那一路是 `editor/-1`，
 *    `initialCourse` 依旧是 null，草稿又是 `rememberSaveable` 的，于是屏幕原样退回「添加课程」；
 * 4. 用户看见的是「点了保存什么也没发生」，再点一次 ⇒ `id = 0L` 那一路**再插一遍** ⇒ 库里两门同样的课。
 *
 * 删除链那一档（同一枚病换了触发器）T166/T177 已经收过：退栈归**目的地层**那枚协程认领、
 * 凭据放在跨配置变化存活的载体里。本卡接的是它留在盘上的另一半 —— 在册普查（
 * `ui/editor/CourseEditorDeleteExitReceiverGuardTest` 第 ③ 层）当时就把保存链这一支登记为
 * 「今天不咬人、但脆」的那一枚，并写明正确的收法是「照删除链那一手搬」。
 *
 * ## 修法（第 ⓪ 问裁的是**乙档**）
 *
 * **写库结果落进转屏存活的状态（`ScheduleViewModel` 那一枚旗），收尾只消费状态决定退栈** ——
 * 收尾不再依赖任何一枚会被组合取消的 scope。三档里为什么不是另两档：
 * - **甲档**（把收尾连同退栈搬到 `viewModelScope`，与写库同一枚接收者）装不下：那一枚协程攥的是
 *   **发起那一刻**的 `navController` / `savedStateHandle` / `mutableStateOf`，转屏之后这三样全部
 *   属于已经被销毁的那棵 composition。把 launch 的接收者换掉，改的只是「谁来跑这一行」，
 *   跑的还是一枚死掉的 controller 与一份没人再读的状态 —— 换写法，不换病。
 * - **丙档**（只加一枚跨配置存活的「保存在途」旗，返回时按旗决定退栈，即 T166 删除链那一站的形状）
 *   缺**另一半信息**：旗只能说出「这一页发起过一趟保存」，说不出那一趟**落成了没有**，
 *   而保存与删除在这里恰恰不同 —— 删成了必须退、没落成才不许退（退了就等于把没写进去的草稿咽掉）。
 *   结果那一半只能由跑完那一趟的人交回，也就是 VM 的状态。
 * - **乙档**留给了「页面那一支还活着」那条最常见的一路原有的形状：页面照旧自己退栈
 *   （它活着，且它比目的地层更早知道结论），目的地层只在**页面那一支被证明没能跑完**时替它认领。
 *   于是两枚认领点结构上互斥：结论交回时页面报到了 ⇒ 目的地不动；没报到 ⇒ 目的地弹。
 *   这条互斥不靠时序论证，靠的是 [EditorSaveStage.tailReported] 那一格盘上可读的事实。
 *
 * ## 判据顺序（顺序即语义）
 *
 * 1. 结论属于**别处**发起的那一趟（陈旧回执：上一 entry 在写库途中被用户自己退掉）⇒
 *    [EditorSaveFinish.IgnoreForeignConclusion]：这一页一个字节都不许动。少了这一档，
 *    新打开的那页编辑器会替**上一趟**写库退自己的栈。
 * 2. 结论落成了 ⇒ 按「页面那一支还活着吗」分两半：
 *    活着 ⇒ [EditorSaveFinish.PageTailOwnsThePop]（目的地层不许跟着弹，否则连 pop 两格）；
 *    没了 ⇒ 按身份分两半：新建 ⇒ [EditorSaveFinish.DestinationPopsEditorHoldingGate]
 *    （退栈之前闸门不许放开 —— 这一页的身份还是 `id = 0L`，此刻再点一次就是第二门课，
 *    本卡那枚「重」正落在这里）；编辑 ⇒ [EditorSaveFinish.DestinationPopsEditor]
 *    （同一行再写一遍不坏数据，所以不必把按钮钉到退栈那一帧之后）。
 * 3. 结论是失败 ⇒ [EditorSaveFinish.StayWithoutPop]：**不退栈**。库里没有那一行，
 *    退了就是把用户的草稿咽掉；红条由还活着的那一支念（它没跑完时这一档只是不复弹，
 *    本卡不替在册 #381 那一档「转屏洗掉失败文案」负责）。
 * 4. 还没结论 ⇒ 在途那一档 [EditorSaveFinish.HoldGateAwaitingConclusion]（闸门按住：写库在飞时
 *    转屏，新 composition 的 `saving` 从 false 重新算起，放开就等于允许第二趟写库起手）；
 *    既没在途也没结论 ⇒ [EditorSaveFinish.NothingPending]。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 调用点要按档做**三件**不同的事：要不要把保存闸门按住、要不要由目的地层退栈、要不要什么都不做；
 * 而「落成了」这一件事还要再按 scope 命运与页面身份分岔（三档动作互不相同）。折成 Boolean
 * 就只能回答「退不退」，恰好丢掉本卡最贵的那两格：新建身份在途时的闸门，与页面那一支活着时
 * 目的地层必须**不**弹的那条互斥。
 */
enum class EditorDraftIdentity {
    /** 新建那一路：保存走 `id = 0L` 插一行 ⇒ 同一趟结论落地之后再点一次就是第二门课 */
    CreatingNewCourse,

    /** 编辑既有行：重复保存只是把同一行再写一遍，没有多插一行的风险 */
    EditingExistingCourse,
}

/** 这一趟写库交回的结论，**按「是不是这一页发起的那一趟」配好对**之后的四档。 */
enum class EditorWriteOutcome {
    /** 没有结论：这一页没发起过保存，或者那一趟还在写 */
    NoConclusion,

    /** 落成了（那一行已在库里） */
    Landed,

    /** 交回了、但没落成（仓储回了 null / 归一化判死） */
    Failed,

    /** 结论属于别处发起的那一趟（陈旧回执）：本页一枚字都不许动 */
    ForeignConclusion,
}

/** 那一趟写库在 VM 状态里走到了哪一步（VM 只存这一枚，判据全在这里） */
enum class EditorSavePhase {
    /** 已经起手、还没交回结论 */
    Writing,

    /** 交回了「落成了」 */
    Landed,

    /** 交回了「没落成」 */
    Failed,
}

/**
 * 转屏存活的那一枚旗的形状：谁是这一趟的主人（[requestId]）、写到哪一步（[phase]）、
 * 页面那一支有没有跑完（[tailReported]）。
 *
 * [tailReported] 是「收尾所在的 scope 还在不在」的**盘上证据**：它由页面那一支（跑在
 * `rememberCoroutineScope()` 上）在 `join()` 回来之后第一手写；那一支被组合取消时这一格
 * 永远留在 false —— 于是目的地层不需要猜时序，读一个值就分得出两种命运。
 */
data class EditorSaveStage(
    val requestId: Long,
    val phase: EditorSavePhase,
    val tailReported: Boolean,
)

/** 哨兵：这一条 entry 还没发起过保存。`requestId` 由 VM 的发号器给，结构上取不出 0L。 */
object EditorSaveSentinel {
    const val NO_REQUEST = 0L
}

/** [EditorSaveFinishing.readout] 的交回：内核要的三枚已算好的值（调用点不许自己再算一遍，那是第二把尺）。 */
data class EditorSaveReadout(
    val writeOutcome: EditorWriteOutcome,
    val saveInFlight: Boolean,
    val tailScopeCancelled: Boolean,
) {
    /** 配上这一页的身份，交回本卡的档位 */
    fun finish(identity: EditorDraftIdentity): EditorSaveFinish = EditorSaveFinishing.decide(
        saveInFlight = saveInFlight,
        writeOutcome = writeOutcome,
        tailScopeCancelled = tailScopeCancelled,
        identity = identity,
    )
}

/** 保存收尾的档位：七档，调用点按档分派，不许折叠。 */
sealed interface EditorSaveFinish {

    /** 没在写库、也没结论：闸门与退栈都照常由页面管 */
    data object NothingPending : EditorSaveFinish

    /** 写库在途、结论还没交回：闸门按住，谁都不许退栈 */
    data object HoldGateAwaitingConclusion : EditorSaveFinish

    /** 落成了、页面那一支也跑完了：退栈归页面，**目的地层不许跟着弹**（连 pop 两格那一族） */
    data object PageTailOwnsThePop : EditorSaveFinish

    /** 落成了、页面那一支没跑完（转屏 / 摘页）：编辑身份 ⇒ 目的地层退栈 */
    data object DestinationPopsEditor : EditorSaveFinish

    /** 同上，但新建身份 ⇒ 退栈那一帧之前闸门还须按住，否则第二趟 `id = 0L` 又插一行 */
    data object DestinationPopsEditorHoldingGate : EditorSaveFinish

    /** 没落成：不退栈（库里没有那一行），红条由活着的那一支念 */
    data object StayWithoutPop : EditorSaveFinish

    /** 陈旧回执（别处发起的一趟）：这一页什么都不做 */
    data object IgnoreForeignConclusion : EditorSaveFinish
}

/** 编辑器保存链收尾判据的入口。 */
object EditorSaveFinishing {

    /**
     * 把 VM 里那一枚旗读成内核要的三枚事实。纯函数：[stage] 与 [myRequestId] 都由调用点算好递进来，
     * 本函数不读任何状态、不碰任何 scope。
     */
    fun readout(stage: EditorSaveStage?, myRequestId: Long): EditorSaveReadout {
        val mine = myRequestId != EditorSaveSentinel.NO_REQUEST && stage?.requestId == myRequestId
        val phase = stage?.phase
        // 「页面那一支到底报到过没有」只从这一枚旗上取一次，下面两档共用同一个读法（不在档位里各写一遍）
        val tailReported = stage?.tailReported == true
        return when {
            stage != null && !mine -> EditorSaveReadout(
                writeOutcome = EditorWriteOutcome.ForeignConclusion,
                saveInFlight = false,
                tailScopeCancelled = false,
            )

            phase == EditorSavePhase.Writing -> EditorSaveReadout(
                writeOutcome = EditorWriteOutcome.NoConclusion,
                saveInFlight = true,
                tailScopeCancelled = false,
            )

            phase == EditorSavePhase.Landed -> EditorSaveReadout(
                writeOutcome = EditorWriteOutcome.Landed,
                saveInFlight = false,
                tailScopeCancelled = !tailReported,
            )

            phase == EditorSavePhase.Failed -> EditorSaveReadout(
                writeOutcome = EditorWriteOutcome.Failed,
                saveInFlight = false,
                tailScopeCancelled = !tailReported,
            )

            else -> EditorSaveReadout(
                writeOutcome = EditorWriteOutcome.NoConclusion,
                saveInFlight = false,
                tailScopeCancelled = false,
            )
        }
    }

    /** 判据本体：四枚已算好的值 ⇒ 七档之一（顺序与理由见本文件头「判据顺序」一节）。 */
    fun decide(
        saveInFlight: Boolean,
        writeOutcome: EditorWriteOutcome,
        tailScopeCancelled: Boolean,
        identity: EditorDraftIdentity,
    ): EditorSaveFinish = when {
        writeOutcome == EditorWriteOutcome.ForeignConclusion -> EditorSaveFinish.IgnoreForeignConclusion

        writeOutcome == EditorWriteOutcome.Landed -> when {
            !tailScopeCancelled -> EditorSaveFinish.PageTailOwnsThePop
            identity == EditorDraftIdentity.CreatingNewCourse -> EditorSaveFinish.DestinationPopsEditorHoldingGate
            else -> EditorSaveFinish.DestinationPopsEditor
        }

        writeOutcome == EditorWriteOutcome.Failed -> EditorSaveFinish.StayWithoutPop

        saveInFlight -> EditorSaveFinish.HoldGateAwaitingConclusion

        else -> EditorSaveFinish.NothingPending
    }

    /** 这一档要不要把保存闸门按住（第二趟写库不许起手） */
    fun holdsSaveGate(finish: EditorSaveFinish): Boolean =
        finish == EditorSaveFinish.HoldGateAwaitingConclusion ||
            finish == EditorSaveFinish.DestinationPopsEditorHoldingGate

    /** 这一档归**目的地层**退栈（页面那一支已经证明跑不到） */
    fun claimedByDestination(finish: EditorSaveFinish): Boolean =
        finish == EditorSaveFinish.DestinationPopsEditor ||
            finish == EditorSaveFinish.DestinationPopsEditorHoldingGate
}
