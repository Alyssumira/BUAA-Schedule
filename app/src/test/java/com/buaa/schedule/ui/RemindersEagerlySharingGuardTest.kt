package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `reminders` 那一枚 `SharingStarted.Eagerly` 的守卫（T373，乙档）。
 *
 * 钉的是什么：`ScheduleViewModel` 那四枚 `stateIn` 里「三枚 WhileSubscribed ＋ 一枚 Eagerly」这个
 * **有意的不对称**，以及同一段 KDoc 里那几句实话。这枚差异长在一枚属性上、不写在任何接口里，
 * 后来人看见「全站就它一档」最容易顺手归一 —— 而归一之后盘面上什么都照常跑（本卡第 2 问量出来
 * 稳态净增量≈0），所以没有仪器拦着的话，这一档会从「有意为之」静默变成「没人知道为什么」。
 *
 * 刀法照同族那几枚源码形状守卫：读工作树的源文本、匹配前先抹整行注释（本卡的 KDoc 里就逐字写着
 * `WhileSubscribed(5_000)` 与 `Eagerly` 这两串，连注释一起扫会把尺读胖）、锚点找不到就 `check` 抛出去
 * （静默跳过等于没有守卫）、零 android、零 assumeTrue。
 *
 * 六格分工：
 * - ①② 档位本体：四枚 `started` 行、Eagerly 恰好一枚且归 `reminders`、其余三枚逐字同形；
 *   以及属性体仍以 `initialValue = emptyList()` 起步（「不是就绪闸门」那句押的就是它）。
 * - ③ 说明：KDoc 里那几句（差异是有意、不是就绪闸门、代价、可以改回的条件、守卫点名）一句不许少，
 *   且被换掉的那行假注释（「必须已就绪」）不许长回来。
 * - ④⑤⑥ 三格钉的是**说明所押的前提**：上游还是 Room 失效驱动的整表流（否则「重放 0 档」要重算）、
 *   编辑器挂载闸门必须念上提醒那一半（T373 那一版钉的是「今天还没有」，T381① 落地之后按它自己 KDoc
 *   写好的方向翻成「必须在场」，翻面记录与判据见 ⑤ 的 KDoc）、
 *   StateFlow 级读者仍只一枚且在根组合（否则「净增量≈0」与「换档省不下东西」的结论都要重算）。
 *   这三格是「朝窄」那一档：它们现在绿，等真正的变化发生时必须红 —— 红了就去改散文，不许去改判据。
 */
class RemindersEagerlySharingGuardTest {

    // ---- ① 档位本体：3∶1 的显式差异 ----

    /**
     * 四枚 `stateIn` 的 `started` 行里，`Eagerly` 只许一枚，而且必须是 `reminders` 那一枚。
     *
     * 归属怎么认：从档位行往上找**类成员那一档**的属性声明（正好四枚空格缩进的 val），
     * 不认方法体内层的 val —— `uiState` 那条链的 map 体内还有两枚内层 val，
     * 按「最近一个 val」认会把 `uiState` 的档位记到别人名下。
     */
    @Test
    fun remindersIsTheOnlyEagerlyStateIn() {
        val lines = codeOnly(source(SCHEDULE_VIEW_MODEL)).lines()
        val sites = ArrayList<Pair<String, String>>()
        var owner = ""
        for (line in lines) {
            val member = MEMBER_PROPERTY.find(line.trimStart())
            if (line.startsWith(MEMBER_INDENT) && member != null) owner = member.groupValues[1]
            val trimmed = line.trim()
            if (trimmed.startsWith(STARTED_PREFIX)) sites += owner to trimmed
        }
        assertEquals(
            "ScheduleViewModel 里带 SharingStarted 的 stateIn 只许四枚（多一枚要来看它该不该也进这册子，" +
                "少一枚要来看那枚属性是不是没了）。现量：" + sites,
            4, sites.size,
        )
        val eagerly = sites.filter { pair -> pair.second.contains(EAGERLY) }
        assertEquals(
            "Eagerly 只许一枚：这一枚是有意为之的例外，不是全站的第三档；" +
                "再多一枚就得回本卡第 2 问那笔代价账重新量一遍。现量：" + eagerly,
            1, eagerly.size,
        )
        assertEquals(
            "那一枚 Eagerly 必须挂在 reminders 上（现在是 " + eagerly[0].first + "）：" +
                "换宿主就得说明新宿主为什么也要常驻",
            REMINDERS, eagerly[0].first,
        )
        val warm = sites.filter { pair -> !pair.second.contains(EAGERLY) }
        assertEquals(
            "另外三枚都必须是 WhileSubscribed(5_000) —— 3∶1 这个比例本身就是被钉的东西。现量：" + warm,
            3, warm.size,
        )
        for (pair in warm) {
            assertEquals(
                "WhileSubscribed 那三枚的档位行要逐字同形（数值漂了就是另一档口径，不是本卡范围）：" +
                    pair.first + " 那一枚是「" + pair.second + "」",
                WHILE_SUBSCRIBED_LINE, pair.second,
            )
        }
        assertEquals(
            "WhileSubscribed 那三枚的归属册子（换了宿主就要说明为什么换）：",
            listOf("allSemesters", "importHistory", UI_STATE).sorted(),
            warm.map { pair -> pair.first }.sorted(),
        )
    }

