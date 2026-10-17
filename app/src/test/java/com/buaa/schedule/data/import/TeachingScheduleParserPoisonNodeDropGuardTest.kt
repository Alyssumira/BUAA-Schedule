package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在册 #727③：`TeachingScheduleParser` 的**抛出面形状**守卫。
 *
 * ## 为什么这一半只能走守卫
 * 表驱动那张表（`TeachingScheduleParserPoisonNodeTableTest`）钉的是"形状怪的正文不许抛、好条目不许被带倒"
 * 这一层**行为**。行为接不住的那一层才归本守卫：有人把兜底从"丢那一枚节点"换成"整棵树一起吞"
 * （在 parse 或 loadAll 那头多套一手），修完之后那种吞法一次都不会触发 ⇒ 表驱动那张表**全绿**，
 * 而"一枚坏节点抹掉整月标注"这一手已经悄悄回来了。同理，把标量取法换回抛出型那一手，
 * 也只有形状尺能在它还没撞上 payload 之前就响。
 *
 * ## 四格各钉什么（两臂的红互不重叠）
 * - ① [throwingScalarReadSurvivesOnlyAsEvidence]：抛出型取法在本文件代码侧一枚都不许剩
 *   （双向复算尺：`jsonPrimitive` 那一枚串只许活在注释里当取证原文，原文侧恰 1 处）；
 *   安全取法的声明必须逐字节在场。**朝宽（把兜底摘掉）那一臂红的就是这一格。**
 * - ② [thePerNodeDropIsTheOnlyFallbackInTheDocument]：本文件的兜底枚数 —— 两处、各有名字，
 *   一处管正文语法、一处管日期解不出来；丢节点的 `?: return null` 恰 3 枚；出口照旧是排序过的那份名册。
 * - ③ [everyChildStillGetsWalked]：**关系尺**（正常路径）。递归两手逐字节在场、when 的三分支各恰一枚、
 *   三格标量读法都从同一枚安全取法走（配对等式 1 + 1 + 2 = 4）。
 *   **朝窄（把正常路径也吞掉）那一臂红的就是这一格** —— 往递归那一手前面塞一个条件，
 *   字面串就断了，而枚数尺①②都还绿着。
 * - ④ [theCriterionIsPureJvmSoDeviceFactsStayAtTheCallSite]：判据函数零 android import、
 *   零 `Build.VERSION`／权限／设备度量 —— import 总数钉死，多一枚就把这一格顶红，
 *   设备侧事实只许由调用点当参数递进来。
 *
 * ## 覆盖面边界（如实写，别当它没有）
 * - 本守卫**只数原文那一栏**（不抹注释）：刻意不新增一枚 buildString 助手，免得顶高在册
 *   `CommentStrippingHelperShapeGuardTest` 的合并态写死值 —— 与 `SpecialDayCacheAtomicWriteGuardTest`
 *   ⑦ 段那条在册警告同一条理由。代价：某一枚串要是被抄进注释，这一族的枚数尺会跟着漂；
 *   所以每格都写明了"这一枚串该出现在哪儿、不该出现在哪儿"，注释里那一处是**取证原文**、算在内。
 * - 认串不认抽象：把安全取法改名、或换成另一枚同样不抛的写法，会让 ①②③ 一起红（枚数漂），
 *   换不成假绿；真正的假绿只剩"另起一枚同样正确的写法"那一类，登记在册 #528 同族。
 * - 挡不住的两路（落点选在解析层带来的账，不是本守卫的漏）：深度嵌套正文把 `walk` 的递归打到
 *   `StackOverflowError`（那是 Error，本文件的兜底与调用点的兜底都不接）；以及调用点那枚裸 `launch`
 *   的形状本身 —— `ScheduleViewModel` 的 init 里那一手仍然没有 CoroutineExceptionHandler，
 *   本文件之外任何新抛出一族都照旧走线程未捕获异常。
 */
class TeachingScheduleParserPoisonNodeDropGuardTest {

