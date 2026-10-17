package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡 T423（②的另一半）：玻璃位图缓存那记清入口的**形状**守卫 —— 五格各钉一件事，
 * 全部只读 `app/src/main/java` 的工作树文本，按声明块取块，一枚行号都不写。
 *
 * ## 为什么主尺是读形状
 * 本卡点名的三格全是「说不清就写歪」的那一类：清入口到底只做丢引用还是顺手回收、
 * 键给不出来那一格到底只留读数还是偷偷加了处置、`BUAAApplication.onTrimMemory` 到底
 * 把档位交出去还是自己又判了一遍。逐字节与数出现次数只在各自格子里当副钉，
 * 次序与块归属才拦得住「把那一手挪到别处」这一族改法。
 *
 * ## 不新增抹注释助手
 * 本守卫不需要 `blankComments` 那一族写法（会顶掉
 * `CommentStrippingHelperShapeGuardTest` 钉着的绝对值）：需要看代码的地方都按
 * **声明块**切（函数体里没有注释），需要看注释的地方（丙那一格）反而要**只**读注释块。
 *
 * ## 输入面
 * 只读两枚在册文件的文本：`WidgetBackgroundRenderer.kt` 与 `BUAAApplication.kt`。
 * 该目录整体已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里进
 * `tasks.withType<Test>()` 的 inputs；本卡没有新增读取面，零设备、不读钟、不联网。
 */
class WidgetGlassCacheMemoryPressureGuardTest {

    // ---- ① 清入口只做「丢引用」这一手 ----

    @Test
    fun clearEntryDropsReferencesAndNeverRecycles() {
        val renderer = readMainSource(RENDERER_FILE)
        val block = balancedBlock(renderer, "fun clearOnMemoryPressure() {")

        assertEquals("清入口里 entries.clear() 只许一记：\n$block", 1, occurrences(block, "entries.clear()"))
        FORBIDDEN_RECYCLE.forEach { needle ->
            assertEquals(
                "清入口里出现了「$needle」：逐出与清场都不许对位图做处置 —— " +
                    "这张图可能已经交给 RemoteViews、还在 binder 事务里排着：\n$block",
                0,
                occurrences(block, needle),
            )
        }
        assertEquals("清入口不许再烤一张：\n$block", 0, occurrences(block, "bake"))
        assertTrue(
            "清入口必须与 obtain 抢同一枚 monitor（@Synchronized），否则会出现一边写一边清的半条：\n$block",
            renderer.contains("@Synchronized\n    fun clearOnMemoryPressure() {"),
        )
    }

    // ---- ② object 上那一记公开入口：先问纯判据，再清 ----

    @Test
    fun productionEntryAsksThePurePredicateBeforeClearing() {
        val renderer = readMainSource(RENDERER_FILE)
        val block = balancedBlock(renderer, "fun releaseGlassCacheForMemory(level: Int, backgroundFloor: Int) {")

        val ask = block.indexOf("widgetGlassCacheReleasesAtTrimLevel(level, backgroundFloor)")
        val clear = block.indexOf("glassCache.clearOnMemoryPressure()")
        assertTrue("入口没问档位判据（前台档也会清）：\n$block", ask >= 0)
        assertTrue("判据问了却没清场，或清场写在问之前：\n$block", clear > ask)
        assertEquals(
            "入口里除了那一记 return 之外不许有第二道门：\n$block",
            1,
            occurrences(block, "if (!widgetGlassCacheReleasesAtTrimLevel"),
        )
        FORBIDDEN_RECYCLE.forEach { needle ->
            assertEquals("入口里出现了「$needle」：\n$block", 0, occurrences(block, needle))
        }
        assertEquals("入口不许自己烤图：\n$block", 0, occurrences(block, "bake"))
        assertEquals("整个文件里 glassCache.clearOnMemoryPressure() 只许这一处：\n$renderer",
            1, occurrences(renderer, "glassCache.clearOnMemoryPressure()"))
    }

