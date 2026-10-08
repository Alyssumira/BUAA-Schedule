package com.buaa.schedule.data.share

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T270④：源码形状守卫 —— 把「三张表各一枚闸、被拒一次都不落库、口令路也先问一句」钉在盘上。
 *
 * 卡的病灶是三行连起来才成立的（每一行单看都「看着没问题」），所以本守卫每层只管其中一行：
 * ① 闸门又只数课程（本卡主病灶）② 解码档把「越界」折回「无法识别」 ③ 事务内复核排在写库之后
 * ④ 口令路的确认弹窗写了没人念 ⑤ 留痕级别 ⑥ 判据内核不纯 / 又多一份定义 ⑦ 编码格式被动过。
 *
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。读法与小工具抄
 * `data/backup/BackupSemesterRestoreWiringGuardTest`（抹注释保字面量 + 花括号配平取块 +
 * 原文切片**不吃行号**）。守卫读 `app/src/main/java` 下的源码文本 —— 那一目录已在
 * `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()`
 * 的 inputs（T92①，台账 #136），所以「改了源码、单跑守卫拿到 Gradle 缓存绿灯」那一档盖得住。
 *
 * ## 四种钉法逐格点名
 *
 * 1. [闸门又只数课程那一形全树 0 处] —— **抹注释找锚点**：锚点是改前两道闸门的逐字本体
 *    （codec 的 takeIf 那一枚、VM 的 `data.courses.size > …MAX_COURSE_COUNT` 那一枚）。
 *    必须抹注释：T270①③ 的 KDoc 与注释里逐字引用着它们（登记旧形状用），
 *    不抹就是把自己的注释当成病灶（假红），只 grep 不抹又是另一头的假绿。
 *    另有 [探测器自己认得改前那一形 也不误伤合法形状] 做两侧自检。
 * 2. [三枚条数在解码档各数一遍] —— **逐字节**：`decodeOutcome` 体内
 *    `data.courses.size,` / `data.timeSlots.size,` / `data.reminders.size,` 各恰好一处
 *    （**这一层就是「至少一枚拦住闸门又只数课程」的那枚**：少递任一枚条数立刻红），
 *    外加 `RestoreVolumeGate.capacityViolation(` 全 app/src/main 恰好一处调用点。
 * 3. [三处调用点真的吃同一枚内核 + 顺序] —— **读形状**（花括号配平取
 *    `restoreBackupDataInTx` 的体）+ **数出现次数**：体内 `RestoreVolumeGate.decide(` 一枚、
 *    且该枚落在 `timeSlotDao.deleteAll()` **之前**、`RestoreResult.OverCapacity(volume)` 一枚、
 *    `UndoManager.clear()` 一枚、`restoreSlotReplacementLogCopy(` 一枚、`entry = entry,` 两枚。
 * 4. [确认弹窗真的挂在那一句上] —— **逐字节 + 数出现次数 + 成对**：见 ④ 那一层。
 * 5. [留痕级别与枚数] —— **数出现次数**：tag 的引用枚数逐枚数（按标识符数、不按行号，
 *    换行写法改不动），VM 四处处处都在 `Log.i`，而 `Log.d` / `Log.v` / `Log.e` / `Log.w`
 *    在 VM 各 0 处 —— 那条口径不是本卡自造的，是同页在册守卫
 *    `SpecialDayRefreshWiringGuardTest` 第 ③ 层已经写死的"取证行级别漂过就红"。
 * 6. [判据内核纯度与唯一定义 + 三枚比较各一枚] —— **读形状**（逐行扫 import、花括号配平取
 *    `capacityViolation` 的体）+ **数出现次数**： 零 android/androidx、零时钟/时区/权限/机型、
 *    三档结论各恰好一处声明、`fun decide(` 与 `fun capacityViolation(` 全树各一处，
 *    体内三张表各留下「条数 > 上限」的比较一枚、越级结论恰好三枚。
 * 7. [编码格式没动 = 红线] —— **逐字节**：前缀、Base64/Deflater/Inflater、4MB 上限、
 *    `const val BACKUP_FORMAT_VERSION = 2`、`BackupTimeSlot` 那枚 `number` 字段各自原样。
 *
 * 本守卫只读源码，**不证明**运行时行为：3 万行节次的口令真贴进输入框时屏幕上那句话长什么样、
 * 确认弹窗在转屏后还在不在、库里最终是不是 0 行新增 —— 那些归编排者装机
 * （`:app` 的 JVM 测试面没有 Robolectric，也没有能在 JVM 上跑的 Room 缝）。
 * 运行期档位与文案由 `RestoreVolumeGateTest` 逐格钉；本文件钉的是「接线在不在」。
 */
