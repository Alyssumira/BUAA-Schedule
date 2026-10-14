package com.buaa.schedule.data.local

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T369③：节假日缓存**写侧形状**守卫（在册 #336 ＋ 任务表 T248-C1 那一格，同一枚文件并卡）。
 *
 * ## 为什么这一半只能走守卫
 * 本卡的纯判据（落盘档 × 月份名册 ⇒ 该念哪一句）已经在表驱动那张表里了（`SpecialDayCacheWriteOutcomeTableTest`）。
 * 剩下的是 **IO 形状**：截断直写有没有被换成「临时件 + 改名」、「改名不成就 copy 顶上」那手兜底有没有被抄进来、
 * `put` 的签名还回不回 `Unit`、两把 `listFiles` 并没并成一把、调用点有没有把每一档折进内核。
 * 这些都不是纯函数，按本仓铁律"非纯函数只有守卫这一条落点"，硬把它包装成"纯判据"就是又造一枚查不出的形状。
 *
 * ## 六格各钉什么（每格的红都不重叠）
 * ① [directTruncateWriteIsGoneFromCodeAndKeptOnlyAsEvidence]：那一直写串在**代码侧 0 处 / 注释侧恰 1 处**
 *    （本仓双向复算尺的标准形状：改前的原文留在注释里当取证，但不许再长在代码里），
 *    外加临时件写法四枚枚数尺与"不许抄 copy 兜底"那一格。
 * ② [putHandsBackATieredVerdictInsteadOfUnit]：`put` 的签名交回 [SpecialDayWriteOutcome]、体内一枚裸 `Unit` 都不许留，
 *    两手 `runCatching` 都必须把结论取回来（`exceptionOrNull()` / `getOrDefault(false)` 各按枚数钉）。
 * ③ [oneListFilesFilterCoversBothReaders]：两把尺**并成一把**（裁的是甲档 + 乙档那半格）。
 *    少 `f.isFile` 那一形在代码与注释两栏都是 0 处；`listFiles` 全文件恰 3 处（loadAll / cachedMonthAges / clear）。
 * ④ [falseCommentLivesOnlyInTheEvidenceColumn]：那句假注释（「…让那个月按"没有缓存"处理 —— 方向与 loadAll
 *    读到坏文件时一致…」）只许活在注释那一栏，代码侧 0 处；订正后的真话（名册只解析**文件名**）必须在代码侧在场。
 * ⑤ [callSiteFoldsEveryCacheTierIntoKernelTiersWithoutAnElse]：全仓唯一那一枚 `put` 调用点的返回值必须被接住
 *    （长在折算函数体里），且折算函数**零 else**、四枚内核档名各恰一枚 —— 加 else 就是"新的一档静默折成已落盘"。
 * ⑥ [tierRostersPairAcrossTheSeamAndTheAgeRosterHasNoValidityColumn]：配对尺（内核 4 档 == 缓存 3 档 - 1 + stage 2 档）
 *    ＋ 名册那枚载体逐字节钉住它只有两格（**没有"内容坏"那一档**，这是 ⓪ 段"7 天盲区"成立的结构前提）。
 *
 * ## 覆盖面边界（如实写，别当它没有）
 * - 钉的是**源码形状**：改名在真机上到底成不成、磁盘满时 `writeText` 抛什么、`.part` 会不会被 ROM 留下，
 *   都不在本守卫射程（本卡零设备，那几格押给编排者的装机读数，见回执"未验到"）。
 * - **不替 `clear` 作证**：`clear` 丢掉 `delete()` 返回值那一格本卡未动（它在 main 与测试树里零调用点），
 *   本守卫也刻意不钉它的现状枚数 —— 钉了就等于替一张没读过的文件签字。
 * - `TeachingScheduleParser.parse` 内部接不接得住坏正文，由表驱动那张表的「己」组钉，不在这里。
 * - 抹注释用 `buildString` 逐段拼，**没有**新增"字符数组 + 包成 String"那一族助手（在册守卫
 *   `CommentStrippingHelperShapeGuardTest` 把那一族的枚数钉成合并态写死值，多造一枚会把那三格一起顶红，
 *   而那枚守卫不在本卡过滤门禁的覆盖面里）。
 */
class SpecialDayCacheAtomicWriteGuardTest {

