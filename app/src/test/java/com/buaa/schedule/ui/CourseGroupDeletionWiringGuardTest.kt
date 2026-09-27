package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T127：接线守卫 —— 钉住「课表管理页删一整组」那一支**读了删除结论**，
 * 并且"这一趟删没删到"这一枚结论在 UI 上**只有一把尺子**。
 *
 * ## 病（用户看得见的那一句）
 *
 * `CourseManagementScreen` 的确认框 `confirmButton` 里，`viewModel.deleteCourseGroup(target.fragments)`
 * 返回的 Boolean 被丢掉，紧接着一句提示条**无条件**念「已删除「…」」并**无条件**挂一颗「撤销」。
 * 而 [com.buaa.schedule.ui.ScheduleViewModel.deleteCourseGroup] 是有结论的，两枚早返回都回 `false`：
 * - `if (courses.isEmpty()) return false` —— 递进来的片段表是空的；
 * - `if (snapshot.courses.isEmpty()) return@suspendCatching false` —— 库里那一组行**一行都没捞到**
 *   （`repository.deleteCourseGroup` 的 `rows.isEmpty()` 早退交回空快照，T121 的
 *   `CourseDeletionPolicyTest` 记的就是这一族形状）。
 *
 * 删不到时屏幕上仍然念「已删除」，那颗「撤销」也照样在；点它 → `undoDeleteCourse()` → `undo()` →
 * `UndoManager.pop()` 捞**栈顶** ⇒ 撤掉的是栈里上一枚**别的**条目（先删 A 再删 B、然后点 A 那条
 * 提示条的「撤销」，回来的是 B）。用户看到的是一句谎话加一次不相干的回滚。
 *
 * ## 药（本卡落的形状，一字照同仓 `HomeScreen` 单课删除那一支对齐 · T121 收的）
 *
 * ```
 * val deleted = viewModel.deleteCourseGroup(target.fragments)
 * message = if (deleted) "已删除「${target.displayName}」" else "删除失败：${target.displayName} 还在课表里",
 * actionLabel = "撤销".takeIf { deleted },
 * ```
 *
 * 提示条文案与那颗按钮**同读一枚** `deleted`，且两处都在同一次 `scope.launch` 里 —— 于是
 * "念了删除失败却还给一颗撤销"这种自相矛盾的组合在形状上就拼不出来。
 *
 * ## 本守卫防的是什么（三档里的"接线"那一档；判据本体不在本卡）
 *
 * 判据本体（"什么算删到了"）今天**已经在 VM/仓储层**：`snapshot.courses.isEmpty()` 读的就是
 * 事务内读回来的那些行。卡面红线明写"本卡缺的只是 UI 没读 ⇒ 别顺手再补一层"，所以本卡**不抽新内核**：
 * 卡面白名单只许动 `CourseManagementScreen.kt` 与测试源码，往 UI 文件里塞一枚 `internal` 判据函数
 * 既要插行（顶穿全仓文档里那批 `:215`/`:222` 锚点）、又让 JVM 测不到（那文件满是 compose import），
 * 违反本仓的纯 JVM 边界；而在测试侧造一枚"main 不叫它"的内核去当尺子，就是 #118 驳回过的那种套套逻辑。
 * 所以这一档全部按**落点 / 次序 / 逐字**取判据：
 * 1. [两枚删除入口对照表…] —— 表驱动：首页单课与管理页删组各一枚，三拍（读结论 → 分文案 → 闸那颗按钮）
 *    逐枚同序，闸的变量名**从原文里抓**（不是拿常量比常量）。
 * 2. [VM 那两枚早返回…] —— 两枚都回 `false`、压栈排在读到非空快照之后、结论被 `.fold({ it }, …)` 原样端出去。
 * 3. [仓储层那份空快照就是结论…] —— 签名仍返回快照（不许长出一条平行的 Boolean 面）、
 *    空快照构造点仍恰好四处（"再补一层"会从这一格露出来）。
 * 4. [调用点册子…] —— `deleteCourseGroup` 全仓被谁调、**返回值被谁读**，枚数与落点一起钉，复算命令在消息里。
 * 5. [撤销条目的身份这一族本卡未动…] —— 红线：不给撤销条目加身份（T123）、不改 `undo()` 签名、不动 `UndoManager`。
 * 6. [管理页那一支只此一枚提示条…] —— 不许长出第二条 snackbar、不许加兜底文案，对话框结构那三处自关闸还在。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。
 *
 * ## 证据档位（如实写明）
 *
 * 本卡只有 JVM 源码核对与静态尺两级，**没有装机级证据**：删不到那一档在真机上到底什么手感
 * （提示条念「删除失败：X 还在课表里」时屏幕上是不是真的还留着那一组）本卡没量、也不会去量（零设备红线）。
 * 另一笔**没收到**的账如实登记：`deleteCourseGroup` 抛异常那一档 VM 走的是 `.fold` 的
 * `onFailure` 支，它把 `AppMessage("删除课程失败：…", isError = true)` 写进 `importMessage`，
 * 而**课表管理页从来不渲染 `importMessage`**（全文件对它的引用 0 处；首页有那枚 `LaunchedEffect` 桥，
 * `HomeScreen` 才有）⇒ 异常那一趟用户只看得见「… 还在课表里」这句**归因不对**的文案。
 * 本卡按红线没造新文案、没补渲染，只把这一笔记成残账（收它要另开一档：要么本页补桥，要么 VM 分档回话）。
 */
