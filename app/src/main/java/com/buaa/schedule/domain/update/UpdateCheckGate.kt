package com.buaa.schedule.domain.update

/**
 * 「检查更新」这一族的**准入与呈现判据**（T261；台账 #501「隐私闸门挡成静默 no-op」、
 * #367「忽略是单向门」、#368「失败与成功在屏上同形」）。
 *
 * ## 纯度口径（本仓明文规矩）
 *
 * 零 android / androidx import、**不读时钟、不读时区、不读权限、不读 `Build.VERSION`**，
 * 也不读偏好、不碰网络。六枚事实全部由调用点（`update/UpdateCheck.kt`）**算好**再当参数递进来：
 * 隐私是否已同意、这一手是自动还是手动、每日节流放不放行、上一次的**结果档**、
 * 有没有被忽略的版本号、这一次查到了什么（比当前新的版本号 / 失败原因）。
 * 「今天查没查过」这类要读钟的算术留在调用点现成的 `shouldCheckToday` 里，本文件只吃它的结论。
 *
 * ## 它补的是哪三处静默
 *
 * 1. **隐私闸门挡在 `force` 之前**：改前那一手是「未同意隐私就整段返回当时的状态」——
 *    不弹窗、副标题不变、一条日志都不留，而同一条链上的注释正承诺「失败/无更新也要出声」。
 *    本文件把隐私**降级成一枚输入**：它只决定「查不查」，绝不决定「说不说话」。
 *    于是「未同意隐私 + 手动点」得到 [UpdateCheckVerdict.BlockedByPrivacy] 并且**必须出声**，
 *    「未同意隐私 + 自动」同一档但 `announces` 为假（合规要求：没同意就不能拿弹窗打断用户）。
 *    把 `force` 挪到隐私闸之前那种"绕开用户同意"的修法第 ⓪ 问已经点名禁掉。
 * 2. **失败档只落时间戳、屏上同形**：进程一死，内存状态回 Idle，设置页那一行只剩
 *    「上次检查 X月X日」——失败与成功念的是同一句话。修法是把**结果档**落进偏好，
 *    由 [UpdateLastOutcome] 承载，[restingLine] 一处写完三种读数各说各的话。
 * 3. **「忽略此版本」是单向门**：写了偏好就再没有清除路径、界面也从不回读它。
 *    本文件给它一档自己的呈现（[UpdateCheckVerdict.SuppressedByIgnore]）：
 *    自动检查仍然不打扰，手动检查必须说出「这一版被你忽略了、去哪儿能撤销」，
 *    撤销的落点在设置页「版本」那一组里另起一行（接线在第 ③ 段）。
 *
 * ## 判据顺序（顺序即语义）
 *
 * [admit]：**先**隐私（[UpdateCheckVerdict.BlockedByPrivacy]，带 trigger 所以手动那一下能出声），
 * **再**节流（[UpdateCheckVerdict.HeldByThrottle]，带上一手结果档），**最后**才放行
 * [UpdateCheckVerdict.RunCheck]。谁排在前面决定的是「拿不到网时用户听见哪一句」，
 * 而不是「能不能出网」——出网与否两档都是不出网。
 * [present]：先失败（[UpdateCheckVerdict.CheckFailed]），再「压根没有新版本」，
 * 再「新版本正是被忽略的那一版」，最后才是可提示的新版本。
 * 「被忽略」那一条必须排在 [UpdateCheckVerdict.NewVersionFound] 之前，否则又被当成新提示弹一遍；
 * 而它排在失败之后，否则网络挂了会念成「你已经忽略了这个版本」——那句是假话。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 调用点要分三件不同的事：出不出网、弹不弹窗、那一行念哪句话。
 * 七档里两档出网后闭嘴（自动的忽略档与自动的无更新档都不该打断用户）、
 * 三档要弹窗或改副标题、一档开始转圈、一档只在日志里留痕——
 * 折成 Boolean 就把「被隐私挡下」与「查过了但没东西」念成同一句话，
 * 那正是本卡要消灭的静默。所以 [UpdateCheckVerdict] 逐档带自己的字段与句子。
 */
enum class UpdateCheckTrigger {
    /** 冷启动那次每日自动检查（`force = false`） */
    Automatic,

    /** 用户在设置页按下「检查更新」（`force = true`）：这一手永远要出声 */
    Manual,
}

