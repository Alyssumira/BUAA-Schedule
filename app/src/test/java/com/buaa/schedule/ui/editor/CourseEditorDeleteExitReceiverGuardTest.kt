package com.buaa.schedule.ui.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T166②：钉住**「页面的组合期 scope 上不许 await 完一次写库再自己退栈」这一族**。
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟），读法与小工具抄
 * `ui/WriteChainReceiverWiringGuardTest` 与 `ui/CourseDeletionWiringGuardTest`。
 *
 * ## 病（装机 10-04 第三轮 2/2 复现：删完课整屏空白且不自愈，按一次 BACK 内容才回来）
 *
 * `rememberCoroutineScope()` 给的那枚 scope 绑在**这一次组合**上。删除确认框那颗按钮原来写的是
 * `scope.launch { … if (onDelete(target)) { onBack() } … }`：
 * 1. `deleteCourseAndAwait`（VM `:632`）里 `job.join()` 跑在**调用者**（这枚页 scope）上；
 * 2. 事务 commit 之后 VM 还要跑 `afterDataChangedInternal()`（重排课堂铃 / 组件 / 明日预告），
 *    这期间新的 `uiState` 落地；
 * 3. 下一帧 `MainActivity` 编辑器目的地那道 `if (!uiState.loading && !courseMissing)` 闸门
 *    把 `CourseEditorScreen` **整棵摘出组合** ⇒ 那枚 scope 一起被取消 ⇒
 *    挂在它后面的 `onBack()` 一行都没执行；
 * 4. 于是栈顶还站着一页渲染成空的编辑器：内容区 0 墨像素、底栏也没有（底栏是 NavHost 的兄弟节点，
 *    `showBottomBar` 判的是"当前目的地是不是 topLevel"，栈顶是编辑器 ⇒ 不是）；
 * 5. 同页那枚 `BackHandler` 也随页面一起不在组合里 ⇒ 用户按下的 BACK 直达 nav 层，一次就回首页。
 *    ⇒ 这一条同时解释"0 墨像素"与"BACK 一次就救回来"，不需要第二种解释。
 *
 * ## 修法与本守卫的形状判据
 *
 * 「删完课要退栈」那一手搬到**编辑器目的地层**：`MainActivity` 里 `editor/{courseId}` 那一枚
 * `composable { backStackEntry -> … }` 的 lambda 体内，协程长在 NavHost 的那一条 back stack entry 上，
 * **不在被摘的那棵子树里**，所以闸门摘页时它照样点火。两档分开认领：
 * - 「这门课从来没读到」→ 原来那枚 `LaunchedEffect(courseId, uiState.loading)` 一字未动
 *   （键里**没有** `courseMissing`，为的就是"数据还没到"那一帧不误弹 —— R5 F-41 那一半的原意）；
 * - 「这门课刚被这一页删掉」→ 新那枚 `LaunchedEffect(deleteRequestedByThisEntry, courseMissing,
 *   uiState.loading)`，凭据是 `onDelete` lambda 在 **await 之前**立起的那枚旗标。
 * 删失败那一档 `courseMissing` 不成立 ⇒ 页面留着、由编辑器念 `saveError = "删除失败，请重试"`，
 * 不退化成静默。
 *
 * ## 钉法（两侧都有格子）
 *
 * 1. [删成之后那一档的退栈认领在编辑器目的地上 不在被摘的那棵子树里]：
 *    旗标声明逐字 / 新协程的键与判据逐字 / 它**不在**渲染闸门那块体内 / `onDelete` lambda 体内
 *    「立旗 → await」且 `popBackStack(` 与 `onBack(` 各 0 处 / 编辑器删除链体内 `onBack(` 0 处 /
 *    编辑器全文 `onBack(` 恰好三处且逐枚点名宿主 / VM 全文 `onBack(` 0 处。
 *    ⇒ **朝宽扭**（把 `onBack()` 从 scope 里摘掉、或搬到"看着不随组合取消"的接收者上 —— 写进
 *    `onDelete` lambda、搬进 VM、或把新协程挪进闸门块里）**红**：那三种都不是这一族要的接收者。
 * 2. [那道渲染闸门与从来读不到那一档逐字未动]：`courseMissing` 的定义、闸门的判据、
 *    那枚旧协程的**键表**与它的体内条件全部逐字钉住。⇒ 谁用"courseMissing 就立刻 pop"把两档
 *    并成一档（那会把"点了没反应"换成"数据还没到就误弹"），或把闸门放宽成照旧渲染
 *    （那会把 R5 F-41 的 `id=0L` 重复课程放回来），**红**。
 * 3. [页面组合期scope上await完一次写库再自己退栈的落点册子]：全仓 main 扫每一枚由
 *    `rememberCoroutineScope()` 供给的变量名下的 `launch { … }` 体，体内**同时**出现
 *    写库那一手与自我退栈那一手的落点按 文件 × 处数 登记，判据是**双向等集**：
 *    长出一枚（把删除链那一支搬回来、或新写一枚这种形状）红；登记在册的那一枚从盘上消失
 *    （有人顺手把 `performSave` 那一支也搬走了）同样红 —— 那一格在册就代表"本守卫在判它"，
 *    掏空就等于这一格不再判任何东西。在册的两枚与"今天为什么不咬人"逐枚写在 `AWAIT_THEN_EXIT` 上。
 *    ⇒ **朝窄扭**（改锚点字面量 / 少一枚调用点 / 摘名册不摘盘）**红**。
 *
 * 本守卫只读源码，**不证明**运行时行为：装机那一档（`del` / `depth` 两档重放）归编排者。
 */
