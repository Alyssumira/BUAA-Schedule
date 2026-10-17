package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #536 F2 的接线守卫：一枚后台链的读载体**在入口造一次、链上各步当参数消费**，
 * 任何一步都不许回头再查它已经装着的那几张表。
 *
 * ## 为什么这张卡需要一枚源码形状守卫
 *
 * 判据本体（[com.buaa.schedule.domain.schedule.ChainReadSnapshot.covers]）是纯 JVM 的，
 * 格子在 `domain/schedule/ChainReadSnapshotTest`；「整链只读一遍」在冷启动那条路上
 * 数得出来（注入缝上的 `loadReadSnapshot` 被授几次），钉在 `widget/ColdStartRebuildTest`。
 * 剩下的那一半——**四步真的接上了入口那一份、而不是各自回头查库**——只在有 Context
 * 的生产接线里成立，而 `:app` 的 JVM 测试面只有 junit（没有 Robolectric、没有能在 JVM 上
 * 跑起来的 Room 缝，`BackupSemesterRestorePolicy.kt:108` 那句写得很清楚）。
 * 所以这里按源码形状核对：改坏接线（少传、漏传、把某一步换回裸调用）在盘上是一个字节的
 * 事，跑一次要设备，而跑不到的那条路恰恰是本卡全部的收益面。
 *
 * ## 每一格用的钉法（按 `docs/TESTING.md` 那三种口径点名）
 *
 * - **逐字节**：单行字面量实参表（`snapshot ?: …loadChainReadSnapshot()`、三处入口的
 *   `readChainSnapshotOrNull(…)`、`WidgetDataSynchronizer` 那三行回退式）。改一个字符就红。
 * - **数出现次数**：全仓调用点册子（哪几个文件引用了 `ChainReadSnapshot`、
 *   `loadChainReadSnapshot()` / `readChainSnapshotOrNull(` 各在哪个文件出现几遍）。
 *   多一处重复读、或少一处消费，都在这格里红。
 * - **读形状**：花括号配平切出函数体后比先后次序（入口那一句排在被它喂的那几步之前；
 *   载体的读取排在闸门那条 `return Outcome.Skipped` **之后**）。
 *
 * 报错消息里不写行号（只写文件名与切出来的那段）—— 行号会随注释增删漂，写进消息里就是
 * 一枚每改一次文档就得重钉的假判据（T118 为这一类错过一次）。
 *
 * ## 反过来说明本卡没收的东西
 *
 * `getAllSemesters()` 与组件快照按 key 排产的那 K 趟课程查询（K = 学期数 + 1）**不在**负册里：
 * 它们在链上各只被读一遍，是 T7 定下的排产口径。这里若把它们钉成"不许出现"，
 * 等于替下一张卡把 丙 档的结论预先写死。
 */
class ChainReadSnapshotWiringGuardTest {

    private val snapshotFile = "com/buaa/schedule/domain/schedule/ChainReadSnapshot.kt"
    private val repositoryFile = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
    private val backgroundSyncFile = "com/buaa/schedule/widget/BackgroundSync.kt"
    private val coldStartFile = "com/buaa/schedule/widget/ColdStartRebuild.kt"
    private val schedulerFile = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"
    private val synchronizerFile = "com/buaa/schedule/widget/WidgetDataSynchronizer.kt"
    private val bootReceiverFile = "com/buaa/schedule/reminder/BootReceiver.kt"
    private val viewModelFile = "com/buaa/schedule/ui/ScheduleViewModel.kt"
    private val settingsScreenFile = "com/buaa/schedule/ui/settings/SettingsScreen.kt"

