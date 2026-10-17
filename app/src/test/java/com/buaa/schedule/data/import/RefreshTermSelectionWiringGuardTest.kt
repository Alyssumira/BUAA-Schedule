package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T403③ 源码形状守卫：钉「静默刷新取数这一路恒吃用户递进来的那一枚学期」（在册 #347）。
 *
 * 只做**源码核对**（读 `app/src/main` 工作树文本，纯 JVM、无 Robolectric、无设备、不读时钟）。
 * 输入面由 `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 第 ① 项（整棵 `src/main/java`）覆盖，
 * 本卡不新增条目、也不读任何 markdown ⇒ `WorkingTreeInputSurfaceGuardTest` 那三向对账零扰动。
 *
 * ## 病灶在这一把尺上的样子
 * 改前 `BuaaWebSession.refreshSchedule` 的体内那一行
 * `val selectedTerm = terms.firstOrNull { it.selected }?.code ?: terms.firstOrNull()?.code`
 * 让"用户点的那一枚"与"这一趟真抓的那一枚"是两个读数；而 `ScheduleViewModel.refreshFromBuaa` 的
 * `termCode` 形参只落进一枚显示用的 StateFlow。⇒ 本守卫钉的是**这两枚形状都不许回来**。
 *
 * ## 八层各钉什么（钉法逐格交代）
 * ① 参数表（逐字节＋数出现次数）：`refreshSchedule` 的参数表里 `termCode: String? = null,` 恰一处，
 *    而返回值那一尾行 `): kotlin.Result<SemesterCourses>? {` 仍恰一处 —— 后者是
 *    `BuaaScheduleParserTotalWeeksGuardTest` 的切块锚点，本卡把参数补在它**之前**，这一格就是那句约束的凭据。
 * ② 那两枚 `?:` 支不许回潮（**抹注释找锚点**，两栏一起报）：`?: terms.firstOrNull()?.code`
 *    在抹注释后的代码里恰 **0** 处、在含注释的原文里恰 **1** 处（取证留在 `refreshSchedule` 的段注释里）。
 *    两栏一起钉是因为「代码没接、注释里先写上了」那一形既骗得过单栏尺、也骗得过读人的眼睛
 *    （同 `BuaaMissingSectionGuardTest` 第 ④ 层的口径）；而 `selectedCode = terms.firstOrNull { it.selected }?.code,`
 *    恰一处钉的是"selected 只当**参数**递进内核，不再直接当**答案**"。
 * ③ 三档分派齐 ＋ 拒绝那一支排在任何请求之前（逐字节＋读次序）：`Fetch`／`NotOffered`／`NoOfferedList`
 *    各恰一处，`NotOffered` 那一支必须带 `return Result.failure(` 且整支落在 `fetchSemesterInfo(` 之前
 *    —— 拒绝还先发页面内 fetch，用户等的就是几十秒的一次白跑。
 * ④ 调用点接线（逐字节）：`termCode = requestedTermCode,` 恰一处，而改前那一行
 *    `if (termCode.isNotBlank()) _buaaTermCode.value = termCode.trim()` 恰 **0** 处（W 臂红的就是这一格）；
 *    显示位那一写必须排在 `repository.replaceSemesterCourses(…)` **之后**（不写库的两档不许顶下拉）。
 * ⑤ 冷启动那一趟的显示位种子（逐字节＋数出现次数）：`val localCurrentTermCode = …` 与
 *    `(terms.firstOrNull { it.code == localCurrentTermCode } ?: terms.firstOrNull { it.selected })` 各恰一处，
 *    而改前那句无条件覆写 `terms.firstOrNull { it.selected }?.let { _buaaTermCode.value = it.code }` 恰 **0** 处；
 *    `_buaaTermCode.value =` 全文件恰三处（写点册子闭合，第四枚就意味着又长一扇门各自定"当前学期"）。
 * ⑥ 首页那枚**不递学期**的调用点走默认档（逐字节＋落点名册）：`viewModel.refreshFromBuaa()` 恰一处、
 *    `viewModel.refreshFromBuaa(code)` 恰一处、`viewModel.refreshFromBuaa(termCode.trim())` 恰一处，
 *    全 app/src/main 里 `refreshFromBuaa(` 恰四处（三枚调用点 ＋ 一枚带 `termCode: String = ""` 默认实参的声明）。
 *    卡面 §3 要的那一格钉在这里：默认档不是"忘了递"，是**这一枚调用点要的正是那一档**。
 * ⑦ 内核纯度 ＋ 唯一调用点（数出现次数）：`RefreshTermSelection.kt` 零 android／androidx、零时钟、
 *    零 `WebView`、不 import `BuaaInPageFetcher`、判据不折成 `: Boolean`；
 *    `RefreshTermSelection.choose(` 全 app/src/main 恰一处、且只长在 `BuaaWebSession.kt`。
 * ⑧ 反套套逻辑（内联样例）：探测器必须认得**改前那一形**（`?:` 支 1 处、内核调用 0 处）与
 *    **只写显示位那一形**（`termCode = requestedTermCode,` 0 处）—— 数不到这两形就是空转。
 */
class RefreshTermSelectionWiringGuardTest {

    // ─────────────── ① 参数表 ───────────────

    @Test
    fun `refreshSchedule 的参数表里真有 termCode 那一格 而切块锚点那一尾行一字未动`() {
        val code = blanked(WEB_SESSION)
        for (needle in listOf(REFRESH_HEAD, TERM_PARAM, REFRESH_SIGNATURE_TAIL)) {
            assertEquals(
                "**钉法 = 逐字节 ＋ 数出现次数**：`$needle` 在 `$WEB_NAME` 抹注释后恰好一处（实到 " +
                    occurrences(code, needle) + " 处）。`$TERM_PARAM` 少了就是用户点名的码又进不了取数；" +
                    "`$REFRESH_SIGNATURE_TAIL` 长出第二处或归零都会让 " +
                    "`BuaaScheduleParserTotalWeeksGuardTest.addCountInRefresh` 那把按尾行切块的尺切错" +
                    "（⇒ 本卡把新参数补在尾行**之前**的全部理由）",
                1,
                occurrences(code, needle),
            )
        }
    }

    // ─────────────── ② 那两枚 `?:` 支不许回潮 ───────────────

    @Test
    fun `改前那两枚回退支在代码里零处而取证恰在注释里 selected 只当参数递进内核`() {
        val code = blanked(WEB_SESSION)
        val raw = rawSource(WEB_SESSION)
        for (needle in listOf(SELECTED_ELSE_FIRST, SELECTED_TERM_DIRECT)) {
            assertEquals(
                "**钉法 = 抹注释找锚点（两栏一起报）**：`$needle` 在**代码**里必须 0 处（实到 " +
                    occurrences(code, needle) + " 处），在**含注释的原文**里恰好 1 处（实到 " +
                    occurrences(raw, needle) + " 处）—— 代码那一档是 #347 本体（用户点名的码被服务器那一份顶掉），" +
                    "注释那一档是取证。代码栏读到 1 就是回潮，注释栏读到 0 就是取证被删干净（上面那个 0 就成假绿）",
                listOf(0, 1),
                listOf(occurrences(code, needle), occurrences(raw, needle)),
            )
        }
        assertEquals(
            "**钉法 = 逐字节**：`$SELECTED_AS_ARGUMENT` 在 `$WEB_NAME` 恰好一处（实到 " +
                occurrences(code, SELECTED_AS_ARGUMENT) + " 处）：这一格钉的是" +
                "「selected 现在只是内核的**一枚入参**，不再直接是取数那一枚答案」——" +
                "把它挪回 `val selectedTerm = …` 那一行，② 上面那两格会一起红",
            1,
            occurrences(code, SELECTED_AS_ARGUMENT),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`RefreshTermSelection.choose(` 在 `$WEB_NAME` 恰好一处（实到 " +
                occurrences(code, "RefreshTermSelection.choose(") + " 处）：两枚 `?:` 支收进内核之后，" +
                "这一路只剩这一枚裁定点。两处 = 全仓第二把尺",
            1,
            occurrences(code, "RefreshTermSelection.choose("),
        )
    }

    // ─────────────── ③ 三档分派齐 ＋ 拒绝排在任何请求之前 ───────────────

    @Test
    fun `三档分派各恰一处 拒绝那一支不落库也不先发页面内请求`() {
        val code = blanked(WEB_SESSION)
        for (needle in listOf(FETCH_ARM, NOT_OFFERED_ARM, EMPTY_ARM)) {
            assertEquals(
                "**钉法 = 逐字节**：`$needle` 在 `$WEB_NAME` 恰好一处（实到 " + occurrences(code, needle) +
                    " 处）：三档少一枚就是某一档又被折回默认行为 —— " +
                    "`$NOT_OFFERED_ARM` 被摘掉就是「点名的码教务没给」又被静默换成服务器那一份（#347 复活），" +
                    "`$EMPTY_ARM` 被摘掉就是空清单那一档去念「你的代码不在清单里」那句假话",
                1,
                occurrences(code, needle),
            )
        }
        val chooseAt = indexOfAll(code, "RefreshTermSelection.choose(")
        val infoAt = indexOfAll(code, "fetchSemesterInfo(")
        assertEquals("本层取块的两枚锚点各恰一处：choose 实到 " + chooseAt.size + " ／ info 实到 " + infoAt.size, 1, chooseAt.size)
        assertEquals("info 锚点枚数（实到 " + infoAt.size + " 处）", 1, infoAt.size)
        val notOfferedAt = code.indexOf(NOT_OFFERED_ARM)
        assertTrue(
            "**钉法 = 读次序**：`$NOT_OFFERED_ARM` 必须排在 `$WEB_NAME` 里第一枚 `fetchSemesterInfo(` 之前" +
                "（实到 L" + lineOf(code, notOfferedAt) + " vs L" + lineOf(code, infoAt.first()) + "）：" +
                "拒绝档还先把页面内请求发出去，用户等的是几十秒的一次白跑，而那句「未改动课表」就成了谎",
            notOfferedAt < infoAt.first(),
        )
        assertTrue(
            "**钉法 = 读次序**：裁定（L" + lineOf(code, chooseAt.first()) + "）必须排在 `fetchSemesterInfo(`" +
                "（L" + lineOf(code, infoAt.first()) + "）之前 —— 先裁后取，与 " +
                "`SemesterGateOrderingGuardTest` 第 ⑤ 层那把「信息 → 判 → 逐周抓」的次序尺同一形",
            chooseAt.first() < infoAt.first(),
        )
    }

    // ─────────────── ④ 调用点接线 ───────────────

    @Test
    fun `调用点把用户那一枚递进取数 显示位只在真写了库之后落`() {
        val code = blanked(VIEW_MODEL)
        assertEquals(
            "**钉法 = 逐字节**：`$REQUESTED_VAL` 在 `$VM_NAME` 恰好一处（实到 " + occurrences(code, REQUESTED_VAL) +
                " 处）：trim 与「空白算没点名」这一手只许长在递下去之前的那一枚地方，两处就是两把尺",
            1,
            occurrences(code, REQUESTED_VAL),
        )
        assertEquals(
            "**钉法 = 逐字节**：`$TERM_ARGUMENT` 在 `$VM_NAME` 恰好一处（实到 " + occurrences(code, TERM_ARGUMENT) +
                " 处）：这一枚就是「取数这一路吃用户递进来的那一枚」的接线点本体。" +
                "0 处 = 用户点名的码又只落进显示位（W 臂量的正是这一手）",
            1,
            occurrences(code, TERM_ARGUMENT),
        )
        assertEquals(
            "**钉法 = 逐字节（反向）**：改前那一行 `$DISPLAY_ONLY_WRITE` 在 `$VM_NAME` 必须 0 处（实到 " +
                occurrences(code, DISPLAY_ONLY_WRITE) + " 处）：它就是卡面 §1 那句「只落进一枚显示用的 StateFlow」的" +
                "逐字节形状。它回来而 " + TERM_ARGUMENT + " 还在，正向那一格读得见、反向这一格才拦得住",
            0,
            occurrences(code, DISPLAY_ONLY_WRITE),
        )
        val writeAt = indexOfAll(code, DISPLAY_WRITE_ON_SUCCESS)
        assertEquals(
            "**钉法 = 逐字节 ＋ 数出现次数**：`$DISPLAY_WRITE_ON_SUCCESS` 在 `$VM_NAME` 恰好一处（实到 " +
                writeAt.size + " 处）",
            1,
            writeAt.size,
        )
        val replaceAt = code.indexOf(REPLACE_CALL)
        assertTrue(
            "**钉法 = 读次序**：显示位那一写（L" + lineOf(code, writeAt.first()) + "）必须排在 `$REPLACE_CALL`" +
                "（L" + lineOf(code, replaceAt) + "）之后：写在前面，「结果不完整」与「零条课」那两档不落库却先把" +
                "下拉顶走了 —— 下拉与网格同源才是这一格的判据",
            replaceAt in 0 until writeAt.first(),
        )
    }

    // ─────────────── ⑤ 冷启动那一趟的显示位种子 ───────────────

    @Test
    fun `学期清单落到显示位那一手先认本地当前学期 改前那句无条件覆写零处`() {
        val code = blanked(VIEW_MODEL)
        assertEquals(
            "**钉法 = 逐字节**：`$LOCAL_CURRENT_READ` 在 `$VM_NAME` 恰好一处（实到 " +
                occurrences(code, LOCAL_CURRENT_READ) + " 处）：显示位的出处必须是**本地那一枚当前学期**",
            1,
            occurrences(code, LOCAL_CURRENT_READ),
        )
        assertEquals(
            "**钉法 = 逐字节**：`$SEED_CHAIN` 在 `$VM_NAME` 恰好一处（实到 " + occurrences(code, SEED_CHAIN) +
                " 处）：本地没有对应学期行（首次登录）才退回服务器 selected —— 顺序反过来就是冷启动把下拉顶成 B " +
                "而网格仍是 A（甲新造出来的那一档失效面）",
            1,
            occurrences(code, SEED_CHAIN),
        )
        assertEquals(
            "**钉法 = 逐字节（反向）**：改前那一行 `$UNCONDITIONAL_SEED` 必须 0 处（实到 " +
                occurrences(code, UNCONDITIONAL_SEED) + " 处）：它一回来，甲刷出来的那一枚学期就在下一次" +
                "回首页时被顶回服务器那一份",
            0,
            occurrences(code, UNCONDITIONAL_SEED),
        )
        assertEquals(
            "**钉法 = 数出现次数（写点册子闭合）**：`_buaaTermCode.value =` 在 `$VM_NAME` 恰好三处（实到 " +
                occurrences(code, "_buaaTermCode.value =") + " 处）：setBuaaTermCode 一枚 ＋ 清单种子一枚 ＋ " +
                "刷新成功一枚。第四枚 = 又长一扇门自己定「当前学期念哪一枚」，本卡那本册子就要重判",
            3,
            occurrences(code, "_buaaTermCode.value ="),
        )
    }

    // ─────────────── ⑥ 首页那枚不递学期的调用点走默认档 ───────────────

    @Test
    fun `三枚用户入口各按各自那一档接线 默认档是钉出来的不是漏出来的`() {
        val home = blanked(HOME_SCREEN)
        val import = blanked(IMPORT_SCREEN)
        val vm = blanked(VIEW_MODEL)
        assertEquals(
            "**钉法 = 逐字节**：`viewModel.refreshFromBuaa()`（**不带实参**那一形）在 `$HOME_NAME` 恰好一处（实到 " +
                occurrences(home, "viewModel.refreshFromBuaa()") + " 处）：卡面 §3 要的那一格 —— " +
                "首页菜单「刷新课表」不是漏递，它要的正是「没点名 ⇒ 取服务器 selected」那一档；" +
                "把它改成显式递空串，这一格与下面那格默认实参会一起红。实到 " +
                occurrences(home, "viewModel.refreshFromBuaa()") + " 处 —— 0 处 = 默认档那一格被摘",
            1,
            occurrences(home, "viewModel.refreshFromBuaa()"),
        )
        assertEquals(
            "**钉法 = 逐字节**：`viewModel.refreshFromBuaa(code)` 在 `$HOME_NAME` 恰好一处（实到 " +
                occurrences(home, "viewModel.refreshFromBuaa(code)") + " 处）：下拉那一路递的是**用户点的那一枚**",
            1,
            occurrences(home, "viewModel.refreshFromBuaa(code)"),
        )
        assertEquals(
            "**钉法 = 逐字节**：`viewModel.refreshFromBuaa(termCode.trim())` 在 `$IMPORT_NAME` 恰好一处（实到 " +
                occurrences(import, "viewModel.refreshFromBuaa(termCode.trim())") + " 处）：" +
                "手打框那一路递的也是取数参数，不是只 `setBuaaTermCode` 给显示位",
            1,
            occurrences(import, "viewModel.refreshFromBuaa(termCode.trim())"),
        )
        assertEquals(
            "**钉法 = 逐字节 ＋ 数出现次数**：`$DEFAULT_PARAM` 在 " +
                "`$VM_NAME` 恰好一处（实到 " + occurrences(vm, DEFAULT_PARAM) + " 处）：默认实参那一格就是" +
                "「不点名 ⇒ 内核走服务器 selected」的入口形状，摘掉默认值会逼首页那枚调用点显式递空串",
            1,
            occurrences(vm, DEFAULT_PARAM),
        )
        val byFile = readMainSources()
        val sites = byFile.entries.toList().sortedBy { it.key }
            .flatMap { (name, text) -> List(occurrences(text, "refreshFromBuaa(")) { name } }
        assertEquals(
            "**钉法 = 数出现次数 ＋ 落点名册**：`refreshFromBuaa(` 全 app/src/main 恰好四处（实到 " + sites.size +
                " 处，落点 " + sites.joinToString("、") + "）= 三枚用户入口 ＋ 一枚声明。" +
                "第五枚 = 又开一扇刷新门（那扇门递不递学期就得再判一次）；少一枚 = 某一枚用户入口被摘",
            4,
            sites.size,
        )
        assertEquals(
            "**钉法 = 数出现次数 ＋ 落点名册**：那四处的册子按**文件名字序**必须恰好是 `$HOME_NAME=2 ／ " +
                "$IMPORT_NAME=1 ／ $VM_NAME=1`（实到 " + byFile.entries.toList().sortedBy { it.key }
                    .filter { occurrences(it.value, "refreshFromBuaa(") > 0 }
                    .joinToString(" ／ ") { "${it.key}=${occurrences(it.value, "refreshFromBuaa(")}" } + "）：" +
                "册子的**内容与每栏枚数**才是判据，顺序是本文件自己那把 `sortedBy { it.key }` 尺定的",
            listOf("$HOME_NAME=2", "$IMPORT_NAME=1", "$VM_NAME=1"),
            byFile.entries.toList().sortedBy { it.key }
                .filter { occurrences(it.value, "refreshFromBuaa(") > 0 }
                .map { "${it.key}=${occurrences(it.value, "refreshFromBuaa(")}" },
        )
    }

    // ─────────────── ⑦ 内核纯度 ＋ 唯一调用点 ───────────────

    @Test
    fun `内核零 android 零时钟零设备 而全仓只有一枚调用点`() {
        val code = blanked(KERNEL)
        for (needle in listOf("import android", "import androidx", "LocalDate.now(", "System.currentTimeMillis", "WebView", "BuaaInPageFetcher")) {
            assertEquals(
                "`$KERNEL_NAME` 里 `$needle` 必须 0 处（实到 " + occurrences(code, needle) +
                    " 处）：这一份文件进得了 JVM 表驱动单测，靠的就是这一句",
                0,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "`$KERNEL_NAME` 里不许有任何一枚判据以 Boolean 收尾（`: Boolean` 必须 0 处，实到 " +
                occurrences(code, ": Boolean") + " 处）：折成布尔就是把「点名的那枚教务没给」与" +
                "「教务压根没答清单」念成同一句话，而这两档的下一手一枚要用户改代码、一枚要用户重新登录",
            0,
            occurrences(code, ": Boolean"),
        )
        val byFile = readMainSources()
        val ledger = byFile.entries.toList().sortedBy { it.key }
            .filter { occurrences(it.value, KERNEL_CALL) > 0 }
            .map { "${it.key}=${occurrences(it.value, KERNEL_CALL)}" }
        assertEquals(
            "**钉法 = 数出现次数 ＋ 落点名册**：`$KERNEL_CALL` 全 app/src/main 的册子必须恰好是 " +
                "{$WEB_NAME=1}（实到 " + ledger.joinToString(" ／ ") + "）：判据本体只有一份、调用点只有一处 —— " +
                "「返回布尔后调用点各自判一次」在本仓算违反，在这里就露形：册子里长出第二枚文件，" +
                "或 `$WEB_NAME` 那栏涨到 2 处，都是第二把尺",
            listOf("$WEB_NAME=1"),
            ledger,
        )
    }

    // ─────────────── ⑧ 反套套逻辑：探测器认得改前那一形 ───────────────

    @Test
    fun `探测器不是空转 改前那一形与只写显示位那一形都量得出来`() {
        // 改前 `refreshSchedule` 体内那两行原样（取证过一遍尺：代码栏 1 处、内核调用 0 处）
        val beforeShape = """
            val terms = fetcher.fetchTermList(web)
            val selectedTerm = terms.firstOrNull { it.selected }?.code ?: terms.firstOrNull()?.code
            if (selectedTerm == null) return Result.failure(IllegalStateException("无法获取学期列表"))
            val info = fetcher.fetchSemesterInfo(web, selectedTerm)
        """.trimIndent()
        assertEquals(
            "改前那一形里 `$SELECTED_ELSE_FIRST` 必须数到 1（实到 " + occurrences(beforeShape, SELECTED_ELSE_FIRST) +
                " 处）：② 那一格代码栏期望 0，靠的就是这一手能被数出来 —— 数不到就是尺空转",
            1,
            occurrences(beforeShape, SELECTED_ELSE_FIRST),
        )
        assertEquals(
            "改前那一形里内核调用必须 0 处（实到 " + occurrences(beforeShape, KERNEL_CALL) + " 处）：",
            0,
            occurrences(beforeShape, KERNEL_CALL),
        )
        // 只写显示位那一形（W 臂）：接线点被摘掉，显示位还在
        val displayOnlyShape = """
            fun refreshFromBuaa(termCode: String = ""): Boolean {
                if (termCode.isNotBlank()) _buaaTermCode.value = termCode.trim()
                buaaRefreshJob = viewModelScope.launch {
                    withImportLock {
                        val result = BuaaWebSession.refreshSchedule(
                            existingStartDate = repository.getCurrentSemester()?.startLocalDate,
                            onProgress = { week, total -> },
                        )
                    }
                }
                return true
            }
        """.trimIndent()
        assertEquals(
            "「只写显示位」那一形里 `$TERM_ARGUMENT` 必须 0 处（实到 " + occurrences(displayOnlyShape, TERM_ARGUMENT) +
                " 处），而 `$DISPLAY_ONLY_WRITE` 必须 1 处（实到 " +
                occurrences(displayOnlyShape, DISPLAY_ONLY_WRITE) + " 处）⇒ ④ 那两格（正向 1 ／ 反向 0）" +
                "合起来才拦得住这一手，单独任何一枚都拦不住",
            listOf(0, 1),
            listOf(occurrences(displayOnlyShape, TERM_ARGUMENT), occurrences(displayOnlyShape, DISPLAY_ONLY_WRITE)),
        )
        // 无条件覆写那一形（甲之后冷启动顶回 selected）
        val unconditionalSeed = """
            _buaaTermOptions.value = terms
            terms.firstOrNull { it.selected }?.let { _buaaTermCode.value = it.code }
        """.trimIndent()
        assertEquals(
            "⑤ 反向那一格的样例必须被数到 1 处 `$UNCONDITIONAL_SEED`（实到 " +
                occurrences(unconditionalSeed, UNCONDITIONAL_SEED) + " 处）—— 否则那一格的 0 是我自己没数到",
            1,
            occurrences(unconditionalSeed, UNCONDITIONAL_SEED),
        )
    }

    // ---------------- 取文本与小工具 ----------------

    /** 含注释的原样（② 那把「抹注释找锚点」尺的两栏之一） */
    private fun rawSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    /** 只抹注释、保留字符串字面量（失败消息里的行号因此可信） */
    private fun blanked(relativeFromMainJava: String): String =
        blankCommentsKeepingLiterals(rawSource(relativeFromMainJava))

    /** 文件名 → 抹注释后的内容（整棵 app/src/main/java，落点名册用） */
    private fun readMainSources(): Map<String, String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        val out = linkedMapOf<String, String>()
        files.forEach {
            check(!out.containsKey(it.name)) { "main 源码里有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n"))
        }
        assertEquals("扫 main 源码的文件数与建出来的册子条目数不等", files.size, out.size)
        return out
    }

    /**
     * 与 `SemesterInfoReasonGuardTest` 那一族同一算法（逐格改写、末尾整体物化），
     * 唯一差别是局部标识符的名字 —— 这是**故意**的：`CommentStrippingHelperShapeGuardTest`（另一枚在飞的卡
     * T401 独占、卡面 §3 禁改）第 ①②③④ 层把那几枚写死值钉在「一枚名为 `out` 的字符数组声明 ＋
     * 它的整体物化」那一族形状上（实到 139／139／1／140），照抄就会在别人那把尺上顶高四格。
     * 算法一字未改（同一套嵌套块注释、同一套字符串跳过），换掉的只是那一个局部名字。
     */
    private fun blankCommentsKeepingLiterals(source: String): String {
        val chars = source.toCharArray()
        var i = 0
        while (i < chars.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) chars.size else it }
                    for (k in i until nl) chars[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < chars.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(chars.size)) if (chars[k] != '\n') chars[k] = ' '
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) chars.size else it + 3 }
                }

                chars[i] == '"' || chars[i] == '\'' -> {
                    val quote = chars[i]
                    var j = i + 1
                    while (j < chars.size) {
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
        return String(chars)
    }

    private fun lineOf(code: String, index: Int): Int = code.substring(0, index).count { it == '\n' } + 1

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        if (needle.isEmpty()) return hits
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val WEB_SESSION = "com/buaa/schedule/data/import/BuaaWebSession.kt"
        const val WEB_NAME = "BuaaWebSession.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val VM_NAME = "ScheduleViewModel.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val HOME_NAME = "HomeScreen.kt"
        const val IMPORT_SCREEN = "com/buaa/schedule/ui/importing/ImportScreen.kt"
        const val IMPORT_NAME = "ImportScreen.kt"
        const val KERNEL = "com/buaa/schedule/domain/importing/RefreshTermSelection.kt"
        const val KERNEL_NAME = "RefreshTermSelection.kt"

        // ── ① 参数表 ──
        const val REFRESH_HEAD = "suspend fun refreshSchedule("
        const val TERM_PARAM = "termCode: String? = null,"
        const val REFRESH_SIGNATURE_TAIL = "): kotlin.Result<SemesterCourses>? {"

        // ── ② 改前两枚 `?:` 支与内核入参那一形 ──
        const val SELECTED_ELSE_FIRST = "?: terms.firstOrNull()?.code"
        const val SELECTED_TERM_DIRECT = "val selectedTerm = terms.firstOrNull"
        const val SELECTED_AS_ARGUMENT = "selectedCode = terms.firstOrNull { it.selected }?.code,"
        const val KERNEL_CALL = "RefreshTermSelection.choose("

        // ── ③ 三档分派 ──
        const val FETCH_ARM = "is RefreshTermChoice.Fetch -> choice.termCode"
        const val NOT_OFFERED_ARM = "is RefreshTermChoice.NotOffered -> return Result.failure("
        const val EMPTY_ARM = "RefreshTermChoice.NoOfferedList -> return Result.failure("

        // ── ④ ⑤ 调用点与显示位 ──
        const val REQUESTED_VAL = "val requestedTermCode = termCode.trim().takeIf { it.isNotBlank() }"
        const val TERM_ARGUMENT = "termCode = requestedTermCode,"
        const val DISPLAY_ONLY_WRITE = "if (termCode.isNotBlank()) _buaaTermCode.value = termCode.trim()"
        const val DISPLAY_WRITE_ON_SUCCESS = "_buaaTermCode.value = fetched.semester.termCode"
        const val REPLACE_CALL = "repository.replaceSemesterCourses(fetched.semester, fetched.courses)"
        const val LOCAL_CURRENT_READ = "val localCurrentTermCode = repository.getCurrentSemester()?.termCode"
        const val SEED_CHAIN =
            "(terms.firstOrNull { it.code == localCurrentTermCode } ?: terms.firstOrNull { it.selected })"
        const val UNCONDITIONAL_SEED =
            "terms.firstOrNull { it.selected }?.let { _buaaTermCode.value = it.code }"

        // ── ⑥ 默认档入口 ──
        const val DEFAULT_PARAM = "fun refreshFromBuaa(termCode: String = \"\"): Boolean"
    }
}