class CourseEditorDeleteExitReceiverGuardTest {

    // ─────────────── ① 退栈那一手的接收者：目的地层，不是被摘的子树 ───────────────

    @Test
    fun `删成之后那一档的退栈认领在编辑器目的地上 不在被摘的那棵子树里`() {
        val activity = blankCommentsKeepingLiterals(source(MAIN_ACTIVITY))
        val editor = blankCommentsKeepingLiterals(source(EDITOR))
        val rawActivity = source(MAIN_ACTIVITY)
        val rawEditor = source(EDITOR)

        // —— MainActivity 侧：旗标 + 目的地层那条协程 ——
        assertEquals(
            "`$FLAG_DECL` 恰好一处：它就是「这门课刚被这一页删掉」与「这门课从来没读到」两档之间" +
                "唯一的凭据。**摘掉它** ⇒ 删完课没人退栈（整屏空白原样回来）；**改名** ⇒ 下面那枚" +
                "键表跟不上了，同样不点火。载体那一等（为什么必须是本条 entry 的 savedStateHandle、" +
                "为什么点火要把它消费掉）逐形钉在 ui/editor/DeleteFlagConfigSurvivalGuardTest：" +
                hint(rawActivity, indexOfAll(activity, FLAG_DECL)),
            1,
            occurrences(activity, FLAG_DECL),
        )
        val exitEffectHits = indexOfAll(activity, EXIT_EFFECT)
        assertEquals(
            "`$EXIT_EFFECT` 恰好一处 —— 这一枚协程就是 T166 搬进来的那一手退栈。" +
                "它的键表**三枚都要在**：少 `deleteRequestedByThisEntry` 就把两档并成一档（数据还没到" +
                "那一帧也会弹），少 `courseMissing` 就永远不会点火（删成的那一刻正是它翻 true），" +
                "少 `uiState.loading` 就丢掉了「等数据到位再说」那一半：" + hint(rawActivity, exitEffectHits),
            1,
            exitEffectHits.size,
        )
        val exitBody = braceBodyFrom(activity, activity.indexOf('{', exitEffectHits.first() + EXIT_EFFECT.length))
        assertEquals(
            "那枚协程体内 `$EXIT_CONDITION` 恰好一处：三个条件**缺一条都不许弹**。把它扭成 " +
                "`if (courseMissing && !uiState.loading)`（丢掉旗标）就是「用一枚尺量两档」—— " +
                "那一档今天由上面那枚旧协程管，键表里没有 courseMissing，为的就是不在「首帧课程列表" +
                "为空 / 刚切学期」那种帧里弹栈（R5 F-41 的原话）。实到 " +
                occurrences(exitBody.body, EXIT_CONDITION) + " 处",
            1,
            occurrences(exitBody.body, EXIT_CONDITION),
        )
        assertEquals(
            "那枚协程体内 `$POP_BACK_STACK` 恰好一处（实到 " + occurrences(exitBody.body, POP_BACK_STACK) +
                " 处）：退栈那一手**只有这一处**认领，别处再多一枚就是连 pop 两格",
            1,
            occurrences(exitBody.body, POP_BACK_STACK),
        )
        // 接收者必须**在外面**：闸门块体内不许有这条协程
        val gateHits = indexOfAll(activity, RENDER_GATE)
        assertEquals("渲染闸门 `$RENDER_GATE` 恰好一处：", 1, gateHits.size)
        val gateBody = braceBodyFrom(activity, gateHits.first() + RENDER_GATE.length - 1).body
        assertFalse(
            "新那枚退栈协程被挪进了 `$RENDER_GATE` 那块体里 ⇒ 它和 `CourseEditorScreen` **同一棵子树**，" +
                "闸门摘页的同一刻它也被取消：这正是本卡要修的那枚病换了个位置。它必须与闸门**平级**" +
                "（长在 `composable { backStackEntry -> … }` 那一层），实到 " +
                occurrences(gateBody, EXIT_EFFECT) + " 处",
            gateBody.contains(EXIT_EFFECT),
        )
        assertEquals(
            "`CourseEditorScreen(` 必须仍然**在那道闸门里**（实到 " + occurrences(gateBody, "CourseEditorScreen(") +
                " 处）：闸门不拦任何东西的话，这一族的存在理由（R5 F-41）就没了",
            1,
            occurrences(gateBody, "CourseEditorScreen("),
        )

        // —— onDelete lambda：立旗排在 await 之前，体内一枚退栈都不许有 ——
        val lambdaHits = indexOfAll(activity, ON_DELETE_ANCHOR)
        assertEquals("`$ON_DELETE_ANCHOR` 恰好一处：", 1, lambdaHits.size)
        val lambda = braceBodyFrom(activity, activity.indexOf('{', lambdaHits.first())).body
        assertEquals(
            "体内 `$FLAG_SET` 恰好一处（实到 " + occurrences(lambda, FLAG_SET) + " 处）",
            1,
            occurrences(lambda, FLAG_SET),
        )
        assertEquals(
            "体内 `$AWAIT_DELETE` 恰好一处（实到 " + occurrences(lambda, AWAIT_DELETE) + " 处）",
            1,
            occurrences(lambda, AWAIT_DELETE),
        )
        assertTrue(
            "次序必须是 **立旗 → await**：`$FLAG_SET`@${lambda.indexOf(FLAG_SET)} 必须排在 " +
                "`$AWAIT_DELETE`@${lambda.indexOf(AWAIT_DELETE)} 之前。这一整支跑在编辑器的组合期 scope 上，" +
                "删成之后 uiState 落地、闸门摘页 ⇒ 这一支多半活不到返回那一刻；把立旗挪到 await 之后" +
                "= 那一枚旗十有八九立不起来 = 退栈永远不点火 = 本卡白修",
            lambda.indexOf(FLAG_SET) in 0 until lambda.indexOf(AWAIT_DELETE),
        )
        assertEquals(
            "**朝宽扭就在这一格红**：`$ON_DELETE_ANCHOR` 体内 `$POP_BACK_STACK` 必须 0 处（实到 " +
                occurrences(lambda, POP_BACK_STACK) + " 处）、`onBack(` 必须 0 处（实到 " +
                occurrences(lambda, "onBack(") + " 处）。写成 " +
                "`if (viewModel.deleteCourseAndAwait(target)) navController.popBackStack()` 看着像" +
                "「退栈已经不在 scope.launch 里了」，其实这枚 lambda 的续体**就是调用者那一枚组合期 scope**" +
                "（join 回来时它已经被取消）—— 换的是写法，病一点没修",
            true,
            occurrences(lambda, POP_BACK_STACK) == 0 && occurrences(lambda, "onBack(") == 0,
        )

        // —— 编辑器侧：删除链体内不许再有退栈 ——
        assertEquals(
            "`$SCOPE_DECL` 恰好一处（本卡不动声明，保存链那一枚落点还挂在它上面）：实到 " +
                occurrences(editor, SCOPE_DECL) + " 处",
            1,
            occurrences(editor, SCOPE_DECL),
        )
        val deleteHandHits = indexOfAll(editor, DELETE_HAND)
        assertEquals("`$DELETE_HAND` 恰好一处（删除链读结论那一手）：", 1, deleteHandHits.size)
        val deleteBody = launchBodyAround(editor, deleteHandHits.first())
        assertEquals(
            "删除链那一枚 `scope.launch { … }` 体里 `onBack(` 必须 **0 处**（实到 " +
                occurrences(deleteBody, "onBack(") + " 处）、`popBackStack` 必须 0 处（实到 " +
                occurrences(deleteBody, "popBackStack") + " 处）：这一枚 scope 绑在这次组合上，" +
                "闸门摘页的同一刻被取消。把退栈补回这里 = 装机那条整屏空白原样复活。" +
                "（保存链那一支今天还带着 `onBack()`，那是普查 ③ 登记在册的另一枚，见第 ③ 层）",
            true,
            occurrences(deleteBody, "onBack(") == 0 && occurrences(deleteBody, "popBackStack") == 0,
        )
        assertEquals(
            "删除链体内那句失败文案逐字仍在（`$DELETE_ERROR_COPY` 恰好一处，实到 " +
                occurrences(deleteBody, DELETE_ERROR_COPY) + " 处）：删不到那一档**必须**在这页念红条，" +
                "不许因为「退栈搬走了」就顺手把这半也搬进 VM/提示条 —— 那一条搬走就是退化成静默",
            1,
            occurrences(deleteBody, DELETE_ERROR_COPY),
        )
        // 三枚 onBack() 宿主逐一点名（朝窄：摘掉任意一枚 ⇒ 总数变 ⇒ 红）。
        // 第三枚（保存链那一支）落在 `scope.launch` 体内，用块取法单独钉（见下面 saveBody 那一格），
        // 所以这里只逐字点名那两枚**不需要切块就唯一**的宿主。
        assertEquals(
            "编辑器全文 `onBack(` 恰好 " + ON_BACK_SITES + " 处（实到 " + occurrences(editor, "onBack(") +
                " 处），逐枚宿主是：保存链（见下面那一格）/ 那道丢弃闸 $REQUEST_BACK / 丢弃确认框那颗按钮 " +
                "$DISCARD_ON_BACK。**少一处** = 有人摘了一枚调用点（或把删除链那一枚又" +
                "「顺手补回来」的同时挪走了别的一枚）；**多一处** = 退栈又长回一枚会被组合取消的位置",
            ON_BACK_SITES,
            occurrences(editor, "onBack("),
        )
        for (verbatim in listOf(REQUEST_BACK, DISCARD_ON_BACK)) {
            assertEquals("那一枚 `onBack()` 宿主的行内形状逐字仍在「$verbatim」：", 1, occurrences(editor, verbatim))
        }
        val saveBody = launchBodyAround(editor, editor.indexOf(SAVE_HAND))
        assertEquals(
            "保存链那一枚 scope.launch 体里 `onBack()` 恰好一处（普查 ③ 在册的那一枚；实到 " +
                occurrences(saveBody, "onBack(") + " 处）：它今天不咬人靠的是「写完课程还在、闸门不摘页」，" +
                "不是靠运气。把它搬走也**要回来重判这一格**（连同第 ③ 层那本册子）。" +
                "⚠️ T260 给这一支补了**另一侧**：页面那一支在写库期间被组合取消（转屏 / 摘页）时，" +
                "退栈由目的地层那枚第三协程按 VM 的结论旗认领；报到那一手（`onSaveTailReached()`）" +
                "与认领协程的互斥形状归 ui/editor/EditorSaveChainTailReceiverGuardTest 钉，" +
                "两枚都在才算修完",
            1,
            occurrences(saveBody, "onBack("),
        )
        val vm = blankCommentsKeepingLiterals(source(SCHEDULE_VIEW_MODEL))
        assertEquals(
            "VM 全文 `onBack(` 必须 0 处（实到 " + occurrences(vm, "onBack(") + " 处）：退栈那一手不许" +
                "「搬到不随组合取消的接收者上」的另一副样子 —— 搬进 viewModelScope 就是对着 navController " +
                "多 pop 一格（页面还在的时候它照 pop，页面早走了它还是 pop）",
            0,
            occurrences(vm, "onBack("),
        )
    }

