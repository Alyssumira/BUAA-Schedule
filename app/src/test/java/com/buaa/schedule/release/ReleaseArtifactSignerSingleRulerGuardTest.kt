package com.buaa.schedule.release

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T295③ —— 台账 #535 的形状守卫：产物的**名字**、自证条里的**签名者**、交给操作者的**发布步骤**
 * 这三本册子，跨 `app/build.gradle.kts` + `release.ps1`（再加手册 `docs/RELEASE.md` 那一页）钉成一把尺。
 *
 * ## 先说实话：这一族为什么只能钉形状（不许假装覆盖）
 *
 * `:app:releasePackage` 长在构建脚本里，而构建脚本不在单测 classpath 上：纯 JVM 跑不了它，
 * 本工程也没有 Gradle TestKit 依赖。所以「一次调试自签的运行拿不到正名」这件事在这里钉的是**构造**：
 * 名字那支 `when` 的形状、自证条那十三格的枚数、发布步骤打印机吃的是量到的结论还是那枚旗。
 * 行为侧的真读数在产物层那一格（`ReleaseForensicLogSurvivalTest` 里的
 * `artifactLayerCrossChecksTheRecordedSignerAgainstTheApkBytes`，读的是 release 产物的字节），
 * 它只在跑过 `:app:releasePackage` 且带上 `-PbuaaRequireReleaseArtifacts` 时吃到 ——
 * 那是 `release.ps1` 最后一步那条链，不是本文件，本文件不冒充它。
 *
 * ## 四家钉法（每一格在自己的报错消息里点名）
 *
 * - **逐字节**：针是整行原文（分支行、函数签名行、册子里那一行），改一个字符就红；
 * - **数出现次数**：枚数尺（`setProperty(` 十三枚、`printPublishSteps(` 两枚、那枚调试 DN 常量
 *   在全仓的读者枚数）—— 抓「悄悄多写一份」与「悄悄删掉一格」；
 * - **抹注释找锚点**：先把行注释与块注释（含 KDoc）抹成空白再找针 ⇒ 针必须长在**代码**里，不许长在
 *   文案里。本文件有一格是决定性的：「上传附件：」那一行在构建脚本里实到 4 处，其中 3 处是注释与
 *   KDoc 里的解释 —— 抹掉之后必须**只剩 1 处**，那 1 处才是要打印给人看的；
 * - **读形状**：按函数体切块，再判针**落在哪一支里**（可发布名必须在 release 那一支、闸门必须在
 *   「上传附件：」那一行**之前**）。顺序用字符偏移比，报错里不写行号。
 *
 * 报错消息一律不含行号：行号漂会让今天正确的代码红，而那正是本仓踩过的那类假红。
 * 本文件里那枚调试 DN 常量是拼出来的（末尾那一段 `"d Debug"`），所以册子扫不到自己 ——
 * 与 `ReleaseForensicLogSurvivalTest` 里那枚 `assumeTrue` 针同一手法。
 */
class ReleaseArtifactSignerSingleRulerGuardTest {