    /** ① 载体本体（逐字节 + 数出现次数）：四枚参数、一枚判据、零 android、零派生字段 */
    @Test
    fun carrierItselfStaysPureJvmWithFourParameters() {
        val raw = readMainSource(snapshotFile)
        val code = withoutComments(raw)

        // 纯 JVM：一个 android/androidx import 都不许有（KDoc 里提到的那些词会被抹掉，
        // 所以这里吃的是 blankCommentsAndLiterals 那份，整份文件的 import 段才是真代码）
        val blanked = blankCommentsAndLiterals(raw)
        assertEquals("载体里出现了 android import：" + importLines(blanked, "android"), 0, importLines(blanked, "android").size)
        assertEquals("载体里出现了 androidx import：" + importLines(blanked, "androidx"), 0, importLines(blanked, "androidx").size)

        // 参数表恰好四枚，而且**没有派生字段**（docs/derived-field-audit.md §6.0：
        // 值 = f(同表另一枚参数) 不许有第三条落点，这里选的第三条是"根本不做成字段"）
        for (needle in listOf(
            "val semester: Semester?,",
            "val courses: List<Course>,",
            "val timeSlots: List<TimeSlot>,",
            "val remindersByCourseId: Map<Long, ReminderSetting>,",
        )) {
            assertTrue("参数表少了这一枚：$needle\n${headerOf(code)}", code.contains(needle))
        }
        for (derived in listOf("val semesterStart", "val totalWeeks", "val startLocalDate", "val weekCount")) {
            assertEquals("载体长出了派生字段 $derived（每个 copy 站点都得重算的那种债）", 0, occurrences(code, derived))
        }

        // 判据只有一枚，而且长在类体里（不是扩展函数、不写在调用点）
        assertEquals("covers 的判据写不止一份", 1, occurrences(code, "fun covers(row: Semester?)"))
        assertEquals("covers 之外的第二枚判据（判据只能吃 termCode）", 1, occurrences(code, "termCode == row.termCode"))
        // 判据本体不读钟、不碰权限、不碰 Context（这三行是"纯函数"那句承诺的实现侧证据）
        for (forbidden in listOf("LocalDate.now(", "System.currentTimeMillis", "Build.VERSION", "Context")) {
            assertEquals("判据本体里出现 $forbidden", 0, occurrences(blanked, forbidden))
        }
    }

    /** ② 仓储层那一份读（逐字节 + 数出现次数）：一份载体 = 四张表各一趟，多一趟都算红 */
    @Test
    fun repositoryBuildsTheCarrierWithExactlyFourReads() {
        val body = normalize(functionBlock(withoutComments(readMainSource(repositoryFile)), "suspend fun loadChainReadSnapshot()"))

        for (needle in listOf(
            "val semester = getCurrentSemester()",
            "courses = getDisplayCourses(semester)",
            "timeSlots = getTimeSlots()",
            "remindersByCourseId = getReminders().associateBy { it.courseId },",
        )) {
            assertTrue("这一趟读不在载体里（或换了写法，判据就数不出来了）：$needle\n$body", body.contains(needle))
        }
        // 同一张表在这一份里只许被读一遍：多加一遍就是"载体自己先把这张卡的病犯了一遍"
        assertEquals("学期行被读了不止一遍", 1, occurrences(body, "getCurrentSemester()"))
        assertEquals("节次表被读了不止一遍", 1, occurrences(body, "getTimeSlots()"))
        assertEquals("展示口径的课程被读了不止一遍", 1, occurrences(body, "getDisplayCourses("))
        assertEquals("提醒册子被读了不止一遍", 1, occurrences(body, "getReminders("))
        // 这一份不越界：学期册与按 key 排产的那 K 趟课程是 丁 档的账，不在这里预取
        assertEquals("载体替全链预取了学期册（桌面上一个组件都没放的用户也要多付一趟）", 0, occurrences(body, "getAllSemesters()"))

        // 构造点全仓只有一处：别处再 `ChainReadSnapshot(` 就是第二份"读四张表"的实现
        // （册子里另一枚出现的 `ChainReadSnapshot(` 是类自己的 header，那一枚算声明不算构造）
        //
        // ⚠️ T297③补（地雷 1）：针法原本是 `occurrences(blanked, "ChainReadSnapshot(")`，
        // 而那串恰好是 `loadChainReadSnapshot(` 的后缀 —— 于是全仓每一处**读**都被当成**造**：
        // 仓储层数成 2（它是 1 造 + 1 读）、BackgroundSync 数成 3、ClassProgressScheduler 与
        // ColdStartRebuild 各数成 1，期望名册那两格永远对不上。相减才是构造点。
        // 同一个 `assertEquals` 还有第二枚地雷：`constructed.entries` 是 `List<Map.Entry>`，
        // 而期望那份 `listOf(a to 1, b to 1)` 是 `List<Pair>` —— 两枚永不相等的两个 List，
        // 这一格从写下的那一刻起就不可能绿（`equals` 逐元素比，`Pair != Map.Entry`）。
        // 判据本身一个字没动：还是"除类 header 之外全仓只许仓储层构造一次"。
        // 第三枚地雷（编译级）跟着一起修：原本这里写 `constructed.entries.map { it.key to it.value }`
        // 再和一份 `List<Pair>` 比 —— K2 在 `entries`（`Set<MutableMap.Entry>`）上推不出 `map` 的 R，
        // 而那两份 List 本来就永不相等。现在按**键集**比、再逐枚按**构造枚数**比，两条都只判同一件事。
        val constructed = censusPerFile { blanked ->
            occurrences(blanked, "ChainReadSnapshot(") - occurrences(blanked, "loadChainReadSnapshot(")
        }
        assertEquals(
            "载体的构造点集合变了（除类自己那枚 header，全仓只许仓储层这一处构造）：\n${constructed.keys.toList().sorted()}",
            listOf(
                "$MAIN_PREFIX/$repositoryFile",
                "$MAIN_PREFIX/$snapshotFile",
            ).sorted(),
            constructed.keys.toList().sorted(),
        )
        for ((file, hits) in constructed) {
            assertEquals("$file 里载体的构造点不止一枚（每处只许一枚：类文件那枚是声明、仓储层那枚是构造）", 1, hits)
        }
    }