class ShareRestoreVolumeGuardTest {

    // ─────────────── ① 闸门又只数课程：抹注释后全树 0 处 ───────────────

    @Test
    fun `闸门又只数课程那一形在全树零命中`() {
        val hits = mutableListOf<String>()
        for (file in mainSources()) {
            for (needle in listOf(OLD_CODEC_GATE, OLD_VM_GATE)) {
                val at = file.code.indexOf(needle)
                if (at >= 0) {
                    hits += "${file.path} 第 ${lineOf(file.code, at)} 行命中「$needle」"
                }
            }
        }
        assertEquals(
            "app/src/main（抹掉注释之后）这两枚锚点必须各 0 处 —— 它们是改前那两道闸门的逐字本体，" +
                "两处都**只数 data.courses.size**，于是节次表与提醒表零上限：" +
                "一条只带 1 门课程、却带 3 万行节次的口令一路通到 deleteAll()。" +
                "实到 ${hits.size} 处：\n  " + hits.joinToString("\n  ") +
                "\n红了说明有人把闸门重新折回「只数一门课程」（本卡主病灶）。" +
                "今天这一判的唯一来源是 RestoreVolumeGate.capacityViolation（三张表各一枚上限）",
            0,
            hits.size,
        )
        // 这一层不是空转的两条证据：① 旧形状仍在注释里逐字登记着（抹注释之后才归零）
        val vmRaw = source(VIEW_MODEL)
        assertTrue(
            "`$VIEW_MODEL` 的原文里必须仍能在**注释**里找到「只数 data.courses.size」这句登记" +
                "（改前那两道闸门的形状）。实到 " + occurrences(vmRaw, OLD_SHAPE_NOTE_IN_VM) +
                " 处；抹注释之后第 ① 层才归零 —— 这就是「只 grep 字面量会假红」的实物证据",
            occurrences(vmRaw, OLD_SHAPE_NOTE_IN_VM) >= 1,
        )
        // ② 新闸门那一手真的长在 codec 里
        assertEquals(
            "codec（抹注释后）里 `RestoreVolumeGate.capacityViolation(` 恰好一处：实到 " +
                occurrences(blankCommentsKeepingLiterals(source(SHARE_CODEC)), CAPACITY_CALL) +
                " 处。换成「只数课程」或整枚摘掉，第 ① 层就白钉",
            1,
            occurrences(blankCommentsKeepingLiterals(source(SHARE_CODEC)), CAPACITY_CALL),
        )
    }

