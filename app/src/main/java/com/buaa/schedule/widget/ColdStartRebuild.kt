package com.buaa.schedule.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.buaa.schedule.BuildConfig
import com.buaa.schedule.reminder.ClassProgressReceiver
import com.buaa.schedule.reminder.ClassProgressScheduler
import com.buaa.schedule.reminder.ReminderScheduler

/**
 * [ColdStartRebuild.run] 两个留痕口的默认值。
 *
 * 写成顶层属性而不是签名里的 lambda：本仓库的源码形状守卫按"函数体第一个 `{`"切函数体，
 * 默认值写成 `{ label, error -> ... }` 它会切到默认值上去（同 `BackgroundSync.NO_STEP_FAILURE`
 * 与 `LOG_COLD_START_STEP_FAILURE` 的那条理由）。
 */
private val LOG_REBUILD_STEP_FAILURE: (label: String, error: Throwable) -> Unit = { label, error ->
    Log.w("ColdStartRebuild", "后台链路初始化失败：$label", error)
}

private val LOG_REBUILD_DECISION: (ColdStartRebuild.Decision) -> Unit = { decision ->
    Log.d("ColdStartRebuild", "冷启动后台重建：${decision.reason}")
}

/**
 * 冷启动那条后台重建链的"三把钥匙"闸门（审计 §2.1 P1-①剩余 + §2.2 P1-②）。
 *
 * ## 要修的是什么
 *
 * [BUAAApplication.onCreate] 里的重建整链此前在**每一次冷进程启动**时无条件跑全套
 * （约 `7 + 2N` 次 Room 查询、多次跨 binder 探测、N 份全量课程 JSON 编码、两条闹钟重排），
 * 而绝大多数冷启动是"某条闹钟把进程从零拉起来投递广播"，那一刻课表一个字都没变。
 * 现在改成：**三把钥匙都说"无事可做"时才跳过，任何一把不确定就照旧全跑**。
 *
 * ## 三把钥匙（AND 关系；下面 [decide] 里的顺序就是它们的短路顺序）
 *
 * 1. **versionCode 变了** —— 与上次"整链成功跑完"时记下的那个不同就必须重跑。
 *    这才是 `BUAAApplication.kt:33` 那句注释（"升级会清掉已注册闹钟"）原本写的设计意图：
 *    意图是"启动/升级"，改动前的实现是"每次进程创建"，这里把实现拉回意图。
 * 2. **我们自己的闹钟确实还挂着** —— 用 `PendingIntent.FLAG_NO_CREATE` 只查不造
 *    （口径同 [ClassProgressScheduler.hasPendingClassBells]、
 *    [BackgroundSync.existingMidnightPendingIntent]、`TomorrowPreviewScheduler.cancel`、
 *    [ReminderScheduler.cancelAll]）。探测目标**按人群分别选**，见 [alarmsStillArmed]。
 * 3. **距上次成功 ≥ 24h** —— 无条件重跑，连前两条都不判（[RERUN_AFTER_MILLIS]）。
 *    这条给"跳过"一个硬上限，兜住"整条重建链被掐死、不再自我传播"这种最坏情况，
 *    同时也是下面那条 PI 误判的封顶。
 *
 * ## 钥匙 2 的已知不可靠性（**这段论证不许省**）
 *
 * `ClassProgressScheduler.cancel` 的注释（`ClassProgressScheduler.kt:409-413`）记着一条
 * **真机实测过的反向事实**：只调 `alarmManager.cancel()` 之后，那条 PI 记录**仍可被
 * `FLAG_NO_CREATE` 查得到**，因为 PI 对象被应用侧的引用钉住；所以课堂铃那条取消路径
 * 特意补了 `PendingIntent.cancel()`（`ClassProgressScheduler.kt:414-421`）。
 * 也就是说"探得到 PI"**逻辑上不等于**"闹钟还在排"。
 *
 * 为什么在**冷启动这个特定时点**可以接受：
 * - 那条实测的前提是"PI 记录被**本进程**的引用钉住"，而判据跑在 `Application.onCreate`
 *   —— 那个方法只在**进程创建**时运行一次。钉住记录的那次 `getBroadcast` 发生在**上一个**进程里，
 *   进程一死应用侧引用就没了，系统里那条 PI 记录剩下的持有者就是"还在排的闹钟"本身。
 *   所以在新进程里"探得到"与"还在排"是同一件事，那条反向事实不复存在；
 * - 仓库的取消路径有两种形状，都摊在冷启动这个时点上：课堂铃那条带 `PendingIntent.cancel()`
 *   （`ClassProgressScheduler.cancelWith`，`ClassProgressScheduler.kt:418-421`，
 *   `cancel` 的 `:414-415` 走的就是它），课前提醒那条只 `alarmManager.cancel()`
 *   （`ReminderScheduler.cancelAll`，`ReminderScheduler.kt:307-313`）—— 后者正是实测会踩的形状，
 *   但它只在"同一个进程里紧接着再查一遍"时才有后果，而本闸门查的那一刻进程刚创建；
 * - 万一还是误判（除此之外还有别的持有者：仍挂在通知栏里那条课前倒计时通知的 contentIntent
 *   是最现实的一条，它能让一条早已不在排的闹钟的 PI 记录照样查得到）：
 *   最坏是白跳过一轮，**[RERUN_AFTER_MILLIS]（24h）到点必然无条件重跑**，
 *   而 `widget_fallback_refresh` 那条 12 小时的兜底 Worker 走的根本不是这条链
 *   （[WidgetFallbackWorker.doWork] 自己重排），它比 24h 更早到。
 *
 * 残余风险（真机才能证伪）：上一条里"新进程 ⇒ 探得到 ⟺ 还在排"是推理，
 * 没有设备取证过"冷启动时探到 PI 而闹钟其实不在排"到底会不会发生；
 * 通知栏残留是其中唯一看得见的具体形态。
 *
 * ## 为什么"敢跳过"：自愈链已经逐人群回读证实（本卡前置验证）
 *
 * - **开着课前提醒的人**：[ReminderReceiver] 每次被投递都会重跑
 *   `ReminderScheduler.rescheduleAll`（`ReminderReceiver.kt:56-63`），链条自我传播，
 *   自愈窗口 = 下一条提醒。所以这类人钥匙 2 探的是课前提醒那一头（[AlarmHead.PreClassReminder]）。
 * - **课前提醒全关的人**：应用内一个提醒闹钟都没有，活着的是上/下课铃链 ——
 *   下课铃 `ACTION_END` 必定续排下一个窗口（`ClassProgressReceiver.kt:71-78` →
 *   `:98-110` → `ClassProgressScheduler.rescheduleNextWindow`）。这条链也断了的话，
 *   剩下的入口是 `widget_fallback_refresh`（周期 **12 小时**，`WidgetFallbackWorker.kt:85`；
 *   注册条件是"有组件 **或** 提醒走应用内闹钟"，`WidgetFallbackWorker.kt:81`，
 *   所以这类人确实在册）与开机/改时间/改课表那三类事件。
 *   窗口长到 12 小时，所以这类人的钥匙 2 **必须探到课堂铃在排才算数**
 *   （[AlarmHead.ClassBells]），不接受"另一头还挂着"当替代。
 * - **切到「系统日历提醒」的人**：应用内本来一个闹钟都不排
 *   （[BackgroundSync.rescheduleReminders] 的 `!usesInAppReminders` 分支走清理然后返回），
 *   所以钥匙 2 对他们永远判"不在" → 永远重跑，行为与改动前逐字一致。
 *   这是正确结果，**没有为这类用户开任何例外**。
 *
 * ## 不归本闸门管的（照旧每次冷启动都跑）
 *
 * - `ClassProgressDnd.selfCheck` —— 勿扰的**唯一**自愈入口（开机那条已由 ai/T12 改走它），
 *   跳过它等于把勿扰自愈关掉。
 * - `Personalization.load` / `ReminderNotifications.ensureChannels` —— 本来就在 `launch` 之前。
 * - [BackgroundSync.cancelLegacyPeriodicWork] —— 论证过，结论是**留在闸门外面**：
 *   它要 `WorkManager.getInstance`（冷进程首次会建它自己的库），看着正该跳过；
 *   但"旧版固定周期轮询还在册"这个状态恰恰只有它能清掉，而它一被跳过，
 *    legacy 任务自己的下一次唤醒又会被判成"无事可做"继续跳过 ——
 *   那是个自我闭合的死循环，被退役的任务会一路polling到某次 versionCode 变化为止。
 *   留在外面付的是"一次幂等 cancelUniqueWork"，而这个代价正是它存在的理由。
 *
 * ✅ 订正（ai/T14 收掉 ai/T13 登记的最后一条残余）：这一节过去还列着
 * `ClassProgressScheduler.rescheduleNextWindow`（课堂铃兜底续排）—— 它自己吞异常，
 * 闸门看不见它的失败，那一轮因此仍被记成成功。**现在它归本闸门的第一半管**：
 * 那一步把失败报给 [BackgroundSync.rescheduleRemindersAndBells] 的同一个报告口
 * ⇒ 进下面的 `failures` 清单 ⇒ 不写指纹 ⇒ 下一次冷启动无条件重跑。
 * 旧注释那句"方向上是安全的"不成立：那道失败抛在 `rescheduleWindows` 第一句 `cancel` **之前**，
 * 此时上一轮那对课堂铃还挂着，钥匙 2 反倒因此判"在"→ 白跳过一整轮，
 * 最长 24 小时里用户用的是上一轮的窗口（课删了还在响、改过时间还按旧的时刻响）。
 * 完整的账写在 [BackgroundSync.rescheduleRemindersAndBells] 的注释里。
 *
 * ## 指纹为什么不进 Room
 *
 * 只有两个数（versionCode + 时间戳）加一个枚举名。当前 DB 是 v9，为这两个数开一张表要走一次
 * 迁移、一次 schema 导出与一批迁移测试，代价和测试面都比这件事本身大；
 * 而 `Application.onCreate` 这条链上**已经**在读这个 prefs 文件
 * （[BackgroundSync.usesInAppReminders] 与两个课堂开关都在 `schedule_settings` 里），
 * 寄在它里面是零次额外磁盘打开。它会被云备份带走，但方向是安全的：
 * 新设备上没有任何已排闹钟，钥匙 2 当场判"不在"→ 照旧重跑。
 */