    /** ① 抛出型标量取法：代码侧 0 枚、取证原文恰 1 枚；安全取法的声明逐字节在场。 */
    @Test
    fun throwingScalarReadSurvivesOnlyAsEvidence() {
        val source = readMain(PARSER)
        val problems = mutableListOf<String>()
        if (occurrences(source, THROWING_READ_NEEDLE) != THROWING_READ_IN_EVIDENCE) {
            problems.add("「" + THROWING_READ_NEEDLE + "」实到 " + occurrences(source, THROWING_READ_NEEDLE) +
                " 处 expected:<" + THROWING_READ_IN_EVIDENCE + "> —— 多出来的那一处只能是有人把标量读法换回了" +
                "抛出型那一手（非标量即抛 ⇒ 整条 loadAll 抛给调用点 ⇒ init 里那枚裸 launch 把冷启动打死）；" +
                "少掉的那一处是取证原文被抹掉，那是删证据不是修尺")
        }
        if (occurrences(source, SAFE_READ_DECL) != 1) {
            problems.add("安全取法声明「" + SAFE_READ_DECL + "」实到 " + occurrences(source, SAFE_READ_DECL) +
                " 处 expected:<1> —— 换成裸 cast（as 而不是 as?）逐字节就对不上，而那一手恰恰是会抛的那一枚")
        }
        if (occurrences(source, UNSAFE_CAST) != 0) {
            problems.add("抛出型 cast「" + UNSAFE_CAST + "」实到 " + occurrences(source, UNSAFE_CAST) +
                " 处 expected:<0> —— 这一枚就是朝宽那一臂的形状：非标量撞上它当场抛，本文件的「丢弃那一枚节点」就没了")
        }
        assertTrue(
            "钉法 = 双向复算（抛出型取法只许当取证原文留在注释里）+ 安全取法声明逐字节。实到：" +
                THROWING_READ_NEEDLE + " " + occurrences(source, THROWING_READ_NEEDLE) +
                " ｜ 声明 " + occurrences(source, SAFE_READ_DECL) + " ｜ 裸 cast " +
                occurrences(source, UNSAFE_CAST) + " ｜ 问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** ② 兜底的枚数与落点：两处各有名字，丢节点三处，出口不许折成空名册。 */
    @Test
    fun thePerNodeDropIsTheOnlyFallbackInTheDocument() {
        val source = readMain(PARSER)
        val problems = mutableListOf<String>()
        if (occurrences(source, RUN_CATCHING) != RUN_CATCHING_TOTAL) {
            problems.add("本文件兜底实到 " + occurrences(source, RUN_CATCHING) + " 枚 expected:<" +
                RUN_CATCHING_TOTAL + ">（语法那一手 + 日期解不出来那一手）—— 多一枚就是有人把兜底扩到了" +
                "整棵树上（一枚坏节点抹掉整月标注），少一枚就是校验被摘掉")
        }
        if (occurrences(source, SYNTAX_FALLBACK) != 1) {
            problems.add("语法那一手「" + SYNTAX_FALLBACK + "」实到 " + occurrences(source, SYNTAX_FALLBACK) +
                " 处 expected:<1> —— 它是「语法不合法 ⇒ 整枚文件交回空」这一档的唯一落点")
        }
        if (occurrences(source, NODE_DROP) != NODE_DROP_TOTAL) {
            problems.add("丢节点那一手「" + NODE_DROP + "」实到 " + occurrences(source, NODE_DROP) +
                " 枚 expected:<" + NODE_DROP_TOTAL + ">（日期格取法、日期字段、日期解析各一枚）—— " +
                "射程止于「那一枚节点」这件事就钉在这一格上")
        }
        if (occurrences(source, WHOLE_TREE_FOLD) != 1) {
            problems.add("整枚文件交回空那一手「" + WHOLE_TREE_FOLD + "」实到 " + occurrences(source, WHOLE_TREE_FOLD) +
                " 处 expected:<1> —— 多一枚就是新增了一档「整棵树一起吞」")
        }
        if (occurrences(source, SORTED_EXIT) != 1) {
            problems.add("出口「" + SORTED_EXIT + "」实到 " + occurrences(source, SORTED_EXIT) +
                " 处 expected:<1> —— 被换成裸 emptyList 就是「解出来的名册也不交了」")
        }
        assertTrue(
            "钉法 = 兜底枚数 + 出口逐字节（全是绝对值，不是 >=）。本文件的口径是「丢那一格／丢那一枚节点」，" +
                "不是「接住整棵树」。实到：兜底 " + occurrences(source, RUN_CATCHING) + " ｜ 丢节点 " +
            occurrences(source, NODE_DROP) + " ｜ 交回空 " + occurrences(source, WHOLE_TREE_FOLD) + " ｜ 出口 " +
            occurrences(source, SORTED_EXIT) + " ｜ 问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** ③ 关系尺（朝窄那一臂的落点）：正常路径的每一手都还在场，三格标量读法同源。 */
    @Test
    fun everyChildStillGetsWalked() {
        val source = readMain(PARSER)
        val walkRoot = occurrences(source, WALK_CALL)
        val walkChildren = occurrences(source, WALK_OBJECT_CHILDREN)
        val walkItems = occurrences(source, WALK_ARRAY_ITEMS)
        val safeCalls = occurrences(source, TEXT_OF_CALLS)
        val declared = occurrences(source, TEXT_OF_DECL)
        val dateSite = occurrences(source, TEXT_OF_DATE_SITE)
        val otherSites = occurrences(source, TEXT_OF_OTHER_SITES)
        val problems = mutableListOf<String>()
        if (walkRoot != 1) {
            problems.add("树根那一手「" + WALK_CALL + "」实到 " + walkRoot + " 处 expected:<1> —— 被整手兜住" +
                "（套进兜底里）就是「形状怪 ⇒ 整枚文件交回空」，那是被否掉的落点")
        }
        if (walkChildren != 1 || walkItems != 1) {
            problems.add("递归两手实到 对象 " + walkChildren + " ／ 数组 " + walkItems + " expected:<1,1> —— " +
                "往任一手的接收者前面塞条件（「只走标量」那一形），好条目会跟着坏邻居一起消失，" +
                "而枚数尺①②对此全绿")
        }
        for (arm in listOf(WHEN_OBJECT, WHEN_PRIMITIVE)) {
            if (occurrences(source, arm) != 1) {
                problems.add("when 分支「" + arm + "」实到 " + occurrences(source, arm) + " 处 expected:<1>")
            }
        }
        if (declared != 1 || dateSite != 1 || otherSites != 2) {
            problems.add("安全取法的三格读点实到 声明 " + declared + " ／ 日期格 " + dateSite + " ／ 另两格 " +
                otherSites + " expected:<1,1,2> —— 少一枚就是某一格又走回抛出型那一手")
        }
        if (safeCalls != TEXT_OF_CALLS_TOTAL || safeCalls != declared + dateSite + otherSites) {
            problems.add("配对等式断了：「" + TEXT_OF_CALLS + "」实到 " + safeCalls + " 枚 expected:<" +
                TEXT_OF_CALLS_TOTAL + ">，且应等于 声明 + 日期格 + 另两格 = " +
                (declared + dateSite + otherSites))
        }
        assertTrue(
            "钉法 = 关系尺（树根一手、递归两手、when 三分支、三格读法同源 + 一条配对等式），不是「串在不在」。" +
                "正常路径与兜底路径必须在同一手里分得开：坏邻居只带走它自己。实到：树根 " + walkRoot +
                " ｜ 递归 " + walkChildren + "/" + walkItems + " ｜ 取法 " + safeCalls + " ｜ 问题：" +
                problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** ④ 纯度尺：判据函数是纯 JVM 的，设备侧事实留在调用点。 */
    @Test
    fun theCriterionIsPureJvmSoDeviceFactsStayAtTheCallSite() {
        val source = readMain(PARSER)
        val imports = occurrences(source, IMPORT)
        val problems = mutableListOf<String>()
        if (occurrences(source, ANDROID_IMPORT) != 0 || occurrences(source, ANDROIDX_IMPORT) != 0) {
            problems.add("android 系 import 实到 " + occurrences(source, ANDROID_IMPORT) + " ／ " +
                occurrences(source, ANDROIDX_IMPORT) + " 枚 expected:<0,0> —— 判据一沾 android，" +
                "表驱动那张表就再也跑不动它（本仓铁律：Build.VERSION／权限／设备度量留在调用点当参数）")
        }
        for (deviceFact in listOf(BUILD_VERSION, PERMISSION_CHECK, DISPLAY_METRICS)) {
            if (occurrences(source, deviceFact) != 0) {
                problems.add("设备度量那一手「" + deviceFact + "」实到 " + occurrences(source, deviceFact) +
                    " 处 expected:<0> —— 内核自己去问设备，这几档就没法在 JVM 里逐支打表")
            }
        }
        if (imports != IMPORT_TOTAL) {
            problems.add("import 总数实到 " + imports + " 枚 expected:<" + IMPORT_TOTAL + ">（序列化族 + java.time，" +
                "抛出型那一枚已经摘掉）—— 多一枚就得点名它是谁的依赖，别把 android 折进来")
        }
        if (occurrences(source, CONTENT_OR_NULL) != CONTENT_OR_NULL_TOTAL) {
            problems.add("「" + CONTENT_OR_NULL + "」实到 " + occurrences(source, CONTENT_OR_NULL) +
                " 处 expected:<" + CONTENT_OR_NULL_TOTAL + ">（import + 安全取法声明）—— 又一处直接读标量就是又一枚抛出点")
        }
        assertTrue(
            "钉法 = import 名册枚数 + 三枚设备侧字零出现。判据纯度是这张表能在 JVM 里跑的前提。" +
                "实到：import " + imports + " ｜ contentOrNull " + occurrences(source, CONTENT_OR_NULL) +
                " ｜ 问题：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    // ---- 读盘 ------------------------------------------------------------------------------

    private fun occurrences(haystack: String, needle: String): Int = haystack.split(needle).size - 1

    private fun readMain(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：这一枚文件挪过家，本守卫的尺要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 单测的 cwd 是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛（静默跳过比没有守卫更糟） */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { candidate -> File(dir, candidate) }
                .firstOrNull { candidate -> candidate.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 " + File("").absolutePath)
    }

    private companion object {
        const val PARSER = "com/buaa/schedule/data/import/TeachingScheduleParser.kt"

        // ---- ① 抛出面 ----
        /** 抛出型标量取法那一枚串：只许当取证原文留在注释里（注释那一处算在内，所以是 1 不是 0） */
        const val THROWING_READ_NEEDLE = "jsonPrimitive"
        const val THROWING_READ_IN_EVIDENCE = 1
        const val SAFE_READ_DECL =
            "private fun JsonElement.textOf(): String? = (this as? JsonPrimitive)?.contentOrNull"
        const val UNSAFE_CAST = "as JsonPrimitive"

        // ---- ② 兜底枚数 ----
        const val RUN_CATCHING = "runCatching"
        const val RUN_CATCHING_TOTAL = 2
        const val SYNTAX_FALLBACK = "runCatching { json.parseToJsonElement(raw) }"
        const val NODE_DROP = "?: return null"
        const val NODE_DROP_TOTAL = 3
        const val WHOLE_TREE_FOLD = "?: return emptyList()"
        const val SORTED_EXIT = "out.values.sortedBy { it.date }"

        // ---- ③ 正常路径（关系尺）----
        const val WALK_CALL = "walk(null, root, out)"
        const val WALK_OBJECT_CHILDREN = "element.forEach { (k, v) -> walk(k, v, out) }"
        const val WALK_ARRAY_ITEMS = "is JsonArray -> element.forEach { walk(key, it, out) }"
        const val WHEN_OBJECT = "is JsonObject ->"
        const val WHEN_PRIMITIVE = "is JsonPrimitive -> Unit"
        const val TEXT_OF_CALLS = ".textOf()"
        const val TEXT_OF_CALLS_TOTAL = 4
        const val TEXT_OF_DECL = "JsonElement.textOf()"
        const val TEXT_OF_DATE_SITE = "dateEntry.value.textOf()"
        const val TEXT_OF_OTHER_SITES = "?.value?.textOf()"

        // ---- ④ 纯度 ----
        const val IMPORT = "import "
        const val IMPORT_TOTAL = 9
        const val ANDROID_IMPORT = "import android."
        const val ANDROIDX_IMPORT = "import androidx."
        const val BUILD_VERSION = "Build.VERSION"
        const val PERMISSION_CHECK = "checkSelfPermission"
        const val DISPLAY_METRICS = "WindowManager"
        const val CONTENT_OR_NULL = "contentOrNull"
        const val CONTENT_OR_NULL_TOTAL = 2
    }
}