    // ---- ② 属性体：仍以空列表起步（「不是就绪闸门」那句话的事实基础） ----

    @Test
    fun eagerlyBlockStillStartsFromAnEmptyInitialValue() {
        val vm = codeOnly(source(SCHEDULE_VIEW_MODEL))
        val at = vm.indexOf(REMINDERS_ANCHOR)
        check(at >= 0) { "ScheduleViewModel 里已经没有「$REMINDERS_ANCHOR」：这一枚换写法了，本守卫要跟着改" }
        val end = vm.indexOf(BLOCK_TAIL, at)
        check(end >= at) { "reminders 那枚 stateIn 的收尾括号找不着：属性写法换过了，本守卫要重新切窗" }
        val block = vm.substring(at, end)
        assertEquals(
            "属性体里 Eagerly 那一行只许一处（现在是 " + occurrences(block, EAGERLY_LINE) + " 处）",
            1, occurrences(block, EAGERLY_LINE),
        )
        assertEquals(
            "initialValue = emptyList() 只许一处且必须在 —— 有它才有「空列表不等于没有那一行」那一档，" +
                "KDoc 里「不是就绪闸门」那句押的就是这一格",
            1, occurrences(block, EMPTY_INITIAL_LINE),
        )
        assertEquals(
            "作用域仍然是 viewModelScope：换成别的寿命（应用级）就不是「这一枚 ViewModel 常驻」这笔账了",
            1, occurrences(block, VM_SCOPE_LINE),
        )
    }

    // ---- ③ 说明：KDoc 那几句不许被删，被换掉的假注释不许长回来 ----