    /** ③ 三处入口各造一次（逐字节 + 读形状的顺序） */
    @Test
    fun everyChainEntryBuildsTheCarrierExactlyOnce() {
        // 开机那条链：一次唤醒锁里四步，载体排在吃它的那三步之前
        val boot = withoutComments(readMainSource(bootReceiverFile))
        assertEquals("BootReceiver 造了不止一枚载体", 1, occurrences(boot, "val chainSnapshot = readChainSnapshotOrNull(context)"))
        val built = boot.indexOf("val chainSnapshot = readChainSnapshotOrNull(context)")
        for (consumer in listOf(
            "BackgroundSync.rescheduleRemindersAndBells(context, snapshot = chainSnapshot)",
            "BackgroundSync.refreshWidgets(context, snapshot = chainSnapshot)",
            "BackgroundSync.scheduleTomorrowPreview(context, chainSnapshot)",
        )) {
            val at = boot.indexOf(consumer)
            assertTrue("这一步没吃入口那一份（或排在造载体之前）：$consumer", at > built)
        }

        // 改课表那条链：唯一的写库已经落在那之前，收尾读一次、三步共用
        val vm = withoutComments(readMainSource(viewModelFile))
        val tail = normalize(functionBlock(vm, "private suspend fun afterDataChangedInternal()"))
        assertEquals("改课表收尾造了不止一枚载体", 1, occurrences(tail, "readChainSnapshotOrNull(app)"))
        val tailBuilt = tail.indexOf("val chainSnapshot = com.buaa.schedule.widget.readChainSnapshotOrNull(app)")
        assertTrue("收尾第一步没吃载体：$tail", tail.indexOf("rescheduleRemindersAndBells(app, snapshot = chainSnapshot)") > tailBuilt)
        assertTrue("收尾的组件那步没吃载体：$tail", tail.indexOf("refreshWidgets(app, snapshot = chainSnapshot)") > tailBuilt)
        assertTrue("收尾的明日预告那步没吃载体：$tail", tail.indexOf("scheduleTomorrowPreview(app, chainSnapshot)") > tailBuilt)

        // 冷启动那条链：载体只在闸门放行之后才读（跳过的那一轮一趟都不许花）
        val cold = normalize(withoutComments(readMainSource(coldStartFile)))
        val wiring = functionBlock(cold, "suspend fun run(context: Context): Outcome")
        assertEquals(
            "闸门的 Context 入口造了不止一枚载体",
            1,
            occurrences(wiring, "loadReadSnapshot = { context.scheduleRepository().loadChainReadSnapshot() }"),
        )
        val body = chainBodyOf(cold)
        val guard = body.indexOf("if (!decision.rebuild) return Outcome.Skipped")
        val load = body.indexOf("val snapshot = runCatching { loadReadSnapshot() }")
        assertTrue("闸门里找不到那条提前 return：$body", guard >= 0)
        assertTrue(
            "载体的读取排到了闸门放行之前 —— 跳过的那一轮也开始付四张表的钱，这张卡省下来的又花回去：$body",
            load > guard,
        )
        assertTrue("读不到载体没留痕（闸门会把这一轮记成成功）：$body", body.contains("report(\"loadChainReadSnapshot\", it)"))
        assertTrue("上下两半没共用同一枚：$body", body.contains("rebuildRemindersAndBells(report, snapshot)"))
        assertTrue("上下两半没共用同一枚：$body", body.contains("runWidgetSteps(report, snapshot)"))

        // Worker 那条链（改数据后的第四处入口）：onDataChanged 自己造一枚，交给下面两步
        val sync = normalize(withoutComments(readMainSource(backgroundSyncFile)))
        // ⚠️ T400② 同批重钉两处（判据对象一个字没动，动的只是锚点宽度与实参表长度）：
        // `onDataChanged` 现在多收一枚报告口（#675 —— Worker 那条链调它，此前形参表里根本没有那一枚，
        // 于是提醒那一步抛了在这条链上结构上不可见），签名因此换成竖排多行 ——
        // 锚点从 `…onDataChanged(context: Context` 收到 `…onDataChanged(`（normalize 把换行折成一个空格，
        // 旧锚点从写下的那一刻起就切不出函数体）。载体与结论那两枚实参仍然逐枚在册，
        // 只是各自多带一个尾巴上的报告口 —— 递出去的东西只多不少。
        val onDataChanged = functionBlock(sync, "suspend fun onDataChanged(")
        assertTrue("onDataChanged 没造载体：$onDataChanged", onDataChanged.contains("val snapshot = readChainSnapshotOrNull(context)"))
        assertTrue("onDataChanged 里的提醒那步没吃载体：$onDataChanged", onDataChanged.contains("rescheduleReminders(context, onStepFailed, snapshot)"))
        assertTrue("onDataChanged 里的组件那步没吃载体：$onDataChanged", onDataChanged.contains("refreshWidgets(context, hasAnyWidget, snapshot, onStepFailed)"))
    }