class CourseGroupDeletionWiringGuardTest {

    // ─────────────── ① 表驱动：两枚删除入口，三拍同序、闸同一枚变量 ───────────────

    /**
     * 首页长按删一门课（T121 收的）与课表管理页删一整组（本卡收的）是**同一族形状**：
     * 一次删除 → 一枚提示条 → 一颗可能被闸住的「撤销」。两枚各占一行，逐行按同一套格子判。
     *
     * 拿两枚放一起判的理由不是省事：本卡的修法就是"照首页那一支对齐"，
     * 对齐**这件事实**只有把两支摆进同一张表才钉得住 —— 单看管理页那一支，
     * 谁都能改成另一套形状而自称"也读了结论"。
     */
    @Test
    fun `两枚删除入口对照表 读结论 分文案 闸按钮 三拍同序且闸同一枚变量`() {
        for (row in DELETION_ENTRIES) {
            val raw = readMainSource(row.file)
            val code = blankCommentsKeepingLiterals(raw)
            val hits = indexOfAll(code, row.readVerdict)
            assertEquals(
                "${row.who}：那一趟删除必须**读**结论（`${row.readVerdict}` 恰好一处）。\n" +
                    "复算：grep -n '${row.readVerdict}' app/src/main/java/${row.file}\n" +
                    "0 处 = 返回值又被丢回地上；2 处 = 一趟删除调了两回，第二回必然删不到 ⇒ 屏幕上" +
                    "念的那句与真正发生的那件事对不上",
                1,
                hits.size,
            )
            val launch = scopeLaunchBody(code, hits.first())
            val base = launch.openBrace + 1
            val body = launch.body

            assertEquals(
                "${row.who}：提示条那一句必须**由同一枚结论分两支**（整句逐字匹配）。" +
                    "拆开写（两枚 `showSnackbar`、或文案判一枚变量而按钮判另一枚）就不叫同源了",
                1,
                occurrences(body, row.messageExpression),
            )
            assertEquals(
                "${row.who}：那颗「撤销」必须仍在那儿、且被同一枚结论闸着（`${row.gatedLabel}` 恰好一处）。" +
                    "**朝窄扭这里红**：整颗按钮被删掉不是收窄，是把「删到了也撤不回」当代价付出去",
                1,
                occurrences(body, row.gatedLabel),
            )
            assertEquals(
                "${row.who}：无条件的那颗按钮（`actionLabel = \"撤销\",`）今天必须 0 处 —— 那正是本卡的病本体。" +
                    "**朝宽扭这里红**：改回无条件，删不到的那一次就又会长出一颗点了就撤错东西的按钮",
                0,
                occurrences(body, UNGATED_LABEL),
            )
            assertEquals(
                "${row.who}：失败那支的话术必须是既有这句「…还在课表里」（恰好一处，与首页同一句）。" +
                    "本卡按红线**没造新文案**；要换成别的口径（例如分「库里没有」与「出错了」两档）得回来重判",
                1,
                occurrences(body, row.failureCopy),
            )
            // 闸变量：**从原文里**各抓一次名字，再核同源（拿两张常量比是套套逻辑）
            val assigned = row.assignPattern.find(body)?.groupValues?.get(1)
            val onMessage = row.messagePattern.find(body)?.groupValues?.get(1)
            val onLabel = row.labelPattern.find(body)?.groupValues?.get(1)
            assertEquals(
                "${row.who}：三处都得分得出闸在哪枚变量上（赋值句 / 文案那一句 / 那颗按钮），" +
                    "应当分出 3 处，实际 $assigned / $onMessage / $onLabel —— 抓不到就是写法换了" +
                    "（三元换 `when`、`takeIf` 换 `if` 块），本守卫要跟着重钉",
                3,
                listOfNotNull(assigned, onMessage, onLabel).size,
            )
            assertEquals(
                "${row.who}：「文案两支怎么分」与「那颗按钮给不给」必须闸在**同一枚**变量上（同源）：" +
                    "赋值句 `$assigned` / 文案 `$onMessage` / 按钮 `$onLabel` 三处必须同名",
                1,
                listOfNotNull(assigned, onMessage, onLabel).distinct().size,
            )
            val order = listOf(
                row.readVerdict,
                row.messageExpression,
                row.gatedLabel,
                DURATION_LONG,
                row.actionPerformed,
                row.undoCall,
            ).map { body.indexOf(it) }
            assertTrue(
                "${row.who}：三拍次序应当是 读结论 → 分文案 → 闸按钮 → 定时长 → 看点没点撤销，" +
                    "且点判在前、撤销调用在后（次序倒了就是在读上一次删除的结果）。实际落在：" +
                    order.joinToString(" / ") { "${lineOf(raw, if (it < 0) 0 else base + it)}" } +
                    "\n（-1 表示那一处压根没找着）",
                order.none { it < 0 } && order.zipWithNext().all { (a, b) -> a < b },
            )
            assertEquals(
                "${row.who}：撤销那一步仍然只在有人点了提示条上那颗按钮之后才发生（`Performed` 那一判恰好一处）。" +
                    "本卡没给撤销条目加身份 —— 捞栈顶这一族是 T123 的账，见第 ⑤ 层",
                1,
                occurrences(body, row.actionPerformed),
            )
            assertEquals(
                "${row.who}：提示条时长仍是 `SnackbarDuration.Long`（恰好一处）",
                1,
                occurrences(body, DURATION_LONG),
            )
        }
    }