    /**
     * 探测器自己得认得出改前那一形，也不许误伤合法形状（「全树 0 命中」不是空转的证据）。
     *
     * 朝松那一头：锚点拼错（比如把 `it.courses.size` 写成 `courses.size`）会让第 ① 层永远绿。
     * 朝窄那一头：一把见 `courses.size` 就杀的尺会把**合法**的读数一起拆掉 ——
     * 仓储层 `courses.size - normalized.size`（跳过无效行那本账，T181 的修法）、
     * 预览里的 `courseCount = data.courses.size`（T184 的修法）、递给仓储的
     * `courses = data.courses.map { it.toDomain() }` 都会中枪。
     */
    @Test
    fun `探测器自己认得改前那一形 也不误伤合法形状`() {
        val oldCodecShape = "}.getOrNull()?.takeIf { it.courses.size <= CourseConstraints.MAX_COURSE_COUNT }"
        assertEquals(
            "探测器必须抓得出改前 codec 那一形（实到 " + occurrences(oldCodecShape, OLD_CODEC_GATE) +
                " 处）：0 处就说明第 ① 层那句「全树 0 命中」是假的绿",
            1,
            occurrences(oldCodecShape, OLD_CODEC_GATE),
        )
        val oldVmShape = """
            if (data.courses.size > com.buaa.schedule.domain.schedule.CourseConstraints.MAX_COURSE_COUNT) {
                _importMessage.value = AppMessage("x", isError = true)
            }
        """.trimIndent()
        assertEquals(
            "探测器必须抓得出改前 VM 那一形（实到 " +
                occurrences(blankCommentsKeepingLiterals(oldVmShape), OLD_VM_GATE) + " 处）",
            1,
            occurrences(blankCommentsKeepingLiterals(oldVmShape), OLD_VM_GATE),
        )
        val legit = """
            val skippedInvalid = courses.size - normalized.size
            val counts = RestoreCounts(courseCount = data.courses.size, timeSlotCount = data.timeSlots.size)
            courseCount = data.courses.size,
            courses = data.courses.map { it.toDomain() },
        """.trimIndent()
        val blanked = blankCommentsKeepingLiterals(legit)
        assertEquals(
            "合法的条数读数**不许**被算成病灶（实到 " + occurrences(blanked, OLD_CODEC_GATE) + " + " +
                occurrences(blanked, OLD_VM_GATE) + " 处）：见 `courses.size` 就杀的尺会把 " +
                "skippedInvalid 那本账、预览那枚 courseCount 与递给仓储的课程列表一起拆掉",
            0,
            occurrences(blanked, OLD_CODEC_GATE) + occurrences(blanked, OLD_VM_GATE),
        )
        val quoted = """
            /**
             * 旧形状：`.getOrNull()?.takeIf { it.courses.size <= CourseConstraints.MAX_COURSE_COUNT }`
             */
            fun noop() = Unit
        """.trimIndent()
        assertEquals(
            "注释里逐字引用的那一形不许进第 ① 层的账（抹注释后必须 0 处；实到 " +
                occurrences(blankCommentsKeepingLiterals(quoted), OLD_CODEC_GATE) +
                " 处）。未抹注释时它确实在原文里：" + occurrences(quoted, OLD_CODEC_GATE) + " 处",
            0,
            occurrences(blankCommentsKeepingLiterals(quoted), OLD_CODEC_GATE),
        )
    }

    // ─────────────── ② 解码档：三枚条数各自数一遍（逐字节）───────────────

    /**
     * 这一层就是卡面点名的「至少一枚拦住『闸门又只数课程』」的那枚守卫。
     * 第 ① 层认的是**改前那一形**；这一层认的是**今天这一形必须三枚条数都在**——
     * 于是"把 timeSlots 那一枚偷偷删掉"这种新形状（不在旧锚点里）也盖得住。
     */
    @Test
    fun `解码档真的把三张表各数一遍`() {
        val code = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        val hits = indexOfAll(code, DECODE_OUTCOME_ANCHOR)
        assertEquals(
            "「$DECODE_OUTCOME_ANCHOR」在 codec 里恰好一处（本层取块的宿主）：实到 ${hits.size} 处。" +
                "摘掉它 = 三档结论退化回一枚 null，被拒那条口令就又念「口令无法识别」那句假话",
            1,
            hits.size,
        )
        val body = braceBodyFrom(code, code.indexOf('{', hits.first() + DECODE_OUTCOME_ANCHOR.length))
        for (needle in listOf("data.courses.size,", "data.timeSlots.size,", "data.reminders.size,")) {
            assertEquals(
                "decodeOutcome 体内「$needle」恰好一处（实到 " + occurrences(body, needle) +
                    " 处）：三张表**各**数一枚才是甲档的裁定。少递一枚 = 那张表重新零上限 —— " +
                    "本卡病灶「3 万行节次」就是少递 data.timeSlots.size 那一枚的形状",
                1,
                occurrences(body, needle),
            )
        }
        assertEquals(
            "全 app/src/main 里「$CAPACITY_CALL」恰好一处调用点（实到 " +
                mainDefinitionCount(CAPACITY_CALL) + " 处）：上限口径只有内核那一份，" +
                "谁自己再写一遍 `> 500` 之类的比较就是第二把尺",
            1,
            mainDefinitionCount(CAPACITY_CALL),
        )
        val outcome = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        for (decl in listOf("data object Malformed", "data class OverCapacity", "data class Valid")) {
            assertEquals(
                "ShareCodeOutcome 里「$decl」恰好一处声明（实到 " + occurrences(outcome, decl) +
                    " 处）：三档少一档就有一格要借别人的句子说话 —— " +
                    "缺 OverCapacity 就是「越界」与「解不出来」折成同一句假话",
                1,
                occurrences(outcome, decl),
            )
        }
    }

    // ─────────────── ③ 调用点吃同一枚内核 + 判据排在写库之前（读形状 + 顺序）───────────────