    /** ① 截断直写那一手：代码侧 0 处、注释侧恰 1 处；临时件写法的四枚枚数尺。 */
    @Test
    fun directTruncateWriteIsGoneFromCodeAndKeptOnlyAsEvidence() {
        val cache = readMain(CACHE)
        val code = withoutCommentsKeepingLiterals(cache)
        val problems = mutableListOf<String>()
        // 双向复算：针只许长在注释里当取证，不许再长在代码里
        if (occurrences(code, DIRECT_WRITE) != DIRECT_WRITE_IN_CODE) {
            problems.add("改前那一直写串「" + DIRECT_WRITE + "」在代码侧实到 " + occurrences(code, DIRECT_WRITE) +
                " 处 expected:<" + DIRECT_WRITE_IN_CODE + ">—— 多于 0 就是把原子改名换回了截断直写（W 臂的形状），" +
                "截断直写一回来，「半截正文 + 新 mtime + 7 天盲区」整条账跟着回来")
        }
        if (occurrences(cache, DIRECT_WRITE) != DIRECT_WRITE_IN_RAW) {
            problems.add("同一枚串在原文（含注释）侧实到 " + occurrences(cache, DIRECT_WRITE) +
                " 处 expected:<" + DIRECT_WRITE_IN_RAW + ">—— 少了就是取证原文被抹掉（那是删证据不是修尺），" +
                "多了就是有人把它抄进代码又抄进注释")
        }
        for (needle in listOf(STAGE_WRITE, PROMOTE_RENAME_CALL, TEMP_CLEANUP)) {
            if (occurrences(code, needle) != 1 && occurrences(code, needle) != TEMP_CLEANUP_SITES) {
                problems.add("改后那一手「" + needle + "」实到 " + occurrences(code, needle) + " 处 expected:<1>（清场那一手是 " +
                    TEMP_CLEANUP_SITES + "）—— 枚数漂了就是写侧步骤被增删，本守卫的账要同批重钉")
            }
        }
        if (occurrences(code, COPY_FALLBACK) != 0 || occurrences(cache, COPY_FALLBACK) != 0) {
            problems.add("「" + COPY_FALLBACK + "」实到 代码 " + occurrences(code, COPY_FALLBACK) + " ／ 原文 " +
                occurrences(cache, COPY_FALLBACK) + " 处 expected:<0>—— 本卡刻意**不抄** UpdateCheck 那一族" +
                "\"改名不成就 copy 顶上\"的兜底：那一手只在正式件当时并不存在时走得到，而缓存这一枚的正式件" +
                "可以带着上一份好内容，往它上面 copy 就是把截断直写换个地方复现")
        }
        if (occurrences(code, TEMP_SUFFIX_DECL) != 1) {
            problems.add("临时件后缀「" + TEMP_SUFFIX_DECL + "」实到 " + occurrences(code, TEMP_SUFFIX_DECL) +
                " 处 expected:<1>—— 后缀换了（尤其换成以 .json 结尾那一形）就会被名册与 loadAll 那两把尺看见，" +
                "半成品从此以下一次\"刚抓的\"身份进册")
        }
        assertTrue(
            "钉法 = 双向复算（代码侧 0 / 原文侧恰 1）+ 枚数尺。节假日缓存的写侧必须是「同目录先写临时件、" +
                "再改名顶上正式件」；改前那一手是 `writeText` 直写（开流即 truncate），失败时盘上留的正是" +
                "「半截正文 + 新 mtime」，而只看 mtime 的新鲜度把这一月判成刚抓的 ⇒ 7 天整之内不再补抓（在册 #336）。" +
                "实到：直写串 代码 " + occurrences(code, DIRECT_WRITE) + " ／ 原文 " + occurrences(cache, DIRECT_WRITE) +
                " ｜ 临时件写 " + occurrences(code, STAGE_WRITE) + " ｜ 改名 " + occurrences(code, PROMOTE_RENAME_CALL) +
                " ｜ 清场 " + occurrences(code, TEMP_CLEANUP) + " ｜ copy 兜底 " + occurrences(code, COPY_FALLBACK) +
                " ｜ 问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** ② `put` 交回结论：签名不再返回 Unit，体内两手都必须把结论取回来。 */
    @Test
    fun putHandsBackATieredVerdictInsteadOfUnit() {
        val cache = readMain(CACHE)
        val code = withoutCommentsKeepingLiterals(cache)
        val body = blockBody(code, PUT_SIGNATURE_ANCHOR)
        val problems = mutableListOf<String>()
        if (body == null) {
            problems.add("切不出 put 的函数体（锚点「" + PUT_SIGNATURE_ANCHOR + "」之后的花括号没配平）")
        } else {
            if (occurrences(body, BARE_UNIT) != 0) {
                problems.add("put 体内还留着裸「" + BARE_UNIT + "」" + occurrences(body, BARE_UNIT) +
                    " 枚 expected:<0>—— 那就是把 Result 丢进 Unit 的旧吞法：调用点依旧拿不到\"这次落没落\"")
            }
            if (occurrences(body, RUN_CATCHING) != PUT_RUN_CATCHINGS) {
                problems.add("put 体内 runCatching 实到 " + occurrences(body, RUN_CATCHING) +
                    " 枚 expected:<" + PUT_RUN_CATCHINGS + ">（写临时件 + 改名，一步一枚）")
            }
            if (occurrences(body, EXCEPTION_OR_NULL) != PUT_RUN_CATCHINGS) {
                problems.add("结论被取回的次数实到 " + occurrences(body, EXCEPTION_OR_NULL) +
                    " 枚 expected:<" + PUT_RUN_CATCHINGS + ">—— runCatching 的 Result 不接走就是裸吞换了个写法")
            }
            if (occurrences(body, GET_OR_DEFAULT) != 1) {
                problems.add("改名那一步的布尔结论被读回的次数实到 " + occurrences(body, GET_OR_DEFAULT) +
                    " 枚 expected:<1>—— \"false 也算落了\"正是这一格挡的形状")
            }
            if (occurrences(body, DIR_GATE) != 1) {
                problems.add("目录闸门那一手实到 " + occurrences(body, DIR_GATE) + " 枚 expected:<1>——" +
                    " 没有它，「缓存目录立不起来」与「写失败」就折成同一句（改前 `apply { mkdirs() }` 丢返回值那一格）")
            }
        }
        if (occurrences(code, PUT_RETURN_TYPE) != 1) {
            problems.add("put 的返回类型那一行「" + PUT_RETURN_TYPE + "」实到 " + occurrences(code, PUT_RETURN_TYPE) +
                " 处 expected:<1>（签名换回 Unit 或换成一枚 Boolean 都会红：Boolean 把三档压成一档，" +
                "而\"腾空间重试\"与\"ROM 拒改名\"给用户下一步完全不同）")
        }
        for (tier in OUTCOME_TIERS) {
            if (occurrences(code, tier) != 1) {
                problems.add("结论名册那一档「" + tier + "」实到 " + occurrences(code, tier) + " 处 expected:<1>")
            }
        }
        if (occurrences(code, STAGE_ENUM) != 1) {
            problems.add("失败那一步的名册「" + STAGE_ENUM + "」实到 " + occurrences(code, STAGE_ENUM) + " 处 expected:<1>")
        }
        assertTrue(
            "钉法 = 切体 + 枚数尺（绝对值，不是 >=）。改前 `put` 的签名是 `... ) = withContext(...) { runCatching { … }; Unit }`：" +
                "结论既没名字也没出口，这就是 #336 那格的正面。实到：runCatching " +
                (body?.let { occurrences(it, RUN_CATCHING) } ?: -1) + " ／ 取回结论 " +
                (body?.let { occurrences(it, EXCEPTION_OR_NULL) } ?: -1) + " ／ 读回布尔 " +
                (body?.let { occurrences(it, GET_OR_DEFAULT) } ?: -1) + " ｜ 问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** ③ 两把 listFiles 尺并成一把（本卡采了乙档的那半格）。 */
    @Test
    fun oneListFilesFilterCoversBothReaders() {
        val cache = readMain(CACHE)
        val code = withoutCommentsKeepingLiterals(cache)
        val merged = occurrences(code, MERGED_FILTER)
        val split = occurrences(code, SPLIT_FILTER)
        val splitInRaw = occurrences(cache, SPLIT_FILTER)
        val all = occurrences(code, LIST_FILES)
        val problems = mutableListOf<String>()
        if (merged != MERGED_SITES) {
            problems.add("并好的那一把「" + MERGED_FILTER + "」实到 " + merged + " 处 expected:<" + MERGED_SITES +
                ">（读侧两枚点名：loadAll 与 cachedMonthAges）")
        }
        if (split != 0 || splitInRaw != 0) {
            problems.add("少 `f.isFile` 那一形实到 代码 " + split + " ／ 原文 " + splitInRaw + " 处 expected:<0>" +
                " —— 两把尺原来差的就是这一格：同一目录里一枚名叫 2026-10.json 的**目录**，" +
                "loadAll 那一把会收它（readText 抛 ⇒ 被 runCatching 咽掉 ⇒ 那一月静默交回零条），" +
                "而 cachedMonthAges 那一把不收（⇒ 那一月按\"没有缓存\"处理）；同一份盘两本账，" +
                "正是\"半截被当成功\"这一族最容易看走的那种形状。现在两边都必须带 isFile")
        }
        if (all != LIST_FILES_SITES) {
            problems.add("本文件 listFiles 实到 " + all + " 处 expected:<" + LIST_FILES_SITES +
                ">（三处：loadAll / cachedMonthAges / clear。clear 那一处本卡没动、也没替它作证）")
        }
        assertTrue(
            "钉法 = 一把尺 + 枚数下限。同一目录只许一把过滤尺；报错消息里写清了被并掉的那把差在哪一格" +
                "（" + "`f.isFile`" + "）。实到：并好的 " + merged + " ／ 少 isFile 那一形 " + split +
                "（原文 " + splitInRaw + "）／ listFiles 共 " + all + " ｜ 问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** ④ 假注释只许活在取证那一栏；订正后的真话必须在原文里逐字节在场。 */
    @Test
    fun falseCommentLivesOnlyInTheEvidenceColumn() {
        val cache = readMain(CACHE)
        val code = withoutCommentsKeepingLiterals(cache)
        val problems = mutableListOf<String>()
        for (needle in FALSE_SENTENCE) {
            if (occurrences(code, needle) != 0) {
                problems.add("假句「" + needle + "」在代码侧实到 " + occurrences(code, needle) + " 处 expected:<0>——" +
                    " 那三句里没有任何一句是真话：半截的 2026-10.json **文件名完好**，照样带着新 mtime 进名册，" +
                    "压根不是\"按没有缓存处理\"；而 loadAll 对\"内容坏\"其实有两档（语法不合法 ⇒ 跳过；" +
                    "语法合法而形状怪 ⇒ parse 里没被 runCatching 罩住的那一手会抛，整条 loadAll 抛给调用点），" +
                    "所以\"与 loadAll 一致、补抓一次就好\"两头都不成立")
            }
            if (occurrences(cache, needle) != 1) {
                problems.add("同一句在原文（含注释）侧实到 " + occurrences(cache, needle) + " 处 expected:<1>（取证原文）")
            }
        }
        for (truth in CORRECTED_FACTS) {
            if (occurrences(cache, truth) != 1) {
                problems.add("订正后要说出的那份事实「" + truth + "」在原文侧实到 " + occurrences(cache, truth) +
                    " 处 expected:<1> —— 针脚没了就是那段注释被改回含糊话")
            }
            // 那句真话本来就是**注释**里的一句话，代码侧天然 0；这里把它写成断言而不是"不检查"，
            // 为的是哪天有人把这句话说进代码（比如拿它当字符串常量），本格会当场响。
            if (occurrences(code, truth) != 0) {
                problems.add("同一句在代码侧实到 " + occurrences(code, truth) +
                    " 处 expected:<0> —— 它是散文，落点只该在注释里")
            }
        }
        assertTrue(
            "钉法 = 双向复算（假句 代码 0 ／ 原文恰 1）+ 真话在原文侧恰 1（代码侧恰 0，因为它本该是散文）。实到：" +
                FALSE_SENTENCE.joinToString(" ／ ") { needle -> needle + " 代码 " + occurrences(code, needle) + "／原文 " + occurrences(cache, needle) } +
                " ｜ 问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** ⑤ 调用点：唯一那枚 put 的返回值被接住，且折算函数零 else、四档逐枚点名。 */
    @Test
    fun callSiteFoldsEveryCacheTierIntoKernelTiersWithoutAnElse() {
        val vm = readMain(VIEW_MODEL)
        val code = withoutCommentsKeepingLiterals(vm)
        val helper = blockBody(code, FOLD_ANCHOR)
        val problems = mutableListOf<String>()
        if (occurrences(code, OLD_LOOP) != 0) {
            problems.add("改前那一形「" + OLD_LOOP + "」在代码侧实到 " + occurrences(code, OLD_LOOP) +
                " 处 expected:<0>—— forEach 会把返回值整批丢掉，落没落就又没人吃了")
        }
        if (occurrences(vm, OLD_LOOP) != 1) {
            problems.add("同一形在原文侧实到 " + occurrences(vm, OLD_LOOP) + " 处 expected:<1>（取证原文）")
        }
        if (occurrences(code, NEW_LOOP) != 1) {
            problems.add("改后那一形「" + NEW_LOOP + "」实到 " + occurrences(code, NEW_LOOP) + " 处 expected:<1>")
        }
        if (occurrences(code, PUT_CALL) != PUT_CALL_SITES) {
            problems.add("全仓那唯一一枚 put 调用点实到 " + occurrences(code, PUT_CALL) +
                " 处 expected:<" + PUT_CALL_SITES + ">（多了就是有人另开了一条落盘路，少了就是这条链改道而本守卫看不见）")
        }
        if (occurrences(code, FOLD_ANCHOR) != 1) {
            problems.add("折算函数自身「" + FOLD_ANCHOR + "」实到 " + occurrences(code, FOLD_ANCHOR) + " 处 expected:<1>")
        }
        if (helper == null) {
            problems.add("切不出折算函数的体（锚点漂了）")
        } else {
            if (occurrences(helper, ELSE_ARROW) != 0) {
                problems.add("折算函数里有「" + ELSE_ARROW + "」" + occurrences(helper, ELSE_ARROW) +
                    " 枚 expected:<0>—— 加 else 就是\"缓存层哪天多出一档失败，被静默折成已落盘\"，" +
                    "那正是 #336 的形状换个落点重演")
            }
            for (arm in FOLD_ARMS) {
                if (occurrences(helper, arm) != 1) {
                    problems.add("结论档名「" + arm + "」在折算函数里实到 " + occurrences(helper, arm) + " 处 expected:<1>")
                }
            }
            for (state in KERNEL_STATES) {
                if (occurrences(helper, state) != 1) {
                    problems.add("内核档名「" + state + "」在折算函数里实到 " + occurrences(helper, state) + " 处 expected:<1>")
                }
            }
        }
        if (occurrences(code, WRITES_ARGUMENT) != 1) {
            problems.add("那句日志把落盘名册递进去的那一手「" + WRITES_ARGUMENT + "」实到 " +
                occurrences(code, WRITES_ARGUMENT) + " 处 expected:<1>—— 名册算出来了却不递出去，" +
                "措辞那一头就只能永远念\"已更新\"")
        }
        assertTrue(
            "钉法 = 切体 + 逐枚名册对账。全仓只有这一枚 put 调用点（SpecialDayCache.put 的 grep 结果），" +
                "它必须把结论折成内核的字汇再交给措辞那一份（与 YearMonth↔SpecialDayMonth 同一道折算缝）。" +
                "实到：put 调用 " + occurrences(code, PUT_CALL) + " ／ 折算函数 " + occurrences(code, FOLD_ANCHOR) +
                " ｜ 问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** ⑥ 配对尺：两族名册的档数必须对得上；名册载体没有"内容坏"那一格。 */
    @Test
    fun tierRostersPairAcrossTheSeamAndTheAgeRosterHasNoValidityColumn() {
        val cache = withoutCommentsKeepingLiterals(readMain(CACHE))
        val policy = withoutCommentsKeepingLiterals(readMain(POLICY))
        val cacheTopTiers = OUTCOME_TIERS.count { tier -> occurrences(cache, tier) == 1 }
        val cacheStages = STAGE_ARMS.count { stage -> occurrences(cache, stage) >= 1 }
        val kernelTiers = KERNEL_ENTRY_DECLS.count { entry -> occurrences(policy, entry) == 1 }
        val problems = mutableListOf<String>()
        if (cacheTopTiers != OUTCOME_TIER_COUNT) {
            problems.add("缓存侧结论名册实到 " + cacheTopTiers + " 档 expected:<" + OUTCOME_TIER_COUNT + ">")
        }
        if (cacheStages != STAGE_COUNT) {
            problems.add("缓存侧\"停在哪一步\"名册实到 " + cacheStages + " 档 expected:<" + STAGE_COUNT + ">")
        }
        if (kernelTiers != KERNEL_TIER_COUNT) {
            problems.add("内核侧落盘档名册实到 " + kernelTiers + " 档 expected:<" + KERNEL_TIER_COUNT + ">——" +
                " 摘掉一枚（比如把\"改名没被接受\"并进\"临时件没写完整\"）就是 N 臂要当场响的那一格")
        }
        if (kernelTiers != cacheTopTiers - 1 + cacheStages) {
            problems.add("两族不同源：内核 " + kernelTiers + " 档 ≠ 缓存 " + cacheTopTiers + " 档 - 1（已落盘）+ " +
                cacheStages + "（失败停在哪一步）—— 折算缝一旦缺一档，就有一档失败在句子里没有名字")
        }
        if (occurrences(policy, AGE_ROSTER_DECL) != 1) {
            problems.add("月龄名册那枚载体必须逐字节是「" + AGE_ROSTER_DECL + "」，实到 " +
                occurrences(policy, AGE_ROSTER_DECL) + " 处 expected:<1> —— 这一格钉的是**名册里没有\"内容坏\"那一档**：" +
                "只有「月份 + 月龄」两格，判据拿不到\"这份文件解不解得开\"。这正是 ⓪ 段那句" +
                "「一次半截写入 ⇒ 这一月 7 天整之内不再被补抓」的结构前提；哪天有人给名册补第三格，" +
                "这一格会红，红的正是\"新鲜度那一头新增了一维事实\"这件事，要同批重钉")
        }
        assertTrue(
            "钉法 = 两族名册各自枚数 + 一条配对等式 + 名册载体逐字节。实到：缓存顶层 " + cacheTopTiers +
                " ／ 缓存 stage " + cacheStages + " ／ 内核 " + kernelTiers + " ｜ 问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    // ---- 读盘、抹注释、切体 ------------------------------------------------------------------------

    /**
     * 只抹注释、保留字符串字面量，长度与换行位置都不动（行号因此仍然可信）。
     * 读法与理由抄在册那一族（`CourseEditorSavingFinallyGuardTest` 里那一枚）；⚠️ 刻意**不**写成
     * 「先把源铺成字符数组、收尾再把那枚数组包成字符串返回」那一族形状 —— 那会顶高在册守卫
     * `CommentStrippingHelperShapeGuardTest` 的写死值，而那枚守卫不在本卡过滤门禁的覆盖面里。
     */
    private fun withoutCommentsKeepingLiterals(source: String): String = buildString(source.length) {
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i)
                    val stop = if (nl < 0) source.length else nl
                    appendBlanks(source, i, stop)
                    i = stop
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }

                            source.startsWith("*/", j) -> {
                                depth--
                                j += 2
                            }

                            else -> j++
                        }
                    }
                    appendBlanks(source, i, j.coerceAtMost(source.length))
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) source.length else end + 3
                    append(source, i, stop)
                    i = stop
                }

                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> {
                                j++
                                break
                            }

                            source[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    append(source, i, j.coerceAtMost(source.length))
                    i = j
                }

                else -> {
                    append(source[i])
                    i++
                }
            }
        }
    }

    /** 把 from 到 until 那一段抹成空格，换行原样留着（行数不许被尺改动） */
    private fun StringBuilder.appendBlanks(source: String, from: Int, until: Int) {
        var k = from
        while (k < until) {
            append(if (source[k] == '\n') '\n' else ' ')
            k++
        }
    }

    /** 从 [anchor] 之后第一枚左花括号配平到对应右括号（含）；切不出来交回 null，由调用点把话念出来 */
    private fun blockBody(text: String, anchor: String): String? {
        val at = text.indexOf(anchor)
        if (at < 0) return null
        val open = text.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(at, index + 1)
                }
            }
            index++
        }
        return null
    }

    private fun occurrences(haystack: String, needle: String): Int = haystack.split(needle).size - 1

    private fun readMain(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：这枚文件挪过家的话本守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 单测的 cwd 是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛（静默跳过比没有守卫更糟） */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { candidate -> File(dir, candidate) }
                .firstOrNull { candidate -> candidate.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 " + File("").absolutePath)
    }

    private companion object {
        const val CACHE = "com/buaa/schedule/data/local/SpecialDayCache.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val POLICY = "com/buaa/schedule/ui/SpecialDayRefreshPolicy.kt"

        // ---- ① 写侧形状（枚数全部在 T369① 落地的盘面上现量，见那一段的 commit message）----
        /** 改前那一直写串：只许当取证原文留在注释里 */
        const val DIRECT_WRITE = "file(context, " + "month).writeText("
        const val DIRECT_WRITE_IN_CODE = 0
        const val DIRECT_WRITE_IN_RAW = 1
        const val STAGE_WRITE = "temp.writeText("
        const val PROMOTE_RENAME_CALL = "renameTo("
        const val TEMP_CLEANUP = "temp.delete()"
        const val TEMP_CLEANUP_SITES = 2
        const val COPY_FALLBACK = "copyTo"
        const val TEMP_SUFFIX_DECL = "const val TEMP_SUFFIX = " + "\".part\""

        // ---- ② put 的出口 ----
        const val PUT_SIGNATURE_ANCHOR = "suspend fun put("
        const val PUT_RETURN_TYPE = "): SpecialDayWriteOutcome = withContext(Dispatchers.IO) {"
        const val BARE_UNIT = "Unit"
        const val RUN_CATCHING = "runCatching"
        const val PUT_RUN_CATCHINGS = 2
        const val EXCEPTION_OR_NULL = "exceptionOrNull()"
        const val GET_OR_DEFAULT = "getOrDefault(false)"
        const val DIR_GATE = "dirReady(context)"
        val OUTCOME_TIERS = listOf(
            "data object Written :",
            "data class WriteFailed(",
            "data object CacheDirUnreachable :",
        )
        const val OUTCOME_TIER_COUNT = 3
        const val STAGE_ENUM = "enum class SpecialDayWriteStage"
        val STAGE_ARMS = listOf("TEMP_WRITE", "PROMOTE_RENAME")
        const val STAGE_COUNT = 2

        // ---- ③ 一把过滤尺 ----
        const val MERGED_FILTER = "listFiles { f -> f.isFile && f.name.endsWith(\".json\") }"
        const val MERGED_SITES = 2
        const val SPLIT_FILTER = "listFiles { f -> f.name.endsWith(\".json\") }"
        const val LIST_FILES = "listFiles"
        const val LIST_FILES_SITES = 3

        // ---- ④ 假注释 ----
        val FALSE_SENTENCE = listOf("方向与 loadAll 读到坏文件时一致", "让那个月按")
        val CORRECTED_FACTS = listOf("这一档 `runCatching` 解析的是**文件名**")

        // ---- ⑤ 调用点 ----
        const val OLD_LOOP = "fetched.forEach { (month, raw) ->"
        const val NEW_LOOP = "fetched.map { (month, raw) ->"
        const val PUT_CALL = "com.buaa.schedule.data.local.SpecialDayCache.put(app, month, raw)"
        const val PUT_CALL_SITES = 1
        const val FOLD_ANCHOR = "private fun specialDayMonthWriteOf("
        const val ELSE_ARROW = "else ->"
        val FOLD_ARMS = listOf(
            "SpecialDayWriteOutcome.Written ->",
            "is SpecialDayWriteOutcome.WriteFailed ->",
            "SpecialDayWriteOutcome.CacheDirUnreachable ->",
            "SpecialDayWriteStage.TEMP_WRITE",
            "SpecialDayWriteStage.PROMOTE_RENAME",
        )
        val KERNEL_STATES = listOf(
            "SpecialDayWriteState.WRITTEN",
            "SpecialDayWriteState.TEMP_WRITE_FAILED",
            "SpecialDayWriteState.PROMOTION_FAILED",
            "SpecialDayWriteState.CACHE_DIR_UNREACHABLE",
        )
        const val KERNEL_TIER_COUNT = 4
        /**
         * 内核侧那四档在 `SpecialDayRefreshPolicy` 里的**声明形**（档名 + 给用户念的那句）。
         * 这里不拿 `SpecialDayWriteState.XXX` 那种引用形来数：文件体内只有 `isWritten` 那一手用得上限定名，
         * 另三档是在枚举内部由裸档名声明的 —— 引用形数出来是"档被用的次数"，不是"名册里有几档"。
         */
        val KERNEL_ENTRY_DECLS = listOf(
            "WRITTEN(\"已落盘\")",
            "TEMP_WRITE_FAILED(\"临时件没写完整\")",
            "PROMOTION_FAILED(\"改名没被接受\")",
            "CACHE_DIR_UNREACHABLE(\"缓存目录立不起来\")",
        )
        const val WRITES_ARGUMENT = "writes = writes,"

        // ---- ⑥ 名册载体 ----
        const val AGE_ROSTER_DECL =
            "internal data class SpecialDayCacheAge(val month: SpecialDayMonth, val ageDays: Long)"
    }
}
