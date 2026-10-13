package com.buaa.schedule.ui.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T177：钉住**「『这门课刚被这一页删掉』那面旗的载体必须跨配置变化存活、仍按 entry 隔离、
 * 立旗仍排在 await 之前、点火即消费仍排在退栈之前」这一族**。
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟），读法与小工具抄
 * `ui/editor/CourseEditorDeleteExitReceiverGuardTest`（本卡就是它那一族的残窗）。
 *
 * ## 病（#211 = T166 的残窗：「删除已在途」这一刻转屏，仍留一页空白）
 *
 * T166 把退栈搬到目的地层那枚 `LaunchedEffect(deleteRequestedByThisEntry, courseMissing, uiState.loading)`
 * 上，旗标本体当时登在 `remember(backStackEntry) { mutableStateOf(false) }` 上。键里带 `backStackEntry`
 * 只解决「换一条 entry 要换一面旗」，**不解决转屏**：配置变化整棵重建这棵 composition，`remember` 的槽位
 * 连同值一起消失，新 composition 里这枚旗从 `init` 重新算起 = false。于是
 * 1. 用户点删除 → 立旗 → `deleteCourseAndAwait` 还没回；
 * 2. 这一刻转屏 ⇒ 旗没了（`remember` 不跨配置变化）；
 * 3. 删除落地、新的 `uiState` 到达 ⇒ `courseMissing` 翻 true、`loading` 已经是 false，
 *    但 `:899` 那枚协程三条条件里少掉旗标那一条 ⇒ **不弹栈**；
 * 4. 渲染闸门 `if (!uiState.loading && !courseMissing)` 照样把编辑器整棵摘着 ⇒ 用户停在一页
 *    板上什么都没有的编辑器上，要再按一次返回才是首页 —— T166 装机两条读数没盖住的正是这一档。
 *
 * ## 修法（本卡的载体选择，两侧都能从盘上核）
 *
 * 换成本条 entry 的 `savedStateHandle`：读 `getStateFlow(key, false).collectAsState()`、
 * 写 `savedStateHandle[key] = true`。反汇编 navigation-common / navigation-runtime /
 * lifecycle-viewmodel-savedstate 2.8.7 拿到的链条是：
 * `NavController.saveState()` 只遍历 `backQueue`（= **此刻还在栈里**的那些 entry）为每一枚造
 * `NavBackStackEntryState`，而 `NavBackStackEntryState` 的构造里逐枚调 `entry.saveState(bundle)` →
 * `SavedStateRegistryController.performSave` → `SavedStateHandleSupport` 早先替这枚 entry
 * `registerSavedStateProvider` 过的那枚 `SavedStateHandlesProvider.saveState()` 把 handle 的 map
 * 写进 Bundle ⇒ **转屏存活**；restore 那一头 `NavBackStackEntry.updateState()` 里
 * `performAttach()` + `performRestore(savedState)`，之后 `getStateFlow(key, false)` 从回灌好的 map
 * 取值（`SavedStateHandleImpl.getStateFlow` 只在键**不在** map 里时才 put 默认值）⇒ 读得到 true。
 * 另一半：「按 entry 隔离」只在**新建 entry** 那一路成立 —— 栈顶不是编辑器时 `navigate("editor/{courseId}")`
 * 才新建一枚 entry、新建一份 handle；栈顶已是编辑器时单顶**复用同一枚 entry**（2.8.5 反汇编：单顶只比
 * destination.id，替换条目连 id 与 savedState 一起复用 ⇒ 这面旗跟着复用，T348 订正）。全局旗仍然不许。
 *
 * ## 「只点火一次」这条不变式换载体之后怎么剩下来的
 *
 * 旧论证（`:894` 原话）是「三条键在点火之后都不再变」。换到 savedStateHandle 之后它**不再成立**：
 * `loading` 确实不再翻、被删那一行确实不会自己回来，但**旗标如今跨转屏存活**，而转屏会把这枚协程
 * 连 composition 一起重建 —— 三条键一个都没变，体也要重跑一遍。所以本卡把它换成**点火即消费**：
 * 协程体内先 `savedStateHandle[key] = false`、再退栈。两句之间没有挂起点 ⇒ 这一趟一定弹得出去；
 * 消费之后三条条件少一条 ⇒ 任何重启（转屏、键变化）都点不动第二次，于是「pop 已在途 / pop 完但
 * 退出动画还没走完」这一刻转屏不会把用户从首页再往后多弹一格。而「删除在途时转屏」那一档**不受影响**：
 * 那次转屏时没人写过 false，旗仍然立着 ⇒ 新的 composition 里三条一齐就照样弹（这一档是本卡要修的）。
 * 消费为什么写回 false 而不是 `remove(key)`：`SavedStateHandleImpl.remove` 会把 `getStateFlow`
 * 缓存的那枚 flow 一并从 map 里摘掉，之后写同一个键就不再通知正在 collect 的协程 —— 摘掉缓存比写
 * false 更狠，本卡不需要它。所以「不许改成 remove」也是第 ④ 层的一格。
 *
 * ## 钉法（两侧都有格子）
 *
 * 1. [这面旗不再由只活到配置变化那一秒的容器供给]：旧逐字形 0 处 + `deleteRequestedByThisEntry by remember`
 *    那一族 0 处（**朝宽**：把它改回裸 `remember` 就在这一格红）+ 三行声明逐字（必须过
 *    `backStackEntry.savedStateHandle` → `getStateFlow(…, false)` → `collectAsState()`，
 *    **朝窄**：整枚声明摘掉、或换成不产生 recomposition 的裸 `get<Boolean>()` 都在这一格红）
 *    + **落点名册**（一枚声明 + 三枚读者逐枚点名，再判「盘上枚数 == 名册数到枚数」；新增一枚没在册的
 *    读者当场红 —— 裸枚数判不出「多出来的是谁」，而本仓那条成文教训要的正是先裁再钉）。
 * 2. [立旗那一手仍排在 await 之前]：`onDelete` lambda 体内两枚逐字各恰好一处 + **次序断言**
 *    （本仓「顺序决定生死」那一族的老规矩：只断言"两处都在"不够）+ 全文件立旗恰好一处。
 * 3. [「从来没读到」那一枚协程的键集合与体逐字未动]：六枚**原文切片**整段逐字比，不用行号 ——
 *    键表一行（T335 之后仍是 `(courseId, uiState.loading)` 两枚）+ `when (EditorMissingCourseExit.verdictOf(`
 *    + 三枚具名实参。往键表里加 `courseMissing`、把 `dataLoaded = !uiState.loading` 换成 `true`、
 *    在调用点重算 `courseMissing` 这几条路都在这一格红；另两格钉四档表的两头（能弹的恰好两档、
 *    不弹的两档不许折进 `else`），体内退栈枚数与「能弹的档」同源（2），所以「多写一枚 pop」也红。
 * 4. [点火即消费排在退栈那一手之前]：退栈协程体内条件逐字一处、`if (` 恰好一处（三条条件一条不多
 *    一条不少）、消费一处、pop 一处，且**消费 index 落在 pop index 之前**；再钉「不许换成 remove」。
 * 5. [旗只属于这一条 entry]：目的地块内 `backStackEntry.savedStateHandle` 恰好三处（读 + 写 + 消费）、
 *    `currentBackStackEntry` 0 处、键常量声明恰好一处、`savedStateHandle.get<Boolean>(` 0 处
 *    （读了不订阅 = 删成的那一刻协程不重启，等于本卡白修）。
 *
 * 本守卫只读源码，**不证明**运行时行为：转屏那一档（删除在途中转屏 ⇒ 应回首页 / pop 之后立刻转屏
 * ⇒ 不许连弹两格）归编排者装机。
 */