    @Test
    fun `VM 与事务各一枚判据 且排在写库之前`() {
        val vm = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        assertEquals(
            "VM 里「RestoreVolumeGate.decide(」恰好一处（restoreFromData 门外那一判；实到 " +
                occurrences(vm, "RestoreVolumeGate.decide(") +
                " 处）：两路入口共用这一枚 choke point。第二处 = 又分岔出一条绕过判据的恢复路",
            1,
            occurrences(vm, "RestoreVolumeGate.decide("),
        )
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val repoHits = indexOfAll(repo, RESTORE_TX_ANCHOR)
        assertEquals("「$RESTORE_TX_ANCHOR」恰好一处（本层取块的宿主）：实到 ${repoHits.size} 处", 1, repoHits.size)
        val body = braceBodyFrom(repo, repo.indexOf('{', repoHits.first() + RESTORE_TX_ANCHOR.length))
        assertEquals(
            "体内「RestoreVolumeGate.decide(」恰好一处（实到 " + occurrences(body, "RestoreVolumeGate.decide(") +
                " 处）：这是**事务内的复核**，与门外那枚同一内核。摘掉它 = 第三枚调用点可以裸写",
            1,
            occurrences(body, "RestoreVolumeGate.decide("),
        )
        val decideAt = body.indexOf("RestoreVolumeGate.decide(")
        val deleteAt = body.indexOf("timeSlotDao.deleteAll()")
        assertTrue(
            "顺序判据：复核必须排在换表**之前**（decide 落在 index=$decideAt、timeSlotDao.deleteAll() 落在 " +
                "$deleteAt）。反了之后「超限一个字节都不写」这句话就是假的 —— 事务虽然回滚，" +
                "但这一等本来不该把整张表的 delete 都跑完才发现",
            decideAt in 0 until deleteAt,
        )
        assertEquals(
            "体内「RestoreResult.OverCapacity(volume)」恰好一处（实到 " +
                occurrences(body, "RestoreResult.OverCapacity(volume)") +
                " 处）：拒那一档必须真的把结论交回调用点，裸 return@withTransaction 是丢掉档位",
            1,
            occurrences(body, "RestoreResult.OverCapacity(volume)"),
        )
        assertEquals(
            "体内「UndoManager.clear()」恰好一处（实到 " + occurrences(body, "UndoManager.clear()") +
                " 处）：本卡**没有**撤掉它（快照指向的行确实已不属于这张表），" +
                "而是把「它同时拆掉了节次表唯一的把手」这件事念到明处 —— 见换表前的 Log.i 与确认文案",
            1,
            occurrences(body, "UndoManager.clear()"),
        )
        assertEquals(
            "体内「restoreSlotReplacementLogCopy(」恰好一处（实到 " +
                occurrences(body, "restoreSlotReplacementLogCopy(") +
                " 处）：恢复成功的 toast 会自己消失，而「作息表是谁换的」是事后唯一能查的东西",
            1,
            occurrences(body, "restoreSlotReplacementLogCopy("),
        )
        assertEquals(
            "仓储里「entry = entry,」恰好两处（restoreBackupData 转参 + 递给 decide；实到 " +
                occurrences(repo, "entry = entry,") + " 处）：不递 = 复核说不出是哪一路动的手",
            2,
            occurrences(repo, "entry = entry,"),
        )
    }

    @Test
    fun `文件路的两枚调用点把 previewConfirmed 递成 true`() {
        val vm = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        // 文件路的预览弹窗、「仍然恢复」那两支与 confirmPendingRestore 的重放都已经问过用户
        assertEquals(
            "VM 里「previewConfirmed = true」恰好三处（restorePendingBackup、confirmEmptyRestore、" +
                "confirmPendingRestore 重放；实到 " + occurrences(vm, "previewConfirmed = true") +
                " 处）：少一处就是**合法的**文件恢复被问第二遍（朝窄方向的误杀，" +
                "把 T100/T186 那一族的预览流程改成两次点击）",
            3,
            occurrences(vm, "previewConfirmed = true"),
        )
        assertEquals(
            "口令入口那一枚必须明写「previewConfirmed = false,」（实到 " +
                occurrences(vm, "previewConfirmed = false,") +
                " 处）：写成 true 就等于把乙档那一问整枚跳过 —— 本卡「两条入口保护等级不同」" +
                "那一格重新立起来",
            1,
            occurrences(vm, "previewConfirmed = false,"),
        )
    }

