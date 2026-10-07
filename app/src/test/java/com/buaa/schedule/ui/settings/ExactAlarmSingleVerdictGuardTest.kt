package com.buaa.schedule.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T207 病灶 B 的源码形状守卫：钉住「设置页的精确闹钟判据与调度侧同一口径，且那枚会抛的调用没裸调」。
 *
 * 只做源码核对（JVM，无 Robolectric、无设备）。判据按四种钉法取，每一格的消息里点名自己用的是
 * 哪一种：**逐字节 / 抹注释找锚点 / 数出现次数 / 读形状**。行为侧的判据表在
 * `ui/settings/ReminderGuidanceExactAlarmTest`（十三格），本守卫钉的是**口径不回潮**与
 * **「Build.VERSION 判据留在调用点」这条硬口径**：
 * - 调度侧四家（加第五家 CourseFluidService）逐家点名，各家那枚受守卫的读数枚数一字不动；
 * - `ReminderGuidance` 抹注释后「`?: return true`」「`return true`」都 0 处 —— 服务拿不到答「可用」
 *   那一族不许回来；同时靶子（注释里点名的旧原文）必须还在；
 * - 判据本体（`canScheduleExactAt`）形状：零 `Build.`、零 `Context`、零 `getSystemService`、
 *   零 `runCatching` ⇒ 它必须能在纯 JVM 上被表驱动断言；
 * - 版本闸门与吞异常两手都留在调用点那一层（读形状）。
 */
class ExactAlarmSingleVerdictGuardTest {

    // ─────────────── ① 调度侧四家逐一点名（数出现次数） ───────────────

    @Test
    fun `调度侧四家的守卫读数一枚不多一枚不少`() {
        for ((file, expected) in SCHEDULER_SIDES) {
            val code = blankCommentsKeepingLiterals(source(file))
            val actual = occurrences(code, GUARDED_READ)
            assertEquals(
                "**钉法 = 数出现次数**：${shortName(file)} 里「runCatching { alarmManager.canScheduleExactAlarms() }" +
                    ".getOrDefault(false)」必须恰 $expected 处（实到 $actual 处）。\n" +
                    "复算：grep -Fn '$GUARDED_READ' app/src/main/java/$file\n" +
                    "少了 = 那一家的兜底方向被换走（或改成裸调），设置页就不再与它同一口径；" +
                    "多了 = 新长了第二处读数，得回判据表补格子",
                expected,
                actual,
            )
            // 每一家：会抛的那枚调用**全部**长在守卫里（抹注释后两处枚数必须相等）
            assertEquals(
                "**钉法 = 数出现次数**：${shortName(file)} 里 `canScheduleExactAlarms` 的每一次调用都必须被守卫包着" +
                    "（裸调一次就是把「设置页可以说可用而实际没排」或「整页崩」重新装回去）。" +
                    "读数 ${occurrences(code, "canScheduleExactAlarms")} 处 vs 受守卫 $actual 处",
                occurrences(code, "canScheduleExactAlarms"),
                actual,
            )
        }
    }

    /**
     * 第五家：`CourseFluidService.keepsAlarmClockExemption` 把「服务拿不到」与「读数抛」一起包进
     * 同一枚 `runCatching { … }.getOrDefault(false)`（形状与另外四家不同：SDK 档在前、服务缺失
     * 走 `?: return@runCatching false`）。本卡按它取齐**方向**，不是按它取齐**写法**。
     */
    @Test
    fun `第五家也按保守方向答`() {
        val code = blankCommentsKeepingLiterals(source(COURSE_FLUID))
        val (block, trailer) = balancedBlockWithTrailer(code, "private fun keepsAlarmClockExemption(", "keepsAlarmClockExemption")

        assertEquals("**钉法 = 读形状**：服务拿不到那一档必须答 false（`?: return@runCatching false`）", 1, occurrences(block, "?: return@runCatching false"))
        assertEquals(
            "**钉法 = 读形状**：整枚 runCatching 的兜底方向是 `.getOrDefault(false)`（块尾那一句，判不出来就按「没豁免」答）。实到「$trailer」",
            ".getOrDefault(false)",
            trailer,
        )
        assertEquals("**钉法 = 数出现次数**：这一家读授权恰一枚", 1, occurrences(block, "canScheduleExactAlarms"))
        assertTrue(
            "**钉法 = 读形状**：这一家的 SDK 档在前（`if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runCatching true`），" +
                "本卡的判据本体跟着取同一个短路次序",
            block.contains("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runCatching true"),
        )
    }

    // ─────────────── ② 病灶不回潮：乐观兜底与裸调都是 0 处 ───────────────

