package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T205 的源码形状守卫：并列排程的"排与查同一处"，与兜底排铃的"失败必须留痕"。
 *
 * 为什么只能钉形状：排铃那两手要 `Context` 与 `AlarmManager`，本模块单测没有 Robolectric
 * （android.jar 里全是抛 "not mocked" 的桩），真跑一次要设备；而这张卡要防的两件事恰好都长在
 * **形状**上 ——
 * ① `REQUEST_CODE_REMINDER` 那枚注释自己警告过的 requestCode/`Intent.filterEquals` 配对坑：
 *    排程用一份 builder、撤销与查询用另一份，对不上就是"查得到一条永远不存在的闹钟"，零报错；
 * ② `runCatching` 裸着再接 `return`：省电档排铃失败 = 零 logcat 证据 + 零结论。
 * 形状一变这两件事就回来了，所以判据全部读源码，不读运行时。
 *
 * 四把钉法逐条点名（本仓守卫的四种写法，不许混着当"反正都读源码"）：
 * - [requestCodeIsStillASingleSourceAndSlottingIsToo] —— **数出现次数**
 * - [schedulingAndLookUpShareOneSlotBuilder] —— **读形状**（函数体里只许出现"只查不造"那一手）
 * - [everyBellArmAttemptIsChainedAndVisible] —— **读形状 + 抹注释找锚点**
 * - [tiedGroupKernelNoLongerPicksTheFirstMinimum] —— **抹注释找锚点**
 * - [failureLogsSurviveAtWarnLevel] —— **逐字节**
 * - [bellConclusionIsActuallyReadByTheCaller] —— **数出现次数**
 */
class TiedReminderArmGuardTest {

    /**
     * **数出现次数**：`REQUEST_CODE_REMINDER` 仍是全仓唯一一条声明 —— 它现在指"一段连续槽位的
     * 第一格"，那么"槽位号怎么算"也必须只有一处（`REQUEST_CODE_REMINDER + slot` 一枚）、
     * `PendingIntent.getBroadcast` 在本文件一枚、槽位段落的宽度一枚。
     * 两处各拼一份 requestCode 就是那枚常量当初警告的坑：排的那一侧与查的那一侧对不上。
     */
    @Test
    fun requestCodeIsStillASingleSourceAndSlottingIsToo() {
        val declarations = mainSources()
            .map { it.relative to occurrences(withoutComments(it.text), DECLARATION) }
            .filter { it.second > 0 }
        assertEquals(
            "REQUEST_CODE_REMINDER 的声明不止全仓一枚（槽位窗口的第一格只能有一个出处）：$declarations",
            listOf("$REMINDER_FILE -> 1"),
            declarations.map { "${it.first} -> ${it.second}" },
        )

        val code = withoutComments(readMainSource(REMINDER_FILE))
        assertEquals(
            "本文件里 PendingIntent.getBroadcast( 不止一枚：排、撤、查必须共用同一个 builder",
            1,
            occurrences(code, "PendingIntent.getBroadcast("),
        )
        assertEquals(
            "槽位号算术出现不止一处：撤销与查询就会与排程错开一格",
            1,
            occurrences(code, "REQUEST_CODE_REMINDER + slot"),
        )
        assertEquals(
            "MAX_TIED_REMINDERS 的声明也必须只有一枚（槽位段落的宽度只有一个出处）",
            1,
            occurrences(code, "private const val MAX_TIED_REMINDERS"),
        )
    }

    /**
     * **读形状**：撤与查两条路都走"只查不造"那一手（`FLAG_NO_CREATE`），而
     * `FLAG_UPDATE_CURRENT` 只许出现在排程那一条路里；两条路都不许自己再写一遍
     * `PendingIntent.getBroadcast`。这是 `CancelPathNoCreatePendingIntentTest` 同族的第三处。
     */
    @Test
    fun schedulingAndLookUpShareOneSlotBuilder() {
        val code = withoutComments(readMainSource(REMINDER_FILE))

        val cancelBody = balancedBlock(code, "fun cancelAll(context: Context)")
        val probeBody = functionText(code, "internal fun hasPendingReminder(context: Context)")
        val slotBuilder = functionText(code, "private fun reminderSlotIntent(")
        val lookUp = functionText(code, "private fun existingReminderSlot(")

        for ((name, body) in listOf("cancelAll" to cancelBody, "hasPendingReminder" to probeBody)) {
            assertTrue("$name 不再走那个唯一的只查不造 builder：它开始自己拼 requestCode 了", body.contains(LOOK_UP_CALL))
            assertFalse("$name 里出现了 FLAG_UPDATE_CURRENT：取消路径白造一条 PendingIntent 记录", body.contains("FLAG_UPDATE_CURRENT"))
            assertEquals("$name 里不该自己写 PendingIntent.getBroadcast(", 0, occurrences(body, "PendingIntent.getBroadcast("))
        }
        assertTrue(
            "只查不造那一手本身必须带 FLAG_NO_CREATE：$lookUp",
            lookUp.contains("FLAG_NO_CREATE") && lookUp.contains("reminderSlotIntent("),
        )
        assertTrue("唯一的 builder 里必须真的用到常量与槽位号：$slotBuilder", slotBuilder.contains("REQUEST_CODE_REMINDER + slot"))
        assertEquals(
            "排程那一条路里才许出现 FLAG_UPDATE_CURRENT（全文件只此一处）",
            1,
            occurrences(code, "FLAG_UPDATE_CURRENT"),
        )
    }