    // ─────────────── ④ 确认弹窗：写了就有人念（数出现次数 + 成对）───────────────

    @Test
    fun `口令路的确认弹窗真的挂在那一句上`() {
        val vm = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        assertEquals(
            "VM 里「_pendingRestore.value = PendingRestore(」恰好一处（实到 " +
                occurrences(vm, "_pendingRestore.value = PendingRestore(") +
                " 处）：判据交回 ConfirmFirst 之后必须有个把手存着这一问",
            1,
            occurrences(vm, "_pendingRestore.value = PendingRestore("),
        )
        assertEquals(
            "VM 里「restoreShareConfirmCopy(volume)」恰好一处（实到 " +
                occurrences(vm, "restoreShareConfirmCopy(volume)") +
                " 处）：句子由判据渲染、当场存进把手 —— 弹窗里自己重拼一档就是第二把尺",
            1,
            occurrences(vm, "restoreShareConfirmCopy(volume)"),
        )
        assertEquals(
            "把手清场两处成对（confirmPendingRestore 与 dismissPendingRestore 各一枚 " +
                "「_pendingRestore.value = null」；实到 " + occurrences(vm, "_pendingRestore.value = null") +
                " 处）：本仓「成对字段漏清」那一族 —— 少一枚就是弹窗关不掉，" +
                "或下次导入直接弹旧的那一问（用户会以为 App 又疯了）",
            2,
            occurrences(vm, "_pendingRestore.value = null"),
        )
        val screen = blankCommentsKeepingLiterals(source(IMPORT_SCREEN))
        assertEquals(
            "ImportScreen 里「pendingRestore != null」恰好一处（实到 " +
                occurrences(screen, "pendingRestore != null") +
                " 处）：VM 写了把手而界面不吃 = 口令路仍然零确认" +
                "（T134 那一族：importMessage 写了没人念）",
            1,
            occurrences(screen, "pendingRestore != null"),
        )
        assertEquals(
            "界面里「pending?.confirmCopy」恰好一处（实到 " + occurrences(screen, "pending?.confirmCopy") +
                " 处）：弹窗念的就是判据那一句，不许在 UI 里重写一遍措辞",
            1,
            occurrences(screen, "pending?.confirmCopy"),
        )
        assertEquals(
            "界面里「viewModel.confirmPendingRestore()」恰好一处（实到 " +
                occurrences(screen, "viewModel.confirmPendingRestore()") + " 处）",
            1,
            occurrences(screen, "viewModel.confirmPendingRestore()"),
        )
        assertEquals(
            "界面里「viewModel.dismissPendingRestore()」恰好两处（onDismissRequest 与「取消」那颗成对；" +
                "实到 " + occurrences(screen, "viewModel.dismissPendingRestore()") +
                " 处）：只接一颗就是点系统返回后把手留在内存里、下次导入直接弹旧的那一问",
            2,
            occurrences(screen, "viewModel.dismissPendingRestore()"),
        )
    }

    // ─────────────── ⑤ 留痕级别：Log.d 等于没写 ───────────────

