package com.buaa.schedule.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份排除名单的两把尺（T85b-B 的推导 ＋ T330 的逐字册子）。
 *
 * 一句话口径：**凡是鉴权材料，都必须出现在两份备份规则每一块的排除名单里** ——
 * 一份靠"从源码数出来"（只管走 [KeystoreBlobStore] 的存储），另一份靠"逐字对一张写死的册子"
 * （管其余三枚：`buaa_session.xml`、`app_webview`、`spoc_token_store.xml`）。
 *
 * 为什么值得单独钉：排除名单是**手写的资源文件**，凭证存储是**另一头写的 Kotlin**。
 * 两边唯一的连线就是一枚字面量（prefs 名 + `.xml`），所以加一枚凭证存储的人
 * 只要没顺手改 res/xml，那枚密文就会安静地跟着云备份上云 —— 编译不响、
 * 单测不响、装机也不响，因为它只在用户换机或开了备份的时候才发生。
 *
 * T84 就漏了这一次：`buaa_cookie_store.xml` 与 `spoc_token_store.xml` 都在名单里，
 * 新加的 `iclass_id_store.xml` 不在，而它和前两枚是同一套密文、同一条排除理由。
 * 那一笔当时只留下一句「本卡记下但没有改」的注释（T85b-A 已经把它兑现）。
 * 本文件要防的是**下一枚**：不靠人记得，靠一次 `testDebugUnitTest`。
 *
 * T330 收的是另一头账（推导那一判据自己的漏）：它只数得出 2 枚 `KeystoreBlobStore` 实例，
 * 而名单 5 枚 ⇒ `app_webview`、`buaa_session.xml`、`spoc_token_store.xml` 三枚**零判据**，
 * 手工删掉那三行里任何一行，`testDebugUnitTest` 全绿、`assembleRelease` 成功。
 *
 * 判据的两条路（**第 0 问的读数决定了为什么是两条**）：
 * - 推导那一路仍然**从源码里数出来**，不抄名单 —— 抄来的名单会跟着源码一起漏改。
 *   数法：扫 `app/src/main/java` 下所有 `.kt`，取每个 `KeystoreBlobStore(` 实例化的
 *   `prefsName` 字面量（具名实参、或按位置的第一枚字面量都认）。
 * - 册子那一路是**写死的 5 枚**（[CREDENTIAL_ROSTER]）。它不是没想到要推导，是盘上推导不出来：
 *   唯一同族的第二枚标记是 `PREFS_NAME` / `prefsName` 的字面量，可它在 main 里数出 6 枚值，其中
 *   `schedule_settings`（用户课表与偏好）、`widget_appearance`、`widget_binding` **不是凭证**，
 *   拿它当推导源就等于把这三枚也排进名单（那是换一种错）；`app_webview` 在 `app/src/main` 的任何
 *   `.kt` 里零字面量，那枚目录名由 framework 决定，本仓没有任何一行代码或测试能把它算出来。
 *   所以册子逐枚配**盘上锚点**，锚点归哪一档由 [everyRosterEntryCarriesAnAnchorFindableOnDisk] 钉，
 *   册子与推导集的同源由 [rosterDerivableEntriesAreExactlyTheDerivedKeystoreStores] 钉，
 *   「非凭证不许进名单」由 [everyScannedPrefsNameIsEitherExcludedOrDeclaredNonCredential] 钉。
 *   代价（在册）：新增一枚凭证存储要同时改两份 xml 的三块 + 这张册子；只改代码不改册子的话，
 *   推导那一判据与那两格同源尺都会点名它，不会是静默的。
 *
 * 三条口径说明：
 * - **读不到就是要失败，不许 skip**。刀法照 `IClassSignInWiringGuardTest`：
 *   跳过的守卫比没有守卫更糟，历史上"文件读不到就 assume 掉"掩过真问题。
 * - 只看**工作目录里的源码与资源**，不碰构建产物、不碰网络、不装机，纯 JVM。
 * - `spoc_token_store.xml` 那一行**不许算进推导集**（`SpocTokenStore.kt` 已随 T85 删除，
 *   盘上再没有对应的 prefs 名可数，推导那一路看不见它），但它那一行**现在由这把尺子管着** ——
 *   管它的是逐字册子那一格，不是推导那一格（T330 之前的口径是「它只由注释钉着」，那句话已经不成立）。
 *   那行排除是给**老设备残留**留的，删掉等于把别人设备上的凭证密文送上云，所以报错消息里点名
 *   「老设备残留行，不许跟着删」。
 *
 * ⚠️ 改完 `res/xml` 单独重跑本文件**不需要**再带 `--rerun`（T92① 把这根拐杖收了）：
 * 这把尺子量的是磁盘上的字，而 JVM 测试任务默认只认编译产物 —— 资源文件不在里面，Gradle 会
 * 直接端上缓存的绿灯（T85b 取证时就是这么"通过"了一次删掉三行排除的改动，本轮实验 A 复现了它）。
 * 现在 `app/build.gradle.kts` 末尾那段 `guardReadWorkingTreeFiles` 把本文件读的两份 res/xml
 * 连同整棵 `src/main/res`、清单、脚本一起声明成了 `Test` 任务的输入，增量跑也认这些文件。
 * 门禁那侧本来就跑 `--rerun-tasks`，不受影响。
 *
 * 所以：往 `res/` 下**新加**一类要被守卫读的文件时，只要它还在 `app/src/main/res` 里就自动进输入面；
 * 但要是守卫改去读 `app/src/main/res` 之外的路径（例如某个新模块的资源），就得把那条路径也补进
 * 那份清单 —— 漏了的表现为"这把尺子又回到只在全量跑时管用"，正是本卡收掉的那笔账。
 * T330 没有动那份清单：它新增读取的只有 `app/src/main/java`（已登记的第 ① 格）与
 * 原本就在读的 `app/src/main/res` 下那两份规则文件（第 ② 格覆盖整棵 `src/main/res`），
 * 一格都不必补 —— 清单由 `WorkingTreeInputSurfaceGuardTest` 三向对账，本卡的改动不触发它。
 */