    /**
     * 册子①（钉法 = **逐字节** + **数出现次数** + **读形状**）：可发布名在构建脚本里只许有一枚字面，
     * 而且它只长在 release 那一支 —— 这就是「一次调试自签的运行在构造上拿不到正名」。
     */
    @Test
    fun thePublishableArtifactNameIsProducedOnlyFromTheReleaseVerdict() {
        val build = workingTreeText(BUILD_SCRIPT)

        assertEquals(
            "钉法 = 逐字节 + 数出现次数：可发布名「buaa-schedule-\$releaseVersionName.apk」在 " +
                "$BUILD_SCRIPT 里必须恰好 1 处（实到 " + occurrences(build, CANONICAL_NAME_NEEDLE) +
                " 处）：多出来的每一处都是第二枚落点名来源 —— #535 那笔「调试自签的包被复制成可发布正名」" +
                "就是从这一处重新长出来的",
            1,
            occurrences(build, CANONICAL_NAME_NEEDLE),
        )
        for (needle in listOf(RELEASE_NAME_BRANCH, DEBUG_NAME_BRANCH, UNSIGNED_NAME_BRANCH)) {
            assertEquals(
                "钉法 = 逐字节：名字那支「$needle」不再是恰好一枚（实到 " + occurrences(build, needle) +
                    " 处）：分支换了写法 ⇒ 下一枚卡可以把它挪到别处再拼一次，这本册子就管不住了",
                1,
                occurrences(build, needle),
            )
        }

        val body = functionBody(build, ARTIFACT_NAME_FUN)
        assertTrue("钉法 = 读形状：找不到 artifactNameFor 的函数体 ⇒ 名字那支换了写法，本册子要重钉", body != null)
        val nameBody = requireNotNull(body)
        assertEquals(
            "钉法 = 读形状：可发布名在名字那枚函数体里只许出现 1 次（release 那一支），实到 " +
                occurrences(nameBody, CANONICAL_NAME_NEEDLE) + " 次：多一次就是" +
                "有第二支也交回正名，调试自签的运行于是又能拿到可发布的名字",
            1,
            occurrences(nameBody, CANONICAL_NAME_NEEDLE),
        )
        assertTrue(
            "钉法 = 读形状：非 release 那两支都必须带后缀常量（debug / unsigned），实到 body 里 " +
                "debug 支=" + nameBody.contains(DEBUG_NAME_BRANCH) + " unsigned 支=" +
                nameBody.contains(UNSIGNED_NAME_BRANCH) + "：尾巴被写回字面量或某一支被摘掉，名字这层就退化成一枚旗子",
            nameBody.contains(DEBUG_NAME_BRANCH) && nameBody.contains(UNSIGNED_NAME_BRANCH),
        )

        assertEquals(
            "钉法 = 数出现次数：两枚后缀常量各 1 处，实到 debug=" + occurrences(build, DEBUG_SUFFIX_DECL) +
                " unsigned=" + occurrences(build, UNSIGNED_SUFFIX_DECL) + "：debug 那枚的尾巴里含 debug 不是巧合 —— " +
                "客户端选附件第二顺位排除的就是名字里带 debug 的那一枚",
            listOf(1, 1),
            listOf(occurrences(build, DEBUG_SUFFIX_DECL), occurrences(build, UNSIGNED_SUFFIX_DECL)),
        )
    }

    /**
     * 册子②（钉法 = **数出现次数** + **逐字节**）：自证条仍然十三格，其中三格是签名者。
     *
     * 枚数尺这一手专防「删一格」：#535 的原始形状是十条键里没有一条问签名者，
     * 所以「键还在不在」必须是读数，而不是靠人记着有几条。
     */
    @Test
    fun theProvenanceSidecarStillRecordsTheSignerAsAFirstClassKey() {
        val build = workingTreeText(BUILD_SCRIPT)

        assertEquals(
            "钉法 = 数出现次数：$BUILD_SCRIPT 里 `setProperty(` 应恰好 13 处（原有十格 + 签名者三格），实到 " +
                occurrences(build, "setProperty(") + " 处：少一格就是有人把某一格静默删掉了，" +
                "而产物层是按格对表的 —— 那一格从此没人读",
            13,
            occurrences(build, "setProperty("),
        )
        for (needle in listOf(SIGNED_BY_KEY, SIGNER_DN_KEY, SIGNER_FINGERPRINT_KEY)) {
            assertEquals(
                "钉法 = 逐字节：自证条那一格「$needle」不再是恰好一枚（实到 " + occurrences(build, needle) +
                    " 处）：#535 要的就是签名者进侧车 —— 进不去，产物层对签名这件事重新变成读空气",
                1,
                occurrences(build, needle),
            )
        }
        assertTrue(
            "钉法 = 逐字节：`writeReleaseProvenance` 的签名里必须有签名者那枚入参 —— 入参一掉，" +
                "那一格只能靠调用方自己猜（而调用方猜的正是那枚旗）",
            build.contains(PROVENANCE_SIGNATURE),
        )
        assertEquals(
            "钉法 = 数出现次数（抹注释找锚点）：代码里 `writeReleaseProvenance` 应恰好 2 处（定义 + releasePackage 的调用点），" +
                "实到 " + occurrences(blankComments(build), "writeReleaseProvenance") +
                " 处：定义没了或调用点没了都算单侧接线（注释里再提一次不算接线，所以先抹注释）",
            2,
            occurrences(blankComments(build), "writeReleaseProvenance"),
        )
    }