class DeleteFlagConfigSurvivalGuardTest {

    // ─────────────── ① 载体：不再是只活到配置变化那一秒的那一枚 ───────────────

    @Test
    fun `这面旗不再由只活到配置变化那一秒的容器供给`() {
        val code = blankCommentsKeepingLiterals(source(MAIN_ACTIVITY))

        // 朝宽那一扭就在这一格红：把旗改回 T166 当时登在盘上的那一形
        assertEquals(
            "旧那一形 `$OLD_FLAG_DECL` 必须 0 处 —— 它**不跨配置变化**：转屏整棵重建 composition，" +
                "`remember` 槽位的值丢回 init()，于是「删除已在途」这一刻转屏就没人再弹栈（#211 的病本体）。" +
                "实到 " + occurrences(code, OLD_FLAG_DECL) + " 处",
            0,
            occurrences(code, OLD_FLAG_DECL),
        )
        // 更宽的一族一起拦：带不带键、mutableStateOf 写什么都不行（composition 活多久旗就活多久）
        assertFalse(
            "`deleteRequestedByThisEntry` 不许再由 `remember(...)` / `remember { }` 供给（正则：`" +
                COMPOSITION_BACKED + "` 实到 " + Regex(COMPOSITION_BACKED).findAll(code).count() +
                " 处）：这一枚旗要的是**比这棵 composition 更长**的寿命，而 `remember` 给的正是 composition " +
                "那么长。想给旗换容器可以，但那条路得先回答「转屏之后它还在吗」",
            Regex(COMPOSITION_BACKED).containsMatchIn(code),
        )
        // 声明只许一枚：两枚 = 两把尺，删掉的那一枚还会静默留在原地
        assertEquals(
            "`deleteRequestedByThisEntry by ` 恰好一处（它是这条退栈链唯一的凭据；实到 " +
                occurrences(code, "$FLAG_NAME by ") + " 处）",
            1,
            occurrences(code, "$FLAG_NAME by "),
        )
        // 朝窄：整枚声明摘掉、或换成「读了不订阅」的那一等，都在这一格红
        val declHits = indexOfAll(code, DECL_HEAD)
        assertEquals(
            "`$DECL_HEAD` 恰好一处（旗标的读取入口；实到 " + declHits.size + " 处）：" +
                "摘掉它 = 删成之后没人认得这页删过课 = 整屏空白那一档原样回来",
            1,
            declHits.size,
        )
        val declared = lineSlice(code, declHits.first(), DECL_SLICE.size)
        assertEquals(
            "旗标的读取入口三行逐字仍是「读本条 entry 的 savedStateHandle → 用 getStateFlow(key, false) " +
                "取那枚缓存的 flow → collectAsState() 让它产生 recomposition」。第三行尤其不许省：" +
                "`backStackEntry.savedStateHandle[KEY] = true` 只更新 handle 里那枚 flow，" +
                "没有 collectAsState() 就没有 State 读、协程那三枚键不会翻面 ⇒ 删成的那一刻协程不重启" +
                "（等于没修）。实到：\n" + declared.joinToString("\n  ", prefix = "  ") +
                "\n  期望：\n  " + DECL_SLICE.joinToString("\n  "),
            DECL_SLICE,
            declared,
        )
        // 这一族的落点：**逐枚点名 + 盘上枚数与名册同源**（不是一枚裸枚数）
        for ((role, landing) in FLAG_LANDING_ROSTER) {
            assertEquals(
                "**名册逐枚点名**：$role 那一枚落点逐字必须恰好一处（针 `$landing` 实到 " +
                    occurrences(code, landing) + " 处）：摘掉它或换了它的写法 = 名册里那一枚读者从盘上消失，" +
                    "而本守卫不替它改判 —— 要么连名册一起显式销账（并说清那一枚凭什么不再需要），" +
                    "要么它就不该被摘",
                1,
                occurrences(code, landing),
            )
        }
        val rosterHits = FLAG_LANDING_ROSTER.sumOf { occurrences(code, it.second) }
        assertEquals(
            "`$FLAG_NAME` 这一族在**代码**里（注释已抹）的每一枚落点都必须被上面那本名册点名：" +
                "盘上实到 " + occurrences(code, FLAG_NAME) + " 处、名册只数到 " + rosterHits + " 处，逐处是 " +
                hint(source(MAIN_ACTIVITY), FLAG_NAME) +
                "：**多出来的那一枚 = 有新人没经点名就来读这面旗** —— 本仓成文教训（台账 #523）是" +
                "「配对守卫拦下的第二枚读者是卡面缺陷，不许朝上重钉裸枚数」，正解是先裁它合不合法、" +
                "合法才加进名册；名册里有而盘上没有 = 某一枚落点被搬走或改了名（上面那一圈已先红）",
            rosterHits,
            occurrences(code, FLAG_NAME),
        )
    }