    /** ④ 链上每一步吃载体、不回头查（逐字节的回退式 + 四张表的负册） */
    @Test
    fun chainStepsConsumeTheCarrierInsteadOfReReadingTheSameTables() {
        val fallback = "val read = snapshot ?: context.scheduleRepository().loadChainReadSnapshot()"

        val syncCode = withoutComments(readMainSource(backgroundSyncFile))
        for (signature in listOf("suspend fun rescheduleReminders(", "suspend fun scheduleTomorrowPreview(")) {
            val body = normalize(functionBlock(syncCode, signature))
            assertTrue("这一步不再优先吃载体（$signature）：$body", body.contains(fallback))
            assertNoRawTableRead(signature, body)
        }

        val scheduler = withoutComments(readMainSource(schedulerFile))
        // ⚠️ T297③补（地雷 2）：这一手是**表达式体**（`) = rescheduleNextWindow(readClassSwitches = { … },
        // loadWindowInput = load@{ … }, reschedule = { … })`），实参全是 lambda。
        // `functionBlock` 那套"从签名后面第一个 `{` 配平到它的 `}`"在这里只会切到**第一枚实参 lambda**
        // （`readClassSwitches` 那一截，里面只有两枚 prefs 读），而载体那一排在下一枚实参里 ——
        // 于是 `wrapper.contains(fallback)` 从写下的那一刻起永远红。
        // 切法跟着盘面改：按 `loadWindowInput` 那枚注入 lambda 配平取块（先例就是
        // `ClassProgressRescheduleWiringTest` 里的 `balancedBlock(source, "loadWindowInput = load@{")`，
        // 它钉的同一处、用的是同一种切法）。判据对象没变：这一步吃不吃入口那一份载体。
        val wrapper = normalize(lambdaBlock(scheduler, "loadWindowInput = load@{"))
        assertTrue("续排那一步不再优先吃载体：$wrapper", wrapper.contains(fallback))
        assertNoRawTableRead("rescheduleNextWindow", wrapper)

        // 组件快照那一步：三行回退式逐字节钉住，学期码不同那一档仍然各查各的（covers 判过才算同一问）
        val synchronizer = normalize(withoutComments(readMainSource(synchronizerFile)))
        val syncBody = functionBlock(synchronizer, "suspend fun sync(context: Context")
        for (needle in listOf(
            "val current = snapshot?.semester ?: repository.getCurrentSemester()",
            "getTimeSlots = { snapshot?.timeSlots ?: repository.getTimeSlots() }",
            "if (snapshot != null && snapshot.covers(row)) snapshot.courses else repository.getDisplayCourses(row)",
        )) {
            assertTrue("快照那一步的回退式换了形状（判据就数不出来了）：$needle\n$syncBody", syncBody.contains(needle))
        }
        assertEquals("节次表在快照那一步里被回库取了不止一遍", 1, occurrences(syncBody, "repository.getTimeSlots()"))
        assertEquals("学期行在快照那一步里被回库取了不止一遍", 1, occurrences(syncBody, "repository.getCurrentSemester()"))
        // 载体递到了快照那一步（中间隔的那层不许把它丢掉，否则上面三行回退式永远是 null 那侧）
        val spaced = syncCode.replace(Regex("\\s+"), " ")
        val redraw = functionBlock(spaced, "fun syncAndRedrawAllWidgets(")
        assertTrue("重绘那一步没把载体递给快照：$redraw", redraw.contains("WidgetDataSynchronizer.sync(context, snapshot)"))
        val refresh = functionBlock(spaced, "suspend fun refreshWidgets(")
        assertTrue("refreshWidgets 没把载体递给重绘：$refresh", refresh.contains("syncAndRedrawAllWidgets(context, snapshot)"))

        // 单独调用这一步的调用点仍然自己读：默认值必须是 null，不许改成"先造一枚局部的"
        for (signature in listOf(
            "suspend fun rescheduleReminders(",
            "suspend fun rescheduleRemindersAndBells(",
            "suspend fun refreshWidgets(",
            "suspend fun runColdStartWidgetSteps(",
            "suspend fun scheduleTomorrowPreview(",
            "suspend fun rescheduleNextWindow(",
            "suspend fun sync(context: Context",
        )) {
            val params = normalize(parameterList(declaredCodeOf(signature), signature))
            assertTrue(
                "$signature 的参数表里没有 `snapshot: ChainReadSnapshot? = null` 这一枚" +
                    "（默认值改成非 null 就是拿空册子当真册子；删掉参数则单独调用这条路会少读表）：$params",
                params.contains("snapshot: ChainReadSnapshot? = null"),
            )
        }
    }