    /**
     * 册子③（钉法 = **逐字节** + **抹注释找锚点** + **读形状**）：发布步骤打印机吃的是**量到的结论**。
     *
     * 这里是 W 臂（「让打印机不管签名者」）的落点：入参被拿掉、闸门被摘掉、或调用点被硬写成
     * release —— 三档各自红。
     */
    @Test
    fun publishStepsAreGatedOnTheMeasuredSignerNotOnTheFlag() {
        val build = workingTreeText(BUILD_SCRIPT)
        val code = blankComments(build)

        assertEquals(
            "钉法 = 逐字节：发布步骤打印机必须是「$PUBLISH_STEPS_SIGNATURE」这个形（签名者是入参），实到 " +
                occurrences(build, PUBLISH_STEPS_SIGNATURE) + " 处：入参一掉，打印机就重新变成无条件教人上传",
            1,
            occurrences(build, PUBLISH_STEPS_SIGNATURE),
        )
        assertEquals(
            "钉法 = 逐字节 + 数出现次数：非 release 那一支的闸门与转出调用各 1 处，实到 gate=" +
                occurrences(build, NOT_PUBLISHABLE_GATE) + " notice=" + occurrences(build, NOT_PUBLISHABLE_CALL) +
                "：闸门与说明书是一枚判据的两半，缺任一半都等于「不管签名者」",
            listOf(1, 1),
            listOf(occurrences(build, NOT_PUBLISHABLE_GATE), occurrences(build, NOT_PUBLISHABLE_CALL)),
        )
        assertEquals(
            "钉法 = 数出现次数：`printPublishSteps(` 抹掉注释之后在代码里应恰好 2 处（定义 + 调用点），实到 " +
                occurrences(code, "printPublishSteps(") + " 处：多一处说明有人绕过签名者又打一份说明书",
            2,
            occurrences(code, "printPublishSteps("),
        )
        assertEquals(
            "钉法 = 逐字节（反套套逻辑）：调用点**不许**把结论硬写成 release 那一档 —— " +
                "「printPublishSteps(artifact, signerRelease)」这种写法等于让打印机忽略签名者（W 臂那一形），实到 " +
                occurrences(build, HARDCODED_VERDICT_CALL) + " 处（应为 0）",
            0,
            occurrences(build, HARDCODED_VERDICT_CALL),
        )
        assertEquals(
            "钉法 = 抹注释找锚点：「" + UPLOAD_PREFIX + "」原文在 $BUILD_SCRIPT 里实到 " +
                occurrences(build, UPLOAD_PREFIX) + " 处，其中只有 1 处真会打进给用户看的那一段 ⇒ " +
                "抹掉注释与 KDoc 后必须恰好剩 1 处（实到 " + occurrences(code, UPLOAD_INSTRUCTION) +
                " 处）：剩 0 处说明那一行被删（第 3 步没了），剩 2 处说明有人在别处也教人上传",
            1,
            occurrences(code, UPLOAD_INSTRUCTION),
        )

        assertTrue(
            "钉法 = 读形状：找不到发布步骤打印机的函数体 ⇒ 形状换了写法，本册子要重钉",
            functionBody(build, PUBLISH_STEPS_SIGNATURE) != null,
        )
        val publishBody = requireNotNull(functionBody(build, PUBLISH_STEPS_SIGNATURE)) {
            "钉法 = 读形状：这一格上面那条「找不到发布步骤打印机的函数体」应当先红"
        }
        val gateAt = publishBody.indexOf(NOT_PUBLISHABLE_GATE)
        val uploadAt = publishBody.indexOf(UPLOAD_INSTRUCTION)
        assertTrue(
            "钉法 = 读形状：非 release 的转出闸门排在「上传附件：」那一行**之后**（或任一枚不在函数体里）⇒ " +
                "不可发布的产物照样能拿到一份带上传指令的说明书（#535 本体）：gateAt=$gateAt uploadAt=$uploadAt",
            gateAt in 0 until uploadAt,
        )
        assertTrue(
            "钉法 = 读形状：闸门那一支里没有 return ⇒ 转出不成立，那五条编号步骤还是会打印出来",
            publishBody.substring(gateAt).lineSequence().take(4).any { line -> line.contains("return") },
        )
    }