    @Test
    fun `服务拿不到不再答可用 会抛的读数不再裸调`() {
        val raw = source(GUIDANCE)
        val code = blankCommentsKeepingLiterals(raw)

        for (needle in listOf("?: return true", "return true")) {
            assertEquals(
                "**钉法 = 数出现次数（抹注释后）**：`ReminderGuidance` 里「$needle」必须 0 处 —— " +
                    "判不出来（服务没拿到 / 系统抛）一律按「不可用」答。\n" +
                    "复算：grep -Fn '$needle' app/src/main/java/$GUIDANCE\n" +
                    "红了 = 乐观兜底回来了（T207 病灶 B 的第一格）",
                0,
                occurrences(code, needle),
            )
        }
        // 靶子：旧原文必须在**注释**里留痕（否则上面那个 0 可能是「注释被删干净」）
        assertTrue(
            "旧写法 `?: return true` 必须还在 KDoc 里被点名（实到 ${occurrences(raw, "?: return true")} 处）—— " +
                "第 ② 层那个 0 判的是代码，不是把说理删了",
            occurrences(raw, "?: return true") >= 1,
        )

        assertEquals(
            "**钉法 = 数出现次数**：全仓「拿不到服务就 return true」那一形必须 0 处（逐字针，抹注释后数）。\n" +
                "复算：grep -rn 'getSystemService(AlarmManager::class.java) ?: return true' app/src/main",
            0,
            occurrences(code, "getSystemService(AlarmManager::class.java) ?: return true"),
        )
    }

    // ─────────────── ③ 判据本体是纯函数（读形状） ───────────────

    @Test
    fun `判据本体零android零时钟 版本闸门留在调用点`() {
        val code = blankCommentsKeepingLiterals(source(GUIDANCE))
        val kernel = expressionWindow(code, KERNEL_ANCHOR, "canScheduleExactAt")

        assertEquals(
            "**钉法 = 读形状**：判据本体必须恰两行（签名一行 + 三档一行），锚点到首个空行之间就是它的全部；" +
                "多一行就是有人在判据里另起了档位。实到 ${kernel.lines().size} 行：\n$kernel",
            2,
            kernel.lines().size,
        )
        for (banned in listOf("Build.", "SDK_INT", "Context", "getSystemService", "runCatching", "AlarmManager")) {
            assertFalse(
                "**钉法 = 读形状**：判据本体里不许出现「$banned」—— 本仓硬口径是 Build.VERSION 判据留在**调用点**当参数传入，" +
                    "判据本体要能在纯 JVM 上摆表断言（`ui/settings/ReminderGuidanceExactAlarmTest` 十三格）。\n$banned",
                kernel.contains(banned),
            )
        }
        assertTrue("**钉法 = 读形状**：SDK 档用调用点交进来的 sdkInt 与常数 API_ANDROID_12 判", kernel.contains("sdkInt < API_ANDROID_12"))
        assertTrue("**钉法 = 读形状**：读数档只认 Granted（判不出来 / 拒绝都落到 false）", kernel.contains("reading == ExactAlarmReading.Granted"))
        assertEquals(
            "**钉法 = 数出现次数**：判据里字面 `true` 只许出现在 SDK 那一档（恰一枚）；" +
                "出现第二枚 = 又有一档被无条件放行。实到 ${occurrences(kernel, "true")} 枚",
            1,
            occurrences(kernel, "true"),
        )
    }

