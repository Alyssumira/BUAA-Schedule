package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码页的降级口径（T44 的 D2 + D3）。
 *
 * D2 的那条事实错得最隐蔽：`cameraLive` 旧写法是
 * `scanner != null && scannerWorking && granted && cameraError == null` —— **provider 不在里面**。
 * 而取 provider 那一颗 `LaunchedEffect(Unit)` 里包的是没有上限、没有日志、没有重试的
 * `runCatching { future.get() }`：future 永不完成时 `provider` 停在 null、绑定 effect 在
 * `provider ?: return` 那一句直接返回、`cameraError` 一个字都没写 —— 于是提示条五档全落空、
 * `cameraLive` 还成立，取景框就画在一块永远不会有画面的黑 `PreviewView` 上，
 * 手输签到码被压成最弱一档。这是与 D1 结构同型的第二个「没反应」。
 *
 * 为什么这两条判据要抽成纯函数、而不是写一条扫源码的守卫：提示档位一共有五支、
 * `cameraLive` 有六个乘项，只有把判据本体拿到 JVM 里才能逐支跑（仓库口径，
 * 见 [ScanSubmissionGateTest] 与 `WidgetSnapshotDirtyTest`）。接线本身另外用形状守卫钉住，
 * 保证页面没有偷偷再算一份自己的口径。
 *
 * 本文件零 `android` import。
 */
class ScanUiStatusTest {

    /** 一切正常那一档：什么都不提示 */
    private val healthy = mapOf(
        "decoderMissing" to false,
        "scannerUsable" to true,
        "granted" to true,
        "cameraError" to null as String?,
        "cameraProviderMissing" to false,
    )

    private fun statusOf(vararg overrides: Pair<String, Any?>): String? {
        val s = healthy + overrides.toMap()
        return scanUiStatus(
            decoderMissing = s["decoderMissing"] as Boolean,
            scannerUsable = s["scannerUsable"] as Boolean,
            granted = s["granted"] as Boolean,
            cameraError = s["cameraError"] as String?,
            cameraProviderMissing = s["cameraProviderMissing"] as Boolean,
        )
    }

    /** ① 健康态不提示（其余每一档都必须提示，否则就等于把 D2 那一档又写回"沉默"） */
    @Test
    fun healthyStateStaysSilent() {
        assertNull(statusOf())
    }

    /** ② D2 的正身：provider 没交出来必须自己说话，而且是**这一档专属**的话 */
    @Test
    fun missingCameraProviderSpeaks() {
        val text = statusOf("cameraProviderMissing" to true)
        assertNotNull("provider 取不到时提示条仍然一声不吭 —— 这就是第二个「没反应」", text)
        val message = requireNotNull(text)
        assertTrue("这一档的话没提相机开不起来：$message", message.contains("CameraX 起不来"))
        // 相册那条路仍然可用（scanner 在），所以这句话必须指着它，也不能只指着它
        assertTrue("provider 挂了但解码器还在，话里该留着相册那条出口：$message", message.contains("相册"))
        assertTrue("手输才是最后一档出口：$message", message.contains("手输"))
        // 与其余每一档的文案都不同（复用同一支就等于把两种病因说成一种）
        val others = listOf(
            statusOf("decoderMissing" to true),
            statusOf("scannerUsable" to false),
            statusOf("granted" to false),
            statusOf("cameraError" to "No back camera"),
        )
        others.forEach { assertNotNull(it) }
        assertEquals("五档文案彼此都不能重复", 5, (others + message).distinct().size)
    }

    /** ③ 档位次序：谁能决定用户下一步，谁在前 */
    @Test
    fun ladderPriorityFollowsTheNearestWayOut() {
        // 缺库压过一切（这时候提相册是死路）
        assertTrue(statusOf("decoderMissing" to true, "granted" to false, "cameraProviderMissing" to true)!!
            .contains("只能手输"))
        // 没权限时先说权限（provider 同时缺失也不抢这一档：放行之后还可能拿得到）
        assertTrue(statusOf("granted" to false, "cameraProviderMissing" to true)!!.contains("相机权限"))
        // 绑定失败的原因要原文透出来，别被泛化的话吃掉
        assertEquals("相机不可用（绑定失败：x），请改用下面两个入口。",
            statusOf("cameraError" to "绑定失败：x", "cameraProviderMissing" to true))
        // scanner 不可用（建不出来 / 跑起来坏了）压过权限
        assertTrue(statusOf("scannerUsable" to false, "granted" to false)!!.contains("用不了相机扫码"))
    }

    /** ④ cameraLive：六个乘项缺一就 false（D2 补的是前三个） */
    @Test
    fun cameraLiveRequiresEveryOneOfItsSixTerms() {
        val live = scanCameraLive(
            scannerAvailable = true,
            analyzerReady = true,
            cameraProviderReady = true,
            granted = true,
            scannerWorking = true,
            cameraError = null,
        )
        assertTrue(live)
        val flips = mapOf(
            "scannerAvailable" to false,
            "analyzerReady" to false,
            "cameraProviderReady" to false,
            "granted" to false,
            "scannerWorking" to false,
            "cameraError" to "boom",
        )
        flips.forEach { (key, value) ->
            val all = mapOf(
                "scannerAvailable" to true,
                "analyzerReady" to true,
                "cameraProviderReady" to true,
                "granted" to true,
                "scannerWorking" to true,
                "cameraError" to null as String?,
            ) + (key to value)
            assertFalse(
                "$key 不参与 cameraLive 的话，取景框就会画在死掉的预览上",
                scanCameraLive(
                    scannerAvailable = all["scannerAvailable"] as Boolean,
                    analyzerReady = all["analyzerReady"] as Boolean,
                    cameraProviderReady = all["cameraProviderReady"] as Boolean,
                    granted = all["granted"] as Boolean,
                    scannerWorking = all["scannerWorking"] as Boolean,
                    cameraError = all["cameraError"] as String?,
                ),
            )
        }
    }