    /**
     * 留痕分两问：**有没有留**与**级别对不对**。
     *
     * 级别一律 Info，不用 Warn —— 因为同页 VM 上有一枚在册守卫
     * （`SpecialDayRefreshWiringGuardTest` 第 ③ 层）把整枚 ScheduleViewModel 文件里的
     * `Log.d` / `Log.v` / `Log.e` / `Log.w` 各数成 0 处。本卡若拿 Warn，红的不是自己的格子而是
     * 别人那张卡（第 ③ 层那格同时把这件事钉成"不许漂"）。
     */
    @Test
    fun `被拒与待确认那几手留痕在 Info 级`() {
        val vm = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        assertEquals(
            "VM 里新 tag「RESTORE_VOLUME_LOG_TAG」恰好五处（一处声明 + 三处「被拒」+ 一处「待确认」；" +
                "实到 " + occurrences(vm, RESTORE_VOLUME_TAG_NEEDLE) +
                " 处）：枚数按引用数、不按行号 —— 换行写法改不动这一层",
            5,
            occurrences(vm, RESTORE_VOLUME_TAG_NEEDLE),
        )
        assertEquals(
            "VM 里 `Log.i(RESTORE_VOLUME_LOG_TAG` 恰好四处（codec 越界 / 门外越界 / 事务内复核 / 待确认；实到 " +
                occurrences(vm, "Log.i(RESTORE_VOLUME_LOG_TAG") + " 处）：被拒那一手不许静默",
            4,
            occurrences(vm, "Log.i(RESTORE_VOLUME_LOG_TAG"),
        )
        for (banned in listOf("Log.d(", "Log.v(", "Log.e(", "Log.w(")) {
            assertEquals(
                "VM 里「$banned」必须 0 处（实到 " + occurrences(vm, banned) +
                    " 处）：级别漂到 Warn 会被本仓另一枚在册守卫（SpecialDayRefresh 第 ③ 层）判成" +
                    "「取证行级别漂过」，而 Info 以下在那台机器上根本读不到",
                0,
                occurrences(vm, banned),
            )
        }
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        assertEquals(
            "仓储里同一枚 tag 恰好三处（一处私有声明 + 一处复核被拒 + 一处换表留痕；实到 " +
                occurrences(repo, RESTORE_VOLUME_TAG_NEEDLE) + " 处）",
            3,
            occurrences(repo, RESTORE_VOLUME_TAG_NEEDLE),
        )
        assertEquals(
            "仓储里 `Log.i(` 恰好两处（改前这一页一行日志都没有，本卡加的两处都是判据留痕；实到 " +
                occurrences(repo, "Log.i(") + " 处）；`Log.d(` 必须 0 处（实到 " +
                occurrences(repo, "Log.d(") + " 处）",
            2,
            occurrences(repo, "Log.i(") + occurrences(repo, "Log.d("),
        )
        // T204 那一族的 tag 一处未动（本卡把新日志另起一枚 tag，正是为了不挤掉它）
        assertEquals(
            "VM 里「Log.i(RESTORE_LOG_TAG」仍恰好一处（T204 的补建账，被 " +
                "BackupSemesterRestoreWiringGuardTest 第 ⑥ 层数死一枚；实到 " +
                occurrences(vm, "Log.i(RESTORE_LOG_TAG") +
                " 处）：把本卡那四行塞进同一枚 tag 会红在**别人的**守卫上",
            1,
            occurrences(vm, "Log.i(RESTORE_LOG_TAG"),
        )
        assertEquals(
            "tag 的字面值「$TAG_VALUE」在 app/src/main 恰好两处（VM 与仓储各一枚私有声明；实到 " +
                mainDefinitionCount(TAG_VALUE) + " 处）：一枚 logcat tag 两处私有声明是本卡认可的形状" +
                "（跨文件常量要把它拖进 data 层反而多一处耦合），但第三处就是有人开始各写各的了",
            2,
            mainDefinitionCount(TAG_VALUE),
        )
    }

    // ─────────────── ⑥ 判据内核：纯 JVM、只有一份、三档齐 ───────────────