    // ─────────────── ② VM：两枚早返回都是"没删到"，压栈排在读到快照之后 ───────────────

    @Test
    fun `VM 组删除那两枚早返回都回false 压栈排在读到非空快照之后 结论原样端出去`() {
        val raw = readMainSource(SCHEDULE_VIEW_MODEL)
        val code = blankCommentsKeepingLiterals(raw)
        // 这一枚函数的签名以 `…: Boolean {` 收尾（不是 `= suspendCatching {` 那种表达式体），
        // 所以按花括号配平切出来的"体"连 `.fold(…)` 那段一起含在内 —— 想单判成功路径那一拍，
        // 得再往里切一层。两枚偏移累加起来才换得回全文行号（失败消息里要报行）。
        val outer = functionBody(code, VM_GROUP_SIGNATURE, "ScheduleViewModel.deleteCourseGroup")
        val outerBody = outer.body
        val block = functionBody(outerBody, VM_SUSPEND_BLOCK, "deleteCourseGroup 的 suspendCatching 块")
        val body = block.body
        val base = outer.openBrace + 1 + block.openBrace + 1

        assertEquals(
            "VM 那一层那枚「递进来的表就是空的」早返回必须还在：`if (courses.isEmpty()) return false`。\n" +
                "复算：grep -n 'courses.isEmpty()) return false' app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt\n" +
                "它就是 UI 那句「删除失败」的两条来源之一；漂了等于少一条 UI 能读到的真相",
            1,
            occurrences(outerBody, VM_EMPTY_ARGUMENT_EXIT),
        )
        assertEquals(
            "那一枚早返回必须排在 `suspendCatching` 块**之前**（在块里就是另一回事了：块体是被 fold 兜着的）",
            0,
            occurrences(body, VM_EMPTY_ARGUMENT_EXIT),
        )
        assertEquals(
            "VM 那一层那枚「库里一行都没捞到」的早返回必须还在：`if (snapshot.courses.isEmpty()) " +
                "return@suspendCatching false`。这一枚才是本卡那一句谎的解药本体 —— " +
                "改前 UI 根本不叫它，删不到也念「已删除」",
            1,
            occurrences(body, VM_EMPTY_SNAPSHOT_EXIT),
        )
        assertEquals(
            "两枚早返回都只许回 `false`：不许出现 `return@suspendCatching true`" +
                "（那一支一旦成立，UI 读到的「结论」就又成了一句空话）",
            0,
            occurrences(body, "return@suspendCatching true"),
        )
        assertEquals(
            "压撤销记录必须排在读回快照**并且判过非空**之后：`UndoManager.pushDeleteGroup(snapshot.courses, " +
                "snapshot.reminders)` 恰好一处。它要是挪到早退之前，删不到也压栈 ⇒ 栈里多一条 phantom，" +
                "而 `pop()` 捞栈顶，下一次撤销正好捞到它（T121 在单课那一支记的就是这笔账）",
            1,
            occurrences(body, VM_PUSH_GROUP),
        )
        assertEquals(
            "整个 `deleteCourseGroup` 对仓储层只许一趟调用（读快照那一趟）。" +
                "长出第二趟 = VM 自己再去查一遍库来判存在性 —— 那是本卡红线明令不许的「再补一层」：" +
                "\n复算：grep -n 'repository\\.' app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt",
            1,
            occurrences(outerBody, "repository."),
        )
        assertEquals(
            "成功块里那句结论字面量 `true` 只许一处（块体的收尾）。多处 = 这一支长出了第二条「成功」，" +
                "UI 就得分辨哪一条才是删到了，而同源那格（第 ① 层）只钉得住一枚变量",
            1,
            occurrences(body, "true"),
        )
        val snapshotAt = body.indexOf(VM_READ_SNAPSHOT)
        val emptyExitAt = body.indexOf(VM_EMPTY_SNAPSHOT_EXIT)
        val pushAt = body.indexOf(VM_PUSH_GROUP)
        val refreshAt = body.indexOf(VM_REFRESH)
        assertTrue(
            "四拍次序：读快照 → 判空早退 → 才压栈 → 才刷 UI。漂任何一拍都要重判「删不到会不会留下痕迹」：" +
                "\n  读快照 L${lineOf(raw, base + snapshotAt)} / 判空早退 L${lineOf(raw, base + emptyExitAt)} / " +
                "压栈 L${lineOf(raw, base + pushAt)} / 刷 UI L${lineOf(raw, base + refreshAt)}",
            snapshotAt in 0 until emptyExitAt && emptyExitAt in 0 until pushAt && pushAt in 0 until refreshAt,
        )
        assertEquals(
            "VM 必须把块体里那枚 Boolean **原样**端给调用点（`}.fold({ it }, { e ->` 恰好一处）。" +
                "回到旧那句 `.fold({ true }, { e ->` 就等于把两枚早返回作废 —— 本卡那句谎的机制本体，" +
                "T121 在单课那一支已经把同一枚断言钉过一次，两族共一把尺",
            1,
            occurrences(outerBody, VM_FOLD_PASSTHROUGH),
        )
        assertEquals(
            "VM 报 false 的来源今天恰好三处（`if (courses.isEmpty()) return false` / " +
                "`… return@suspendCatching false` / 异常兜底那一句），且只有这三处：" +
                "多一处 = 又长出一条「其实删到了却报没删到」的路（那是反向的病）；少一处 = 有一条真话被抹了。" +
                "UI 读的是同一枚结论，所以本卡不需要在提示条上分「库里没有」与「出错了」两档文案" +
                "（那笔归因账登记在类头残账里）",
            3,
            occurrences(outerBody, "false"),
        )
    }