    // ─────────────── ② 次序：立旗仍然排在 await 之前 ───────────────

    @Test
    fun `立旗那一手仍然排在 deleteCourseAndAwait 之前`() {
        val code = blankCommentsKeepingLiterals(source(MAIN_ACTIVITY))

        val lambdaHits = indexOfAll(code, ON_DELETE_ANCHOR)
        assertEquals("`$ON_DELETE_ANCHOR` 恰好一处（本层取块的宿主）：", 1, lambdaHits.size)
        val lambda = braceBodyFrom(code, code.indexOf('{', lambdaHits.first()))

        assertEquals(
            "体内 `$AWAIT_DELETE` 恰好一处（实到 " + occurrences(lambda, AWAIT_DELETE) +
                " 处）：换载体那一手不许顺手把删除链从 AndAwait 挪回 fire-and-forget",
            1,
            occurrences(lambda, AWAIT_DELETE),
        )
        assertEquals(
            "体内 `$FLAG_SET` 恰好一处（实到 " + occurrences(lambda, FLAG_SET) + " 处）；" +
                "全文件也只许一处（实到 " + occurrences(code, FLAG_SET) +
                " 处）：立旗这一手不许被搬到 VM / 编辑器里 / 那枚协程体内 —— 搬到 await 之后那一侧" +
                "就等于搬回那枚会被取消的 scope 上",
            1,
            occurrences(code, FLAG_SET),
        )
        assertTrue(
            "次序必须是 **立旗 → await**：`$FLAG_SET` 落在体内 index=" + lambda.indexOf(FLAG_SET) +
                "，必须排在 `$AWAIT_DELETE` 的 index=" + lambda.indexOf(AWAIT_DELETE) + " 之前。" +
                "这一整支跑在编辑器的组合期 scope 上：删成之后 uiState 落地、闸门把编辑器摘出组合 ⇒ " +
                "这一支多半活不到 await 回来那一刻，把立旗排在它后面 = 那面旗十有八九立不起来 = " +
                "退栈永远不点火 = T166 与 #211 两档一起复活（本仓「顺序决定生死」那一族的老规矩：" +
                "这一格是**次序断言**，两处都在不算过）",
            lambda.indexOf(FLAG_SET) in 0 until lambda.indexOf(AWAIT_DELETE),
        )
        assertEquals(
            "`$ON_DELETE_ANCHOR` 体内 `$POP_BACK_STACK` 必须 0 处（实到 " +
                occurrences(lambda, POP_BACK_STACK) + " 处）、`onBack(` 必须 0 处（实到 " +
                occurrences(lambda, "onBack(") + " 处）：这一族的退栈只归目的地层那枚协程认领",
            true,
            occurrences(lambda, POP_BACK_STACK) == 0 && occurrences(lambda, "onBack(") == 0,
        )
    }