    /**
     * **读形状 + 抹注释找锚点**：课堂铃两枚档位本体里的每一枚 `runCatching` 都必须被链接住，
     * 而且**排铃那一手必须接 `.onFailure` / `.recoverCatching`**（只有读授权那一问允许
     * `.getOrDefault(false)` 的保守默认值）。裸着一枚 `runCatching` 再 `return` 是病灶 B 第①格，
     * 接了 `.isSuccess` 却不接 `onFailure` 是它的另一半 —— 结论有了、logcat 里还是零证据。
     *
     * 必须先抹注释：这两条链的 KDoc 里**谈到了** `runCatching` 与 `Log.`（口径说明），
     * 不抹就会把散文当证据。
     */
    @Test
    fun everyBellArmAttemptIsChainedAndVisible() {
        val code = withoutComments(readMainSource(SCHEDULER_FILE))
        val bodies = listOf(
            "armStartBell" to balancedBlock(code, "internal fun armStartBell("),
            "armEndBell" to balancedBlock(code, "internal fun armEndBell("),
            "armBellAttempt" to functionText(code, "private fun armBellAttempt("),
            "scheduleClassStartBell" to balancedBlock(code, "private fun scheduleClassStartBell("),
            "scheduleEndBell" to functionText(code, "private fun scheduleEndBell("),
        )

        for ((name, body) in bodies) {
            val offenders = bareRunCatching(name, body)
            assertTrue("$name 里有裸着的 runCatching（失败既不留痕也不进结论）：$offenders", offenders.isEmpty())
        }
        val attemptBody = bodies.first { it.first == "armBellAttempt" }.second
        assertTrue(
            "armBellAttempt 不再用 isSuccess 交结论：把失败读成成功的形状就回来了\n$attemptBody",
            attemptBody.contains(".isSuccess"),
        )

        val reminder = withoutComments(readMainSource(REMINDER_FILE))
        val arm = balancedBlock(reminder, "private fun armOneReminder(")
        assertTrue("armOneReminder 的 runCatching 没人接：并列时哪一枚没排上就查不出来了\n$arm", arm.contains(".onFailure"))
        assertTrue("留痕必须带槽位与课程 id，否则并列时认不出是哪一门\n$arm", arm.contains("slot=\$slot"))
    }

    /**
     * **抹注释找锚点**：并列判据本体里不许再出现 `minByOrNull`。
     *
     * `minByOrNull` 取的是**第一个**最小键，于是"同一时刻两门课"里谁被提醒交给数据库行序，
     * 另一门在下一次重排时触发时刻已过 ⇒ 被"已过即跳"那判吃掉。判据换成 `minOf` + 按最小键
     * 整等过滤；分组键必须是 `triggerAtMillis`（不是 classStart，也不是"加 id 当第二键继续只取
     * 一枚"—— 那只是把行序换成 id 序，仍然只有一门被提醒）。
     */
    @Test
    fun tiedGroupKernelNoLongerPicksTheFirstMinimum() {
        val code = withoutComments(readMainSource(REMINDER_FILE))
        val kernel = balancedBlock(code, "fun planTiedReminders(")

        assertFalse("planTiedReminders 里又出现 minByOrNull：并列吃课回来了\n$kernel", kernel.contains("minByOrNull"))
        assertTrue("判据不再按最小键取一等（缺 minOf { it.triggerAtMillis }）：$kernel", kernel.contains("minOf { it.triggerAtMillis }"))
        assertTrue(
            "分组键不再是触发时刻（换成 classStart、或加第二键只取一枚，都还是同一件事）：$kernel",
            kernel.contains("filter { it.triggerAtMillis == earliest }"),
        )
        assertTrue("等首那格必须带上同刻伙伴：$kernel", kernel.contains("sameInstantPartners = partners"))
        assertTrue(
            "整等要交给排程那一步读（armTiedReminders 不读 armableTogether() 就是又只排一枚）",
            balancedBlock(code, "private fun armTiedReminders(").contains("armableTogether()"),
        )
    }

