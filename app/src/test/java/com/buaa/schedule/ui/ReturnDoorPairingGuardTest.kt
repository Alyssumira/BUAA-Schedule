package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T345③：一屏两扇返回门（顶栏那颗箭头 ＋ 系统返回键/侧滑的 `BackHandler`）**必须同调一枚具名 lambda**
 * —— 逐字节核对，钉法族＝逐字节。
 *
 * ## 病（T333 只读审计在册 T333-04；卡面给的行号按构造重定位过，本文件一律按形状取块、不钉行号）
 *
 * 基点 `42baf01` 上两处同病，方向还相反：
 *
 * 1. 扫码页：`BackHandler(enabled = busy, onBack = { })` 把系统返回**整枚吞掉**（在飞时按返回键＝一个字
 *    都不发生），而同页顶栏那颗箭头**不吃任何闸**（`GlassTopBar` 里 `onBack == null` 只决定渲不渲染，
 *    压根没有 enabled 那一档）⇒ 提交在飞时按返回键没反应、点箭头却立刻离页，正在飞的那一发签到落到
 *    哪儿没人知道。
 * 2. 编辑器：`BackHandler` 的 enabled 里那半句 `!saving` 让**写库那几秒**系统返回直接放行、不弹确认，
 *    而同屏那颗箭头（走 `requestBack()`，体内只看 `isDraftDirty`）照样弹「丢弃确认」⇒ 同一件事两扇
 *    方向相反，按返回键那一条把草稿直接扔掉。
 *
 * ## 判据（第⓪问裁的是乙，依据是本仓先例不是整齐）
 *
 * `BuaaLoginScreen` 的 `safeBack()` 是全仓唯一另一处「在飞窗＋两扇返回门」：BackHandler 的 enabled 只决定
 * 「要不要拦下来交给它」，**拦下来之后怎么办由同一处说了算**。那一页今天就把这条律成立到底
 * （enabled 与 `safeBack()` 体内那枚 `if` 逐字同文，两扇同调它）⇒ 本守卫把那一页当**参照物**一起钉：
 * 它哪天被改成两扇分档，同样红。
 *
 * 甲（箭头也吃 enabled 变哑）在本仓零先例，而且这一枚卡里做不到：`GlassTopBar` 没有 enabled 形参，
 * 加它要改设计系统组件（9 枚调用点）并碰 `Text("返回")` —— 那枚字面量是 baseline profile 交互 CUJ 的
 * uiautomator 锚点，归 `HeaderBandWiringGuardTest` 钉。丙（在飞时允许离开，先把活儿收干净再退）判否：
 * 签到与写库两趟都长在 VM 的 `viewModelScope` 上，页 scope 被取消时 T260 已经安排了「目的地层替这一页
 * 弹」，而盘上没有可取消的句柄（`flight` 是 `StateFlow<Boolean>` 不是 `Job`），服务端那一发签到也撤不回。
 *
 * ## 名册与双向闭合（形状抄 T343 那批「具名读者名册＋反向计数」）
 *
 * 盘上「同时有 `BackHandler(` 与 `GlassTopBar(` 」的 main 文件恰好三枚 ⇒ 名册三枚，多一枚少一枚都红。
 * 每枚名册登记它那枚同源 lambda 的**名字**与**调用点处数**（两枚 T345 页各 2 处；登录页 3 处 —— 顶栏、
 * BackHandler、状态卡里那颗 `TextButton`）。⇒ 新长出一扇门、或某扇绕过同源 lambda，本守卫红，逼着回来
 * 重判「这一族还有没有分档的门」。按 T343 铁律：**多出来的那枚读者是卡面缺陷，不许朝上重钉名册合法化**。
 *
 * 手法沿用 `CourseEditorSaveErrorClearPairingGuardTest`（抹注释保字面量、括号配平且跳过字符串字面量、
 * **找不到锚点就抛**——静默跳过等于没有守卫、失败消息带行号与原文）与 `HeaderBandWiringGuardTest`
 * 的 `callArgumentLists`（按调用点取实参表，本守卫额外允许 `com.buaa.schedule.core.designsystem.` 那种
 * 全限定前缀 —— 编辑器那颗箭头就是这么写的）。
 *
 * 三枚 @Test 全做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）：零 adb ⇒ 真机上「按返回键/点箭头
 * 的手感」本卡一律**未验到**，这里钉的是形状不是行为复现。
 */