    // ─────────────── ② 两档分开：闸门与「从来没读到」那一档逐字未动 ───────────────

    /**
     * 卡面那两条约束里容易被"顺手撞坏"的一条：闸门 `:877` 的原意（首帧课程列表为空 / 刚切学期时
     * 编辑器不许渲染，否则 `initialCourse=null` 组合并恢复草稿、保存用 `id=0L` 插出重复课程，R5 F-41）
     * 不许为了修删课而放宽或改成"courseMissing 就立刻 popBackStack"。
     *
     * 所以这一层不去数"有几枚 popBackStack"（MainActivity 里八个目的地各有一枚，文件级计数既判不到
     * 这一处又替别人背债 —— `WriteChainReceiverWiringGuardTest` 档 ⑬ 撞过这一洞），而是**按那一枚
     * composable 块取形状**：从 `route = "editor/{courseId}"` 起花括号配平切出整块目的地，在**块内**判
     * 旧协程的键表与条件逐字、闸门判据逐字、`initialCourse = course` 逐字。
     */
    @Test
    fun `那道渲染闸门与从来读不到那一档逐字未动 两档不许并成一档`() {
        val raw = source(MAIN_ACTIVITY)
        val activity = blankCommentsKeepingLiterals(raw)
        val destHits = indexOfAll(activity, EDITOR_DESTINATION_ANCHOR)
        assertEquals("`$EDITOR_DESTINATION_ANCHOR` 恰好一处（本守卫取块的宿主）：", 1, destHits.size)
        val destination = braceBodyFrom(
            activity,
            activity.indexOf('{', destHits.first()),
        ).body

        assertEquals(
            "`$COURSE_MISSING_DECL` 在这一枚目的地块内恰好一处（它是两档共用的那枚事实）：" +
                "定义改成 `courseId >= 0 && course == null || …` 之类就等于把两档搅在一起",
            1,
            occurrences(destination, COURSE_MISSING_DECL),
        )
        assertEquals(
            "`$NEVER_READ_EFFECT` 恰好一处：这一枚协程**只管**「这门课从来没读到」那一档。" +
                "**朝窄扭就在这一格红**：往它的键表里加 `courseMissing`（= 把删掉那一档并进来，" +
                "于是「首帧列表为空 / 刚切学期」那种帧也会弹栈 = 用户读作「点了没反应」甚至误弹），" +
                "或把它整枚删掉（= 从来没读到那一档失去退栈），都改的是这一族的原意。" +
                "R5 F-41 那句注释说的就是这个洞：" + hint(raw, indexOfAll(activity, NEVER_READ_EFFECT)),
            1,
            occurrences(destination, NEVER_READ_EFFECT),
        )
        val neverReadAt = activity.indexOf(NEVER_READ_EFFECT)
        assertTrue(
            "锚点取到了却切不出那一枚协程体（`$NEVER_READ_EFFECT` 不在盘上？）",
            neverReadAt >= 0,
        )
        val neverReadBody = braceBodyFrom(activity, activity.indexOf('{', neverReadAt + NEVER_READ_EFFECT.length)).body
        assertEquals(
            "那枚旧协程体内的判据 `$NEVER_READ_CONDITION` 逐字仍在恰好一处（实到 " +
                occurrences(neverReadBody, NEVER_READ_CONDITION) + " 处）：`!uiState.loading` 排在前面是" +
                "「数据还没到就不判」那一半，摘掉它就是在首帧弹栈",
            1,
            occurrences(neverReadBody, NEVER_READ_CONDITION),
        )
        assertEquals(
            "那枚旧协程体内 `$POP_BACK_STACK` 恰好一处（实到 " + occurrences(neverReadBody, POP_BACK_STACK) + " 处）",
            1,
            occurrences(neverReadBody, POP_BACK_STACK),
        )
        assertEquals(
            "渲染闸门 `$RENDER_GATE` 在这一枚目的地块内恰好一处（实到 " +
                occurrences(destination, RENDER_GATE) + " 处）：判据两半（`!uiState.loading` 与 " +
                "`!courseMissing`）一枚都不许摘 —— 摘掉 `!courseMissing` 就是让编辑器以 " +
                "initialCourse=null 组合、恢复草稿、保存时以 id=0L 插出重复课程（R5 F-41 原案）",
            1,
            occurrences(destination, RENDER_GATE),
        )
        assertEquals(
            "`$INITIAL_COURSE_ARG` 逐字仍在：递进编辑器的必须是**这一帧读到的那一行**，不是任何" +
                "「记得的那份快照」。拿 remember 住的过期课程顶掉它，等于把 R5 F-41 那一族" +
                "从「不渲染」改回「渲染一份不存在的课」（实到 " + occurrences(destination, INITIAL_COURSE_ARG) + " 处）",
            1,
            occurrences(destination, INITIAL_COURSE_ARG),
        )
        // 这一枚目的地里的退栈主人共四处：从来读不到那一档 + 本页删掉那一档 + 保存链收尾那一档
        // （T260 补的认领）+ 页面上那些「返回」。
        // 数在一枚尺里是故意的：**并档**（把两档合成一枚 courseMissing 就弹）或**多开一路**
        // （例如往 onDelete lambda 里塞一枚 pop，第 ① 层那格也会一起红）都会让这个数变。
        assertEquals(
            "这一枚目的地块内 `$POP_BACK_STACK` 恰好四处（从来读不到那一档 / 本页删掉那一档 / " +
                "保存链收尾没人认领那一档（T260）/ 递给编辑器的 `onBack` lambda，实到 " +
                occurrences(destination, POP_BACK_STACK) + " 处）：多一处 = 又多一条路径能弹这一格栈，" +
                "删失败 / 保存成功那些档就得分不清谁弹的；少一处 = 有一档失去认领" +
                "（整屏空白那一族回来）",
            4,
            occurrences(destination, POP_BACK_STACK),
        )
        assertEquals(
            "递给编辑器的那枚 `$ON_BACK_LAMBDA` 逐字仍在恰好一处（保存链那一支 `onBack()` 的另一端 —— " +
                "本卡按红线没动它）：实到 " + occurrences(destination, ON_BACK_LAMBDA) + " 处",
            1,
            occurrences(destination, ON_BACK_LAMBDA),
        )
        assertEquals(
            "这一枚目的地块内 `LaunchedEffect(` 恰好三枚（旧那枚「从来没读到」+「本页删掉了」+ " +
                "T260 那枚「保存在途时页面那一支被取消，收尾归这里」；实到 " +
                occurrences(destination, "LaunchedEffect(") + " 处）：枚数就是这一族的档位表，" +
                "长出第四枚 = 又开一路自动退栈，谁都没被点名。第三枚的形状判据（点火即消费排在退栈之前、" +
                "判据只吃内核那一枚 claimedByDestination）在 " +
                "ui/editor/EditorSaveChainTailReceiverGuardTest 逐层钉",
            3,
            occurrences(destination, "LaunchedEffect("),
        )
    }

