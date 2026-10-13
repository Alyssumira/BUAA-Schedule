package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T227 #338（台账 #265 的剩余两格）：课前倒计时的**归属/时间两判**在排铃与前台校准那两条链上
 * 各自读哪一只钟 —— 纯 JVM 源码核对（无 Robolectric、无设备、不读时钟）。
 *
 * ## 病灶一句话
 *
 * 同一个"课还没开始 && 屏上正数着这节课"的判断里，`now` 用注入的读数，
 * 而倒计时归属用 [ReminderNotifications.isCountingDownTo] 自己 `System.currentTimeMillis()` 现读
 * ⇒ **一次判断两只钟**。两次读数之间不是纳秒级，是毫秒到几十毫秒级 ——
 * 因为中间隔着那轮 `课程数 × 剩余周次 × 节次` 的全量窗口搜索
 * （`ReminderScheduler` 自己量过：学期中段上千次窗口构造）。跨秒那一瞬两处会先掉个儿：
 * 注入的那只说"还没开始"、真钟说"已开始" ⇒ 守卫当场失效，走的是遗留清理那一条分支
 * （`CourseFluidService.stop` + `cancelClassOngoing` + `ClassProgressDnd.restore`），
 * 当场拆掉的恰恰是"这一节正要开始、勿扰正要生效"的那一秒：勿扰在一秒内被放开再挂回去、
 * 实况掉一下再弹回。不崩、不报错，用户只看见"那一节课抖了一下"。
 *
 * ## 第三处早就收过口，这一层按同一口径补另两处
 *
 * `ReminderScheduler` 的接线（T206/#266）递的是 [ReminderNotifications.ownsCountdownTo]
 * —— 只判归属，时间判由同一条判断里那只已经注入的 `now` 做。这里钉的就是"同一口径"四格：
 * - **课堂铃链**（`ClassProgressScheduler.rescheduleWindows`）：`startsAfter(now.toEpochMillis())`
 *   已经判过"课还没开始"，所以归属那一判换成 `ownsCountdownTo` 就是语义等价且只读一只钟；
 * - **前台校准**（`LiveClassResyncer.resync`）：这一处**时间判不能丢** —— 它的
 *   `ClearLeftovers` 也包含"这一节已经结束"那一档，只看归属会让下课铃被吞的遗留永远收不掉
 *   （R5 F-11）。所以走三枚参数的注入版，把 `decide` 那只钟递给谓词，而不是合并判据；
 * - **两枚谓词不许合并**：只判归属与判归属+时间是两本账（卡面 T206 落的那条判据，
 *   不是冗余）。第 ① 层按"体里读不读钟、有没有时间比较式"分钉，合一起就红；
 * - **体内读真钟的那一枚入口**全仓只剩一处，且它只是委托（第 ② 层）。
 *
 * ## 四家钉法逐格点名（卡面要求）
 *
 * 1. [theTwoChainReadersAskOneClockOnly] —— **数出现次数**（宿主文件里各锚点的枚数册子）
 *    + **读形状**（取调用块实参表，判它递的是注入的那只钟）+ **逐字节**（接线那一行）；
 * 2. [theRealClockReaderIsTheThinDelegate] —— **逐字节**（三枚定义的签名与体）+ **数出现次数**
 *    （`System.currentTimeMillis(` 在 ReminderNotifications 那三枚谓词里恰一处）；
 * 3. [theTwoPredicatesAreNotTheSameRuler] —— **读形状**（归属那一枚体里零时间比较式）
 *    + **数出现次数**（全仓各恰一处定义）：这一层就是卡面那句"不是冗余、别合并成一枚"；
 * 4. [probeRecognisesThePreFixShape] —— **抹注释找锚点**：改前那两形（链上直接吃两枚参数的谓词、
 *    体内自己读钟的判据体）喂进同一把尺各数到该数的值，写在 KDoc 里的引用抹完必须 0 处。
 *
 * ## 第 ⑤ 层（ai/T354，在册 #603）：同一只钟还得把勿扰看门狗带到新帧
 *
 * 时间类变化那一跳（清单里唯一的入口 `WidgetRefreshReceiver` → `BackgroundSync.rescheduleRemindersAndBells`
 * → 本文件的宿主 `rescheduleWindows`）会把 11 枚绝对毫秒闹钟里的 10 枚重排到新帧，
 * 唯独漏那只看门狗 —— 它唯一的排程点是 `ClassProgressDnd.enter`，而 enter 唯一的调用点是上课铃。
 * 修法吃的是本文件第 ① 层那条既有约束：**新帧的下课时刻与那只已经注入的 `now` 就在这同一跳里**，
 * 所以补排收在 `rescheduleWindows` 而不是另起一步（另起那一步拿不到下课时刻，见
 * `ClassProgressDnd.alignWatchdogToNewFrame` 的说明）。五层钉法：
 * 5. [theWatchdogHopHandsOverTheNewFrameNotASecondClock] —— **逐字节**（两枚实参各恰一处）
 *    + **读形状**（`ongoingAt` 必须显式吃注入的读数，写成吃签名默认值的 `ongoingAt()` 就长了第二只钟）
 *    + **数出现次数**（这一跳只驱动一次、`LocalDateTime.now()` 仍是枚一）；
 * 6. [theNewFrameCriterionAndRulerExistInExactlyOnePlace] —— **数出现次数（全 main 册子）**
 *   （判据本体一处、接线一处、`armWatchdog(` 三处、宽限量一处）+ **读形状**（判据体与那把尺都零钟）；
 * 7. [watchdogProbeRecognisesTheTwoBadShapes] —— **抹注释找锚点**：根本没有那一手 /
 *    吃签名默认值那一形 / 写在注释里那一形，三形各数到该数的值。
 */