internal object ColdStartRebuild {

    private const val TAG = "ColdStartRebuild"

    /**
     * 指纹的落脚点：与钥匙 2 要读的课堂开关、提醒模式同在 `schedule_settings`
     * （键名带 `background_rebuild_` 前缀，不与任何用户设置撞名）。理由见类注释最后一段。
     */
    private const val PREFS_NAME = ClassProgressReceiver.PREFS_NAME
    private const val KEY_VERSION = "background_rebuild_version_code"
    private const val KEY_SUCCEEDED_AT = "background_rebuild_succeeded_at"
    private const val KEY_HEAD = "background_rebuild_alarm_head"

    /** 上一次"整链成功跑完"留下的指纹；`succeededAt` 为 0 表示从没记过（读的时候会给出 null） */
    internal data class Fingerprint(
        val versionCode: Long,
        val succeededAt: Long,
        /** 那一次重排排上了哪一头闹钟 = 下一次钥匙 2 该探哪一头；null = 什么都没排上 */
        val head: AlarmHead?,
    )

    /** 钥匙 2 的探测目标（人群分别探测，不许只探一种） */
    internal enum class AlarmHead {
        /** 课前提醒那一头：它活着就说明整条提醒链在自我传播，顺带把课堂铃一起续排 */
        PreClassReminder,