    /**
     * 册子④（钉法 = **逐字节** + **抹注释找锚点** + **数出现次数**）：名字与量到的签名者必须被
     * **当场对表**，而且对表用的是字节读回来的结论，不是 `-PdebugSign` 那枚旗。
     */
    @Test
    fun nameAndSignerAreCrossCheckedFromBytesOnBothSidesOfTheCopy() {
        val build = workingTreeText(BUILD_SCRIPT)
        val code = blankComments(build)

        for (needle in listOf(ASSERT_NAME_FUN_SIGNATURE, ASSERT_NAME_CALL, PRE_POST_COPY_CHECK, ORIGIN_BRANCH_NEEDLE)) {
            assertEquals(
                "钉法 = 逐字节：「$needle」在 $BUILD_SCRIPT 里不再是恰好 1 处（实到 " + occurrences(build, needle) +
                    " 处）：这几枚是「不需要任何人读屏幕」的那一手 —— 摘掉任何一枚，发版路径就退回照屏幕行事",
                1,
                occurrences(build, needle),
            )
        }
        assertEquals(
            "钉法 = 抹注释找锚点：代码里 `assertNameMatchesSigner` 应恰好 2 处（定义 + 调用点），实到 " +
                occurrences(code, "assertNameMatchesSigner") + " 处：只在注释里出现的接线等于没接",
            2,
            occurrences(code, "assertNameMatchesSigner"),
        )
        assertEquals(
            "钉法 = 数出现次数：`classifyArtifactSigner(` 只许 3 处（定义 + 复制前那一次 + 打印机那一次），" +
                "抹注释后实到 " + occurrences(code, "classifyArtifactSigner(") +
                " 处：多一处就是有人另开了一枚签名判据（第二把尺），少一处就是某一侧不再问字节",
            3,
            occurrences(code, "classifyArtifactSigner("),
        )
        assertEquals(
            "钉法 = 逐字节：调试密钥那张证书的 DN 片段在 $BUILD_SCRIPT 里只许定义一次（判据按**包含**、" +
                "不按前缀 —— 本机实测那一张的完整 DN 是 C、O、CN 三段 RDN 拼出来的，CN 那一段才是判据），实到 " +
                occurrences(build, DEBUG_DN_DECL) + " 处",
            1,
            occurrences(build, DEBUG_DN_DECL),
        )
    }

    /**
     * 册子⑤（钉法 = **逐字节** + **数出现次数**）：`release.ps1` 按同一本册子找产物，
     * 而「能不能发」由它拿到的是哪一枚**名字**决定，不再由 `$DebugSign` 那枚旗猜。
     */
    @Test
    fun releaseScriptLooksUpBothNamesAndDecidesFromTheNameNotTheFlag() {
        val script = workingTreeText(RELEASE_SCRIPT)

        for (needle in listOf(SCRIPT_CANONICAL_ASSIGN, SCRIPT_SELFTEST_ASSIGN)) {
            assertEquals(
                "钉法 = 逐字节：脚本那枚产物路径的定义行「$needle」在 $RELEASE_SCRIPT 里应恰好 1 处（实到 " +
                    occurrences(script, needle) + " 处）：构建侧改了名而脚本还按老名字找 ⇒ 自测那一路假红、" +
                    "照老名字硬发那一路假绿",
                1,
                occurrences(script, needle),
            )
        }
        assertEquals(
            "钉法 = 数出现次数：脚本里那两枚文件名各只许有「定义 + 收尾念一句」这么多处（实到 正名=" +
                occurrences(script, SCRIPT_CANONICAL_TEMPLATE) + " 自测名=" +
                occurrences(script, SCRIPT_SELFTEST_TEMPLATE) + " 处，应 [2, 1]）：正名冒出第三处就是脚本里" +
                "又落了一次硬编码点名 —— 那正是 #535 的形状",
            listOf(2, 1),
            listOf(occurrences(script, SCRIPT_CANONICAL_TEMPLATE), occurrences(script, SCRIPT_SELFTEST_TEMPLATE)),
        )
        assertEquals(
            "钉法 = 逐字节 + 数出现次数：可发布性由名字推出来那一句应恰好 1 处，实到 " +
                occurrences(script, SCRIPT_PUBLISHABLE_ASSIGN) + " 处：这一手删掉的正是「脚本自己猜旗子」那一步",
            1,
            occurrences(script, SCRIPT_PUBLISHABLE_ASSIGN),
        )
        assertEquals(
            "钉法 = 数出现次数：收尾按那枚推出来的结论分岔（「if (-not \$publishable) {」），实到 " +
                occurrences(script, SCRIPT_PUBLISHABLE_BRANCH) + " 处（应为 1）：分岔没了，" +
                "不可发布的产物就会重新被念成「已就绪，发到 Gitee」",
            1,
            occurrences(script, SCRIPT_PUBLISHABLE_BRANCH),
        )
        assertEquals(
            "钉法 = 数出现次数：`'-PdebugSign'` 只许在拼 :app:releasePackage 那一步传一次，实到 " +
                occurrences(script, "'-PdebugSign'") + " 处；\$DebugSign 那枚参数本身实到 " +
                occurrences(script, "\$DebugSign") + " 处（参数声明 + 传旗子那一支各一次）：" +
                "第三处就是有人在脚本里又拿旗子当签名结论",
            1,
            occurrences(script, "'-PdebugSign'"),
        )
        assertTrue(
            "钉法 = 逐字节：发版路径最后一步仍然点名产物层守卫（#535 的判据跑在那枚类里；" +
                "那一行被删或那枚类被改名，就等于把签名者这一格交回给零枚读者）",
            script.contains(FORENSIC_CLASS_QUOTED_NEEDLE),
        )
    }