class BackupRulesCoverCredentialStoresTest {

    /** 主判据：每一枚在用的凭证存储，两份规则文件都得排除掉它 */
    @Test
    fun everyKeystoreBackedCredentialPrefIsExcludedFromBothRuleFiles() {
        val stores = credentialStorePrefNames()
        val legacy = read(RELATIVE_LEGACY)
        val modern = read(RELATIVE_MODERN)

        // 名单本身不许是空的：扫不出任何存储 ⇒ 数法坏了，"全都排除了"就是句空话
        assertTrue(
            "一个 KeystoreBlobStore 实例都没扫出来：要么存储整条搬了家（本守卫要跟着改），" +
                "要么 `prefsName` 的写法换了 ⇒ 下面那些「都排除了」全是假的",
            stores.isNotEmpty(),
        )

        val cloud = excludePathsInBlock(modern, "cloud-backup")
        val transfer = excludePathsInBlock(modern, "device-transfer")
        val legacyPaths = excludePathsInBlock(legacy, "full-backup-content")

        val missing = ArrayList<String>()
        for (pref in stores) {
            val file = "$pref.xml"
            if (file !in cloud) missing += "backup_rules.xml <cloud-backup> 没排除 $file"
            if (file !in transfer) missing += "backup_rules.xml <device-transfer> 没排除 $file"
            if (file !in legacyPaths) missing += "backup_rules_legacy.xml 没排除 $file"
        }
        assertEquals(
            "凭证存储漏出备份名单（漏一行就是 quietly 把密文送上云 / 迁到别的设备）：" +
                "\n" + missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
    }

    /**
     * 反向对照：这三块排除名单确实**读得出东西**，所以上面那句「在名单里」不是空集比空集。
     *
     * 不加这一条的话，标签写法一变（比如 `<cloud-backup>` 带了属性、或改了缩进）
     * 就会让解析器返回空集合，于是"什么都没排除"与"排除的正好是扫出来的那些"在断言里长得一样。
     */
    @Test
    fun eachRuleBlockActuallyParsesToANonEmptyExcludeList() {
        val modern = read(RELATIVE_MODERN)
        val legacy = read(RELATIVE_LEGACY)
        for ((label, paths) in listOf(
            "<cloud-backup>" to excludePathsInBlock(modern, "cloud-backup"),
            "<device-transfer>" to excludePathsInBlock(modern, "device-transfer"),
            "<full-backup-content>" to excludePathsInBlock(legacy, "full-backup-content"),
        )) {
            assertTrue(
                "$label 里一条 exclude 都没解析出来：解析器坏了，第一条判据就成了自证",
                paths.size >= 3,
            )
        }
        // 两个通道各自独立：只写在一边等于另一边没排除（T85b-A 之前 iclass 就是这个形状）
        assertEquals(
            "<cloud-backup> 与 <device-transfer> 的排除名单长岔了：两个通道要走同一条口径",
            excludePathsInBlock(modern, "cloud-backup").sorted(),
            excludePathsInBlock(modern, "device-transfer").sorted(),
        )
    }

    /**
     * 靶子自检：这把尺子**真的会红**。
     *
     * 拿一枚没登记的 prefs 名走一遍同一条判据，必须被判成缺三行。
     * 少了这一条，"漏排 iclass_id_store.xml 那次没人发现"完全可以再来一次 ——
     * 因为解析器什么都没看见时，上面两条断言都是绿的。
     */
    @Test
    fun rulerStillReportsAnUnregisteredStore() {
        val modern = read(RELATIVE_MODERN)
        val legacy = read(RELATIVE_LEGACY)
        val probe = "no_such_credential_store"
        for ((label, paths) in listOf(
            "<cloud-backup>" to excludePathsInBlock(modern, "cloud-backup"),
            "<device-transfer>" to excludePathsInBlock(modern, "device-transfer"),
            "<full-backup-content>" to excludePathsInBlock(legacy, "full-backup-content"),
        )) {
            assertFalse("$probe.xml 不该出现在 $label 里", probe + ".xml" in paths)
        }
    }

    // ---- T330①：乙档（逐字册子对账）—— 三块各算各的账 -------------------------------------------

    /**
     * T330 主判据（乙档）：三块排除名单**各自**枚数＝5 且逐字等于写死的凭证册子 [CREDENTIAL_ROSTER]。
     *
     * 为什么要另加这一格：上面那条推导出来的判据只数得出 `KeystoreBlobStore` 的实例（本树实测 2 枚），
     * 而名单有 5 枚 —— `app_webview`、`buaa_session.xml`、`spoc_token_store.xml` 三枚**零判据**。
     * 删掉 `backup_rules_legacy.xml` 里 `<exclude domain="root" path="app_webview" />` 那一行，
     * `testDebugUnitTest` 全绿、`assembleRelease` 成功：云备份与就近传输就会带走 WebView 数据目录里的
     * Cookies / Local Storage / IndexedDB，正是两份文件注释与 `docs/PRIVACY.md` 都点名的
     * 「跨设备真正可用」的 SSO 凭证 —— R5 F-C3 那起历史事故的复发路径（内容修好了，判据没修）。
     * 删掉 `buaa_session.xml` 那一行同理（后果轻一档：冷启动白跑一次会话恢复）。
     *
     * 三块不许折成「任一块含该名就算过」：`<cloud-backup>` 与 `<device-transfer>` 是两个独立通道，
     * `full-backup-content` 是 API ≤ 30 那一本，少任何一块就是那条通道上 quietly 没排除。
     * 折成取或时这一格会红在**枚数**上（并集 15 枚 vs 册子 5 枚），不是红在「看不见」上。
     *
     * 代价（如实写）：新增一枚凭证存储要改三处 —— 两份 xml 的每一块 + 这张册子。忘了改册子，
     * [rosterDerivableEntriesAreExactlyTheDerivedKeystoreStores] 与
     * [everyScannedPrefsNameIsEitherExcludedOrDeclaredNonCredential] 都会点名它。
     */
    @Test
    fun eachRuleBlockEqualsTheCredentialRosterVerbatim() {
        val modern = read(RELATIVE_MODERN)
        val legacy = read(RELATIVE_LEGACY)
        val mismatch = ArrayList<String>()
        for ((label, entries) in listOf(
            "backup_rules.xml <cloud-backup>" to excludeEntriesInBlock(modern, "cloud-backup"),
            "backup_rules.xml <device-transfer>" to excludeEntriesInBlock(modern, "device-transfer"),
            "backup_rules_legacy.xml <full-backup-content>" to excludeEntriesInBlock(legacy, "full-backup-content"),
        )) {
            if (entries.size != CREDENTIAL_ROSTER.size) {
                mismatch += "$label 枚数实到 ${entries.size}，册子写死 ${CREDENTIAL_ROSTER.size}（册子：" +
                    CREDENTIAL_ROSTER.joinToString("、") { "${it.domain}/${it.fileName}" } + "）"
            }
            for (want in CREDENTIAL_ROSTER) {
                if (entries.none { have -> have == (want.domain to want.fileName) }) {
                    mismatch += "$label 少了 ${want.fileName}（domain=${want.domain}）—— ${want.why}"
                }
            }
            for ((domain, path) in entries) {
                if (CREDENTIAL_ROSTER.none { it.domain == domain && it.fileName == path }) {
                    mismatch += "$label 多出册子外的一枚 $domain/$path —— 名单每多一枚都要在锚点那一格交代它是什么"
                }
            }
        }
        assertEquals(
            "备份排除名单与凭证册子对不上账（少一行就是 quietly 把鉴权材料送上云 / 迁到别的设备，" +
                "多一枚就是把该随备份迁移的课表偏好堵死；三块各算各的账，不许折成任一块有就算过）：\n" +
                mismatch.joinToString("\n"),
            emptyList<String>(),
            mismatch,
        )
    }

    /**
     * 册子的每一枚都得有盘上锚点，且**锚归哪一档要与它的档一致**。
     *
     * 「读不到就是要失败」在这一格的落法：
     * - 标成 [RosterAnchor.DERIVABLE] 的枚枚都要在 `app/src/main/java`（抹注释后）里命名锚，
     *   且不许挂着「必须数不出」那一档；
     * - 标成 [RosterAnchor.CODE_ANCHORED_BUT_NOT_DERIVABLE] 的要在 main 里数得出名字锚 ——
     *   它不进推导档的理由由 [everyScannedPrefsNameIsEitherExcludedOrDeclaredNonCredential] 说；
     * - 标成 [RosterAnchor.LEGACY_RESIDUE] / [RosterAnchor.FRAMEWORK_DIRECTORY] 的两枚**在 Kotlin 侧
     *   零名字锚**，它们的锚是「main 里数不出这枚名」＋规则文件原文里那句理由 ＋ main 里那套
     *   WebView/Keystore 机制仍在。报错消息会点名这一档没有代码锚这件事，不 skip、不假设通过。
     *
     * 反向也钉：一旦 `spoc_token_store` 或 `app_webview` 真的出现在 main 源码里（说明本仓开始
     * 生产/管理那枚名字），这一格当场红并要求把该枚挪档 —— 第 0 问(a) 的读数变了就得重来，
     * 不许让册子继续挂着「数不出来」。
     */
    @Test
    fun everyRosterEntryCarriesAnAnchorFindableOnDisk() {
        val mainCode = mainSourceCodeWithoutComments()
        val ruleFiles = readRaw(RELATIVE_MODERN) + "\n" + readRaw(RELATIVE_LEGACY)
        val findings = ArrayList<String>()
        for (entry in CREDENTIAL_ROSTER) {
            val needsNameAnchor = entry.anchor == RosterAnchor.DERIVABLE ||
                entry.anchor == RosterAnchor.CODE_ANCHORED_BUT_NOT_DERIVABLE
            if (needsNameAnchor && entry.mainSourceNameAnchors.isEmpty()) {
                findings += "${entry.fileName} 挂的是 ${entry.anchor} 档，却在 main 里一枚名字锚都没有 —— " +
                    "这一档的含义就是「盘上数得出这枚名」，数不出来就不要挂这一档"
            }
            if (!needsNameAnchor && entry.mainSourceNameAnchors.isNotEmpty()) {
                findings += "${entry.fileName} 标成 ${entry.anchor} 档（Kotlin 侧零名字锚），" +
                    "可它现在写着名字锚 ${entry.mainSourceNameAnchors} —— 要么删掉那枚锚，要么把它挪进可推导那一档"
            }
            if (entry.anchor == RosterAnchor.DERIVABLE && entry.namesThatMustStayOutOfMainSources.isNotEmpty()) {
                findings += "${entry.fileName} 既走推导又挂着「main 里必须数不出」${entry.namesThatMustStayOutOfMainSources}：两栏自相矛盾"
            }
            for (anchor in entry.mainSourceNameAnchors) {
                if (!mainCode.contains(anchor)) findings += "${entry.fileName} 的 main 名字锚在盘上命不中：$anchor"
            }
            for (anchor in entry.mainSourceSupportAnchors) {
                if (!mainCode.contains(anchor)) findings += "${entry.fileName} 的 main 佐证锚在盘上命不中：$anchor"
            }
            for (absent in entry.namesThatMustStayOutOfMainSources) {
                if (mainCode.contains(absent)) {
                    findings += "${entry.fileName} 是靠「main 源码里数不出 $absent 这枚名」立成 ${entry.anchor} 档的，" +
                        "如今它出现在 main 源码里 —— 第 0 问(a)(b)(c) 的读数变了，这一枚该改走推导，不要拿册子顶着"
                }
            }
            if (!ruleFiles.contains(entry.ruleFileAnchor)) {
                findings += "${entry.fileName} 在两份规则文件原文里也找不到那句理由：${entry.ruleFileAnchor} —— " +
                    "它既没有 Kotlin 侧的名字锚，现在连注释侧的交代也没了，这一格不许当过"
            }
        }
        assertEquals(
            "凭证册子的锚点对不上盘面（在册的每一枚都得说得出「凭什么说它装的是鉴权材料」）：\n" +
                findings.joinToString("\n"),
            emptyList<String>(),
            findings,
        )
    }

    /**
     * 名单与真值同源（卡 §3.3 那把尺）：册子里标成 [RosterAnchor.DERIVABLE] 的那几枚，
     * 逐字等于从源码扫出来的推导集 —— 不多不少。
     *
     * 这一格同时是「不许把 `spoc_token_store.xml` 算进推导集」的执行处（类 KDoc 的 T85 那条口径）：
     * 推导集只数 `KeystoreBlobStore(` 的实例化，而册子里 spoc 标的是 [RosterAnchor.LEGACY_RESIDUE]。
     * 有人图省事把 spoc 挪进 DERIVABLE 档，这里念的就是「推导集里没有它」。
     *
     * 少一枚＝新增凭证存储忘了登记进册子（它此刻只在推导那一判据里有名，名单里却是空的）；
     * 多一枚＝册子里挂了一枚其实扫得出来的名，那它就不该由写死的册子管。
     */
    @Test
    fun rosterDerivableEntriesAreExactlyTheDerivedKeystoreStores() {
        val derived = credentialStorePrefNames()
        val derivable = CREDENTIAL_ROSTER.filter { it.anchor == RosterAnchor.DERIVABLE }.map { it.prefName }.sorted()
        assertTrue(
            "一个 KeystoreBlobStore 实例都没扫出来 ⇒ 「册子的 DERIVABLE 档 == 推导集」这一格就成了空集比空集",
            derived.isNotEmpty(),
        )
        assertEquals(
            "凭证册子的 DERIVABLE 档与源码推导集不同源。推导集（扫 KeystoreBlobStore 实例化）：" +
                derived.joinToString("、") +
                " ｜ 册子 DERIVABLE 档：" + derivable.joinToString("、") +
                " ｜ 册子里其余各枚走的是逐字册子那一档（" +
                CREDENTIAL_ROSTER.filter { it.anchor != RosterAnchor.DERIVABLE }.joinToString("、") { "${it.prefName}=${it.anchor}" } +
                "），理由见每一枚的 why",
            derived,
            derivable,
        )
    }

    /**
     * 乙档为什么是乙：`PREFS_NAME` / `prefsName` 这一路标记**圈不住凭证**，所以册子只能逐字写死。
     *
     * 第 0 问(a) 的现量：main 源码里这一路数出 6 枚 distinct 值 —— 3 枚在册（`buaa_cookie_store`、
     * `iclass_id_store`、`buaa_session`），3 枚**不是**凭证（`schedule_settings`、`widget_appearance`、
     * `widget_binding`）。`schedule_settings` 装着用户课表与偏好，正是该随备份走的东西，一旦把它排进
     * 名单就是「换一种错」：登录态不外泄了，课表也不迁移了。这一格钉的就是那一档。
     *
     * 两把尺钉同一枚名的交代（不许含糊）：`buaa_cookie_store` 与 `iclass_id_store` 既在推导集里、
     * 又在册子里。推导那一路不许退化（卡 §5：现有主判据的覆盖面只许增不许减），册子那一路多钉的
     * 是**枚数与 domain**；`buaa_session` 只走册子 —— 它的名字锚虽在 main 里数得出，但同一枚标记
     * 会把三枚非凭证一起圈进来，拿它当推导源就等于把 `schedule_settings` 也送上排除名单。
     *
     * 枚数尺钉绝对值（[MARKER_SCANNED_PREF_NAMES]，6 枚）：扫空了＝尺瞎了要红，长了＝有人新增了
     * 一枚 prefs 名，必须先判它是不是凭证再登记 —— 不许「多出来就算没看见」。
     */
    @Test
    fun everyScannedPrefsNameIsEitherExcludedOrDeclaredNonCredential() {
        val scanned = prefsNamesMarkedByTheSourceMarker()
        val rosterNames = CREDENTIAL_ROSTER.map { it.prefName }
        val findings = ArrayList<String>()
        for (name in NON_CREDENTIAL_PREF_NAMES) {
            if (name in rosterNames) {
                findings += "非凭证的 $name 被排进了备份名单 —— 它装着用户课表与偏好，正是该随备份迁移的东西"
            }
            if (name !in scanned) {
                findings += "$name 还在「非凭证」册子里，可 main 源码里已经数不出它了：那枚 prefs 搬了家，两栏要一起收"
            }
        }
        for (name in scanned) {
            if (name !in NON_CREDENTIAL_PREF_NAMES && name !in rosterNames) {
                findings += "main 源码数出一枚 $name，既不在凭证册子也不在明写的非凭证册子里 —— 判它归哪一档，再回来登记"
            }
        }
        for (entry in CREDENTIAL_ROSTER.filter { it.anchor == RosterAnchor.CODE_ANCHORED_BUT_NOT_DERIVABLE }) {
            if (entry.prefName !in scanned) {
                findings += "${entry.fileName} 挂在 CODE_ANCHORED_BUT_NOT_DERIVABLE 档，靠的就是「标记数得出它但圈不准」，" +
                    "如今 PREFS_NAME 这一路已经数不出 ${entry.prefName} 了"
            }
        }
        assertEquals(
            "第 0 问(a) 的读数变了：PREFS_NAME / prefsName 这一路在 app/src/main/java 里扫出的 distinct 值，" +
                "写死的是 ${MARKER_SCANNED_PREF_NAMES.size} 枚（${MARKER_SCANNED_PREF_NAMES.sorted().joinToString("、")}），" +
                "实到 ${scanned.size} 枚（${scanned.joinToString("、")}）。" +
                "先看下面这些没归类的，再决定是把尺扩成推导还是把值重钉：\n" + findings.joinToString("\n"),
            MARKER_SCANNED_PREF_NAMES.sorted(),
            scanned,
        )
        assertTrue(
            "凭证册子与非凭证册子的归类没对上盘面：\n" + findings.joinToString("\n"),
            findings.isEmpty(),
        )
    }

    // ---- 数存储名的那一段：扫源码（这一路只数得出 KeystoreBlobStore 的实例；写死的那张册子在
    //      文件末尾的 CREDENTIAL_ROSTER，两条路各管什么、为什么不能并成一条，见类 KDoc 与
    //      rosterDerivableEntriesAreExactlyTheDerivedKeystoreStores） ----

    /**
     * `app/src/main/java` 下每一枚 `KeystoreBlobStore(...)` 实例化用到的 prefs 名。
     *
     * 类声明那一处（`internal class KeystoreBlobStore(`）必须跳过 —— 它的形参表里
     * 一个字符串字面量都没有，混进来也数不出东西，但"按名字扫"这件事要说清楚。
     */
    private fun credentialStorePrefNames(): List<String> {
        val sources = mainSourceFiles()

        val found = LinkedHashSet<String>()
        for (file in sources) {
            val code = blankComments(file.readText())
            var from = 0
            while (true) {
                val at = code.indexOf(CALL, from)
                if (at < 0) break
                from = at + CALL.length
                if (isDeclaration(code, at)) continue
                val args = argumentList(code, at + CALL.length - 1)
                val named = NAMED_PREFS.find(args)
                val pref = named?.groupValues?.get(1) ?: positionalFirstLiteral(args)
                if (pref != null) {
                    assertTrue(
                        "prefs 名不该带路径分隔或空白：$pref（${file.name}）",
                        pref.isNotBlank() && !pref.contains('/') && !pref.contains('\\') && !pref.contains(' '),
                    )
                    found += pref
                } else {
                    throw IllegalStateException(
                        "${file.path} 里有一处 $CALL 取不出 prefsName：实参表=$args。" +
                            "写法换过了要把数法一起改 —— 让它静默不计入，等于给下一枚漏排开门",
                    )
                }
            }
        }
        return found.sorted()
    }

    /** `app/src/main/java` 下的全部 `.kt`，按路径排序；读不到就当场失败，不许 skip */
    private fun mainSourceFiles(): List<File> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到源码目录：$root", root.isDirectory)
        val sources = ArrayList<File>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { sources += it }
        assertTrue("main 源码树里一个 .kt 都没有：路径或工作副本坏了", sources.isNotEmpty())
        return sources
    }

