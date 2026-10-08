package com.buaa.schedule.ui.onboarding

import com.buaa.schedule.domain.onboarding.OnboardingGesture
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T271④：首启引导「同意路径」的接线守卫 —— 四处出口**各自带走哪一手**，钉到源码形状上。
 *
 * 钉的四法（逐条点名，报错都带文件与判据，不写行号）：
 *
 * - **逐字节**（④ 那一段）：常驻页那一手的两枚字面必须一字不差地还在 ——
 *   `FirstRun.acceptPrivacy(context)` 与 `Text("同意隐私说明")`。
 *   它们就是第 ⓪ 问丁档里「乙」的那一枚按钮；删掉它，跳过引导的用户重新无处同意，
 *   本卡整族复发。同一段还拿这枚按钮的**在册旗**
 *   [PERMANENT_PRIVACY_CONSENT_ENTRY_AVAILABLE] 的字面值与它互相对账：
 *   按钮没了而旗还写着 true ⇒ 红；按钮在而旗被翻成 false ⇒ 也红（判据吃的是旗，
 *   旗说谎就等于把 PathToPermanentEntry 那一档换成 PathSevered）。
 *   T280③ 又添三枚逐字节锚点：payload 那一行本身、递进内核的 `consentStamp = consentAt,`
 *   与 `handLandedOnThisScreen =` —— 「正文吃 payload」这件事从此写在盘面上而不只在注释里。
 * - **抹注释找锚点**（② ③ ⑦ 段）：先把注释抹平再找锚点，钉的是代码而不是白话 ——
 *   `fun recordOnboardingGesture(` 那一体的配平块里必须同时有判据调用与两枚**带闸门**的落盘位，
 *   而且 `OnboardingHeader(` 那一体内必须出现 `recordOnboardingGesture(OnboardingGesture.HeaderSkip)`。
 *   后者就是「跳过那一手」的名字住在哪一层：把它摘掉，跳过又变回只写引导完成位的单向门。
 *   ⑦ 段用同一把刀切出「数据与隐私那层窗口」，在窗口里找 `privacyConsentAt` ——
 *   只许剩归零那一枚，`FirstRun.privacyConsentAt(` 一枚都不许多（那就是 T271③ 的乙档）。
 * - **数出现次数**（① 段 + ⑤ 段的枚数册子）：`recordOnboardingGesture(` 恰 5 枚、
 *   `FirstRun.completeOnboarding(context)` 恰 **1** 枚（住在闸门里）、
 *   `FirstRun.acceptPrivacy(context)` 在 `ui/onboarding/` 恰 1 枚、全 main 恰 2 枚。
 *   T280③ 把 ⑥ 那族两把尺的现值一并记进 ⑤ 的册子：`privacyConsentAt` 4 枚生命点、
 *   `showPrivacyDialog` 6 枚、`showPrivacyDialog = false` 3 处、`consentAt > 0L` 2 处 ——
 *   与 `CalendarSyncTargetPairingGuardTest` 同数，两处一起拦，谁也不给「重钉上去」留位置。
 * - **读形状**（① 段 + ⑥ ⑦ 段）：五枚调用点交出的 `OnboardingGesture` 必须与内核在册的五枚
 *   **一一对齐**（缺枚、重复、多一枚都算漂），并且落「引导已完成」的那四枚
 *   （`OnboardingGesture.writesOnboardingDone`）必须与内核的册子逐名相等；
 *   ⑦ 段还读三枚形状：撤回那一档的「归零 → 关窗 → 换那枚内存事实」次序（连号那两行不许被拆开）、
 *   正文那一格只由一枚 `residentDialogCopy(` 交话（两枚 = 两把尺各判一次档），
 *   以及窗口里不许出现 `privacyNotice` / `screenNotice(`（行上那句由本屏现读状态派生的提示
 *   不许被搬进弹窗 —— 读了就会在收场动画那几帧里当场翻面）。
 *
 * 为什么要这么钉：基点上 `completeOnboarding` 四枚裸调用、`acceptPrivacy` 一枚裸调用，
 * 枚数差本身**不是**缺陷（引导完成与同意是两扇不同的门，`core/FirstRun.kt` 的文件头写着这条），
 * 所以本守卫不许写成「枚数差即红」那种恒真判据。它钉的是形状：
 * 四枚落完成位的手必须各自点名自己的那一枚 [OnboardingGesture]，
 * 而带同意的那一手必须有 `writesConsent` 闸门在前 ——
 * 把四枚裸调用抄回来 ⇒ ① 的 5 枚册子红、③ 的闸门红、④ 的常驻页那一手还在但引导里没人落同意位，
 * 于是 ② 的 `OnboardingHeader` 锚点红。三处合起来才是「哪几枚出口各自带走哪一手」。
 *
 * 不跳过、不 assumeTrue：找不到文件、找不到锚点一律抛，静默通过等于没有守卫。
 */