class ReturnDoorPairingGuardTest {

    /**
     * ①-a 扫码页：两扇返回门逐字节同调 `requestBack()`，而「该不该拦」那一枚读数只有 `busy` 一处口径。
     *
     * 另外钉住改前那两枚形状不许长回来：`onBack =` 后面那枚**空 lambda 纯吞**、以及箭头侧的裸透传
     * （把页参数直接递给顶栏）。在飞时给用户说话的那张嘴也钉一下（中心卡那句既有状态文案）——
     * 乙档兑的是「两扇都给同一个反应」，靠的是本页**已有**的文案，不是新造一套反馈载体；摘掉它这一格就该红。
     */
    @Test
    fun `扫码页两扇返回门逐字节同源 在飞时都不离页而说话的是那句既有状态文案`() {
        val code = blankCommentsKeepingLiterals(source(SCAN_SCREEN))
        val door = singleDoorCall(code, BACK_HANDLER, SCAN_SCREEN)
        val arrow = singleDoorCall(code, GLASS_TOP_BAR, SCAN_SCREEN)
        assertSameDoorReaction(SCAN_SCREEN, door, arrow)
        assertGateIsTheKernelCondition(code, door, SCAN_SCREEN, "requestBack")

        assertKernelSites(code, SCAN_SCREEN, "requestBack", 2)
        assertTrue(
            "在飞那一支必须**早退**（`return`），不许改成「边拦边退」——那正是本卡收掉的那一格：\n" +
                kernelBody(code, SCAN_SCREEN, "requestBack"),
            kernelBody(code, SCAN_SCREEN, "requestBack").contains("return"),
        )
        assertFalse(
            "改前那枚空 lambda 纯吞回来了（`onBack =` 后面只有一对空花括号）：系统返回在飞时一个字都不发生，" +
                "而屏幕上没有一处替它说话：\n" + lineOfMatches(code, EMPTY_ON_BACK),
            code.contains(EMPTY_ON_BACK),
        )
        assertFalse(
            "箭头侧又变回裸透传（把页参数 `onBack` 原样递给顶栏）：那颗按钮不吃任何闸，提交在飞时一点就离页" +
                "—— 本卡收掉的就是这一格：\n  顶栏 onBack → " + arrow.reaction,
            Regex("""onBack\s*=\s*onBack\s*[,)]""").containsMatchIn(door.arguments + arrow.arguments),
        )
        assertTrue(
            "在飞时给用户说话的那张嘴（结果卡「正在提交签到…」那一档）不在了：乙档的反馈载体是本页既有状态" +
                "文案，摘掉它就等于让两扇都变哑",
            code.contains(SUBMITTING_COPY),
        )
        assertTrue(
            "吞掉返回那一手不再留痕（本页纪律：T59③ 对静默支路的判罚是「按了没反应」必须留进取证，" +
                "口径同「点按对焦被忽略」那一支）：\n" + kernelBody(code, SCAN_SCREEN, "requestBack"),
            kernelBody(code, SCAN_SCREEN, "requestBack").contains("Log.i(TAG,"),
        )
    }

