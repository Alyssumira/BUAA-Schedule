package com.buaa.schedule.core.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T422 落点守卫：壁纸采样档位不再由写死的竖屏目标单独决定，上界常数具名，
 * 且"解码返回 → 赋值"那一截的两记"取消即回收"的兜都还在位。
 *
 * ## 判据（四格，全是纯 JVM 源码扫描，零 android.* import）
 * ① 采样档由纯判据函数 [wallpaperSampleSize] 决定：`decodeSampledWallpaper` 的函数体里
 *    `while (` 恰好 0 枚（档位不许在那一枚里就地重算）、对纯判据函数的调用恰好 1 枚；
 *    两趟循环都长在纯判据函数自己的函数体里（各 1 枚）。
 * ② 上界常数具名且可改：`internal const val WALLPAPER_MAX_DECODED_LONG_EDGE_PX = 2160` 恰好 1 枚，
 *    抹注释后全颗文件里裸字面量 `2160` 恰好 1 枚（只许出现在声明处）；三档上限一律走
 *    **参数默认值**（`targetWidthPx: Int = WALLPAPER_TARGET_WIDTH` 等三枚各 1），
 *    于是"目标写死在判据里"那一型复发时这一格会红。
 * ③ 本路从不看屏幕（第⓪问选丙档的全部前提，不许被写成"已经按实屏量过"）：
 *    抹注释后 `displayMetrics`／`WindowManager`／`DisplayMetrics` 各 0 枚；纯判据函数体内
 *    `android.`／`Bitmap`／`SDK_INT` 各 0 枚（设备事实留在调用点当参数，本仓成文判据）。
 * ④ 乙丙两格的形状：两记兜（`discardIfCancelled` 定义 1 枚＋解码两分支各调用 1 枚）、
 *    `handedOff` 那一手 3 枚、`ensureActive()` 1 枚；赋值仍是 `wallpaper = bitmap` 恰好 1 枚且长在
 *    try 的最后一手（没有"整份延迟赋值"那种新闪烁）；回收路径 `DisposableEffect(wallpaper)` 1 枚。
 *    口径那格：本文件裸原文里 `native 堆`／`Java 堆`／`GC 回收滞后`／`约 8MB` 各 0 枚
 *    （堆归属的两句互斥读数一手出处未取到 ⇒ 本卡不许把本文件那句改成其中任何一句的口径）。
 *
 * ## 写法口径
 * 抄同包既有守卫的方言（`findMainJavaDir` ＋ StringBuilder 那一族抹注释助手），不摊字符数组 ——
 * 那一族的枚数正被 `CommentStrippingHelperShapeGuardTest` 按绝对值钉着，本卡不去动别人那几枚写死值。
 * 指路只写"文件＋符号"，注释与消息里不写会漂的行号。
 */
class WallpaperSampleCeilingGuardTest {

