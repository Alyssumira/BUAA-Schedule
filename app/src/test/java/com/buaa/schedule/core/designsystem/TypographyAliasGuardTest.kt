package com.buaa.schedule.core.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 12sp/Medium 这一档只许有一个名字：`labelMedium`。
 * [ScheduleTypography] 里的 `labelSmall` 是它的**同值别名**，注释写着「调用点已全部迁到
 * labelMedium（②V-08）」—— T158 普查量到那句话在盘上是假的（`ScheduleCharts.kt` 还剩 10 处），
 * 而它的危害不是难看，是闭环断了：这句话是"下一次改动该选哪个名字"的唯一书面依据，
 * 注释说迁完、新代码就去学代码里那 10 处，于是迁移永远完不成。
 *
 * T161① 把那 10 处换完名之后，这句第一次为真。本文件把它从"一句要靠自觉的话"
 * 变成一条会红的守卫 —— 这就是 `Type.kt` 那段注释里补的那句"靠什么维持"的实体。
 *
 * 判据：`app/src/main/java` 里不许再出现读 `labelSmall` 这一槽的**调用点**。
 * 两种写法都算（`Type.kt` 注释里也这么写）：
 * - 直取：`MaterialTheme.typography.labelSmall`；
 * - 先把 `MaterialTheme.typography` 存进局部 val 再取槽：`val t = MaterialTheme.typography` + `t.labelSmall`。
 * 抓法是按槽名而不是按整串 `MaterialTheme.typography.labelSmall` —— 后者只覆盖第一种写法，
 * 拿它宣称覆盖了这一族就是假绿。`aliasSlotRead` 匹配的是 `.labelSmall` 这个成员访问本身，
 * 接收者是谁不影响；`typographyAliasDefinition` 匹配的是那枚别名定义。
 * 两种写法各抓得到这一点由 [bothSlotReadSpellingsAreCaught] 用合成样本当场自证，不靠宣称。
 *
 * 三条口径要挑明：
 * 1. **只排除那一枚定义，不排除整枚 `Type.kt`**。别名本身不许删（M3 组件内部如
 *    `NavigationBar` 会读 `labelSmall`，放任它回落到默认 11sp 就重现"名义 12sp、实际 11sp"
 *    那条断链，见 `Type.kt` 注释与 docs/DESIGN_SYSTEM.md §4），所以定义必须留在盘上、
 *    必须被排除；但排除写得越宽，将来在 `Type.kt` 里新加一处调用点就越扫不到。
 *    [isTypographyAliasDefinition] 两枚条件（路径 + 那一行的形状）是 `&&` 关系，缺一不可。
 *    它到底承不承重由 [exclusionIsLoadBearing] 自证：不排除时全盘必须只命中这一枚定义。
 * 2. **注释先抹成等长空格再匹配**（[blankComments]），判的是调用点不是散文——本文件与
 *    `Type.kt` 的注释本来就要提这个名字。代价说清楚：一枚原始字符串字面量里若同时藏着
 *    `//`，那一行后半段会被当成注释而漏判；这种组合在本仓不存在，也不是这条守卫要防的写法。
 * 3. 字符串字面量不抹，所以字符串里写 `"….labelSmall…"` 会红。这是有意的假阳性：
 *    在主源码里留这么一串字，正是下一个人抄错名字的起点。
 *
 * 本模块的单元测试没有 Compose 运行时（无 Robolectric、无 ui-test），所以这条只能读主源码做静态核对。
 * 定位源码的办法与 [GlassSurfaceSingleChildTest] 一致；找不到目录就直接抛，不用 assumeTrue 跳过——
 * 找错路径只表现为"永远是绿的"，比红更糟。
 */
class TypographyAliasGuardTest {