    /**
     * ①-b 编辑器：两扇返回门逐字节同调 `requestBack()`，且 `saving` 不再参与返回闸。
     *
     * `!saving` 那一半不许长回 enabled（那正是「写库那几秒系统返回直接放行、不弹确认」的病根，在册
     * #383 后半 —— 本卡把它裁掉了）。`saving` 自己那三枚读者一枚没动，那是
     * `WriteChainReceiverWiringGuardTest` 的账；这里只钉「它不再是返回闸的口径」这一格。
     */
    @Test
    fun `编辑器两扇返回门逐字节同源 而写库那几秒系统返回不再被放行`() {
        val code = blankCommentsKeepingLiterals(source(EDITOR_SCREEN))
        val door = singleDoorCall(code, BACK_HANDLER, EDITOR_SCREEN)
        val arrow = singleDoorCall(code, GLASS_TOP_BAR, EDITOR_SCREEN)
        assertSameDoorReaction(EDITOR_SCREEN, door, arrow)
        assertGateIsTheKernelCondition(code, door, EDITOR_SCREEN, "requestBack")

        assertKernelSites(code, EDITOR_SCREEN, "requestBack", 2)
        assertEquals(
            "那颗 BackHandler 的实参表里 `saving` 必须**一枚都不出现**（实到 " +
                occurrences(door.arguments + door.reaction, "saving") + " 处）：改前那半句 `&& !saving` 让" +
                "写库那几秒系统返回直接放行、不弹确认，而同屏那颗箭头照样弹「丢弃确认」—— 两扇方向相反" +
                "（T345 收在册 #383 后半）。`saving` 的正当读者是那三枚（底栏文案／删除那颗 enabled／" +
                "editorCanSave 折并），归 WriteChainReceiverWiringGuardTest 钉，不在本守卫射程：\n" +
                lineOf(code, listOf(code.indexOf(door.arguments))),
            0,
            occurrences(door.arguments + door.reaction, "saving"),
        )
        assertFalse(
            "箭头侧长出了 enabled／backEnabled 那一档（甲档的形状）：第⓪问裁的是乙 —— 箭头按不动等于给用户" +
                "一颗死按钮，而 GlassTopBar 根本没有 enabled 形参：\n  顶栏 onBack → " + arrow.reaction,
            Regex("""\benabled\s*=""").containsMatchIn(arrow.reaction),
        )
    }

    /**
     * ② 族律：盘上「同时有 `BackHandler(` 与 `GlassTopBar(` 」的每一枚 main 文件都必须在名册里，
     * 且名册登记的同源 lambda 名与调用点处数必须与盘上**互为等集**（双向闭合）。
     *
     * 名册三枚：两枚 T345 页（`requestBack`，各 2 处）＋ `BuaaLoginScreen`（`safeBack`，3 处 —— 顶栏、
     * BackHandler、状态卡那颗 `TextButton`）。登录页是**参照物**：这条律在它身上早就成立，本卡照抄它的
     * 形状，所以它也被同一把尺量（它哪天分档，本格红）。
     *
     * 不在名册的那枚 `BackHandler`（`WeekView` 的浮层关闭）不构成这一族：那一页压根没有配对的顶栏箭头门，
     * 它的对门是「点外面」—— 由上面那句「同时有两枚调用点」的筛子自动排除，不额外钉枚数（枚数会漂）。
     */
    @Test
    fun `族律名册双向闭合 盘上每一枚两扇门页都被点名`() {
        val sources = mainSources()
        val onBoard = sources
            .filter { (_, code) ->
                val blanked = blankCommentsKeepingLiterals(code)
                blanked.contains(BACK_HANDLER) && blanked.contains(GLASS_TOP_BAR)
            }
            .keys
            .map { it.substringAfterLast('/') }
            .sorted()
        val roster = kernels.map { it.file.substringAfterLast('/') }.sorted()
        assertEquals(
            "盘上「同时挂 BackHandler 与 GlassTopBar」的文件与名册不等：盘上实到 $onBoard，名册登记 $roster。" +
                "**多一枚** = 新长出一页两扇门，它有没有同源必须回来判；**少一枚** = 有一页不再两扇并存，" +
                "名册过期同样是漏判",
            roster,
            onBoard,
        )

        for (entry in kernels) {
            val code = blankCommentsKeepingLiterals(source(entry.file))
            assertKernelSites(code, entry.file, entry.kernel, entry.sites)
            val door = singleDoorCall(code, BACK_HANDLER, entry.file)
            val arrow = singleDoorCall(code, GLASS_TOP_BAR, entry.file)
            assertSameDoorReaction(entry.file, door, arrow)
            assertGateIsTheKernelCondition(code, door, entry.file, entry.kernel)
            assertTrue(
                "${entry.file}：两扇门同源的那枚 lambda 应当是名册登记的 `${entry.kernel}()`，" +
                    "实到「${door.reaction}」——名册与盘上各判各的，这一格就是那把交叉尺",
                door.reaction.contains("${entry.kernel}()"),
            )
        }
    }

