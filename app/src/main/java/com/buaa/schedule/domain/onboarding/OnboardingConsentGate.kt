package com.buaa.schedule.domain.onboarding

/**
 * 首启引导「同意隐私」这一位的**路径判据**（T271，台账 #410）。
 *
 * 纯判据：零 android / androidx import、**不读时钟、不读时区、不读权限、不读 `Build.VERSION`**、
 * 不读偏好、不碰网络。五枚事实（用户刚走的是哪一手、那一屏的勾选框勾没勾、盘上同意位在不在、
 * 引导那棵子树还进不进得去、这一屏要不要同意）全部由调用点
 * （`ui/onboarding/OnboardingScreen.kt` 与 `ui/settings/SettingsScreen.kt`）**算好**再当参数递进来。
 *
 * ## 病在哪（四处出口只有一处带同意）
 *
 * 引导里落盘「引导已完成」的那一手有四枚，全仓落盘「隐私已同意」的那一手只有一枚，
 * 而且那枚排在**流程中间**（隐私步的「下一步」）。顶栏「先跳过，稍后设置」从每一步都点得到，
 * 它只写「引导已完成」—— 于是从那一手出去的用户：
 *
 * 1. 引导那棵子树此后永不组合（`startDestination` 拿的是盘上那枚 `onboarding_completed`，
 *    T244 收过的那两手在册白名单与兜底，是钉子不是洞）；
 * 2. 全仓唯一的同意写法只剩引导里那一处，那处从此不可达；
 * 3. 依赖同意的功能（本机唯一的后台联网项 = 每日更新检查）从此静默不可用；
 * 4. 而屏上还写着「同意那一格在首启引导页」—— 指向一页他再也进不去的页面。
 *
 * 另有同族的一半：勾选框的状态住在 `remember` 里，**勾了再点跳过**时屏上正亮着
 * 「我已阅读并同意」，盘上却从未有过这一位 —— 屏与盘各说各话。
 *
 * ## 修法（第 ⓪ 问裁的是丁档：乙＋丙）
 *[OnboardingExitDisposition.writesConsent] 只在
 *   「用户当面勾了那一格」时为真；跳过而没勾 ⇒ 同意位不动，这是判据不是疏忽。
 * - **乙**：同意入口搬到常驻页（设置 → 数据与隐私 的确认位）。引导进不去之后，
 *   路径判据交回的就是那一屏：[ConsentPath.settingsEntry]，三下走到。
 *   [consentPathAt] 的入参 `permanentEntryAvailable` 就是这一手的在册旗，
 *   调用点把「常驻页没有同意那一手」递进来 ⇒ 本内核交回 [ConsentPathSevered]，
 *   那一档就是改前的形状，留在判据里是为了让它可被证伪（第 ⑤ 段的变异臂）。
 * - **丙**：未同意而这一屏又要同意时，[OnboardingConsentGate.screenNotice] 交回持续提示档
 *   （红点 + 那一句），直到用户处理；已同意的用户走 [ConsentNoticeSilent]，
 *   不许把他再拖回提示里。
 *
 * ## 第 ⓪ 问的第二半（T280 重裁）：弹窗正文那几句「当前」的话从哪儿取
 *
 * 三档候选里裁的是**甲**：常驻页那层弹窗的每一句同意状态话都只由 **payload 交回来的那一位值**
 * （加上「用户在本屏按下的那一手」这一枚事实）决定，一次算好、组合期内不再回读。
 * 判据来自 `CalendarSyncTargetPairingGuardTest` 自己的两句话：
 * 「正文里读 payload 交回的那枚 `consentAt > 0L` 判据不再是 2 处（:1830 那行文案、:1844 那颗
 * 「撤回同意」按钮的可见性）」与「给 `privacyConsentAt` 添第二枚读者，收场那几帧就当场念「未同意」」——
 * 也就是**一枚读者、而且那枚读者必须是 payload 那一行本身**。
 *
 * - **乙（读那枚活字段）被否**：T271③ 就是走的乙（`privacyConsentAt = FirstRun.privacyConsentAt(context)`
 *   与 `remember(privacyConsentAt) { screenNotice(...) }`），当场把 ⑥ 那两把尺顶成 8/7 并给弹窗体内
 *   添了第二枚读者 —— 那正是本守卫拦下的东西，不是可以重钉上去的 collateral damage。
 * - **丙（正文吃 payload、只在现有那一枚读者站点里读活字段）裁成生产形状**：活字段的读者全仓只留
 *   payload 那一行；行上那句持续提示改吃**算好的布尔**（[screenNotice] 的 `privacyAccepted` 入参，
 *   由 `FirstRun.privacyAccepted(context)` 现读，不经过那枚成对字段），重算时机由「本屏那一手」给。
 *   弹窗正文那一侧则一律走 [residentDialogCopy]：入参是 payload 交回的值与用户那一手，
 *   **不读活字段、也不读行上那句提示**（读了就会在收场动画那几帧里当场翻面）。
 *
 * 四处出口的适用面：`ui/onboarding/OnboardingScreen.kt` 那五手吃 `gestureDisposition`（落盘侧，
 * 与正文无关）；`ui/settings/SettingsScreen.kt` 这一屏同时吃 `screenNotice`（行上）与
 * `residentDialogCopy`（弹窗正文），第 ⓪ 问这一半裁的就是它；`ui/importing/ImportScreen.kt` 与
 * `MainActivity.kt` 上没有同意这一手也没有「当前」那一句（它们只吃在册白名单与兜底），
 * 所以这一半的判据不落在那两屏 —— 它们的病灶在 `UpdateCheckGate.privacyBlockedCopy` 那一句指向里，
 * 那一格本卡不收（见回执「未交付」）。
 *
 * ## 判据顺序（顺序即语义）
 *
 * [gestureDisposition]：先看**这一手是不是用户当面勾了同意**（[ConsentFromTick]），
 * 再看盘上是不是已经有了（[NothingToSay]），再看这一手属不属于「只能出现在隐私步之后」的那三枚
 * （[FlowOrderContradiction]），最后才按「同意入口还在不在常驻页」分
 * [PathToPermanentEntry] / [PathSevered]；隐私步没勾那一手单独一档（[ConsentNotGivenOnPrivacyStep]），
 * 因为今天的界面用 `nextEnabled` 挡着它，走到这里只能是调用点把结论递错了。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 调用点要做三件不同的事：落不落同意位、落不落引导完成位、出去之后念哪一句并指哪儿。
 * 折成 Boolean 就把「屏上已经勾了」与「屏上还没勾」念成同一句话 —— 前者要落盘（那是用户的手），
 * 后者绝不能落盘；而「同意入口在常驻页」与「同意入口全没了」在 Boolean 上更是同一个值，
 * 那正是本卡第 4 处病灶（那句指向不可达页面的承诺）。所以每一档带自己的两枚位与自己的句子。
 */

