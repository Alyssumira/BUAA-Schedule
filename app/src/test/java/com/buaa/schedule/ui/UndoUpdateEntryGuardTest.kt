package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T122：钉住「编辑课程」这一档撤销条目的**现状形状**，以及"同值不压栈"这一判
 * **今天为什么还不能按 `original == course` 落**。
 *
 * ## 这张卡量的是什么
 *
 * `docs/derived-field-audit.md` §9.5② 登记的说法是：[com.buaa.schedule.ui.ScheduleViewModel.updateCourse]
 * 写完库以后**无条件**压一枚撤销条目、不比 before/after ⇒ 同值重写也压 ⇒「用户点『撤销』、屏幕念
 * 『已撤销：编辑课程』而课表纹丝不动」。第 0 步复核下来三件事成立、一句指不到落点：
 * - 成立：[com.buaa.schedule.data.undo.UndoManager] 是全局 LIFO 栈（`ArrayDeque` + `CAPACITY = 10` +
 *   `pop() = removeLastOrNull()`），不是单槽；
 * - 成立：`undo()` 无参、捞栈顶，全仓 UI 里**只有两枚**撤销入口，两枚都挂在**删除之后**的提示条上；
 * - 成立：所以「编辑」那一档的条目在删除路径永远压在顶上时**没有属于它自己的 pop 时机**，
 *   §9.5② 那句按字面读（点在某次编辑之后）走不通；
 * - **但**它不是完全不可达：两枚提示条都是 `SnackbarDuration.Long`（约 2.75 秒），那扇窗里同一页
 *   还能继续写库，于是「编辑条目压在删除条目之上」这一格排得出来 —— 只是那条链的病灶是
 *   **pop 捞的是栈顶**（T123 的靶子），不是"多压了一枚同值条目"。
 *
 * ## 为什么本卡判成 deferred、main 一字未改
 *
 * 想收掉 §9.5② 那句，就得在压栈前判「这一趟到底动没动东西」。而**这一判今天在压栈点算不出来**：
 * 全仓唯一一枚**保证**同值的写库路径（课表管理页反复点**当前已选中**那块色板，
 * `CourseManagementScreen` 的 `onPickColor` → `primary.copy(colorIndex = index, customColorArgb = null)`，
 * 主行与库里那一行逐字段相等）走的正是 `options.applyToGroup` 那一支；而那一支写的是**兄弟行**，
 * 并且无条件把整组的 `isManualOverride` 翻成 `true`（`ScheduleRepository.updateCourseGroupAppearance`），
 * 而那枚函数**返回 `Unit`** —— 写没写到东西不回报给调用点。于是调用点只有两个选择，都不能接受：
 * - 「组那一支跑了 ⇒ 算动过」⇒ 那一枚同值条目照旧留在栈里，改了等于没改；
 * - 「主行同值 ⇒ 什么都没动」⇒ 把一次真的改了兄弟行的编辑的撤销记录**静默丢掉**，比现状更贵。
 *
 * 所以真正的**前置条件**是「组写先交出它自己的结论」（T121 那一族形状），那是仓储层的一张卡。
 * 下面第 ② ③ 两层钉的就是"前置条件还没落地"这件事本身。
 *
 * ## 本守卫防的是什么
 *
 * 防下一个人拿 §9.5② 那句话当尺子，直接在 `if (original != null)` 上补一枚 `&& original != course`
 * 就把卡收了。那一改会同时踩掉三样真东西，第 ② 层逐样钉着：部分周次拆行顺手清掉的兄弟片段
 * （R5 F-35）、`savedId != course.id` 的另发行、组那一支写在兄弟行上的外观。
 * 真要改，先让第 ③ 层那枚签名断言红掉（组写开始返回结论），再回来连着那三条一起重判。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。断言按**落点 / 次序 / 逐字**取判据，
 * 不数总出现次数 —— 「枚数对但落点错」是本仓登记过的洞。报错消息里带复算命令。
 */
class UndoUpdateEntryGuardTest {

    // ─────────────── ① 压栈点现状：只闸「原文读没读回来」，且全仓只有这一处压 Update ───────────────

