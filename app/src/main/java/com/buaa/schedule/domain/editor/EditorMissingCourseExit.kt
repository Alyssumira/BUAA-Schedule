package com.buaa.schedule.domain.editor

/**
 * 编辑器目的地发现「它要编辑的那门课已经不在课表里」时该怎么收场 —— 这一份判定的唯一一本（T335，台账 #388）。
 *
 * ## 纯判据（本仓硬规则）
 *
 * 零 android / androidx import、不读时钟、不读权限、不读 `Build.VERSION`、不碰 `NavController` /
 * `NavBackStackEntry` / `uiState` 本身。三枚事实（数据到位了没有、这一条 entry 的目标在不在屏上、
 * 这一页自己有没有发起过删除）**全部**由调用点（`MainActivity` 编辑器目的地那一层）算好当布尔递进来，
 * 本文件一枚都不许自己去够。
 *
 * ## 病在哪
 *
 * 用户从桌面组件点一行课 → `MainActivity` 跳 `editor/{courseId}`。课表刷新把那门课删掉之后，组件上的行
 * 还会滞留一帧，于是这一跳的目标已经不存在了。基点上这一档的处理是：闸门（`MainActivity` 里
 * `if (!uiState.loading && !courseMissing)` 那一行）把整棵 `CourseEditorScreen` 摘掉，而 `popBackStack()`
 * 要等下一帧才生效 ⇒ 白一帧，然后**一句话不说**地弹回原页。用户读作「组件坏了」。
 * 当时的注释已经把这一档称作「这门课从来没读到」，但没有安排任何一句给用户的话 —— 静默是漏的，不是设计。
 *
 * ## 为什么是四档而不是「要不要念一句」那一枚 Boolean
 *
 * 调用点要按档做**两件**不同的事（退不退栈、念不念话），而这两件事在四档上两两不同：
 *
 * | 数据到位 | 目标不在屏上 | 本页发起过删除 | 档 | 动作 |
 * |---|---|---|---|---|
 * | 否 | —— | —— | [MissingCourseExitVerdict.DataNotReadyYet] | 什么都不做，等 `loading` 翻面的那一手 |
 * | 是 | 否 | —— | [MissingCourseExitVerdict.CourseIsOnScreen] | 什么都不做，页面照常组合 |
 * | 是 | 是 | 是 | [MissingCourseExitVerdict.PopWithoutMessage] | **只退，不念** |
 * | 是 | 是 | 否 | [MissingCourseExitVerdict.PopWithMessage] | **先念，后退** |
 *
 * 第三行与第四行必须分开，这是本卡唯一一处「不说」比「说」更贵的地方：`CourseEditorScreen` 的删除链在
 * **删成功**那一路页面一个字都不念（只有 `deleted == false` 才写那句红条），于是「本页自己删的」那一档
 * 如果也去念一句「已不在课表里，可能被刷新移除」，就是对着用户亲手删掉的课谎报死因 —— 同屏两句打脸那一族。
 * 折成 Boolean 就只能回答「退不退」，恰好丢掉这一格。
 *
 * ## 次序即语义（两处，本卡都靠调用点守住，形状归守卫钉）
 *
 * 1. **话排在退栈之前**，且两句之间没有挂起点 ⇒ 这一趟一定念得出去。载体是 `ScheduleViewModel` 上那枚
 *    跨配置变化存活的 `StateFlow`（`showMessage` 的写侧），**不是** composition 上的 `remember`：
 *    `popBackStack()` 之后首页重新组合，它自己那枚读消息口的协程是**新起**的，首帧就读到这句话。
 *    这也是甲档的话为什么不会被那一帧吃掉 —— 被吃掉的前提是话长在正在被摘掉的那棵子树上。
 * 2. 这一档的判定**不许**被写进协程的键里。协程的键仍是「目标 id + 数据到位没有」两枚：把
 *    「本页发起过删除」那枚旗加进键里，旗翻真的那一刻两条协程（本档与删除链那一档）会各自弹一次 ⇒
 *    连 pop 两格，用户被多退一层。基点上那行 ⚠️ 注释（「不许把它改成按 courseMissing 重启」）说的是同一件事。
 *
 * ## 本卡没收的那一半
 *
 * 白帧本身（闸门关着而退栈要等下一帧）不在本档里 —— 甲档兑的是「静默」那一半，改闸门属于另一格账。
 *
 * 判据逐格账见 `domain/editor/EditorMissingCourseExitTest`（四档 + 死档检查 + 两侧边界），
 * 接线形状见 `ui/editor/ExternalCourseGoneWiringGuardTest`。
 */

/** 「这一条编辑器 entry 的目标已经不在课表上了」这一档的四种收场。 */
enum class MissingCourseExitVerdict {
    /** 数据还没到位：一个字节都不许动，等 `uiState.loading` 翻面那一手（R5 F-41 的原意） */
    DataNotReadyYet,

    /** 课还在列表上（含 `editor/-1` 那一档新建身份）：页面照常组合，不弹也不念 */
    CourseIsOnScreen,

    /** 目标没了，而且是**这一页自己删的**：只退栈，那句话归删除链（本页成功那一路零句，是有意的） */
    PopWithoutMessage,

    /** 目标没了，而且不是这一页删的（组件/通知那一跳撞上了刷新）：先把死因念出来，再退栈 */
    PopWithMessage,
}

/** [MissingCourseExitVerdict] 的判定本体。 */
object EditorMissingCourseExit {

    /**
     * @param dataLoaded `uiState.loading` 的反面：课程列表这一趟读完了没有
     * @param courseMissing 目的地层在册的那枚 `courseMissing`（「这一条 entry 点名了一门真课程，
     *   而它不在当前列表上」）。传**已算好的那枚**，不在这里重演 `courseId >= 0 && course == null` ——
     *   那一行的闸门读的是同一个值，两处各算一遍就是两把尺
     * @param deletionRequestedByThisEntry 本页的删除链有没有立起过那枚旗（跨配置存活的那一枚）
     */
    fun verdictOf(
        dataLoaded: Boolean,
        courseMissing: Boolean,
        deletionRequestedByThisEntry: Boolean,
    ): MissingCourseExitVerdict = when {
        // 顺序要紧：数据没到位时「列表里没有」根本不是结论，只是还没读到（R5 F-41）
        !dataLoaded -> MissingCourseExitVerdict.DataNotReadyYet
        !courseMissing -> MissingCourseExitVerdict.CourseIsOnScreen
        deletionRequestedByThisEntry -> MissingCourseExitVerdict.PopWithoutMessage
        else -> MissingCourseExitVerdict.PopWithMessage
    }

    /** 这一档要不要由目的地层退栈（两档退、两档不退；本函数只是把那张表读成一个布尔） */
    fun popsBackStack(verdict: MissingCourseExitVerdict): Boolean =
        verdict == MissingCourseExitVerdict.PopWithoutMessage ||
            verdict == MissingCourseExitVerdict.PopWithMessage

    /** 这一档欠用户一句话吗（四档里只有「不是本页删的」那一档欠） */
    fun owesAMessage(verdict: MissingCourseExitVerdict): Boolean =
        verdict == MissingCourseExitVerdict.PopWithMessage
}
