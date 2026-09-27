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
 * ## 现状（T128 落地，扳机已响；上面那一段"为什么判 deferred"一字未抹，它量的是 `d22a3f8` 之前的盘面）
 *
 * T128 把前置做了：`updateCourseGroupAppearance` 现在返回 [com.buaa.schedule.data.repository.GroupAppearanceEdit]
 * （逐字段已经等于目标态的行一行都不写，改掉的行连同**改前**值一起交回），第 ③ 层那枚签名断言
 * **当场红** —— 那正是它设计出来的用途。按红线没有删那一格，而是把它的极性整个翻过来钉**药**
 * （同 T127 改判第 ⑤ 枚的手法）：签名必须**带着**返回类型、组那一支必须**排在压栈之前**、
 * 函数体内那枚无条件 `isManualOverride = true,` 必须是 0 处（判据改立在
 * `CourseGroupAppearancePolicy.kt` 里）。压栈闸门那一侧由新的第 ⑥ 层钉：
 * 判据本体只有 `updateUndoWorthRecording` 那一份，调用点只读它、不再自己比一遍。
 *
 * 四条机制逐条对账（上面那句「这一判今天在压栈点算不出来」从今天起不再成立）：
 * ① 保证同值那条路径走的确实是组这一支 ⇒ 现在它自己报"整组一行都没改"；
 * ② 组写返回 `Unit` ⇒ 已改；③ 压栈排在组写**之前** ⇒ 顺序翻过来了（第 ② 层新增那一格钉住）；
 * ④ VM 手里那份课程表是过滤过的、不能当组视图用 ⇒ 仍然成立，所以这一判**不是** VM 自己读一组算的，
 * 而是组写在它自己的事务里、按 `getByGroupKey` 读回来的那一组里算的（第 ③ 层那两枚"不许旁路"格仍在）。
 *
 * ## 本守卫防的是什么
 *
 * 防下一个人拿 §9.5② 那句话当尺子，直接在 `if (original != null)` 上补一枚 `&& original != course`
 * 就把卡收了。那一改会同时踩掉三样真东西，第 ② 层逐样钉着：部分周次拆行顺手清掉的兄弟片段
 * （R5 F-35）、`savedId != course.id` 的另发行、组那一支写在兄弟行上的外观。
 * 真要改，先让第 ③ 层那枚签名断言红掉（组写开始返回结论），再回来连着那三条一起重判。
 * **现状（T128）**：那一改落地了，走的正是这条路 —— 组写先返回结论（扳机红在第 ③ 层第一枚），
 * 然后"要不要压"四把维度一起收进一枚纯判据内核 `updateUndoWorthRecording`，
 * 三条真凭据一条没少（第 ② 层那六枚断言原样在，另加一枚"组写必须排在压栈之前"的新次序钉）。
 * 本守卫今天防的从"提前收"变成"收了一半又退回去"：朝宽（组那一支不再报结论 / 无条件字面量回来）
 * 与朝窄（少算一把维度 / 压栈点自己另起一趟读组）各扭一次，两次都必须红。
 *
 * ## 第 ⑤ 枚那一格在 T127 改过一次判（改前钉的是病、改后钉的是药）
 *
 * 本文件落到 master 时，第五枚 `@Test` 的方法名是
 * `管理页那枚无条件撤销按钮今天只登记不修 归T127`，它钉的是**病还在**：管理页那颗「撤销」
 * `actionLabel = "撤销",` 无条件长在那儿，而 `viewModel.deleteCourseGroup(…)` 返回的 Boolean 被丢掉。
 * T127 把那一支改成"读结论 ⇒ 文案与那颗按钮同读一枚 `deleted`"（同仓 `HomeScreen` 已有的形状，
 * T121 收的），于是那一枚**当场红**（红是设计好的方向，不是事故）。改判时按红线**没有删那一格**，
 * 而是把它的极性整个翻过来钉**药**：`UNGATED_LABEL` 从「必须恰好一处」改成「必须 0 处」，
 * 新增「`takeIf` 那一闸必须恰好一处、且闸的是文案那一句读的同一枚变量」。
 * 两侧各留一道口子：**朝宽扭**（有人把那颗按钮改回无条件 ⇒ 0 处那一断言红）；
 * **朝窄扭**（有人把那颗按钮整颗删掉、或改闸到另一枚布尔上 ⇒ 同源与次序那几断言红）。
 * 更细的落点账（VM 两枚早返回、仓储层空快照、调用点册子、两枚删除入口的对照表）在
 * `CourseGroupDeletionWiringGuardTest`，本文件只留"撤销条目的可达性"这一族自己的账。
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
            "压栈的闸门必须是 `if (original != null && updateUndoWorthRecording(` 那一枚（恰好一处）：" +
                "`original` 是写库**之前**从库里读回来的那一行，它是「这次保存有没有对象可撤销」的证据，" +
                "而「到底动没动东西」自 T128 起交回给判据内核去算（第 ⑥ 层钉的那一份）。" +
                "**朝宽扭这里红**：谁把闸门改回只闸 `original != null`（无条件压栈），§9.5② 那枚 " +
                "no-op 条目就又回栈里躺着（改前那一句 `if (original != null) {` 今天必须 0 处，见下一格）\n" +
                "复算：grep -n \"if (original != null\" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt\n" +
                "（L" + lineOf(raw, base) + " 起那一段）",
            1,
            occurrences(body, ADMISSION_GATE),
        )
        assertEquals(
            "`if (original != null) {` 那一枚**无条件**闸门今天必须是 0 处（T128 之前的现状形状）。" +
                "**朝宽扭这里红**：把判据那一半摘掉、只留「原文读没读回来」，就是把它改回来了",
            0,
            occurrences(body, UNCONDITIONAL_GATE),
        )
        // 反向钉：同值那一判只许长在内核里一份，不许在 VM 里就地比一遍
        assertFalse(
            "`updateCourse` 体内不许出现「调用点自己比一次主行」（`original != course` / `original == course`）。" +
                "同值那一判的四把维度（主行 before-after / 另发行 / 被清掉的兄弟片段 / 组交回的行）" +
                "只有 " + ADMISSION_FUNCTION + " 那一份本体 —— 在这里再比一遍就是第二把尺子，" +
                "而 T122 驳回过的恰恰是「只判主行」那一把（三条真凭据在主行之外）",
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
                "它就是 T122 判 deferred 的那枚主角，自 T128 起它**交回自己的结论**（第 ③ 层）",
            1,
            occurrences(body, GROUP_WRITE),
        )
        assertEquals(
            "那一处必须是**被接收**的（`val group = if (...) {` 起头、`else null` 收尾）：" +
                "结论没人接 = 第 ⑥ 层那四把维度少一把。" +
                "复算：grep -n \"val group = if (options.applyToGroup\" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt",
            1,
            occurrences(body, GROUP_RECEIVE),
        )
        assertTrue(
            "次序自 T128 起翻过来了：**组写 → 才压栈**。这一条仍是结构证明，不是措辞 —— " +
                "判据要读组那一支交回的行，压栈就只能在它后面（改前是「压栈 → 才写组」，" +
                "那笔顺序账正是 T122 四条机制里的第 ③ 条）：" +
                "\n  组写 L" + lineOf(vmRaw, base + body.indexOf(GROUP_WRITE)) +
                " 早于 压栈 L" + lineOf(vmRaw, base + push) +
                "\n复算：grep -n \"pushUpdate(\\|updateCourseGroupAppearance(course)\" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt",
            body.indexOf(GROUP_WRITE) in 0 until push,
        )
    }

    // ─────────────── ③ 前置已落地：组写交出它自己的结论（改前钉的是"报不出结论"） ───────────────

    /**
     * **T128 改判的一整层**（改前钉前置未落地、改后钉药；同 T127 改判第 ⑤ 枚的手法）。
     *
     * 改前第一枚断言钉的是 `suspend fun updateCourseGroupAppearance(course: Course) {` 那一枚
     * **没有返回类型**的签名，并在失败消息里明写「这一枚一旦变红，说明前置条件落地了」。
     * T128 让它红了 —— 按红线没有删那一格，而是把极性翻过来正向钉：签名必须**带着**
     * [com.buaa.schedule.data.repository.GroupAppearanceEdit]，那一枚无返回类型的旧签名必须 0 处。
     * 两侧各留一道口子：**朝宽扭**（把返回类型摘掉、或另起一枚会报结论的同名重载）红在头两枚；
     * **朝窄扭**（组函数里不再调判据、或把 `isManualOverride = true,` 那枚无条件字面量搬回来）
     * 红在接收结论那一枚与第 ⑥ 层。
     */
    @Test
    fun `组那一支交出它自己的结论 同值判据因此不再需要 VM 自己读一组`() {
        val repoRaw = readMainSource(SCHEDULE_REPOSITORY)
        val repo = blankCommentsKeepingLiterals(repoRaw)
        val group = functionBody(repo, GROUP_SIGNATURE, "ScheduleRepository.updateCourseGroupAppearance")

        assertEquals(
            "`updateCourseGroupAppearance` 的签名必须**带着返回类型** " +
                "[GroupAppearanceEdit]（恰好一处）。\n" +
                "复算：grep -n \"suspend fun updateCourseGroupAppearance\" app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt\n" +
                "这一枚是 T122 那根扳机的正面形状：组写写完一组行以后要把「改了谁、动没动」交回去。" +
                "**朝宽扭这里红**：把返回类型摘回 `Unit`，压栈点就又看不见兄弟行那一维，" +
                "§9.5② 只能退回无条件压栈",
            1,
            occurrences(repo, GROUP_SIGNATURE),
        )
        assertEquals(
            "改前那一枚**没有返回类型**的旧签名必须 0 处（不许与上面那一枚并存 = 同名重载）。\n" +
                "复算：grep -c \"suspend fun updateCourseGroupAppearance(course: Course) {\" " +
                "app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt",
            0,
            occurrences(repo, GROUP_SIGNATURE_UNIT),
        )
        assertEquals(
            "也不许藏着一枚「换了名、会报结论」的组写绕开上面那枚签名" +
                "（同名重载、或另起一枚 `updateCourseGroupAppearanceXxx`）：签名族合计只能一枚",
            1,
            occurrences(repo, "suspend fun updateCourseGroupAppearance"),
        )
        assertEquals(
            "它按 `sourceGroupKey` 逐行改写整组：`courseDao.getByGroupKey(groupKey).forEach` 必须还在。" +
                "被改的行里**包含主行自己**，所以「主行同值」连主行都保不住 —— 这一判的读数只能来自这一趟",
            1,
            occurrences(group.body, GROUP_FOREACH),
        )
        assertEquals(
            "`isManualOverride = true,` 那枚**无条件**字面量在组函数体内今天必须 0 处（改前是 1 处，" +
                "L" + lineOf(repoRaw, group.openBrace) + " 起那一段）。" +
                "**朝宽扭这里红**：把它搬回来 = 反复点色板又开始给整组踢出教务匹配，" +
                "而且空操作又被判成「动过东西」。判据那一枚（跟着真改动走、旗标仍粘）在第 ⑥ 层钉",
            0,
            occurrences(group.body, GROUP_MANUAL_OVERRIDE),
        )
        assertEquals(
            "组函数体必须**接住判据的结论**：判成不必写的那些行靠 `?: return@forEach` 跳过，" +
                "改掉了的那些行的**改前**值靠 `beforeRows += domain` 攒进结论。" +
                "两枚各一处，少一枚就是「报得出行数、报不出改了谁」或者反过来：" +
                "\n  " + GROUP_RECEIVE_ROW + " 实到 " + occurrences(group.body, GROUP_RECEIVE_ROW) + " 处" +
                " / " + GROUP_ACCUMULATE + " 实到 " + occurrences(group.body, GROUP_ACCUMULATE) + " 处\n" +
                "复算：awk 'NR>=245 && NR<=275' app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt",
            listOf(1, 1, 1),
            listOf(
                occurrences(group.body, GROUP_RECEIVE_ROW),
                occurrences(group.body, GROUP_ACCUMULATE),
                occurrences(group.body, GROUP_EMIT),
            ),
        )
        assertEquals(
            "仓储层仍然没有、也不该有供调用点预判的按组读数口（`getCoursesByGroupKey` 之类 = 0 枚）。\n" +
                "复算：grep -rn \"suspend fun get.*GroupKey\" app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt\n" +
                "T128 走的是「组写在它自己的事务里报结论」，不是「VM 先读一组再判」—— " +
                "后者要另算一笔「读回来的组是不是写库前那一刻的组」的时序账",
            0,
            occurrences(repo, "suspend fun getCoursesByGroupKey"),
        )

        // VM 手里那份课程表是过滤过的，不能当组视图用（这一条今天仍然成立，所以判据不许搬到 VM 去）
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
            "`updateCourse` 体内那四趟写/读各恰好一处（合计四趟）：读原文 / 拆行写库 / 覆盖写库 / " +
                "组那一支外观写。本格按**逐字实参**算账，不数整份文件里的 `repository.`（那一把尺子在 " +
                "ScheduleViewModel.kt 上有 50+ 行，`saveCourse` 那枚函数里还另有一趟 " +
                "`repository.saveCourse(course)`，都跟这条链无关）。\n" +
                "复算：awk 'NR>=517 && NR<=541' app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt " +
                "| grep -c \"repository\\.\"   ⇒ 4\n" +
                "updateCourse 体内长出第五趟 = 有人新加了一趟读数来判同值 —— T128 明确不走这条路（" +
                "「读回来的那一组是不是写库前那一刻的那一组」这笔时序账得先钉清楚）；" +
                "少一趟就是这条链换了入口，第 ②③ 层要重判：" +
                "\n" + REPO_CALLS_IN_UPDATE.joinToString("\n") { "  ${it}: ${occurrences(body, it)} 趟" },
            4,
            REPO_CALLS_IN_UPDATE.sumOf { occurrences(body, it) },
        )
        assertFalse(
            "`updateCourse` 体内不许出现「自己按组去读一回兄弟行来判同值」的旁路（`getByGroupKey` 之类）。" +
                "要判组那一支动没动，读它自己交回的那张表（第 ⑥ 层），别在 VM 里另起一趟读",
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

    /**
     * **T127 改判过的一格**（改前钉病、改后钉药，见类头那一节）。
     *
     * 改前它叫 `管理页那枚无条件撤销按钮今天只登记不修 归T127`，三枚断言全是**正向钉病灶**：
     * `occurrences(code, "actionLabel = \"撤销\",") == 1`、
     * `occurrences(code, "viewModel.deleteCourseGroup(target.fragments)") == 1`（那句没有 `val ` 接收 ⇒ 结论被丢）、
     * `assertFalse(code.contains("val deleted = viewModel.deleteCourseGroup"))`。
     * 那三枚能抓住的是"有人抢先把 T127 的活提前做了一半"（编排侧 M-A 臂就是这么叫红它的），
     * 却**抓住病灶、抓不住修法**：真修的时候把 `takeIf { }` 里的变量名换成别的、或只闸文案不闸按钮，
     * 它一个字都不吭。
     *
     * 改后钉的是药，两侧各有格子：**朝宽**（把那颗按钮改回无条件 `actionLabel = "撤销",`）红在
     * [UNGATED_LABEL] 那枚 0 处断言与那枚"整句被丢掉"的反向钉；**朝窄**（把那颗按钮整颗删了 /
     * 换一枚布尔去闸 / 只留成功那支文案）红在 [GATED_LABEL] 的 1 处、闸变量同源、以及两支文案那三枚。
     */
    @Test
    fun `管理页那一支T127落地 撤销按钮与提示文案同读一枚删除结论`() {
        val raw = readMainSource(COURSE_MANAGEMENT_SCREEN)
        val code = blankCommentsKeepingLiterals(raw)

        // ── 反向钉：病（无条件 + 结论被丢掉）不许回来 ──
        assertEquals(
            "`actionLabel = \"撤销\",` 那枚**无条件**的撤销按钮今天必须是 0 处（T127 已把它改成按删除结论闸）。\n" +
                "复算：grep -n 'actionLabel = \"撤销\",' app/src/main/java/com/buaa/schedule/ui/course/CourseManagementScreen.kt\n" +
                "**朝宽扭这里红**：谁把它改回无条件，用户就又会在一次什么都没删掉的删除之后看见一颗「撤销」，" +
                "点它捞的是栈里上一枚别的条目（`undo()` 无参、`pop()` 捞栈顶，见第 ⑤ 层）",
            0,
            occurrences(code, UNGATED_LABEL),
        )
        assertFalse(
            "`viewModel.deleteCourseGroup(target.fragments)` 不许再单独成一句（返回值被丢掉）——" +
                "那正是本卡修的病灶本体：删没删到只有 VM 知道，UI 不读就无从分辨。\n" +
                "复算：grep -n 'viewModel.deleteCourseGroup' app/src/main/java/com/buaa/schedule/ui/course/CourseManagementScreen.kt\n" +
                "⚠️ 这一格不能按旧写法拿子串计数：`viewModel.deleteCourseGroup(target.fragments)` 是" +
                "`val deleted = viewModel.deleteCourseGroup(target.fragments)` 的**子串**，改前改后都命中一次，" +
                "数出来永远对 ⇒ 套套逻辑（#118 那一族）。这里改成整行比对",
            code.lineSequence().any { it.trim() == DISCARDED_GROUP_DELETE },
        )
        assertEquals(
            "那一支今天必须**读**这枚结论（`val deleted = viewModel.deleteCourseGroup(target.fragments)` 恰好一处）。" +
                "**朝窄扭这里红**：整句换成别的接收法（`@Suppress(\"UNUSED_VARIABLE\")`、或换成再调一趟判存在性）" +
                "都不算读了结论",
            1,
            occurrences(code, READING_GROUP_DELETE),
        )

        // ── 正向钉：药（同一枚结论同时决定文案与那颗按钮）──
        val hits = indexOfAll(code, READING_GROUP_DELETE)
        val launch = scopeLaunchBody(code, hits.first())
        val launchBase = launch.openBrace + 1
        assertEquals(
            "那颗「撤销」必须**仍在那儿**、且被删除结论闸着（`actionLabel = \"撤销\".takeIf { deleted },` 恰好一处）：" +
                "形状照同仓 `HomeScreen` 的单课删除那一支（T121 收的）。" +
                "**朝窄扭这里红**：整颗按钮被删掉（连带成功那次也撤不回）不是收窄，是把另一头的功能弄丢",
            1,
            occurrences(launch.body, GATED_LABEL),
        )
        assertTrue(
            "文案必须两支都在：成功念「已删除「…」」（这一句改前改后一字未动），" +
                "失败念「删除失败：… 还在课表里」（与 `HomeScreen` 同一句话术，本卡没造新文案）。" +
                "**朝窄扭这里红**：把 else 那一支抹掉 = 删不到时又只剩一句谎",
            launch.body.contains(GROUP_SUCCESS_COPY) && launch.body.contains(GROUP_FAILURE_COPY),
        )
        // 同源：三处闸的是不是同一枚变量，**从原文里各抓一次名字**（拿常量比常量是套套逻辑）
        val assigned = GROUP_ASSIGN_VARIABLE.find(launch.body)?.groupValues?.get(1)
        val onMessage = MESSAGE_IF_VARIABLE.find(launch.body)?.groupValues?.get(1)
        val onLabel = LABEL_TAKE_IF_VARIABLE.find(launch.body)?.groupValues?.get(1)
        assertEquals(
            "三处都得分得出闸在哪枚变量上（赋值句 / 文案那一句 / 那颗按钮），应当分出 3 处，" +
                "实际 $assigned / $onMessage / $onLabel —— 有一处抓不到就是写法换了" +
                "（三元换成 `when`、`takeIf` 换成 `if` 块…），本守卫要跟着重钉",
            3,
            listOfNotNull(assigned, onMessage, onLabel).size,
        )
        assertEquals(
            "「文案两支怎么分」与「那颗按钮给不给」必须闸在**同一枚**变量上（同源），三处读到的名字：" +
                "赋值句 `$assigned` / 文案 `$onMessage` / 按钮 `$onLabel`。" +
                "对不上就是长出了第二把尺子 —— 提示条念「删除失败」却仍给一颗「撤销」这种自相矛盾的组合" +
                "今天钉不住，明天就会漂出来（本卡的病恰恰是文案与按钮**都**无条件，同源得可笑）",
            1,
            listOfNotNull(assigned, onMessage, onLabel).distinct().size,
        )
        assertTrue(
            "三拍次序：读结论 → 才分文案 → 才决定那颗按钮（都在同一个 `scope.launch` 块里）。" +
                "次序倒了就是在读上一次删除的结果：\n" +
                "  读结论 L${lineOf(raw, launchBase + launch.body.indexOf(READING_GROUP_DELETE))} / " +
                "分文案 L${lineOf(raw, launchBase + launch.body.indexOf(GROUP_IF_DELETED))} / " +
                "闸按钮 L${lineOf(raw, launchBase + launch.body.indexOf(GATED_LABEL))}",
            launch.body.indexOf(READING_GROUP_DELETE) in 0 until launch.body.indexOf(GROUP_IF_DELETED) &&
                launch.body.indexOf(GROUP_IF_DELETED) in 0 until launch.body.indexOf(GATED_LABEL),
        )
        assertEquals(
            "点了那颗按钮才 pop 栈顶：`if (result == SnackbarResult.ActionPerformed) {` 那一闸必须还在恰好一处。" +
                "撤销**捞的是栈顶而不是这一笔**（`undo()` 无参）本卡按红线未动 —— 那是 T123 的账，" +
                "本卡只保证「删不到的那一次根本不给那颗按钮」",
            1,
            occurrences(launch.body, ACTION_PERFORMED_IF),
        )
        assertEquals(
            "提示条时长仍是 `SnackbarDuration.Long`（恰好一处）：第 ④ 层那笔「同一次会话里栈顶被别的条目压上去」" +
                "的可达性账全靠这扇窗，改成 `Indefinite` 之类要回来重判第 ④⑤ 两层",
            1,
            occurrences(launch.body, SNACKBAR_LONG),
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

    // ─────────────── ⑥ 新判据落点（T128）：同值那一判只有内核那一份，压栈点只读它 ───────────────

    /**
     * 第 ① ③ 层钉的是「闸在哪、组写报不报结论」，这一层钉的是**新长出来的那两枚判据本体**：
     * 落点（全仓 main 各一枚、不许在别的文件里再写一遍）、逐字（四把维度一把都不许多、都不许少）、
     * 次序（组写 → 判据 → 压栈），以及两枚内核都**仍是纯判据**（零 android import、零时钟读取，
     * 外部事实一律当参数递进来 —— 本仓收单硬判据，"返回布尔后调用点各自判一次"也算违反）。
     */
    @Test
    fun `同值那一判落在纯内核里 压栈点只读它一次`() {
        val byFile = readAllMainSources().mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }
        val admission = byFile.getValue(UNDO_UPDATE_ADMISSION_NAME)
        val appearance = byFile.getValue(GROUP_POLICY_NAME)

        // ── 落点册子：两枚判据本体各全仓一份 ──
        assertEquals(
            "`internal fun updateUndoWorthRecording(` 全仓 main 恰好一处，就在 UndoUpdateAdmission.kt。\n" +
                "复算：grep -rn \"internal fun updateUndoWorthRecording(\" app/src/main/java --include='*.kt'\n" +
                "**朝宽扭这里红**：谁在压栈点旁边再写一份同值比较（" +
                "「返回布尔后调用点各自判一次」那一族），这本册子就长出第二枚文件",
            listOf(UNDO_UPDATE_ADMISSION_NAME),
            byFile.filter { occurrences(it.value, ADMISSION_SIGNATURE) > 0 }.keys.toList(),
        )
        assertEquals(
            "`internal fun groupAppearanceRow(` 全仓 main 恰好一处，就在 CourseGroupAppearancePolicy.kt。\n" +
                "复算：grep -rn \"internal fun groupAppearanceRow(\" app/src/main/java --include='*.kt'\n" +
                "组函数体内那一处调用（第 ③ 层）不算这里 —— 它调判据、不复制判据",
            listOf(GROUP_POLICY_NAME),
            byFile.filter { occurrences(it.value, GROUP_ROW_SIGNATURE) > 0 }.keys.toList(),
        )

        // ── 逐字：压栈点只读一次，实参就是那五枚外部事实 ──
        val body = updateCourseBody()
        assertEquals(
            "`updateCourse` 体内读这枚判据恰好一次，且实参逐字是那五枚外部事实" +
                "（主行 before / 主行 after / 仓储层给的最终行 id / 被清掉的片段条数 / 组那一支的结论）：" +
                "\n  " + ADMISSION_CALL + " 实到 " + occurrences(body, ADMISSION_CALL) + " 处" +
                "\n复算：grep -n \"updateUndoWorthRecording\" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt",
            1,
            occurrences(body, ADMISSION_CALL),
        )
        val vmRaw = readMainSource(SCHEDULE_VIEW_MODEL)
        val updateBase = bodyBase(vmRaw)
        assertTrue(
            "三拍次序：组写 → 判据 → 才压栈（判据拿在中间的读数，压栈只在它后面）。" +
                "次序倒了就是在压一枚还没算出来的条目：" +
                "\n  组写 L" + lineOf(vmRaw, updateBase + body.indexOf(GROUP_WRITE)) +
                " / 判据 L" + lineOf(vmRaw, updateBase + body.indexOf(ADMISSION_CALL)) +
                " / 压栈 L" + lineOf(vmRaw, updateBase + body.indexOf(PUSH_UPDATE)),
            body.indexOf(GROUP_WRITE) in 0 until body.indexOf(ADMISSION_CALL) &&
                body.indexOf(ADMISSION_CALL) in 0 until body.indexOf(PUSH_UPDATE),
        )

        // ── 逐字 + 枚数：四把维度一把都不许多、都不许少 ──
        val dimensions = ADMISSION_DIMENSIONS.filter { occurrences(admission, it) != 1 }
        assertEquals(
            "判据本体那四把维度必须各出现恰好一次（多一把 = 长出第五把尺子；少一把 = 那一维又没人算了，" +
                "正是 T122 第 ② 层那三条真凭据的账）。不齐的：" +
                dimensions.joinToString { "「$it」实到 " + occurrences(admission, it) + " 处" } +
                "\n复算：awk '/^internal fun updateUndoWorthRecording/,/^}/' " +
                "app/src/main/java/com/buaa/schedule/ui/UndoUpdateAdmission.kt",
            emptyList<String>(),
            dimensions,
        )
        assertEquals(
            "组外观判据里那枚旗标必须是「跟着真改动走、且粘住」的那一句（恰好一处）。" +
                "**朝宽扭**（写回无条件 true）与**朝窄扭**（写回恒 false，等于让改颜色不再护着本地外观）" +
                "都红在这里：" +
                "\n复算：grep -n \"isManualOverride = \" app/src/main/java/com/buaa/schedule/data/repository/CourseGroupAppearancePolicy.kt",
            1,
            occurrences(appearance, APPEARANCE_FLAG_JUDGMENT),
        )
        assertEquals(
            "判据末尾那一枚「结构相等 = 不必写」的总闸必须恰好一处 —— 它才是「这一趟动没动」的唯一尺子，" +
                "上面那六维比较只是给旗标用的：" +
                "\n复算：grep -n \"takeIf { it != original }\" app/src/main/java/com/buaa/schedule/data/repository/CourseGroupAppearancePolicy.kt",
            1,
            occurrences(appearance, APPEARANCE_VERDICT),
        )

        // ── 两枚内核都仍是纯判据：零 android import、零时钟读取 ──
        for ((name, expectedImports) in listOf(
            UNDO_UPDATE_ADMISSION to UNDO_UPDATE_ADMISSION_IMPORTS,
            GROUP_POLICY to GROUP_POLICY_IMPORTS,
        )) {
            assertEquals(
                "$name 的 import 册子变了（应当只有那两枚纯类型、或更少）。" +
                    "长出 android.* / androidx.* / java.time.* / System.currentTimeMillis 之类就是判据" +
                    "又学会了读外部事实 —— 本仓口径：外部事实由调用点当参数递进来。" +
                    "\n复算：grep -n \"^import\" app/src/main/java/$name",
                expectedImports,
                Regex("(?m)^import .*$").findAll(readMainSource(name)).map { it.value }.toList(),
            )
        }
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

    /**
     * 从 [hit] 往前找包围它的那次 `scope.launch {`，再按花括号配平取出那一段体。
     *
     * 返回 [Extracted] 而不是裸串：调用点要拿 `openBrace` 把体内偏移换回全文偏移去报行号
     * （`lineOf`）。只报"次序对不对"不报"落在第几行"，下一个人就没法核这条守卫读的是哪一处。
     */
    private fun scopeLaunchBody(source: String, hit: Int): Extracted {
        val anchor = source.lastIndexOf(LAUNCH_ANCHOR, hit)
        check(anchor >= 0) {
            "那一处往前找不到包围它的 scope.launch {：这段操作换了载体（不在协程里了？），本守卫要跟着改"
        }
        val brace = anchor + LAUNCH_ANCHOR.length - 1
        check(source[brace] == '{') { "`scope.launch {` 那锚点末尾不是花括号：写法变了" }
        val extracted = braceBodyFrom(source, brace)
        check(hit in extracted.openBrace + 1..extracted.closeBrace) { "取出的 scope.launch 体不含那一处：配平跑偏了" }
        return extracted
    }

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

        // ⑥ T128：两枚新判据本体的落点
        const val UNDO_UPDATE_ADMISSION = "com/buaa/schedule/ui/UndoUpdateAdmission.kt"
        const val GROUP_POLICY = "com/buaa/schedule/data/repository/CourseGroupAppearancePolicy.kt"
        const val UNDO_UPDATE_ADMISSION_NAME = "UndoUpdateAdmission.kt"
        const val GROUP_POLICY_NAME = "CourseGroupAppearancePolicy.kt"
        const val ADMISSION_SIGNATURE = "internal fun updateUndoWorthRecording("
        const val GROUP_ROW_SIGNATURE = "internal fun groupAppearanceRow("
        val UNDO_UPDATE_ADMISSION_IMPORTS = listOf(
            "import com.buaa.schedule.data.repository.GroupAppearanceEdit",
            "import com.buaa.schedule.domain.model.Course",
        )
        val GROUP_POLICY_IMPORTS = listOf("import com.buaa.schedule.domain.model.Course")

        // ① 压栈点
        const val VM_UPDATE_SIGNATURE =
            "suspend fun updateCourse(course: Course, options: CourseSaveOptions = CourseSaveOptions()): Long? ="
        const val READ_ORIGINAL = "val original = repository.getCourseById(course.id)"
        const val UNCONDITIONAL_GATE = "if (original != null) {"
        const val PUSH_UPDATE = "UndoManager.pushUpdate("

        // ①⑥ T128 之后压栈闸门读的就是这一枚判据（逐字含那五枚外部事实）
        const val ADMISSION_GATE = "if (original != null && updateUndoWorthRecording("
        const val ADMISSION_FUNCTION = "updateUndoWorthRecording"
        const val ADMISSION_CALL =
            "updateUndoWorthRecording(original, course, edit.savedId, edit.removed.size, group)"

        /** 判据本体那四把维度（一把都不许多、都不许少；逐字取自 `UndoUpdateAdmission.kt`） */
        val ADMISSION_DIMENSIONS = listOf(
            "savedId != original.id ||",
            "removedCount > 0 ||",
            "original != after ||",
            "(group != null && group.beforeRows.isNotEmpty())",
        )

        // ② 三条真凭据
        const val PARTIAL_WEEKS_WRITE = "repository.updateCoursePartialWeeks(original, course)"
        const val SNAPSHOT_REMOVED = "removed = edit.removed,"
        const val SNAPSHOT_REMINDERS = "reminders = edit.reminders,"
        const val SNAPSHOT_AFTER_ID = "afterId = edit.savedId,"
        const val GROUP_BRANCH = "if (options.applyToGroup && course.sourceGroupKey != null) {"
        const val GROUP_WRITE = "repository.updateCourseGroupAppearance(course)"

        /** ② T128 起那一枚组写是被**接收**的（结论要进判据），不再是一句裸调用 */
        const val GROUP_RECEIVE = "val group = $GROUP_BRANCH"

        // ③ 组写交出自己的结论（改前那一层钉的是"报不出结论"）
        const val GROUP_SIGNATURE =
            "suspend fun updateCourseGroupAppearance(course: Course): GroupAppearanceEdit {"

        /** 改前的旧签名（没有返回类型那一枚）：今天必须 0 处，不许与新签名并存成同名重载 */
        const val GROUP_SIGNATURE_UNIT = "suspend fun updateCourseGroupAppearance(course: Course) {"
        const val GROUP_FOREACH = "courseDao.getByGroupKey(groupKey).forEach"
        const val GROUP_MANUAL_OVERRIDE = "isManualOverride = true,"
        const val UPDATE_OVERWRITE_WRITE = "repository.updateCourse(course)?"

        /** ③ 组函数体接住判据的三枚落点：跳过不必写的行 / 攒下改前值 / 交回结论 */
        const val GROUP_RECEIVE_ROW = "val written = groupAppearanceRow(domain, course, customColor) ?: return@forEach"
        const val GROUP_ACCUMULATE = "beforeRows += domain"
        const val GROUP_EMIT = "GroupAppearanceEdit(beforeRows.toList())"

        /** ⑥ 组外观判据里那枚旗标与那枚总闸（逐字） */
        const val APPEARANCE_FLAG_JUDGMENT = "isManualOverride = original.isManualOverride || appearanceChanged"
        const val APPEARANCE_VERDICT = "takeIf { it != original }"

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

        // ④ 管理页那一支（T127 改判后钉的是药）
        const val LAUNCH_ANCHOR = "scope.launch {"
        const val READING_GROUP_DELETE = "val deleted = viewModel.deleteCourseGroup(target.fragments)"
        const val GROUP_IF_DELETED = "message = if (deleted) \"已删除「\${target.displayName}」\""
        const val GROUP_SUCCESS_COPY = "\"已删除「\${target.displayName}」\""
        const val GROUP_FAILURE_COPY = "\"删除失败：\${target.displayName} 还在课表里\""
        const val GATED_LABEL = "actionLabel = \"撤销\".takeIf { deleted },"
        const val ACTION_PERFORMED_IF = "if (result == SnackbarResult.ActionPerformed) {"

        /**
         * 三处闸的变量名**从原文里各抓一次**（不是拿常量比常量 —— 那是 #118 那种套套逻辑）。
         * 每一枚都只对自己那一处负责：赋值句 / 文案那一句 / 那颗按钮。
         */
        val GROUP_ASSIGN_VARIABLE = Regex("""val (\w+) = viewModel\.deleteCourseGroup\(""")
        val MESSAGE_IF_VARIABLE = Regex("""message = if \((\w+)\) "已删除「""")
        val LABEL_TAKE_IF_VARIABLE = Regex("""actionLabel = "撤销"\.takeIf \{ (\w+) \},""")

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