    /** ① 档位归纯判据函数，解码函数自己不重算 */
    @Test
    fun theSamplingDecisionLivesOnlyInThePureJudgingFunction() {
        val code = withoutComments(readSceneBackground())
        assertEquals(
            "「wallpaperSampleSize(」不再是恰好 2 枚（1 枚声明＋1 枚调用）：多一枚＝第二处调用点，" +
                "少一枚＝解码路自己把档位算回去了。实到 " + occurrences(code, SAMPLE_CALL),
            2,
            occurrences(code, SAMPLE_CALL),
        )
        val decodeBody = functionBody(code, DECODE_SIGNATURE)
        assertEquals(
            "decodeSampledWallpaper 的函数体里又出现了就地 while —— 采样档回到写死目标那一型：" +
                "\n" + decodeBody,
            0,
            occurrences(decodeBody, WHILE),
        )
        assertEquals(
            "decodeSampledWallpaper 的函数体里没有恰好 1 次调用纯判据函数（实到 " +
                occurrences(decodeBody, SAMPLE_CALL) + "）",
            1,
            occurrences(decodeBody, SAMPLE_CALL),
        )
        val judgeBody = functionBody(code, JUDGE_SIGNATURE)
        assertEquals(
            "纯判据函数里两趟循环（两轴同达标＋长边上界）不再是各一枚、合起来恰好 2 枚 while：" +
                "少一枚＝有一趟被摘（横图或最坏账就此无上界）",
            2,
            occurrences(judgeBody, WHILE),
        )
        assertEquals(
            "第一趟的写法被改动（sampleSize *= 2 不是恰好 1 枚）：本卡说的是「其余不动」，" +
                "两轴同达标那一趟要原样保留",
            1,
            occurrences(judgeBody, "sampleSize *= 2"),
        )
        assertEquals(
            "第二趟不是沿 2 的幂往上跳（sampleSize = next 不是恰好 1 枚）：写回「sampleSize += 1」" +
                "那种就以为缩了 3 倍，inSampleSize 只认 2 的幂",
            1,
            occurrences(judgeBody, "sampleSize = next"),
        )
        assertEquals(
            "inSampleSize 的赋值不再是恰好 1 枚（实到 " + occurrences(code, "inSampleSize = sampleSize") +
                "）：多一枚＝第二处把档位直接喂给 BitmapFactory",
            1,
            occurrences(code, "inSampleSize = sampleSize"),
        )
    }

    /** ② 上界常数具名、可改；目标只以参数默认值进入判据 */
    @Test
    fun theCeilingIsANamedConstantAndNotABareMagicNumber() {
        val code = withoutComments(readSceneBackground())
        assertEquals(
            "具名上界的声明不再是那一枚（internal const val WALLPAPER_MAX_DECODED_LONG_EDGE_PX = 2160）：" +
                "丙档的落点就是「常数具名＋KDoc 写为什么够用」，摘掉声明或改成魔数都要重判",
            1,
            occurrences(code, CEILING_DECLARATION),
        )
        assertEquals(
            "抹注释后全颗文件里裸字面量 2160 不再是恰好 1 枚（实到 " + occurrences(code, "2160") +
                "）：多一枚＝有人又把上界抄成了散落的魔数，改常数时漏一处",
            1,
            occurrences(code, "2160"),
        )
        for (needle in listOf(CEILING_PARAM, TARGET_WIDTH_PARAM, TARGET_HEIGHT_PARAM)) {
            assertEquals(
                "参数默认值「$needle」不是恰好 1 枚：三档上限必须经参数进判据（设备事实留在调用点），" +
                    "判据里写死目标就是本卡甲档的病灶",
                1,
                occurrences(code, needle),
            )
        }
        assertEquals("目标短轴常数仍是 1 枚声明", 1, occurrences(code, "= $TARGET_WIDTH"))
        assertEquals("目标长轴常数仍是 1 枚声明", 1, occurrences(code, "= $TARGET_HEIGHT"))
    }

    /** ③ 这一路从不看屏幕：不许把"未验到的屏实测"写成既成事实 */
    @Test
    fun theSamplingPathStillNeverLooksAtTheScreen() {
        val code = withoutComments(readSceneBackground())
        for (needle in listOf("displayMetrics", "DisplayMetrics", "WindowManager", "wallpaperMinimum")) {
            assertEquals(
                "抹注释后本文件里出现「$needle」：第⓪问选丙档的前提就是拿不到可信的屏实测读数" +
                    "（API 31+ 的 WindowManager 分档未验到）。要改成按实屏量目标，" +
                    "那需要调用点把设备事实传进来，而不是在这一颗文件里就地量",
                0,
                occurrences(code, needle),
            )
        }
        val judgeBody = functionBody(code, JUDGE_SIGNATURE)
        for (needle in listOf("android.", "Bitmap", "SDK_INT", "Build.VERSION")) {
            assertEquals(
                "纯判据函数「wallpaperSampleSize」的函数体里出现「$needle」：它必须零 android 依赖，" +
                    "否则表驱动 JVM 单测就铺不开（本仓成文判据）",
                0,
                occurrences(judgeBody, needle),
            )
        }
        // 默认参数不许把设备事实算出来：判据的默认值只许指向本文件的编译期常数。
        assertTrue(
            "判据签名里出现了函数调用形的默认值（不是指向常量的那一型）：目标/上界必须是编译期常数",
            functionSignature(code, JUDGE_SIGNATURE).contains("Int = WALLPAPER_"),
        )
    }