/**
 * 上一次检查的**结果档**（落在偏好里，进程死了还在）。
 *
 * 改前只有「上次检查的时间戳」一枚，成功与失败在盘上不可区分 ——
 * 于是杀进程再进设置页，两句假话里用户只能听见同一句。
 * [Never] 是"这台机器上从没查过"，不能当成"1970 年查成功过一次"。
 */
enum class UpdateLastOutcome {
    Never,
    Succeeded,
    Failed,
}

/** 一档要念给用户听的话：标题给弹窗，正文给弹窗与设置页那一行共用（一处写、两处读） */
data class UpdateCopy(val title: String, val message: String)

/** [UpdateCheckGate] 的结论：七档，调用点按档分派，不许折叠 */
sealed interface UpdateCheckVerdict {

    /** 这一手是自动的还是手动的：它决定「该不该打断用户」，从不决定「要不要出网」 */
    val trigger: UpdateCheckTrigger

    /**
     * 值不值得弹窗打断用户。同一个档位名在两侧要说不同的话：
     * **自动**那一手只有「发现新版本」才出声（否则每天第一次打开都要被一个网络错误打断，
     * 而未同意隐私的用户每次冷启动都要被一句"没同意"打断）；
     * **手动**那一手除了「正在转圈」那一档，每一档都必须给反馈——
     * 这是本卡的不变量，改前正是它被整段跳过（台账 #501）。
     */
    val announces: Boolean
        get() = when (this) {
            is NewVersionFound -> true
            is BlockedByPrivacy -> trigger == UpdateCheckTrigger.Manual
            is SuppressedByIgnore -> trigger == UpdateCheckTrigger.Manual
            is NothingNewer -> trigger == UpdateCheckTrigger.Manual
            is CheckFailed -> trigger == UpdateCheckTrigger.Manual
            // 「手动那一下绝不静默」是不变量：这一档在今天的调用点上只可能由自动那一手走到
            // （每日节流里 force 永远放行，那把尺住在 shouldCheckToday，本文件不重抄第二把），
            // 真走到手动这一格就说明调用点把结论递错了 —— 宁可念一句「今天已经查过」，
            // 也不许把它折成"按了没反应"，那正是本卡第 1 处病灶的形状。
            is HeldByThrottle -> trigger == UpdateCheckTrigger.Manual
            is RunCheck -> false
        }

    /**
     * 准入通过：该出网查一次（调用点据此把状态摆成「正在检查」——那是手动那一手唯一的
     * 非说话型反馈，也是 [announces] 里唯一为假的手动档）。
     */
    data class RunCheck(
        override val trigger: UpdateCheckTrigger,
    ) : UpdateCheckVerdict

    /**
     * 被隐私闸门挡下：这一手**根本没出网**。
     *
     * 手动那一下必须念 [spoken]（改前正是这一档整段静默、零日志）；自动那一下不打断用户，
     * 但调用点仍要留一条读得到的痕迹。
     */
    data class BlockedByPrivacy(
        override val trigger: UpdateCheckTrigger,
        val spoken: UpdateCopy,
    ) : UpdateCheckVerdict

    /**
     * 被每日节流压掉：今天已经成功查过一次（今天的调用点上只有自动那一手会走到这里，
     * 手动那一手在 `shouldCheckToday` 里就放行了，本文件不重抄那把尺）。
     * [lastOutcome] 带着上一手的结果档，所以设置页那一行说的是实话而不是「一切正常」；
     * 那一行念的话由 [UpdateCheckGate.restingLine] 一处写完，本档不另存一份副本。
     */
    data class HeldByThrottle(
        override val trigger: UpdateCheckTrigger,
        val lastOutcome: UpdateLastOutcome,
    ) : UpdateCheckVerdict

    /** 查到了比当前新、也没被忽略的版本 */
    data class NewVersionFound(
        override val trigger: UpdateCheckTrigger,
        val version: String,
    ) : UpdateCheckVerdict

    /**
     * 比当前新的那一版**正是**用户忽略过的版本。
     *
     * 自动检查照旧不吵人，手动检查必须说清「这一版被你忽略了、在哪里能撤销」——
     * 忽略是有出口的，不是一锤定音。
     */
    data class SuppressedByIgnore(
        override val trigger: UpdateCheckTrigger,
        val version: String,
        val spoken: UpdateCopy,
    ) : UpdateCheckVerdict