    /**
     * ③ 全仓禁形：生产码里不许有任何一枚 `BackHandler` 用空 lambda 纯吞系统返回。
     *
     * 这一格扫的是**整棵 main**，不是名册那三枚：本卡改前的扫码页就是全仓唯一一枚，改后归零。
     * 谁新添一枚空吞 ⇒ 红，逼着作者回答「用户按下去那一下，屏幕上有哪一句在说话」。
     */
    @Test
    fun `全仓生产码不许再有空 lambda 那枚纯吞返回`() {
        val offenders = mutableListOf<String>()
        for ((path, raw) in mainSources()) {
            val code = blankCommentsKeepingLiterals(raw)
            var from = 0
            while (true) {
                val at = code.indexOf(BACK_HANDLER, from)
                if (at < 0) break
                val arguments = balancedParens(code, at + BACK_HANDLER.length - 1, path)
                if (EMPTY_ON_BACK.containsMatchIn(arguments)) offenders += path
                from = at + 1
            }
        }
        assertTrue(
            "这些文件里又长出「整枚吞掉系统返回而 lambda 里一个字都不做」的 BackHandler：" +
                offenders.joinToString() + "。改前的扫码页就是全仓唯一一枚（T345 收掉）。要么让这一扇与同屏" +
                "那颗箭头调同一枚具名 lambda，要么在这一扇里给用户一句话 —— 纯静默不是选项（T59③）。",
            offenders.isEmpty(),
        )
    }

    // ─────────────────── 判据本体：两扇同源那两格 ───────────────────

    /**
     * 两扇门的**反应表达式**必须逐字节相同：`BackHandler` 那一侧取它 trailing lambda 的体（取不到就退回
     * 圆括号里的 `onBack =` 实参 —— 那正是改前扫码页的形状），顶栏那一侧取 `onBack` 实参。
     */
    private fun assertSameDoorReaction(file: String, door: DoorCall, arrow: DoorCall) {
        val backReaction = door.reaction.trim()
        val arrowReaction = arrow.reaction.trim()
        assertEquals(
            "$file：两扇返回门的反应表达式不再逐字同源 —— 那颗箭头点下去与那一下系统返回走的是**两枚不同**" +
                "的写法。朝宽扭（把箭头侧的闸摘掉／改成裸透传）与朝窄扭（把 BackHandler 那一侧摘掉或改指向" +
                "另一个 lambda）都会红在这一格：\n  BackHandler → $backReaction\n  顶栏箭头   → $arrowReaction",
            backReaction,
            arrowReaction,
        )
        assertTrue(
            "$file：两扇门那一份同源的写法必须调一枚**具名 lambda**（不许是就地展开的 if，也不许是裸参数）：" +
                "\n  $backReaction",
            Regex("""\b\w+\(\)""").containsMatchIn(backReaction),
        )
    }

    /**
     * BackHandler 的 enabled 表达式必须与那枚具名 lambda 体内**第一枚 `if`** 的条件逐字相同 ——
     * 「这一扇此刻该不该被拦」只许有一本册子，两把尺量一件事就是改前编辑器那一格（enabled 多吃半句 `!saving`）。
     */
    private fun assertGateIsTheKernelCondition(code: String, door: DoorCall, file: String, kernel: String) {
        val gate = door.arguments.removePrefix("(").removeSuffix(")").let { argList ->
            argumentOf(argList, "enabled", file).trim()
        }
        val condition = firstIfCondition(kernelBody(code, file, kernel), file, kernel)
        assertEquals(
            "$file：`BackHandler` 的 enabled 与同源 lambda `$kernel()` 体内那枚 `if` 读的不是同一份字面 —— " +
                "两扇门的放行窗口会漂（改前的编辑器：enabled 多吃了半句 `!saving`，写库那几秒系统返回畅通）：\n" +
                "  enabled → $gate\n  if      → $condition",
            condition,
            gate,
        )
    }