    @Test
    fun `判据内核零 android 依赖 不读时钟 全仓只有一份`() {
        val raw = source(POLICY)
        val code = blankCommentsKeepingLiterals(raw)
        val offenders = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") }
            .toList()
        assertEquals(
            "纯 JVM 判据必须**零 android / androidx import**（仓库硬规矩：设备面一律由调用点当参数传进来）。" +
                "实到：" + offenders,
            emptyList<String>(),
            offenders,
        )
        for (probe in listOf(
            "LocalDate.now(", "System.currentTimeMillis", "Calendar.", "java.time.Clock",
            "Instant.", "ZonedDateTime", "TimeZone", "ZoneId", "Date(", "Build.VERSION", "Context",
        )) {
            assertEquals(
                "内核里「$probe」必须 0 处（实到 " + occurrences(code, probe) +
                    " 处）：判据一旦能读钟/时区/机型/上下文，同一份条数就会因「今天几点、在哪台机器上」" +
                    "给出不同档位，表驱动单测随之失真",
                0,
                occurrences(code, probe),
            )
        }
        assertEquals(
            "内核宿主「object RestoreVolumeGate {」全 app/src/main 恰好一处（实到 " +
                mainDefinitionCount("object RestoreVolumeGate {") + " 处）：第二枚 object = 第二本册子",
            1,
            mainDefinitionCount("object RestoreVolumeGate {"),
        )
        for (fn in listOf("fun decide(", "fun capacityViolation(", "fun undoSnapshotNeed(")) {
            assertEquals(
                "「$fn」在内核文件里恰好一处（实到 " + occurrences(code, fn) + " 处）：",
                1,
                occurrences(code, fn),
            )
        }
        for (fn in listOf("fun capacityViolation(", "fun undoSnapshotNeed(")) {
            assertEquals(
                "「$fn」全 app/src/main 也只许一处定义（实到 " + mainDefinitionCount(fn) +
                    " 处）：第二份定义 = 两把尺量同一件事，改其一不改其余" +
                    "（`fun decide(` 这个名字全仓有七枚同名判据，所以它只在**本文件内**数，见上一层）",
                1,
                mainDefinitionCount(fn),
            )
        }
        for (tier in listOf("data class Write(", "data class ConfirmFirst(", "data class Reject(")) {
            assertEquals(
                "结论档位「$tier」恰好一处声明（实到 " + occurrences(code, tier) +
                    " 处）：三档就是「落库 / 先问 / 拒」。第四档会稀释它们，" +
                    "少一档就有一格要借别人的话说（卡面 §3.5 不许折成 Boolean）",
                1,
                occurrences(code, tier),
            )
        }
        assertEquals(
            "三枚上限各恰好一处声明（maxCourses / maxTimeSlots / maxReminders；实到 " +
                occurrences(code, "val maxCourses: Int,") + " + " + occurrences(code, "val maxTimeSlots: Int,") +
                " + " + occurrences(code, "val maxReminders: Int,") +
                " 处）：甲档的裁定是「各表分别一枚」——并成一枚总条数就漏掉" +
                "「只灌节次表而课程只带一条」那一档（本卡的靶子）",
            3,
            occurrences(code, "val maxCourses: Int,") +
                occurrences(code, "val maxTimeSlots: Int,") +
                occurrences(code, "val maxReminders: Int,"),
        )
        assertEquals(
            "节次上限「$SLOT_LIMIT」在 app/src/main 恰好一处（实到 " +
                mainDefinitionCount(SLOT_LIMIT) + " 处）：第二处就是第二把尺",
            1,
            mainDefinitionCount(SLOT_LIMIT),
        )
        // 三张表逐张比上限那三行（第 ② 层数的是调用点递进去的条数，这一格数的是内核自己比不比）
        val gateBody = braceBodyFrom(code, code.indexOf('{', code.indexOf("fun capacityViolation(")))
        for (needle in listOf(
            "counts.courseCount > limits.maxCourses",
            "counts.timeSlotCount > limits.maxTimeSlots",
            "counts.reminderCount > limits.maxReminders",
        )) {
            assertEquals(
                "capacityViolation 体内「$needle」恰好一处（实到 " + occurrences(gateBody, needle) +
                    " 处）：这一格与第 ② 层是同一道缝的两头 —— 第 ② 层管「调用点有没有把条数递进来」，" +
                    "这一格管「内核有没有真的去比这一枚上限」。删掉任一行 = 那张表重新零上限，" +
                    "也就是本卡主病灶「闸门只管课程」换了一种写法回来",
                1,
                occurrences(gateBody, needle),
            )
        }
        assertEquals(
            "capacityViolation 体内越界结论恰好三枚（每张表一枚，实到 " +
                occurrences(gateBody, "RestoreVolumeDecision.Reject(entry,") + " 处）：" +
                "少一枚就是有一张表的分支只剩比较、没有结论",
            3,
            occurrences(gateBody, "RestoreVolumeDecision.Reject(entry,"),
        )
    }

    // ─────────────── ⑦ 红线：编码格式一字未动 ───────────────