    /**
     * 册子⑥（钉法 = **数出现次数（全仓册子 + 手册侧下限）**）：同一枚调试 DN 常量与那枚尾巴，
     * 写侧、读侧、手册侧各有几处 —— 抓「第三枚读者冒出来」与「手册与脚本又各说各话」。
     */
    @Test
    fun theDebugSignerConstantHasOneWriterOneReaderAndTheHandbookAgrees() {
        val needle = DEBUG_DN_VALUE
        val buildCode = blankComments(workingTreeText(BUILD_SCRIPT))

        assertEquals(
            "钉法 = 数出现次数（抹注释找锚点）：写侧 $BUILD_SCRIPT 的**代码**里那枚调试 DN 片段应恰好 1 处" +
                "（只剩常量定义那一枚；KDoc 与文案里解释它不算第二把尺），实到 " +
                occurrences(buildCode, needle) + " 处：代码里多一处就是有人又写了一遍判据",
            1,
            occurrences(buildCode, needle),
        )

        val testReaders = kotlinFilesUnder(APP_TEST_TREE).filter { file -> occurrences(file.readText(), needle) > 0 }
        assertEquals(
            "钉法 = 数出现次数（册子）：读侧只许一枚测试文件认识这枚调试 DN 片段（$FORENSIC_CLASS_SIMPLE.kt，" +
                "注释里提及也算），实到 " + testReaders.size + " 枚：" + testReaders.map { file -> file.name }.sorted() +
                " —— 冒出来的第二枚读者意味着有人把这条判据又复刻了一遍（两把尺家族）",
            listOf(FORENSIC_CLASS_SIMPLE + ".kt"),
            testReaders.map { file -> file.name }.sorted(),
        )
        assertEquals(
            "钉法 = 数出现次数（抹注释找锚点）：读侧那一枚里也只许**代码**里 1 处（定义），实到 " +
                occurrences(blankComments(workingTreeText(FORENSIC_TEST_FILE)), needle) +
                " 处：报错文案里再抄一遍就变两把尺",
            1,
            occurrences(blankComments(workingTreeText(FORENSIC_TEST_FILE)), needle),
        )
        assertEquals(
            "钉法 = 数出现次数（册子）：应用侧源码不该认识这枚调试常量 —— 运行时判的是附件名与安装前的" +
                "签名预检，不判「谁签的」，实到的文件枚数应为 0",
            0,
            kotlinFilesUnder(APP_MAIN_TREE).count { file -> occurrences(file.readText(), needle) > 0 },
        )

        // 手册那一页：F3 那两处假句（三步序列、调试附件命名纪律）改完之后必须留得住
        val handbook = workingTreeText(RELEASE_HANDBOOK)
        assertTrue(
            "钉法 = 数出现次数（手册侧下限）：docs/RELEASE.md 里得有「$DEBUG_TAIL_IN_HANDBOOK」那枚名字 —— " +
                "这一页原先要求调试附件名含 debug，而脚本自己交出的名字违反它",
            occurrences(handbook, DEBUG_TAIL_IN_HANDBOOK) >= 1,
        )
        assertTrue(
            "钉法 = 数出现次数（手册侧下限）：docs/RELEASE.md 里得有「$HANDBOOK_GUARD_SECTION」那一档 —— " +
                "这一页原先把发版写成三步并明言「做完就停」，把唯一读产物的那一步描述成正常终点",
            occurrences(handbook, HANDBOOK_GUARD_SECTION) >= 1,
        )
        assertEquals(
            "钉法 = 数出现次数：手册里那枚调试 DN 片段应恰好 1 处（判据说明那一句），实到 " +
                occurrences(handbook, needle) + " 处",
            1,
            occurrences(handbook, needle),
        )
    }