    @Test
    fun `压栈那一支今天只闸在原文读没读回来 并且全仓只有这一处压Update条目`() {
        val raw = readMainSource(SCHEDULE_VIEW_MODEL)
        val body = updateCourseBody()
        val base = bodyBase(raw) + body.indexOf(READ_ORIGINAL)

        assertEquals(
            "`UndoManager.pushUpdate(` 全仓恰好一处（就在 `updateCourse` 体内）。\n" +
                "复算：grep -rn \"pushUpdate(\" app/src --include='*.kt'\n" +
                "长出第二处 = 又有一条路径能往栈里塞 Update，而本守卫只钉住了原来那一处的形状",
            listOf(SCHEDULE_VIEW_MODEL_NAME),
            readAllMainSources().filter { occurrences(it.value, PUSH_UPDATE) > 0 }.keys.toList(),
        )
        assertEquals(
            "压栈的唯一闸门必须还是 `if (original != null) {`：`original` 是写库**之前**从库里读回来的那一行，" +
                "今天它是「这次保存有没有对象可撤销」的唯一证据。这一处变了就是判据换了，" +
                "要回来重判第 ② ③ 层（L" + lineOf(raw, base) + "）",
            1,
            occurrences(body, UNCONDITIONAL_GATE),
        )
        // 反向钉：今天**没有**同值比较。谁加了，第 ② ③ 层会跟着红；只有那两层的前置条件落地才允许加。
        assertFalse(
            "压栈点今天不许出现只看主行的同值判据（`original != course` / `original == course`）。" +
                "主行同值不等于整次保存什么都没动：见第 ② 层三条真凭据、第 ③ 层「组写报不出结论」。" +
                "要加，先让 `updateCourseGroupAppearance` 返回它自己的结论，再连同那三样一起判",
            body.contains("original != course") || body.contains("original == course"),
        )
        assertEquals(
            "`updateCourse` 体内读原文那一趟恰好一处：`val original = repository.getCourseById(course.id)` " +
                "是唯一给得出「改之前是什么」的证据源，它一旦变成两趟（或换成手里那份 `course`），" +
                "第 ① 层那枚闸门的含义就整个变了",
            1,
            occurrences(body, READ_ORIGINAL),
        )
        assertTrue(
            "次序：读原文 → 写库 → 才压栈。倒过来就是先记账后干活（本卡按现状钉，不动它）：" +
                "\n  读原文 L" + lineOf(raw, base) +
                " / 压栈 L" + lineOf(raw, base + body.indexOf(PUSH_UPDATE) - body.indexOf(READ_ORIGINAL)),
            body.indexOf(READ_ORIGINAL) in 0 until body.indexOf(PUSH_UPDATE),
        )
    }

    // ─────────────── ② 三条「主行同值 ≠ 什么都没动」的证据维度，各有落点 ───────────────