    // ─────────────── ③ 家族棘轮：页 scope 上 await 完一次写库再自己退栈的落点册子 ───────────────

    /**
     * 全仓扫：每枚由 `rememberCoroutineScope()` 供给的变量名下的 `launch { … }` 体里，
     * **同时**出现「写库那一手」与「自我退栈那一手」的落点。判据是双向等集（在册的必须还在、
     * 不在册的一枚都不许多），因为这一族的账今天**没收完**：
     * `performSave()` 那一支（卡面 ③ 的普查项）与 iClass 登录那一支按红线都**没动**，
     * 它们各自的"今天为什么不咬人"写在 [AWAIT_THEN_EXIT] 的登记里。
     */
    @Test
    fun `页面组合期scope上await完一次写库再自己退栈的落点册子 长一枚红少一枚也红`() {
        val observed = awaitThenExitLandingLedger()
        val grown = mutableListOf<String>()
        val shrunken = mutableListOf<String>()
        for ((site, count) in observed.toSortedMap()) {
            val cap = AWAIT_THEN_EXIT[site]
            if (cap == null) {
                grown += "$site 新长出 $count 处（不在册子里）"
            } else if (count > cap) {
                grown += "$site 从册子登记的 $cap 处涨到 $count 处"
            }
        }
        for ((site, cap) in AWAIT_THEN_EXIT.toSortedMap()) {
            val count = observed[site] ?: 0
            if (count < cap) {
                shrunken += "$site 在册登记 $cap 处，盘上只剩 $count 处"
            }
        }
        assertTrue(
            "页 scope（`rememberCoroutineScope()` 供给的变量）名下「await 完一次写库再自己退栈」的" +
                "落点册子与盘面对不上（朝宽：长出新一枚；朝窄：在册那一枚被摘走）：\n" +
                (if (grown.isEmpty()) "" else "  多出来：" + grown.joinToString("；") + "\n") +
                (if (shrunken.isEmpty()) "" else "  少了：" + shrunken.joinToString("；") + "\n") +
                "  盘上导出：" + observed.toSortedMap().entries.joinToString { (s, n) -> "$s×$n" } +
                "\n  册子登记：" + AWAIT_THEN_EXIT.toSortedMap().entries.joinToString { (s, n) -> "$s×$n" } +
                "\n这一族的病本卡逐条写在类首（组合期 scope 被闸门取消 ⇒ 退栈那一手一行都不执行 ⇒ 栈顶" +
                "留着一页渲染成空的目的地）。**多出来一枚**只有一条正确的收法：像 T166 对删除链那样，" +
                "把退栈那一手搬到不随该子树取消的接收者上（目的地层的协程），并把那一枚从册子里摘掉" +
                "同时把第 ①② 层的形状判据一起改过来 —— **别只摘名册不摘盘**（那是本仓登记过的那一洞）。" +
                "\n**少了一枚**也红不是不许：那是有人把 `performSave()` / iClass 登录那一支也搬走了，" +
                "搬得对，但那一档的「今天为什么不咬人」这笔账就得从登记里销掉、并回 " +
                "WriteChainReceiverWiringGuardTest 档 ⑫ 把 `onBackSites` 一起改。" +
                "\n复算：grep -rn 'rememberCoroutineScope()' app/src/main --include=*.kt",
            grown.isEmpty() && shrunken.isEmpty(),
        )
        assertFalse(
            "册子被掏空了：这一族的账今天没收完（保存链那一支还挂在页 scope 上），一枚都不留" +
                "就等于这一格不再判任何东西",
            AWAIT_THEN_EXIT.isEmpty(),
        )
        // 删除链那一枚必须确实**不在**盘上（棘轮的"这一侧"）：由第 ① 层单独判过，这里再数一次。
        // 册子的键是 文件#写库那一手→退栈那一手，所以按文件前缀收拢再数。
        val editorSites = observed.filter { (site, _) -> site.startsWith("$EDITOR#") }
        assertEquals(
            "编辑器那一枚文件里「await 完一次写库再自己退栈」的落点应当恰好 1 处（保存链那一枚；实到 " +
                editorSites.values.sum() + " 处：" + editorSites.keys + "）：删除链那一枚由 T166 摘干净了，" +
                "它要是回到盘上（`$EDITOR#$DELETE_HAND→onBack()` 会作为**不在册的第二枚**露出来），" +
                "第 ① 层与本格会一起红",
            1,
            editorSites.values.sum(),
        )
    }