    /**
     * 档位判据本体的纯度：它是表达式体，两枚分量都是参数 —— 文件里其它地方读的
     * `ComponentCallbacks2`（注释里指路那一处不算）不许渗进这一行，具体数值也不许抄进来。
     */
    @Test
    fun trimTierPredicateComparesParametersAndNothingElse() {
        val renderer = readMainSource(RENDERER_FILE)
        val lines = renderer.lines()
        val signature = lines.withIndex().firstOrNull {
            it.value.trim().startsWith("internal fun widgetGlassCacheReleasesAtTrimLevel(")
        }?.index ?: -1
        assertTrue("找不到档位判据的声明：函数改名或挪过家", signature >= 0)
        val body = lines.subList(signature, minOf(signature + 2, lines.size)).joinToString("\n")

        assertTrue("判据不是「level >= backgroundFloor」这一条了：\n$body", body.contains("level >= backgroundFloor"))
        listOf("ComponentCallbacks2", "Build.", "Context", "Int = 7").forEach { needle ->
            assertEquals("判据那两行里出现了「$needle」—— 设备事实得留在调用点当参数：\n$body", 0, occurrences(body, needle))
        }
        assertEquals("判据里不许出现任何数字档位（抄第二份常量就是第二条口径）：\n$body",
            0, body.count { it.isDigit() })
    }

    // ---- ③ 乙档：键给不出来那一格只留读数，不开第二条静默路径 ----

    @Test
    fun unkeyedBranchCountsInsteadOfOpeningADisposalPath() {
        val renderer = readMainSource(RENDERER_FILE)
        val block = balancedBlock(renderer, "if (key == null) {")

        assertEquals(
            "键给不出来那一格没记读数（卡 T423 乙档裁的是「如实注释＋具名读数」）：\n$block",
            1,
            occurrences(block, "unkeyedBakeCount.incrementAndGet()"),
        )
        assertEquals("那一格仍然现烤一张、不入缓存：\n$block", 1, occurrences(block, "bakeGlass("))
        FORBIDDEN_RECYCLE.forEach { needle ->
            assertEquals("那一格里出现了「$needle」：它出去就进 RemoteViews，这里回收就是拿崩溃换驻留：\n$block",
                0, occurrences(block, needle))
        }
        assertEquals("那一格不许改走缓存或改走清场（第二条静默路径）：\n$block",
            0, occurrences(block, "glassCache."))
    }

    /** 读数入口挂着：只增不减、不参与判据、不随清场归零。 */
    @Test
    fun unkeyedReadingIsGetOnly() {
        val renderer = readMainSource(RENDERER_FILE)
        val block = balancedBlock(renderer, "internal fun unkeyedBakeCountForReadOff(): Int")

        assertEquals(1, occurrences(block, "unkeyedBakeCount.get()"))
        assertEquals("读数入口不许顺手归零（它记的是趟数，不是驻留量）：\n$block",
            0, occurrences(block, "set(0)"))
        assertEquals(
            "全文件只许键给不出来那一格写这枚读数：\n$renderer",
            1,
            occurrences(renderer, "unkeyedBakeCount.incrementAndGet()"),
        )
    }

    // ---- ④ 丙那处：注释不再依赖堆归属 ----

    @Test
    fun glassCacheDocJustifiesNoRecycleWithoutAMemoryResidencyClaim() {
        val renderer = readMainSource(RENDERER_FILE)
        val doc = docBlockBefore(renderer, "internal class GlassBakeCache<V>(")

        assertTrue("类注释丢了「交给 RemoteViews／binder 队列／Launcher 显示」那半可证的理由：\n$doc",
            doc.contains("RemoteViews") && doc.contains("binder") && doc.contains("丢掉引用就够了"))
        FORBIDDEN_HEAP_CLAIMS.forEach { needle ->
            assertEquals(
                "类注释里出现了「$needle」：堆归属那一问的一手出处本卡取不到，" +
                    "不许拿猜测定结论，也不许翻成另一句的相反断言：\n$doc",
                0,
                occurrences(doc, needle),
            )
        }
        assertEquals("整个文件还留着「像素就在 Java 堆上」那句：\n$renderer",
            0, occurrences(renderer, "像素就在"))
        assertEquals(
            "entryCount 那句「生产路径上没有清缓存这一步」得改成如实（现在有两记清场）：\n$renderer",
            0,
            occurrences(renderer, "生产路径上没有"),
        )
    }

    // ---- ⑤ 接线：BUAAApplication 把档位交出去，自己不重判、不回收 ----

