package com.buaa.schedule.core.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生效档位那一族的接线守卫（T315③）。
 *
 * 为什么要一枚静态守卫而不是只靠单测：T315 第 0 问裁的是「假设式 = 被测函数自己」那一形，
 * 而在核数 ≤4 的机器上（`glassStaticCapTier` 那条四核判据），`effectiveTier` 对**两枚**偏好的
 * 真值都是 OFF ⇒ 反例「把 `effectiveTier` 改成恒回 `GLASS_TIER_OFF`（玻璃对所有人永久关）」在
 * **返回值**那一层与正解不可分辨，任何运行时断言都摸不到它。那种机器上唯一还能说话的钉子是
 * 磁盘上的字：下面四格全部只读文本（抹注释找锚点）、零假设、与核数无关。
 *
 * 读盘面：`app/src/main/java` 整目录已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles`
 * 里登记成测试任务的输入（T86 / T92①，本卡又补了 ⑪ 那一格）⇒ 改这些文件的**注释**也会重跑，
 * 这一族不会有「改了字却端旧绿」那一档。第 ④ 格读的那枚测试文件本来就在编译输入里。
 */
class GlassEffectiveTierWiringGuardTest {

    /**
     * ① 调用点只许是把两枚设备事实递给判据内核的那一行；夹取那两条表达式不许留在 `GlassGovernance.kt` 里。
     *
     * 抓三档：把 `effectiveTier` 改回内联夹取（判据第二本账）、改成恒回某一档（本卡点名的反例）、
     * 把递进去的两枚设备事实里的一枚换成字面量（摘参照物）。
     */
    @Test
    fun effectiveTierIsAThinForwarderToTheKernel() {
        val governance = blankedMainSource(GOVERNANCE_FILE)
        assertEquals(
            "钉法 = 逐字节数出现次数（抹注释找锚点）：`GlassGovernance.kt` 的代码里 `effectiveTier` 只许" +
                "那一行转发（实到 " + occurrences(governance, FORWARDER) + " 处）。改回内联夹取、改成恒回 " +
                "`GLASS_TIER_OFF`、或把两枚设备事实里的一枚换成字面量 —— 都当场红",
            1,
            occurrences(governance, FORWARDER),
        )
        assertEquals(
            "钉法 = 同源：静态那把钳子只许写在判据内核里，`GlassGovernance.kt` 里再出现一遍就是第二把尺" +
                "（实到 " + occurrences(governance, STATIC_CLAMP) + " 处）",
            0,
            occurrences(governance, STATIC_CLAMP),
        )
    }

    /**
     * ② 判据内核里两把钳子都在、顺序也没换：先按静态上限夹，再（仅当运行时降档存在时）按运行时上限夹。
     */
    @Test
    fun kernelKeepsBothClampsInTheOriginalOrder() {
        val decision = blankedMainSource(DECISION_FILE)
        assertEquals(
            "钉法 = 逐字节数出现次数：静态那把钳子在 `GlassJankDecision.kt` 的代码里恰好 1 处" +
                "（实到 " + occurrences(decision, STATIC_CLAMP) + " 处）—— 摘掉它就是朝窄",
            1,
            occurrences(decision, STATIC_CLAMP),
        )
        assertEquals(
            "钉法 = 逐字节数出现次数：运行时那把钳子恰好 1 处，且带着「null = 未降档」那一支" +
                "（实到 " + occurrences(decision, RUNTIME_CLAMP) + " 处）",
            1,
            occurrences(decision, RUNTIME_CLAMP),
        )
        assertTrue(
            "钉法 = 顺序：静态夹取必须排在运行时夹取之前（改前那两条表达式就是这个顺序）——" +
                "静态位 " + decision.indexOf(STATIC_CLAMP) + " ／运行时位 " + decision.indexOf(RUNTIME_CLAMP),
            decision.indexOf(STATIC_CLAMP) in 0 until decision.indexOf(RUNTIME_CLAMP),
        )
    }

    /**
     * ③ 纯 JVM 判据边界：判据不许读设备度量与时钟。整个 `GlassJankDecision.kt` 是判据文件，
     * 所以按文件级扫（抹注释后数代码里的探针调用）—— 本卡把上限做成注入参数，设备事实的落点
     * 只剩调用点那一侧（`staticCap` 那枚 lazy 读内存与核数）。
     */
    @Test
    fun kernelFileReadsNoDeviceMetricsOrClock() {
        val decision = blankedMainSource(DECISION_FILE)
        val offenders = FORBIDDEN_IN_KERNEL
            .map { needle -> needle to occurrences(decision, needle) }
            .filter { (_, count) -> count > 0 }
        assertTrue(
            "钉法 = 数出现次数：判据文件 `GlassJankDecision.kt` 的**代码**里这些探针一枚都不许出现" +
                "（内存、核数、挂钟都只许当参数从调用点递进来）—— 实到：" +
                offenders.joinToString(" ｜ ") { (needle, count) -> needle + " ×" + count },
            offenders.isEmpty(),
        )
    }

    /**
     * ④ 那一形的墓碑：`GlassGovernanceTest.kt` 里不许再有那枚开道针。
     *
     * 改前的形状是「拿 `effectiveTier` 自己的返回值当前提，前提不成立就整格静默跳过」，而
     * `effectiveTier` 在全测试树里的命中只有那一格内四处 = 唯一覆盖。这一格钉的是「要说得出话，
     * 就不许用被测函数给自己开路」这条裁定本身 —— 不看核数、不看档位，只数字。
     * ⚠️ 针本身拼出来取（同 `GlassGovernance.kt` 里那枚调试 DN 的手法），免得这一枚守卫自己数进册子。
     */
    @Test
    fun runtimeCapCellNeverAssumesTheFunctionUnderTest() {
        val text = blankedTestSource(GLASS_GOVERNANCE_TEST)
        assertEquals(
            "钉法 = 数出现次数：`GlassGovernanceTest.kt` 的代码里那枚开道针只许 0 枚（实到 " +
                occurrences(text, ASSUME_NEEDLE) + " 枚）—— 换成读底层探针也算朝宽，换完仍然是一枚" +
                "「静态判 OFF 就不说话」的格子；本卡裁的是这一格在任何核数上都要断言",
            0,
            occurrences(text, ASSUME_NEEDLE),
        )
    }

    // ---- 读盘与小工具 ------------------------------------------------------------------------------

    private fun blankedMainSource(fileName: String): String {
        val file = File(findMainJavaDir(), fileName)
        assertTrue("找不到 ${file.path}：这一族的接线判据依赖它，挪过家要回来改这里", file.isFile)
        return blankComments(file.readText())
    }

    private fun blankedTestSource(relative: String): String {
        val file = File(findTestJavaDir(), relative)
        assertTrue("找不到 ${file.path}：这一格读的墓碑依赖它", file.isFile)
        return blankComments(file.readText())
    }

    /** 与同族守卫同一写法：几种工作目录布局都试一遍 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { candidate -> File(dir, candidate) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private fun findTestJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/test/java", "app/src/test/java")
                .map { candidate -> File(dir, candidate) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/test/java：当前目录 ${File("").absolutePath}")
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

    /** 抹注释保行号，字符串不动：与 UserVisibleParenBalanceGuardTest 同一写法 */
    private fun blankComments(src: String): String {
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
                            src.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }

                            src.startsWith("*/", j) -> {
                                depth--
                                j += 2
                            }

                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> {
                                j++
                                break
                            }

                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return out.toString()
    }

    private companion object {
        const val GOVERNANCE_FILE = "GlassGovernance.kt"
        const val DECISION_FILE = "GlassJankDecision.kt"
        const val GLASS_GOVERNANCE_TEST = "com/buaa/schedule/core/designsystem/GlassGovernanceTest.kt"

        const val FORWARDER =
            "fun effectiveTier(preferred: Int): Int = glassEffectiveTier(preferred, staticCap, runtimeCap)"
        const val STATIC_CLAMP = "val clamped = preferred.coerceAtMost(staticCap)"
        const val RUNTIME_CLAMP =
            "return if (runtimeCap != null) clamped.coerceAtMost(runtimeCap) else clamped"

        /** 拼出来取：本文件不许数进自己读的册子里 */
        val ASSUME_NEEDLE = "ass" + "umeTrue" + "("

        /** 设备度量与时钟探针：判据文件的代码里一枚都不许出现 */
        val FORBIDDEN_IN_KERNEL = listOf(
            "Runtime.getRuntime(",
            "System.currentTimeMillis(",
            "System.nanoTime(",
            "android.os.Build",
            "availableProcessors(",
            "maxMemory(",
        )
    }
}
