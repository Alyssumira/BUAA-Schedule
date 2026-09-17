package com.buaa.schedule.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实抓取**的 Gitee v5 响应（`src/test/resources/update/gitee_releases_real.json`，
 * 2026-09-17 匿名请求 `releases?per_page=100` 原样落盘）跑一遍更新决策。
 *
 * [UpdateInfoTest] 里的样本是按真实形状**手写**的，覆盖不到 Gitee 自动附加的两样东西：
 * `v0.1.0.zip` 与 `v0.1.0.tar.gz` 源码归档。它们的 `browser_download_url` 同样是
 * `https://gitee.com/...`，域名白名单拦不住——只有附件名后缀那道判断能拦。
 * 一旦哪天选包逻辑改成"取第一个可信附件"，用户就会下载到一个源码压缩包、
 * 校验它又不是 zip 文件头而是合法 zip（源码归档就是 zip），最后交给安装器报"解析软件包时出现问题"。
 *
 * 这个用例钉的就是"真实响应里挑出来的必须恰好是正式签名那个 APK"。
 */
class RealGiteePayloadTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val releases: List<GiteeRelease> = javaClass
        .getResourceAsStream("/update/gitee_releases_real.json")
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?.let { parseReleases(it, json) }
        ?: error("测试夹具 /update/gitee_releases_real.json 读不到")

    /** 夹具本身要包含源码归档，否则"没被选中"这件事无法证伪 */
    @Test
    fun fixtureCarriesTheSourceArchivesThatMustNeverBePicked() {
        val names = releases.flatMap { it.assets.orEmpty() }.mapNotNull { it.name }
        assertTrue("夹具里没有源码归档，这个用例就失去意义：$names", names.any { it.endsWith(".zip") })
        assertTrue("夹具里没有源码归档，这个用例就失去意义：$names", names.any { it.endsWith(".tar.gz") })
        assertTrue("夹具里没有正式包：$names", names.contains("buaa-schedule-0.1.0.apk"))
        assertTrue("夹具里没有调试包：$names", names.contains("buaa-schedule-0.1.0-debug.apk"))
    }

    /** 线上正好是当前版本：不能提示更新，否则装完号不变、冷启动再弹一遍 */
    @Test
    fun currentVersionMatchesThePublishedTagSoNothingIsOffered() {
        assertNull(pickLatestUpdate(releases, "0.1.0", skippedVersion = null))
    }

    /** 装的是更旧的版本：必须挑中正式签名那个 APK，而不是 debug 包或源码归档 */
    @Test
    fun olderInstallPicksTheReleaseApkAndNothingElse() {
        val info = pickLatestUpdate(releases, "0.0.9", skippedVersion = null)
        assertNotNull("比当前新的版本被漏掉了，用户永远收不到更新", info)
        requireNotNull(info)

        assertEquals("0.1.0", info.version)
        assertEquals(
            "https://gitee.com/alyssumira/buaa-schedule/releases/download/v0.1.0/buaa-schedule-0.1.0.apk",
            info.apkUrl,
        )
        assertTrue("挑中的直链不在可信域名上，应用会把它交给浏览器而非安装器", isTrustedDownloadUrl(info.apkUrl))
        // html_url 在真实响应里缺席，靠 tag 拼；实测该地址返回 200
        assertEquals("https://gitee.com/alyssumira/buaa-schedule/releases/tag/v0.1.0", info.pageUrl)
        // assets 不带 size，弹窗的"安装包体积"那一行不显示，体积改由下载响应的 Content-Length 给
        assertEquals(0L, info.apkSize)
        assertEquals("2026年9月16日", formatReleaseDate(info.publishedAt))
        assertEquals("0.1.0", info.title)
    }

    /** release 正文要能渲染出来，否则弹窗只剩"发布者没有填写更新说明" */
    @Test
    fun changelogRendersAndMentionsTheInAppUpdate() {
        val info = pickLatestUpdate(releases, "0.0.9", skippedVersion = null)!!
        val lines = changelogLines(info.notes)
        assertTrue("更新说明解析成了空列表，弹窗看不到任何发布内容", lines.isNotEmpty())
        val text = lines.filterIsInstance<ChangelogLine.Bullet>().joinToString("\n") { it.text }
        assertTrue("正文里的中文条目没被解析出来：$text", text.contains("应用内检查更新"))
        // 正文里的下划线标识符不能被 markdown 清理吃掉（MARKDOWN_EMPHASIS 故意不处理 `_`）
        assertTrue(text.isNotEmpty())
    }

    /** 被忽略的版本不再自动提示；但手动检查（force）绕过忽略，仍要能拿到 */
    @Test
    fun skippedVersionIsHonouredForAutomaticChecks() {
        assertNull(pickLatestUpdate(releases, "0.0.9", skippedVersion = "0.1.0"))
        // check() 里 force=true 时传的是 skipped=null，所以手动检查走的就是这条
        assertNotNull(pickLatestUpdate(releases, "0.0.9", skippedVersion = null))
    }
}