    @Test
    fun `主行同值不算什么都没动 三条真凭据逐条钉在主行之外`() {
        val vmRaw = readMainSource(SCHEDULE_VIEW_MODEL)
        val body = updateCourseBody()
        val base = bodyBase(vmRaw)
        val push = body.indexOf(PUSH_UPDATE)

        // 维度一：部分周次那一支顺手清掉的兄弟片段 —— 快照必须带着它们
        assertEquals(
            "`removed = edit.removed,` 必须还进快照（恰好一处）。这一行就是 `:528` 那段 KDoc 说的账：" +
                "部分周次拆行会顺手清掉同组兄弟片段，只记 before/after 的话撤销之后它们永久消失（R5 F-35）。" +
                "谁把同值判据加在 `if (original != null)` 上而不同判这一条，丢的就是这些行",
            1,
            occurrences(body, SNAPSHOT_REMOVED),
        )
        assertEquals(
            "`reminders = edit.reminders,` 同理：撤销只回得来课程、回不来被连带清掉的课前提醒",
            1,
            occurrences(body, SNAPSHOT_REMINDERS),
        )
        assertTrue(
            "维度一的来源必须排在压栈之前（先写库才知道清掉了哪几行）：" +
                "\n  拆行写库 L" + lineOf(vmRaw, base + body.indexOf(PARTIAL_WEEKS_WRITE)) +
                " 早于 压栈 L" + lineOf(vmRaw, base + push),
            body.indexOf(PARTIAL_WEEKS_WRITE) in 0 until push,
        )

        // 维度二：拆行另发行 —— afterId 与 course.id 可以不是同一行
        assertEquals(
            "`afterId = edit.savedId,` 必须还在：仓储层给的最终行 id 可以**不等于** `course.id`（拆行另发一行）。" +
                "这时候 before/after 两个值对象逐字段一样，也说明不了「库里那一行没动」—— 动的可能是另一行",
            1,
            occurrences(body, SNAPSHOT_AFTER_ID),
        )
        assertEquals(
            "撤销侧配套那道守卫也必须还在，它就是「afterId 可能是另一行」的书面凭据：" +
                "`if (action.afterId != null && action.afterId != action.before.id) {`",
            1,
            occurrences(blankCommentsKeepingLiterals(readMainSource(SCHEDULE_REPOSITORY)), REPO_AFTER_ID_GUARD),
        )

        // 维度三：组那一支写的是兄弟行
        assertEquals(
            "`if (options.applyToGroup && course.sourceGroupKey != null) {` 必须还在且恰好一处：" +
                "这一支写的是**别的行**，主行 `before == after` 推不出「整次保存什么都没动」",
            1,
            occurrences(body, GROUP_BRANCH),
        )
        assertEquals(
            "`repository.updateCourseGroupAppearance(course)` 恰好一处：" +
                "它就是本卡判 deferred 的那枚主角 —— 见第 ③ 层",
            1,
            occurrences(body, GROUP_WRITE),
        )
        assertTrue(
            "次序：压栈 → 才写组。这一条是「压栈点判不了组那一支」的**结构**证明，不是措辞：" +
                "\n  压栈 L" + lineOf(vmRaw, base + push) +
                " 早于 组写 L" + lineOf(vmRaw, base + body.indexOf(GROUP_WRITE)) +
                "\n复算：grep -n \"pushUpdate(\\|updateCourseGroupAppearance(course)\" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt",
            push in 0 until body.indexOf(GROUP_WRITE),
        )
    }

    // ─────────────── ③ 前置条件还没落地：组写不报结论，VM 也没有权威的组视图 ───────────────