    // ---------------- 源码核对小工具（抄 WriteChainReceiverWiringGuardTest / CourseDeletionWiringGuardTest）----------------

    /**
     * 盘上侧：全仓 main，每枚 `rememberCoroutineScope()` 供给的变量名下的 `launch { … }` 体里，
     * 同时含 [WRITE_NEEDLES] 之一与 [EXIT_NEEDLES] 之一的落点，按 文件 × 写库那一手 × 退栈那一手 计数。
     */
    private fun awaitThenExitLandingLedger(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        val decl = Regex("""val\s+(\w+)\s*=\s*rememberCoroutineScope\(\)""")
        for ((relative, text) in mainSources()) {
            val code = blankCommentsKeepingLiterals(text)
            for (name in decl.findAll(code).map { it.groupValues[1] }.toSet()) {
                for (open in launchBraces(code, name)) {
                    val body = braceBodyFrom(code, open).body
                    val writes = WRITE_NEEDLES.filter { body.contains(it) }
                    val exits = EXIT_NEEDLES.filter { body.contains(it) }
                    if (writes.isEmpty() || exits.isEmpty()) continue
                    val site = "$relative#${writes.sorted().joinToString("+")}→${exits.sorted().joinToString("+")}"
                    out[site] = (out[site] ?: 0) + 1
                }
            }
        }
        return out
    }

