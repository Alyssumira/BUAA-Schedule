package com.buaa.schedule.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检测的纯逻辑单测（JVM，不碰网络和 Android）。
 *
 * JSON 样本按 Gitee v5 Releases 的真实形状写：未填的字段是显式 `null` 而不是缺字段，
 * 这是解析最容易踩的地方，所以直接把它钉进用例里。
 */
class UpdateInfoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun release(
        tag: String?,
        assets: List<GiteeAsset> = emptyList(),
        draft: Boolean? = false,
        prerelease: Boolean? = false,
    ) = GiteeRelease(
        tagName = tag,
        name = "Release $tag",
        body = "更新说明",
        draft = draft,
        prerelease = prerelease,
        htmlUrl = "https://gitee.com/alyssumira/buaa-schedule/releases/tag/$tag",
        assets = assets,
    )

    private fun apk(url: String?, name: String? = "app-release.apk") = GiteeAsset(
        name = name,
        contentType = "application/vnd.android.package-archive",
        browserDownloadUrl = url,
        size = 4_718_592L,
    )

    @Test
    fun parsesGiteeReleaseListWithExplicitNulls() {
        val text = """
            [
              {
                "tag_name": "v0.2.0",
                "name": "0.2.0",
                "body": null,
                "draft": false,
                "prerelease": false,
                "html_url": "https://gitee.com/alyssumira/buaa-schedule/releases/tag/v0.2.0",
                "published_at": "2026-09-16T10:00:00+08:00",
                "assets": [
                  {
                    "name": "buaa-schedule-v0.2.0.apk",
                    "label": null,
                    "content_type": "application/vnd.android.package-archive",
                    "browser_download_url": "https://gitee.com/.../buaa-schedule-v0.2.0.apk",
                    "size": 4718592
                  }
                ]
              },
              { "tag_name": null, "name": null, "assets": null }
            ]
        """.trimIndent()

        val releases = parseReleases(text, json)

        assertEquals(2, releases.size)
        val first = releases[0].toUpdateOrNull()!!
        assertEquals("0.2.0", first.version)
        assertEquals("0.2.0", first.title)
        // body 为 null 时不能写出 "null"，弹窗按空说明处理
        assertEquals("", first.notes)
        assertEquals(4_718_592L, first.apkSize)
        assertTrue(first.apkUrl!!.startsWith("https://gitee.com/"))
        // tag_name 为 null 的一条不能被当成更新
        assertNull(releases[1].toUpdateOrNull())
    }

    @Test
    fun comparesVersionsNumericallyNotLexically() {
        // 字典序会把 1.10 判成比 1.9 小
        assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(compareVersions("0.2.0", "0.10.0") < 0)
        // 缺的段按 0 算，发布方少写一段不能判成"没有更新"
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertEquals(0, compareVersions("v1.2.3", "1.2.3"))
        assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
    }

    @Test
    fun normalizesTagPrefix() {
        assertEquals("1.2.3", normalizeVersion("  v1.2.3 "))
        assertEquals("1.2.3", normalizeVersion("V1.2.3"))
        assertEquals("1.2.3", normalizeVersion("1.2.3"))
    }

    @Test
    fun ignoresDraftPrereleaseAndNonNumericTags() {
        val url = "https://gitee.com/alyssumira/buaa-schedule/releases/download/v1/app.apk"
        val reason = "draft / prerelease / 非数字 tag 不能成为更新候选"

        assertNull(reason, release("v9.9.9", listOf(apk(url)), draft = true).toUpdateOrNull())
        assertNull(reason, release("v9.9.9", listOf(apk(url)), prerelease = true).toUpdateOrNull())
        assertNull(reason, release("latest", listOf(apk(url))).toUpdateOrNull())
        assertNull(reason, release(null, listOf(apk(url))).toUpdateOrNull())
    }

    @Test
    fun onlyTrustsGiteeHttpsDownloadLinks() {
        assertTrue(isTrustedDownloadUrl("https://gitee.com/a/b.apk"))
        // 子域放行：Gitee 的附件实际走 foruda.gitee.com 这类 CDN 域
        assertTrue(isTrustedDownloadUrl("https://foruda.gitee.com/a.apk"))
        assertFalse(isTrustedDownloadUrl("http://gitee.com/a.apk"))
        assertFalse(isTrustedDownloadUrl("https://gitee.com.evil.co/a.apk"))
        assertFalse(isTrustedDownloadUrl("https://gitee.com.cn/a.apk"))
        assertFalse(isTrustedDownloadUrl("https://evil.co/?next=https://gitee.com/a.apk"))
        assertFalse(isTrustedDownloadUrl(null))
        assertFalse(isTrustedDownloadUrl(""))
    }

    @Test
    fun releaseWithoutTrustedApkFallsBackToBrowserPage() {
        val info = release(
            "v0.3.0",
            listOf(
                apk("https://evil.co/app.apk"),
                GiteeAsset(name = "source.zip", contentType = "application/zip", browserDownloadUrl = "https://gitee.com/a/source.zip"),
            ),
        ).toUpdateOrNull()!!

        assertNull(info.apkUrl)
        assertEquals(0L, info.apkSize)
        // 没有可用附件也要能跳发布页，不能把更新提示整个吞掉
        assertEquals("https://gitee.com/alyssumira/buaa-schedule/releases/tag/v0.3.0", info.pageUrl)
    }

    @Test
    fun picksNewestReleaseAboveCurrentVersion() {
        val url = "https://gitee.com/alyssumira/buaa-schedule/releases/download/x/app.apk"
        val releases = listOf(
            release("v0.1.0", listOf(apk(url))),
            release("v0.10.0", listOf(apk(url))),
            release("v0.2.0", listOf(apk(url))),
            release("v0.9.0", listOf(apk(url)), prerelease = true),
        )

        assertEquals("0.10.0", pickLatestUpdate(releases, "0.1.0", null)?.version)
        // 已经是最新（含"当前版本更高"的前向兼容场景）时不提示
        assertNull(pickLatestUpdate(releases, "0.10.0", null))
        assertNull(pickLatestUpdate(releases, "1.0.0", null))
        // 「忽略此版本」只压掉那一个版本，更新的仍然要提示
        assertEquals("0.10.0", pickLatestUpdate(releases, "0.1.0", "0.2.0")?.version)
        assertEquals("0.2.0", pickLatestUpdate(releases, "0.1.0", "0.10.0")?.version)
    }

    @Test
    fun checksOncePerDayUnlessForced() {
        assertTrue(shouldCheckToday(null, "2026-09-16", force = false))
        assertFalse(shouldCheckToday("2026-09-16", "2026-09-16", force = false))
        assertTrue(shouldCheckToday("2026-09-15", "2026-09-16", force = false))
        // 设置页手动检查不受每日节流限制
        assertTrue(shouldCheckToday("2026-09-16", "2026-09-16", force = true))
    }

    @Test
    fun autoCheckStaysSilentUnlessAnUpdateExists() {
        val info = UpdateInfo("0.2.0", "0.2.0", "", null, RELEASES_PAGE_URL, 0L)

        assertTrue(UpdateUiState.Available(info, manual = false).visible)
        assertTrue(UpdateUiState.Downloading(info, percent = 42).visible)
        // 自动检测的"无更新/失败"不能每天第一次打开时打断用户
        assertFalse(UpdateUiState.UpToDate(manual = false).visible)
        assertFalse(UpdateUiState.Failed("HTTP 404", manual = false).visible)
        // 手动检查两者都要给反馈
        assertTrue(UpdateUiState.UpToDate(manual = true).visible)
        assertTrue(UpdateUiState.Failed("HTTP 404", manual = true).visible)
        assertFalse(UpdateUiState.Idle.visible)
        assertFalse(UpdateUiState.Checking.visible)
    }

    @Test
    fun apiUrlTargetsThisRepo() {
        assertEquals(
            "https://gitee.com/api/v5/repos/alyssumira/buaa-schedule/releases?per_page=30",
            releasesApiUrl(),
        )
    }
}