    /** ④ 乙格（取消即回收的两记兜）与丙格（口径不押堆归属） */
    @Test
    fun theCancellationNetsAndTheHeapNeutralWordingHold() {
        val raw = readSceneBackground()
        val code = withoutComments(raw)
        assertEquals(
            "「discardIfCancelled」不再是声明 1 枚＋调用 2 枚（合起来 3 枚）：少一枚＝一条解码分支" +
                "脱兜，位图就会在取消时丢在 IO 线程上（DisposableEffect 只认已经进 state 的那枚）",
            3,
            occurrences(code, "discardIfCancelled"),
        )
        assertEquals(
            "「recycleUnpublished」不再是声明 1 枚＋两处调用（合起来 3 枚）",
            3,
            occurrences(code, "recycleUnpublished"),
        )
        assertEquals(
            "handedOff 那一手不再是 3 枚（声明／赋值前哨／catch 里读）：这是" +
                "「catch 碰不到已经交出去的那一张」的全部凭据",
            3,
            occurrences(code, "handedOff"),
        )
        assertEquals(
            "ensureActive() 不是恰好 1 枚：就地回收之后必须让取消照常传播，" +
                "否则后半截会把 Unknown 写进 SceneLuma、把 null 写进 wallpaper",
            1,
            occurrences(code, "ensureActive()"),
        )
        assertEquals(
            "wallpaper = bitmap 不再是恰好 1 枚：本卡禁止改成「整份延迟到 drawWithCache 再赋值」" +
                "（那一手引入新的空帧闪烁）",
            1,
            occurrences(code, "wallpaper = bitmap"),
        )
        assertEquals("DisposableEffect(wallpaper) 的认领手不再是恰好 1 枚", 1, occurrences(code, "DisposableEffect(wallpaper)"))
        assertEquals(
            "LaunchedEffect 的键被改动：本卡只补兜，不动 wallpaperUri / useSystemWallpaper / retryToken 这三键",
            1,
            occurrences(code, "LaunchedEffect(wallpaperUri, useSystemWallpaper, retryToken)"),
        )
        for (needle in listOf("native 堆", "Java 堆", "GC 回收滞后", "约 8MB")) {
            assertEquals(
                "裸原文里还留着「$needle」：堆归属在本仓同一进程里有两句互斥读数，一手出处本轮未取到 ⇒ " +
                    "本文件那句必须落在可证的「引用归属／已被画进录制层」上，不许押其中任何一句的口径" +
                    "（另一枚文件那句不归本卡，本卡一个字没动）",
                0,
                occurrences(raw, needle),
            )
        }
        assertTrue(
            "本文件那句没有落在「一手出处未取到 ⇒ 两句都不采信」的口径上：丙格的登记方式变了要回来重钉",
            raw.contains("一手出处本轮未取到"),
        )
        assertFalse(
            "回收那句又把「在 native 堆上」当立论（现文已改口径，见 KDoc 与 T422 回执的未验到栏）",
            raw.contains("且在 native 堆上"),
        )
    }

    // ---- 源码扫描助手（同包既有守卫的 StringBuilder 方言） -------------------

