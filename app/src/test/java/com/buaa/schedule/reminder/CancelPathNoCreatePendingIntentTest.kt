package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取消一条闹钟，不该顺手造出一个永远用不上的 PendingIntent（§2.8）。
 *
 * 同一件事在 `ReminderScheduler.cancelAll` 与 `ClassProgressScheduler.cancel` 里已经修过一轮，
 * 那边各留了一段注释说明为什么（FLAG_UPDATE_CURRENT 去取消 = 先创建一条记录再立刻撤掉）。
 * 这里把剩下两处收进同一口径：[TomorrowPreviewScheduler.cancel] 与
 * `com.buaa.schedule.widget.BackgroundSync.cancelWidgetMidnight` —— 它们此前和"排"共用一个
 * FLAG_UPDATE_CURRENT 的 builder。
 *
 * ⚠️ 修法**不是**把那个 builder 的 flag 改掉：同一个 builder 还伺候着排的那条路
 * （`schedule` / `scheduleWidgetMidnight`），换成 NO_CREATE 之后，还没排过的闹钟在
 * "排"这一步就会拿到 null —— 明日预告与零点刷新静默不排，比原来那条多余的 PI 记录严重得多。
 * 所以拆成两个 builder：排继续 UPDATE_CURRENT（负责把 PI 造出来），
 * 取消走 NO_CREATE + 判 null（查不到就是没排过，本来就是 no-op）。
 *
 * 本模块单测没有 Robolectric，`PendingIntent.getBroadcast` 要的 Context 在 JVM 里拿不到，
 * 所以这条钉的是结构：两处取消路径的函数体里只许出现"只查不造"的那个 builder，
 * 两处排程路径里不许出现另一个。写法与 GlassSurfaceSingleChildTest 同一套源码核对。
 */
class CancelPathNoCreatePendingIntentTest {

    @Test
    fun tomorrowPreviewCancelOnlyLooksUpAnExistingIntent() {
        val source = mainText(RECEIVER_FILE)
        assertCancelOnlyLooksUp(bodyOf(source, "fun cancel(context: Context)"), "pendingIntent(")
    }

    @Test
    fun widgetMidnightCancelOnlyLooksUpAnExistingIntent() {
        val source = mainText(BACKGROUND_SYNC_FILE)
        assertCancelOnlyLooksUp(bodyOf(source, "fun cancelWidgetMidnight(context: Context)"), "midnightPendingIntent(")
    }

    /**
     * 排那两条路不许被"顺手"改掉：一旦把 UPDATE_CURRENT 换成 NO_CREATE，
     * 第一次排闹钟就拿到 null，预告与零点刷新会静默消失（这才是本条最大的坑）。
     */
    @Test
    fun schedulingPathsStillCreateTheirPendingIntent() {
        val receiver = mainText(RECEIVER_FILE)
        val scheduleBody = bodyOf(receiver, "fun schedule(context: Context, previewDay: LocalDate)")
        assertTrue("排预告这一路要用会创建 PI 的 builder", scheduleBody.contains("pendingIntent(context)"))
        assertTrue("而且它不能改成只查不造", !scheduleBody.contains("existingPendingIntent("))
        assertTrue(
            "排闹钟用的 builder 必须保持 FLAG_UPDATE_CURRENT",
            declOf(receiver, "private fun pendingIntent(context: Context)").contains("FLAG_UPDATE_CURRENT"),
        )

        val background = mainText(BACKGROUND_SYNC_FILE)
        // 签名带上了"探测结论"这个默认参数（冷启动那条链一次唤醒只问一遍组件，审计 §2.1）：
        // 这里按前缀切，函数体仍是那一个
        val midnightBody = bodyOf(background, "fun scheduleWidgetMidnight(context: Context, hasAnyWidget: Boolean")
        assertTrue("零点刷新要用会创建 PI 的 builder", midnightBody.contains("midnightPendingIntent(context)"))
        assertTrue("而且它不能改成只查不造", !midnightBody.contains("existingMidnightPendingIntent("))
        assertTrue(
            "排闹钟用的 builder 必须保持 FLAG_UPDATE_CURRENT",
            declOf(background, "private fun midnightPendingIntent(context: Context)").contains("FLAG_UPDATE_CURRENT"),
        )
    }

    private fun assertCancelOnlyLooksUp(body: String, scheduleBuilder: String) {
        val lookUp = "existing${scheduleBuilder.replaceFirstChar { it.uppercase() }}"
        assertTrue(
            "取消路径要用 FLAG_NO_CREATE 的那个 builder（口径同 ReminderScheduler.cancelAll）",
            body.contains(lookUp),
        )
        // "只查不造"那个名字本身就含着排闹钟 builder 的名字，先摘掉再看有没有第二处
        assertTrue(
            "取消路径不许再调用会创建 PendingIntent 的 builder：$scheduleBuilder 白造一条记录",
            !body.replace(lookUp, "").contains(scheduleBuilder),
        )
        assertTrue("取消路径里不该出现 FLAG_UPDATE_CURRENT", !body.contains("FLAG_UPDATE_CURRENT"))
    }

    // ---- 源码核对：函数体按花括号配平切出来，注释不参与判断 ----------------------

    /** 表达式体的 builder（没有花括号可配平）：取到下一个同级声明为止 */
    private fun declOf(source: String, signature: String): String {
        val code = withoutComments(source)
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：builder 改名或挪过家，这条守卫要跟着改" }
        val lines = code.substring(at).lines()
        val declaration = StringBuilder(lines.first())
        for (line in lines.drop(1)) {
            if (isSiblingDeclaration(line)) break
            declaration.append('\n').append(line)
        }
        return declaration.toString()
    }

    /** `local fun` 与缩进更深的嵌套函数都还在函数体里，不算同级声明 */
    private fun isSiblingDeclaration(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("local ")) return false
        if (line.takeWhile { it == ' ' }.length > SIBLING_INDENT) return false
        return listOf("private ", "internal ", "public ", "override ", "suspend ").fold(trimmed) { rest, m ->
            rest.removePrefix(m)
        }.let { it.startsWith("fun ") || it.startsWith("val ") || it.startsWith("companion ") }
    }

    private fun bodyOf(source: String, signature: String): String {
        val code = withoutComments(source)
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：函数改名或挪过家，这条守卫要跟着改" }
        val open = code.indexOf('{', code.indexOf(')', at))
        check(open >= 0) { "$signature 后面没有函数体" }
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) return code.substring(open, i + 1)
        }
        throw IllegalStateException("$signature 的花括号配不上对")
    }

    /** 注释里提到 builder 名字不算调用（KDoc 里正是拿它当话说的那种提及） */
    private fun withoutComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        val text = out.toString()
        return text.lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    private fun mainText(relative: String): String {
        val file = File(findMainJavaDir(), relative)
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

    private companion object {
        const val RECEIVER_FILE = "com/buaa/schedule/reminder/TomorrowPreviewReceiver.kt"
        const val BACKGROUND_SYNC_FILE = "com/buaa/schedule/widget/BackgroundSync.kt"

        /** 这两个文件里 object 的成员缩进就是 4 格，比它深的一定还在某个函数体里 */
        const val SIBLING_INDENT = 4
    }
}