    /** [signature] 处那对圆括号之间的实参表（配平，嵌套括号与默认值一起收；锚点从函数名起算） */
    private fun parameterList(code: String, signature: String): String {
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：函数改名或挪过家，这条守卫要跟着改" }
        val open = code.indexOf('(', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, index)
                }
            }
        }
        throw IllegalStateException("$signature 的圆括号没配平")
    }

    /**
     * ⑤ 载体不越过一枚链的寿命（数出现次数的册子 + 负册）：没有缓存、没有字段、没有跨调用复用。
     *
     * 抄的是 [BackgroundSync.hasAnyWidget] 那条既定口径（`BackgroundSync.kt` 里"当参数传下去，
     * 而不是缓存起来"那段）：没有跨调用的缓存，就不存在陈旧问题。这一格防的是"顺手把载体
     * 存成 object 字段 / prefs / lazy"，那一步一旦发生，用户改完课表看到的还是上一轮的课。
     */
    @Test
    fun carrierNeverOutlivesTheChainItWasBuiltFor() {
        // 册子按"哪几枚文件引用了这枚载体"取集合（不比次数：次数会随注释增删漂，
        // 而这一格要判的是"有没有第五处把它的寿命拖过一条链"）
        val census = censusPerFile { blanked -> occurrences(blanked, "ChainReadSnapshot") }.keys.sorted()
        assertEquals(
            "引用这枚载体的文件集合变了（新增一处就要在这里说明它活多久）：\n$census",
            listOf(
                "$MAIN_PREFIX/$snapshotFile",
                "$MAIN_PREFIX/$repositoryFile",
                "$MAIN_PREFIX/$backgroundSyncFile",
                "$MAIN_PREFIX/$coldStartFile",
                "$MAIN_PREFIX/$schedulerFile",
                "$MAIN_PREFIX/$synchronizerFile",
            ).sorted(),
            census,
        )

        // 没有任何一行把载体存成可变字段或 lazy 属性（枚级扫描：只看真代码行，注释已抹）
        for (source in mainSources()) {
            val blanked = blankCommentsAndLiterals(source.text)
            for (line in blanked.lines()) {
                if (!line.contains("ChainReadSnapshot")) continue
                val holdsItAcrossCalls = line.contains("var ") || line.contains("by lazy") || line.contains("Mutable")
                assertTrue(
                    "${source.relative} 里把载体存成了跨调用可活的东西（一枚链的寿命就此结束，" +
                        "用户改完课表看到的还是上一轮那一份）：\n${line.trim()}",
                    !holdsItAcrossCalls,
                )
            }
        }
    }

    /**
     * ⑥ #536 F3 那一手落在 VM 的 viewModelScope 上（逐字节 + 负册）：
     * 设置页那枚开关不再从本页的 composition scope 排闹钟。
     */
    @Test
    fun previewAlarmArmingLeftThePageScope() {
        val settings = blankCommentsAndLiterals(readMainSource(settingsScreenFile))
        assertEquals("设置页还在自己排明日预告的闹钟（页 scope 一被组合取消就一颗也不剩）", 0, occurrences(settings, "scheduleTomorrowPreview"))
        assertTrue("设置页那枚开关没把这一手交给 VM", occurrences(settings, "viewModel.onTomorrowPreviewToggled()") >= 1)

        val vm = withoutComments(readMainSource(viewModelFile))
        val body = normalize(functionBlock(vm, "fun onTomorrowPreviewToggled()"))
        assertEquals("这一手起的不止一枚协程", 1, occurrences(body, "viewModelScope.launch {"))
        assertTrue("排闹钟那一手没挂在 viewModelScope 的协程里：$body", body.contains("BackgroundSync.scheduleTomorrowPreview(getApplication())"))
        assertEquals("VM 这一手里又用上了组合期的 scope", 0, occurrences(body, "rememberCoroutineScope"))
        // 单步链不造载体：这一路只有它自己查一遍四张表，没有第二处可省
        assertEquals("单步的开关那一路不该造载体", 0, occurrences(body, "ChainReadSnapshot"))
    }

    // ---- 这一格自己的判据：四张表不许再被裸读（供 ④ 复用，方向只有一个）--------------

    /**
     * 链上某一步的体内：四张表的裸读一次都不许出现（回退式那一行里的 loadChainReadSnapshot 不算）。
     *
     * ⚠️ T297③补（地雷 6，判死格）：这一族的针原本写成 `repository().getCurrentSemester()` 那种形状，
     * 而盘上根本不存在这个形状 —— 链上的写法要么是 `context.scheduleRepository().getCurrentSemester()`
     * （那里 "Repository" 是大写 R，小写 needle 逐字节匹配不到），要么是 `repository.getCurrentSemester()`
     * （没有那对圆括号）。四枚 needle 在改前与改后的体内都数出 0，等于**这一格从来不判任何东西**：
     * 朝宽那一臂（某一步回头裸读学期行）红不到它。换成方法名本体这四枚，改后仍为 0（逐枚实测），
     * 而任何回库裸读——不论接收者怎么写——都会撞上。判据方向没变：只许 0。
     */
    private fun assertNoRawTableRead(where: String, normalizedBody: String) {
        for (needle in listOf(
            "getCurrentSemester()",
            "getTimeSlots()",
            "getDisplayCourses(",
            "getReminders(",
        )) {
            assertEquals("$where 里还有 $needle 这一趟裸读（载体装着的东西又被查了一遍）", 0, occurrences(normalizedBody, needle))
        }
    }

    // ---- 源码核对工具（抄 ColdStartRebuildWiringTest 与 ClassProgressRescheduleWiringTest）----

    private class Source(val relative: String, val text: String)

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 只扫主源码：测试目录里那些注入的假实现不该算进册子 */
    private fun mainSources(): List<Source> {
        val javaDir = findMainJavaDir()
        val files = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${javaDir.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source("$MAIN_PREFIX/${it.relativeTo(javaDir).path.replace('\\', '/')}", it.readText()) }
    }

    /** 每枚文件计数（丢掉 0 的那些），供"哪个文件引用了几遍"那本册子用 */
    private fun censusPerFile(counter: (String) -> Int): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        for (source in mainSources()) {
            val hits = counter(blankCommentsAndLiterals(source.text))
            if (hits > 0) out[source.relative] = hits
        }
        return out
    }

    private fun importLines(code: String, prefix: String): List<String> =
        code.lines().filter { it.trimStart().startsWith("import $prefix") }

    /** 从 [signature] 处起，配平到对应的右括号（含签名本身）；找不到就抛，静默跳过等于没有守卫 */
    private fun functionBlock(code: String, signature: String): String {
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：函数改名或加了参数，这条守卫要跟着改" }
        val open = code.indexOf('{', code.indexOf(')', at))
        check(open >= at) { "$signature 之后找不到函数体" }
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    /**
     * 注入缝上那一枚 lambda 的块：从 [anchor]（含）起，到它之后第一对配平的花括号为止。
     *
     * 为什么要有这一枚而不是复用 [functionBlock]：表达体函数（`) = other(…) `）的"函数体"根本没有
     * `{`，functionBlock 只会切到第一枚实参 lambda 为止。锚点失效照样**抛**，静默跳过等于没有守卫。
     */
    private fun lambdaBlock(code: String, anchor: String): String {
        val at = code.indexOf(anchor)
        check(at >= 0) { "找不到 $anchor：lambda 改名或挪过家，这条守卫要跟着改" }
        val open = code.indexOf('{', at + anchor.length)
        check(open >= at) { "$anchor 之后找不到 lambda 体" }
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$anchor 的 lambda 花括号没配平")
    }

    /** 闸门编排本体的那段（从提前 return 那道判断往后都算它的一半） */
    private fun chainBodyOf(normalizedColdStart: String): String {
        val at = normalizedColdStart.indexOf("internal suspend fun run(")
        check(at >= 0) { "找不到闸门编排本体：internal suspend fun run( 改名了" }
        return normalizedColdStart.substring(normalizedColdStart.indexOf('{', at))
    }

    /** 某枚函数签名所在的整份文件（默认实参表就在签名里，取声明所在那份文件最稳） */
    private fun declaredCodeOf(signature: String): String {
        val file = listOf(
            backgroundSyncFile, schedulerFile, synchronizerFile, coldStartFile,
        ).firstOrNull { withoutComments(readMainSource(it)).contains(signature) }
        check(file != null) { "全仓找不到声明 $signature 的那份文件" }
        return normalize(withoutComments(readMainSource(file)))
    }

    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** 抹注释、留字面量：切单个函数体用这份 */
    private fun withoutComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        return out.toString().lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    // ⚠️ T297③补（地雷 3，编译级）：这一行原本写成一行式 KDoc，而正文里出现了字面的
    // 「斜杠＋星号」那对字符 —— Kotlin 的块注释是**嵌套**的，注释体内那对字符把注释又开了一层，
    // 于是句尾的 `*/` 只关掉里层，本行以下整片（`blankCommentsAndLiterals` / `findMainJavaDir` /
    // companion 的 MAIN_PREFIX 全在内）被吞进注释：编译器报 "Unclosed comment" + "Missing '}'"，
    // 并连带 21 枚 "Unresolved reference"。整个文件从 ③ 落盘那一刻起就没编译过。
    // 改成行注释（`//` 到行尾为止，不嵌套）并把那对字符用中文念出来，判据一个字没动。
    // 注释与字符串字面量都抹成空白、换行位置不变：整份文件的计数用这份
    // （防止字符串字面量里的那对「斜杠加星号」骗过配平）。
    private fun blankCommentsAndLiterals(src: String): String {
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
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                    for (k in (i + 3) until (end - 3).coerceAtLeast(i + 3)) if (out[k] != '\n') out[k] = ' '
                    i = end
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    for (k in (i + 1) until (j - 1).coerceAtLeast(i + 1)) out[k] = ' '
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛 */
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

    private fun headerOf(code: String): String = code.substring(0, minOf(code.length, 400))

    private companion object {
        const val MAIN_PREFIX = "app/src/main/java"
    }
}