    @Test
    fun kdocAboveRemindersStillTellsTheTruth() {
        val raw = source(SCHEDULE_VIEW_MODEL)
        val propertyAt = raw.indexOf(REMINDERS_ANCHOR)
        check(propertyAt >= 0) { "ScheduleViewModel 里已经没有「$REMINDERS_ANCHOR」：本守卫的靶子没了" }
        val docEnd = raw.lastIndexOf(COMMENT_TAIL, propertyAt)
        check(docEnd >= 0) { "reminders 之前找不到注释收尾：那整段说明被删了" }
        val docStart = raw.lastIndexOf(COMMENT_HEAD, docEnd)
        check(docStart in 0 until docEnd) { "reminders 之前没有块注释那一整段：说明换形式了，本守卫要跟着改切法" }
        val gap = raw.substring(docEnd + COMMENT_TAIL.length, propertyAt)
        assertTrue(
            "KDoc 收尾与属性之间隔了别的东西（" + gap.trim() + "）：本守卫钉的是紧邻其上的那一段",
            gap.isBlank(),
        )
        val doc = raw.substring(docStart, docEnd + COMMENT_TAIL.length)
        val missing = ArrayList<String>()
        for (marker in DOC_MARKERS) {
            if (!doc.contains(marker)) missing += marker
        }
        assertTrue(
            "reminders 那枚 Eagerly 的 KDoc 少了实话里的某一句（删哪句都有代价）：" +
                "「唯一一枚 Eagerly」与「有意为之」少了它，这一档就退回没人知道为什么；" +
                "「不是就绪闸门」少了它，下一读的人就会把 Eagerly 当成担保而去拆编辑器那侧的判据；" +
                "「代价」少了它，第 2 问那笔账就没有落点；「可以改回」少了它，这一档就变成永久档；" +
                "守卫点名那句少了它，散文与本文件就脱钩。缺的句子：" + missing,
            missing.isEmpty(),
        )
        assertFalse(
            "那行假注释不许长回来（提醒列表必须已就绪）：Eagerly 只能让 Room 那趟读表早到，" +
                "不能让它同步，initialValue 在第一次返回之前就是当前值 —— 这句在盘面上是假的",
            doc.contains(STALE_CLAIM),
        )
    }

    // ---- ④ 前提一：上游还是 Room 失效驱动的整表流（否则「反复重放 0 档」要重算） ----

    @Test
    fun upstreamIsStillTheInvalidationDrivenRoomFlow() {
        val repo = codeOnly(source(SCHEDULE_REPOSITORY))
        assertEquals(
            "ScheduleRepository 里那枚 reminders 流的声明只许一处；两处以上就是两条上游，" +
                "本卡第 2 问的代价账要按新的那本重算：",
            1, occurrences(repo, REPOSITORY_FLOW_DECL),
        )
        assertTrue(
            "reminders 这条流不再挂在 reminderDao.observeAll() 上：换的若是轮询或多源 combine，" +
                "KDoc 里那句「只在表被写之后重查」就不成立了，代价要重新量",
            repo.contains(DAO_OBSERVE_ALL),
        )
        val dao = codeOnly(source(REMINDER_DAO))
        assertTrue(
            "ReminderDao 的 observeAll 不再是那条整表 @Query：失效驱动的口径要重核",
            dao.contains(DAO_QUERY),
        )
        assertTrue(
            "ReminderDao 里 observeAll 的签名漂了（返回类型或名字变了）：本守卫第四格押的就是它交回 Flow",
            dao.contains(DAO_SIGNATURE),
        )
    }

    // ---- ⑤ 前提二（T381① 翻面）：编辑器挂载闸门必须念上提醒那一半 ----