class CountdownClockSingleReadGuardTest {

    // ─────────────── ① 两条链的读侧各只有一只钟 ───────────────

    @Test
    fun theTwoChainReadersAskOneClockOnly() {
        val progress = withoutComments(readMainSource(PROGRESS_FILE))
        val resyncer = withoutComments(readMainSource(RESYNCER_FILE))
        val scheduler = withoutComments(readMainSource(REMINDER_SCHEDULER_FILE))

        // 课堂铃链：体内读钟的那一枚 0 处，只判归属的那一枚 1 处
        assertEquals(
            "**钉法 = 数出现次数**：`ClassProgressScheduler.kt` 里 `isCountingDownTo(` 必须 0 处 —— " +
                "它每一判都该读同一只注入的 `now`。实到 " + occurrences(progress, "isCountingDownTo(") +
                " 处（抹注释数：文件里那些注释逐字写着这个名字，不抹就是把散文当证据）",
            0,
            occurrences(progress, "isCountingDownTo("),
        )
        assertEquals(
            "**钉法 = 逐字节 + 数出现次数**：课堂铃链那一读必须恰一处走只判归属的那枚" +
                "（`$PLAN_OWNERSHIP_READ`；实到 " + occurrences(progress, PLAN_OWNERSHIP_READ) + " 处）",
            1,
            occurrences(progress, PLAN_OWNERSHIP_READ),
        )
        val reschedule = balancedBlock(progress, "fun rescheduleWindows(")
        assertEquals(
            "**钉法 = 读形状**：rescheduleWindows 整次重排只许读一次真钟（`val now = LocalDateTime.now()`），" +
                "选窗口与判\"课还没开始\"都由它派生。实到 " + occurrences(reschedule, CLOCK_LOCAL_DATE_TIME) + " 处",
            1,
            occurrences(reschedule, CLOCK_LOCAL_DATE_TIME),
        )
        assertEquals(
            "**钉法 = 读形状**：时间判必须留在同一条 `&&` 上、由那只注入的钟做" +
                "（`$PLAN_TIME_JUDGEMENT`；实到 " + occurrences(reschedule, PLAN_TIME_JUDGEMENT) +
                " 处）。0 处 = 时间判被顺手删了，遗留从此收不掉；2 处 = 又长了第二只钟",
            1,
            occurrences(reschedule, PLAN_TIME_JUDGEMENT),
        )
        assertEquals(
            "**钉法 = 数出现次数（读形状取块，不数整份文件）**：`rescheduleWindows` 的体里 " +
                "`System.currentTimeMillis(` 必须 0 处 —— 这条链的读数只许那一只注入的 `now`。" +
                "长出第二枚就是同一次判断两只钟（同文件 ClassWindow 那三枚判据的**签名默认值**不在这一格里，" +
                "那是台账 #265 的另一本账，今天全仓零读者）：实到 " +
                occurrences(reschedule, CLOCK_EPOCH_MILLIS) + " 处",
            0,
            occurrences(reschedule, CLOCK_EPOCH_MILLIS),
        )

        // 前台校准：一次判断一只钟 —— 整份文件只许一枚读数，谓词吃的是它
        assertEquals(
            "**钉法 = 数出现次数**：`LiveClassResyncer.kt` 整份文件里 `System.currentTimeMillis(` 恰一处" +
                "（decide 与遗留清理那两判共用这一次读数）。实到 " +
                occurrences(resyncer, "System.currentTimeMillis(") + " 处",
            1,
            occurrences(resyncer, CLOCK_EPOCH_MILLIS),
        )
        assertEquals(
            "**钉法 = 数出现次数**：前台校准那一读恰一处（`isCountingDownTo(`；实到 " +
                occurrences(resyncer, "isCountingDownTo(") + " 处）—— 它的时间判不能丢",
            1,
            occurrences(resyncer, "isCountingDownTo("),
        )
        val ask = argumentsOf(resyncer, "isCountingDownTo(")
        assertTrue(
            "**钉法 = 读形状**：递进谓词的必须是那只已经读过的 `nowMillis`，不许是第二次读数：\n$ask",
            ask.contains("nowMillis"),
        )
        assertFalse(
            "**钉法 = 读形状**：调用实参表里又出现一次现读真钟（$CLOCK_EPOCH_MILLIS）= 一次判断两只钟，" +
                "本卡的病灶原地复发：\n$ask",
            ask.contains(CLOCK_EPOCH_MILLIS),
        )
        assertTrue(
            "**钉法 = 逐字节**：前台校准仍按课堂计划窗口那一型取上课时刻（`$CLASS_START_CALL`；" +
                "口径与 CountdownOwnershipAnchorGuardTest 第 ② 层同一把尺）：\n$ask",
            ask.contains(CLASS_START_CALL),
        )

        // 第三处（早已收口那一处）不许被改回去
        assertEquals(
            "**钉法 = 数出现次数**：`ReminderScheduler.kt` 里 `isCountingDownTo(` 必须 0 处（抹注释数），" +
                "实到 " + occurrences(scheduler, "isCountingDownTo(") + " 处",
            0,
            occurrences(scheduler, "isCountingDownTo("),
        )
        assertEquals(
            "**钉法 = 逐字节**：那条链的生产接线仍只有一处（`$SEAM_WIRING`；实到 " +
                occurrences(scheduler, SEAM_WIRING) + " 处）",
            1,
            occurrences(scheduler, SEAM_WIRING),
        )

        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`$TWO_CLOCK_SHAPE` 那一形全 app/src/main 恰 0 处" +
                "（实到 " + mainOccurrences(TWO_CLOCK_SHAPE) + " 处）—— 病灶不许换个宿主回来",
            0,
            mainOccurrences(TWO_CLOCK_SHAPE),
        )
    }

    // ─────────────── ② 体内读真钟的那一枚只剩薄委托 ───────────────

    @Test
    fun theRealClockReaderIsTheThinDelegate() {
        val code = withoutComments(readMainSource(NOTIFICATIONS_FILE))

        assertEquals(
            "**钉法 = 逐字节**：两枚参数那一形必须只剩委托（`$DELEGATING_BODY`；实到 " +
                occurrences(code, DELEGATING_BODY) + " 处）。它继续读真钟是给手里没有注入读数的" +
                "调用方留的面，但判据文本必须只有一份 —— 抄第二份就是两把尺各错各的",
            1,
            occurrences(code, DELEGATING_BODY),
        )
        assertEquals(
            "**钉法 = 逐字节**：三枚参数那一枚是纯内核（`$INJECTED_KERNEL`；实到 " +
                occurrences(code, INJECTED_KERNEL) + " 处）",
            1,
            occurrences(code, INJECTED_KERNEL),
        )
        val injected = balancedLineAfter(code, INJECTED_KERNEL)
        assertFalse(
            "**钉法 = 读形状**：三枚参数的内核体里读真钟（$CLOCK_EPOCH_MILLIS）就不再是零时钟判据，" +
                "表驱动测试也量不到它：\n$injected",
            injected.contains(CLOCK_EPOCH_MILLIS),
        )
        assertTrue(
            "**钉法 = 读形状**：内核必须是「归属 + 时间」两判的组合（委托给只判归属那一枚 + " +
                "调用点递进来的钟），不是自己再摊一遍两格字段名：\n$injected",
            injected.contains("ownsCountdownTo(courseId, classStartMillis)"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：那三枚谓词的体里 `System.currentTimeMillis(` 全仓只许一枚" +
                "（就是上面那行委托）。实到 " +
                occurrences(injected, CLOCK_EPOCH_MILLIS) + " + " + occurrences(twoArgBody(code), CLOCK_EPOCH_MILLIS) + " 处",
            1,
            occurrences(twoArgBody(code), CLOCK_EPOCH_MILLIS) + occurrences(injected, CLOCK_EPOCH_MILLIS),
        )
    }

    // ─────────────── ③ 两枚谓词不是同一把尺（不许合并） ───────────────

    @Test
    fun theTwoPredicatesAreNotTheSameRuler() {
        val code = withoutComments(readMainSource(NOTIFICATIONS_FILE))

        assertEquals(
            "**钉法 = 数出现次数（全 main）**：`fun isCountingDownTo(` 全仓恰两处（两枚参数 + 三枚参数），" +
                "实到 " + mainOccurrences("fun isCountingDownTo(") + " 处",
            2,
            mainOccurrences("fun isCountingDownTo("),
        )
        assertEquals(
            "**钉法 = 数出现次数（全 main）**：`internal fun ownsCountdownTo(` 全仓恰一处，实到 " +
                mainOccurrences(OWNERSHIP_DECLARATION) + " 处",
            1,
            mainOccurrences(OWNERSHIP_DECLARATION),
        )

        val ownership = balancedLineAfter(code, OWNERSHIP_DECLARATION)
        for (forbidden in listOf(CLOCK_EPOCH_MILLIS, "LocalDateTime.now(", ">", "now")) {
            assertFalse(
                "**钉法 = 读形状**：只判归属那一枚的体里出现「$forbidden」= 它开始兼任时间判，" +
                    "于是链上两处（`rescheduleWindows` 与 `shouldTakeDownClassProgress`）的" +
                    "\"课还没开始\"变成各判各的；反过来把时间判并回来则会让" +
                    "「下课铃被吞的遗留」永远收不掉（R5 F-11）。这两枚不是同值函数，卡面明令不许合并。" +
                    "\n实到体：$ownership",
                ownership.contains(forbidden),
            )
        }
        assertEquals(
            "**钉法 = 逐字节**：归属那一判就是两格字段相等，一行式体逐字对账" +
                "（expected `$OWNERSHIP_BODY`；实到「$ownership」）",
            OWNERSHIP_BODY,
            ownership,
        )
    }

    // ─────────────── ④ 探测器自检 ───────────────

    /**
     * 锚点拼错 ⇒ 永远数到 0 永远绿。两头各喂一形：
     * - **朝松**：改前那两形（链上直接吃两枚参数、谓词体内自己读钟）必须各数到 1 处；
     * - **朝窄**：同一句写在 KDoc 里、抹完注释必须 0 处（本文件与 main 的注释都逐字写着那些名字）。
     */
    @Test
    fun probeRecognisesThePreFixShape() {
        val preFixChainRead = "if (window.startsAfter(now.toEpochMillis()) &&\n" +
            "    !ReminderNotifications.isCountingDownTo(window.courseId, window.startMillis)"
        assertEquals(
            "探测器必须抓得出改前那一手（链上直接吃两枚参数的谓词；实到 " +
                occurrences(preFixChainRead, TWO_CLOCK_SHAPE) + " 处）—— 第 ① 层那句「0 处」的对照物",
            1,
            occurrences(preFixChainRead, TWO_CLOCK_SHAPE),
        )

        val preFixBody = """
            fun isCountingDownTo(courseId: Long, classStartMillis: Long): Boolean =
                countdownCourseId == courseId &&
                    countdownClassStart == classStartMillis &&
                    classStartMillis > System.currentTimeMillis()
        """.trimIndent()
        assertEquals(
            "探测器必须抓得出「判据在体内读真钟」那一形（$CLOCK_EPOCH_MILLIS 数到 1 处；实到 " +
                occurrences(preFixBody, CLOCK_EPOCH_MILLIS) + " 处）—— 第 ② 层那枚「全仓只许一枚」靠的就是它",
            1,
            occurrences(preFixBody, CLOCK_EPOCH_MILLIS),
        )
        assertEquals(
            "同一形里时间判也读同一只钟 ⇒ 改前它是「归属 + 时间」三判摊在一起（`>` 一处）",
            1,
            occurrences(preFixBody, ">"),
        )

        val quotedInKDoc = """
            /**
             * 旧形状登记：`isCountingDownTo(window.courseId, window.startMillis)` 这一形今天在排铃链上写过一份。
             */
            fun noop() = Unit
        """.trimIndent()
        assertEquals(
            "注释里逐字引用的那一形**不许**进第 ① 层的账：未抹注释时它确实在原文里（实到 " +
                occurrences(quotedInKDoc, TWO_CLOCK_SHAPE) + " 处），抹完必须 0 处（实到 " +
                occurrences(withoutComments(quotedInKDoc), TWO_CLOCK_SHAPE) + " 处）",
            0,
            occurrences(withoutComments(quotedInKDoc), TWO_CLOCK_SHAPE),
        )
    }

    // ─────────────── ⑤ #603 看门狗跟着新帧走：接线只在这一跳、判据只此一份 ───────────────

    /**
     * 重排那一跳确实把新帧的下课时刻交给了看门狗那一步，而且**吃的是同一只已经注入的钟**。
     *
     * 这一层要拦的是 seam 那一族的固有取舍（判据本体搬到 JVM 表驱动那边去了，
     * 见 `reminder/DndWatchdogTimeFrameRearmDecisionTest`）：接线到底注没注对那三样，
     * 只有按源码形状核对才量得到。两头各钉一次：
     * - **逐字节**：两枚实参各恰一处（写错的形态是"把 `window.endMillis` 原样递过去"——
     *   那就绕过了 [ClassProgressDnd.deadlineOfEnd] 那把唯一的宽限尺）；
     * - **读形状**：`ongoingAt` 必须显式吃那只钟 —— 它有一个
     *   `nowMillis: Long = System.currentTimeMillis()` 的**签名默认值**，
     *   写成无实参的 `ongoingAt()` 编译得过、测试也照样绿，但那条链上就长了第二只钟
     *   （台账 #265 那一族的形状，与本守卫第 ① 层同一本账）。
     */
    @Test
    fun theWatchdogHopHandsOverTheNewFrameNotASecondClock() {
        val progress = withoutComments(readMainSource(PROGRESS_FILE))
        val reschedule = balancedBlock(progress, "fun rescheduleWindows(")

        assertEquals(
            "**钉法 = 数出现次数**：`rescheduleWindows` 里驱动看门狗那一步只许一处" +
                "（多一处 = 同一格期限被两处各改各的）。实到 " +
                occurrences(reschedule, "ClassProgressDnd.alignWatchdogToNewFrame(") + " 处",
            1,
            occurrences(reschedule, "ClassProgressDnd.alignWatchdogToNewFrame("),
        )
        assertEquals(
            "**钉法 = 逐字节**：新帧的期限必须经由唯一那把宽限尺（`$WATCHDOG_FRESH_ARG`；实到 " +
                occurrences(reschedule, WATCHDOG_FRESH_ARG) + " 处）—— 直接递 `window.endMillis` " +
                "就是把「下课 + 宽限」摊成第二处",
            1,
            occurrences(reschedule, WATCHDOG_FRESH_ARG),
        )
        assertEquals(
            "**钉法 = 逐字节 + 读形状**：`classOngoing` 必须吃那只已经注入的 `now`" +
                "（`$WATCHDOG_ONGOING_ARG`；实到 " + occurrences(reschedule, WATCHDOG_ONGOING_ARG) +
                " 处）。0 处 = 这一判没人做，下一节课的下课时刻也会替上一节的遗留记录推走恢复点",
            1,
            occurrences(reschedule, WATCHDOG_ONGOING_ARG),
        )
        assertEquals(
            "**钉法 = 数出现次数（负钉）**：无实参的 `ongoingAt()`（吃签名默认值 = 体内现读真钟）" +
                "在这一跳里必须 0 处，否则整次重排就有两只钟。实到 " +
                occurrences(reschedule, "ongoingAt()") + " 处",
            0,
            occurrences(reschedule, "ongoingAt()"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：这一跳仍然只读一次 `LocalDateTime.now()`（第 ① 层那枚数，" +
                "看门狗那一手不许把它变成两枚）。实到 " + occurrences(reschedule, CLOCK_LOCAL_DATE_TIME) + " 处",
            1,
            occurrences(reschedule, CLOCK_LOCAL_DATE_TIME),
        )
    }

    /**
     * 判据与那把尺都只此一份，且全仓只有重排那一跳驱动它。
     *
     * - **数出现次数（全 main）**：`fun watchdogRearmDecision(` 恰一处、
     *   `alignWatchdogForNewFrame` 的调用点恰一处 = 第②处接线（比如直接在
     *   `WidgetRefreshReceiver` 里另起一步）不许长出来 —— 那一侧拿不到这一节课的下课时刻，
     *   要么白跑一遍全量窗口搜索、要么把旧帧的毫秒当新帧用（在册 #603 的病灶就在那儿）；
     * - **数出现次数**：`armWatchdog(` 在 `ClassProgressDnd.kt` 里恰三处 = 定义 + `enter`
     *   （上课铃）+ 新帧那一步，第四处就是有人另写了一遍排程；
     * - **读形状**：判据体内零钟 —— 它的输入只有四枚标量，"现在几点"由调用点折成
     *   `classOngoing` 递进来（本仓成文禁令）。
     */
    @Test
    fun theNewFrameCriterionAndRulerExistInExactlyOnePlace() {
        val dnd = withoutComments(readMainSource(DND_FILE))
        val criterion = balancedBlock(dnd, "internal fun watchdogRearmDecision(")
        val ruler = balancedLineAfter(dnd, "internal fun deadlineOfEnd(endMillis: Long): Long? =")

        assertEquals(
            "**钉法 = 数出现次数（全 main）**：六档判据的本体全仓只许一处，实到 " +
                mainOccurrences("internal fun watchdogRearmDecision(") + " 处",
            1,
            mainOccurrences("internal fun watchdogRearmDecision("),
        )
        assertEquals(
            "**钉法 = 数出现次数（全 main）**：`ClassProgressDnd.alignWatchdogToNewFrame(` 的调用点" +
                "全仓只许一处（就是 rescheduleWindows 那一跳）。实到 " +
                mainOccurrences("ClassProgressDnd.alignWatchdogToNewFrame(") + " 处",
            1,
            mainOccurrences("ClassProgressDnd.alignWatchdogToNewFrame("),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`ClassProgressDnd.kt` 里 `armWatchdog(` 恰三处 = 定义 + enter" +
                "（上课铃那一手）+ 新帧那一步；第四处就是又长了一遍排程。实到 " +
                occurrences(dnd, "armWatchdog(") + " 处",
            3,
            occurrences(dnd, "armWatchdog("),
        )
        assertEquals(
            "**钉法 = 数出现次数**：「下课 + 宽限」的宽限量只许一处（[ClassProgressDnd.deadlineOfEnd]），" +
                "两处各写一遍就是一格旧帧期限与一格新帧期限用不同的余量。实到 " +
                occurrences(dnd, "RESTORE_GRACE_MINUTES * 60_000L") + " 处",
            1,
            occurrences(dnd, "RESTORE_GRACE_MINUTES * 60_000L"),
        )
        for (sneak in listOf(CLOCK_EPOCH_MILLIS, "LocalDateTime.now(", "ongoingAt(", "Date(", "SystemClock.")) {
            assertFalse(
                "**钉法 = 读形状**：六档判据的体里出现「$sneak」= 它开始自己读钟/自己判课堂状态，" +
                    "表驱动那族就量不到它，而调用点那只钟与它读的那只可以在跨秒那一瞬先掉个儿" +
                    "（本守卫第 ① 层的病灶形状）。\n实到体：$criterion",
                criterion.contains(sneak),
            )
        }
        for (sneak in listOf(CLOCK_EPOCH_MILLIS, "SystemClock.", "LocalDateTime.now(")) {
            assertFalse(
                "**钉法 = 读形状**：「下课 + 宽限」那把尺读了钟（$sneak）就不是纯算了 —— " +
                    "兜底那两支各读各的，同一节课的期限在两处能差出一次读数的间隔：\n$ruler",
                ruler.contains(sneak),
            )
        }
    }

    /**
     * 探测器自检（第 ⑤ 层那两枚锚点拼错就永远数到 0 永远绿）。
     *
     * - **朝松**：改前那一形（这一跳根本没有看门狗那一步）喂进来，
     *   `alignWatchdogToNewFrame(` 数到 0 处 —— 与第 ⑤ 层那句「恰一处」互为对照物；
     *   把 `ongoingAt` 写成吃签名默认值那一形喂进来，`ongoingAt()` 必须数到 1 处；
     * - **朝窄**：同一句形状写在 KDoc 里、抹完注释必须 0 处（本文件的注释逐字写着那些名字）。
     */
    @Test
    fun watchdogProbeRecognisesTheTwoBadShapes() {
        val absentHop = "        return schedule(context, window)"
        assertEquals(
            "探测器必须认得出「改前那一手 = 这一跳根本没有看门狗那一步」（实到 " +
                occurrences(absentHop, "ClassProgressDnd.alignWatchdogToNewFrame(") + " 处）",
            0,
            occurrences(absentHop, "ClassProgressDnd.alignWatchdogToNewFrame("),
        )

        val secondClockShape = "        ClassProgressDnd.alignWatchdogToNewFrame(\n" +
            "            context,\n" +
            "            classOngoing = window.ongoingAt(),\n" +
            "            freshDeadlineMillis = ClassProgressDnd.deadlineOfEnd(window.endMillis),\n" +
            "        )"
        assertEquals(
            "探测器必须抓得出「无实参的 ongoingAt()（体内现读第二只钟）」那一形（实到 " +
                occurrences(secondClockShape, "ongoingAt()") + " 处）",
            1,
            occurrences(secondClockShape, "ongoingAt()"),
        )
        assertEquals(
            "同一形里注入的读数确实被丢掉了 ⇒ 第 ⑤ 层那句「逐字节 1 处」的对照物",
            0,
            occurrences(secondClockShape, WATCHDOG_ONGOING_ARG),
        )

        val quotedInKDoc = """
            /**
             * 旧形状登记：`classOngoing = window.ongoingAt(),` 这一形今天不该在排铃链上出现。
             */
            fun noop() = Unit
        """.trimIndent()
        assertEquals(
            "注释里逐字引用的那一形**不许**进第 ⑤ 层的账：未抹注释时确实在原文里（实到 " +
                occurrences(quotedInKDoc, "ongoingAt()") + " 处），抹完必须 0 处（实到 " +
                occurrences(withoutComments(quotedInKDoc), "ongoingAt()") + " 处）",
            0,
            occurrences(withoutComments(quotedInKDoc), "ongoingAt()"),
        )
    }

    // ---------------- 源码核对小工具（与本仓守卫同一套写法）----------------

    /**
     * 取一行式函数（`): Boolean =` 收尾、体在**下一行**）的那一体并 trim。
     * 切不出来就抛 —— 返回空串会让上面那句「0 处」变成空转。
     */
    private fun balancedLineAfter(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        check(at >= 0) { "找不到 $declaration：签名换写法了，本层切不出体" }
        val head = source.substring(at).lines().first()
        check(head.trimEnd().endsWith("=")) {
            "$declaration 那一行不再是 `): Boolean =` 收尾的一行式形状了（实到「$head」）：本层取法要跟着改"
        }
        val body = source.substring(at).lines().drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        check(body.isNotEmpty()) { "$declaration 之后切不出体：本层是空的" }
        return body
    }

    /** 两枚参数那一枚谓词的体（委托那一行） */
    private fun twoArgBody(code: String): String = balancedLineAfter(code, TWO_ARGUMENT_DECLARATION)

    /** `needle(` 之后配平的实参表（含两端）；找不到就抛 —— 静默跳过等于没有守卫 */
    private fun argumentsOf(code: String, needle: String): String {
        val at = code.indexOf(needle)
        check(at >= 0) { "找不到 $needle：调用点被删或被改名，本层是空的" }
        val open = code.indexOf('(', at)
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, index)
                }
            }
        }
        throw IllegalStateException("$needle 的括号没配平：从 $open 起")
    }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSources(): List<Pair<String, String>> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map {
            root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') to
                it.readText().replace("\r\n", "\n")
        }
    }

    private fun mainOccurrences(needle: String): Int =
        mainSources().sumOf { occurrences(withoutComments(it.second), needle) }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 按花括号配平从签名起取整段（含签名） */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：改过名或挪过家就要回来重钉本守卫" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    /**
     * **只抹注释、保留字符串字面量**，长度与换行位置不变（与 CountdownOwnershipAnchorGuardTest
     * 同一把刀）。本卡尤其需要抹注释：那两枚谓词的名字在 main 与本文件的注释里被逐字写了十几遍。
     */
    private fun withoutComments(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val PROGRESS_FILE = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"
        const val RESYNCER_FILE = "com/buaa/schedule/reminder/LiveClassResyncer.kt"
        const val REMINDER_SCHEDULER_FILE = "com/buaa/schedule/reminder/ReminderScheduler.kt"
        const val NOTIFICATIONS_FILE = "com/buaa/schedule/reminder/ReminderNotifications.kt"
        const val DND_FILE = "com/buaa/schedule/reminder/ClassProgressDnd.kt"

        const val CLOCK_EPOCH_MILLIS = "System.currentTimeMillis("
        const val CLOCK_LOCAL_DATE_TIME = "LocalDateTime.now()"
        const val PLAN_OWNERSHIP_READ = "ownsCountdownTo(window.courseId, window.startMillis)"
        const val TWO_CLOCK_SHAPE = "isCountingDownTo(window.courseId, window.startMillis)"
        const val PLAN_TIME_JUDGEMENT = "window.startsAfter(now.toEpochMillis())"
        const val CLASS_START_CALL = "ReminderNotifications.classStartOf(window),"
        const val SEAM_WIRING = "countingDownTo = ReminderNotifications::ownsCountdownTo"

        /** #603 那一跳的两枚实参（逐字节钉）：期限走唯一的宽限尺、状态吃那只已经注入的钟 */
        const val WATCHDOG_FRESH_ARG = "freshDeadlineMillis = ClassProgressDnd.deadlineOfEnd(window.endMillis),"
        const val WATCHDOG_ONGOING_ARG = "classOngoing = window.ongoingAt(now.toEpochMillis()),"

        const val TWO_ARGUMENT_DECLARATION =
            "fun isCountingDownTo(courseId: Long, classStartMillis: Long): Boolean ="
        const val DELEGATING_BODY =
            "isCountingDownTo(courseId, classStartMillis, System.currentTimeMillis())"
        const val INJECTED_KERNEL =
            "fun isCountingDownTo(courseId: Long, classStartMillis: Long, nowMillis: Long): Boolean ="
        const val OWNERSHIP_DECLARATION =
            "internal fun ownsCountdownTo(courseId: Long, classStartMillis: Long): Boolean ="
        const val OWNERSHIP_BODY =
            "countdownCourseId == courseId && countdownClassStart == classStartMillis"
    }
}