    // ─────────────── ③ 「从来没读到」那一档：键与体逐字未动 ───────────────

    /**
     * R5 F-41 那一半的原意：这一枚协程**只管**「这门课从来没读到」，键表里没有 `courseMissing`，
     * 为的就是不在「首帧课程列表为空 / 刚切学期数据还没到」那种帧里弹栈。卡面红线是「那一枚协程
     * 与它的键，一个字节都不许多动」，所以这一层不用行号（本仓行号锚点会漂），拿**原文切片**整段逐字比。
     *
     * T335（台账 #388）之后「体」换了写法：裸 `if (!uiState.loading && courseMissing) { popBackStack() }`
     * 改成「问 `EditorMissingCourseExit.verdictOf` 四档、按档念/退」。键表那一行**一字未动**，
     * 而原来那三格各自换成新的参照物：
     * - 「`!uiState.loading` 排在前面 = 数据还没到就不判」→ 现在是递给内核的 `dataLoaded = !uiState.loading`
     *   这枚具名实参（判据本体第一档 `!dataLoaded -> DataNotReadyYet` 的顺序由
     *   `domain/editor/EditorMissingCourseExitTest` 逐格钉，本层钉的是调用点递进去的那一枚）；
     * - 「退栈那一手只有一枚」→ 现在是四档里**互斥的两档**各一枚（`PopWithMessage` / `PopWithoutMessage`），
     *   一次点火仍只弹一格；两档「不弹」的必须点名写着，不许折进 `else`；
     * - 「多写一枚 pop」→ 枚数与那两支同源：并档（两支合成一支）或添第三枚退栈手都会红。
     */
    @Test
    fun `从来没读到那一枚协程的键集合与体逐字未动`() {
        val raw = source(MAIN_ACTIVITY)
        val code = blankCommentsKeepingLiterals(raw)

        val hits = indexOfAll(code, NEVER_READ_ANCHOR)
        assertEquals(
            "`$NEVER_READ_ANCHOR` 恰好一处（这一枚协程的键表；实到 " + hits.size + " 处）：" +
                "往键表里加 `courseMissing`（= 把两档并成一档，于是「数据还没到」那一帧也弹栈 = " +
                "用户读作「点了没反应」）、或改成 `LaunchedEffect(courseMissing)`，都从这一格露出来",
            1,
            hits.size,
        )
        val slice = lineSlice(code, hits.first(), NEVER_READ_SLICE.size)
        assertEquals(
            "那枚协程**连同它递给内核的三枚事实**逐字节未动（键表一行 + `when (` + 内核一行 + 三枚具名实参）。" +
                "`dataLoaded = !uiState.loading` 是「数据还没到就不判」那一半（把它换成 `dataLoaded = true` " +
                "或整枚摘掉 = 在首帧弹栈，R5 F-41 原案）；`courseMissing = courseMissing` 递的是目的地层" +
                "那枚唯一尺，不许在这一行重算成第二把尺；键表那一行多一枚键就是两档并一档：" +
                "\n  期望：\n    " + NEVER_READ_SLICE.joinToString("\n    ") +
                "\n  实到：\n    " + slice.joinToString("\n    "),
            NEVER_READ_SLICE,
            slice,
        )
        val body = braceBodyFrom(code, hits.first() + NEVER_READ_ANCHOR.length - 1)
        val popBranches = body.lines().map { it.trim() }.filter { it.startsWith("MissingCourseExitVerdict.Pop") }
        assertEquals(
            "体内**能弹栈的档**必须恰好这两枚、逐字各一行（实到：\n    " + popBranches.joinToString("\n    ") +
                "）：`PopWithoutMessage` 那一支是「本页自己删的」⇒ 只退不念，给它添上念话就是对着" +
                "用户亲手删掉的课谎报死因；把两支并成 `PopWithMessage, PopWithoutMessage ->` 又折回同一句" +
                "两档，而本仓红线是那两档必须分开认领",
            NEVER_READ_POP_BRANCHES,
            popBranches,
        )
        val silentBranches = body.lines().map { it.trim() }.filter {
            it.startsWith("MissingCourseExitVerdict.CourseIsOnScreen") ||
                it.startsWith("MissingCourseExitVerdict.DataNotReadyYet") || it == "-> Unit"
        }
        assertEquals(
            "两档「什么都不做」必须还点名写出来、没收进 `else`（实到：\n    " +
                silentBranches.joinToString("\n    ") + "）：`DataNotReadyYet` 那一档就是「数据还没到」" +
                "那一半的落点，把它折进 else 的话内核将来多一档会被静默咽掉（台账 #370/#251-04 那一族）",
            NEVER_READ_SILENT_BRANCHES,
            silentBranches,
        )
        assertEquals(
            "那枚协程体内 `$POP_BACK_STACK` 恰好 " + NEVER_READ_POP_COUNT + " 枚（实到 " +
                occurrences(body, POP_BACK_STACK) + " 处）：与上面那两支同枚 —— 一支一枚，一次点火只弹一格。" +
                "**三枚**就是这一档多开一路退栈，**一枚**就是把两档并成了一档（上面那一格会一起红）",
            NEVER_READ_POP_COUNT,
            occurrences(body, POP_BACK_STACK),
        )
    }