    /** 主源码里不许有 `labelSmall` 的调用点（唯一放过的是那枚别名定义本身） */
    @Test
    fun noAliasSlotReadInMainSources() {
        val hits = scanMainSources()
        val offenders = hits.filterNot { it.isTypographyAliasDefinition() }

        assertTrue(
            "`labelSmall` 是 `labelMedium` 的同值别名（Medium/12sp/16sp），调用点应已全部迁到 " +
                "labelMedium（②V-08）。下面这些还在读别名那一槽，两个名字一个样式、" +
                "选哪个纯凭手感就是这么来的——改成 MaterialTheme.typography.labelMedium 即可：\n" +
                offenders.joinToString("\n") { "${it.relative}:${it.line}: ${it.text.trim()}" },
            offenders.isEmpty(),
        )
    }

    /**
     * 排除项必须是承重的，而不是一句装饰。
     *
     * 不做这条的话有个很难看的笑话：万一匹配串根本没扫到 `Type.kt`（路径写错、
     * 定义改成了 `labelSmall = m3LabelSmall` 之类），那句"排除定义"就永远没有工作过，
     * 守卫还是绿的——于是所有人以为排除项在管事，其实它一直空转，
     * 而将来真在 `Type.kt` 里加调用点时也照样扫不到。
     * 这里把"不排除时命中什么"钉下来：必须**有且只有** `Type.kt` 那一枚定义。
     */
    @Test
    fun exclusionIsLoadBearing() {
        val definitions = scanMainSources().filter { it.isTypographyAliasDefinition() }

        assertEquals(
            "排除项没在干活：主源码里被认成「别名定义」的行应当只有 $typographyFile 那一枚，" +
                "实际 ${definitions.size} 枚（0 枚＝匹配没扫到它，排除项是装饰；" +
                ">1 枚＝别名被抄到了第二处）。逐行看：" +
                definitions.joinToString("\n") { "${it.relative}:${it.line}: ${it.text.trim()}" },
            1,
            definitions.size,
        )
        assertEquals(
            "别名定义应当就在 ${typographyFile} 的 `labelSmall = TextStyle(` 那一行" +
                "（它挪过家/换过写法的话这条守卫要跟着改）：\n" +
                definitions.joinToString("\n") { "${it.relative}:${it.line}: ${it.text.trim()}" },
            typographyFile,
            definitions.single().relative,
        )
    }

    /**
     * 两种取法都要在射程里——这一条用合成样本自证，不靠宣称。
     * 拿只匹配 `MaterialTheme.typography.labelSmall` 的判据去说"覆盖了先存 val 那一族"，
     * 是这条守卫最省事的假绿写法，所以把它写成会被测的断言。
     */
    @Test
    fun bothSlotReadSpellingsAreCaught() {
        // 写法①：直取 MaterialTheme.typography
        val direct = "                Text(\"有课\", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)"
        // 写法②：先把 typography 存进局部 val，再从 val 取槽（接收者换了名字）
        val viaLocal = listOf(
            "        val type = MaterialTheme.typography",
            "        Text(\"有课\", style = type.labelSmall, color = scheme.onSurfaceVariant)",
        ).joinToString("\n")
        // 反向对照：迁完之后的样子不该再被命中，否则这条守卫是在数行数而不是在读代码
        val migrated = "                Text(\"有课\", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)"
        // 槽名更长的那个（labelSmallX）与注释里的提及都不该算调用点
        val lookalikeAndProse = listOf(
            "val labelSmallX = 1",
            "// 曾经写作 MaterialTheme.typography.labelSmall，现在不写了",
            "/* val t = MaterialTheme.typography; t.labelSmall 已经换名 */",
        ).joinToString("\n")

        assertEquals(
            "写法①（直取 MaterialTheme.typography）没被抓到：$direct",
            1,
            hitsIn("Sample.kt", direct).size,
        )
        assertEquals(
            "写法②（先 val 存 typography 再取槽）没被抓到——只匹配 MaterialTheme.typography.labelSmall " +
                "就会漏掉这一族，而它恰恰是最容易重新长出来的一族：\n$viaLocal",
            1,
            hitsIn("Sample.kt", viaLocal).size,
        )
        assertEquals(
            "已迁到 labelMedium 的写法不该被抓到：$migrated",
            0,
            hitsIn("Sample.kt", migrated).size,
        )
        assertEquals(
            "相近槽名与注释里的提及不该被当成调用点（判的是调用点，不是这个名字出现在哪）：\n$lookalikeAndProse",
            0,
            hitsIn("Sample.kt", lookalikeAndProse).size,
        )
    }