    /** 读 SceneBackground.kt 裸原文（文件挪过家就报路径，不报行号） */
    private fun readSceneBackground(): String {
        val file = File(findMainJavaDir(), SCENE_BACKGROUND)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 函数体：从签名的第一枚 `{` 起按花括号配平取到闭合（调用点已抹注释） */
    private fun functionBody(code: String, signature: String): String {
        val signatureText = functionSignature(code, signature)
        val start = code.indexOf(signature)
        assertTrue("找不到签名「$signature」：这一手被改名／搬走，守卫要按新形状重钉", start >= 0)
        val open = code.indexOf('{', start + signatureText.length)
        assertTrue("签名「$signature」后面没有函数体的开括号", open >= 0)
        var depth = 0
        var index = open
        var inLiteral: Char? = null
        while (index < code.length) {
            val ch = code[index]
            when {
                inLiteral != null -> if (ch == inLiteral) inLiteral = null
                ch == '"' || ch == '\'' -> inLiteral = ch
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
            index++
        }
        throw AssertionError("签名「$signature」的函数体花括号没配平：扫描口径要重看")
    }

    /** 签名原文：从 needle 起点到第一枚 `{` 之前（参数表都在这截里） */
    private fun functionSignature(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("找不到签名「$signature」：这一手被改名／搬走，守卫要按新形状重钉", start >= 0)
        val open = code.indexOf('{', start)
        assertTrue("签名「$signature」后面没有函数体的开括号", open >= 0)
        return code.substring(start, open)
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

    /** 只抹注释、保留字符串／字符字面量与换行位置（StringBuilder 方言，不摊字符数组）。 */
    private fun withoutComments(source: String): String {
        val builder = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val newline = source.indexOf('\n', i)
                    i = if (newline < 0) source.length else newline
                }

                source.startsWith("/*", i) -> {
                    val close = source.indexOf("*/", i + 2)
                    val stop = if (close < 0) source.length else close + 2
                    var k = i
                    while (k < stop) {
                        if (source[k] == '\n') builder.append('\n')
                        k++
                    }
                    i = stop
                }

                source[i] == '"' || source[i] == '\'' -> {
                    val stop = skipLiteral(source, i)
                    builder.append(source, i, stop)
                    i = stop
                }

                else -> {
                    builder.append(source[i])
                    i++
                }
            }
        }
        return builder.toString()
    }

    /** 字符串／字符字面量的收尾下标（含闭引号）；未闭合就退到行首，绝不吞掉后面的整段代码。 */
    private fun skipLiteral(source: String, at: Int): Int {
        if (source.startsWith(TRIPLE_QUOTE, at)) {
            val end = source.indexOf(TRIPLE_QUOTE, at + 3)
            return if (end < 0) source.length else end + 3
        }
        val quote = source[at]
        var j = at + 1
        while (j < source.length) {
            when {
                source[j] == '\\' -> j += 2
                source[j] == quote -> return j + 1
                source[j] == '\n' -> return j
                else -> j++
            }
        }
        return source.length
    }

    private companion object {
        const val SCENE_BACKGROUND = "com/buaa/schedule/core/designsystem/SceneBackground.kt"
        const val TRIPLE_QUOTE = "\"\"\""

        const val DECODE_SIGNATURE = "internal fun decodeSampledWallpaper("
        const val JUDGE_SIGNATURE = "internal fun wallpaperSampleSize("
        const val SAMPLE_CALL = "wallpaperSampleSize("
        const val WHILE = "while ("

        const val CEILING_DECLARATION = "internal const val WALLPAPER_MAX_DECODED_LONG_EDGE_PX = 2160"
        const val CEILING_PARAM = "maxDecodedLongEdgePx: Int = WALLPAPER_MAX_DECODED_LONG_EDGE_PX"
        const val TARGET_WIDTH_PARAM = "targetWidthPx: Int = WALLPAPER_TARGET_WIDTH"
        const val TARGET_HEIGHT_PARAM = "targetHeightPx: Int = WALLPAPER_TARGET_HEIGHT"

        /** 两枚目标常数的字面量（声明处各 1 枚，别的任何地方都不许再抄） */
        const val TARGET_WIDTH = 1080
        const val TARGET_HEIGHT = 1920
    }
}