    // ─────────────── ④ 点火即消费：排在退栈那一手之前 ───────────────

    @Test
    fun `点火即消费那一手排在退栈之前`() {
        val code = blankCommentsKeepingLiterals(source(MAIN_ACTIVITY))

        val effectHits = indexOfAll(code, EXIT_EFFECT)
        assertEquals(
            "`$EXIT_EFFECT` 恰好一处，且**键表逐字没动**（旗标只是换了容器，三枚键一枚不多一枚不少）：" +
                "实到 " + effectHits.size + " 处",
            1,
            effectHits.size,
        )
        val body = braceBodyFrom(code, code.indexOf('{', effectHits.first() + EXIT_EFFECT.length))
        assertEquals(
            "体内 `$EXIT_CONDITION` 恰好一处（三条条件：旗标 + 行确实不在 + 数据已到位，" +
                "缺一条都不许弹；删失败那一档 `courseMissing` 不成立 ⇒ 页面留着、由编辑器念红条，" +
                "不退化成静默）。实到 " + occurrences(body, EXIT_CONDITION) + " 处",
            1,
            occurrences(body, EXIT_CONDITION),
        )
        assertEquals(
            "体内 `if (` 恰好一处：判据只许这一枚（多一枚 = 多一路能弹这一格栈的分支，" +
                "少一枚 = 三条条件被并掉了）。实到 " + occurrences(body, "if (") + " 处",
            1,
            occurrences(body, "if ("),
        )
        assertEquals(
            "体内 `$FLAG_CONSUME` 恰好一处（实到 " + occurrences(body, FLAG_CONSUME) +
                " 处）；全文件也只许一处（实到 " + occurrences(code, FLAG_CONSUME) + " 处）",
            1,
            occurrences(code, FLAG_CONSUME),
        )
        assertEquals(
            "体内 `$POP_BACK_STACK` 恰好一处（实到 " + occurrences(body, POP_BACK_STACK) + " 处）",
            1,
            occurrences(body, POP_BACK_STACK),
        )
        assertTrue(
            "次序必须是 **消费 → 退栈**：`$FLAG_CONSUME` 落在体内 index=" + body.indexOf(FLAG_CONSUME) +
                "，必须排在 `$POP_BACK_STACK` 的 index=" + body.indexOf(POP_BACK_STACK) + " 之前。" +
                "换载体之后旗跨转屏存活，而转屏会把这枚协程整个重建（三条键一个没变也要重跑一遍体）；" +
                "消费排在退栈之前 + 两句之间没有挂起点 ⇒ 这一趟照样弹得出去，而点火之后三条条件少一条 ⇒ " +
                "任何重启都点不动第二次（不会把用户从首页再往后多弹一格）。反过来挪到 pop 之后，" +
                "「pop 已在途、组合还没来得及摘掉」这一刻转屏就是连弹两格的那一枚反例",
            body.indexOf(FLAG_CONSUME) in 0 until body.indexOf(POP_BACK_STACK),
        )
        assertFalse(
            "消费必须是**写回 false**，不许换成 `$FLAG_KEY` 的 `remove(...)`：`SavedStateHandleImpl.remove`" +
                " 会把 `getStateFlow` 缓存的那枚 flow 一并从 map 里摘掉，之后写同一个键就不再通知正在 " +
                "collect 的那一枚协程（反汇编读到的形状，实到 " + occurrences(body, ".remove($FLAG_KEY)") +
                " 处）。要真清场也轮不到这里：这条 entry 弹栈销毁时它那份 handle 整个走掉",
            body.contains(".remove($FLAG_KEY)") || body.contains("remove($FLAG_KEY)"),
        )
        assertEquals(
            "「立旗 → 消费」这一对必须**同键同名**：体内只许出现一次 `$FLAG_KEY`（消费那一手），" +
                "声明与立旗各一次在别处；本层判的是别有人在协程里再写第三个键名（那就是第二面旗）。" +
                "体内实到 " + occurrences(body, FLAG_KEY) + " 处",
            1,
            occurrences(body, FLAG_KEY),
        )
    }

