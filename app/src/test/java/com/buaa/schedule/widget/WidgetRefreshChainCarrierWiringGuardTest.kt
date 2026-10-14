package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T366③ 接线守卫：钉「时间／跨天那条广播的链，读表载体只在头顶取一次、四枚消费者各自具名递进去」
 * （台账 #218 ＋在册 #536 F2 同族的第二处落点；裁的是甲档，⓪ commit 里有逐路账）。
 *
 * ## 病（为什么形状也要钉）
 * 那条链改前有四枚裸调（组件全量刷新两枚 + 提醒铃重排 + 明日预告），各自回库查同一批表：
 * 改时间那一趟满档读学期行 4 遍、节次表 4 遍、展示口径的课程 3+K 遍、提醒册子 4 遍。
 * 接线改好之后，**改坏它在盘上是一个字节的事**（把某一枚 `snapshot = chainSnapshot` 摘掉，
 * 或把头顶那一行复制成第二枚），而它跑不跑得到要设备：广播投递、唤醒锁、Room 全在 JVM 单测之外
 * （本模块没有 Robolectric）。所以这里按源码形状核对，判据本体（取／不取的分档）是纯的，
 * 钉在 `domain/widget/RefreshChainCarrierGateTest`。
 *
 * ## 六层判据（每层都点名红了去改哪一条链）
 * ① [carrierIsBuiltExactlyOnceAndUpstreamOfEveryConsumer] —— 头顶那一枚 `readChainSnapshotOrNull(`
 *    代码栏恰 1 枚（两枚就是同一批表又被满读一遍，本卡的病原样回潮），而且排在
 *    `WakeLocks.withPartialWakeLock(` 之后、排在第一枚吃载体的调用之前（锁内取：这一手本身是查库）。
 * ② [everyConsumerNamesTheCarrierArgument] —— 具名实参册子逐字节：组件那形 2 枚（两枚放行档共一支
 *    之外还有打脸那一档）、提醒铃重排 1 枚、明日预告 1 枚 ⇒ 合计 4 枚，一格不许少。
 * ③ [theOnlyBareCallIsTheOneWithoutACarrierParameter] —— 「不带 snapshot 实参的 BackgroundSync 调用」
 *    代码栏恰 $EXPECTED_BARE_IN_CODE 枚，逐枚点名它是 `scheduleWidgetMidnight(context)`：它**不算漏接**，
 *    因为签名里根本没有载体形参（BackgroundSync 裸 :434 那一枚只有 context 与 hasAnyWidget），
 *    而它体内一趟表都不读（读的是 LocalDate.now + 跨 binder 探测 + AlarmManager）⇒ 没东西可递。
 *    含注释原文栏恰 $EXPECTED_BARE_IN_RAW 枚，多的那一枚就是取证残留那一行（同一格里点名它）。
 * ④ [theForensicCommentIsCountedSeparately] —— 改前那一手 `BackgroundSync.refreshWidgets(context)`
 *    （带右括号那一形）代码里 **0** 处、含注释恰好 **1** 处：这一格同时是「抹注释这一步真的在挡散文
 *    假阳性」的反证，也是卡面那条硬要求（尺要把代码里与注释里分开数）的实现。
 * ⑤ [theCarrierTypeStaysUnnamedAtTheCallSiteAndTheGateIsDispatchedByTier] —— 本文件不许写出
 *    `ChainReadSnapshot` 那枚类型名（同包取载体不需要它，而全仓"引用载体的文件册"是在册六枚的等式）；
 *    头顶那一手只按档位分派：三枚档名各恰 1 处、`else ->` 0 处（新增一档时这里必须跟着表态）。
 * ⑥ [thePolicyKernelStaysPureJvm] —— 内核那一侧：文件长在 domain/ 下，抹注释后
 *    `android` / `androidx` / `Context` / `Build.VERSION` / `LocalDate.now` / `SystemClock` /
 *    `scheduleRepository` 各 0 枚，而裸原文栏读得到 KDoc 里那些转述 ⇒ 那些 0 不是抹出来的。
 *
 * ## 复算这几把尺（命令写成方括号类是故意的：一个字都不匹配本文件）
 * - 代码栏裸调：`grep -nE 'BackgroundSync[.][A-Za-z]+[(]' app/src/main/java/com/buaa/schedule/widget/WidgetRefreshReceiver.kt`
 *   逐枚回读实参表 ⇒ 带 snapshot 的 4 枚、不带的 1 枚（零点续排）；原文栏再多了取证那一枚 = 2 枚不带。
 * - 载体枚数：`grep -o 'readChainSnapshotOrNull[(]' …WidgetRefreshReceiver.kt | wc -l` → 1
 *
 * ## 覆盖面边界（如实写）
 * - 只读工作树里那两枚 `.kt`（`app/build.gradle.kts` 的守卫输入面已声明，T92①）；
 *   零 android import、不起 Robolectric、零时钟、零设备。
 * - 本守卫**故意不用**「字符数组＋包成字符串」那一族助手写法：那四枚写死值
 *   （CommentStrippingHelperShapeGuardTest 的 139／139／1／140）会被新增一枚同族助手顶高，
 *   而过滤门禁盖不住它、只有全量才暴露 ⇒ 抹注释走逐行扫 + buildString 拼串
 *   （先例：domain/schedule/WeekdayDateOffsetSingleRulerGuardTest 里的 withoutStrings）。
 *   代价：那份文本保留换行位置（报错消息里的行号可信），不保留列位与行长 —— 本守卫只数枚数、不比列。
 * - 第 ③ 层数的是**实参表里没有 `snapshot` 这几个字符**，不是"实参表逐字节等于在册那一份"：
 *   将来给零点续排真加了载体形参，这一格会跟着少一枚（要回来重钉期望值并复算那一步吃不吃载体）。
 * - 本卡不收 WidgetFallbackWorker 那条链（乙档，⓪ 判否：那条链的 :45 在应用内提醒那一档省 0、
 *   在日历模式那一档白付，而且是 12 小时一轮的常驻轮询）。
 */