    /**
     * **逐字节**：新加的留痕原文与级别必须逐字活着，级别只认 `Log.w`。
     *
     * 本仓真机侧 HyperOS 把 logcat 截在 Info ⇒ `Log.d` 在那台机器上等于没写。
     * 两枚留痕口都是文件顶层属性（`android.util.Log` 在 JVM 单测里是抛 "not mocked" 的桩，
     * 写进函数体就先崩在报告口之前，同 `LOG_RESCHEDULE_WINDOW_FAILURE` 的理由）。
     */
    @Test
    fun failureLogsSurviveAtWarnLevel() {
        val scheduler = readMainSource(SCHEDULER_FILE)
        val reminder = readMainSource(REMINDER_FILE)

        for (anchor in listOf(
            "Log.w(\"ClassProgressScheduler\", reason, error)",
            "省电档排上课铃失败：这一节的实况与自动勿扰都不会自己开始",
            "退回精确档那一手也没排上课铃",
            "省电档排下课铃失败",
            "非精确档也没排上下课铃",
        )) {
            assertTrue(
                "ClassProgressScheduler.kt 里查不到逐字节原文「$anchor」：留痕被改名或降级了",
                scheduler.contains(anchor),
            )
        }
        for (anchor in listOf("调度课程提醒失败 slot=", "门排不出去", "slotting.droppedCount", "本轮课堂铃没排上")) {
            assertTrue("ReminderScheduler.kt 里查不到逐字节原文「$anchor」", reminder.contains(anchor))
        }

        val code = withoutComments(scheduler)
        assertEquals("ClassProgressScheduler.kt 里的 Log 调用只许是顶层两枚留痕口", 2, occurrences(code, "Log."))
        assertEquals("那两枚留痕口都必须是 Log.w（本机 Info 闸门以下读不到）", 2, occurrences(code, "Log.w("))
        for (level in listOf("Log.d(", "Log.i(", "Log.v(", "Log.e(")) {
            assertEquals("ClassProgressScheduler.kt 里出现了 $level：排铃失败不许换级别留痕", 0, occurrences(code, level))
        }
    }

    /**
     * **数出现次数**：`rescheduleWindows` 的结论必须真的被念出来。
     *
     * 排铃失败写成一个返回值而没人读，等于把静默从 logcat 搬到返回值 —— 本仓已经有
     * 「写了没人念」这一族前科。这里钉的是：结论被接住恰好一处、整轮那句判据恰好一处
     * （多一处就是同一道失败被打两遍，审计 §4.4 的计数会废）。
     */
    @Test
    fun bellConclusionIsActuallyReadByTheCaller() {
        val code = withoutComments(readMainSource(REMINDER_FILE))
        val reschedule = balancedBlock(code, "fun rescheduleAll(")

        assertEquals("rescheduleAll 里接 rescheduleWindows 结论的地方不是恰好一处", 1, occurrences(reschedule, "bellsArmed ="))
        assertEquals("整轮那句判据不是恰好一处（同一道失败被打两遍的话 §4.4 计数作废）", 1, occurrences(reschedule, "if (!bellsArmed)"))
        assertTrue("那句整轮判据必须走 Log.w：$reschedule", reschedule.contains("Log.w(TAG, \"本轮课堂铃没排上"))

        val scheduler = withoutComments(readMainSource(SCHEDULER_FILE))
        assertEquals("rescheduleWindows 不再把排铃结论交出去（return schedule(...) 没了）", 1, occurrences(scheduler, "return schedule(context, window)"))
        assertEquals("schedule 里上课铃与下课铃必须各自拿到结论（startArmed 只出现一处赋值）", 1, occurrences(scheduler, "startArmed ="))
    }

    // ---- 源码核对工具（口径同 ClassProgressCleanupDecisionTest）------------------

    private class SourceFile(val relative: String, val file: File) {
        val text: String get() = file.readText()
    }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun mainSources(): List<SourceFile> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { SourceFile(it.relativeTo(root).path.replace('\\', '/'), it) }
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