    @Test
    fun `编码格式没动 老码仍然解得回来`() {
        val code = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        assertEquals(
            "红线：前缀常量「const val PREFIX = \"BUAASCH1:\"」必须逐字还在（实到 " +
                occurrences(code, "const val PREFIX = \"BUAASCH1:\"") +
                " 处）。改前缀 = 已经发出去的旧码整批解不出来，那不是本卡授权的手",
            1,
            occurrences(code, "const val PREFIX = \"BUAASCH1:\""),
        )
        for (needle in listOf(
            "Base64.getUrlEncoder().withoutPadding()",
            "Base64.getUrlDecoder()",
            "Deflater(Deflater.BEST_COMPRESSION)",
            "Inflater()",
            "MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024",
        )) {
            assertEquals(
                "codec 里「$needle」恰好一处（实到 " + occurrences(code, needle) +
                    " 处）：形状与体积上限都属编码格式，本卡只加判据、一个字都不动它们" +
                    "（改格式 = 让已发出去的旧码全部失效，要改先论证迁移）",
                1,
                occurrences(code, needle),
            )
        }
        val models = blankCommentsKeepingLiterals(source(BACKUP_MODELS))
        assertEquals(
            "红线：「const val BACKUP_FORMAT_VERSION = 2」必须逐字还在（实到 " +
                occurrences(models, "const val BACKUP_FORMAT_VERSION = 2") +
                " 处）：本卡没有新增字段、也没有涨版本号（涨了就老应用直接拒收）",
            1,
            occurrences(models, "const val BACKUP_FORMAT_VERSION = 2"),
        )
        assertEquals(
            "BackupTimeSlot 那枚「val number: Int,」一字未动（实到 " + occurrences(models, "val number: Int,") +
                " 处）：给节次行加/删字段同样是格式改动，要先论证迁移才许碰",
            1,
            occurrences(models, "val number: Int,"),
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private data class Source(val path: String, val code: String)

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        return files.map {
            Source(it.relativeTo(root).path.replace('\\', '/'), blankCommentsKeepingLiterals(it.readText()))
        }
    }

    private fun mainDefinitionCount(needle: String): Int = mainSources().sumOf { occurrences(it.code, needle) }

    private fun lineOf(code: String, index: Int): Int = code.substring(0, index).count { it == '\n' } + 1

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
        return hits
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    /** 从已知开括号位按花括号配平取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') {
            "锚点末尾不是花括号：写法变了，切不出一段体（实到「${source.getOrNull(open)}」）"
        }
        return source.substring(
            open + 1,
            (matchingBracket(source, open, '{', '}') ?: error("花括号没配平：从 $open 起")),
        )
    }

    /** 括号配平（跳过字符串与字符字面量）；不配平返回 null */
    private fun matchingBracket(src: String, open: Int, openChar: Char, closeChar: Char): Int? {
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '\'' -> {
                    // 字符字面量：'{' / '}' 之类不参与配平
                    var j = i + 1
                    if (src.getOrNull(j) == '\\') j += 2 else j += 1
                    if (src.getOrNull(j) == '\'') i = j
                }

                c == openChar -> depth++
                c == closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信）。
     * 本卡尤其需要抹注释：codec、VM 与内核的 KDoc 都逐字引用着改前那两行闸门（登记旧形状用）。
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

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
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

    private companion object {
        const val POLICY = "com/buaa/schedule/domain/schedule/RestoreVolumeGate.kt"

        /**
         * 解码那一站的三档结论（ShareCodeOutcome）与 codec 同文件 —— 这里两枚常量指的是
         * **同一枚文件**，点名是为了让按符号 grep 的人不用猜：`ShareCodeOutcome` 与
         * `decodeOutcome` 都落在 ScheduleShareCodec.kt 这一枚里。
         */
        const val SHARE_CODEC = "com/buaa/schedule/data/share/ScheduleShareCodec.kt"
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val IMPORT_SCREEN = "com/buaa/schedule/ui/importing/ImportScreen.kt"
        const val BACKUP_MODELS = "com/buaa/schedule/data/backup/BackupModels.kt"

        /** 改前两道闸门的逐字本体（`3ef34cb` 上原样；第 ① 层的锚点） */
        const val OLD_CODEC_GATE = "takeIf { it.courses.size <= CourseConstraints.MAX_COURSE_COUNT }"
        const val OLD_VM_GATE =
            "data.courses.size > com.buaa.schedule.domain.schedule.CourseConstraints.MAX_COURSE_COUNT"

        /** VM 注释里那句「登记旧形状」的短语（第 ① 层的"不是空转"证据之一） */
        const val OLD_SHAPE_NOTE_IN_VM = "只数 data.courses.size"

        const val CAPACITY_CALL = "RestoreVolumeGate.capacityViolation("
        const val DECODE_OUTCOME_ANCHOR = "fun decodeOutcome(code: String): ShareCodeOutcome"
        const val RESTORE_TX_ANCHOR = "private suspend fun restoreBackupDataInTx("
        const val RESTORE_VOLUME_TAG_NEEDLE = "RESTORE_VOLUME_LOG_TAG"
        const val TAG_VALUE = "RESTORE_VOLUME_LOG_TAG = \"RestoreVolume\""
        const val SLOT_LIMIT = "MAX_TIME_SLOT_COUNT = 500"
    }
}