    // ---- 读盘与小工具 ------------------------------------------------------------------------------

    private fun workingTreeText(relativeFromRoot: String): String {
        val file = File(projectRoot(), relativeFromRoot)
        assertTrue("找不到 ${file.path}：#535 这册判据依赖它，路径挪过家要回来改这里", file.isFile)
        return file.readText()
    }

    /** 工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍（同 ReleaseForensicLogSurvivalTest） */
    private fun projectRoot(): File {
        var dir: File? = File("").absoluteFile
        var hops = 0
        while (dir != null && hops < 5) {
            val current = dir!!
            if (File(current, "app/src/main/java/com/buaa/schedule").isDirectory) return current
            if (File(current, "src/main/java/com/buaa/schedule").isDirectory) return current.parentFile
            dir = current.parentFile
            hops++
        }
        throw IllegalStateException("找不到项目根目录：当前目录 ${File("").absolutePath}")
    }

    private fun kotlinFilesUnder(relativeDir: String): List<File> {
        val dir = File(projectRoot(), relativeDir)
        assertTrue("找不到 $relativeDir：这一格扫的册子覆盖面就是它", dir.isDirectory)
        return dir.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()
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

    /**
     * 取一枚顶层函数的函数体：从签名处起找到的第一枚左花括号（签名行自己带 brace 时就是那一枚），
     * 按花括号配平取到闭合处。交回 null = 签名不在盘上或配平失败（换了写法 ⇒ 调用方那一格自己红，这里不猜）。
     */
    private fun functionBody(text: String, signatureLine: String): String? {
        val at = text.indexOf(signatureLine)
        if (at < 0) return null
        val open = text.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        return null
    }

    /**
     * 把行注释与块注释（含 KDoc）抹成空白，**字符串与三引号原文里的内容一个字不动**。
     *
     * 不做「把整份文件里的注释字符全删」那种写法：构建脚本里那段三引号的发布说明书含有
     * `curl -i ... | grep -i -E "^(HTTP|location)"` 这种带引号、星号与斜杠的形状。本实现按
     * 「先认三引号、再认单引号、最后才认注释」的顺序走，所以字符串里的注释起点不会被误当注释。
     */
    private fun blankComments(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith(TRIPLE_QUOTE, index) -> {
                    val close = text.indexOf(TRIPLE_QUOTE, index + TRIPLE_QUOTE.length)
                    if (close < 0) {
                        out.append(text.substring(index))
                        index = text.length
                    } else {
                        out.append(text, index, close + TRIPLE_QUOTE.length)
                        index = close + TRIPLE_QUOTE.length
                    }
                }
                text[index] == '"' -> {
                    out.append('"')
                    index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\' && index + 1 < text.length) {
                            out.append(text[index])
                            out.append(text[index + 1])
                            index += 2
                        } else {
                            out.append(text[index])
                            index++
                        }
                    }
                    if (index < text.length) {
                        out.append('"')
                        index++
                    }
                }
                text.startsWith("//", index) -> {
                    while (index < text.length && text[index] != '\n') index++
                }
                text.startsWith(BLOCK_COMMENT_OPEN, index) -> {
                    val close = text.indexOf(BLOCK_COMMENT_CLOSE, index + BLOCK_COMMENT_OPEN.length)
                    index = if (close < 0) text.length else close + BLOCK_COMMENT_CLOSE.length
                    out.append(' ')
                }
                else -> {
                    out.append(text[index])
                    index++
                }
            }
        }
        return out.toString()
    }

    // ---- 册子里的针（逐字节原文；改一个字符就红） ------------------------------------------------------

    private val BUILD_SCRIPT = "app/build.gradle.kts"
    private val RELEASE_SCRIPT = "release.ps1"
    private val RELEASE_HANDBOOK = "docs/RELEASE.md"
    private val APP_TEST_TREE = "app/src/test"
    private val APP_MAIN_TREE = "app/src/main"
    private val FORENSIC_CLASS_SIMPLE = "ReleaseForensicLogSurvivalTest"
    private val FORENSIC_TEST_FILE = "app/src/test/java/com/buaa/schedule/reminder/ReleaseForensicLogSurvivalTest.kt"
    private val FORENSIC_CLASS_QUOTED_NEEDLE = "'com.buaa.schedule.reminder." + FORENSIC_CLASS_SIMPLE + "'"

    private val CANONICAL_NAME_NEEDLE = "buaa-schedule-\$releaseVersionName.apk"
    private val ARTIFACT_NAME_FUN = "fun artifactNameFor(signer: String): String = when (signer) {"
    private val RELEASE_NAME_BRANCH = "signerRelease -> \"" + CANONICAL_NAME_NEEDLE + "\""
    private val DEBUG_NAME_BRANCH = "signerDebug -> \"buaa-schedule-\$releaseVersionName\$debugArtifactNameSuffix\""
    private val UNSIGNED_NAME_BRANCH = "else -> \"buaa-schedule-\$releaseVersionName\$unsignedArtifactNameSuffix\""
    private val DEBUG_SUFFIX_DECL = "val debugArtifactNameSuffix = \"-debugsigned\""
    private val UNSIGNED_SUFFIX_DECL = "val unsignedArtifactNameSuffix = \"-unsigned\""

    private val SIGNED_BY_KEY = "setProperty(\"signedBy\", signer)"
    private val SIGNER_DN_KEY = "setProperty(\"signerDn\", certificate?.first.orEmpty())"
    private val SIGNER_FINGERPRINT_KEY = "setProperty(\"signerSha256\", certificate?.second.orEmpty())"
    private val PROVENANCE_SIGNATURE =
        "fun writeReleaseProvenance(artifact: File, readFromApk: Pair<String, Int>?, signer: String)"

    private val PUBLISH_STEPS_SIGNATURE = "fun printPublishSteps(artifact: File, signer: String)"
    private val NOT_PUBLISHABLE_GATE = "if (signer != signerRelease) {"
    private val NOT_PUBLISHABLE_CALL = "printNotPublishableNotice(artifact, signer)"
    private val HARDCODED_VERDICT_CALL = "printPublishSteps(artifact, signerRelease)"
    private val UPLOAD_PREFIX = "上传附件："
    private val UPLOAD_INSTRUCTION = UPLOAD_PREFIX + "\${artifact.name}"

    private val ASSERT_NAME_FUN_SIGNATURE = "fun assertNameMatchesSigner(artifact: File, signer: String)"
    private val ASSERT_NAME_CALL = "assertNameMatchesSigner(artifact, measuredSigner)"
    private val PRE_POST_COPY_CHECK = "if (measuredSigner != signer) {"
    private val ORIGIN_BRANCH_NEEDLE =
        "unsigned.isFile && releaseDebugSign -> signWithDebugKey(unsigned) to originDebugKey"

    private val SCRIPT_CANONICAL_ASSIGN =
        "\$canonicalArtifact = Join-Path \$root \"dist\\buaa-schedule-\$Version.apk\""
    private val SCRIPT_SELFTEST_ASSIGN =
        "\$selfTestArtifact = Join-Path \$root \"dist\\buaa-schedule-\$Version-debugsigned.apk\""
    private val SCRIPT_CANONICAL_TEMPLATE = "dist\\buaa-schedule-\$Version.apk"
    private val SCRIPT_SELFTEST_TEMPLATE = "dist\\buaa-schedule-\$Version-debugsigned.apk"
    private val SCRIPT_PUBLISHABLE_ASSIGN = "\$publishable = (\$artifact -eq \$canonicalArtifact)"
    private val SCRIPT_PUBLISHABLE_BRANCH = "if (-not \$publishable) {"

    /** 拼出来取：本文件不许数进自己的册子里（同那枚 `assumeTrue` 针的手法） */
    private val DEBUG_DN_VALUE = "CN=Androi" + "d Debug"
    private val DEBUG_DN_DECL = "val debugSignerDnMarker = \"" + DEBUG_DN_VALUE + "\""
    private val DEBUG_TAIL_IN_HANDBOOK = "buaa-schedule-<版本>-debugsigned.apk"
    private val HANDBOOK_GUARD_SECTION = "产物层守卫"

    private val TRIPLE_QUOTE = "\"\"\""
    private val BLOCK_COMMENT_OPEN = "/*"
    private val BLOCK_COMMENT_CLOSE = "*" + "/"
}