    @Test
    fun `组那一支今天不回报结论 所以同值判据在压栈点算不出来`() {
        val repoRaw = readMainSource(SCHEDULE_REPOSITORY)
        val repo = blankCommentsKeepingLiterals(repoRaw)
        val group = functionBody(repo, GROUP_SIGNATURE, "ScheduleRepository.updateCourseGroupAppearance")

        assertEquals(
            "`updateCourseGroupAppearance` 的签名必须仍是**没有返回类型**那一句（即 `Unit`）。\n" +
                "复算：grep -n \"suspend fun updateCourseGroupAppearance\" app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt\n" +
                "本卡判 deferred 的全部理由就是这一句：它写完一组行以后什么都不回报，调用点无从知道动没动。" +
                "**这一枚一旦变红，说明前置条件落地了** —— 那时才允许回到 `updateCourse` 去判同值，" +
                "并且要连同第 ② 层那三条维度一起判，不许只判主行",
            1,
            occurrences(repo, GROUP_SIGNATURE),
        )
        assertEquals(
            "也不许已经藏着一枚「换了名、会报结论」的组写绕开上面那枚签名" +
                "（同名重载、或另起一枚 `updateCourseGroupAppearanceXxx`）",
            1,
            occurrences(repo, "suspend fun updateCourseGroupAppearance"),
        )
        assertEquals(
            "它按 `sourceGroupKey` 逐行改写整组：`courseDao.getByGroupKey(groupKey).forEach` 必须还在。" +
                "被改的行里**包含主行自己**，所以「主行同值」连主行都保不住",
            1,
            occurrences(group.body, GROUP_FOREACH),
        )
        assertEquals(
            "`isManualOverride = true,` 必须仍被**无条件**写进组内每一行（恰好一处，L" +
                lineOf(repoRaw, group.openBrace + group.body.indexOf(GROUP_MANUAL_OVERRIDE)) + "）：" +
                "这是「组那一支即便外观全等也可能是真改动」的硬证据，" +
                "也是本卡不肯按「跑过组写就算没动」省事的原因",
            1,
            occurrences(group.body, GROUP_MANUAL_OVERRIDE),
        )
        assertEquals(
            "仓储层今天没有供调用点预判的按组读数口（`getCoursesByGroupKey` 之类 = 0 枚）。\n" +
                "复算：grep -rn \"suspend fun get.*GroupKey\" app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt\n" +
                "若下一张卡选「让 VM 自己先读一组再判」这一条会红，那是要的：那条路得连带把" +
                "「读到的组是不是写库前那一刻的组」这笔时序账钉清楚",
            0,
            occurrences(repo, "suspend fun getCoursesByGroupKey"),
        )

        // VM 手里那份课程表是过滤过的，不能当组视图用
        val vm = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        assertEquals(
            "`uiState.courses` 必须仍是过滤出来的 `visibleCourses`（不是全表）：" +
                "拿它当「整组兄弟行」来预判会漏掉被过滤掉的行 ⇒ 误判成「什么都没动」⇒ " +
                "一次真编辑丢了撤销记录，那是比现状更贵的方向",
            1,
            occurrences(vm, STATE_USES_VISIBLE),
        )
        assertEquals(
            "`val visibleCourses = CourseFilter.visibleIn(courses, semester)` 必须还在：" +
                "这句就是上面那枚断言的根据（按学期 + 手动课过滤过）",
            1,
            occurrences(vm, VISIBLE_FILTERED),
        )
        val body = updateCourseBody()
        assertEquals(
            "`updateCourse` 体内对仓储层的调用今天恰好四趟：读原文 / 拆行写库 / 覆盖写库 / 组那一支外观写。\n" +
                "复算：grep -n \"repository\\.\" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt | sed -n '1,80p'\n" +
                "多出第五趟 = 有人新加了一趟读数来判同值 —— 本卡判 deferred 时明确不走这条路（" +
                "「读回来的那一组是不是写库前那一刻的那一组」这笔时序账得先钉清楚）；" +
                "少一趟就是这条链换了入口，第 ②③ 层要重判：" +
                "\n" + REPO_CALLS_IN_UPDATE.joinToString("\n") { "  ${it}: ${occurrences(body, it)} 趟" },
            4,
            REPO_CALLS_IN_UPDATE.sumOf { occurrences(body, it) },
        )
        assertFalse(
            "`updateCourse` 体内不许出现「自己按组去读一回兄弟行来判同值」的旁路（`getByGroupKey` 之类）。" +
                "要判组那一支动没动，走第 ③ 层第一枚那把签名的改法，别在 VM 里另起一趟读",
            body.contains("getByGroupKey"),
        )
    }

    // ─────────────── ④ 撤销入口册子：两枚，都挂在删除之后的提示条上 ───────────────

    @Test
    fun `撤销入口全仓两枚 都挂在删除之后的提示条上`() {
        val byFile = readAllMainSources().mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        assertEquals(
            "`viewModel.undo()` 的调用点全仓恰好一处（首页长按菜单删除那条链的提示条）。\n" +
                "复算：grep -rn \"viewModel.undo()\\|undoDeleteCourse()\" app/src/main --include='*.kt'\n" +
                "多一处 = 又多一个能 pop 栈顶的入口，「编辑条目到底什么时候捞得着」这笔账要重算：" +
                filesHint(byFile, "viewModel.undo()") + filesHint(byFile, "viewModel.undoDeleteCourse()"),
            listOf(HOME_SCREEN_NAME),
            byFile.filter { occurrences(it.value, "viewModel.undo()") > 0 }.keys.toList(),
        )
        assertEquals(
            "`viewModel.undoDeleteCourse()` 的调用点全仓恰好一处（课表管理页「删除整门课」那条提示条）",
            listOf(COURSE_MANAGEMENT_NAME),
            byFile.filter { occurrences(it.value, "viewModel.undoDeleteCourse()") > 0 }.keys.toList(),
        )
        assertEquals(
            "两枚撤销入口都必须在 `if (result == SnackbarResult.ActionPerformed)` 里面（合计两枚，" +
                "每页一枚）：栈顶这一捞**只发生在有人点了提示条上那颗「撤销」之后**，" +
                "而这两颗按钮都长在删除之后 —— 这就是 §9.5② 那句按字面走不通的根据",
            2,
            occurrences(byFile.getValue(HOME_SCREEN_NAME), ACTION_PERFORMED) +
                occurrences(byFile.getValue(COURSE_MANAGEMENT_NAME), ACTION_PERFORMED),
        )
        assertEquals(
            "两枚提示条都必须是 `SnackbarDuration.Long`（合计两枚）：那扇窗是「编辑条目压在删除条目之上」" +
                "唯一排得出来的地方，本卡对可达性的判断全靠它。改成别的时长（尤其 `Indefinite`）" +
                "就要回来重判第 ⑤ 层那笔净效果账",
            2,
            occurrences(byFile.getValue(HOME_SCREEN_NAME), SNACKBAR_LONG) +
                occurrences(byFile.getValue(COURSE_MANAGEMENT_NAME), SNACKBAR_LONG),
        )
        assertEquals(
            "首页那颗「撤销」仍由删除结果闸着（`actionLabel = \"撤销\".takeIf { deleted }` 恰好一处）",
            1,
            occurrences(byFile.getValue(HOME_SCREEN_NAME), HOME_GATED_LABEL),
        )
    }