    // ─────────────── ③ 仓储层：结论早就有了，本卡不往它上面再补一层 ───────────────

    @Test
    fun `仓储层那份空快照就是结论 本卡没往它上面再补一层`() {
        val raw = readMainSource(SCHEDULE_REPOSITORY)
        val code = blankCommentsKeepingLiterals(raw)
        val extracted = functionBody(code, REPO_GROUP_SIGNATURE, "ScheduleRepository.deleteCourseGroup")
        val body = extracted.body
        val base = extracted.openBrace + 1

        assertEquals(
            "仓储层那枚 `deleteCourseGroup` 的签名必须仍然**返回快照**而不是 Boolean：" +
                "撤销要的就是事务内读回来的那些行本身，换成就剩「删了几行」这笔糊账（T121 的" +
                "`CourseDeletionPolicy` 那节把为什么不选 Boolean 型写得很清楚）",
            1,
            occurrences(code, REPO_GROUP_SIGNATURE),
        )
        assertEquals(
            "不许再长出第二枚平面的 `deleteCourseGroup(…): Boolean`（重载/改名旁路）：" +
                "同一件事有两个入口，下一处调用点抄哪一面全凭运气",
            0,
            occurrences(code, "suspend fun deleteCourseGroup(courses: List<Course>): Boolean"),
        )
        assertEquals(
            "「删没删到」的证据源必须仍然是事务内按请求行 id 读回来的那一批行：" +
                "`val rows = courses.mapNotNull { courseDao.getById(it.id)?.toDomain() }`",
            1,
            occurrences(body, REPO_READ_ROWS),
        )
        assertEquals(
            "库里一行没捞到就整个事务什么都不动、交回空快照：`if (rows.isEmpty()) " +
                "return@withTransaction CourseGroupSnapshot(emptyList(), emptyList())`。" +
                "VM 那枚早返回读的就是它 ⇒ **本卡不许在这一层再补一道判定**（红线）",
            1,
            occurrences(body, REPO_EMPTY_ROWS_EXIT),
        )
        assertEquals(
            "空快照的构造点全文件恰好四处（类型声明 + 仓储层两处早退 + 成功那份）。" +
                "**多一处 = 有人在这一层「又补了一层」空快照** —— 那是本卡红线点名不许的方向；" +
                "**少一处 = 某一趟早退换了说法**，VM 那两枚早返回的含义要跟着重判。" +
                "\n复算：grep -c 'CourseGroupSnapshot(' app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt",
            4,
            occurrences(code, "CourseGroupSnapshot("),
        )
        val readAt = body.indexOf(REPO_READ_ROWS)
        val emptyAt = body.indexOf(REPO_EMPTY_ROWS_EXIT)
        val deleteAt = body.indexOf(REPO_DELETE_ROWS)
        val returnAt = body.indexOf(REPO_RETURN_SNAPSHOT)
        assertTrue(
            "仓储层四拍次序：读回行 → 判空早退 → 才删行 → 才把删掉的行连提醒一起交回去。" +
                "「判空早退排在删行之后」就是拿 0 行的删除试一把：" +
                "\n  读回行 L${lineOf(raw, base + readAt)} / 判空 L${lineOf(raw, base + emptyAt)} / " +
                "删行 L${lineOf(raw, base + deleteAt)} / 交回快照 L${lineOf(raw, base + returnAt)}",
            readAt in 0 until emptyAt && emptyAt in 0 until deleteAt && deleteAt in 0 until returnAt,
        )
    }