        /** 课堂铃那一头：课前提醒全关的人只剩它 */
        ClassBells,
    }

    /** 这一次冷启动的结论（`reason` 进日志，供审计 §4.4 那种改前/改后对账用） */
    internal data class Decision(val rebuild: Boolean, val reason: String)

    /** 一次调用的结果：跳过了 / 跑了且干净 / 跑了但有步骤抛（后两者都不写指纹的只有第一种） */
    internal enum class Outcome { Rebuilt, RebuiltWithFailures, Skipped }

    /** 钥匙 3 的窗口：跳过行为的上限，超过它就无条件重跑 */
    internal const val RERUN_AFTER_MILLIS = 24L * 60L * 60L * 1000L

    /**
     * 生产接线：`BUAAApplication.onCreate` 那条链的入口。
     *
     * 只有这一步真的用到 Context；判据本体在 [run]（不接 Context、副作用全注入）。
     */
    suspend fun run(context: Context): Outcome {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return run(
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            nowMillis = System.currentTimeMillis(),
            readFingerprint = { readFingerprint(prefs) },
            writeFingerprint = { writeFingerprint(prefs, it) },
            alarmsStillArmed = { head -> alarmsStillArmed(context, head) },
            rebuildRemindersAndBells = { report ->
                // 返回值就是"本轮排上了课前提醒闹钟"（口径见 rescheduleRemindersAndBells）
                val reminderArmed = BackgroundSync.rescheduleRemindersAndBells(context, report)
                if (reminderArmed) AlarmHead.PreClassReminder else AlarmHead.ClassBells
            },
            runWidgetSteps = { report ->
                BackgroundSync.runColdStartWidgetSteps(context, report)
            },
        )
    }