    /**
     * 这一格现在钉的是**已经在场**那一手（T381①，收在册 #660 的主体）：挂载闸门除 `uiState.loading` 与
     * `courseMissing` 之外还念着提醒那一半的判据，而那一半只能由一枚**显式的未就绪位**递过来 ——
     * VM 那枚位以 `false` 起步、全仓只有一处把它翻真、且那一处写在上游链上（不是拿列表长度当就绪）。
     *
     * 判据方向是照 T373 那一版这段 KDoc 写好的路翻的（原文：「将来谁在编辑器侧补上『列表未就绪不许写回』,
     * 这一格必须红 —— 红了要做的不是把这格改松，而是把 `ScheduleViewModel` 里那段 KDoc 的『本卡未做』
     * 改成『已有闸门』，并按新前提重判 Eagerly 还买不买得到东西」）：散文与那枚档位同批改口，
     * 本格则从「不含 reminder 那个 token」翻成「必须含」。**现在摘掉闸门里那一半 ⇒ 这一格红。**
     *
     * 编辑器那两格提醒字段仍然按首帧固化（上面那几条 `ENABLED_INIT_LINE` / `ADVANCE_*` 仍在钉它们），
     * 这不是遗漏而是这一格的前提：正因为字段不回灌，闸门才只能排在挂载之前。
     */
    @Test
    fun editorSideReadinessGateIsOnTheMountGate() {
        val editor = codeOnly(source(COURSE_EDITOR_SCREEN))
        val gateAt = editor.indexOf(EDITING_EXISTING_GATE)
        check(gateAt >= 0) { "CourseEditorScreen 里已经没有「$EDITING_EXISTING_GATE」：写回那一手的形状变了，本守卫要重核" }
        assertEquals(
            "编辑器里那道 isEditingExisting 的门只许一处（现在是 " + occurrences(editor, EDITING_EXISTING_GATE) + " 处）：",
            1, occurrences(editor, EDITING_EXISTING_GATE),
        )
        assertEquals(
            "onSaveReminder 的调用点只许一处，而且必须在 isEditingExisting 那道门之后 —— " +
                "两处或换了宿主都要来看提醒行是被谁写下去的：",
            1, occurrences(editor, REMINDER_CALL),
        )
        assertTrue(
            "写回那一手不再紧跟 isEditingExisting 那道门（400 字符窗里没有 onSaveReminder 调用）：" +
                "提醒行的写入点搬走了，本卡第 1 问那条真路要重新走一遍",
            editor.substring(gateAt, minOf(gateAt + WINDOW, editor.length)).contains(REMINDER_CALL),
        )
        assertTrue(
            "reminderEnabled 的初值取法变了：本守卫钉的是「首帧读到什么就固化成什么」那一档" +
                "（rememberSaveable 的键里没有 reminders 本身）—— 那一档还在，所以闸门必须在挂载之前就拦住",
            editor.contains(ENABLED_INIT_LINE),
        )
        assertTrue(
            "advanceMinutes 的初值取法变了（同上，那一格也是首帧固化）：",
            editor.contains(ADVANCE_INIT_HEAD) && editor.contains(ADVANCE_INIT_BODY),
        )
        val activity = codeOnly(source(MAIN_ACTIVITY))
        val mountAt = activity.indexOf(EDITOR_MOUNT_GATE)
        check(mountAt >= 0) { "MainActivity 里已经没有编辑器挂载闸门「$EDITOR_MOUNT_GATE」：目的地写法变了，本守卫要重核" }
        val mountLine = activity.substring(mountAt, activity.indexOf('\n', mountAt))
        assertTrue(
            "挂载闸门不再念 reminder 那一半了 —— 就绪位被摘掉，在册 #660 的主体就回来了：首帧的 " +
                "emptyList() 会被两格 rememberSaveable 固化成用户设置，再由保存那一趟无条件写回。" +
                "现读那一行：「" + mountLine.trim() + "」",
            mountLine.contains(REMINDER_TOKEN, ignoreCase = true),
        )
        assertEquals(
            "挂载闸门那一行只许一处（在册的 T381① 那一形）—— 多一处就是有人另起了一扇不判就绪位的门：" +
                occurrences(activity, EDITOR_MOUNT_GATE) + " 处",
            1, occurrences(activity, EDITOR_MOUNT_GATE),
        )
        assertTrue(
            "闸门那一半不再问内核（`$MOUNT_GATE_LOCAL` 读不到）：判据本体挪走了或就地重算了一遍 ⇒ " +
                "三档表就成了没人念的第二本账",
            activity.contains(MOUNT_GATE_LOCAL),
        )
        assertEquals(
            "闸门那一半的算式只许一处（实到 " + occurrences(activity, MOUNT_GATE_LOCAL) + " 处）：",
            1, occurrences(activity, MOUNT_GATE_LOCAL),
        )
        assertEquals(
            "根组合里那枚就绪位的订阅只许一处（实到 " + occurrences(activity, REMINDER_ROWS_READER) +
                " 处）：订阅的是那枚布尔而不是整表，多一枚读者就要重数 ⑥ 那本的册子",
            1, occurrences(activity, REMINDER_ROWS_READER),
        )
        val vm = codeOnly(source(SCHEDULE_VIEW_MODEL))
        assertEquals(
            "就绪位的声明只许一处，而且必须以「未就绪」起步（实到 " + occurrences(vm, LISTED_DECL) + " 处）：" +
                "「列表是空的」与「列表还没发过第一行」在盘面上是两档，起点写成 true 就是把它折成一档",
            1, occurrences(vm, LISTED_DECL),
        )
        assertEquals(
            "把它翻真的写点全文件只许一处，且必须写在 reminders 那条上游链上（实到 " +
                occurrences(vm, LISTED_FLIP) + " 处）：除上游那一次发射之外谁都不许宣布「已就绪」——" +
                "VM 构造时翻真、或拿 `isNotEmpty()` 当就绪，都是把 #660 那一档又折回去",
            1, occurrences(vm, LISTED_FLIP),
        )
        assertEquals(
            "那枚可变载体在文件里的引用只许三处（声明 / 对外 asStateFlow / 上面那一处翻真，实到 " +
                occurrences(vm, LISTED_HOST) + " 处）：第四处就是第二个写点",
            3, occurrences(vm, LISTED_HOST),
        )
        assertEquals(
            "编辑器取 initialReminder 那一行只许一处（往下递的列表读者多一枚就要重数读者册子）：",
            1, occurrences(activity, INITIAL_REMINDER_LINE),
        )
    }