    @Test
    fun `管理页那枚无条件撤销按钮今天只登记不修 归T127`() {
        val code = blankCommentsKeepingLiterals(readMainSource(COURSE_MANAGEMENT_SCREEN))

        assertEquals(
            "`actionLabel = \"撤销\",` 只许出现在管理页这一处，且**不带任何闸**（无条件）。" +
                "它是 T127 的靶子，本卡按红线一字未动。它一旦改成 `takeIf { … }`，这一枚会红 —— " +
                "那是好事，回来把第 ④ 层那笔「什么时候捞得着栈顶」的账一起重钉",
            1,
            occurrences(code, UNGATED_LABEL),
        )
        assertEquals(
            "`viewModel.deleteCourseGroup(target.fragments)` 的返回值今天被**丢掉**（前面没有 `val `）。\n" +
                "复算：grep -n \"deleteCourseGroup(target.fragments)\" app/src/main/java/com/buaa/schedule/ui/course/CourseManagementScreen.kt\n" +
                "本卡不动它（红线：那是 T127）。这一枚红 = 有人开始读那枚 Boolean 了，" +
                "届时「删没删到」与「那颗按钮该不该存在」就成了对，可达性结论要重算",
            1,
            occurrences(code, DISCARDED_GROUP_DELETE),
        )
        assertFalse(
            "确认它今天仍然没被赋值给任何变量（本卡没顺手修）：不许出现 `val deleted = viewModel.deleteCourseGroup`",
            code.contains("val deleted = viewModel.deleteCourseGroup"),
        )
    }

    // ─────────────── ⑤ 栈语义，与「捞到一枚同值 Update 条目」的净效果 ───────────────