    // ─────────────── ⑤ 隔离：旗只属于这一条 entry，读了必须订阅 ───────────────

    @Test
    fun `旗只属于这一条 entry 且读它是订阅式的`() {
        val code = blankCommentsKeepingLiterals(source(MAIN_ACTIVITY))
        val destHits = indexOfAll(code, EDITOR_DESTINATION_ANCHOR)
        assertEquals("`$EDITOR_DESTINATION_ANCHOR` 恰好一处（取块的宿主）：", 1, destHits.size)
        val destination = braceBodyFrom(code, code.indexOf('{', destHits.first()))

        assertEquals(
            "这一枚编辑器目的地块内 `backStackEntry.savedStateHandle` 恰好三处 = 读（collectAsState 那一条链）" +
                "+ 写（立旗）+ 消费（点火）；实到 " + occurrences(destination, ENTRY_HANDLE) + " 处：" +
                "**多一处** = 又开了一路状态源（两把尺量同一件事）；**少一处** = 有人把某一等挪去了" +
                "别的影响域（VM / 全局旗），那一等就不再按 entry 隔离了",
            3,
            occurrences(destination, ENTRY_HANDLE),
        )
        assertEquals(
            "目的地块内 `currentBackStackEntry` 必须 0 处（实到 " +
                occurrences(destination, "currentBackStackEntry") + " 处）：拿 `navController." +
                "currentBackStackEntry?.savedStateHandle` 当旗的容器 = 「谁在栈顶算谁」，" +
                "弹完这一格之后栈顶换成首页，那面旗就成了「首页删过课」—— 本卡明令不许的那一等全局旗",
            0,
            occurrences(destination, "currentBackStackEntry"),
        )
        assertEquals(
            "目的地块内 `$HANDLE_GET` 必须 0 处（实到 " + occurrences(destination, HANDLE_GET) +
                " 处）：`savedStateHandle.get<Boolean>(KEY)` 是一次性快照读，**不产生 recomposition**，" +
                "写进 handle 之后协程那三枚键不会翻面 ⇒ 删成的那一刻不点火。读这一族只有 " +
                "getStateFlow + collectAsState 一等是订阅式的",
            0,
            occurrences(destination, HANDLE_GET),
        )
        assertEquals(
            "键常量 `$FLAG_KEY` 的声明恰好一处（文件级 private const）：实到 " +
                occurrences(code, "private const val $FLAG_KEY = ") + " 处。写、消费、读三处都引它 = " +
                "只有一份键名；就地散三遍字面量的话，改其一不改其余 = 旗写了没人读得到，静默回到 #211",
            1,
            occurrences(code, "private const val $FLAG_KEY = "),
        )
    }