/**
 * 引导里用户走得出的那一手。
 *
 * 五枚里四枚落「引导已完成」（[writesOnboardingDone]），只有一枚是同意那一手 ——
 * 枚数差本身不是缺陷（引导完成与同意是两扇不同的门，见 `core/FirstRun.kt` 的文件头），
 * 缺陷是**四枚落完成的手里没有任何一枚把同意带到盘上，也没留下一条还能同意的路**。
 * 所以每一枚都得自己说清 [reachesPastConsentStep]：能不能在不经过隐私步的情况下走到。
 */
enum class OnboardingGesture(
    /** 这一手会不会把「引导已完成」写进偏好（顶栏跳过 = 会） */
    val writesOnboardingDone: Boolean,
    /** 这一手在今天的流程里是否只可能出现在隐私步**之后**（跳过那一手不满足：每一步都在屏上） */
    val reachesPastConsentStep: Boolean,
) {
    /** 顶栏「先跳过，稍后设置」：从每一步都点得到，是全仓唯一一枚能把引导焊死又不带同意的出口 */
    HeaderSkip(writesOnboardingDone = true, reachesPastConsentStep = false),

    /** 隐私步的「下一步」：改前后全仓唯一的同意写法，不落「引导已完成」 */
    PrivacyStepNext(writesOnboardingDone = false, reachesPastConsentStep = true),

    /** 导入步点「导入你的课表」：落点 import */
    ImportLanding(writesOnboardingDone = true, reachesPastConsentStep = true),

    /** 导入步点「手动添加课程」：落点 editor/-1 */
    ManualAddLanding(writesOnboardingDone = true, reachesPastConsentStep = true),

    /** 导入步点「下一步」= 现在不导入，落回首页 */
    LastStepDone(writesOnboardingDone = true, reachesPastConsentStep = true),
}