class WidgetRefreshChainCarrierWiringGuardTest {

    // ─────────────── ① 载体只取一次，而且排在吃它的每一步之前、锁之内 ───────────────

    @Test
    fun carrierIsBuiltExactlyOnceAndUpstreamOfEveryConsumer() {
        val raw = readMainSource(RECEIVER)
        val code = withoutComments(raw)

        assertEquals(
            "**钉法 = 数出现次数（代码栏，抹注释）**：$RECEIVER 里「$CARRIER_READ」必须恰 1 枚 —— " +
                "一枚链只读一遍。实到 " + occurrences(code, CARRIER_READ) + " 枚：0 枚就是头顶那一手被摘了" +
                "（四枚调用点全部退回各自回库，台账 #218 那笔账原样回来）；2 枚就是同一批表又被满读了一遍" +
                "（把载体复制成第二枚 = 本卡病没修而多付一趟）",
            1,
            occurrences(code, CARRIER_READ),
        )
        assertEquals(
            "**钉法 = 数出现次数（含注释原文栏）**：同一枚针在原文里也是 $EXPECTED_CARRIER_READ_RAW 枚 —— " +
                "取证注释里不许另写一枚它的转述，否则上面那个 1 会把注释也算进载体枚数。实到 " +
                occurrences(raw, CARRIER_READ) + " 枚",
            EXPECTED_CARRIER_READ_RAW,
            occurrences(raw, CARRIER_READ),
        )
        assertEquals(
            "**钉法 = 数出现次数**：$RECEIVER 里判据入口「$GATE_CALL」恰 1 枚（取不取只问一遍）。实到 " +
                occurrences(code, GATE_CALL) + " 枚",
            1,
            occurrences(code, GATE_CALL),
        )

        val lock = code.indexOf(WAKE_LOCK_ANCHOR)
        val built = code.indexOf(CARRIER_BUILD)
        val firstConsumer = code.indexOf(REFRESH_WITH_CARRIER)
        assertTrue(
            "**钉法 = 读形状（顺序即语义）**：取载体那一手必须排在唤醒锁**里面**（实到锁 " + lock +
                " 对取载体 " + built + "）。这一手本身就是一趟查库，而 goAsync 只保证进程存活、" +
                "不保证 CPU 醒着 —— 挪到锁外就是让整条链第一趟查询没有唤醒保证（WakeLocks 类注释 §2.5" +
                " 数的那个静默降级）",
            lock in 0 until built,
        )
        assertTrue(
            "**钉法 = 读形状（顺序）**：取载体必须排在第一枚吃载体的调用之前（实到 " + built + " 对 " +
                firstConsumer + "）—— 排在后面就是那一步先自己读了一遍，头顶这一枚成了第二遍",
            built in 0 until firstConsumer,
        )
        assertTrue(
            "两枚锚点都得在场（改名/挪家要回来重钉本守卫）：实到锁 $lock 与载体 $built",
            lock >= 0 && built >= 0,
        )
    }