    /** 名册那一枚 lambda 的身份证：声明恰好一处，调用点（去掉声明那一处）恰好 [sites] 处 */
    private fun assertKernelSites(code: String, file: String, kernel: String, sites: Int) {
        val declaration = "fun $kernel()"
        assertEquals(
            "$file 里 `fun $kernel()` 的声明必须恰好一处（实到 " + occurrences(code, declaration) + " 处）：" +
                "出现两枚声明就是又分档了",
            1,
            occurrences(code, declaration),
        )
        val calls = occurrences(code, "$kernel()") - occurrences(code, declaration)
        assertEquals(
            "$file 里 `$kernel()` 的**调用点**处数不再是名册登记的 $sites 处（实到 $calls 处）：" +
                "多一处 = 又长出一扇门没进名册（按 T343 铁律那是卡面缺陷，不许朝上重钉名册合法化）；" +
                "少一处 = 有一扇门绕过了同源 lambda（本守卫要拦的病根）",
            sites,
            calls,
        )
    }

    // ─────────────────── 源码核对小工具 ───────────────────

    /** 一枚门调用点的两个部分：圆括号实参表（含两端）＋ 反应表达式（trailing lambda 或 `onBack =` 实参） */
    private data class DoorCall(val arguments: String, val reaction: String)

    /** 名册：文件（相对 src/main/java）× 同源 lambda 名 × 调用点处数 */
    private data class DoorKernel(val file: String, val kernel: String, val sites: Int)

    private val kernels = listOf(
        DoorKernel(EDITOR_SCREEN, "requestBack", 2),
        DoorKernel(SCAN_SCREEN, "requestBack", 2),
        DoorKernel(LOGIN_SCREEN, "safeBack", 3),
    )

    /** 按锚点取**恰好一枚**调用点（多一枚少一枚都抛：静默跳过等于没有守卫）；全限定前缀算同一枚 */
    private fun singleDoorCall(code: String, anchor: String, file: String): DoorCall {
        val hits = indexOfAll(code, anchor).filter { at ->
            !code.substring(0, at).trimEnd().endsWith("fun")
        }
        check(hits.size == 1) {
            "$file 里 `$anchor` 的调用点应当恰好一处（实到 ${hits.size} 处）：本守卫按「唯一调用点」取形状，" +
                "枚数变了就要跟着重判 —— " + lineOf(code, hits)
        }
        val open = hits.first() + anchor.length - 1
        val arguments = balancedParens(code, open, file)
        val after = code.substring(open + arguments.length)
        val brace = after.indexOf('{')
        return if (brace >= 0 && after.substring(0, brace).all { it.isWhitespace() }) {
            DoorCall(arguments, after.substring(brace, closeBraceOf(after, brace, file) + 1))
        } else {
            DoorCall(arguments, argumentOf(arguments.removePrefix("(").removeSuffix(")"), "onBack", file))
        }
    }

    /** 具名 lambda 的体（`fun requestBack() { … }` 那对花括号里的原文） */
    private fun kernelBody(code: String, file: String, kernel: String): String {
        val signature = "fun $kernel()"
        val at = code.indexOf(signature)
        check(at >= 0) { "$file 里找不到 `$signature`：这枚同源 lambda 没了或改了名（本卡的病根复活）" }
        val brace = code.indexOf('{', at)
        check(brace >= 0) { "$file 的 `$signature` 后面接不到花括号：写法变了" }
        return code.substring(brace + 1, closeBraceOf(code, brace, file))
    }