    /**
     * 判据 + 整链 + 指纹的编排本体：**不接 Context，副作用全部注入 lambda**。
     *
     * 形状抄 [BackgroundSync.runColdStartWidgetSteps]（注入版与接 Context 的生产接线两层）——
     * 本模块单测没有 Robolectric（android.jar 里全是 "not mocked" 的桩），
     * 真跑一次这条链要设备，所以 JVM 侧唯一钉得住的是"整链被跑了几遍、什么时候不该跑"，
     * 而那正是这张卡的全部风险面（见 `ColdStartRebuildTest`）。
     *
     * @param alarmsStillArmed 钥匙 2 的探针，参数是"上一次成功重排排上了哪一头"。
     *   只在钥匙 3 与钥匙 1 都放行时才被调用（省掉两次 binder 探测）。
     * @param rebuildRemindersAndBells 重建整链的第一半（重排提醒 + 课堂铃兜底）；
     *   返回这一轮排上的那一头闹钟，null 表示这一步抛了。
     * @param runWidgetSteps 重建整链的第二半（组件四步）。
     * @param reportStepFailure 闸门自己那三步（读指纹 / 探测 / 写指纹）的留痕口，
     *   默认就是 `Log.w`；收成参数是为了让 JVM 单测不被 android.jar 的桩带偏
     *   （同 `runColdStartWidgetSteps` 的那个参数，理由一字不差）。
     * @param logDecision 判据结论的留痕口。审计 §4.4 数的是"冷进程启动次数"，
     *   而这条链的成功路径本来就一条日志都不打 —— 跳过/重跑各留一行才量得清改没改对，
     *   所以这里给一个口（生产是 `Log.d`）。
     * @return 这一次的结论，仅用于留痕与单测
     */
    internal suspend fun run(
        currentVersionCode: Long,
        nowMillis: Long,
        readFingerprint: () -> Fingerprint?,
        writeFingerprint: (Fingerprint) -> Unit,
        alarmsStillArmed: (AlarmHead?) -> Boolean,
        rebuildRemindersAndBells: suspend (report: (String, Throwable) -> Unit) -> AlarmHead?,
        runWidgetSteps: suspend (report: (String, Throwable) -> Unit) -> Unit,
        rerunAfterMillis: Long = RERUN_AFTER_MILLIS,
        reportStepFailure: (String, Throwable) -> Unit = LOG_REBUILD_STEP_FAILURE,
        logDecision: (Decision) -> Unit = LOG_REBUILD_DECISION,
    ): Outcome {
        val stored = runCatching { readFingerprint() }
            .onFailure { reportStepFailure("readRebuildFingerprint", it) }
            .getOrNull()
        val failures = mutableListOf<String>()
        val report: (String, Throwable) -> Unit = { label, error ->
            failures += label
            reportStepFailure(label, error)
        }
        val decision = decide(
            stored = stored,
            currentVersionCode = currentVersionCode,
            nowMillis = nowMillis,
            rerunAfterMillis = rerunAfterMillis,
            // 只有前两把钥匙都放行时才会被调到（探针一次都不必花钱）
            alarmsStillArmed = { head ->
                runCatching { alarmsStillArmed(head) }
                    .onFailure { report("probeRebuildAlarms", it) }
                    .getOrDefault(false) // 探测抛了按"不在"处理 = 重跑，方向只能是多跑
            },
        )
        logDecision(decision)
        if (!decision.rebuild) return Outcome.Skipped

        val head = runCatching { rebuildRemindersAndBells(report) }
            .onFailure { report("rebuildRemindersAndBells", it) }
            .getOrNull()
        runCatching { runWidgetSteps(report) }
            .onFailure { report("coldStartWidgetSteps", it) }

        // ⚠️ 只有整链干净地跑完才写"上次成功"：任一步抛了就不写，下一次冷启动照跑。
        // 跳过的那一轮同样不写（否则 24h 这个硬上限永远到不了）。
        if (head == null || failures.isNotEmpty()) return Outcome.RebuiltWithFailures
        runCatching { writeFingerprint(Fingerprint(currentVersionCode, nowMillis, head)) }
            .onFailure { reportStepFailure("writeRebuildFingerprint", it) }
        return Outcome.Rebuilt
    }