    // ---- ⑥ 前提三：StateFlow 级读者仍只一枚、且在根组合里（「净增量≈0」押的就是它） ----

    @Test
    fun stateFlowHasExactlyOneRootCompositionReader() {
        val dir = findMainJavaDir()
        val files = dir.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()
            .sortedBy { file -> file.path }
        assertTrue("$dir 下一枚 .kt 都没有，扫描范围取错了", files.isNotEmpty())
        val hits = ArrayList<String>()
        for (file in files) {
            for (match in STATE_FLOW_READER.findAll(codeOnly(file.readText()))) {
                hits += file.name + match.value
            }
        }
        assertEquals(
            "reminders 这枚 StateFlow 在 main 侧的读者只许一枚：本卡第 2 问那句「净增量≈0」、" +
                "以及「换成 WhileSubscribed 也省不下什么」的结论，押的全是唯一读者在根组合里从不离场。" +
                "读者册子变了（多一枚、或长出 .value 那种非组合取法）就要按新形状重算第 2 问，" +
                "再回来改这格 —— 不许反过来把这格放松。现量：" + hits,
            1, hits.size,
        )
        assertEquals(
            "那一枚读者必须在 MainActivity 的根组合里（现在是 " + hits[0] + "）：",
            MAIN_ACTIVITY_NAME + ROOT_READER_TAIL, hits[0],
        )
    }

    // ---- 靶子定位与词法小工具（同族刀法；拼串一律 buildString，不造字符数组那一族） ----

