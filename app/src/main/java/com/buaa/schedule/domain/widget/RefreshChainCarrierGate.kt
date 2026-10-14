package com.buaa.schedule.domain.widget

/**
 * 一次「时间／跨天」广播的读载体该不该在链的头顶造（T366①，台账 #218 ＋ 在册 #536 F2 同族）。
 *
 * 纯判据：零 android / androidx import、**不读时钟、不读时区、不读权限、不读 `Build.VERSION`**，
 * 也不接 `Context` —— 三枚事实（这一趟会不会走到提醒那条链、会不会走到明日预告、
 * 会不会走到组件全量刷新）全部由调用点（`widget/WidgetRefreshReceiver.kt`）当参数递进来，
 * 本文件里一个都不许自己去取（本仓明文口径，同 [WidgetRolloverGate]）。
 *
 * ## 病在哪（#218 的那笔账）
 *
 * 一条后台链上的各步原先各自回库查同一批四张表（学期行、该学期的展示口径课程、节次表、
 * 提醒册子）。调用点那一趟改前实数（逐路账在 T366 的 ⓪ commit 里）：
 * 改时间那一趟满档读学期行 4 遍、节次表 4 遍、展示口径的课程 3+K 遍、提醒册子 4 遍。
 * 而这些步骤之间没有任何写那四张表的动作（调用点里唯一那手写入是跨天配额的 CAS，
 * 写的是一枚进程内 `AtomicLong`），所以后面那几遍读到的必然是同一份内容 —— 白付的往返。
 * 收法与本仓已经写下的那条线一致：**把结论当参数传下去，而不是缓存起来**
 * （载体寿命 = 一次链，见 [com.buaa.schedule.domain.schedule.ChainReadSnapshot] 的类注释）。
 *
 * ## 为什么这里要一枚判据，而不是无条件造
 *
 * 另一条链（开机那一条，`reminder/BootReceiver`）第一步就是「重排提醒 + 课堂铃兜底」，
 * 它必吃提醒册子那一格，所以那条链头顶无条件取一次，每一格都有消费者。
 * 这一条链不恒有：**纯零点那一趟唯一的消费者是组件全量刷新那一步**，而它只需要
 * 学期行／节次表／课程那三格 —— 那三格它自己那一趟就读得完（快照排产按 key 走，
 * 见 [com.buaa.schedule.widget.WidgetDataSynchronizer.sync] 的回退式三行），
 * 载体多装的那一格（提醒册子）在这一条路上一个消费者都没有。
 * 于是无条件取 = 每天必跑的那一上午多付一趟查询、别的格一分不省：**取一次反而更差**。
 * 这一档就是 [RefreshChainCarrierPlan.SkipOnlyWidgetConsumer]，它挡在链的头顶，
 * 而零消费者那一档（今天被调用点外面那道动作判断拦着，走不到）单独留着 ——
 * 哪天动作面放宽，那一趟也不许白读四张表。
 */
enum class RefreshChainCarrierPlan {

    /**
     * 链的头顶取一次，交给这一趟真跑到的各步共用。
     *
     * 走到这里只有两种形状：提醒那条链在本趟（它体内本来就是「重排提醒」与「课堂铃兜底」
     * 两步读同一批表），或两枚以上步骤同读一批表。取到之后取不到（读库抛）就当没有，
     * 各步照旧自己读 —— 宁可多读一趟，不许少读。
     */
    Build,

    /**
     * 本趟唯一的消费者是组件全量刷新那一步：载体要省的那三格它自己一趟就读得完，
     * 而它多带的那格没人吃 ⇒ 不取（净增一读的形状，逐格账见本文件类注释）。
     */
    SkipOnlyWidgetConsumer,

    /** 本趟一步都不吃载体 ⇒ 一趟都不许花（今天不可达，调用点外面那道动作判断先拦掉了） */
    SkipNoCarrierConsumer,
}

/** [RefreshChainCarrierPlan] 的入口：把三枚已经算好的事实折成一档结论。 */
object RefreshChainCarrierGate {

    /**
     * @param hasReminderChainStep 本趟会不会走到「重排提醒 + 课堂铃兜底」那一步
     *   （它自己就含两枚吃载体的步骤，而且吃的是提醒册子那一格）
     * @param hasPreviewStep 本趟会不会走到「明日预告对齐」那一步
     * @param hasWidgetRefreshStep 本趟会不会走到「组件全量刷新」那一步
     */
    fun decide(
        hasReminderChainStep: Boolean,
        hasPreviewStep: Boolean,
        hasWidgetRefreshStep: Boolean,
    ): RefreshChainCarrierPlan {
        val consumers = listOf(hasReminderChainStep, hasPreviewStep, hasWidgetRefreshStep).count { it }
        return when {
            consumers == 0 -> RefreshChainCarrierPlan.SkipNoCarrierConsumer

            // 只剩组件那一枚消费者：它要的三格自己读得完，第四格零消费者
            consumers == 1 && hasWidgetRefreshStep -> RefreshChainCarrierPlan.SkipOnlyWidgetConsumer

            else -> RefreshChainCarrierPlan.Build
        }
    }
}