    /**
     * ⑤ 接线形状：页面用的是这两份判据，没有自己再算一份。
     *
     * JVM 跑不到 Composable，只能按源码核对（手法同 `ColdStartRebuildWiringTest`）。
     * 钉的是两种"纸面修好了、界面照旧"的形态：判据写了但没人调，或者调了而页面里
     * 还留着第二份自己的口径。
     */
    @Test
    fun screenUsesTheJudgesAndKeepsNoPrivateCopy() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertEquals("scanUiStatus 被调了 ${occurrences(code, "scanUiStatus(")} 处，只能一处", 1, occurrences(code, "scanUiStatus("))
        assertEquals("scanCameraLive 被调了 ${occurrences(code, "scanCameraLive(")} 处，只能一处", 1, occurrences(code, "scanCameraLive("))
        // cameraLive：定义一处 + 取景框一处 + 手输那颗按钮的档位一处
        assertEquals("cameraLive 出现 ${occurrences(code, "cameraLive")} 次（定义 + 取景框 + 手输按钮档位）", 3, occurrences(code, "cameraLive"))
        // 页面里不许还藏着文案原文：那就是第二份口径
        for (lit in listOf("没有相机权限", "CameraX 起不来", "这份安装包没带")) {
            assertFalse("提示文案还在页面里另写一份（$lit），判据就被绕过了", code.contains(lit))
        }
        // provider 那一档是**真的**传给了判据，而不是只写了个没人读的字段
        assertTrue(code.contains("cameraProviderMissing = cameraProviderMissing"))
        assertTrue(code.contains("cameraProviderReady = provider != null"))
    }

    /** ⑥ D3：回到前台重读权限，而且读的是系统那边的真值 */
    @Test
    fun permissionIsReReadOnResume() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val observer = balancedBlock(code, "LifecycleEventObserver { _, event ->")
        assertTrue("ON_RESUME 没接：\n$observer", observer.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue("ON_RESUME 没有推动那个 tick：\n$observer", observer.contains("permissionResumeTick++"))
        // granted 必须以 tick 为键重建（无 key 的 remember{} 就是把快照钉死在进入那一刻）
        assertTrue("granted 还是无 key 的 remember{}：放行回来照样是旧值", code.contains("remember(permissionResumeTick)"))
        // 重读读的是真权限，不是某个缓存的猜测
        val read = balancedBlock(code, "var granted by remember(permissionResumeTick)")
        assertTrue("重读的不是 CAMERA 权限本身：\n$read", read.contains("Manifest.permission.CAMERA"))
        // 绑定与取 provider 都挂在 granted 上：翻面之后两条都会重跑
        assertTrue(code.contains("LaunchedEffect(granted, provider, scannerWorking, analyzer)"))
        assertTrue(code.contains("LaunchedEffect(granted)"))
    }

    /** ⑦ D2 的第三半：取 provider 有上限、重试一次，而且失败会落到那个字段上 */
    @Test
    fun providerAcquisitionIsBoundedAndRetriedOnce() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val effect = balancedBlock(code, "LaunchedEffect(granted)")
        assertTrue("取 provider 没走带上限的那一颗：\n$effect", effect.contains("cameraProviderWithRetry(context)"))
        assertTrue("拿不到时没置 cameraProviderMissing：\n$effect", effect.contains("cameraProviderMissing = true"))

        val body = balancedBlock(code, "private suspend fun cameraProviderWithRetry(")
        assertTrue("没有上限（future 永不完成就又回到老样子）：\n$body", body.contains("withTimeoutOrNull(ProviderTimeoutMillis)"))
        assertTrue(body.contains("cameraProviderOrNull(context)"))
        // 次数：首试 + 一次重试
        val attempts = readMainSource(SCAN_SCREEN_FILE).lines().first { it.contains("ProviderAttempts") && it.contains("const val") }
        assertTrue("重试次数写进了这一行，应当是 2（首试 + 一次）：$attempts", attempts.contains("= 2"))
        // 上限不许是 0 或负数（那等于没有等待窗口）；源码里写的是带下划线分隔的字面量
        val timeout = readMainSource(SCAN_SCREEN_FILE).lines().first { it.contains("ProviderTimeoutMillis") && it.contains("const val") }
        val millis = timeout.substringAfter("=").trim().trimEnd('L').replace("_", "").toLong()
        assertTrue("provider 等待上限只有 ${millis}ms，一次冷启动都不够它等：$timeout", millis in 1_000L..10_000L)
    }

    /** ⑧ 取 provider 那颗 effect 不再是 Unit 键（否则放行权限后不会再拿一次） */
    @Test
    fun providerEffectIsNotKeyedOnUnit() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertFalse(
            "还有一颗只跑一次的 LaunchedEffect(Unit) 在取 provider：",
            Regex("LaunchedEffect\\(Unit\\)\\s*\\{[^}]*cameraProvider").containsMatchIn(code),
        )
    }

    // ---- 源码核对工具（与 ColdStartRebuildWiringTest 同一套手法）----

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
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
        throw IllegalStateException("$signature 的花括号没配平")
    }

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

    /** 单测的 cwd 是 :app 模块目录，也可能是仓库根：两种布局都试，全落空就抛 */
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
        const val SCAN_SCREEN_FILE = "com/buaa/schedule/ui/signin/SpocScanScreen.kt"
    }
}