    /** 整行注释抹成空行、行号不变：档位与形状只认代码那一栏 */
    private fun codeOnly(src: String): String = buildString {
        for (line in src.lineSequence()) {
            val trimmed = line.trimStart()
            val comment = trimmed.startsWith(LINE_COMMENT) || trimmed.startsWith(BLOCK_COMMENT_HEAD) ||
                trimmed.startsWith(COMMENT_TAIL) || trimmed.startsWith(STAR)
            if (comment) {
                append('\n')
            } else {
                append(line)
                append('\n')
            }
        }
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话本守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { candidate -> File(dir, candidate) }
                .firstOrNull { candidate -> candidate.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val SCHEDULE_REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val REMINDER_DAO = "com/buaa/schedule/data/local/ReminderDao.kt"
        const val COURSE_EDITOR_SCREEN = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"

        const val REMINDERS = "reminders"
        const val UI_STATE = "uiState"
        const val REMINDERS_ANCHOR = "val reminders = repository.reminders"
        const val STARTED_PREFIX = "started = SharingStarted."
        const val EAGERLY = "Eagerly"
        const val EAGERLY_LINE = "started = SharingStarted.Eagerly,"
        const val WHILE_SUBSCRIBED_LINE = "started = SharingStarted.WhileSubscribed(5_000),"
        const val VM_SCOPE_LINE = "scope = viewModelScope,"
        const val EMPTY_INITIAL_LINE = "initialValue = emptyList(),"
        const val BLOCK_TAIL = "\n    )"
        const val WINDOW = 400

        const val LINE_COMMENT = "//"
        const val BLOCK_COMMENT_HEAD = "/*"
        const val COMMENT_HEAD = "/**"
        const val COMMENT_TAIL = "*/"
        const val STAR = "*"

        /** 被换掉的那行假注释里独一枚的说法：它一旦长回来，第 2、3 问的读数就没人念了 */
        const val STALE_CLAIM = "必须已就绪"

        const val REPOSITORY_FLOW_DECL = "val reminders: Flow<List<ReminderSetting>> ="
        const val DAO_OBSERVE_ALL = "reminderDao.observeAll()"
        const val DAO_QUERY = "@Query(" + "\"" + "SELECT * FROM reminders" + "\"" + ")"
        const val DAO_SIGNATURE = "fun observeAll(): Flow<List<ReminderEntity>>"

        const val EDITING_EXISTING_GATE = "if (isEditingExisting) {"
        const val REMINDER_CALL = "onSaveReminder("
        const val ENABLED_INIT_LINE =
            "var reminderEnabled by rememberSaveable(requestedCourseId) { " +
                "mutableStateOf(initialReminder?.enabled ?: ReminderSetting.DEFAULT_ENABLED) }"
        const val ADVANCE_INIT_HEAD = "var advanceMinutes by rememberSaveable(requestedCourseId) {"
        const val ADVANCE_INIT_BODY =
            "mutableStateOf(initialReminder?.advanceMinutes?.toString() ?: " +
                "ReminderSetting.DEFAULT_ADVANCE_MINUTES.toString())"
        const val EDITOR_MOUNT_GATE = "if (!uiState.loading && !courseMissing && reminderGateOpen) {"
        const val REMINDER_TOKEN = "reminder"

        /**
         * T381① 在 `reminders` 那条链上插了一枚 `onEach`（就绪位只由上游那次发射翻真），
         * 锚点因此从「…stateIn(」退到那一行开头；②③ 两格的切窗与三枚枚数针一字未动。
         */
        const val MOUNT_GATE_LOCAL =
            "val reminderGateOpen = EditorReminderMountGate.allowsMount(course != null, reminderRowsListed)"
        const val REMINDER_ROWS_READER =
            "val reminderRowsListed by viewModel.reminderRowsListed.collectAsState()"
        const val LISTED_DECL = "private val _reminderRowsListed = MutableStateFlow(false)"
        const val LISTED_FLIP = ".onEach { _reminderRowsListed.value = true }"
        const val LISTED_HOST = "_reminderRowsListed"
        const val INITIAL_REMINDER_LINE =
            "initialReminder = course?.let { c -> reminders.firstOrNull { it.courseId == c.id } },"

        /** 类成员那一档的缩进（四枚空格）：内层 lambda 的 val 是八枚，据此把归属认错人这一档挡掉 */
        const val MEMBER_INDENT = "    val "
        val MEMBER_PROPERTY = Regex("""val ([A-Za-z_][A-Za-z0-9_]*)\s*=""")

        /** StateFlow 级取法：组合订阅与直读都算，repository 那枚上游自己的 stateIn 不算 */
        val STATE_FLOW_READER = Regex("""\.reminders\.(collectAsState\(\)|value|collect\(|first\(|firstOrNull)""")

        /** ⑥ 那一枚命中的组成：文件名 ＋ 匹配到的那段取法文本 */
        const val MAIN_ACTIVITY_NAME = "MainActivity.kt"
        const val ROOT_READER_TAIL = ".reminders.collectAsState()"

        /** KDoc 里那几句的锚：少一句就说明这段说明被人改回一行白话 */
        val DOC_MARKERS = listOf(
            "唯一一枚 Eagerly",
            "有意为之",
            "不是就绪闸门",
            "代价",
            "可以改回",
            "RemindersEagerlySharingGuardTest",
        )
    }
}