    /**
     * 整棵 `app/src/main/java` 抹注释后的文本（一枚大字符串，够用：本仓 main 源码 ~5 千行）。
     *
     * 抹注释是必须的：`backup_rules.xml` 的理由写在注释里会被 [read] 抹掉，反过来 Kotlin 侧的
     * 注释里也举过 prefs 名的例子（`IClassIdStore.kt` 头一段就是），不抹就会把注释当成盘上事实。
     */
    private fun mainSourceCodeWithoutComments(): String =
        buildString { mainSourceFiles().forEach { append(blankComments(it.readText())).append('\n') } }

    /**
     * 第 0 问(a) 那把尺：`PREFS_NAME` / `prefsName` 后面跟字面量的写法，在 main 源码里数出的 distinct 值。
     *
     * 覆盖面边界（如实写）：只认这两枚标识符；`WidgetBackgroundRenderer.kt:67` 那枚换了名的
     * `SETTINGS_PREFS`、以及 `ColdStartRebuild.kt:137` 那种 `PREFS_NAME = ClassProgressReceiver.PREFS_NAME`
     * （右侧不是字面量）都扫不进来。这一把的存在恰恰是**甲档不成立**的证据：它圈不住凭证。
     */
    private fun prefsNamesMarkedByTheSourceMarker(): List<String> =
        PREFS_NAME_LITERAL.findAll(mainSourceCodeWithoutComments()).map { it.groupValues[1] }.toSortedSet().toList()