    /** `<name>.launch {` 那一次调用开出来的花括号位置（接收者前面不许贴标识符字符） */
    private fun launchBraces(code: String, name: String): List<Int> {
        val receiver = "$name.launch"
        val opens = mutableListOf<Int>()
        for (at in indexOfAll(code, receiver).filter { it == 0 || !(code[it - 1].isLetterOrDigit() || code[it - 1] == '_') }) {
            var i = at + receiver.length
            if (i < code.length && code[i] == '(') {
                var depth = 0
                var closedAt = -1
                while (i < code.length) {
                    when {
                        code[i] == '(' -> depth++
                        code[i] == ')' -> { depth--; if (depth == 0) { closedAt = i; break } }
                        code[i] == '{' -> break // 括号里不该出现花括号：这一枚形状不认识
                    }
                    i++
                }
                if (closedAt < 0) continue
                i = closedAt + 1
            }
            while (i < code.length && code[i].isWhitespace()) i++
            if (i < code.length && code[i] == '{') opens += i
        }
        return opens
    }

    /** 从 [hit] 往前找**包围它的那一枚** `<某名>.launch {`，取出那一段协程体（找不到就抛：静默跳过等于没有守卫） */
    private fun launchBodyAround(code: String, hit: Int): String {
        check(hit >= 0) { "锚点没找到，切不出协程体" }
        val at = code.lastIndexOf(".launch {", hit)
        check(at >= 0) {
            "那一处往前找不到包围它的 .launch {：这一趟操作换了载体（不在协程里了？），本守卫要跟着改"
        }
        val extracted = braceBodyFrom(code, code.indexOf('{', at))
        check(hit > extracted.openBrace && hit < extracted.closeBrace) {
            "取出的协程体不含那一处（花括号配平跑偏）：hit=$hit body=${extracted.openBrace}..${extracted.closeBrace}"
        }
        return extracted.body
    }

