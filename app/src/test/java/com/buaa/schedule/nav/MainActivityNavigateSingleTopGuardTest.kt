package com.buaa.schedule.nav

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MainActivity 里每一枚 `navigate(` 调用点的**单顶册子**（T344②，在册候选 T333-01）。
 *
 * ## 病灶
 * 用户快速双击首页任一课程卡／入口 ⇒ 同一 route 同 args 叠两枚 back-stack entry ⇒ 返回键要按两下
 * 才出得去。本卡之前盘上只有四枚调用式写了单顶（组件行点击那一路、通知落点那一路、「先清后跳」
 * 那一手、一级页助手自己），其余十四枚调用点全是裸 `navigate(route)`。
 * 带 `popUpTo("iclass_login") { inclusive = true }` 的那一枚最坏：登录连点会把扫码页叠两枚 ——
 * inclusive 的 popUpTo 只清得掉**还在栈上**的那枚登录页，清不掉第二枚扫码页。
 *
 * ## 为什么这里没有可抽的运行时判据（本仓那条「纯 JVM 内核 + 表驱动」规矩怎么落）
 * 本仓守卫的常规形状是把判据抽成一枚纯 JVM 函数、把时钟／权限当参数递进去。这一族抽不出那种内核：
 * 单顶是**调用点字面量**，没有任何运行期输入可以喂给它 —— 硬造一枚
 * `fun shouldBeSingleTop(route: String): Boolean` 就是把表抄第二遍还假装是判据。
 * 所以内核这一格换成**册子**：下面的 ROSTER 逐枚列「盘上第 N 枚调用式是谁、它的 route 原文是什么、
 * 它必须单顶」，对账对象是 MainActivity 的源码文本（抹注释之后）。表驱动仍然成立：每一格都是逐行走
 * 这张表，而不是给十八枚调用点写十八段散文断言。零 android import、不起 Robolectric。
 *
 * ## 第⓪问裁定（甲：逐处补单顶，不留例外枚）
 * 十八枚逐枚读过，没有一枚是**故意**要叠两枚的：
 * - editor 一族那两处 KDoc 以前写的是「不同 courseId ⇒ 不同 args ⇒ 单顶本来就不合并」—— **那句已被盘上
 *   读数推翻**：navigation 2.8.5 反汇编里 `launchSingleTopInternal` 偏移 300/305/308（NC.txt:4980/4982/4983）
 *   只比 `NavDestination.getId()`、整条函数一枚 arguments 都不比；替换条目走
 *   `NavBackStackEntry.<init>(NavBackStackEntry, Bundle)`（BSE.txt:166 起），偏移 21/25/29 连
 *   `viewModelStoreProvider`、`id` 与 `savedState` 一起复用旧条目，只有 arguments 是新的（偏移 15）。
 *   ⇒ 栈顶已是 `editor/{oldId}` 时再跳不同 courseId：不新增 entry、不换 entry.id、不换 savedState，
 *   只换 arguments。补单顶治的是「连点叠两枚」，它**不**负责「一屏一门课」—— 那一档归 T348② 那五格；
 * - 同 courseId 连续两枚 entry 唯一组得出来的来路就是连点，那正是要修的形状；
 * - 盘上早有两枚在册的 editor 单顶先例（组件那一路的 `editor/$id`、引导落点的 `editor/-1`），
 *   同一枚 destination、同一条理由 ⇒ 补参数改的是「叠不叠」，不是语义。
 * 丙（抽一枚全仓统一的 safeNavigate 助手）判死：`grep -rn 'fun.*navigate' app/src/main` 全仓只有一枚
 * `navigateTopLevel`，而它是 `popUpTo(graph.findStartDestination().id){saveState}` + `restoreState` 的
 * 一级页语义（NavHost 只有 home/import/settings 三枚一级条目），detail 页套进去会把来路那页从栈上弹掉、
 * 返回键从此回首页而不是回列表 —— 改的是行为不是写法，而且盘上没有「默认单顶的裸 push」这枚抽象，
 * 新造一枚就是本仓没有的形状。乙（只补守卫不补参数）判死：册子一旦等于「现状＝十四枚缺」，
 * 这一族就永远没有红的一天。
 * ⇒ 十七枚补字面 options lambda（与在册三枚逐字同形），一级页助手那一枚体内本就有那一行。
 *
 * ## 钉法族 = 数出现次数（T344③ 的三格在同一个类里）
 * ③(a) 一枚**不读册子**的独立尺：抹注释后逐枚解析盘上每一处 `navigate(`，缺单顶就红，并把那一枚的
 *   行号与原文打进断言消息。③(b) 反向一格：册子长度必须等于真实调用点数（本仓把这叫「第二枚读者」——
 *   有人新增一枚 `navigate(` 而册子没看见它，这一格必须当场响，尺子不许只会数自己认识的东西）。
 *   ③(c) 写法尺：带单顶的调用式枚数与调用点枚数同源，且不许有反着写的 `launchSingleTop = false`。
 *
 * ## T348② 追加的五格：编辑器那 17 枚草稿字段必须与「本屏请求的那门课」同源
 * 上面那把尺补的是「别叠两枚 entry」，而单顶复用 entry 时（偏移 25/29）**连 savedState 都复用** ⇒
 * 「一屏一门课」不能靠 entry 隔离，只能靠「重挂判据 + 存盘键」两半一起跟着请求 id 走。落点形状（甲＋乙同批）：
 * - 乙侧：17 枚草稿字段逐枚 `rememberSaveable(requestedCourseId)` —— 盘上compose 1.11.3 的
 *   `RememberSaveableKt` 偏移 325 `getValueIfInputsDidntChange`（`SaveableHolder` 偏移 0–18 就是 `Arrays.equals`）
 *   变了就走偏移 334 的 `init()` ⇒ 治「在屏换课」；
 * - 甲侧：MainActivity 的渲染闸门块体内一枚 `androidx.compose.runtime.key(courseId) { CourseEditorScreen(…) }`
 *   —— 它编成 movable group（本仓 `BuaaLoginScreenKt` 里 `key(retryToken)` 那枚唯一调用点交回 1 枚
 *   `Composer.startMovableGroup`），`GapComposer.updateCompositeKeyWhenWeEnterGroup` 偏移 229+ 把 object key 的
 *   hashCode 旋进 `compositeKeyHashCode`，而 rememberSaveable 缺省存盘键就是那枚 hash 的 36 进制字符串
 *   （`RememberSaveableKt` 偏移 48→86–114）⇒ 治「从同一份 savedState 按同位置自动键把 A 的草稿捞回 B」。
 * - **为什么不走「自定义 key 参数」那一手**（卡面先验说的「keys 同时改重挂判据与 registry 存盘键」在 androidx
 *   这一族里只有 `key` 形参能改存盘键，位置实参改不了）：compose 1.11.3 已经把 `rememberSaveable(..., key = ...)`
 *   **标成 @Deprecated**，编译器逐枚报「'rememberSaveable' with a custom 'key' is no longer supported. It
 *   bypasses positional scoping… Please remove the 'key' parameter to use positional scoping」（r.android.com/3610053）
 *   —— 拿一枚「官方宣布不再支持」的参数去钉一档不变量，就是给下一次 compose 升级埋一枚静默失效；
 *   官方指的那一手正是位置分格，也就是上面甲侧那一枚 wrapper。所以本卡落**甲＋乙同批**，不是纯乙。
 * 册子 = [DRAFT_FIELDS]（17 行，逐枚点名字段名）。
 * - **钉法族点名：逐字节锚点（抹注释后）＋ 数出现次数 ＋ 一枚读形状**。①（[everyRosteredDraftFieldCarriesTheRequestedCourseIdKey]）
 *   拿册子逐枚对：每一行的那一行开头必须在盘上恰好一处；②（[keyedDraftCallSitesNeitherOutnumberTheRosterNorFallBackToUnkeyed]）
 *   是反向格：带请求 id 的调用式枚数**不许多于**在册枚数（也不许少），且裸 `rememberSaveable {` 那一形 0 枚；
 *   ③（[theCourseIdKeyWrapsTheEditorSubtreeInsideTheRenderGate]）读形状：wrapper 恰好一枚且必须落在渲染闸门块**体内**，
 *   同时钉「闸门那一行逐字未动」（本卡没拧 `EditorSaveChainTailReceiverGuardTest` 与
 *   `CourseEditorDeleteExitReceiverGuardTest` 的 `RENDER_GATE`）；④（[requestedCourseIdIsEveryDraftsCompanionArg]）
 *   是不读册子的独立绝对值尺（`requestedCourseId` 在编辑器文件里 = 17 枚实参 + 1 枚声明 = 18）；
 *   ⑤（[requestedIdComesFromArgumentsAndCarriesNoDefault]）回到 MainActivity 侧钉键的**来源**：:967 现读 arguments
 *   那一行逐字、调用点 `requestedCourseId = courseId` 逐字、而形参不许有默认值（`requestedCourseId: Long =` 0 枚）。
 * - 朝宽怎么红：摘掉某一枚的位置实参（回到裸 `rememberSaveable {`）→ ①那一行念不到原文、②同时红两把
 *   （带键 16 ≠ 17、裸 1 ≠ 0）、④从 18 掉到 17；把 wrapper 撤一层 → ③红（0 枚）；把 wrapper 挪到闸门外面 →
 *   ③红（落在块内 = false）。
 * - 朝窄怎么红：从册子里摘一行 → ②（带键枚数 17 ≠ 册子 16）红；把键的实参换成常量
 *   （`rememberSaveable(0L)`）→ ①念不到原文、④从 18 掉到 17；新增一枚没进册子的带键字段 → ②红（多于在册）；
 *   新增一枚裸 rememberSaveable → ②红（unkeyed ≠ 0）；往闸门那一行里塞条件 → ③红（逐字那把先断）。
 * - 覆盖面边界（如实写）：这五格全是**源码形状**，一行组合期行为都没验到 —— 换课那一下草稿到底丢不丢、
 *   :290 那枚 shared element 重挂之后跳不跳卡、IME／滚动位置、脉冲是否照旧，都要装机；本卡零设备零 adb。
 *
 * ## 配对口径与覆盖面边界（如实写）
 * - 配对按**源码顺序**逐枚对：第 i 行册子对第 i 枚解析出来的调用式。中间插一枚新调用点会让后面全部错位，
 *   于是同时红在「原文片段读不到」与 ③(b) 的枚数格 —— 错位的响声比漏检贵得多，这是刻意的。
 * - `(route)` 那三枚（通知落点、一次性落点、一级页助手体内）实参原文相同，靠顺序与行名区分；
 *   真要换形状，先动这张表。
 * - 单顶只认字面量赋值那一行：写成 `launchSingleTop = false` 不算带单顶（③(c) 那格还单独数它）。
 * - 取法照本仓在册守卫（[com.buaa.schedule.ui.home.StatsEntryWiringGuardTest] 与
 *   [com.buaa.schedule.nav.NavRootStartDestinationGuardTest] 同一套刀法）：读工作树源码文本、匹配前先
 *   抹注释（MainActivity 的 KDoc 里就写着两处 `navigate("editor/{courseId}")` 那样的散文，连注释一起扫
 *   会数出二十枚）、实参表按括号配平取、options lambda 按花括号配平取、找不到锚点就抛、不用 assumeTrue 跳过。
 * - 只读 MainActivity 这一枚 main 文件：别的屏幕的调用点属于别的卡（本轮现量 app/src/main 里真的在叫
 *   navigate 的只有这一枚文件，另一处 KDoc 散文不算读者）。
 * - 屏上表现（连点之后返回键要按几下）本卡一格都没验到：零设备、零 adb。
 */