    /** 实参表里取一枚具名实参的值：吃到深度 0 的逗号为止（嵌套括号与字符串字面量都跳过） */
    private fun argumentOf(arguments: String, name: String, file: String): String {
        val at = Regex("""\b${Regex.escape(name)}\s*=\s*""").find(arguments)
        check(at != null) { "$file 的这一段实参表里找不到 `$name =`：形状变了，本守卫要跟着改\n$arguments" }
        var index = at.range.last + 1
        var depth = 0
        var inString = false
        var escaped = false
        while (index < arguments.length) {
            val c = arguments[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> {
                    if (depth == 0) break
                    depth--
                }

                c == ',' && depth == 0 -> break

                else -> Unit
            }
            index++
        }
        return arguments.substring(at.range.last + 1, index)
    }

    /** 体内**第一枚** `if (` 的括号内条件（逐字，含两端括号再剥掉） */
    private fun firstIfCondition(body: String, file: String, kernel: String): String {
        val at = Regex("""\bif\s*\(""").find(body)
        check(at != null) { "$file 的 `$kernel()` 体内没有 `if (`：这一扇不再判任何东西，同源的前提没了" }
        return balancedParens(body, at.range.last, file).removePrefix("(").removeSuffix(")")
    }

    /** 从 [open] 那枚 `(` 配平到对应 `)`，返回**含两端**的原文（跳过字符串字面量） */
    private fun balancedParens(source: String, open: Int, file: String): String {
        check(source[open] == '(') { "$file：锚点位置不是左圆括号：形状变了" }
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
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        throw IllegalStateException("$file：圆括号没配平（从 ${source.substring(open, minOf(open + 40, source.length))} 起）")
    }

    /** 从 [openBrace] 那枚 `{` 配平到对应 `}`，返回它的下标（跳过字符串字面量） */
    private fun closeBraceOf(source: String, openBrace: Int, file: String): Int {
        check(source[openBrace] == '{') { "$file：锚点位置不是左花括号：形状变了" }
        var depth = 0
        var index = openBrace
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
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        throw IllegalStateException("$file：花括号没配平")
    }

    /** 正则命中处的行号与原文 */
    private fun lineOfMatches(source: String, pattern: Regex): String =
        lineOf(source, pattern.findAll(source).map { it.range.first }.toList())

    /** 命中处的行号与原文：失败消息只报个数等于没有守卫 */
    private fun lineOf(source: String, positions: List<Int>): String {
        if (positions.isEmpty()) return ""
        return "\n" + positions.distinct().joinToString("") { at ->
            val start = source.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
            val end = source.indexOf('\n', at).let { if (it < 0) source.length else it }
            "  L${source.substring(0, at).count { it == '\n' } + 1}: " + source.substring(start, end).trim() + "\n"
        }
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun mainSources(): Map<String, String> {
        val root = findMainJavaDir()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associateBy({ it.relativeTo(root).path.replace('\\', '/') }, { it.readText() })
    }

    private fun findMainJavaDir(): File {
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

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（行号因此仍然可信）。
     * 整段抄 `CourseEditorSaveErrorClearPairingGuardTest`：本守卫这三枚 @Test 的判据既有字面量
     * （`onBack = { }`、结果卡那句文案）也有行号提示，那两把刀（整段换空格／连字面量一起抹）都会砍错。
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
                            source[j] == quote -> {
                                j++
                                break
                            }

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
        const val EDITOR_SCREEN = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"
        const val SCAN_SCREEN = "com/buaa/schedule/ui/signin/SpocScanScreen.kt"
        const val LOGIN_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"

        const val BACK_HANDLER = "BackHandler("
        const val GLASS_TOP_BAR = "GlassTopBar("
        const val SUBMITTING_COPY = "is SignInState.Submitting -> \"正在提交签到…\""

        /** 改前扫码页那一枚形状：`onBack =` 后面只有一对空花括号（{} 与 { } 都算） */
        val EMPTY_ON_BACK = Regex("""onBack\s*=\s*\{\s*}""")
    }
}