    @Test
    fun applicationHandsTheLevelToTheWidgetEntryInsteadOfReJudgingIt() {
        val app = readMainSource(APP_FILE)
        val block = balancedBlock(app, "override fun onTrimMemory(level: Int) {")

        val webView = block.indexOf("BuaaWebSession.releaseForMemory()")
        val rows = block.indexOf("WidgetDataCache.invalidate()")
        val glass = block.indexOf("WidgetBackgroundRenderer.releaseGlassCacheForMemory(")
        assertTrue("体里少了 WebView 那一手：\n$block", webView >= 0)
        assertTrue("体里少了组件行缓存那一手：\n$block", rows > webView)
        assertTrue(
            "体里没把组件玻璃位图缓存交出去，或交在了前两手之前（缺口原样留着）：\n$block",
            glass > rows,
        )
        assertEquals(
            "地板线只许交 `ComponentCallbacks2.TRIM_MEMORY_BACKGROUND` 本体（那道门与参数同一枚符号，" +
                "不抄第二份常量）：\n$block",
            2,
            occurrences(block, "ComponentCallbacks2.TRIM_MEMORY_BACKGROUND"),
        )
        assertEquals(
            "onTrimMemory 体里只许那道门判一次档位 —— 玻璃那一记的档位归组件侧入口自己问：\n$block",
            1,
            occurrences(block, "if (level"),
        )
        FORBIDDEN_RECYCLE.forEach { needle ->
            assertEquals("应用侧出现了「$needle」：位图的处置不归这里管：\n$block", 0, occurrences(block, needle))
        }
        assertEquals(
            "KDoc 还写着「最大的两块常驻内存」：那句现在不如实（组件侧那记已经纳进来了）：\n$app",
            0,
            occurrences(app, "最大的两块常驻内存"),
        )
        assertTrue(
            "KDoc 没点名玻璃这一族是哪一记交出去的：\n$app",
            app.contains("releaseGlassCacheForMemory") && app.contains("widgetGlassCacheReleasesAtTrimLevel"),
        )
    }

    /**
     * 在册那笔驻留账的口径没被改掉（第⓪问的丙档——容量收成 1——在本卡被驳掉）：
     * `WidgetBackgroundRendererCacheTest` 钉着的 2 与 1,228,800 两处读数一字未动。
     */
    @Test
    fun residentCeilingRulerStillReadsTwoEntries() {
        val renderer = readMainSource(RENDERER_FILE)
        assertTrue(
            "GLASS_CACHE_MAX_ENTRIES 被改掉了（本卡不许动容量，那是另一枚在册卡的口径）：\n$renderer",
            renderer.contains("internal const val GLASS_CACHE_MAX_ENTRIES = 2"),
        )
        assertTrue(
            "注释里那笔 1,228,800 B 的驻留账不见了：\n$renderer",
            renderer.contains("1,228,800"),
        )
    }

    // ---- 读盘与小工具 ------------------------------------------------------------------------------

    private fun readMainSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

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

    /** 从签名的第一个左括号做到配平收尾——取的是**声明块**，块里没有注释，所以不需要抹注释。 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：形状改过家，这条守卫要跟着改" }
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
        throw IllegalStateException("$signature 的块没配平")
    }

    /** 取某个声明**上面**那段 KDoc（丙那一格要读的就是注释本身，所以这里不抹它）。 */
    private fun docBlockBefore(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：形状改过家，这条守卫要跟着改" }
        val open = source.lastIndexOf("/**", at)
        check(open >= 0) { "$signature 之前找不到 KDoc 开头" }
        return source.substring(open, at)
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var index = haystack.indexOf(needle)
        var count = 0
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    private companion object {
        const val RENDERER_FILE = "com/buaa/schedule/widget/WidgetBackgroundRenderer.kt"
        const val APP_FILE = "com/buaa/schedule/BUAAApplication.kt"

        /** 「对位图做处置」那一族的写法：清入口、公开入口、乙档那一格、应用侧都只许 0 处。 */
        val FORBIDDEN_RECYCLE = listOf("recycle", "isRecycled", "System.gc")

        /** 堆归属那一族的断言（两个方向都不许出现——本卡不许联网，谁也没判赢）。 */
        val FORBIDDEN_HEAP_CLAIMS = listOf("堆", "Java 堆", "native 堆", "GC 自己收", "API 26")
    }
}