    // ---- 判据本体 --------------------------------------------------------------

    private data class Hit(
        val relative: String,
        val line: Int,
        val text: String,
    )

    /**
     * 唯一的豁免：`Type.kt` 里那枚 `labelSmall = TextStyle(` 定义本身。
     *
     * 两枚条件是 `&&` 关系，都要留着：只留路径就成了"整枚文件不扫"（将来在 `Type.kt`
     * 里加一处调用点扫不到），只留行形状就成了"谁定义都放过"。理由见类头第 1 条与
     * docs/DESIGN_SYSTEM.md §4：别名是 M3 默认 11sp 静默降级的那枚钉子，删不得。
     */
    private fun Hit.isTypographyAliasDefinition(): Boolean =
        relative == typographyFile && typographyAliasDefinition.containsMatchIn(text.trim())

    private fun scanMainSources(): List<Hit> = mainSources().flatMap { hitsIn(it.relative, it.text) }

    /** 抹注释后逐行找：成员访问 `.labelSmall` 与那枚别名定义，两种形状都收 */
    private fun hitsIn(relative: String, text: String): List<Hit> {
        val raw = text.lines()
        val code = blankComments(text).lines()
        return raw.indices.mapNotNull { index ->
            val line = code[index]
            if (aliasSlotRead.containsMatchIn(line) || typographyAliasDefinition.containsMatchIn(line.trim())) {
                Hit(relative, index + 1, raw[index])
            } else {
                null
            }
        }
    }

    /** 槽名的成员访问：接收者是谁不影响（MaterialTheme.typography / theme.typography / 局部 val 都算） */
    private val aliasSlotRead = Regex("""\.labelSmall\b""")

    /** 别名定义那一行：`labelSmall = TextStyle(` */
    private val typographyAliasDefinition = Regex("""^labelSmall\s*=\s*TextStyle\(""")

    private val typographyFile = "com/buaa/schedule/core/designsystem/Type.kt"

    // ---- 读盘小工具 ------------------------------------------------------------

    private data class Source(val relative: String, val text: String)

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source(it.relativeTo(root).path.replace('\\', '/'), it.readText()) }
    }

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
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

    /**
     * 把注释（`//` 到行尾、`/* … */` 整段，KDoc 同属后者）抹成**等长空格**，
     * 换行位置与其余字符一律不动，所以行号与列位置都还能用。
     *
     * 不做这件事的话，本文件与 `Type.kt` 那些"必须提这个名字"的注释会让守卫常年红在
     * 散文上——红在散文上的守卫活不过下一周。
     */
    private fun blankComments(src: String): String {
        val out = src.toCharArray()
        var i = 0
        var blockDepth = 0 // Kotlin 的 /* */ 可嵌套，按层数走
        while (i < out.size) {
            if (blockDepth > 0) {
                when {
                    src.startsWith("*/", i) -> {
                        out[i] = ' '
                        out[i + 1] = ' '
                        blockDepth--
                        i += 2
                    }

                    src.startsWith("/*", i) -> {
                        out[i] = ' '
                        out[i + 1] = ' '
                        blockDepth++
                        i += 2
                    }

                    else -> {
                        if (out[i] != '\n') out[i] = ' '
                        i++
                    }
                }
                continue
            }
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i)
                    val end = if (nl < 0) out.size else nl
                    for (k in i until end) out[k] = ' '
                    i = end
                }

                src.startsWith("/*", i) -> {
                    out[i] = ' '
                    out[i + 1] = ' '
                    blockDepth++
                    i += 2
                }

                else -> i++
            }
        }
        return String(out)
    }
}