    // ---------------- 源码核对小工具（抄 CourseEditorDeleteExitReceiverGuardTest）----------------

    /**
     * 取锚点所在那一行起共 [count] 行，**逐行 left-trim**（不 left-pad：期望值里带的就是盘上的
     * 行内形状）。用行切片而不是整段偏移切片，是为了让「原文逐字」这条判据不吃行号 —— 本仓
     * 行号锚点会漂，而这一族要抓的是键表与体的形状，不是它在第几行。
     */
    private fun lineSlice(code: String, anchorIndex: Int, count: Int): List<String> {
        val lines = code.split('\n')
        val at = code.substring(0, anchorIndex).count { it == '\n' }
        check(at in lines.indices) { "锚点取到了却数不出那一行（偏移越界）：at=$at lines=${lines.size}" }
        // 越界那一行取到 "<越界>"，与期望值比不上 ⇒ 由调用点那格 assertEquals 红，这里不再自数一遍
        return (0 until count).map { lines.getOrElse(at + it) { "<越界>" }.trim() }
    }

    /** 报错消息里点名 行号 + 原文（只报个数等于没有守卫） */
    private fun hint(raw: String, needle: String): String {
        val positions = indexOfAll(raw, needle)
        if (positions.isEmpty()) return " 盘上一处都没有"
        val lines = raw.split('\n')
        return positions.joinToString(prefix = "\n  ") { at ->
            val index = raw.substring(0, at).count { it == '\n' }
            "L${index + 1}: " + lines.getOrElse(index) { "<越界>" }.trim()
        }
    }