/** 一档要念给用户听的话：标题给屏上，正文给屏上与留痕共用（一处写、两处读） */
data class ConsentCopy(val title: String, val message: String)

/**
 * 把一档话拼成屏上那一句（行上的持续提示与常驻页弹窗正文共用同一句形状，
 * 免得两处各写一遍 `"$title：$message"` —— 同句多写是本仓在册的那把尺拦的）。
 */
fun ConsentCopy.spokenLine(): String = "$title：$message"

/**
 * 一条**走得通**的同意路：在哪一屏、点哪一手、从当前页起算要几下。
 *
 * [tapsFromFrontPage] 是路径形状算出来的常数而不是拍的数：
 * 引导里那一屏只需「勾一下 + 下一步一下」；出引导之后要「底栏设置一下 + 行一下 + 弹窗按钮一下」，
 * 而这一屏已经在设置页上时少一下。第 ⓪ 问要的判据形状就是这一枚字段。
 */
data class ConsentPath(
    val screen: String,
    val hand: String,
    val tapsFromFrontPage: Int,
) {
    companion object {

        /** 引导还进得去时的那条路：隐私步勾一下、下一步一下 */
        val onboardingEntry = ConsentPath(
            screen = "首启引导 · 隐私与数据那一屏",
            hand = "勾「我已阅读并同意」，再点「下一步」",
            tapsFromFrontPage = 2,
        )

        /** 引导已经关门时的那条路：常驻页的那一格（乙档的落点） */
        val settingsEntry = ConsentPath(
            screen = "设置 → 数据与隐私",
            hand = "点「同意隐私说明」那一格",
            tapsFromFrontPage = 3,
        )

        /** 人已经站在设置页上：底栏那一下省了 */
        val settingsEntryFromThisScreen = ConsentPath(
            screen = "这一屏的「数据与隐私」行",
            hand = "点行里的「同意隐私说明」",
            tapsFromFrontPage = 2,
        )
    }
}

/**
 * 用户刚走的那一手该怎么落盘、出去之后念哪一句（[OnboardingConsentGate.gestureDisposition] 的结论）。
 *
 * 两枚落盘位由档位自己说，调用点不许再猜：
 * [writesConsent] 只在**用户当面勾了那一格**时为真，[writesOnboardingDone] 按 [OnboardingGesture] 的形状。
 */
sealed interface OnboardingExitDisposition {

    val gesture: OnboardingGesture

    /**
     * 这一手要不要落同意位。**判据只有一条**：用户在屏上当面勾了「我已阅读并同意」。
     * 内核永不为「用户没勾」补真，也永不替首启引导置真。
     */
    val writesConsent: Boolean
        get() = this is ConsentFromTick

    /** 这一手要不要落「引导已完成」：隐私步没勾那一手不落（它压根还没出隐私步），其余按形状落 */
    val writesOnboardingDone: Boolean
        get() = this !is ConsentNotGivenOnPrivacyStep && gesture.writesOnboardingDone

    /** 出去之后要说的那一句；[NothingToSay] 那档没话要说 */
    val spoken: ConsentCopy?
        get() = when (this) {
            is NothingToSay -> null
            is ConsentFromTick -> copy
            is PathToPermanentEntry -> copy
            is PathSevered -> copy
            is FlowOrderContradiction -> copy
            is ConsentNotGivenOnPrivacyStep -> copy
        }