class MainActivityNavigateSingleTopGuardTest {

    /**
     * ② 表驱动：册子与盘上调用式逐枚配对，每一枚都必须带单顶。
     * 红的时候念的是「第几枚、叫什么、缺什么、原文是什么」，不是一句「数量不符」。
     */
    @Test
    fun everyRosteredNavigateCallSiteCarriesLaunchSingleTop() {
        val calls = navigateCallSites()
        val problems = mutableListOf<String>()
        if (calls.size != ROSTER.size) {
            problems.add(
                "册子 " + ROSTER.size + " 行 ／ 盘上实到 " + calls.size + " 枚：顺序配对从这一格起不可信" +
                    "（枚数那一格单独点名，这一格只把配对失败的逐枚念出来）",
            )
        }
        for (row in ROSTER) {
            val call = calls.getOrNull(row.ordinal - 1)
            if (call == null) {
                problems.add("#" + row.ordinal + " " + row.name + "：盘上没有第 " + row.ordinal + " 枚调用式")
                continue
            }
            for (needle in row.expectations) {
                if (!call.expression.contains(needle)) {
                    problems.add(
                        "#" + row.ordinal + " " + row.name + "：调用式里读不到原文片段「" + needle + "」" +
                            "（实到 " + call.describe() + "）",
                    )
                }
            }
            if (row.singleTopRequired && !call.singleTop) {
                problems.add("#" + row.ordinal + " " + row.name + "：缺 " + SINGLE_TOP + "（" + call.describe() + "）")
            }
        }
        assertTrue(
            "钉法 = 表驱动册子逐枚对账（抹注释后按源码顺序配对）：ROSTER 每一行在盘上有一枚调用式、" +
                "实参原文对得上、且带「" + SINGLE_TOP + "」。在册那几枚的写法是字面 options lambda，" +
                "补的那几枚逐字同形；一级页助手那一枚的单顶在它自己那枚多行 lambda 体内。" +
                "实到 " + calls.size + " 枚调用式 ／ 册子 " + ROSTER.size + " 行 ｜ 问题：" +
                problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /**
     * ② 裁定钉死：册子里「故意叠两枚」那一档必须 0 枚。
     * 有人想按乙处理某一枚（登记成「册子＝现状」而不补参数），就得先把那一枚点名进这一格的例外名单、
     * 并在上面的裁定一节写清为什么它非要两枚 entry —— 悄悄把某行改成 false 会当场红在这里。
     */
    @Test
    fun rosterGrantsNoExceptionToAnyCallSite() {
        val exempt = ROSTER.filter { row -> !row.singleTopRequired }.map { row -> "#" + row.ordinal + " " + row.name }
        assertTrue(
            "第⓪问裁定是甲：盘上没有一枚调用点是故意要叠两枚的。⚠️ 单顶治的是「连点叠栈」，它**不**负责" +
                "「一屏一门课」：navigation 2.8.5 盘上读数（NC.txt:4980/4982/4983 = 偏移 300/305/308 只比 " +
                "NavDestination.getId()；BSE.txt:166 起 = 偏移 21/25/29 把 viewModelStoreProvider、id 与 " +
                "savedState 一起复用旧条目）说得很清楚 —— 栈顶已是编辑器时再跳不同 courseId 不新建 entry、" +
                "不换 id、不换 savedState，只换 arguments ⇒ 那一档由本类 T348② 那五格（草稿键与请求 id 同源）钉。" +
                "例外档实到 " + exempt.size +
                " 枚 ／ 写死 " + EXPECTED_EXEMPT_ROWS + " 枚：" + exempt.joinToString(" ／ ") +
                "（要留例外就得连本段判据与类 KDoc 的裁定一起改，别只改表）",
            exempt.size == EXPECTED_EXEMPT_ROWS,
        )
    }

    /**
     * ③(a) 独立尺（钉法族 = 数出现次数）：**不读册子**，抹注释后逐枚解析盘上每一处 `navigate(`，
     * 缺单顶就红，并把那一枚的行号与原文打进断言消息。
     *
     * 这一格与 ②-a 判的是同一件事，但走的是两把独立的尺：②-a 拿册子去对盘（改表能绕过它），
     * 这一格只认盘（改表绕不过它，摘掉册子也不影响它）。在册那四枚的写法本来就是字面 options lambda，
     * 所以这一格不给「换个写法也算对」的余地：认的是 options lambda 里那一句字面量赋值。
     */
    @Test
    fun everyNavigateCallExpressionInMainActivityCarriesLaunchSingleTop() {
        val calls = navigateCallSites()
        val offenders = calls.filter { call -> !call.singleTop }.map { call ->
            "第 " + call.ordinal + " 枚（" + call.describe() + "）"
        }
        assertTrue(
            "钉法 = 数出现次数（只认盘，不读册子）：" + MAIN_ACTIVITY + " 里每一枚 navigate( 调用式" +
                "都必须带「" + SINGLE_TOP + "」—— 同一 route 同 args 叠两枚 back-stack entry 的代价是" +
                "返回键要按两下才出得去（带 popUpTo inclusive 的那一枚连点登录还会把扫码页叠两枚）。" +
                "实到 " + calls.size + " 枚调用式 ｜ 缺单顶 " + offenders.size + " 枚：" +
                offenders.joinToString(" ／ ") + "（原文与行号逐枚点名，不许只报枚数）",
            offenders.isEmpty(),
        )
    }

    /**
     * ③(b) 反向一格（本仓把这叫「第二枚读者」）：册子长度必须等于盘上真实调用点数，且两把都等于
     * ⓪ 步现量写死的那一枚 18。有人新增一枚 `navigate(` 而册子根本没看见它 —— 这一格必须当场响，
     * 尺子不许只会数自己认识的东西；反过来摘掉册子里的一行（朝窄那一臂）也一样红在这里。
     */
    @Test
    fun rosterLengthEqualsTheRealNavigateCallSiteCount() {
        val calls = navigateCallSites()
        val problems = mutableListOf<String>()
        if (calls.size != EXPECTED_NAVIGATE_CALL_SITES) {
            problems.add(
                "盘上实到 " + calls.size + " 枚调用式 ／ ⓪ 步现量写死 " + EXPECTED_NAVIGATE_CALL_SITES +
                    " 枚（裸 grep 原文 20 行 − KDoc 散文 2 行 = 18 枚）",
            )
        }
        if (ROSTER.size != calls.size) {
            problems.add(
                "册子 " + ROSTER.size + " 行 ／ 盘上 " + calls.size + " 枚：" +
                    (if (ROSTER.size < calls.size) {
                        "有人新增了 navigate( 调用点而册子没登记它"
                    } else {
                        "册子里有盘上没有的调用点（被摘掉或改了形状）"
                    }) +
                    " ｜ 盘上逐枚：" + calls.joinToString(" ／ ") { call -> "#" + call.ordinal + " " + call.describe() } +
                    " ｜ 册子逐枚：" + ROSTER.joinToString(" ／ ") { row -> "#" + row.ordinal + " " + row.name },
            )
        }
        if (ROSTER.size != EXPECTED_NAVIGATE_CALL_SITES) {
            problems.add("册子 " + ROSTER.size + " 行 ／ 写死 " + EXPECTED_NAVIGATE_CALL_SITES + " 行")
        }
        for (row in ROSTER) {
            if (row.ordinal != ROSTER.indexOf(row) + 1) {
                problems.add("册子顺序被重排：#" + row.ordinal + " " + row.name + " 不在第 " + (ROSTER.indexOf(row) + 1) + " 位")
            }
        }
        assertEquals(
            "钉法 = 反向一格（数出现次数）：册子与盘必须同源同数，两把都等于 ⓪ 步现量那一枚绝对值 —— " +
                "新增一枚调用点而册子没看见它，红在这一格（本仓把这叫「第二枚读者」）。问题：" +
                problems.joinToString(" ／ "),
            0,
            problems.size,
        )
    }

    /**
     * ③(c) 写法尺：带单顶的枚数与调用点枚数同源，且不许有反着写的那一枚。
     * 拦的是两档漂法：把某一枚单顶包进助手（在册四枚全是字面 options lambda，包起来就是换了写法），
     * 以及往某枚调用式里塞 `launchSingleTop = false`（那一枚字面上"有"单顶、语义上恰恰是要叠两枚）。
     */
    @Test
    fun singleTopOccurrencesAreOnePerCallSiteAndNoneOfThemIsFalse() {
        val code = blankedMainActivity()
        val written = occurrencesOf(code, SINGLE_TOP)
        val negated = occurrencesOf(code, SINGLE_TOP_FALSE)
        val calls = navigateCallSites()
        val withoutTheirOwn = calls.filter { call -> !call.options.contains(SINGLE_TOP) }
            .map { call -> "#" + call.ordinal + "（" + call.describe() + "）" }
        assertTrue(
            "钉法 = 数出现次数（在册写法同源）：" + MAIN_ACTIVITY + " 里「" + SINGLE_TOP + "」的代码级枚数" +
                "必须等于 navigate( 调用点枚数（每枚调用式自己带一行，谁也不替谁带），" +
                "且「" + SINGLE_TOP_FALSE + "」枚数为 0。实到单顶 " + written + " 枚 ／ 调用点 " + calls.size +
                " 枚 ／ 反写 " + negated + " 枚 ／ 自己没带的那几枚：" + withoutTheirOwn.joinToString(" ／ ") +
                "（注释里的命中已被抹掉，不计入任何一把）",
            written == calls.size && negated == 0 && withoutTheirOwn.isEmpty(),
        )
    }

    // ---- T348②：编辑器那 17 枚草稿字段与「本屏请求的那门课」同源（五格）---------------------------

    /**
     * ①（钉法 = 逐字节锚点，抹注释后）：册子逐枚对账。
     * 每一枚在册草稿字段，盘上那一行开头必须逐字读得到、且只读得到一次。
     */
    @Test
    fun everyRosteredDraftFieldCarriesTheRequestedCourseIdKey() {
        val code = blankedEditor()
        val problems = mutableListOf<String>()
        for (row in DRAFT_FIELDS) {
            val hits = occurrencesOf(code, row.needle)
            if (hits != 1) {
                problems.add(
                    "#" + row.ordinal + " " + row.name + "：逐字节锚点实到 " + hits + " 处（要 1 处）＝ " + row.needle,
                )
            }
        }
        assertTrue(
            "钉法 = 逐字节锚点（表驱动册子逐枚对账）：" + COURSE_EDITOR + " 里 " + DRAFT_FIELDS.size +
                " 枚草稿字段逐枚点名，每一枚都得带 " + DRAFT_KEY_ARG + " 那枚位置实参 —— 单顶复用同一枚 entry 时" +
                " id 与 savedState 都不换（2.8.5 偏移 21/25/29），而 compose 1.11.3 里能改 registry 存盘键的自定义" +
                " `key` 参数已经 deprecated ⇒ 存盘键只能靠 ③ 那枚 key(courseId) 走位置分格；两半缺一枚就是把 A 课" +
                "的草稿留在屏上写进 B 课的行。问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /**
     * ②（钉法 = 数出现次数；本仓把这叫「第二枚读者」）：反向格。
     * 带请求 id 键的草稿调用式枚数**等于**在册枚数（多一枚＝有人新增了没进册子的键，少一枚＝摘键／换成常量），
     * 且裸 `rememberSaveable {` 那一形必须 0 枚（新增一枚不带键的草稿字段红在这里）。
     */
    @Test
    fun keyedDraftCallSitesNeitherOutnumberTheRosterNorFallBackToUnkeyed() {
        val code = blankedEditor()
        val keyed = occurrencesOf(code, KEYED_DRAFT_CALL)
        val unkeyed = occurrencesOf(code, UNKEYED_DRAFT_CALL)
        val total = occurrencesOf(code, ANY_DRAFT_CALL)
        assertTrue(
            "钉法 = 数出现次数（反向格）：" + COURSE_EDITOR + " 里带请求 id 的草稿调用式（「" + KEYED_DRAFT_CALL +
                "…」）枚数必须等于在册枚数 " + EXPECTED_DRAFT_FIELDS + " 枚 —— 不许多（新增一枚没进册子的键）" +
                "也不许少（摘掉某一枚的键、或把键的实参换成常量）；同时「" + ANY_DRAFT_CALL + "」总枚数也必须等于 " +
                EXPECTED_DRAFT_FIELDS + " 枚（两把同源 ⇒ 没有第三形），裸「" + UNKEYED_DRAFT_CALL + "」那一形 0 枚。" +
                "实到：带键 " + keyed + " 枚 ／ 裸 " + unkeyed + " 枚 ／ 带括号总 " + total + " 枚 ／ 册子 " +
                DRAFT_FIELDS.size + " 行 ／ 写死 " + EXPECTED_DRAFT_FIELDS + " 枚",
            keyed == EXPECTED_DRAFT_FIELDS && keyed == DRAFT_FIELDS.size && total == EXPECTED_DRAFT_FIELDS &&
                unkeyed == 0,
        )
    }

    /**
     * ③（钉法 = 读形状，抹注释 + 花括号配平切块）：**甲侧那一半**必须真的站在渲染闸门里面。
     * `key(courseId) { CourseEditorScreen(…` 是 rememberSaveable 存盘键跟着换课走的唯一合法通道
     * （compose 1.11.3：自定义 `key` 参数已 deprecated，位置分格是官方指的那一手），而它一旦落到闸门外面，
     * 换课时重挂的就是整枚目的地（连同那两条退栈协程）—— 那是比串草稿更贵的一次事故。
     * 本格同时钉「渲染闸门那一行逐字未动」：`if (!uiState.loading && !courseMissing) {` 仍是 1 枚，
     * 在册的 `EditorSaveChainTailReceiverGuardTest`／`CourseEditorDeleteExitReceiverGuardTest` 才不会被本卡拧红。
     */
    @Test
    fun theCourseIdKeyWrapsTheEditorSubtreeInsideTheRenderGate() {
        val code = blankedMainActivity()
        val gateHits = occurrencesOf(code, RENDER_GATE)
        val wrapperHits = occurrencesOf(code, KEY_WRAPPER_OPEN)
        var wrapperInsideGate = false
        if (gateHits == 1 && wrapperHits == 1) {
            val gateAt = code.indexOf(RENDER_GATE)
            val gateBodyEnd = balancedBraceEnd(code, code.indexOf('{', gateAt + RENDER_GATE.length - 1))
            val wrapperAt = code.indexOf(KEY_WRAPPER_OPEN)
            wrapperInsideGate = wrapperAt > gateAt && wrapperAt < gateBodyEnd
        }
        assertTrue(
            "钉法 = 读形状（花括号配平取闸门块）：\"" + RENDER_GATE + "\" 必须逐字 1 枚（本卡没往这一行里塞东西），" +
                "=\"" + KEY_WRAPPER_OPEN + "\" 也必须 1 枚，且后者必须落在前者的块**体内** —— 落在外面就是拿整枚目的地" +
                "去重挂，那两条退栈协程跟着一起重建。实到：闸门 " + gateHits + " 枚 ／ wrapper " + wrapperHits +
                " 枚 ／ 落在块内 = " + wrapperInsideGate,
            gateHits == 1 && wrapperHits == 1 && wrapperInsideGate,
        )
    }

    /**
     * ④（钉法 = 数出现次数，**不读册子**的独立绝对值尺）：`requestedCourseId` 在编辑器那一棵里出现的枚数。
     * 17 枚位置实参 ＋ 形参声明那一枚 ＝ 写死的 18 枚。
     * 有人把某枚实参换成常量 ⇒ 这一格与 ① 一起红；把它改成**别的**变量（例如 `initialCourse?.id`）
     * ⇒ ① 红（原文念不到）＋这一格红（枚数掉下来）。
     */
    @Test
    fun requestedCourseIdIsEveryDraftsCompanionArg() {
        val code = blankedEditor()
        val mentions = occurrencesOf(code, DRAFT_KEY_ARG)
        assertTrue(
            "钉法 = 数出现次数（只认盘，不读册子）：\"" + DRAFT_KEY_ARG + "\" 在 " + COURSE_EDITOR +
                " 里必须恰好 " + EXPECTED_REQUESTED_ID_MENTIONS + " 枚（" + EXPECTED_DRAFT_FIELDS +
                " 枚位置实参 ＋ 形参声明 1 枚）—— 键的实参只许是本屏请求的那门课（MainActivity:967 现读 " +
                "arguments 那一枚），不许是保存后的新 id、不许是 `initialCourse?.id`（它对「查不到」与「新建」" +
                "两档退化成同一枚值）、不许是常量。实到 " + mentions + " 枚",
            mentions == EXPECTED_REQUESTED_ID_MENTIONS,
        )
    }

    /**
     * ⑤（钉法 = 逐字节锚点，跨两枚文件）：键的**来源**与**不许带默认值**。
     * - MainActivity 那一枚 `courseId` 必须仍是现读 arguments（不是 remember 的那份快照）；
     * - 递进编辑器的必须就是它（`requestedCourseId = courseId` 逐字一枚）；
     * - 形参必须是不带默认值的 `requestedCourseId: Long,`（`= 0L` 之类的默认值＝给「漏传」留门，
     *   而漏传就是这一族整条回来）。
     */
    @Test
    fun requestedIdComesFromArgumentsAndCarriesNoDefault() {
        val activity = blankedMainActivity()
        val editor = blankedEditor()
        val problems = mutableListOf<String>()
        if (occurrencesOf(activity, COURSE_ID_FROM_ARGS) != 1) {
            problems.add(
                "MainActivity 侧读请求 id 那一行实到 " + occurrencesOf(activity, COURSE_ID_FROM_ARGS) +
                    " 处（要 1 处）：「" + COURSE_ID_FROM_ARGS + "」",
            )
        }
        if (occurrencesOf(activity, REQUESTED_ID_PASSED) != 1) {
            problems.add(
                "调用点递键那一手实到 " + occurrencesOf(activity, REQUESTED_ID_PASSED) + " 处（要 1 处）：" +
                    "「" + REQUESTED_ID_PASSED + "」",
            )
        }
        if (occurrencesOf(editor, REQUESTED_ID_PARAM) != 1) {
            problems.add(
                "编辑器形参实到 " + occurrencesOf(editor, REQUESTED_ID_PARAM) + " 处（要 1 处）：" +
                    "「" + REQUESTED_ID_PARAM + "」",
            )
        }
        if (occurrencesOf(editor, REQUESTED_ID_DEFAULTED) != 0) {
            problems.add(
                "形参带上了默认值（「" + REQUESTED_ID_DEFAULTED + "」实到 " +
                    occurrencesOf(editor, REQUESTED_ID_DEFAULTED) + " 枚）＝漏传就是静默 no-op",
            )
        }
        assertTrue(
            "钉法 = 逐字节锚点（两枚文件同源）：键的实参取自目的地层**这一帧现读**的 arguments，" +
                "新建（-1）与「查不到」两档因此各有自己的键，谁也不对 null 退化；问题：" +
                problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    // ---- 读盘与解析（与各 *WiringGuardTest 同一套刀法）---------------------------------------------

    /** 抹注释之后的 CourseEditorScreen 源码文本（T348② 那五格的读盘口；与 MainActivity 同一把刀） */
    private fun blankedEditor(): String = blankComments(source(COURSE_EDITOR))

    /** 盘上每一枚 `navigate(` 调用式：实参表按括号配平、紧随其后的 options lambda 按花括号配平 */
    private fun navigateCallSites(): List<NavigateCall> {
        val code = blankedMainActivity()
        val out = mutableListOf<NavigateCall>()
        var from = 0
        while (true) {
            val at = code.indexOf(NAVIGATE_CALL, from)
            if (at < 0) break
            val open = at + NAVIGATE_CALL.length - 1
            val arguments = code.substring(open, balancedParenEnd(code, open) + 1)
            var next = open + arguments.length
            while (next < code.length && code[next].isWhitespace()) next++
            val options = if (next < code.length && code[next] == '{') {
                code.substring(next, balancedBraceEnd(code, next) + 1)
            } else {
                ""
            }
            out.add(NavigateCall(ordinal = out.size + 1, line = lineOf(code, at), arguments = arguments, options = options))
            from = at + NAVIGATE_CALL.length
        }
        assertTrue(
            "抹注释之后的 " + MAIN_ACTIVITY + " 里一枚 " + NAVIGATE_CALL + " 都没读到：文件读空了，" +
                "不要拿后面那些「实到 0」当绿",
            out.isNotEmpty(),
        )
        return out
    }

    /** 抹注释之后的 MainActivity 源码文本（长度与换行位置不变，报错消息里的行号因此可信） */
    private fun blankedMainActivity(): String = blankComments(source(MAIN_ACTIVITY))

    /** 数一枚针在文本里出现几次（与同族守卫同一把尺；注释已在文本里抹掉） */
    private fun occurrencesOf(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** 从 [open] 那枚左括号配平到与之匹配的右括号，交回右括号下标 */
    private fun balancedParenEnd(code: String, open: Int): Int {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw IllegalStateException(NAVIGATE_CALL + " 的实参表没配平：:" + lineOf(code, open))
    }

    /** 从 [open] 那枚左花括号配平到与之匹配的右花括号，交回右花括号下标 */
    private fun balancedBraceEnd(code: String, open: Int): Int {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw IllegalStateException("options lambda 的花括号没配平：:" + lineOf(code, open))
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与行位置不变）：与同族助手逐字同一写法 */
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
                            src.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }

                            src.startsWith("*/", j) -> {
                                depth--
                                j += 2
                            }

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
                            src[j] == quote -> {
                                j++
                                break
                            }

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

    /** 工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { candidate -> File(dir, candidate) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}

/**
 * 册子的一行：盘上第 ordinal 枚 `navigate(` 调用式（抹注释后按源码顺序）是谁、实参里必须读得到的
 * 原文片段是什么、要不要单顶。顺序即身份 —— 新增一枚调用点会让后面全部错位，同时被枚数那一格点名。
 */
private data class NavigateCallSite(
    val ordinal: Int,
    val name: String,
    val expectations: List<String>,
    val singleTopRequired: Boolean = true,
)

/** 一枚从源码里解析出来的调用式：实参表 + 紧随其后的 options lambda（没有就是空串） */
private data class NavigateCall(
    val ordinal: Int,
    val line: Int,
    val arguments: String,
    val options: String,
) {
    /** 调用式原文（锚点 + 实参表 + options lambda） */
    val expression: String get() = "navigate" + arguments + options

    /** 单顶只认字面量赋值那一行：`launchSingleTop = false` 不算带单顶 */
    val singleTop: Boolean get() = options.contains(SINGLE_TOP)

    /** 断言消息里的调用式摘要：行号 + 折成一行的原文 */
    fun describe(): String =
        ":" + line + " `" + expression.replace("\n", " ⏎ ").replace(Regex("[ ]+"), " ") + "`"
}

/** ② 的册子：十八枚调用点逐枚点名（行号只当线索，判据认的是顺序与原文） */
private val ROSTER: List<NavigateCallSite> = listOf(
    NavigateCallSite(1, "组件行点击 → 编辑器（在册：requestedCourseId 那一路）", listOf("\"editor/\$id\"")),
    NavigateCallSite(2, "通知落点 → 任意路由（在册：requestedRoute 那一路）", listOf("(route)")),
    NavigateCallSite(3, "引导落点里的一次性路由（在册：先清后跳，editor/-1 与 import）", listOf("(route)")),
    NavigateCallSite(
        4,
        "openSpocSignIn → 扫码页／登录页分流（多行实参表）",
        listOf("\"spoc_scan\"", "\"iclass_login\"", "IClassSession.hasSession()"),
    ),
    NavigateCallSite(5, "openIClassSignIn → iClass 登录页", listOf("\"iclass_login\"")),
    NavigateCallSite(6, "openSettingsSection → 设置子页", listOf("\"settings/\${section.id}\"")),
    NavigateCallSite(7, "openCourseManagement → 课程管理", listOf("\"course_management\"")),
    NavigateCallSite(8, "openStats → 学期统计", listOf("\"stats\"")),
    NavigateCallSite(9, "HomeScreen.onAddCourse → 新增课程编辑器", listOf("\"editor/-1\"")),
    NavigateCallSite(10, "HomeScreen.onImportBuaa → 教务登录页", listOf("\"buaa_login\"")),
    NavigateCallSite(11, "HomeScreen.onCourseManagement → 课程管理", listOf("\"course_management\"")),
    NavigateCallSite(12, "HomeScreen.onCourseClick → 那一门课的编辑器", listOf("\"editor/\${course.id}\"")),
    NavigateCallSite(13, "CourseManagementScreen.onEditCourse → 那一门课的编辑器", listOf("\"editor/\${course.id}\"")),
    NavigateCallSite(14, "ImportScreen.onStartBuaaLogin → 教务登录页", listOf("\"buaa_login\"")),
    NavigateCallSite(15, "ImportScreen.onOpenHistory → 导入历史", listOf("\"import_history\"")),
    NavigateCallSite(16, "SpocScanScreen.onNeedLogin → iClass 登录页", listOf("\"iclass_login\"")),
    NavigateCallSite(
        17,
        "IClassLoginScreen.onLoggedIn → 扫码页（唯一一枚带 popUpTo inclusive 的）",
        listOf("\"spoc_scan\"", "popUpTo(\"iclass_login\") { inclusive = true }"),
    ),
    NavigateCallSite(
        18,
        "navigateTopLevel 体内（一级页助手自己：popUpTo + 单顶 + restoreState）",
        listOf("(route)", "popUpTo(graph.findStartDestination().id)", "restoreState = true"),
    ),
)

/** 钉的是「在册写法」那一句字面量：改一个字符就红 */
private const val SINGLE_TOP = "launchSingleTop" + " = true"

/** 反着写的那一枚：字面上"有"单顶、语义上恰恰要叠两枚，③(c) 那格数它 0 枚 */
private const val SINGLE_TOP_FALSE = "launchSingleTop" + " = false"

/** 调用式锚点：`navigate(` 紧跟左括号，`navigateTopLevel(` 这一族同名字落不进尺子 */
private const val NAVIGATE_CALL = "navigate" + "("

/** ② 裁定钉死：例外档写死 0 枚 */
private const val EXPECTED_EXEMPT_ROWS = 0

/** ⓪ 步现量写死的调用点枚数：裸 grep 原文 20 行 − KDoc 散文 2 行 = 18 枚（③(b) 那格的绝对值） */
private const val EXPECTED_NAVIGATE_CALL_SITES = 18

private const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"

/**
 * T348② 的册子一行：盘上第 ordinal 枚草稿字段叫什么。
 * [needle] 就是盘上那一行**开头**的逐字节原文 —— 尾部为什么不上册子：weeksText／creditText／advanceMinutes
 * 三枚的初值是跨行块，把尾部钉进册子会逼着改动行号（本卡零行漂移），而这一族的判据是「草稿与请求 id 同源」，
 * 尾部是初值的事、归 baselineDraft 与那些在册尺管。
 */
private data class DraftFieldRow(
    val ordinal: Int,
    val name: String,
) {
    /** 逐字节锚点：`var <字段> by rememberSaveable(requestedCourseId) {` */
    val needle: String
        get() = "var " + name + " by rememberSaveable(" + DRAFT_KEY_ARG + ") {"
}

/**
 * 17 枚在册草稿字段（bf71058 现量：`grep -c rememberSaveable` 交回 19，其中 :45 是 import、
 * :109 是注释 ⇒ 真调用点 17 枚）。顺序即盘上源码顺序，与 [everyRosteredDraftFieldCarriesTheRequestedCourseIdKey]
 * 的逐枚对账同源。
 */
private val DRAFT_FIELDS: List<DraftFieldRow> = listOf(
    DraftFieldRow(1, "name"),
    DraftFieldRow(2, "teacher"),
    DraftFieldRow(3, "location"),
    DraftFieldRow(4, "campus"),
    DraftFieldRow(5, "day"),
    DraftFieldRow(6, "startSection"),
    DraftFieldRow(7, "endSection"),
    DraftFieldRow(8, "extraPeriods"),
    DraftFieldRow(9, "weeksText"),
    DraftFieldRow(10, "alias"),
    DraftFieldRow(11, "creditText"),
    DraftFieldRow(12, "colorIndex"),
    DraftFieldRow(13, "customColor"),
    DraftFieldRow(14, "reminderEnabled"),
    DraftFieldRow(15, "advanceMinutes"),
    DraftFieldRow(16, "partialWeeks"),
    DraftFieldRow(17, "applyToGroup"),
)

/** 键的实参那一枚（MainActivity:967 现读 arguments 的请求 id；不许是常量、不许是保存后的新 id） */
private const val DRAFT_KEY_ARG = "requestedCourseId"

/** 带请求 id 的草稿调用式锚点（位置实参那一枚 —— 存盘键跟着 ③ 那枚 wrapper 走位置分格） */
private const val KEYED_DRAFT_CALL = "rememberSaveable(" + DRAFT_KEY_ARG + ") {"

/** 裸那一形：新增一枚不带键的草稿字段就红在反向格 */
private const val UNKEYED_DRAFT_CALL = "rememberSaveable {"

/** 编辑器那一棵里所有 rememberSaveable 调用式（带左括号 ⇒ 不含 import 那一行） */
private const val ANY_DRAFT_CALL = "rememberSaveable("

/** ⓪/① 步现量写死的在册枚数（反向格的两把尺都对它账） */
private const val EXPECTED_DRAFT_FIELDS = 17

/** ④ 那把独立绝对值尺：17 枚位置实参 ＋ 形参声明 1 枚 */
private const val EXPECTED_REQUESTED_ID_MENTIONS = 18

private const val COURSE_EDITOR = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"

/** ③：渲染闸门那一行逐字（本卡不许往这一行里塞东西 —— 两枚在册守卫都钉它） */
private const val RENDER_GATE = "if (!uiState.loading && !courseMissing) {"

/** ③：甲侧 wrapper 逐字（不换 import ⇒ 全限定名，行号因此一枚都不漂） */
private const val KEY_WRAPPER_OPEN =
    "androidx.compose.runtime.key(courseId) { CourseEditorScreen("

/** ⑤：键的来源那一行逐字（目的地层现读 arguments，不是 remember 住的那份快照） */
private const val COURSE_ID_FROM_ARGS =
    "val courseId = backStackEntry.arguments?.getLong(\"courseId\") ?: -1L"

/** ⑤：调用点递键那一手逐字 */
private const val REQUESTED_ID_PASSED = "initialCourse = course, requestedCourseId = courseId,"

/** ⑤：形参逐字（与 initialCourse 同一条声明行 —— 本卡零行漂移，不动任何锚点行号） */
private const val REQUESTED_ID_PARAM = "initialCourse: Course?, requestedCourseId: Long,"

/** ⑤：带默认值那一形必须 0 枚（默认值＝漏传就是静默 no-op） */
private const val REQUESTED_ID_DEFAULTED = "requestedCourseId: Long ="

/** 从文本开头数到 [index] 落在第几行（1-based，与 grep -n 同口径） */
private fun lineOf(text: String, index: Int): Int {
    var line = 1
    var at = 0
    while (at < index && at < text.length) {
        if (text[at] == '\n') line++
        at++
    }
    return line
}