    /**
     * 三把钥匙的判据本体（纯函数，可单测）：任一把说"该跑"就跑，全部说"可跳"才跳。
     *
     * 顺序是刻意的：**钥匙 3 在最前面**，它一旦开口就不必再去花 binder 的钱问闹钟；
     * 指纹根本不存在（首次安装 / 数据被清 / 从没成功跑完整链）也走这一条，
     * 因为"没有成功记录"与"上次成功在 24h 之前"给出的是同一个答案。
     *
     * 时钟回摆（用户改系统时间、时区变化）同样按"该跑"处理：指纹上的时间戳已经不不可信，
     * 而多跑一次从来不是这条链的故障模式。
     */
    internal fun decide(
        stored: Fingerprint?,
        currentVersionCode: Long,
        nowMillis: Long,
        alarmsStillArmed: (AlarmHead?) -> Boolean,
        rerunAfterMillis: Long = RERUN_AFTER_MILLIS,
    ): Decision {
        val last = stored?.succeededAt
        if (last == null) return Decision(true, "无成功记录：整链重跑")
        val elapsed = nowMillis - last
        if (elapsed >= rerunAfterMillis) {
            return Decision(true, "距上次成功 ${elapsed / 3_600_000L}h 已过 ${rerunAfterMillis / 3_600_000L}h 上限：整链重跑")
        }
        if (elapsed < 0) return Decision(true, "时钟回摆（$elapsed ms）：时间戳不可信，整链重跑")
        if (stored.versionCode != currentVersionCode) {
            return Decision(true, "versionCode ${stored.versionCode} -> $currentVersionCode：整链重跑")
        }
        if (!alarmsStillArmed(stored.head)) return Decision(true, "自己的闹钟不在了：整链重跑")
        return Decision(false, "三把钥匙均放行：跳过重建整链（$elapsed ms 前刚成功跑过）")
    }

    /**
     * 钥匙 2：这个人的闹钟该探哪一头，以及它到底还挂不挂着。
     *
     * 探测目标按人群分别选，人群由**上一次成功重排留下的结论**（[Fingerprint.head]）决定 ——
     * 那是唯一不查库就知道的信息："有没有可排的课前提醒"要读四张表，
     * 而读四张表正是本卡要省掉的东西。
     *
     * - 日历模式 → 直接判"不在"。这类用户应用内一个闹钟都不排
     *   （[BackgroundSync.rescheduleReminders] 的清理分支），所以每一次冷启动都照旧重跑，
     *   与改动前完全一致 —— 这里**不许**为他们开例外。
     * - `head == null`（上次什么都没排上）→ 同样判"不在"，永远重跑。
     *   宁可让"提醒全关 + 两个课堂开关都关"的这类用户白付一次重建，
     *   也不能在没有正向证据时跳过。
     * - 两头都用 `FLAG_NO_CREATE` **只查不造**，且只查被点名的那一头：课前提醒一头查一条，
     *   课堂铃那一头沿用 [ClassProgressScheduler.hasPendingClassBells] 的既有口径
     *   （上/下课铃各查一次，都在同一次 `getSystemService` 的往来里）。
     */
    internal fun alarmsStillArmed(
        usesInAppReminders: Boolean,
        head: AlarmHead?,
        reminderAlarmArmed: () -> Boolean,
        classBellsArmed: () -> Boolean,
    ): Boolean {
        if (!usesInAppReminders) return false
        return when (head) {
            AlarmHead.PreClassReminder -> reminderAlarmArmed()
            AlarmHead.ClassBells -> classBellsArmed()
            null -> false
        }
    }

    /** [alarmsStillArmed] 的生产接线：读一次 prefs 判人群，再按点名的那一头去问 AlarmManager */
    private fun alarmsStillArmed(context: Context, head: AlarmHead?): Boolean = runCatching {
        alarmsStillArmed(
            usesInAppReminders = BackgroundSync.usesInAppReminders(context),
            head = head,
            reminderAlarmArmed = { ReminderScheduler.hasPendingReminder(context) },
            classBellsArmed = { ClassProgressScheduler.hasPendingClassBells(context) },
        )
    }.onFailure { Log.w(TAG, "探测闹钟是否还挂着失败，按已不在处理（= 整链重跑）", it) }.getOrDefault(false)

    /** 时间戳为 0（或负）就是"从没成功跑完过"：不返回一个 `succeededAt = 0` 的假指纹 */
    private fun readFingerprint(prefs: SharedPreferences): Fingerprint? {
        val succeededAt = prefs.getLong(KEY_SUCCEEDED_AT, 0L)
        if (succeededAt <= 0L) return null
        return Fingerprint(
            versionCode = prefs.getLong(KEY_VERSION, 0L),
            succeededAt = succeededAt,
            // 装不出枚举名说明这条记录来自没见过的版本：按"什么都没排上"处理 = 永远重跑
            head = prefs.getString(KEY_HEAD, null)?.let { name ->
                runCatching { AlarmHead.valueOf(name) }.getOrNull()
            },
        )
    }

    private fun writeFingerprint(prefs: SharedPreferences, fingerprint: Fingerprint) {
        prefs.edit {
            putLong(KEY_VERSION, fingerprint.versionCode)
            putLong(KEY_SUCCEEDED_AT, fingerprint.succeededAt)
            putString(KEY_HEAD, fingerprint.head?.name)
        }
    }
}