    /** 用户此后要经过的同意路径；null = 这一手之后盘上已经有同意了，或压根没有路可走（见 [consentStillReachable]） */
    val path: ConsentPath?
        get() = when (this) {
            is PathToPermanentEntry -> entry
            is FlowOrderContradiction -> entry
            else -> null
        }

    /**
     * 这一手之后，同意这件事还回得来吗 —— 第 ④ 段守卫钉的就是这一枚。
     *
     * [PathSevered] 与「路是 null 的矛盾档」都算回不来；已经同意过的、以及这一手自己刚把
     * 用户的勾选落盘的，都算回得来（后者根本不需要再走一次）。
     */
    val consentStillReachable: Boolean
        get() = when {
            this is PathSevered -> false
            this is FlowOrderContradiction -> entry != null
            else -> true
        }

    /** 用户当面勾了那一格：把**他的手**落盘，屏与盘从此同一句话 */
    data class ConsentFromTick(
        override val gesture: OnboardingGesture,
        val copy: ConsentCopy,
    ) : OnboardingExitDisposition

    /** 盘上已经有同意了（或这一手自己刚把它写上）：只落「引导已完成」，不多嘴 */
    data class NothingToSay(
        override val gesture: OnboardingGesture,
    ) : OnboardingExitDisposition

    /** 这一手不落同意，但常驻页上还留着同意那一格：交回那条路（几步、哪一屏、念哪句） */
    data class PathToPermanentEntry(
        override val gesture: OnboardingGesture,
        val entry: ConsentPath,
        val copy: ConsentCopy,
    ) : OnboardingExitDisposition

    /**
     * 这一手不落同意，而引导那扇门已经永久关上、常驻页又没有同意那一格。
     *
     * 这就是改前的形状。它留在判据里不是给生产留的岔路，而是让「把常驻页那一格删掉」
     * 这一手在测试里必须亮红（第 ⑤ 段 W 臂）；调用点走到这里只能留一条告警，不许静默。
     */
    data class PathSevered(
        override val gesture: OnboardingGesture,
        val copy: ConsentCopy,
    ) : OnboardingExitDisposition

    /**
     * 三枚「结构上只可能出现在隐私步之后」的出口，盘上却没有同意记录。
     *
     * 今天走不到（隐私步的 `nextEnabled` 挡着），真走到就说明流程顺序被改坏、
     * 或同意被撤回过而引导没跟着重开 —— 落点照旧，但这条不许是静默的。
     */
    data class FlowOrderContradiction(
        override val gesture: OnboardingGesture,
        val entry: ConsentPath?,
        val copy: ConsentCopy,
    ) : OnboardingExitDisposition

    /** 隐私步没勾就点下一步：今天的界面挡着它，走到这里说明调用点递错了结论 */
    data class ConsentNotGivenOnPrivacyStep(
        override val gesture: OnboardingGesture,
        val copy: ConsentCopy,
    ) : OnboardingExitDisposition
}

/**
 * 某一屏对「没同意」这件事该怎么表态（[OnboardingConsentGate.screenNotice] 的结论）。
 *
 * 持续提示档：只要用户没处理，[ConsentNoticeToHandle] 的每一帧都要被画出来（红点 + 那一句）。
 * 已同意的用户与不需要同意的屏走 [ConsentNoticeSilent] —— 把已经同意过的人再拖回提示里，
 * 与替他置真一样是错的（第 ② 段朝窄那一侧的格子）。
 */
sealed interface ConsentScreenNotice {

    /** 要不要挂那枚持续可见的标记（红点）：只有「没同意且这一屏要同意」才挂 */
    val showsMark: Boolean
        get() = this is ConsentNoticeToHandle

    /** 屏上念的那一句；[ConsentNoticeSilent] 那档没话要说 */
    val line: ConsentCopy?
        get() = when (this) {
            is ConsentNoticeSilent -> null
            is ConsentNoticeToHandle -> copy
        }