    /** 查成功了，Gitee 上没有比当前更新的版本 */
    data class NothingNewer(
        override val trigger: UpdateCheckTrigger,
    ) : UpdateCheckVerdict

    /** 查失败了。[reason] 由调用点给出（它的措辞口径不在本卡范围内） */
    data class CheckFailed(
        override val trigger: UpdateCheckTrigger,
        val reason: String,
    ) : UpdateCheckVerdict
}

/** 检查更新的准入与呈现判据：两枚入口，输入全是算好的值。 */
object UpdateCheckGate {

    /**
     * 第一手：这一次到底出不出网。
     *
     * ⚠️ 隐私那一格是**输入**不是**门**：它不许出现在任何 `return` 之前
     * （改前那一手是「未同意就直接把当前状态原样交回」，于是设置页那颗按钮对
     * 没同意过隐私的用户是彻底的 no-op）。结论一律带 `trigger`，
     * 「该不该出声」由档位自己说。
     */
    fun admit(
        privacyAccepted: Boolean,
        trigger: UpdateCheckTrigger,
        throttleAllowsCheck: Boolean,
        lastOutcome: UpdateLastOutcome,
    ): UpdateCheckVerdict = when {
        !privacyAccepted ->
            UpdateCheckVerdict.BlockedByPrivacy(trigger, privacyBlockedCopy())

        !throttleAllowsCheck ->
            UpdateCheckVerdict.HeldByThrottle(trigger, lastOutcome)

        else ->
            UpdateCheckVerdict.RunCheck(trigger)
    }

    /**
     * 第二手：查完（或查挂）之后这一档怎么呈现。
     *
     * [newestAboveInstalled] 是调用点已经算好的「比已装版本更新的最大版本号，没有则 null」，
     * **不带**忽略过滤 —— 忽略过哪一个由 [ignoredVersion] 单独递进来，
     * 这样「被忽略」才有自己的那一档，而不是在选版本时就被静默吃掉。
     */
    fun present(
        trigger: UpdateCheckTrigger,
        ignoredVersion: String?,
        newestAboveInstalled: String?,
        failureReason: String?,
    ): UpdateCheckVerdict = when {
        failureReason != null ->
            UpdateCheckVerdict.CheckFailed(trigger, failureReason)

        newestAboveInstalled == null ->
            UpdateCheckVerdict.NothingNewer(trigger)

        newestAboveInstalled == ignoredVersion ->
            UpdateCheckVerdict.SuppressedByIgnore(
                trigger,
                newestAboveInstalled,
                ignoreSuppressedCopy(newestAboveInstalled),
            )

        else ->
            UpdateCheckVerdict.NewVersionFound(trigger, newestAboveInstalled)
    }

    /** 「未同意隐私」那一档的话。同意入口在首启引导，这里不许承诺一个盘面上没有的按钮 */
    fun privacyBlockedCopy(): UpdateCopy = UpdateCopy(
        title = "没有检查更新",
        message = "还没有同意隐私说明，检查更新不会发起任何网络请求。" +
            "可在「设置 → 数据与隐私」里查看说明，同意那一格在首启引导页。",
    )

    /** 「新版本正是被忽略的那一版」那一档的话：撤销落点要点名，否则这扇门还是单向的 */
    fun ignoreSuppressedCopy(version: String): UpdateCopy = UpdateCopy(
        title = "这个版本已被忽略",
        message = "v$version 已被你设为不再提示。想重新收到提醒，" +
            "到「设置 → 版本」里点「恢复更新提醒」那一行就行。",
    )

    /**
     * 没在检查时（进程刚起来、状态还是 Idle）设置页那一行念的话。
     *
     * 三档各说各的：改前只有「上次检查 + 时间戳」一种读数，失败与成功同形。
     * [lastCheckLabel] 由调用点格式化好（时区与日期格式都不属于判据）。
     */
    fun restingLine(outcome: UpdateLastOutcome, lastCheckLabel: String): String = when (outcome) {
        UpdateLastOutcome.Never -> "每天第一次打开自动检查 · 上次检查 $lastCheckLabel"
        UpdateLastOutcome.Succeeded -> "每天第一次打开自动检查 · 上次检查成功 $lastCheckLabel"
        UpdateLastOutcome.Failed -> "上次检查失败，没有获得新版本 · $lastCheckLabel（点这里可直接重试）"
    }
}