    /** 块体函数：按花括号配平切函数体（签名里不许出现 lambda 默认值，见 `NO_RESCHEDULE_STEP_FAILURE`） */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：那条分支或函数改过名，这条守卫要跟着改" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号（改表达式体了就换 functionText）" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号配不上对")
    }

    /** 表达式体（`= runCatching(...)...`）取到下一个同级声明为止；块体仍然走配平 */
    private fun functionText(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：改过名就要跟着改这条守卫" }
        val open = source.indexOf('{', at)
        val head = source.substring(at, if (open < 0) source.length else open)
        // 签名与函数体之间出现 `=` 的是表达式体（`: Boolean = runCatching(...)`）
        if (head.contains("=") && !head.contains(") {")) return declarationUpToSibling(source, at)
        return balancedBlock(source, signature)
    }

    private fun declarationUpToSibling(source: String, at: Int): String {
        val lines = source.substring(at).lines()
        val out = StringBuilder(lines.first())
        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' }.length
            val sibling = indent <= SIBLING_INDENT &&
                (trimmed.startsWith("fun ") || trimmed.startsWith("private ") || trimmed.startsWith("internal ") ||
                    trimmed.startsWith("val ") || trimmed.startsWith("override ") || trimmed.startsWith("companion "))
            if (sibling) break
            out.append('\n').append(line)
        }
        return out.toString()
    }

    /**
     * 找出 body 里每一枚"裸着的 runCatching"：跳过它自己的 `{...}` 或 `(...)` 之后，
     * 下一个非空字符必须是 `.`（链式消费结论）。
     *
     * 两档判据（口径要分开，否则"用 `.isSuccess` 消费掉但不留痕"这种形状会被放行，
     * 而它恰恰就是病灶 B 的另一半：结论有了、logcat 里还是零证据）：
     * - **排铃那一手**（块体里没有 `canScheduleExactAlarms`）：链上必须出现
     *   `.onFailure` 或 `.recoverCatching`，这是"失败要能被看见"；
     * - **读授权那一问**（块体里就是 `canScheduleExactAlarms()`）：允许保守默认值
     *   （`.getOrDefault(false)` 判成"不能精确"，随后那一手仍会留痕），只要求结果被消费掉。
     */
    private fun bareRunCatching(name: String, body: String): List<String> {
        val offenders = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = body.indexOf(RUN_CATCHING, from)
            if (at < 0) return offenders
            from = at + RUN_CATCHING.length

            var i = from
            while (i < body.length && body[i].isWhitespace()) i++
            val opener = if (i < body.length) body[i] else ' '
            if (opener != '{' && opener != '(') {
                offenders += "$name：runCatching 后面不是块也不是调用（@$at）"
                continue
            }
            val closer = if (opener == '{') '}' else ')'
            var depth = 0
            var index = i
            while (index < body.length) {
                when (body[index]) {
                    opener -> depth++
                    closer -> depth--
                }
                if (depth == 0) break
                index++
            }
            if (depth != 0) {
                offenders += "$name：runCatching 的块没配平（@$at）"
                continue
            }
            val guarded = body.substring(i, index + 1)
            val after = body.substring(index + 1).trimStart()
            if (!after.startsWith(".")) {
                offenders += "$name：裸着的 runCatching，后面直接是「${after.take(16).replace("\n", "⏎")}」（@$at）"
            } else if (guarded.contains(EXACT_ALARM_QUERY)) {
                if (CHAIN_HANDLES.none { after.startsWith(it) }) {
                    offenders += "$name：读授权那一步连结果都没人接「${after.take(24).replace("\n", "⏎")}」（@$at）"
                }
            } else if (LOG_CHAIN.none { after.startsWith(it) }) {
                offenders += "$name：排铃那一手的 runCatching 没接 onFailure/recoverCatching" +
                    "（结论可以被消费，但失败必须留痕）「${after.take(24).replace("\n", "⏎")}」（@$at）"
            }
        }
    }

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

    /** 注释里提到 `runCatching`、`Log.` 与函数名都不算调用：先整段抹掉再判形状 */
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

    private companion object {
        const val REMINDER_FILE = "com/buaa/schedule/reminder/ReminderScheduler.kt"
        const val SCHEDULER_FILE = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"
        const val DECLARATION = "private const val REQUEST_CODE_REMINDER"
        const val LOOK_UP_CALL = "existingReminderSlot("
        const val RUN_CATCHING = "runCatching"

        /** 读授权那一问（允许保守默认值，不许无人接结果） */
        const val EXACT_ALARM_QUERY = "canScheduleExactAlarms"

        /** 排铃那一手必须接的链：留痕（onFailure）或"失败后换一手再试并留痕"（recoverCatching） */
        val LOG_CHAIN = listOf(".onFailure", ".recoverCatching")

        /** object 成员缩进就是 4 格，比它深的还函数体里 */
        const val SIBLING_INDENT = 4

        /** 消费掉 Result 的链式写法：出现其一即算"结论有人接" */
        val CHAIN_HANDLES = listOf(
            ".onFailure", ".recoverCatching", ".getOrDefault", ".isSuccess", ".isFailure",
            ".getOrNull", ".exceptionOrNull", ".onSuccess", ".fold", ".mapCatching",
        )
    }
}