class OnboardingConsentPathWiringGuardTest {

    /** ①【读形状 + 数出现次数】五枚出口各点名一次，且点名的正是内核在册的那五枚。 */
    @Test
    fun everyOnboardingHandGoesThroughTheGateByItsOwnName() {
        val text = blankComments(onboarding())
        val callSites = Regex("""recordOnboardingGesture\(\s*OnboardingGesture\.(\w+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "$ONBOARDING 里 recordOnboardingGesture( 的调用处数应当恰好 5 枚" +
                "（跳过 / 隐私步下一步 / 导入落点 / 手动添加落点 / 末步落首页），实到 ${callSites.size} 枚：\n" +
                callSites.joinToString(),
            5,
            callSites.size,
        )
        assertEquals(
            "$ONBOARDING 交出的出口名册与内核 OnboardingGesture 的在册册子对不上 —— " +
                "哪一枚出口漏了判据，或新加了一枚没在册的出口：\n实到 " + callSites.sorted().joinToString(),
            OnboardingGesture.entries.map { it.name }.sorted(),
            callSites.sorted(),
        )
        assertTrue(
            "$ONBOARDING 里同一枚出口被点名了两遍（两处出口共用一枚枚举 = 其中一处不知道自己是谁）：" +
                callSites.groupingBy { it }.eachCount().filterValues { it > 1 }.keys,
            callSites.distinct().size == callSites.size,
        )
    }

    /**
     * ②【抹注释找锚点】跳过那一手（顶栏）必须走过判据：`OnboardingHeader(` 那一体里点名 HeaderSkip。
     *
     * 这一格就是本卡的题眼。基点上那一处是裸的 `FirstRun.completeOnboarding(context)`，
     * 于是「先跳过，稍后设置」把引导那扇门永久关上而不同意位不落 ——
     * 把裸调用抄回来，这一格先红。
     */
    @Test
    fun theSkipHandCarriesTheJudgment() {
        val header = balancedBlock(onboarding(), "OnboardingHeader(")
        assertTrue(
            "OnboardingHeader( 那一体里找不到 recordOnboardingGesture(OnboardingGesture.HeaderSkip) —— " +
                "$ONBOARDING 的顶栏出口又变回只写「引导已完成」的那扇单向门：\n$header",
            header.contains("recordOnboardingGesture(OnboardingGesture.HeaderSkip)"),
        )
    }

    /**
     * ③【抹注释找锚点 + 读形状】落盘的两枚位都必须吃判据交下来的闸门，且同意闸门排在完成闸门之前。
     *
     * 「四枚出口都写完成位而其中没有同意路径」这一形在这里被拦住：完成位全仓只剩一枚裸调用，
     * 而且它前面必须有 `disposition.writesConsent` 那一行 —— 顺序即语义：
     * 先答「这一手带不带用户的同意」，再答「引导算不算看完」。
     */
    @Test
    fun theGateBodyWritesBothBitsBehindTheirOwnConditions() {
        val body = balancedBlock(onboarding(), "fun recordOnboardingGesture(gesture: OnboardingGesture)")
        val dispose = body.indexOf("OnboardingConsentGate.gestureDisposition(")
        val consentWrite = body.indexOf("if (disposition.writesConsent) FirstRun.acceptPrivacy(context)")
        val doneWrite = body.indexOf("if (disposition.writesOnboardingDone) FirstRun.completeOnboarding(context)")
        assertTrue(
            "recordOnboardingGesture 的体里没调 OnboardingConsentGate.gestureDisposition —— " +
                "$ONBOARDING 自己在落盘，判据被绕开了：\n$body",
            dispose >= 0,
        )
        assertTrue(
            "同意位的落盘必须写成 `if (disposition.writesConsent) FirstRun.acceptPrivacy(context)`：" +
                "写成裸调用就是替用户同意（全仓任何一处都不许），写成别的样子守卫读不到它：\n$body",
            consentWrite >= 0,
        )
        assertTrue(
            "「引导已完成」的落盘必须写成 `if (disposition.writesOnboardingDone) FirstRun.completeOnboarding(context)`：" +
                "裸调用就是四枚出口都不带同意路径那一形：\n$body",
            doneWrite >= 0,
        )
        assertTrue(
            "闸门顺序要紧：同意位（writesConsent）要排在完成位（writesOnboardingDone）之前，" +
                "否则跳过那一手先把门关上，同意位再落也没人读了：\n$body",
            dispose < consentWrite && consentWrite < doneWrite,
        )
        assertTrue(
            "五手里至少一枚要把判据交下来的话留进日志（HyperOS 截在 Info 级 ⇒ Log.i/Log.w，不写 Log.d）：" +
                "\n$body",
            body.contains("Log.w(") && body.contains("Log.i(") && !body.contains("Log.d("),
        )
    }

    /**
     * ④【逐字节】同意入口不依赖引导页可达：常驻页那一格一字不差地还在，且与在册旗对得上账。
     *
     * 第 ⓪ 问丁档的「乙」就住在这一格。以前那枚弹窗的 `confirmButton = {}` 只有「撤回」，
     * 没同意过的人在这页上什么也做不了 —— 所以这一体里现在必须有点名「同意隐私说明」的那一手。
     *
     * T280③ 在这一段添的三枚**逐字节**锚点：正文那一句的档位判断换了载体（从内联的 `if (consentAt > 0L)`
     * 文案换成内核交回的话），于是「payload 交回来的那一位被原样递进内核」这件事本身必须一字不差地钉住 ——
     * payload 那一行一枚、`consentStamp = consentAt,` 一枚、`handLandedOnThisScreen =` 一枚。
     */
    @Test
    fun thePermanentScreenStillOffersAConsentHand() {
        val settings = blankComments(settings())
        assertEquals(
            "【逐字节】$SETTINGS 里 FirstRun.acceptPrivacy(context) 应当恰好一处（数据与隐私弹窗的确认位），" +
                "实到 " + occurrences(settings, "FirstRun.acceptPrivacy(context)") + " 处",
            1,
            occurrences(settings, "FirstRun.acceptPrivacy(context)"),
        )
        assertEquals(
            "【逐字节】$SETTINGS 里 Text(\"同意隐私说明\") 应当恰好一处（确认位那颗按钮的字面），实到 " +
                occurrences(settings, "Text(\"同意隐私说明\")") + " 处 —— 少一处就是同意入口又搬回引导页了",
            1,
            occurrences(settings, "Text(\"同意隐私说明\")"),
        )
        val dialog = balancedBlock(settings, "payload = if (showPrivacyDialog) privacyConsentAt else null")
        assertTrue(
            "【逐字节】数据与隐私那层弹窗的确认位必须真的给出同意那一手（改前是 confirmButton = {}，只有撤回）：\n" +
                dialog.take(dialog.length.coerceAtMost(400)),
            dialog.contains("confirmButton = {") && !dialog.contains("confirmButton = {},"),
        )
        assertEquals(
            "【逐字节】payload 那一行是这枚活字段全仓唯一的读者，写法一个字都不许改：" +
                "它一改（例如换成 open 驱动、或把 payload 换成布尔），" +
                "收场动画那几帧里正文读到的就是翻过面的值",
            1,
            occurrences(settings, PAYLOAD_GATE),
        )
        assertEquals(
            "【逐字节】弹窗正文那一句的档位必须由 payload 交回的那一位值喂进内核（`consentStamp = consentAt,` " +
                "一枚）—— 实到 " + occurrences(dialog, "consentStamp = consentAt,") +
                " 处。递进去的是别的载体（活字段、行上那句提示）就不叫 payload 驱动了",
            1,
            occurrences(dialog, "consentStamp = consentAt,"),
        )
        assertEquals(
            "【逐字节】内核那一档的第三枚入参（用户在本屏按下的那一手）必须写出来，" +
                "否则 payload 为 0 的那一侧永远念「还没按下」，把用户自己的手吞掉：实到 " +
                occurrences(dialog, "handLandedOnThisScreen =") + " 处",
            1,
            occurrences(dialog, "handLandedOnThisScreen ="),
        )
        // 在册旗与那一枚按钮互相对账：判据吃的就是这枚旗，旗与按钮不一致 = 交回的档位在读谎
        val acceptHandOnThePermanentScreen =
            occurrences(settings, "FirstRun.acceptPrivacy(context)") == 1
        assertEquals(
            "【逐字节】旗与按钮必须同一侧：$SETTINGS 有 FirstRun.acceptPrivacy(context) 一枚 ⇒ " +
                "acceptHand=$acceptHandOnThePermanentScreen，而 $ONBOARDING 里 " +
                "PERMANENT_PRIVACY_CONSENT_ENTRY_AVAILABLE = $flagLiteral。" +
                "按钮被删而旗还写着 true，判据就承诺一枚盘面上没有的按钮；" +
                "按钮还在而旗被翻成 false，每一手跳过都会被念成死路",
            acceptHandOnThePermanentScreen,
            flagLiteral,
        )
    }

    /**
     * ⑤【数出现次数】丙档的持续提示接线 + ⑥ 那族两把尺的现值（一枚读者、三处关窗）。
     *
     * T280③ 在这一段添的册子就是题眼：T271③ 之所以红两格，是因为它把「弹窗正文 / 行上提示」改成读
     * 那枚活字段，于是 `privacyConsentAt` 从 4 枚生命点涨到 8、`showPrivacyDialog` 从 6 涨到 7。
     * 本卡**不把尺往上重钉**，改的是读法 —— 于是这几枚数同时是「读法已经改回来」的凭据：
     * 谁再往正文或提示里塞一枚活字段读者，这一格先红（不用等 `CalendarSyncTargetPairingGuardTest`）。
     */
    @Test
    fun theUnconsentedStateIsSaidOnThePermanentScreen() {
        val settings = blankComments(settings())
        val ledger = listOf(
            "OnboardingConsentGate.screenNotice(" to 1,
            "privacyNotice.showsMark" to 1,
            "privacyRowSummary(privacyNotice)" to 1,
            "PrivacyConsentMissingDot()" to 2,
            // 行上那一句持续提示的同意状态来源：一枚布尔、一处现读（不许改成回读那枚成对字段）
            "FirstRun.privacyAccepted(context)" to 1,
            // ---- ⑥ 那族两把尺的现值（与 CalendarSyncTargetPairingGuardTest 同数，两处一起拦）----
            "privacyConsentAt" to 4,
            "showPrivacyDialog" to 6,
            "showPrivacyDialog = false" to 3,
            "showPrivacyDialog = true" to 1,
            "privacyConsentAt = 0L" to 1,
            "consentAt > 0L" to 2,
            "residentDialogCopy(" to 1,
            "consentHandOnThisScreen = ResidentConsentHand.Accepted" to 1,
            "consentHandOnThisScreen = ResidentConsentHand.Revoked" to 1,
        )
        for ((anchor, expected) in ledger) {
            val actual = occurrences(settings, anchor)
            assertEquals(
                "【数出现次数】$SETTINGS 里 $anchor 应当 $expected 处（丙档的持续提示接线，以及 ⑥ 那对" +
                    "成位字段「一枚读者、三处关窗」的尺），实到 $actual 处 —— " +
                    "多一处就是有人又给这枚成对字段添了读者（正文或提示回读活字段，" +
                    "部分清掉的值会被当有效念出来），少一处就是那一枚红点、那一句提示或那一处归零被摘掉了",
                expected,
                actual,
            )
        }
        assertEquals(
            "【数出现次数】$ONBOARDING 里 recordOnboardingGesture( 的枚数（5 处调用 + 1 处定义）应当是 6，实到 " +
                occurrences(blankComments(onboarding()), "recordOnboardingGesture("),
            6,
            occurrences(blankComments(onboarding()), "recordOnboardingGesture("),
        )
    }

    /**
     * ⑦【抹注释找锚点 + 读形状】弹窗正文只吃 payload：那一层窗口里不许出现第二枚活字段读者，
     * 也不许出现行上那句由活字段派生的提示。
     *
     * 这是 T280 这一刀的正面判据。T271③ 的形状是「正文念 `privacyNotice.line`（由 `remember(活字段)`
     * 派生）+ 确认位再写一遍那枚成对字段」—— 前者在窗口里读了一枚会翻面的值，
     * 后者给那一对添了第二枚读者与第四枚生命点。裁的是甲：**payload 一次算好交回来，
     * 正文那几句都由它决定**（加上「用户在本屏按下的那一手」这一枚内存事实 —— 它不是那枚成对字段，
     * 也永远不许被当成同意时间的替身）。
     */
    @Test
    fun theDialogBodyIsFedByThePayloadAndReadsNoLiveField() {
        val settings = blankComments(settings())
        val dialog = balancedBlock(settings, "payload = if (showPrivacyDialog) privacyConsentAt else null")
        // ---- 抹注释找锚点：窗口里 privacyConsentAt 只剩那一枚写点 ----
        assertEquals(
            "【抹注释找锚点】数据与隐私那层窗口里 `privacyConsentAt` 出现 " +
                occurrences(dialog, "privacyConsentAt") + " 处，判据是 1 处 —— 而那唯一一处必须是归零那次写。" +
                "多出来的那一处是读者（正文回读活字段）：收场动画那几帧里 payload 还冻着旧值而正文已经翻面，" +
                "半清掉的同意时间就此被念成有效（`CalendarSyncTargetPairingGuardTest` ⑥ 拦的就是这一形）",
            1,
            occurrences(dialog, "privacyConsentAt"),
        )
        assertEquals(
            "【抹注释找锚点】窗口里那一处 `privacyConsentAt` 不再是归零那次写（`privacyConsentAt = 0L` 一枚）：" +
                "它变成读取的这一刻起，这一对要按「读者在不在闸门里」重判",
            1,
            occurrences(dialog, "privacyConsentAt = 0L"),
        )
        assertFalse(
            "【抹注释找锚点】窗口里出现了 `FirstRun.privacyConsentAt(` —— 那是绕过 payload 现读盘上那一位，" +
                "等于给这枚成对字段添第二枚读者（T271③ 的乙档那一形）：\n" + dialog.take(300),
            dialog.contains("FirstRun.privacyConsentAt("),
        )
        assertFalse(
            "【读形状】窗口里念了行上那句 `privacyNotice` —— 那句话是常驻行（弹窗之外）的持续提示档，" +
                "由本屏现读的状态派生；把它搬进正文就是「组合期内回读一枚会翻面的值」那一形，" +
                "正文只许吃 payload 交回来的那一位：\n" + dialog.take(300),
            dialog.contains("privacyNotice"),
        )
        assertFalse(
            "【读形状】窗口里出现了 `screenNotice(` —— 弹窗正文的档位归 `residentDialogCopy` 说，" +
                "行上那句提示不许在弹窗里再算一遍（两把尺读同一件事，谁先落地就没法判了）",
            dialog.contains("screenNotice("),
        )
        // ---- 读形状：确认位那一手落的是「那只手」，不是那枚成对字段 ----
        val acceptedHandAt = dialog.indexOf("consentHandOnThisScreen = ResidentConsentHand.Accepted")
        check(acceptedHandAt >= 0) {
            "【读形状】确认位那一手没有把「用户在本屏按下了同意」写进那枚内存事实 —— " +
                "正文与行上的提示就都没人负责刷新，按下之后屏上还写着「还没同意」"
        }
        assertTrue(
            "【读形状】`FirstRun.acceptPrivacy(context)` 之后落的那一手必须紧跟在那一格里" +
                "（顺序即语义：先落盘、再改档），实到顺序颠倒了",
            dialog.indexOf("FirstRun.acceptPrivacy(context)") < acceptedHandAt,
        )
        val revokedHandAt = dialog.indexOf("consentHandOnThisScreen = ResidentConsentHand.Revoked")
        val zeroWriteAt = dialog.indexOf("privacyConsentAt = 0L")
        val closeAt = dialog.indexOf("showPrivacyDialog = false")
        val revokeAt = dialog.indexOf("FirstRun.revokePrivacy(context)")
        check(revokedHandAt >= 0 && zeroWriteAt >= 0 && closeAt >= 0 && revokeAt >= 0) {
            "【读形状】撤回那一档的位缺一枚：撤偏好、归零、关窗、换那枚内存事实，实到 " +
                "$revokeAt / $zeroWriteAt / $closeAt / $revokedHandAt"
        }
        assertTrue(
            "【读形状】撤回那一档的连号形状漂了：必须是「归零 → 关窗」相邻（⑥ 那对成位字段在册的连号写），" +
                "而那一手的换档排在关窗**之后** —— 排在中间就把连号那两行拆开了，" +
                "`CalendarSyncTargetPairingGuardTest` 的「三处关窗里恰好一处连号归零」当场红",
            revokeAt < zeroWriteAt && zeroWriteAt < closeAt && closeAt < revokedHandAt,
        )
        // ---- 读形状：整段正文里递给内核的那枚状态载体只有 payload 交回的那一位 ----
        assertEquals(
            "【读形状】正文那一句「当前」整段只吃一枚 payload：`residentDialogCopy(` 在窗口里应当恰好一枚，" +
                "实到 " + occurrences(dialog, "residentDialogCopy(") + " 处 —— 两处就是两把尺各判一次档位",
            1,
            occurrences(dialog, "residentDialogCopy("),
        )
    }

    /**
     * ⑥【读形状】全 main 的同意位只有两枚落点：引导里的闸门 + 常驻页的确认位。
     * 多一枚就是有人在自己那儿替用户置真；少一枚就是同意入口又只剩一条不可达的路。
     */
    @Test
    fun consentWritesStayTwoAcrossMainSources() {
        val hits = mainJavaDir().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(mainJavaDir()).path.replace(File.separatorChar, '/') to it.readText() }
            .filter { (_, text) -> blankComments(text).contains("FirstRun.acceptPrivacy(context)") }
            .toList()
        assertEquals(
            "全 app/src/main/java 里 FirstRun.acceptPrivacy(context) 的落点册子应当是两枚" +
                "（引导闸门 + 常驻页确认位），实到 ${hits.size} 枚：\n" +
                hits.joinToString { (path, _) -> path },
            2,
            hits.size,
        )
        assertEquals(
            "同意位的两枚落点必须一枚在引导、一枚在常驻页（同一处写两遍 = 有一处没吃闸门）：\n" +
                hits.joinToString { (path, _) -> path },
            listOf(ONBOARDING, SETTINGS),
            hits.map { (path, _) -> path }.sorted(),
        )
    }

    // ---- helpers ----

    /** 在册旗的字面值（读源码而不是读常量：常量编译进产物，这里要的是盘上那一行写了什么） */
    private val flagLiteral: Boolean
        get() {
            val match = Regex("""PERMANENT_PRIVACY_CONSENT_ENTRY_AVAILABLE\s*=\s*(true|false)""")
                .find(blankComments(onboarding()))
            check(match != null) {
                "$ONBOARDING 里找不到 PERMANENT_PRIVACY_CONSENT_ENTRY_AVAILABLE = true/false 那一行：" +
                    "这枚旗是判据的输入，挪走了就要把守卫一起改"
            }
            return match.groupValues[1] == "true"
        }

    private fun occurrences(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    private fun onboarding(): String = source(ONBOARDING)

    private fun settings(): String = source(SETTINGS)

    private fun source(relative: String): String {
        val file = File(mainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun mainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(source: String, signature: String): String {
        val text = blankComments(source)
        val at = text.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = text.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
    private fun blankComments(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
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

    private companion object {
        const val ONBOARDING = "com/buaa/schedule/ui/onboarding/OnboardingScreen.kt"
        const val SETTINGS = "com/buaa/schedule/ui/settings/SettingsScreen.kt"

        /** 那枚成对字段全仓唯一的读者 —— 逐字节钉住它，换成 open 驱动就红 */
        const val PAYLOAD_GATE =
            "ModalTransition(payload = if (showPrivacyDialog) privacyConsentAt else null) { consentAt, modal ->"
    }
}
