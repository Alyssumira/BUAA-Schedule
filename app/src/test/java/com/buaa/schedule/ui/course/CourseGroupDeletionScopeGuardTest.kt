package com.buaa.schedule.ui.course

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T253⑤：源码形状守卫 —— 钉住「课表管理页的删除范围与过滤态并尺」这一手，防它回潮。
 *
 * ## 病灶（盘面已证实，不是臆造）
 *
 * `groupCourses(courses, query)` 改前是**先过滤再归并**（`SemesterStats.creditsByCourse(filtered)`），
 * 于是搜索态下 `CourseGroup.fragments` 装的是命中片段；而
 * - 那颗按钮的 `contentDescription = "删除整门课"`，
 * - 确认框念「将删除这门课的全部 `${fragments.size}` 个片段」，
 * - 交给仓储层的也就是 `deleteCourseGroupAndAwait(target.fragments)` 那一枚子集。
 * 搜索命中 2 / 整组 5 时删 2 留 3，屏幕上没有一处说过这件事 ⇒ **删除范围被 UI 的过滤状态悄悄改了**。
 *
 * ## 本守卫防的具体是哪一手
 *
 * 卡面点名：必须真能拦住「删除参数又换回过滤集合」。三道都要过才算拦住：
 * 1. 调用点那一格（第 ①② 层）：删除的实参只能是 `deletion.fragments`（判据产物），
 *    病灶那四形（`…AndAwait(group.fragments)` / `…AndAwait(target.fragments)` /
 *    `deleteGroupConfirmText(group.fragments` / `…(target.fragments`)一律 0 处；
 * 2. **产物那一格**（第 ③ 层）：`groupCourses` 里归并只能吃全量 `courses`，
 *    `creditsByCourse(filtered)` 与 `val filtered` 一律 0 处，筛组那一行必须排在归并**之后** ——
 *    有人把次序换回来（先过滤再归并），第 ①② 层的字面量可以一字不改而整张卡又只剩子集，
 *    所以这一层才是那一手的真闸门；
 * 3. 判据本体那一格（第 ⑤ 层）：两档各自存着、返回值不是 Boolean、两支句子都在场。
 *
 * ## 钉法（逐层点名，本仓四种钉法里本守卫三种都用上了）
 *
 * - **数出现次数**（一律先抹注释再数，落点另判）：第 ①②③④⑤⑥⑦⑧ 层都有格子走它，
 *   正向钉（恰好一处）与反向钉（病灶形 0 处）各数各的；
 * - **读形状**（把 `ModalTransition` / `groupCourses` / `CourseGroup` 那一块体切出来判落点与次序）：
 *   第 ①③⑥⑧ 层 —— 只有这一枚能判「归并排在筛组之前」这种**次序**，计数判不到；
 * - **逐字节**（整句原文进 needle，判语里附 grep -F 复算行）：第 ②③⑤⑥⑦ 层里那几句用户可见的话、
 *   那枚谓词签名、那三枚入参、那一枚字段；
 * - **逐字节比对整文件**那一枚本守卫**不用**（插一行就红，不带信息量，还会把每次改文案都变成红线事故）。
 *
 * 每格失败消息都带「钉法 + 判语 + 文件 + `grep` 复算行」，**不写死行号**：需要行号处按运行时
 * 换算（`lineOf`）报实到行号，needle 本身不含行号。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。输入面：`src/main/java` 整目录已由
 * `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 声明成 `testDebugUnitTest` 的输入 ⇒ 不必新增条目。
 * ⚠️ 零装机证据（本卡红线禁 adb）：屏上那句「搜索只命中其中 K 段」的实际呈现没有装机读数。
 */
class CourseGroupDeletionScopeGuardTest {

    // ─────────────── ① 调用点同源：句子与那一手读的是同一枚值 ───────────────

    @Test
    fun `弹窗那句与删除那一手同读一枚判据产物`() {
        val raw = readMainSource(PAGE)
        val code = blankCommentsKeepingLiterals(raw)

        val modalHits = indexOfAll(code, MODAL_ANCHOR)
        assertEquals("`ModalTransition(payload = pendingDelete) {` 恰好一处（确认框的载体）", 1, modalHits.size)
        val modal = braceBodyFrom(code, modalHits.first() + MODAL_ANCHOR.length - 1)
        val body = modal.body

        // 判据产物只许判定一次：两枚 = 屏幕上一次删除有两回「删哪几段」的答案
        assertEquals(
            "**钉法 = 读形状 + 数出现次数**：`val deletion = courseGroupDeletionScope(` 在**那层确认框体内**恰好一处 —— " +
                "长出第二处 = 弹窗念一枚、按钮删另一枚 —— 本卡治的就是这个分家。\n" +
                "复算：grep -n 'courseGroupDeletionScope(' app/src/main/java/$PAGE",
            1,
            occurrences(body, DELETION_DECLARATION),
        )
        assertEquals(
            "**钉法 = 数出现次数**：同一枚判定在全页也只一处（上一格只框住了确认框那一块体；" +
                "有人把第二枚判定塞进列表那一侧的 composable 就靠这一格抓 —— 两枚判定 = 两个「删哪几段」的答案，" +
                "屏上那句念的与那一手删的必然分家）",
            1,
            occurrences(code, DELETION_DECLARATION),
        )
        val declaredAt = body.indexOf(DELETION_DECLARATION)
        val sentenceAt = body.indexOf(CONFIRM_TEXT_CALL)
        val deleteAt = body.indexOf(GROUP_READ_VERDICT)
        assertTrue(
            "**钉法 = 读形状（次序）**：判定必须排在**念那句与交那一手之前**（今天 判定 → 正文 → 确认那一步）。" +
                "顺序倒了 = 那句话读的是上一帧的判定（`query` 在弹窗开着的时候还能变）。\n" +
                "  判定 L${lineOf(raw, modal.openBrace + 1 + declaredAt)} / " +
                "正文 L${lineOf(raw, modal.openBrace + 1 + sentenceAt)} / " +
                "删除 L${lineOf(raw, modal.openBrace + 1 + deleteAt)}（-1 = 那一处压根没找着）",
            declaredAt in 0 until sentenceAt && declaredAt in 0 until deleteAt,
        )
        // 这一格不是「body 里找得到 = 它在 body 里」那种套套逻辑：数的是**这层确认框里各几何一手**
        assertEquals(
            "**钉法 = 数出现次数（读形状框定的范围内）**：确认框那一块体里 `deleteGroupConfirmText(` 恰好一处 —— " +
                "长出第二处 = 同一个弹窗里两句「删几段」的数，读屏只会念一句，另一句是死字",
            1,
            occurrences(body, "deleteGroupConfirmText("),
        )
        assertEquals(
            "**钉法 = 数出现次数（读形状框定的范围内）**：确认框那一块体里交给仓储层的删除恰好一手 —— " +
                "两手的宿体必须是上面那一枚 `deletion`（这一格与次序判据配对：只判次序不判这一格里究竟有几手，" +
                "有人塞进第二手「顺手把没命中的也删了」就漏了）",
            1,
            occurrences(body, "viewModel.deleteCourseGroupAndAwait("),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`deleteCourseGroupAndAwait(` 在整页里也只一处 —— 管理页另有第二枚删除入口" +
                "的话，它读的是哪一枚范围判据要另开一卡判，不许让它悄悄沿用「卡片上挂着的那几段」",
            1,
            occurrences(code, "deleteCourseGroupAndAwait("),
        )
    }

    // ─────────────── ② 逐字面量册子：删除实参 / 正文 / 档 ───────────────

    @Test
    fun `删除实参与弹窗数字的落点逐字 病灶那四形零处`() {
        val code = blankCommentsKeepingLiterals(readMainSource(PAGE))

        for ((needle, expected) in listOf(
                // 交给仓储层的那一手：只能是判据产物
                GROUP_READ_VERDICT to 1,
                // 弹窗那句：吃的也是判据产物，不是再数一遍某个集合
                CONFIRM_TEXT_CALL to 1,
                // 判据的入参：整组（卡底下那一枚集合）+ 过滤态 + 甲档
                "wholeGroupFragments = group.fragments," to 1,
                "request = GroupDeletionRequest.WholeCourse," to 1,
                // 甲档那句用户可见的话
                "全部 \${scope.announcedFragmentCount} 个片段" to 1,
                "删除按整门课执行、不受搜索影响" to 1,
                // 病灶四形：删除参数换回过滤集合的两种写法
                "deleteCourseGroupAndAwait(group.fragments)" to 0,
                "deleteCourseGroupAndAwait(target.fragments)" to 0,
                "deleteGroupConfirmText(group.fragments" to 0,
                "deleteGroupConfirmText(target.fragments" to 0,
                // 「念整组、删子集」那一型在文案侧的写法
                "全部 \${scope.fragments.size} 个片段" to 0,
            )) {
            assertEquals(
                "**钉法 = 数出现次数（抹注释后）**：`$needle` 在管理页里必须恰好 $expected 处，实到 " +
                    occurrences(code, needle) + " 处。\n" +
                    "复算：grep -Fn '$needle' app/src/main/java/$PAGE\n" +
                    "读法：0 处那几格是**反向钉**（病灶不许回来）；1 处那几格是**正向钉**" +
                    "（换了写法就要回来重判「那句念的数与那一手删的集合是不是同一枚」）",
                expected,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：乙档 `GroupDeletionRequest.MatchedFragmentsOnly` 在这一页的代码里恰好一处" +
                "（`deleteGroupConfirmText` 里那支句子）。它**不是**调用点：本卡裁定走甲档，" +
                "乙档留着是因为卡面红线不许把两档折成一枚 Boolean，也就不许换成乙档时那句" +
                "「全部 N 个片段」继续谎称整门课。长出第二处 = 有人真把调用点换成乙档 ⇒ " +
                "要连第 ⓪ 问裁定一起重判，不是顺手改一格",
            1,
            occurrences(code, "GroupDeletionRequest.MatchedFragmentsOnly"),
        )
    }

    // ─────────────── ③ 归并在过滤之前：那一手的真闸门 ───────────────

    @Test
    fun `管理页先归并后筛组 归并吃的是全量`() {
        val raw = readMainSource(PAGE)
        val code = blankCommentsKeepingLiterals(raw)
        val extracted = functionBody(code, GROUP_COURSES_SIGNATURE, "CourseManagementScreen.groupCourses")
        val body = extracted.body
        val base = extracted.openBrace + 1

        assertEquals(
            "**钉法 = 数出现次数（抹注释后）**：归并那一手在 `groupCourses` 体内恰好一处，且吃的形参是**全量** " +
                "`courses`：`return SemesterStats.creditsByCourse(courses)`。\n" +
                "复算：grep -n 'SemesterStats.creditsByCourse(courses)' app/src/main/java/$PAGE",
            1,
            occurrences(body, "return SemesterStats.creditsByCourse(courses)"),
        )
        // 这一格才是"删除参数又换回过滤集合"的真闸门：字面量全对而归并挪回过滤之后 ⇒ 这里红
        assertEquals(
            "**钉法 = 数出现次数（抹注释后）**：病灶那一形 `SemesterStats.creditsByCourse(filtered)` 必须 0 处。" +
                "它是改前那一手（先过滤再归并），带回的后果逐字写在类头：卡底下只剩命中片段，" +
                "而按钮念「删除整门课」、弹窗念「全部 N 个片段」。\n" +
                "复算：grep -n 'creditsByCourse(filtered)' app/src/main/java/$PAGE（应为空）",
            0,
            occurrences(code, "creditsByCourse(filtered)"),
        )
        assertEquals(
            "**钉法 = 数出现次数（抹注释后）**：`val filtered` 那一枚「先过滤」的中间量在本页代码里必须 0 处 —— " +
                "它是上面那一形的宿主；只把归并的实参改回 `filtered` 而留着别的写法（例如 `visible`）" +
                "也会被下面那枚次序判据抓到，两把尺各数各的",
            0,
            occurrences(body, "val filtered"),
        )
        assertEquals(
            "**钉法 = 逐字节**：筛组那一行必须逐字在场（筛的是**组**、判的是「组里有没有任意一段命中」）：" +
                "`$VISIBILITY_FILTER_LINE`",
            1,
            occurrences(body, VISIBILITY_FILTER_LINE),
        )
        val groupedAt = body.indexOf("return SemesterStats.creditsByCourse(courses)")
        val filteredAt = body.indexOf(VISIBILITY_FILTER_LINE)
        assertTrue(
            "**钉法 = 读形状（次序）**：归并（L${lineOf(raw, base + groupedAt)}）必须排在筛组" +
                "（L${lineOf(raw, base + filteredAt)}）**之前** —— 次序一翻就是「先过滤再归并」，" +
                "`fragments` 又变成命中片段",
            groupedAt in 0 until filteredAt,
        )
        assertEquals(
            "**钉法 = 数出现次数**：关键词归一在本页 `groupCourses` 体内恰好一处，且走的是 domain 那一枚 " +
                "`normalizedCourseQuery(query)` —— 页面自己不许再归一一遍（归两遍 = 两把尺口径不同）",
            1,
            occurrences(body, "normalizedCourseQuery(query)"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：本页自己 `query.trim()` 必须 0 处（那是第二把搜索态的尺，" +
                "归一口径写在 domain 的 `normalizedCourseQuery` 里，只此一处）",
            0,
            occurrences(body, "query.trim()"),
        )
    }

    // ─────────────── ④ 搜索态只有一把尺：命中判据的册子 ───────────────

    @Test
    fun `搜索态的命中的确只有一枚 落点在 domain 内核`() {
        val main = readAllMainSources()
        val code = main.mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        for ((needle, who) in listOf(
                "contains(keyword" to "三处字段命中（原名 / 显示名 / 教师）",
                "fun courseFragmentMatchesQuery(" to "命中判据的定义",
                "fun normalizedCourseQuery(" to "关键词归一",
                "query.trim()" to "归一本体那一枚 trim",
            )) {
            val files = code.filter { occurrences(it.value, needle) > 0 }.keys.toList().sorted()
            assertEquals(
                "**钉法 = 数出现次数 + 读形状（落点册子）**：$who（`$needle`）在 main 里今天只许长在 " +
                    "$KERNEL_REL 一枚文件里（册子的键是相对 `com/buaa/schedule/` 的那一枚）—— 列表面" +
                    "（管理页决定哪张卡出场）与判据面（`courseGroupDeletionScope` 决定删哪几段）共吃同一枚，" +
                    "界面上再写一遍 `contains` 就是第二把「搜索态」的尺。\n复算：" +
                    "grep -rn '$needle' app/src/main --include='*.kt'\n" +
                    "实到落点：" + files,
                listOf(KERNEL_REL),
                files,
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：`contains(keyword` 的枚数单独再数一遍（恰好三枚，缺一枚就是三个字段" +
                "少认了一个，多一枚就是界面上又现写了一遍命中）",
            3,
            code.values.sumOf { occurrences(it, "contains(keyword") },
        )
    }

    // ─────────────── ⑤ 两档存着、返回值不折成 Boolean、两支句子都在场 ───────────────

    @Test
    fun `两档各有形状 判据的返回值没被折成一枚Boolean`() {
        val kernel = blankCommentsKeepingLiterals(readMainSource(KERNEL_PATH))
        val page = blankCommentsKeepingLiterals(readMainSource(PAGE))

        for ((needle, who) in listOf(
                "enum class GroupDeletionRequest {" to "那枚档位的枚举头",
                "WholeCourse," to "甲档常量",
                "MatchedFragmentsOnly," to "乙档常量",
            )) {
            assertEquals(
                "**钉法 = 数出现次数（抹注释后）**：$who（`$needle`）在判据内核里必须恰好一处 —— " +
                    "两档折成一枚旗标就是把这两行并掉；长出第三档则要先回答「删除那一手该认哪一档」。\n" +
                    "复算：grep -n '$needle' app/src/main/java/$KERNEL_PATH",
                1,
                occurrences(kernel, needle),
            )
        }
        assertEquals(
            "**钉法 = 逐字节**：判据的返回类型那一格逐字在场（`): GroupDeletionScope {` 恰好一处）。\n" +
                "复算：grep -n '): GroupDeletionScope {' app/src/main/java/$KERNEL_PATH",
            1,
            occurrences(kernel, "): GroupDeletionScope {"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：内核里返回 Boolean 的判据函数今天恰好一枚 —— 就是搜索命中谓词 " +
                "`courseFragmentMatchesQuery`（下一格把它是哪一枚钉死）。" +
                "本卡不许的那一枚是**删除范围**那手折成 Boolean：「是不是整组删」一旦只剩一枚旗标，" +
                "弹窗那句「全部 N 个片段」就没人知道 N 该念谁的数",
            1,
            occurrences(kernel, "): Boolean"),
        )
        assertEquals(
            "**钉法 = 逐字节**：上面那唯一一枚 Boolean 谓词逐字在场（`$MATCH_PREDICATE_SIGNATURE`）—— " +
                "两格配对才不空转：只数 Boolean 的枚数，有人把删除判据改成 Boolean、" +
                "把谓词换成 Int 档位就同时骗过两格；钉住谓词原文后，Boolean 那一枚只能是它",
            1,
            occurrences(kernel, MATCH_PREDICATE_SIGNATURE),
        )
        val scopeHead = kernel.indexOf("fun courseGroupDeletionScope(")
        assertTrue(
            "**钉法 = 读形状**：判据本体在（找不到就无法判它的返回类型）",
            scopeHead >= 0,
        )
        val scopeSignature = kernel.substring(scopeHead, kernel.indexOf('{', scopeHead))
        assertTrue(
            "**钉法 = 读形状（签名整段）**：`courseGroupDeletionScope` 的返回类型必须仍是 `GroupDeletionScope`，" +
                "签名那一段里不许出现 `Boolean`（卡面红线：两档不许折成一枚旗标）。" +
                "实到签名：「" + scopeSignature.replace(Regex("\\s+"), " ").trim() + "」",
            scopeSignature.contains("GroupDeletionScope") && !scopeSignature.contains("Boolean"),
        )
        for ((needle, expected) in listOf(
                "val fragments: List<Course>," to 1,
                "val announcedFragmentCount: Int," to 1,
                "val request: GroupDeletionRequest," to 1,
                "val narrowedByFilter: Boolean," to 1,
                "val matchedFragmentCount: Int," to 1,
                "val wholeGroupFragmentCount: Int," to 1,
            )) {
            assertEquals(
                "**钉法 = 数出现次数**：产物那六枚字段逐枚在场（`$needle` 恰好 $expected 处，判据内核里）—— " +
                    "删哪几段、念哪个数、落在哪一档、有没有被过滤收窄、命中几段、整组几段，一枚都不许并掉。\n" +
                    "复算：grep -n '$needle' app/src/main/java/$KERNEL_PATH",
                expected,
                occurrences(kernel, needle),
            )
        }
        assertEquals(
            "**钉法 = 读形状**：`deleteGroupConfirmText` 里甲档那一支句子必须由 `scope.request` 分档分出来" +
                "（`        GroupDeletionRequest.WholeCourse ->` 恰好一处：八枚空格起头那一格，" +
                "不是 filterNote 里那枚比较式）。管理页里那句「全部 N 个片段」就挂在这一支上。\n" +
                "复算：grep -n 'GroupDeletionRequest.WholeCourse ->' app/src/main/java/$PAGE",
            1,
            occurrences(page, "        GroupDeletionRequest.WholeCourse ->"),
        )
        assertEquals(
            "**钉法 = 逐字节**：乙档那句实话必须也在场（换成乙档时不许仍然念「全部」）：" +
                "`$乙档句子`（管理页）。\n复算：grep -Fn '$乙档句子' app/src/main/java/$PAGE",
            1,
            occurrences(page, 乙档句子),
        )
    }

    // ─────────────── ⑥ CourseGroup 不许长出第二枚「全量片段」字段 ───────────────

    @Test
    fun `组卡只带一枚片段表 不许并排长出一枚全量`() {
        val code = blankCommentsKeepingLiterals(readMainSource(PAGE))
        val open = code.indexOf(COURSE_GROUP_DECLARATION)
        assertTrue(
            "`internal data class CourseGroup(` 必须在管理页里（找不到就无法判它的字段）\n" +
                "复算：grep -n 'internal data class CourseGroup(' app/src/main/java/$PAGE",
            open >= 0,
        )
        val body = parenBodyFrom(code, open + COURSE_GROUP_DECLARATION.length - 1)
        assertEquals(
            "**钉法 = 读形状**：`CourseGroup` 的参数表里 `List<Course>` 恰好一枚 —— 卡底下那一张片段表" +
                "**就是**整组（由 `groupCourses` 保证）。长出第二枚（例如同时带 `fragments` 与 `allFragments`）" +
                "就有了「哪一枚才是删除范围」的第二把尺，本卡那三句同屏矛盾就是这么来的。\n" +
                "复算：grep -n 'internal data class CourseGroup(' -A8 app/src/main/java/$PAGE",
            1,
            occurrences(body, "List<Course>"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：那一枚字段逐字 `val fragments: List<Course>,` 恰好一处（管理页）。\n" +
                "复算：grep -n 'val fragments: List<Course>,' app/src/main/java/$PAGE",
            1,
            occurrences(code, "val fragments: List<Course>,"),
        )
    }

    // ─────────────── ⑦ 内核纯度：判据函数体不读环境 ───────────────

    @Test
    fun `判据内核零android零时钟零设备度量`() {
        val raw = readMainSource(KERNEL_PATH)
        val code = blankCommentsKeepingLiterals(raw)

        for (needle in listOf(
                "import android", "import androidx", "Build.VERSION",
                "System.currentTimeMillis", "SystemClock", "LocalDate.now", "LocalDateTime",
                "TimeZone", "ZoneId", "Calendar.", "DisplayMetrics", "Configuration",
                "Context", "remember(", "mutableStateOf",
            )) {
            assertEquals(
                "**钉法 = 数出现次数（抹注释后）**：`$needle` 在内核里必须 0 处 —— 本仓明文规矩：判据函数体" +
                    "不读时钟 / 时区 / 权限 / 设备度量，也不许把 `Build.VERSION` 写进判据；这些一律由调用点" +
                    "当参数递进来（这一枚文件是纯 JVM 可单测的根据）。\n" +
                    "复算：grep -n '$needle' app/src/main/java/$KERNEL_PATH",
                0,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "反欺骗（不许把上面那串 0 当成「文件被搬空」）：内核那唯一一枚 import 必须逐字在场",
            1,
            occurrences(code, "import com.buaa.schedule.domain.model.Course"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：内核的 import 行恰好一枚 —— 除 `Course` 之外什么都不 import，" +
                    "才谈得上「零 android 运行面、直接 JVM 单测」。多一枚要先回答它读的是哪一档环境",
            1,
            code.lineSequence().count { it.trim().startsWith("import ") },
        )
        // 判据的三枚入参：过滤态与「按的是哪一种删除」都必须是参数，而不是函数体里现取
        for ((param, who) in listOf(
                "    wholeGroupFragments: List<Course>," to "整组片段（调用点交进来的那一枚）",
                "    query: String," to "过滤态原文（搜索框里写着什么）",
                "    request: GroupDeletionRequest," to "用户按的是哪一档删除",
            )) {
            assertEquals(
                "**钉法 = 逐字节 + 数出现次数**：判据的三枚入参逐字在场（「$who」= `$param` 恰好一处）。" +
                    "三枚都必须待在**参数表**里：过滤态或档位一旦改成函数体里现取，那一手就得去读环境" +
                    "（组合状态 / 某枚旗标），既不再是纯 JVM 判据，也让「删哪几段」在同屏长出第二处答案",
                1,
                occurrences(code, param),
            )
        }
    }

    // ─────────────── ⑧ 红线：VM 与仓储层一字未动 ───────────────

    @Test
    fun `删除入口的对外形状没被顺手改 VM那一侧不认识判据`() {
        val vm = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val repo = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_REPOSITORY))
        val code = readAllMainSources().mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        for ((needle, expected) in listOf(
                "suspend fun deleteCourseGroup(courses: List<Course>): Boolean {" to 1,
                "suspend fun deleteCourseGroupAndAwait(courses: List<Course>): Boolean {" to 1,
                "val snapshot = repository.deleteCourseGroup(courses)" to 1,
                "UndoManager.pushDeleteGroup(snapshot.courses, snapshot.reminders)" to 1,
            )) {
            assertEquals(
                "**钉法 = 数出现次数**：VM 那一侧的删除入口形状必须逐字在场（`$needle` 恰好 $expected 处，" +
                    "`$SCHEDULE_VIEW_MODEL`）。本卡只改「谁递哪一组进来」，不碰 VM 的签名、早退、" +
                    "压栈那一族（T121 / T127 / T124b 的账）。\n" +
                    "复算：grep -Fn '$needle' app/src/main/java/$SCHEDULE_VIEW_MODEL",
                expected,
                occurrences(vm, needle),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：仓储层那一手仍然只是「把递进来的那些行删掉、交回快照」——" +
                "签名逐字在场（`$SCHEDULE_REPOSITORY`）。它不该、也没有长出按组键删、按学期删的第二把尺。\n" +
                "复算：grep -n 'suspend fun deleteCourseGroup' app/src/main/java/$SCHEDULE_REPOSITORY",
            1,
            occurrences(repo, "suspend fun deleteCourseGroup(courses: List<Course>): CourseGroupSnapshot {"),
        )
        for (needle in listOf("deletion.fragments", "courseGroupDeletionScope", "GroupDeletionRequest")) {
            assertEquals(
                "**钉法 = 数出现次数**：VM 不认识这一页的判据（`$needle` 在 VM 里 0 处）—— " +
                    "范围判定留在 UI + domain，删完念什么、撤销栈里压什么照旧由 VM 那三拍决定",
                0,
                occurrences(vm, needle),
            )
        }
        val callers = code.filter { occurrences(it.value, "viewModel.deleteCourseGroupAndAwait(") > 0 }
            .keys.toList().sorted()
        assertEquals(
            "**钉法 = 读形状（落点册子）**：整组删除的 UI 入口全仓恰好落在管理页一枚文件" +
                "（册子的键是相对 `com/buaa/schedule/` 的，故为 `$PAGE_REL`）；" +
                "枚数（下一格）与落点（这一格）是两把尺 —— 只钉落点会放过「同一文件里调两回」，" +
                "而第二回必然删不到 ⇒ 屏上念的那句与真正发生的那件事又分家。\n" +
                "复算：grep -rn 'viewModel.deleteCourseGroupAndAwait(' app/src/main --include='*.kt'",
            listOf(PAGE_REL),
            callers,
        )
        assertEquals(
            "**钉法 = 数出现次数**：`viewModel.deleteCourseGroupAndAwait(` 全仓枚数恰好一枚",
            1,
            code.values.sumOf { occurrences(it, "viewModel.deleteCourseGroupAndAwait(") },
        )
    }

    // ---- 源码核对小工具（与本仓同族守卫一套写法：CourseGroupDeletionWiringGuardTest / CourseUnitSingleSourceGuardTest）----

    /** 一枚 `{`…`}` 的体：开括号位（绝对偏移，用来把命中换算成行号）+ 中间那一段 */
    private class Extracted(val body: String, val openBrace: Int)

    /** 从 [signature] 起的那枚函数：取签名之后第一个 `{` 到与它配平的那个 `}` 之间那一段体 */
    private fun functionBody(source: String, signature: String, who: String): Extracted {
        val hits = indexOfAll(source, signature)
        assertEquals("签名 `$signature` 在 $who 里应当恰好一处，实际 ${hits.size} 处", 1, hits.size)
        val open = source.indexOf('{', hits.first())
        check(open > 0) { "$who 的签名之后找不到 `{`：函数体换了写法，本守卫要跟着改" }
        return braceBodyFrom(source, open)
    }

    /** 从已知开括号位 [open] 起按花括号配平（跳过字符串与字符字面量）取出那一段体 */
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
                    if (depth == 0) return Extracted(source.substring(open + 1, index), open)
                }
            }
            index++
        }
        throw IllegalStateException("花括号没配平：从偏移 $open 起切不出一段体")
    }

    /** 从已知开圆括号位 [open] 起按括号配平取出那一段（data class 的参数表用它） */
    private fun parenBodyFrom(source: String, open: Int): String {
        var depth = 0
        var index = open
        var inString = false
        while (index < source.length) {
            val c = source[index]
            when {
                inString -> when {
                    c == '\\' -> index++
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
            index++
        }
        throw IllegalStateException("圆括号没配平：从偏移 $open 起切不出一段参数表")
    }

    /** 那一处落在原文的第几行（失败消息要把人带到那一处；needle 本身一律不写行号） */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

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

    /** 全仓 main 源码：相对 `com/buaa/schedule/` 的路径 → 内容（扫整棵是为了钉"落点只许这一枚文件"） */
    private fun readAllMainSources(): Map<String, String> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到 $root：包结构挪过家，本守卫要跟着改", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val out = linkedMapOf<String, String>()
        files.forEach {
            val relative = root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/')
            check(!out.containsKey(relative)) { "main 源码里有同一路径 $relative，建册子会读错份" }
            out[relative] = it.readText()
        }
        assertEquals("扫 $root 的文件数与建出来的册子条目数不等", files.size, out.size)
        assertTrue("扫 $root 扫出 0 份文件", out.isNotEmpty())
        return out
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
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（失败消息里报的行号因此可信）。
     * 本守卫尤其需要这一步：管理页与内核的 KDoc 里**逐字抄着**病灶那几形
     * （`creditsByCourse(filtered)`、`deleteCourseGroupAndAwait(target.fragments)` 都在账里），
     * 不抹注释的话那些"0 处"格子会数出凭空的第二处。
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
        /** 相对 `app/src/main/java/` 的键：`readMainSource()` 用它 */
        const val PAGE = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val KERNEL_PATH = "com/buaa/schedule/domain/schedule/CourseGroupDeletionScope.kt"
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val SCHEDULE_REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"

        /** 相对 `app/src/main/java/com/buaa/schedule/` 的键：`readAllMainSources()` 建册子用它 */
        const val KERNEL_REL = "domain/schedule/CourseGroupDeletionScope.kt"
        const val PAGE_REL = "ui/course/CourseManagementScreen.kt"

        const val MODAL_ANCHOR = "ModalTransition(payload = pendingDelete) {"
        const val DELETION_DECLARATION = "val deletion = courseGroupDeletionScope("
        const val CONFIRM_TEXT_CALL = "Text(text = deleteGroupConfirmText(deletion, state.timeSlots))"
        const val GROUP_READ_VERDICT = "val deleted = viewModel.deleteCourseGroupAndAwait(deletion.fragments)"
        const val VISIBILITY_FILTER_LINE =
            ".filter { group -> group.fragments.any { courseFragmentMatchesQuery(it, keyword) } }"
        const val GROUP_COURSES_SIGNATURE =
            "internal fun groupCourses(courses: List<Course>, query: String): List<CourseGroup> {"
        const val COURSE_GROUP_DECLARATION = "internal data class CourseGroup("
        const val MATCH_PREDICATE_SIGNATURE =
            "fun courseFragmentMatchesQuery(course: Course, keyword: String): Boolean {"
        const val 乙档句子 = "\"只删除这门课的 \${scope.announcedFragmentCount} 个片段\$where\""
    }
}