    /** 不该出现提示：这一屏不需要同意，或用户已经同意过了 */
    data object ConsentNoticeSilent : ConsentScreenNotice

    /**
     * 未同意而这一屏依赖同意：指一条**走得通**的路，直到用户处理。
     * [path] 为 null 就是全仓没有同意入口了 —— 那句话必须说实话，不许再指一页进不去的屏。
     */
    data class ConsentNoticeToHandle(
        val path: ConsentPath?,
        val copy: ConsentCopy,
    ) : ConsentScreenNotice
}

/** 首启引导的同意路径判据：两枚入口，输入全是算好的值。 */
object OnboardingConsentGate {

    /**
     * 第一手：用户刚走的这一手该怎么落盘、出去之后念哪一句。
     *
     * ⚠️ [privacyBoxTick] 是**用户的手**，不是本内核的判断：真只可能来自「我已阅读并同意」
     * 那一格的勾选。把 `writesConsent` 写成常量真，就是替一整批用户同意 —— 本卡的禁令。
     */
    fun gestureDisposition(
        gesture: OnboardingGesture,
        privacyBoxTick: Boolean,
        privacyAccepted: Boolean,
        onboardingCompleted: Boolean,
        permanentEntryAvailable: Boolean,
    ): OnboardingExitDisposition {
        // 1. 当面勾了：这一手带同意，与「屏上写着已同意」对齐（跳过那一手也吃这一条）
        if (privacyBoxTick && !privacyAccepted && gesture.takesTickIntoDisk()) {
            return OnboardingExitDisposition.ConsentFromTick(gesture, consentGivenCopy())
        }
        // 2. 盘上已经有了：两本账一致，没什么可说的
        if (privacyAccepted) return OnboardingExitDisposition.NothingToSay(gesture)
        // 3. 隐私步没勾：不落任何位，也不许把引导判成完成
        if (gesture == OnboardingGesture.PrivacyStepNext) {
            return OnboardingExitDisposition.ConsentNotGivenOnPrivacyStep(gesture, consentMissingCopy())
        }
        // 这一手之外还有一手要落「引导已完成」，所以判断「引导还进得去吗」吃的是**这一手之后**的那一位：
        // 传进来的若是落盘前的 false，跳过那一手就会被判成「路还在引导里」——那是本卡第 4 处病灶的另一半
        val onboardingDoneAfterThisHand = onboardingCompleted || gesture.writesOnboardingDone
        val notice = screenNotice(
            screenNeedsConsent = true,
            privacyAccepted = false,
            onboardingCompleted = onboardingDoneAfterThisHand,
            permanentEntryAvailable = permanentEntryAvailable,
        )
        // 4. 过了隐私步的那三枚却在盘上没同意：路径照交，但这一档不许静默
        if (gesture.reachesPastConsentStep) {
            return OnboardingExitDisposition.FlowOrderContradiction(
                gesture,
                notice.pathOrNull(),
                flowOrderContradictionCopy(notice),
            )
        }
        // 5. 跳过而没勾：同意位不动（那是用户没同意，不是我们漏写），交回剩下的那条路
        return if (notice is ConsentScreenNotice.ConsentNoticeToHandle && notice.path != null) {
            OnboardingExitDisposition.PathToPermanentEntry(gesture, notice.path, skipWithEntryCopy(notice.path))
        } else {
            OnboardingExitDisposition.PathSevered(gesture, severedPathCopy())
        }
    }