    /** `class KeystoreBlobStore(` / `interface` 之类的声明位，不是实例化 */
    private fun isDeclaration(code: String, at: Int): Boolean {
        val before = code.substring(0, at).trimEnd()
        return before.endsWith("class") || before.endsWith("interface") || before.endsWith("fun")
    }

    /** 从 `open` 处那个左括号起，取到配平的右括号为止（含括号） */
    private fun argumentList(code: String, open: Int): String {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
        }
        throw IllegalStateException("$CALL 的实参表括号没配平")
    }

    /** 位置实参的第一枚字面量就是 prefsName（构造器唯一的 ordering 事实） */
    private fun positionalFirstLiteral(args: String): String? =
        REGEX_STRING_FIRST.find(args)?.groupValues?.get(1)

    private fun excludePathsInBlock(xml: String, block: String): List<String> =
        EXCLUDE_PATH.findAll(blockBody(xml, block)).map { it.groupValues[1] }.toList()

    /**
     * 同一枚块的 `(domain, path)` 对。
     *
     * 只比 `path` 是不够的：`app_webview` 走的是 `domain="root"`，而其余四枚走 `sharedpref`，
     * 把那一枚的 domain 改成 `sharedpref` 会留下一行**看着在名单里、其实一个字都没排除**的假账
     * （`root` 才是数据目录那一层）。取不出 domain 或 path 的那一枚直接抛 —— 读不到就是要失败。
     */
    private fun excludeEntriesInBlock(xml: String, block: String): List<Pair<String, String>> =
        EXCLUDE_TAG.findAll(blockBody(xml, block)).map { tag ->
            val text = tag.value
            val domain = DOMAIN_ATTR.find(text)?.groupValues?.get(1)
                ?: throw IllegalStateException("<$block> 里有一枚 <exclude> 取不出 domain：$text")
            val path = PATH_ATTR.find(text)?.groupValues?.get(1)
                ?: throw IllegalStateException("<$block> 里有一枚 <exclude> 取不出 path：$text")
            domain to path
        }.toList()

    /** 切出某个块的正文（注释已由 [read] 抹掉，所以这里看得见的只可能是真标签） */
    private fun blockBody(xml: String, block: String): String {
        val open = Regex("<$block\\b[^>]*>").find(xml)
            ?: throw IllegalStateException("规则文件里没有 <$block> 这个块：写法换过了，本守卫要跟着改")
        val close = xml.indexOf("</$block>", open.range.last)
        assertTrue("<$block> 没有闭合标签", close > open.range.last)
        return xml.substring(open.range.last + 1, close)
    }

    /**
     * 读规则文件，并**先把 XML 注释抹掉**。
     *
     * 这两份文件的注释里写的正是排除理由（`<cloud-backup>` 这类标签名一旦出现在注释里，
     * 按标签找块就会找到注释那一段，于是排除名单读自错误区间 —— 而读出自注释的行数
     * 往往比真名单还多，看着更像"通过"）。真名单只可能来自标签本身。
     */
    private fun read(relativeUnderRepoRoot: String): String {
        val file = File(findRepoRoot(), relativeUnderRepoRoot)
        assertTrue("找不到 $relativeUnderRepoRoot：$file", file.isFile)
        return withoutXmlComments(file.readText())
    }

    /**
     * 同一份文件的**原文**（注释留着）—— 只有锚点那一格用它。
     *
     * 理由：`spoc_token_store.xml` 与 `app_webview` 这两枚在 Kotlin 侧一个字都数不出来，
     * 它们在盘上的交代只有规则文件自己那段注释。名单判据吃的是抹掉注释的那一份（免得注释里的
     * 标签名骗过切片），锚点判据吃的必须是原文 —— 两栏分开，谁也不冒充谁。
     */
    private fun readRaw(relativeUnderRepoRoot: String): String {
        val file = File(findRepoRoot(), relativeUnderRepoRoot)
        assertTrue("找不到 $relativeUnderRepoRoot：$file", file.isFile)
        return file.readText()
    }

    /** 抹掉 `<!-- … -->`，保留换行，位置错乱不至于把标签吞一半 */
    private fun withoutXmlComments(xml: String): String {
        val out = xml.toCharArray()
        var i = 0
        while (i < out.size) {
            if (xml.startsWith("<!--", i)) {
                val end = xml.indexOf("-->", i + 4).let { if (it < 0) out.size else it + 3 }
                for (k in i until end.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                i = end
            } else {
                i++
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

    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            if (dir != null && File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }

    /** 抹掉注释再扫：注释里举过 `KeystoreBlobStore(` 的例子（`IClassIdStore.kt` 的头一段就是） */
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
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
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
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
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

    private companion object {
        const val RELATIVE_MODERN = "app/src/main/res/xml/backup_rules.xml"
        const val RELATIVE_LEGACY = "app/src/main/res/xml/backup_rules_legacy.xml"
        const val CALL = "KeystoreBlobStore("
        val NAMED_PREFS = Regex("""prefsName\s*=\s*"([^"]+)"""")
        val REGEX_STRING_FIRST = Regex("""\(\s*"([^"]+)"""")
        val EXCLUDE_PATH = Regex("""<exclude\b[^>]*?\bpath\s*=\s*"([^"]*)"""")

        /** T330：一枚 `<exclude … />` 标签整体，再分别取它的 domain 与 path */
        val EXCLUDE_TAG = Regex("""<exclude\b[^>]*>""")
        val DOMAIN_ATTR = Regex("""\bdomain\s*=\s*"([^"]*)"""")
        val PATH_ATTR = Regex("""\bpath\s*=\s*"([^"]*)"""")

        /** T330 第 0 问(a)：`PREFS_NAME` / `prefsName` 后面跟字面量 —— 这一圈不住凭证，见那格 KDoc */
        val PREFS_NAME_LITERAL = Regex("""(?:PREFS_NAME|prefsName)\s*=\s*"([^"]+)"""")
    }
}

/**
 * 册子里每一枚的**立册依据**归哪一档（T330 第 0 问的读数决定它）。
 *
 * 档不是装饰：[everyRosterEntryCarriesAnAnchorFindableOnDisk] 拿它判「这一枚该不该有 Kotlin 侧的
 * 名字锚」。有人把扫得出来的名挂成遗留档（想让锚点那一格闭嘴），或把扫不出来的名挂成推导档
 * （想让同源性那一格闭嘴），都会当场红。
 */
private enum class RosterAnchor {
    /** 名字由 `KeystoreBlobStore(` 的实例化扫出来 —— 推导那一判据与册子两把尺同钉，理由见那格 KDoc */
    DERIVABLE,

    /** 名字在 main 里数得出，但**数出它的那枚标记圈不住凭证**（同一把尺还会圈住 schedule_settings） */
    CODE_ANCHORED_BUT_NOT_DERIVABLE,

    /** 代码已随 T85 删除，盘上没有可数的 prefs 名：那一行是给老设备残留留的，不许跟着删 */
    LEGACY_RESIDUE,

    /** 目录名由 framework 决定（私有数据目录下的 app_ 前缀 + webkit 存储段），本仓零字面量、算不出来 */
    FRAMEWORK_DIRECTORY,
}

/** 一排除项：名单里的那一枚 `(domain, path)` ＋ 它在盘上的交代 */
private data class CredentialRosterEntry(
    val domain: String,
    val fileName: String,
    val prefName: String,
    val anchor: RosterAnchor,
    val mainSourceNameAnchors: List<String>,
    val mainSourceSupportAnchors: List<String>,
    val namesThatMustStayOutOfMainSources: List<String>,
    val ruleFileAnchor: String,
    val why: String,
)

/**
 * 凭证册子（T330 写死的乙档）：两份规则文件每一块都该逐字等于它。
 *
 * 顺序照 xml 里的写法（buaa_cookie_store / spoc_token_store / iclass_id_store / buaa_session /
 * app_webview），对账时两边都排序比，所以顺序只是给人看的。
 * 每一枚的锚点都是盘上 `grep -F` 命中的原文；**命不中 Kotlin 的那两枚在档里明写着命不中**，
 * 报错消息会把它念出来，不许 skip、不许假设通过。
 */
private val CREDENTIAL_ROSTER = listOf(
    CredentialRosterEntry(
        domain = "sharedpref",
        fileName = "buaa_cookie_store.xml",
        prefName = "buaa_cookie_store",
        anchor = RosterAnchor.DERIVABLE,
        mainSourceNameAnchors = listOf("prefsName = \"buaa_cookie_store\""),
        mainSourceSupportAnchors = listOf("internal class KeystoreBlobStore"),
        namesThatMustStayOutOfMainSources = emptyList(),
        ruleFileAnchor = "AndroidKeyStore 密文",
        why = "教务会话 Cookie 的 AndroidKeyStore 密文（走推导那一路）",
    ),
    CredentialRosterEntry(
        domain = "sharedpref",
        fileName = "spoc_token_store.xml",
        prefName = "spoc_token_store",
        anchor = RosterAnchor.LEGACY_RESIDUE,
        mainSourceNameAnchors = emptyList(),
        mainSourceSupportAnchors = listOf("internal class KeystoreBlobStore"),
        namesThatMustStayOutOfMainSources = listOf("spoc_token_store"),
        ruleFileAnchor = "老版本残留",
        why = "老设备残留行，不许跟着删：SpocTokenStore 已随 T85 删除，Kotlin 侧一枚名字锚都数不出" +
            "（这一档在报错里必须说实话），但装过旧版的设备上那份 JWT 密文还在，少一行就是把它送上云。" +
            "它不许进推导集 —— 类 KDoc 那条口径仍然算数",
    ),
    CredentialRosterEntry(
        domain = "sharedpref",
        fileName = "iclass_id_store.xml",
        prefName = "iclass_id_store",
        anchor = RosterAnchor.DERIVABLE,
        mainSourceNameAnchors = listOf("prefsName = \"iclass_id_store\""),
        mainSourceSupportAnchors = listOf("internal class KeystoreBlobStore"),
        namesThatMustStayOutOfMainSources = emptyList(),
        ruleFileAnchor = "iClass 签到 id 的密文",
        why = "北航 iClass（轻新课堂）签到 id 的密文，与 buaa_cookie_store 同一套 KeystoreBlobStore",
    ),
    CredentialRosterEntry(
        domain = "sharedpref",
        fileName = "buaa_session.xml",
        prefName = "buaa_session",
        anchor = RosterAnchor.CODE_ANCHORED_BUT_NOT_DERIVABLE,
        mainSourceNameAnchors = listOf("PREFS_NAME = \"buaa_session\""),
        mainSourceSupportAnchors = listOf("PREF_RETAINED"),
        namesThatMustStayOutOfMainSources = emptyList(),
        ruleFileAnchor = "会话保留标志",
        why = "BuaaWebSession 的会话保留标志（PREF_RETAINED）：漏掉这一行，新设备冷启动会带着" +
            "「应当恢复会话」的标记去加载 byxt，白跑一次恢复流程。名字在 main 里数得出，" +
            "却不许走推导 —— 数出它的那枚 PREFS_NAME 标记同时圈住 schedule_settings 那三枚非凭证名",
    ),
    CredentialRosterEntry(
        domain = "root",
        fileName = "app_webview",
        prefName = "app_webview",
        anchor = RosterAnchor.FRAMEWORK_DIRECTORY,
        mainSourceNameAnchors = emptyList(),
        mainSourceSupportAnchors = listOf("import android.webkit.CookieManager"),
        namesThatMustStayOutOfMainSources = listOf("app_webview"),
        ruleFileAnchor = "WebView 的数据目录",
        why = "WebView 的数据目录：里面的 Cookies / Local Storage / IndexedDB 才是「跨设备真正可用」的 " +
            "SSO 凭证，删掉这一行就是 R5 F-C3 那起事故的复发路径（此前只排了两枚 SharedPreferences，" +
            "等于把登录态整份迁到新设备）。目录名由 framework 决定，本仓 Kotlin 侧零字面量、" +
            "推不出来（这一档在报错里必须说实话）。它走 domain=root，不许折成 include —— 同域 include " +
            "会连带堵死课表迁移",
    ),
)

/**
 * 明写的**非凭证** prefs 名（卡 §1：那不是凭证，不许被排除）。
 *
 * `schedule_settings` 装着用户课表、提醒与外观偏好（`FirstRun` / `ClassProgressReceiver` /
 * `IslandFocusTemplate` / `TomorrowPreviewReceiver` / `UpdateCheck` 五处共用同一枚值，另有
 * `WidgetBackgroundRenderer` 换了标识符名再指它一次），`widget_appearance` 与 `widget_binding`
 * 是桌面组件的外观与绑定 —— 三枚都正是该随备份走的东西。它们出现在 `PREFS_NAME` 那一路的扫描里，
 * 所以甲档（把标记当第二路推导源）一旦成立就会把它们排掉：这就是甲被否掉的理由。
 */
private val NON_CREDENTIAL_PREF_NAMES = listOf("schedule_settings", "widget_appearance", "widget_binding")

/**
 * 第 0 问(a) 的现量：`PREFS_NAME` / `prefsName` 后面跟字面量，在 `app/src/main/java` 里扫出的
 * distinct 值（枚数尺钉绝对值 6，扫空了＝尺瞎了要红）。
 * 新增一枚 prefs（无论是不是凭证）都会让这一格先红，逼人来判归哪一档。
 */
private val MARKER_SCANNED_PREF_NAMES = listOf(
    "buaa_cookie_store",
    "buaa_session",
    "iclass_id_store",
    "schedule_settings",
    "widget_appearance",
    "widget_binding",
)