    private data class Extracted(val body: String, val openBrace: Int, val closeBrace: Int)

    /** 从已知开括号位起按花括号配平（跳过字符串与字符字面量）取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): Extracted {
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
                    if (depth == 0) return Extracted(source.substring(open + 1, index), open, index)
                }
            }
            index++
        }
        throw IllegalStateException("花括号没配平：从偏移 $open 起切不出一段体")
    }

    /** 报错消息里点名 行号 + 原文（只报个数等于没有守卫） */
    private fun hint(raw: String, positions: List<Int>): String {
        if (positions.isEmpty()) return " 盘上一处都没有"
        val lines = raw.split('\n')
        return positions.joinToString(prefix = "\n  ") { at ->
            val index = raw.substring(0, at).count { it == '\n' }
            "L${index + 1}: " + lines.getOrElse(index) { "<越界>" }.trim()
        }
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

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun mainSources(): Map<String, String> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到 $root：包结构挪过家，本守卫要跟着改", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associateBy({ it.relativeTo(findMainJavaDir()).path.replace('\\', '/') }, { it.readText() })
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
     * 本卡尤其需要抹注释：`MainActivity` 与编辑器那几行**注释里写着 `onBack()`、`courseMissing`
     * 与「退栈」的整段理由**，不抹的话它们会被数成盘上多出来的落点。
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
        const val EDITOR = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"
        const val ICLASS_LOGIN = "com/buaa/schedule/ui/signin/iclass/IClassLoginScreen.kt"
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"

        // ── ① 目的地层那一手退栈的形状 ──
        // T177 重登（只改期望值，判据语义没动：仍是「这一枚凭据逐字恰好一处」）：旗标的载体从
        // `remember(backStackEntry)` 换成本条 entry 的 savedStateHandle —— 前者不跨配置变化，
        // 「删除已在途」这一刻转屏会把旗丢回 false，于是删成了也没人弹栈（#211 那一档）。
        // 现在这枚凭据的寿命 = 这一条 back stack entry 的寿命（转屏存活、换 entry 就换一份 handle），
        // 而「转屏之后不许替一次早就结束的删除再弹一次栈」那一半改由**点火即消费**兜（协程体内写回
        // false 排在退栈之前）—— 那一族逐形钉在 ui/editor/DeleteFlagConfigSurvivalGuardTest。
        const val FLAG_DECL =
            "val deleteRequestedByThisEntry by backStackEntry.savedStateHandle"
        const val FLAG_SET =
            "backStackEntry.savedStateHandle[DELETE_REQUESTED_BY_THIS_ENTRY] = true"
        const val EXIT_EFFECT = "LaunchedEffect(deleteRequestedByThisEntry, courseMissing, uiState.loading)"
        const val EXIT_CONDITION = "if (deleteRequestedByThisEntry && courseMissing && !uiState.loading) {"
        const val RENDER_GATE = "if (!uiState.loading && !courseMissing) {"
        const val ON_DELETE_ANCHOR = "onDelete = { target ->"
        const val AWAIT_DELETE = "viewModel.deleteCourseAndAwait(target)"
        const val POP_BACK_STACK = "navController.popBackStack()"
        const val ON_BACK_LAMBDA = "onBack = { navController.popBackStack() },"

        const val SCOPE_DECL = "val scope = rememberCoroutineScope()"
        const val DELETE_HAND = "onDelete(target)"
        const val SAVE_HAND = "saveCourseDraft("
        const val DELETE_ERROR_COPY = "saveError = \"删除失败，请重试\""
        const val REQUEST_BACK = "if (isDraftDirty) showDiscardDialog = true else onBack()"
        const val DISCARD_ON_BACK = "TextButton(onClick = { showDiscardDialog = false; onBack() }) {"
        const val ON_BACK_SITES = 3

        // ── ② 两档分开的另一半：从来读不到那一档与那道闸门 ──
        // 取块宿主：这一枚编辑器目的地 composable 的 entry lambda（全仓 main 只此一处带 backStackEntry 形参，
        // 拿 `route = "editor/{courseId}"` 当宿主锚点会把字符串里那对花括号喂给配平器，切出来的块是错的）
        const val EDITOR_DESTINATION_ANCHOR = ") { backStackEntry ->"
        const val COURSE_MISSING_DECL = "val courseMissing = courseId >= 0 && course == null"
        const val NEVER_READ_EFFECT = "LaunchedEffect(courseId, uiState.loading)"
        const val NEVER_READ_CONDITION = "if (!uiState.loading && courseMissing) {"
        const val INITIAL_COURSE_ARG = "initialCourse = course,"

        // ── ③ 棘轮的册子与两把尺 ──
        /** 算「await 完一次写库」的那些手（suspend 才会真等；非 suspend 的 fire-and-forget 不算） */
        val WRITE_NEEDLES = listOf(
            SAVE_HAND,
            "onDelete(",
            "viewModel.deleteCourseAndAwait(",
            "viewModel.deleteCourseGroupAndAwait(",
            "viewModel.saveCourseAndAwait(",
            "viewModel.updateCourseAndAwait(",
            "viewModel.deleteCourse(",
            "viewModel.deleteCourseGroup(",
            "viewModel.saveCourse(",
            "viewModel.updateCourse(",
            "applyConflictShift(",
            "onApplyShift(",
            "IClassSession.login(",
        )

        /** 算「自己退栈 / 自己把这一页交出去」的那些手 */
        val EXIT_NEEDLES = listOf(
            "onBack()",
            "popBackStack",
            "onLoggedIn()",
            "onImportPrepared(",
            "navigateTopLevel(",
        )

        /**
         * 参照物（本卡改完那天盘点的真值）：页 scope 名下「await 完一次写库 → 自己退栈」的落点。
         * 身份 = 文件#写库那一手→退栈那一手，判据 = 与盘上**互为等集**（多一枚红、少一枚也红）。
         *
         * 两枚在册，各自的"今天为什么不咬人"是登记的一部分（卡面 ③：只报不改，交回编排者判）：
         * - [EDITOR] 的 `saveCourseDraft(→onBack()`：**保存链**。写完那一行还在屏上 ⇒
         *   `courseMissing` 不成立 ⇒ 那道闸门不摘页 ⇒ 这枚 scope 活着 ⇒ `onBack()` 执行得到。
         *   `editor/-1`（新增）那一档更宽：`courseId >= 0` 直接为假，`courseMissing` 恒 false。
         *   ⚠️ 它仍然脆：任何让「保存之后那一行不再在屏上」的改法（合并整组、保存即删原行…）
         *   都会让它变成第二枚删除链 —— 那时本格会红，正确的收法照 T166 对删除链那一手。
         *   **T260 已经落地的是这条登记的另一种收法**：这一支没有从盘上摘掉（页面活着时它照旧自己弹，
         *   所以 `onBack(` 仍 1 处、本格仍在册），但脆的那一半补了背撑 —— 页面那一支被组合取消时
         *   由目的地层按 VM 的结论旗认领退栈（`EditorSaveChainTailReceiverGuardTest` ③ 层），
         *   写库在飞那一刻的闸门也不再只认组合本地的 `saving`（⑤ 层）。
         *   本格与第 ① 层那格因此**不许**被当成「已搬走」而销账：册子判的是盘上还在不在，
         *   还在就还在册 —— 谁真把这一支整体搬去不随组合取消的接收者上，那才是要连名册带
         *   WriteChainReceiverWiringGuardTest 档 ⑫ 的 `onBackSites` 一起重判的那一天。
         * - [ICLASS_LOGIN] 的 `IClassSession.login(→onLoggedIn()`：**登录链**。它 await 的是网络登录
         *   （顺带把 cookie 落到本地），退栈那一手是 `onLoggedIn()` = `navigate("spoc_scan") +
         *   popUpTo("iclass_login") { inclusive = true }`。这一枚目的地**没有任何"数据没到就不渲染"
         *   的闸门**，页面在 await 期间不会被摘出组合（唯一的 dispose 来源是用户自己按返回，
         *   那时取消掉这次跳转恰恰是对的），所以今天不咬人。同一族搬法与保存链一起留给编排者判。
         */
        val AWAIT_THEN_EXIT = mapOf(
            "$EDITOR#$SAVE_HAND→onBack()" to 1,
            "$ICLASS_LOGIN#IClassSession.login(→onLoggedIn()" to 1,
        )
    }
}