    /**
     * 第二手：一屏对「没同意」这件事的表态。
     *
     * 顺序要紧：`!screenNeedsConsent` 排在最前（本机零出网的屏不许挂红点），
     * 已同意排第二（不许把处理完的用户继续拖着念），第三才是「引导还进得去吗」——
     * 它决定指哪一屏，也就决定那句话是真话还是假话。
     */
    fun screenNotice(
        screenNeedsConsent: Boolean,
        privacyAccepted: Boolean,
        onboardingCompleted: Boolean,
        permanentEntryAvailable: Boolean,
    ): ConsentScreenNotice = when {
        !screenNeedsConsent -> ConsentScreenNotice.ConsentNoticeSilent
        privacyAccepted -> ConsentScreenNotice.ConsentNoticeSilent
        // 引导那棵子树还组合得上：路的起点还在原地，指它说的是真话
        !onboardingCompleted -> ConsentScreenNotice.ConsentNoticeToHandle(
            ConsentPath.onboardingEntry,
            nudgeWhileOnboardingCopy(ConsentPath.onboardingEntry),
        )
        // 引导已经永久关门：只能指常驻页那一格；那一格不存在就是死路，念一句实话
        permanentEntryAvailable -> ConsentScreenNotice.ConsentNoticeToHandle(
            ConsentPath.settingsEntry,
            nudgeWithPermanentEntryCopy(ConsentPath.settingsEntry),
        )

        else -> ConsentScreenNotice.ConsentNoticeToHandle(null, severedPathCopy())
    }

    /** 「勾了再点跳过」落的就是用户这一格的手：话说清楚落盘之后放行了什么、去哪儿能撤回 */
    fun consentGivenCopy(): ConsentCopy = ConsentCopy(
        title = "已记下你的同意",
        message = "本机唯一的后台联网项（每天第一次打开时的一次更新检查）从下一次启动起才会发起请求。" +
            "随时能在「设置 → 数据与隐私」里撤回。",
    )

    /** 屏上正勾着、盘上却从没同意过 —— 改前那一格的真话现在由跳过那一手补齐 */
    fun consentMissingCopy(): ConsentCopy = ConsentCopy(
        title = "还没有同意",
        message = "勾「我已阅读并同意」才走得了下一步；不勾就还停在这一屏。",
    )

    /** 跳过而没勾，但常驻页还留着同意那一格：把路一步一屏念清楚 */
    fun skipWithEntryCopy(entry: ConsentPath): ConsentCopy = ConsentCopy(
        title = "先跳过，稍后设置",
        message = "还没有同意隐私说明，检查更新不会发起任何网络请求。想同意，${entry.tapsFromFrontPage} 下就够：" +
            "${entry.screen} · ${entry.hand}。",
    )

    /** 引导已关门而同意入口一个不剩：改前的形状，只能说破，不许再指一页进不去的屏 */
    fun severedPathCopy(): ConsentCopy = ConsentCopy(
        title = "没有同意入口了",
        message = "还没有同意隐私说明，检查更新不会发起任何网络请求；而首启引导已经关闭、" +
            "常驻页上也没有「同意」那一手 —— 这条缺口要把同意入口补到常驻页，不能继续指首启引导页。",
    )

    /** 流程顺序与盘上记录对不上：照旧交路，但这一档必须留痕 */
    fun flowOrderContradictionCopy(notice: ConsentScreenNotice): ConsentCopy = ConsentCopy(
        title = "盘上没有同意记录",
        message = "这一手走到了隐私步之后，偏好里却查不到同意位（流程顺序被改坏，或同意被撤回过）。" +
            "落点照常，重新同意的入口在 ${notice.spokenTarget()}。",
    )

    /** 引导还没结束时，那一屏对「没同意」念的话：句里写清的正是 [entry] 那一屏那一手 */
    fun nudgeWhileOnboardingCopy(entry: ConsentPath): ConsentCopy = ConsentCopy(
        title = "还没同意隐私说明",
        message = "检查更新不会发起任何网络请求。${entry.hand}就放行（${entry.screen}）。",
    )

    /** 丙档：出引导之后仍持续可见的那一句（红点 + 这行字），直到用户处理 */
    fun nudgeWithPermanentEntryCopy(entry: ConsentPath): ConsentCopy = ConsentCopy(
        title = "未同意隐私说明",
        message = "${entry.tapsFromFrontPage} 下就能同意：${entry.screen} · ${entry.hand}。" +
            "在同意之前，检查更新不会发起任何网络请求。",
    )