    // ─────────────── ② 四枚消费者各自具名递进去 ───────────────

    @Test
    fun everyConsumerNamesTheCarrierArgument() {
        val code = withoutComments(readMainSource(RECEIVER))
        for ((shape, expected) in listOf(
            REFRESH_WITH_CARRIER to 2,
            RESCHEDULE_WITH_CARRIER to 1,
            PREVIEW_WITH_CARRIER to 1,
        )) {
            assertEquals(
                "**钉法 = 逐字节（抹注释后）**：$RECEIVER 里「$shape」恰 $expected 处 —— " +
                    "两枚放行档共一支之外还有哨兵打脸那一档照刷，所以组件那一形是 2 枚。实到 " +
                    occurrences(code, shape) + " 处。少一枚 = 那一步又各自回库（0/4 那笔账又长回来）；" +
                    "多一枚 = 同一枚步骤在两处各递一遍，链上就有两份口径",
                expected,
                occurrences(code, shape),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：$RECEIVER 代码栏「snapshot = chainSnapshot」合计恰 $EXPECTED_CARRIER_ARGS 枚" +
                "（就是上面那三形之和）。实到 " + occurrences(code, CARRIER_ARGUMENT) + " 枚 —— " +
                "这一格与上面三格互相印证：某一形被换个写法（位置实参、换成别的变量名）时这一格先对不上",
            EXPECTED_CARRIER_ARGS,
            occurrences(code, CARRIER_ARGUMENT),
        )
        for (shape in listOf(
            "hasReminderChainStep = timeChanged || exactAlarmPermissionChanged",
            "hasPreviewStep = timeChanged",
            "hasWidgetRefreshStep = action == ACTION_MIDNIGHT_REFRESH || timeChanged",
        )) {
            assertEquals(
                "**钉法 = 逐字节**：递进判据的那三枚实参「$shape」各恰 1 处。实到 " +
                    occurrences(code, shape) + " 处 —— 换一枚事实进去（比如把 hasPreviewStep 递成常量）" +
                "就是判据与链脱钩，表驱动那八格从此数不到盘上真跑的档位",
                1,
                occurrences(code, shape),
            )
        }
    }

    // ─────────────── ③ 唯一的裸调：零点续排那一枚（它没有载体形参） ───────────────

    @Test
    fun theOnlyBareCallIsTheOneWithoutACarrierParameter() {
        val raw = readMainSource(RECEIVER)
        val code = withoutComments(raw)
        val bareInCode = callTexts(code).filterNot { it.contains(CARRIER_ARGUMENT) }.sorted()
        val bareInRaw = callTexts(raw).filterNot { it.contains(CARRIER_ARGUMENT) }.sorted()

        assertEquals(
            "**钉法 = 读形状（实参表配平后逐枚点名，抹注释）**：$RECEIVER 里「不带 snapshot 实参的 " +
                "BackgroundSync. 调用」必须恰 $EXPECTED_BARE_IN_CODE 枚。实到 " + bareInCode.size + " 处：\n" +
                bareInCode.joinToString(separator = "") { "  $it\n" } +
                "—— 多出来的一枚就是又有一步没吃头顶那份载体（台账 #218 那一族的原形）；" +
                "少掉一枚就是零点续排也被硬塞了实参（它签名里根本没有那一枚，塞进去编译都过不去）。" +
                "红了先回来复算那一步吃不吃载体，别把期望值改大",
            listOf(MIDNIGHT_BARE_CALL),
            bareInCode,
        )
        assertEquals(
            "**钉法 = 两栏各钉绝对值**：同一把尺吃含注释原文栏时恰 $EXPECTED_BARE_IN_RAW 枚 —— 多出来的那枚" +
                "是取证残留那一行的裸调（第 ④ 层逐字节钉它）。实到 代码 " + bareInCode.size + " ／ 原文 " +
                bareInRaw.size + "：两栏合流 = 尺把注释里的证据当代码（那 ④ 层就没得判了）；" +
                "原文栏少一枚 = 取证那一手被删了（删证据不是修尺）",
            Pair(EXPECTED_BARE_IN_CODE, EXPECTED_BARE_IN_RAW),
            Pair(bareInCode.size, bareInRaw.size),
        )
    }

    // ─────────────── ④ 取证那一手：代码里 0 处、注释里 1 处 ───────────────

    @Test
    fun theForensicCommentIsCountedSeparately() {
        val raw = readMainSource(RECEIVER)
        val code = withoutComments(raw)
        assertEquals(
            "**钉法 = 逐字节（代码栏）**：改前那一手「$LEGACY_BARE_REFRESH」在代码里必须 **0** 处。实到 " +
                occurrences(code, LEGACY_BARE_REFRESH) + " 处 —— 读到 1 就是把某一枚调用又换回裸调了",
            0,
            occurrences(code, LEGACY_BARE_REFRESH),
        )
        assertEquals(
            "**钉法 = 逐字节（含注释原文栏）**：它在原文里必须恰好 **1** 处（取证残留那一行，逐字留着）。" +
                "实到 " + occurrences(raw, LEGACY_BARE_REFRESH) + " 处：0 = 取证被搬走，上面那个 0 就成了" +
                "「注释删干净」的假绿；2 = 病灶回潮。这一格同时证明本守卫那把抹注释的尺真的在挡散文假阳性",
            1,
            occurrences(raw, LEGACY_BARE_REFRESH),
        )
    }

    // ─────────────── ⑤ 调用点不写载体类型名 + 分派按档位、不带 else ───────────────

    @Test
    fun theCarrierTypeStaysUnnamedAtTheCallSiteAndTheGateIsDispatchedByTier() {
        val raw = readMainSource(RECEIVER)
        val code = withoutComments(raw)
        assertEquals(
            "**钉法 = 数出现次数**：$RECEIVER 里「ChainReadSnapshot」两栏都必须 0 枚（实到 原文 " +
                occurrences(raw, "ChainReadSnapshot") + " ／ 抹注释 " + occurrences(code, "ChainReadSnapshot") +
                "）—— 同包取载体不需要那枚类型名，而全仓「引用载体的文件册」是在册六枚的等式" +
                "（widget/ChainReadSnapshotWiringGuardTest 第 ⑤ 层）；本卡把类型名写进调用点就把那本册子顶高了",
            Pair(0, 0),
            Pair(occurrences(raw, "ChainReadSnapshot"), occurrences(code, "ChainReadSnapshot")),
        )

        val block = balancedBlock(code, "val chainSnapshot = when (carrierPlan) {", "头顶取载体那一手")
        for (tier in listOf(TIER_BUILD, TIER_SKIP_WIDGET_ONLY, TIER_SKIP_NO_CONSUMER)) {
            assertEquals(
                "**钉法 = 逐字节**：档位「$tier」在分派里恰 1 处（实到 " + occurrences(block, tier) + " 处）：" +
                    "三档逐枚点名、谁也不许借谁的出口 —— 表驱动那八格认识的三档在盘上必须各有自己的处置",
                1,
                occurrences(block, tier),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：那一手里「else ->」必须 0 处（实到 " + occurrences(block, "else ->") +
                " 枚）：折成 else 就是判据新增一档时这里静默默认「不取」，而那一档可能正该取",
            0,
            occurrences(block, "else ->"),
        )
        assertEquals(
            "两枚不取那档各自交回 null，合计恰 2 处「-> null」（实到 " + occurrences(block, "-> null") + " 枚）",
            2,
            occurrences(block, "-> null"),
        )
    }

    // ─────────────── ⑥ 内核保持纯 JVM ───────────────

    @Test
    fun thePolicyKernelStaysPureJvm() {
        val raw = readMainSource(KERNEL)
        val code = withoutComments(raw)
        assertTrue(
            "$KERNEL 必须长在 domain/ 下（判据本体不许住在 widget/ 或 data/）：实到路径 $KERNEL",
            KERNEL.startsWith("com/buaa/schedule/domain/"),
        )
        for (needle in FORBIDDEN_READS) {
            assertEquals(
                "**钉法 = 口径尺（两栏各钉）**：内核里「$needle」抹注释后必须 0 枚（三枚事实全由调用点" +
                    "递进来，判据自己一个都不取）；裸原文栏实到 " + occurrences(raw, needle) +
                " 枚，只许是 KDoc 的转述。抹注释栏实到 " + occurrences(code, needle) + " 枚 —— " +
                "原判据哪天开始自己读钟／读权限／读库，先被抹掉的就是那句转述",
                0,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "**钉法 = 读形状**：内核除 package 之外不许有 import 行（实到 " + importLines(code).size +
                " 枚：" + importLines(code) + "）",
            0,
            importLines(code).size,
        )
        assertTrue(
            "内核 KDoc 那句纯 JVM 承诺必须在册（$PURE_PROMISE）：判据哪天改形，先抹掉的就是这句",
            raw.contains(PURE_PROMISE),
        )
        for (entry in listOf("    Build,", "    SkipOnlyWidgetConsumer,", "    SkipNoCarrierConsumer,")) {
            assertEquals(
                "**钉法 = 逐字节**：内核的档位名册里那一档「$entry」恰 1 枚（实到 " +
                    occurrences(code, entry) + " 枚）：三档各自一枚、不许并档 —— 并档就是本卡裁掉的" +
                    "「把几档折成一枚 Boolean」那一族病的另一种写法",
                1,
                occurrences(code, entry),
            )
        }
        assertEquals(
            "**钉法 = 逐字节**：判据入口的返回类型必须是分档「): RefreshChainCarrierPlan {」恰 1 枚、" +
                "「): Boolean」0 枚（实到 " + occurrences(code, DECIDE_RETURN_TYPE) + " 与 " +
                occurrences(code, "): Boolean") + "）",
            listOf(1, 0),
            listOf(occurrences(code, DECIDE_RETURN_TYPE), occurrences(code, "): Boolean")),
        )
    }

    // ─────────────── ⑦ 反套套逻辑：尺子认得它宣称能抓的那些形 ───────────────

    @Test
    fun theDetectorRecognisesTheShapesItClaimsToCatch() {
        val raw = readMainSource(RECEIVER)
        val caught = listOf(
            Triple("改前那一枚裸调（朝宽那一臂的形状）", "BackgroundSync.refreshWidgets(context)", 1),
            Triple("接线之后那一形（合法，不算裸调）", REFRESH_WITH_CARRIER, 0),
            Triple("零点续排那一枚（本卡在册的例外）", MIDNIGHT_BARE_CALL, 1),
            Triple("明日预告被换回裸调", "BackgroundSync.scheduleTomorrowPreview(context)", 1),
            Triple("头顶那一手被复制成第二枚", CARRIER_READ, 1),
        )
        for ((label, shape, expected) in caught) {
            val hits = callTexts(shape).filterNot { it.contains(CARRIER_ARGUMENT) }.size +
                occurrences(shape, CARRIER_READ)
            assertEquals(
                "**探测器自检（$label）**：这一形必须被上面那两把尺数到 $expected 枚，实到 $hits 枚 —— " +
                    "数不到的话上面所有「恰 1 枚／0 枚」都是空锚点（本仓口径：锚点拼错的假绿比没有守卫更糟）",
                expected,
                hits,
            )
        }
        // 抹注释那把尺自己：取证那一行喂进去必须读空（否则第 ④ 层那两个数是一枚都判不出差别的）
        val forensicLine = "                        // if (claimRolloverRefresh()) { $LEGACY_BARE_REFRESH }"
        assertEquals(
            "**探测器自检（抹注释切得到散文）**：取证那一整行经本守卫那把尺之后必须 0 枚" +
                "（实到 " + occurrences(withoutComments(forensicLine), LEGACY_BARE_REFRESH) + " 枚），" +
                "而原样喂进去是 1 枚（实到 " + occurrences(forensicLine, LEGACY_BARE_REFRESH) + " 枚）",
            listOf(0, 1),
            listOf(
                occurrences(withoutComments(forensicLine), LEGACY_BARE_REFRESH),
                occurrences(forensicLine, LEGACY_BARE_REFRESH),
            ),
        )
        // 块注释（KDoc 那一大段）也切得到：整段喂进去读空
        val blockSample = "/** 这里写着 $LEGACY_BARE_REFRESH 也不许算进代码栏 */\nval a = 1"
        assertEquals(
            "**探测器自检（块注释）**：实到 " + occurrences(withoutComments(blockSample), LEGACY_BARE_REFRESH) +
                " 枚（必须 0）",
            0,
            occurrences(withoutComments(blockSample), LEGACY_BARE_REFRESH),
        )
        for (relative in listOf(RECEIVER, KERNEL)) {
            assertTrue(
                "$relative 读空了（长度 " + readMainSource(relative).length + "）：路径挪过家，本守卫的针全在空串上比",
                readMainSource(relative).isNotEmpty(),
            )
        }
        assertTrue(
            "app/src/main 一个 .kt 都没扫到（实到路径 " + findMainJavaDir().path + "）：本守卫所有读数都是空的",
            findMainJavaDir().isDirectory,
        )
        assertEquals(
            "**探测器自检（换行位置不动）**：$RECEIVER 抹注释前后行数必须相等（实到 抹 " +
                withoutComments(raw).lines().size + " 对 原文 " + raw.lines().size + "）—— 不等就是那把尺" +
                "把行号也一起抹掉了，本守卫报错消息里点的行号从此说谎",
            raw.lines().size,
            withoutComments(raw).lines().size,
        )
    }

    // ---------------- 读盘与小工具 ----------------

    private val BACKGROUND_SYNC_CALL = Regex("BackgroundSync\\.[A-Za-z]+[(]")

    /**
     * 实参表配平后逐枚点名：交回 `[方法名](实参表)` 那些串（空白压成一枚空格）。
     *
     * 为什么不用"实参表逐字节等于在册那一份"：默认实参、换行位置都会计进那一枚空格，
     * 而这一层要判的只是「这一枚调用到底递没递 snapshot」。配平落空就抛 —— 静默跳过等于没有守卫。
     */
    private fun callTexts(code: String): List<String> {
        val calls = mutableListOf<String>()
        for (match in BACKGROUND_SYNC_CALL.findAll(code)) {
            val open = match.range.last
            var depth = 0
            var close = -1
            var index = open
            while (index < code.length) {
                when (code[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            close = index
                            index = code.length
                        }
                    }
                }
                if (close < 0) index++
            }
            check(close > open) { "BackgroundSync 那一枚调用的圆括号没配平：${match.value}" }
            calls += match.value.substring(0, match.value.length - 1) +
                code.substring(open, close + 1).replace(Regex("\\s+"), " ")
        }
        return calls
    }

    /** 从锚点那一句起按花括号配平取整块（含锚点自己）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到块的开括号", open >= 0)
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    private fun importLines(code: String): List<String> =
        code.lines().filter { it.trimStart().startsWith("import ") }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

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

    /**
     * 只抹注释、保留字符串与**换行位置**（报错消息里的行号因此可信）。
     *
     * 逐行扫 + buildString 拼串：故意不用本仓那一族「把源码摊成字符数组、逐格改写、最后再包回字符串」
     * 的助手写法 —— 那一族每新增一枚就把 CommentStrippingHelperShapeGuardTest 的四枚写死值各顶高 1，
     * 而那把尺盖不住过滤门禁（只有全量跑才红），本卡一寸都不去碰它（先例：
     * domain/schedule/WeekdayDateOffsetSingleRulerGuardTest 里那枚 buildString 形状的 withoutStrings）。
     *
     * 覆盖面边界（如实写）：认得行注释与块注释（KDoc 就是块注释）的起止，字符串字面量里的
     * 那对「斜杠加星号」会被当注释起点 —— 本守卫那几枚针（BackgroundSync. 与 readChainSnapshotOrNull(）
     * 在一枚 .kt 里从不长在字符串字面量里，所以这一条切不到判据；列位与行长会变（块注释收尾之后的
     * 那一截会左移），本守卫只数枚数、不比列。
     */
    private fun withoutComments(source: String): String {
        var inBlock = false
        val lines = source.lines()
        return buildString {
            for ((position, line) in lines.withIndex()) {
                val kept = StringBuilder()
                var index = 0
                while (index < line.length) {
                    if (inBlock) {
                        val end = line.indexOf("*/", index)
                        if (end < 0) {
                            index = line.length
                        } else {
                            inBlock = false
                            index = end + 2
                        }
                        continue
                    }
                    val slash = line.indexOf("//", index)
                    val block = line.indexOf("/*", index)
                    when {
                        block >= 0 && (slash < 0 || block < slash) -> {
                            kept.append(line, index, block)
                            inBlock = true
                            index = block + 2
                        }

                        slash >= 0 -> {
                            kept.append(line, index, slash)
                            index = line.length
                        }

                        else -> {
                            kept.append(line, index, line.length)
                            index = line.length
                        }
                    }
                }
                append(kept)
                if (position != lines.lastIndex) append('\n')
            }
        }
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
        const val RECEIVER = "com/buaa/schedule/widget/WidgetRefreshReceiver.kt"
        const val KERNEL = "com/buaa/schedule/domain/widget/RefreshChainCarrierGate.kt"

        // ── 头顶那一手 ──
        const val CARRIER_READ = "readChainSnapshotOrNull("
        const val CARRIER_BUILD = "val chainSnapshot = when (carrierPlan) {"
        const val GATE_CALL = "RefreshChainCarrierGate.decide("
        const val WAKE_LOCK_ANCHOR = "WakeLocks.withPartialWakeLock("
        const val CARRIER_ARGUMENT = "snapshot = chainSnapshot"

        // ── 四枚消费者的逐字节形 ──
        const val REFRESH_WITH_CARRIER = "BackgroundSync.refreshWidgets(context, snapshot = chainSnapshot)"
        const val RESCHEDULE_WITH_CARRIER =
            "BackgroundSync.rescheduleRemindersAndBells(context, snapshot = chainSnapshot)"
        const val PREVIEW_WITH_CARRIER =
            "BackgroundSync.scheduleTomorrowPreview(context, snapshot = chainSnapshot)"
        const val MIDNIGHT_BARE_CALL = "BackgroundSync.scheduleWidgetMidnight(context)"

        // ── 取证残留那一手（代码 0 ／ 原文 1）──
        const val LEGACY_BARE_REFRESH = "BackgroundSync.refreshWidgets(context)"

        // ── 内核那一侧 ──
        const val TIER_BUILD = "RefreshChainCarrierPlan.Build"
        const val TIER_SKIP_WIDGET_ONLY = "RefreshChainCarrierPlan.SkipOnlyWidgetConsumer"
        const val TIER_SKIP_NO_CONSUMER = "RefreshChainCarrierPlan.SkipNoCarrierConsumer"
        const val DECIDE_RETURN_TYPE = "): RefreshChainCarrierPlan {"
        const val PURE_PROMISE = "零 android / androidx import"
        val FORBIDDEN_READS = listOf(
            "android",
            "androidx",
            "Context",
            "Build.VERSION",
            "LocalDate.now",
            "SystemClock",
            "ZoneId",
            "scheduleRepository",
            "BroadcastReceiver",
        )

        /** ⓪ 步现量写死的期望（口径 = 本守卫内部抹注释后的文本，不是裸 grep） */
        const val EXPECTED_BARE_IN_CODE = 1
        const val EXPECTED_BARE_IN_RAW = 2
        const val EXPECTED_CARRIER_ARGS = 4
        const val EXPECTED_CARRIER_READ_RAW = 1
    }
}