    /** 从已知开括号位起按花括号配平（跳过字符串与字符字面量）取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') { "锚点末尾不是花括号：写法变了，切不出一段体（实到「${
            source.getOrNull(open)
        }」）" }
        var depth = 0
        var index = open
        var inString = false
        var escaped = false
        while (index < source.length) {
            val c = source[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '\'' -> {
                    var j = index + 2
                    if (index + 1 < source.length && source[index + 1] == '\\') j = index + 3
                    while (j < source.length && source[j] != '\'') j++
                    check(j < source.length) { "字符字面量没闭合：花括号配平会跑偏" }
                    index = j
                }

                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
            index++
        }
        throw IllegalStateException("花括号没配平：从偏移 $open 起切不出一段体")
    }

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

    /**
     * **CRLF 归一**：本仓 main 源码在盘上是 CRLF（`MainActivity.kt` 整篇），而本守卫要拿
     * 多行原文切片比（第 ①③ 层）。不归一的话判据就成了「比的是检出风格」，
     * 换一台机器 core.autocrlf 一变、一片假红。
     */
    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

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

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（行号因此仍然可信）。
     * 本卡尤其需要抹注释：`MainActivity` 那一枚 `savedStateHandle` 键常量的 KDoc 与那两枚协程上方
     * 的整段理由里**逐字写着旧那一形**（`remember(backStackEntry) { mutableStateOf(false) }`），
     * 不抹的话它们会被数成盘上多出来的落点。
     */
    private fun blankCommentsKeepingLiterals(source: String): String {
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

    private companion object {
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"

        // ── ① 旗标的容器 ──
        const val FLAG_NAME = "deleteRequestedByThisEntry"
        const val FLAG_KEY = "DELETE_REQUESTED_BY_THIS_ENTRY"
        const val OLD_FLAG_DECL =
            "var deleteRequestedByThisEntry by remember(backStackEntry) { mutableStateOf(false) }"
        /** composition 那一族（`remember(` 与 `remember {` 两形都算），但不碰 `rememberSaveable` */
        const val COMPOSITION_BACKED = """deleteRequestedByThisEntry\s+by\s+remember(?!Saveable)"""
        const val DECL_HEAD = "val deleteRequestedByThisEntry by backStackEntry.savedStateHandle"
        val DECL_SLICE = listOf(
            DECL_HEAD,
            ".getStateFlow(DELETE_REQUESTED_BY_THIS_ENTRY, false)",
            ".collectAsState()",
        )
        /**
         * `$FLAG_NAME` 在**代码**里（注释已抹）的落点名册：一枚声明 + 三枚读者，逐枚点名。
         *
         * 为什么不是一枚裸枚数：裸数只会说「几处」，不会说「是哪几处」—— 配对守卫拦下第二枚读者时
         * 把数往上调（本仓成文教训，台账 #523 / T271 那一洞）等于让守卫替没被审过的读者背书。
         * 名册的做法：每一枚都得先自报身份（角色 + 逐字落点），新增一枚没在册的读者当场红。
         *
         * 第四枚（内核实参）是本批 T335 合法改写带进来的那一枚，裁决见 ③ 层与 ⓪ 号 commit：
         * 它只决定**念不念那句话**，不决定**退不退栈** —— 退栈那一维在
         * `domain/editor/EditorMissingCourseExitTest > 退栈那一维与基点逐格等价` 里对 2³ 输入逐格钉成
         * 「数据到位 && 目标不在屏上」，与旗无关；而这一档的键表仍然只有两枚（不许加旗，加了就是
         * 两条协程各弹一次）。所以它不是「第二条 pop 路径」，是名册里新的一枚。
         */
        val FLAG_LANDING_ROSTER = listOf(
            "① 声明（T177 的载体：本条 entry 的 savedStateHandle → getStateFlow → collectAsState）" to
                "val deleteRequestedByThisEntry by backStackEntry.savedStateHandle",
            "② 退栈协程的键（点火用）" to
                "LaunchedEffect(deleteRequestedByThisEntry, courseMissing, uiState.loading) {",
            "③ 退栈协程的条件（消费前那一读）" to
                "if (deleteRequestedByThisEntry && courseMissing && !uiState.loading) {",
            "④ 出场判据内核的具名实参（T335 新增：只决定念不念那句话）" to
                "deletionRequestedByThisEntry = deleteRequestedByThisEntry,",
        )

        // ── ② 立旗那一手的次序 ──
        const val ON_DELETE_ANCHOR = "onDelete = { target ->"
        const val FLAG_SET = "backStackEntry.savedStateHandle[$FLAG_KEY] = true"
        const val AWAIT_DELETE = "viewModel.deleteCourseAndAwait(target)"

        // ── ③ 「从来没读到」那一档：键表 + 递给内核的三枚事实 + 四档表两头（整段逐字，不用行号）──
        // T335 把体从「裸 if 判完直接 pop」换成「问内核四档、按档念/退」，键表那一行一字未动 ——
        // 它才是「两档不许并成一档」那一半；「数据还没到就不判」那一半搬到了 `dataLoaded` 那枚实参上。
        const val NEVER_READ_ANCHOR = "androidx.compose.runtime.LaunchedEffect(courseId, uiState.loading) {"
        val NEVER_READ_SLICE = listOf(
            NEVER_READ_ANCHOR,
            "when (",
            "EditorMissingCourseExit.verdictOf(",
            "dataLoaded = !uiState.loading,",
            "courseMissing = courseMissing,",
            "deletionRequestedByThisEntry = deleteRequestedByThisEntry,",
        )
        /** 四档表里**能弹栈**的两档（互斥分支 ⇒ 一次点火只弹一格） */
        val NEVER_READ_POP_BRANCHES = listOf(
            "MissingCourseExitVerdict.PopWithMessage -> {",
            "MissingCourseExitVerdict.PopWithoutMessage -> navController.popBackStack()",
        )
        /** 四档表里**什么都不做**的两档：点名写出来，不许折进 `else` */
        val NEVER_READ_SILENT_BRANCHES = listOf(
            "MissingCourseExitVerdict.CourseIsOnScreen,",
            "MissingCourseExitVerdict.DataNotReadyYet,",
            "-> Unit",
        )
        /** 这一档体内的退栈枚数：与「能弹栈的档」同枚（2），不是与「档数」同枚（4） */
        const val NEVER_READ_POP_COUNT = 2

        // ── ④ 点火即消费 ──
        const val EXIT_EFFECT = "LaunchedEffect(deleteRequestedByThisEntry, courseMissing, uiState.loading)"
        const val EXIT_CONDITION = "if (deleteRequestedByThisEntry && courseMissing && !uiState.loading) {"
        const val FLAG_CONSUME = "backStackEntry.savedStateHandle[$FLAG_KEY] = false"

        // ── ⑤ 隔离 ──
        const val EDITOR_DESTINATION_ANCHOR = ") { backStackEntry ->"
        const val ENTRY_HANDLE = "backStackEntry.savedStateHandle"
        const val HANDLE_GET = "savedStateHandle.get<Boolean>("
        const val POP_BACK_STACK = "navController.popBackStack()"
    }
}