    @Test
    fun `栈是全局LIFO 捞到一枚同值Update条目的净效果是零`() {
        val undoCode = blankCommentsKeepingLiterals(readMainSource(UNDO_MANAGER))
        val repoCode = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_REPOSITORY))

        // 全局栈三件套：一枚都不许漂
        assertEquals(
            "撤销栈是全局 `ArrayDeque`，不是单槽：`private val stack = ArrayDeque<UndoEntry>()`",
            1,
            occurrences(undoCode, STACK_FIELD),
        )
        assertEquals(
            "`CAPACITY = 10` 必须还在。同值条目要真挤掉有用条目，得在一枚 `SnackbarDuration.Long` 的窗里" +
                "塞进 10 枚以上 —— 本卡据此判「栈位驱逐」不是这条链的落点（值不值另说，先钉住这个读数）",
            1,
            occurrences(undoCode, CAPACITY),
        )
        assertEquals(
            "驱逐语句 `while (stack.size > CAPACITY) stack.removeFirst()` 必须还在（挤的是**栈底**那条最旧的）",
            1,
            occurrences(undoCode, EVICT),
        )
        assertEquals(
            "`pop()` 仍捞**栈顶**（`removeLastOrNull`）：这就是「编辑条目压在删除条目之上时，删除那笔被它盖住」" +
                "的机制本体。改成按身份捞是 T123 的事，本卡按红线没动签名",
            1,
            occurrences(undoCode, POP_TOP),
        )
        assertEquals(
            "`pushUpdate(...)` 落库的标签必须仍是「编辑课程」：§9.5② 那句「屏幕念『已撤销：编辑课程』」" +
                "念的就是它。标签改了要回来重钉第 ④⑤ 两层的措辞账",
            1,
            occurrences(undoCode, UPDATE_LABEL),
        )

        // 净效果为零的三块结构：同值 + afterId == before.id + removed 空 ⇒ 只剩一次原地回写
        assertEquals(
            "① `afterId == before.id` 时不许删任何行：`if (action.afterId != null && action.afterId != action.before.id) {` " +
                "就是这一支的闸门",
            1,
            occurrences(repoCode, REPO_AFTER_ID_GUARD),
        )
        assertEquals(
            "② 主行只是被 `action.before` 原地回写：同值条目回写的是同一份内容 —— 「纹丝不动」的出处就在这一句",
            1,
            occurrences(repoCode, REPO_WRITE_BACK_BEFORE),
        )
        assertEquals(
            "③ `insertWithReminders(action.removed, action.reminders)` 是唯一的补行口子：`removed` 空 ⇒ 一趟都不补。" +
                "①②③ 合起来 = 一枚同值 Update 条目的净效果为零，而用户已经点掉了一次唯一的那颗「撤销」",
            1,
            occurrences(repoCode, REPO_INSERT_REMOVED),
        )
        assertEquals(
            "`fun undo() {` 仍无参（本卡按红线没动签名），全仓恰好一处定义",
            1,
            occurrences(blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL)), VM_UNDO_SIGNATURE),
        )
    }

    // ---- 源码核对小工具（抄 CourseDeletionWiringGuardTest，同一族口径）----

    /** `ScheduleViewModel.updateCourse` 的函数体（抹注释后的文本，体内偏移配 [bodyBase] 换回全文偏移） */
    private fun updateCourseBody(): String =
        functionBody(
            blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL)),
            VM_UPDATE_SIGNATURE,
            "ScheduleViewModel.updateCourse",
        ).body

    /** [updateCourseBody] 那一段在整份文件里的起始偏移 */
    private fun bodyBase(raw: String): Int =
        functionBody(
            blankCommentsKeepingLiterals(raw),
            VM_UPDATE_SIGNATURE,
            "ScheduleViewModel.updateCourse",
        ).openBrace + 1

    /** 一枚 `{`…`}` 的体：开括号位、闭括号位、中间那段 */
    private class Extracted(val body: String, val openBrace: Int, val closeBrace: Int)

    /** 从 [signature] 起的那枚函数：取签名之后第一个 `{` 到与它配平的那个 `}` 之间那一段 */
    private fun functionBody(source: String, signature: String, who: String): Extracted {
        val hits = indexOfAll(source, signature)
        assertEquals("签名 `$signature` 在 $who 里应当恰好一处，实际 ${hits.size} 处", 1, hits.size)
        val open = source.indexOf('{', hits.first())
        check(open > 0) { "$who 的签名之后找不到 `{`：函数体换了写法，本守卫要跟着改" }
        return braceBodyFrom(source, open)
    }

    /** 从已知开括号位 [open] 起按花括号配平（跳过字符串字面量）取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): Extracted {
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
                    // 字符字面量整个跳过去：`'{'` / `'}'` 混进配平里就是假红/假绿两不沾的第三态
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

    /** 那一处落在原文的第几行（失败消息要把人带到那一处，只报个数等于没有守卫） */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

    private fun filesHint(byFile: Map<String, String>, needle: String): String =
        byFile.filter { occurrences(it.value, needle) > 0 }
            .entries
            .joinToString(separator = "") { "  ${it.key}: ${occurrences(it.value, needle)} 处\n" }

    /** 全仓 main 源码：文件名 → 内容（扫全部是为了钉「不许长出第三处入口」） */
    private fun readAllMainSources(): Map<String, String> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到 $root：包结构挪过家，本守卫要跟着改", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        val out = linkedMapOf<String, String>()
        files.forEach {
            check(!out.containsKey(it.name)) { "main 源码里有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = it.readText()
        }
        assertEquals("扫 main 源码的文件数与建出来的册子条目数不等", files.size, out.size)
        assertTrue("扫 main 源码扫出 0 份文件：$root", out.isNotEmpty())
        return out
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

    private fun readMainSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
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
     *
     * 本文件尤其需要这一步：段首 KDoc 与各条失败消息里**抄了这些字面量本身**
     * （`viewModel.undo()`、`actionLabel = "撤销".takeIf { deleted }`、`CAPACITY = 10`、
     * `if (original != null) {` 都在账里）。不抹注释的话第 ③④ 层那几枚册子会数出凭空的第二处、
     * 第三处 —— 那正是「枚数对但落点错」的反面：枚数本身就是假的。
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
        // 文件
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val SCHEDULE_REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val UNDO_MANAGER = "com/buaa/schedule/data/undo/UndoManager.kt"
        const val COURSE_MANAGEMENT_SCREEN = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val SCHEDULE_VIEW_MODEL_NAME = "ScheduleViewModel.kt"
        const val HOME_SCREEN_NAME = "HomeScreen.kt"
        const val COURSE_MANAGEMENT_NAME = "CourseManagementScreen.kt"

        // ① 压栈点
        const val VM_UPDATE_SIGNATURE =
            "suspend fun updateCourse(course: Course, options: CourseSaveOptions = CourseSaveOptions()): Long? ="
        const val READ_ORIGINAL = "val original = repository.getCourseById(course.id)"
        const val UNCONDITIONAL_GATE = "if (original != null) {"
        const val PUSH_UPDATE = "UndoManager.pushUpdate("

        // ② 三条真凭据
        const val PARTIAL_WEEKS_WRITE = "repository.updateCoursePartialWeeks(original, course)"
        const val SNAPSHOT_REMOVED = "removed = edit.removed,"
        const val SNAPSHOT_REMINDERS = "reminders = edit.reminders,"
        const val SNAPSHOT_AFTER_ID = "afterId = edit.savedId,"
        const val GROUP_BRANCH = "if (options.applyToGroup && course.sourceGroupKey != null) {"
        const val GROUP_WRITE = "repository.updateCourseGroupAppearance(course)"

        // ③ 组写不报结论 + VM 没有权威组视图
        const val GROUP_SIGNATURE = "suspend fun updateCourseGroupAppearance(course: Course) {"
        const val GROUP_FOREACH = "courseDao.getByGroupKey(groupKey).forEach"
        const val GROUP_MANUAL_OVERRIDE = "isManualOverride = true,"
        const val UPDATE_OVERWRITE_WRITE = "repository.updateCourse(course)?"

        /** `updateCourse` 体内那四趟仓储层调用（第 ③ 层拿它钉「没有第五趟读数」） */
        val REPO_CALLS_IN_UPDATE = listOf(
            READ_ORIGINAL,
            PARTIAL_WEEKS_WRITE,
            UPDATE_OVERWRITE_WRITE,
            GROUP_WRITE,
        )
        const val STATE_USES_VISIBLE = "courses = visibleCourses,"
        const val VISIBLE_FILTERED = "val visibleCourses = CourseFilter.visibleIn(courses, semester)"

        // ④ 撤销入口册子
        const val ACTION_PERFORMED = "SnackbarResult.ActionPerformed"
        const val SNACKBAR_LONG = "duration = SnackbarDuration.Long,"
        const val HOME_GATED_LABEL = "actionLabel = \"撤销\".takeIf { deleted }"
        const val UNGATED_LABEL = "actionLabel = \"撤销\","
        const val DISCARDED_GROUP_DELETE = "viewModel.deleteCourseGroup(target.fragments)"

        // ⑤ 栈语义与 Update 条目的落库
        const val STACK_FIELD = "private val stack = ArrayDeque<UndoEntry>()"
        const val CAPACITY = "private const val CAPACITY = 10"
        const val EVICT = "while (stack.size > CAPACITY) stack.removeFirst()"
        const val POP_TOP = "fun pop(): UndoEntry? = stack.removeLastOrNull()"
        const val UPDATE_LABEL = "\"编辑课程\")"
        const val REPO_AFTER_ID_GUARD = "if (action.afterId != null && action.afterId != action.before.id) {"
        const val REPO_WRITE_BACK_BEFORE =
            "CourseConstraints.normalize(action.before)?.let { courseDao.update(it.toEntity()) }"
        const val REPO_INSERT_REMOVED = "insertWithReminders(action.removed, action.reminders)"
        const val VM_UNDO_SIGNATURE = "fun undo() {"
    }
}