    /** 调用点那一层：版本闸门 + 取服务 + 吞异常三手都在，且都朝保守。 */
    @Test
    fun `调用点留版本闸门并把系统抛吞成判不出来`() {
        val code = blankCommentsKeepingLiterals(source(GUIDANCE))
        val block = balancedBlock(code, "fun canScheduleExact(context: Context): Boolean {", "canScheduleExact")
        val flat = block.replace(Regex("""\s+"""), " ")

        assertTrue("**钉法 = 读形状**：闸门留在调用点（`Build.VERSION.SDK_INT < Build.VERSION_CODES.S`）", block.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.S"))
        assertEquals("**钉法 = 数出现次数**：结论一律交给判据本体（`canScheduleExactAt(` 恰两枚：闸门那一档 + 末尾一档）", 2, occurrences(block, "canScheduleExactAt("))
        assertEquals("**钉法 = 数出现次数**：取服务这一手也包在 runCatching 里（第五家 CourseFluidService 同形）", 1, occurrences(block, "runCatching { context.getSystemService(AlarmManager::class.java) }.getOrNull()"))
        assertTrue(
            "**钉法 = 读形状**：会抛的那枚读数必须长在 runCatching 里（改前是 `return manager.canScheduleExactAlarms()` 裸调）",
            flat.contains("runCatching { if (manager.canScheduleExactAlarms()) ExactAlarmReading.Granted else ExactAlarmReading.Denied }.getOrDefault(ExactAlarmReading.Unreadable)"),
        )
        assertTrue(
            "**钉法 = 读形状**：服务拿不到那一档折成 Unreadable（判不出来），不是折成「可用」",
            flat.contains("if (manager == null) { ExactAlarmReading.Unreadable"),
        )
        assertFalse("**钉法 = 读形状**：旧那一形不许回来（裸调）", flat.contains("return manager.canScheduleExactAlarms()"))
    }

    // ─────────────── ④ 册子：谁在读这枚判据、谁在碰这枚 API（数出现次数） ───────────────

    @Test
    fun `会抛的读数只长在六枚文件里 用户面读者恰好三家`() {
        val byFile = allMainSources().mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        val touchers = byFile.filter { occurrences(it.value, "canScheduleExactAlarms") > 0 }.keys.sorted()
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：碰 `canScheduleExactAlarms` 的文件今天恰好六枚 = 调度侧五家 + " +
                "设置页一枚（本卡的收口面）。多一枚 = 又有一处自己判精确闹钟、绕开唯一口径；" +
                "少一枚 = 有一家的守卫被摘了。\n复算：grep -rln 'canScheduleExactAlarms' app/src/main/java\n实到：\n" +
                touchers.joinToString(separator = "") { "  $it\n" },
            listOf(
                "com/buaa/schedule/reminder/ClassProgressDnd.kt",
                "com/buaa/schedule/reminder/ClassProgressScheduler.kt",
                "com/buaa/schedule/reminder/CourseFluidService.kt",
                "com/buaa/schedule/reminder/ReminderScheduler.kt",
                "com/buaa/schedule/reminder/TomorrowPreviewReceiver.kt",
                GUIDANCE,
            ),
            touchers,
        )

        val readers = byFile.filter { occurrences(it.value, "ReminderGuidance.canScheduleExact(") > 0 }.keys.sorted()
        assertEquals(
            "**钉法 = 数出现次数（读者册子）**：念这枚判据的用户面恰好三家 —— 设置页那一行「精确闹钟 / 提醒将准时触发」、" +
                "冷启动环境自检那一档、组件配置页那块提示。⇒ 病灶 B 的「写错的那格」用户**看得见**（三处都是文案与色，" +
                "不是一句日志）。\n实到：\n" + readers.joinToString(separator = "") { "  $it\n" },
            listOf(
                "com/buaa/schedule/ui/onboarding/EnvironmentCheck.kt",
                "com/buaa/schedule/ui/settings/SettingsScreen.kt",
                "com/buaa/schedule/widget/WidgetConfigActivity.kt",
            ),
            readers,
        )
        assertEquals(
            "**钉法 = 数出现次数**：三家各读一次（合计恰三枚）—— 同一页读两遍就是两本口径又长回来了",
            3,
            byFile.values.sumOf { occurrences(it, "ReminderGuidance.canScheduleExact(") },
        )
    }

    // ---------------- helpers（与本仓守卫同一套写法） ----------------

    /** 工作树是 CRLF：判据里的多行针（expressionWindow / 空白折叠后的形状）一律按 LF 读 */
    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText().replace("\r\n", "\n")

    private fun allMainSources(): Map<String, String> {
        val root = findMainJavaDir()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') to it.readText() }
            .toMap()
    }

    private fun shortName(relative: String): String = relative.substringAfterLast('/')

    /** 从锚点起按花括号配平取整块（含锚点自己）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到块的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /** 表达式体函数：从锚点起到**首个空行**为止（花括号配平对 `= if (...) …` 那一无效） */
    private fun expressionWindow(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val blankLine = code.indexOf("\n\n", at)
        assertTrue("$label 找不到收尾的空行（判据本体被写长了，或它上面挤进了别的语句）", blankLine >= 0)
        return code.substring(at, blankLine)
    }

    /**
     * 花括号块 + **紧跟其后的那一行**（`}.getOrDefault(false)` 这种"兜底方向"就写在块尾那一行，
     * 只取配平区间会把它漏在外面）。
     */
    private fun balancedBlockWithTrailer(code: String, anchor: String, label: String): Pair<String, String> {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到块的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val block = code.substring(at, i + 1)
                        val trailer = code.substring(i + 1).lineSequence().first().trim()
                        return block to trailer
                    }
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 只抹注释、保留字符串字面量，长度与换行位置不变：钉的是代码，不是白话 */
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
        const val GUIDANCE = "com/buaa/schedule/ui/settings/ReminderGuidance.kt"
        const val COURSE_FLUID = "com/buaa/schedule/reminder/CourseFluidService.kt"
        const val GUARDED_READ = "runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)"
        const val KERNEL_ANCHOR = "internal fun canScheduleExactAt(sdkInt: Int, reading: ExactAlarmReading): Boolean ="

        /** 卡面点名的四家（逐家枚数按盘面）：ClassProgressScheduler 一家两处 ⇒ 全仓五处而不是四处 */
        val SCHEDULER_SIDES: Map<String, Int> = linkedMapOf(
            "com/buaa/schedule/reminder/ReminderScheduler.kt" to 1,
            "com/buaa/schedule/reminder/ClassProgressScheduler.kt" to 2,
            "com/buaa/schedule/reminder/ClassProgressDnd.kt" to 1,
            "com/buaa/schedule/reminder/TomorrowPreviewReceiver.kt" to 1,
        )
    }
}