    // ─────────────── ④ 调用点册子：谁调它、返回值被谁读 ───────────────

    /**
     * 形状一改要跟着改的就是这几处。**枚数与落点一起钉**：只数枚数的话，有人把管理页那一处挪去
     * 别的文件、再在别处补一枚同名调用，守卫照样绿（本仓登记过这一洞）。
     */
    @Test
    fun `调用点册子 组删除谁调它 返回值谁读 枚数与落点一起钉`() {
        val byFile = readAllMainSources().mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        assertEquals(
            "`viewModel.deleteCourseGroup(` 的调用点全仓恰好落在课表管理页这一处文件" +
                "（首页那枚走的是单课 `deleteCourse`，两族各有自己的入口）。\n" +
                "复算：grep -rn 'viewModel.deleteCourseGroup(' app/src/main --include='*.kt'\n" +
                "多一处 = 又有一条链拿到这枚 Boolean，要按第 ① 层那套「读结论 → 两支文案 → 闸按钮」重钉一次；" +
                "少一处 = 这条链换了入口，整本册子要重算：" + filesHint(byFile, "viewModel.deleteCourseGroup("),
            listOf(COURSE_MANAGEMENT_NAME),
            byFile.filter { occurrences(it.value, "viewModel.deleteCourseGroup(") > 0 }.keys.toList(),
        )
        assertEquals(
            "`viewModel.deleteCourseGroup(` 全仓**枚数**恰好一枚（落点由上一格钉，这一格钉次数）：" +
                "同一份文件里调两回 = 第二回必然删不到 ⇒ 屏幕上念的那句与真正发生的那件事又分家了。" +
                "⚠️ 只钉落点（文件集合）钉不住这一型：两回都在管理页那份文件里，册子照样绿。" +
                "\n复算：grep -rc 'viewModel.deleteCourseGroup(' app/src/main --include='*.kt' | grep -v ':0'：" +
                filesHint(byFile, "viewModel.deleteCourseGroup("),
            1,
            byFile.values.sumOf { occurrences(it, "viewModel.deleteCourseGroup(") },
        )
        assertEquals(
            "那一处**读了**返回值（`val deleted = viewModel.deleteCourseGroup(` 恰好一处）。" +
                "这一格与下一格是同一笔账的两面：调用点册子只钉「有人调」，本卡真正的病是「调了不看」：" +
                "\n复算：grep -rn 'val deleted = viewModel.deleteCourseGroup(' app/src/main --include='*.kt'",
            1,
            occurrences(byFile.getValue(COURSE_MANAGEMENT_NAME), "val deleted = viewModel.deleteCourseGroup("),
        )
        assertEquals(
            "全仓不许再出现「调了组删除却不看返回值」那种裸语句（行首就是 `viewModel.deleteCourseGroup(`）。" +
                "**朝宽扭这里红**：把那枚 `val deleted = ` 拆掉，本格与第 ① 层同时塌；" +
                "⚠️ 这一格必须按整行判，不能数子串 —— `viewModel.deleteCourseGroup(target.fragments)` 是" +
                "`val deleted = viewModel.deleteCourseGroup(target.fragments)` 的子串，改前改后都命中一次",
            emptyList<String>(),
            byFile.mapNotNull { (name, text) ->
                val line = text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("viewModel.deleteCourseGroup(") && !it.startsWith("val ") }
                line?.let { "$name: $it" }
            },
        )
        assertEquals(
            "`repository.deleteCourseGroup(` 的调用点全仓恰好一处，落在 VM（不是 UI 直接捅仓储层）。\n" +
                "复算：grep -rn 'repository.deleteCourseGroup(' app/src/main --include='*.kt'\n" +
                "UI 绕过 VM 直接调仓储层的话，提示条念的那句就与撤销栈的记录不再同源了：" +
                filesHint(byFile, "repository.deleteCourseGroup("),
            listOf(SCHEDULE_VIEW_MODEL_NAME),
            byFile.filter { occurrences(it.value, "repository.deleteCourseGroup(") > 0 }.keys.toList(),
        )
        assertEquals(
            "往撤销栈里塞「整组删除」这条记录的入口全仓恰好一处（VM 读到非空快照之后那一处）。\n" +
                "复算：grep -rn 'UndoManager.pushDeleteGroup(' app/src/main --include='*.kt'\n" +
                "长出第二处 = 又有一条路径能压 DeleteGroup，而本守卫只钉住了原来那一处的次序：" +
                filesHint(byFile, "UndoManager.pushDeleteGroup("),
            listOf(SCHEDULE_VIEW_MODEL_NAME),
            byFile.filter { occurrences(it.value, "UndoManager.pushDeleteGroup(") > 0 }.keys.toList(),
        )
        assertEquals(
            "那颗被闸住的「撤销」的形状（`actionLabel = \"撤销\".takeIf { deleted },`）全仓恰好两枚，" +
                "落在首页与管理页那两份文件里 —— **对齐**这件事的可复核形式就是「两枚同名同形」：" +
                "少一枚 = 有一支漂回无条件（或整颗被删）；多一枚 = 第三处删除入口长出来了，" +
                "它得连同第 ① 层那张表一起重判：" + filesHint(byFile, "actionLabel = \"撤销\".takeIf { deleted },"),
            listOf(COURSE_MANAGEMENT_NAME, HOME_SCREEN_NAME).sorted(),
            byFile.filter { occurrences(it.value, "actionLabel = \"撤销\".takeIf { deleted },") > 0 }
                .keys.toList().sorted(),
        )
        assertEquals(
            "旧病那句无条件的 `actionLabel = \"撤销\",` 今天全仓 0 处（改前它恰好一枚，就在管理页）。" +
                "复算：grep -rn 'actionLabel = \"撤销\",' app/src/main --include='*.kt'",
            emptyList<String>(),
            byFile.filter { occurrences(it.value, UNGATED_LABEL) > 0 }.keys.toList(),
        )
    }

    // ─────────────── ⑤ 红线：撤销身份 / undo 签名 / UndoManager 一字未动 ───────────────

    @Test
    fun `撤销条目的身份这一族本卡未动 undo签名与pop捞栈顶原样 归T123`() {
        val vmCode = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val undoCode = blankCommentsKeepingLiterals(readMainSource(UNDO_MANAGER))
        val cmsCode = blankCommentsKeepingLiterals(readMainSource(COURSE_MANAGEMENT_SCREEN))

        assertEquals(
            "`fun undo() {` 仍无参、全仓恰好一处定义：本卡按红线没动签名 —— 让撤销认得出「是哪一笔」" +
                "是 T123 的事（要装机、已被押后），本卡只保证**删不到的那一次不给那颗按钮**，" +
                "从而不再把不相干的回滚递到用户手边：" +
                "\n复算：grep -n 'fun undo(' app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt",
            1,
            occurrences(vmCode, VM_UNDO_SIGNATURE),
        )
        assertEquals(
            "管理页那颗按钮仍走旧 alias `fun undoDeleteCourse() = undo()`（定义一处、调用点一处）。" +
                "它今天仍然是「捞栈顶」，本卡没换它 —— 收这一族要先有身份，见上",
            1,
            occurrences(vmCode, VM_UNDO_ALIAS),
        )
        assertEquals(
            "`pop()` 仍捞栈顶（`removeLastOrNull`），`UndoManager` 一字未动（红线）",
            1,
            occurrences(undoCode, UNDO_POP_TOP),
        )
        assertEquals(
            "`pushDeleteGroup` 那枚签名不许长出身份参数（组 id / 令牌 / 时间戳都不许）：" +
                "它是 T123 的地盘，本卡提前动它等于把一张需要装机证据的卡当便宜账收掉",
            1,
            occurrences(undoCode, UNDO_PUSH_GROUP_SIGNATURE),
        )
        assertEquals(
            "那一枚 `DeleteGroup` 记录回话用的标签仍是「删除整门课程」（VM 那一步压的就是它）。" +
                "标签改了要回来重钉「用户分辨不分辨得出撤的是哪一笔」这笔账",
            1,
            occurrences(undoCode, UNDO_DELETE_GROUP_LABEL),
        )
        assertEquals(
            "管理页不许提前用上「按身份撤销」那族新入口（`undoDeleteGroup(` / 带参 `undo(` 都算越界）",
            0,
            occurrences(cmsCode, "undoDeleteGroup(") + occurrences(cmsCode, "viewModel.undo("),
        )
    }

    // ─────────────── ⑥ 不许长出第二条提示条 / 兜底 / 改对话框结构 ───────────────

    @Test
    fun `管理页那一支只此一枚提示条 对话框自关闸三处原样`() {
        val raw = readMainSource(COURSE_MANAGEMENT_SCREEN)
        val code = blankCommentsKeepingLiterals(raw)

        assertEquals(
            "本页那枚 `snackbarHostState` 只许一处 `showSnackbar` 调用（确认框里那一支）。" +
                "**朝宽扭这里红**：为了「说清为什么没删到」再补一条 toast / 第二条 snackbar，" +
                "等于把同源那一枚结论拆成两句各说各的（本卡红线：不许加「以防万一」的兜底）：" +
                "\n复算：grep -n 'showSnackbar(' app/src/main/java/com/buaa/schedule/ui/course/CourseManagementScreen.kt",
            1,
            occurrences(code, "snackbarHostState.showSnackbar("),
        )
        assertEquals(
            "`actionLabel = ` 全页恰好一处：提示条上只可能有一颗按钮，且它由第 ① 层那张表管着。" +
                "长出第二处就是有人拿别的条件去闸另一颗按钮，同源的账要重算",
            1,
            occurrences(code, "actionLabel = "),
        )
        assertEquals(
            "成功那句文案（`\"已删除「${'$'}{target.displayName}」\"`）全页恰好一处：改前改后一字未动 —— " +
                "本卡只给它加了一道闸，没造新文案、没改措辞",
            1,
            occurrences(code, GROUP_SUCCESS_COPY),
        )
        // 对话框结构：确认那一步先自关载体，再进协程（三处 `pendingDelete = null` 一处不多一处不少）
        assertEquals(
            "`pendingDelete = null` 恰好三处（`onDismissRequest` / 确认那颗 / 取消那颗）：" +
                "确认那一支写在 `scope.launch {` **之前**，所以删除进行中那层确认框已经收了 —— " +
                "这是 §9.2 第 5 行给这一处判「不收」的两道间接闸之一。改成先起协程后收框，" +
                "那扇窗里的重复点击就又能压一枚结论进来：" +
                "\n复算：grep -c 'pendingDelete = null' app/src/main/java/com/buaa/schedule/ui/course/CourseManagementScreen.kt",
            3,
            occurrences(code, "pendingDelete = null"),
        )
        val launchAt = code.indexOf(GROUP_READ_VERDICT)
        val dismissAt = code.indexOf("onDismissRequest = { pendingDelete = null }")
        assertTrue(
            "确认框那句 `onDismissRequest` 与那趟读结论的 `scope.launch` 都还在，且前者在前：" +
                "弹窗换了载体（或把收框推迟到协程之后）就要回来重判第 ①⑥ 两层。" +
                "\n  onDismissRequest L${lineOf(raw, dismissAt)} / 读结论 L${lineOf(raw, launchAt)}",
            dismissAt in 0 until launchAt,
        )
        assertEquals(
            "确认框正文仍走共享文案件 `deleteGroupConfirmText(group.fragments, state.timeSlots)`（恰好一处）：" +
                "本卡没碰它 —— 它承诺的是「删掉整组」，与提示条念的「删没删到」是两件事",
            1,
            occurrences(code, "Text(text = deleteGroupConfirmText(group.fragments, state.timeSlots))"),
        )
    }

    // ---- 源码核对小工具（抄 CourseDeletionWiringGuardTest / UndoUpdateEntryGuardTest，同一族口径）----

    /** 一枚 `{`…`}` 的体：开括号位、闭括号位、中间那段 */
    private class Extracted(val body: String, val openBrace: Int, val closeBrace: Int)

    /**
     * 一枚删除入口的形状（第 ① 层那张表的行）。
     *
     * 三枚 `Regex` 是**从原文里抓闸变量**用的：只看字面量相等钉不住"读的是不是同一枚变量"，
     * 而把常量摆成两两相等去比就是 #118 驳回过的套套逻辑。
     */
    private class DeletionEntry(
        val who: String,
        val file: String,
        val readVerdict: String,
        val messageExpression: String,
        val failureCopy: String,
        val gatedLabel: String,
        val actionPerformed: String,
        val undoCall: String,
        val assignPattern: Regex,
        val messagePattern: Regex,
        val labelPattern: Regex,
    )

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

    /** 从 [hit] 往前找包围它的那次 `scope.launch {`，再按花括号配平取出那一段体（带偏移，好报行号） */
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

    /** 那一处落在原文的第几行（失败消息要把人带到那一处，只报个数等于没有守卫） */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

    private fun filesHint(byFile: Map<String, String>, needle: String): String =
        byFile.filter { occurrences(it.value, needle) > 0 }
            .entries
            .joinToString(separator = "") { "  ${it.key}: ${occurrences(it.value, needle)} 处\n" }

    /** 全仓 main 源码：文件名 → 内容（扫全部是为了钉"不许长出第三处"，而不是数出现次数） */
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
     * （`actionLabel = "撤销",`、`val deleted = viewModel.deleteCourseGroup(`、
     * `return@suspendCatching false` 都在账里）。不抹注释的话第 ④ 层那几枚册子会数出凭空的
     * 第二处、第三处 —— 那正是"枚数对但落点错"的反面：枚数本身就是假的。
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
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val SCHEDULE_VIEW_MODEL_NAME = "ScheduleViewModel.kt"
        const val HOME_SCREEN_NAME = "HomeScreen.kt"
        const val COURSE_MANAGEMENT_NAME = "CourseManagementScreen.kt"

        const val LAUNCH_ANCHOR = "scope.launch {"
        const val DURATION_LONG = "duration = SnackbarDuration.Long,"
        const val UNGATED_LABEL = "actionLabel = \"撤销\","
        const val GATED_LABEL = "actionLabel = \"撤销\".takeIf { deleted },"
        const val GROUP_SUCCESS_COPY = "\"已删除「\${target.displayName}」\""
        const val GROUP_READ_VERDICT = "val deleted = viewModel.deleteCourseGroup(target.fragments)"

        // ② VM
        const val VM_GROUP_SIGNATURE =
            "suspend fun deleteCourseGroup(courses: List<Course>): Boolean {"
        const val VM_SUSPEND_BLOCK = "return suspendCatching {"
        const val VM_EMPTY_ARGUMENT_EXIT = "if (courses.isEmpty()) return false"
        const val VM_READ_SNAPSHOT = "val snapshot = repository.deleteCourseGroup(courses)"
        const val VM_EMPTY_SNAPSHOT_EXIT = "if (snapshot.courses.isEmpty()) return@suspendCatching false"
        const val VM_PUSH_GROUP = "UndoManager.pushDeleteGroup(snapshot.courses, snapshot.reminders)"
        const val VM_REFRESH = "afterDataChangedInternal()"
        const val VM_FOLD_PASSTHROUGH = "}.fold({ it }, { e ->"

        // ③ 仓储层
        const val REPO_GROUP_SIGNATURE =
            "suspend fun deleteCourseGroup(courses: List<Course>): CourseGroupSnapshot {"
        const val REPO_READ_ROWS = "val rows = courses.mapNotNull { courseDao.getById(it.id)?.toDomain() }"
        const val REPO_EMPTY_ROWS_EXIT =
            "if (rows.isEmpty()) return@withTransaction CourseGroupSnapshot(emptyList(), emptyList())"
        const val REPO_DELETE_ROWS = "rows.forEach { courseDao.delete(it.toEntity()) }"
        const val REPO_RETURN_SNAPSHOT = "CourseGroupSnapshot(rows, reminders)"

        // ⑤ 撤销身份这一族（本卡未动）
        const val VM_UNDO_SIGNATURE = "fun undo() {"
        const val VM_UNDO_ALIAS = "fun undoDeleteCourse() = undo()"
        const val UNDO_POP_TOP = "fun pop(): UndoEntry? = stack.removeLastOrNull()"
        const val UNDO_PUSH_GROUP_SIGNATURE =
            "fun pushDeleteGroup(courses: List<Course>, reminders: List<ReminderSetting>) ="
        const val UNDO_DELETE_GROUP_LABEL = "push(UndoAction.DeleteGroup(courses, reminders), \"删除整门课程\")"

        /**
         * 第 ① 层那张表：两枚删除入口。`file` 用 `app/src/main/java/com/buaa/schedule/` 之下的相对路径。
         *
         * 首页那一行**本卡一字未动**（红线：那是 T121 的产物），它进表是为了把"对齐"钉成可判的东西。
         */
        val DELETION_ENTRIES = listOf(
            DeletionEntry(
                who = "首页长按删一门课（T121）",
                file = "com/buaa/schedule/ui/home/HomeScreen.kt",
                readVerdict = "val deleted = viewModel.deleteCourse(course)",
                messageExpression =
                    "message = if (deleted) \"已删除「\${course.displayName}」\" " +
                        "else \"删除失败：\${course.displayName} 还在课表里\",",
                failureCopy = "\"删除失败：\${course.displayName} 还在课表里\"",
                gatedLabel = GATED_LABEL,
                actionPerformed = "if (result == SnackbarResult.ActionPerformed) viewModel.undo()",
                undoCall = "viewModel.undo()",
                assignPattern = Regex("""val (\w+) = viewModel\.deleteCourse\(course\)"""),
                messagePattern = Regex("""message = if \((\w+)\) "已删除「"""),
                labelPattern = Regex("""actionLabel = "撤销"\.takeIf \{ (\w+) \},"""),
            ),
            DeletionEntry(
                who = "课表管理页删一整组（T127）",
                file = "com/buaa/schedule/ui/course/CourseManagementScreen.kt",
                readVerdict = "val deleted = viewModel.deleteCourseGroup(target.fragments)",
                messageExpression =
                    "message = if (deleted) \"已删除「\${target.displayName}」\" " +
                        "else \"删除失败：\${target.displayName} 还在课表里\",",
                failureCopy = "\"删除失败：\${target.displayName} 还在课表里\"",
                gatedLabel = GATED_LABEL,
                actionPerformed = "if (result == SnackbarResult.ActionPerformed) {",
                undoCall = "viewModel.undoDeleteCourse()",
                assignPattern = Regex("""val (\w+) = viewModel\.deleteCourseGroup\(target\.fragments\)"""),
                messagePattern = Regex("""message = if \((\w+)\) "已删除「"""),
                labelPattern = Regex("""actionLabel = "撤销"\.takeIf \{ (\w+) \},"""),
            ),
        )
    }
}