    /**
     * 常驻页那枚弹窗正文的档位（T280①，第 ⓪ 问裁的甲）：**只**由 payload 交回来的那一位值
     * 与「用户在本屏按下的那一手」决定。
     *
     * 三枚入参全是调用点算好的值：[consentStamp] 是 `ModalTransition` 的 payload 在开窗那一刻交回的
     * 同意时间（不是那枚会翻面的活字段），[consentAtLabel] 是同一位值格式化之后的那一句
     * （格式化住在 UI 侧 —— 本内核不读钟），[handLandedOnThisScreen] 是用户在这一屏已经按下「同意」
     * 那一手的事实。为什么把档位判断搬回内核而不许正文去读活字段：读了就会在收场动画那几帧里当场翻面，
     * 而「一枚读者、且那枚读者就是 payload 那一行」正是 `CalendarSyncTargetPairingGuardTest` ⑥
     * 那一族钉死的配对不变量（多一枚读者 = 部分清掉的同意时间会被当有效念出来）。
     */
    fun residentDialogCopy(
        consentStamp: Long,
        consentAtLabel: String,
        handLandedOnThisScreen: Boolean,
    ): ConsentCopy = when {
        consentStamp > 0L -> alreadyAgreedInsideDialogCopy(consentAtLabel)
        handLandedOnThisScreen -> consentHandedInsideDialogCopy()
        else -> entryInsideDialogCopy()
    }

    /** payload 说有过同意：那一格的日期由调用点从同一位值算好递进来，本档不读钟也不读活字段 */
    private fun alreadyAgreedInsideDialogCopy(consentAtLabel: String): ConsentCopy = ConsentCopy(
        title = "当前",
        message = "已同意（$consentAtLabel）。撤回后从下次启动起重新进入首启引导。",
    )

    /**
     * 盘上已经有同意了而 payload 交回的是 0 —— 只有「用户刚刚在本屏按下同意」这一手能走到这一档：
     * 那枚成对字段本卡不许被第二次写（写了就多一枚读者），于是这一档说的是**那一手**而不是那位时间。
     */
    private fun consentHandedInsideDialogCopy(): ConsentCopy = ConsentCopy(
        title = "当前",
        message = "已记下你在这一屏按下的那一手：检查更新从下一次启动起才会出网，" +
            "撤回就在下面那一格。",
    )

    /**
     * 常驻页那枚弹窗正文的话（T271②/③ 未交回的那一手，T280 补完）：入口就在这颗弹窗里，
     * 那颗按钮就在下面 —— 所以这一句**不指路**，也不提任何别的屏。正因如此它可以完全由
     * payload 交回来的那一位决定，不需要读那枚活字段（读了就会在收场动画那几帧里当场翻面）。
     */
    fun entryInsideDialogCopy(): ConsentCopy = ConsentCopy(
        title = "还没同意",
        message = "检查更新不会发起任何网络请求。按下下面那格「同意隐私说明」即算同意，" +
            "那唯一一次更新检查从下一次启动起才出网。",
    )

    /** 只有这两枚出口能把「用户当面勾了」这一格带走：三枚导入步的出口屏上没有那一枚勾选框 */
    private fun OnboardingGesture.takesTickIntoDisk(): Boolean =
        this == OnboardingGesture.PrivacyStepNext || this == OnboardingGesture.HeaderSkip

    /** 提示档里那条走得通的路；死路档与静默档交回 null，由调用点决定念哪句 —— 不许兜底成一条不存在的路 */
    private fun ConsentScreenNotice.pathOrNull(): ConsentPath? =
        (this as? ConsentScreenNotice.ConsentNoticeToHandle)?.path

    private fun ConsentScreenNotice.spokenTarget(): String = when (this) {
        is ConsentScreenNotice.ConsentNoticeToHandle -> path?.let { "${it.screen} · ${it.hand}" }
            ?: "哪儿都没有（同意入口全丢了）"
        ConsentScreenNotice.ConsentNoticeSilent -> "哪儿都没有（同意入口全丢了）"
    }
}
