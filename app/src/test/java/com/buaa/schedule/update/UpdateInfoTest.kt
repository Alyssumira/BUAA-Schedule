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
    fun debugSignedAssetIsNeverPickedOverTheReleaseOne() {
        val base = "https://gitee.com/alyssumira/buaa-schedule/releases/download/v0.1.0/"
        val canonical = apk("${base}buaa-schedule-0.1.0.apk", name = "buaa-schedule-0.1.0.apk")
        val debug = apk("${base}buaa-schedule-0.1.0-debug.apk", name = "buaa-schedule-0.1.0-debug.apk")

        // 附件顺序由 Gitee 决定，两种顺序都必须选中正式包
        assertEquals(canonical.browserDownloadUrl, pickApkAsset(listOf(debug, canonical), "0.1.0")?.browserDownloadUrl)
        assertEquals(canonical.browserDownloadUrl, pickApkAsset(listOf(canonical, debug), "0.1.0")?.browserDownloadUrl)
        // 正式包被改过名字（没有规范名）时，也不能退到调试包
        assertEquals(
            "https://gitee.com/a/other.apk",
            pickApkAsset(listOf(debug, apk("https://gitee.com/a/other.apk", name = "other.apk")), "0.1.0")
                ?.browserDownloadUrl,
        )
        // 只剩调试包：宁可没有下载链接、让用户走发布页，也不把调试签名的包装机
        assertNull(pickApkAsset(listOf(debug), "0.1.0"))
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
        val day = "2026-09-16"
        val now = 1_760_000_000_000L
        fun should(lastDay: String?, failureAt: Long, force: Boolean = false) =
            shouldCheckToday(lastDay, failureAt, day, now, force)

        assertTrue(should(null, 0L))
        assertFalse(should(day, 0L))
        assertTrue(should("2026-09-15", 0L))
        // 设置页手动检查不受每日节流限制
        assertTrue(should(day, 0L, force = true))

        // 失败不占当天配额：断网时打开过一次应用，剩下的启动仍然要能查到更新
        assertTrue(should(day, now - FAILURE_RETRY_MILLIS - 1))
        // 但也不能一打开就重试——刚失败的那几分钟里仍然静默
        assertFalse(should(day, now - FAILURE_RETRY_MILLIS + 60_000L))
        assertFalse(should(day, now))
    }

    @Test
    fun autoCheckStaysSilentUnlessAnUpdateExists() {
        val info = UpdateInfo("0.2.0", "0.2.0", "", null, RELEASES_PAGE_URL, 0L)

        assertTrue(UpdateUiState.Available(info, manual = false).visible)
        assertTrue(UpdateUiState.Downloading(info, percent = 42).visible)
        // 下载好之后卡在安装权限上：这是要用户动手的，必须弹
        assertTrue(UpdateUiState.NeedsInstallPermission(info).visible)
        // 下载完成但签名对不上：不弹出来就只剩一个"点了没反应"的红点
        assertTrue(UpdateUiState.InstallBlocked(info, "签名与本机不兼容").visible)
        // 体积要能显示给用户看（Content-Length 是唯一的体积来源）
        assertTrue(UpdateUiState.Downloading(info, percent = 42, totalBytes = 2_612_000L).visible)
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
    fun rendersChangelogAsStructuredLines() {
        val lines = changelogLines(
            """
            ## 新增
            - 首启引导：一次讲清**权限**与数据去向
            * 修复 `课表格子` 错位
            
            感谢反馈。
            """.trimIndent(),
        )

        assertEquals(ChangelogLine.Heading("新增"), lines[0])
        assertEquals(ChangelogLine.Bullet("首启引导：一次讲清权限与数据去向"), lines[1])
        assertEquals(ChangelogLine.Bullet("修复 课表格子 错位"), lines[2])
        assertEquals(ChangelogLine.Blank, lines[3])
        assertEquals(ChangelogLine.Paragraph("感谢反馈。"), lines[4])
    }

    @Test
    fun emptyChangelogProducesNoLines() {
        // 弹窗按"没有说明"整段隐藏，不能显示成一片空行
        assertTrue(changelogLines("").isEmpty())
        assertTrue(changelogLines("   \n\n  ").isEmpty())
    }

    @Test
    fun markdownLinksKeepTheirTextAndUrl() {
        assertEquals(
            "发布页 (https://gitee.com/x)",
            markdownPlainText("[发布页](https://gitee.com/x)"),
        )
        assertEquals("粗斜体", markdownPlainText("***粗斜体***"))
        assertEquals("斜体", markdownPlainText("*斜体*"))
        // 下划线记号**不**剥：更新说明里的 update_last_check_day 不能被啃成 update last check day
        assertEquals("update_last_check_day", markdownPlainText("update_last_check_day"))
        // 两侧带空格的星号是乘号/记号，不是强调：内容必须以非空白开头结尾才算 emphasis
        assertEquals("每周 * 节数", markdownPlainText("每周 * 节数"))
    }

    @Test
    fun formatsReleaseDateOrHidesTheRow() {
        assertEquals("2026年9月16日", formatReleaseDate("2026-09-16T10:00:00+08:00"))
        assertEquals("2026年9月16日", formatReleaseDate("2026-09-16T10:00:00Z"))
        assertEquals("2026年9月16日", formatReleaseDate("2026-09-16 10:00:00"))
        assertEquals("2026年9月16日", formatReleaseDate("2026-09-16"))
        // 解析不了要返回 null 让那一行消失，显示 1970年1月1日 比不显示更糟
        assertNull(formatReleaseDate(null))
        assertNull(formatReleaseDate(""))
        assertNull(formatReleaseDate("最近"))
    }

    /**
     * 2026-09-16 实测的匿名列表响应（公开仓库、无 token）：
     * 只有 `tag_name / name / body / prerelease / created_at / author / target_commitish / assets`，
     * 附件只有 `name` 与 `browser_download_url`。GitHub 那套 `html_url`、`published_at`、`draft`
     * 一个都没有 —— 这个样本就是按实测形状写的，不是按文档理想形状写的。
     */
    @Test
    fun parsesRealGiteeShapeWithoutHtmlUrlOrAssetSize() {
        val text = """
            [
              {
                "id": 12345678,
                "tag_name": "v0.1.0",
                "name": "v0.1.0",
                "body": "首个公开版本",
                "prerelease": false,
                "created_at": "2026-09-16T10:00:00+08:00",
                "target_commitish": "master",
                "author": { "login": "alyssumira" },
                "assets": [
                  {
                    "name": "buaa-schedule-0.1.0.apk",
                    "browser_download_url": "https://gitee.com/alyssumira/buaa-schedule/releases/download/v0.1.0/buaa-schedule-0.1.0.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val info = parseReleases(text, json).single().toUpdateOrNull()!!

        assertEquals("0.1.0", info.version)
        // 附件不带 size：只能显示"体积未知"，等响应的 Content-Length 再说
        assertEquals(0L, info.apkSize)
        // 没有 html_url 也要有发布页可跳：按 tag 自己拼，退回列表页会把用户丢在一堆旧版本里
        assertEquals(
            "https://gitee.com/alyssumira/buaa-schedule/releases/tag/v0.1.0",
            info.pageUrl,
        )
        // published_at 缺席时取 created_at，否则弹窗的日期那一行会凭空消失
        assertEquals("2026-09-16T10:00:00+08:00", info.publishedAt)
        assertEquals("2026年9月16日", formatReleaseDate(info.publishedAt))
        // 只有 name 和 url 也认得出这是 APK 附件（content_type 同为缺席字段）
        assertTrue(
            info.apkUrl ==
                "https://gitee.com/alyssumira/buaa-schedule/releases/download/v0.1.0/buaa-schedule-0.1.0.apk",
        )
    }

    @Test
    fun buildsTagPageUrlOrFallsBackToListPage() {
        assertEquals(
            "$RELEASES_PAGE_URL/tag/v0.1.0",
            releasePageUrl("v0.1.0"),
        )
        // URL 安全字符之外的内容一律退回列表页：拼出一个 404 比退回去更糟
        assertEquals("URL 里有空格不能拼直链", RELEASES_PAGE_URL, releasePageUrl("v0.1.0 beta"))
        assertEquals("空 tag 不能拼出 /tag/ 结尾的怪链接", RELEASES_PAGE_URL, releasePageUrl(""))
        assertEquals(RELEASES_PAGE_URL, releasePageUrl("v1#2"))
    }

    @Test
    fun redirectHandoffRequiresHttps() {
        assertTrue(isHttpsUrl("https://foruda.gitee.com/attach_file/1.apk?token=x"))
        // 明文链路上的 APK 交给中间人换包，宁可退回浏览器
        assertFalse(isHttpsUrl("http://gitee.com/a.apk"))
        assertFalse(isHttpsUrl("ftp://gitee.com/a.apk"))
        assertFalse(isHttpsUrl(null))
        assertFalse(isHttpsUrl("not a url"))
    }

    @Test
    fun refusesToHandNonApkToTheInstaller() {
        val html = "<!DOCTYPE html>\n<html><head>登录 - Gitee".toByteArray()
        assertTrue(
            "登录页要指名道姓地说出来，否则用户只会看到「解析软件包时出现问题」",
            apkIntegrityProblem(html, html.size.toLong())!!.contains("网页"),
        )
        val jsonError = """{"message":"Not Found"}""".toByteArray()
        assertTrue(apkIntegrityProblem(jsonError, jsonError.size.toLong())!!.contains("网页"))

        // 小而不像文本的是截断；大到门槛但没有 zip 头的格式不对
        val junk = ByteArray(1024) { (it % 251).toByte() }
        assertTrue(apkIntegrityProblem(junk, 1024L)!!.contains("不完整"))
        val notZip = ByteArray(MIN_APK_BYTES.toInt()) { 0x41 }
        assertTrue(apkIntegrityProblem(notZip, MIN_APK_BYTES)!!.contains("不是 APK"))
        // 空的 zip（只有中央目录）也不是安装包
        val emptyZip = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(MIN_APK_BYTES.toInt())
        assertTrue(apkIntegrityProblem(emptyZip, MIN_APK_BYTES)!!.contains("不是 APK"))

        val apk = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(MIN_APK_BYTES.toInt())
        assertNull("正常的 APK 头不能被判成问题", apkIntegrityProblem(apk, apk.size.toLong()))
    }

    @Test
    fun keepsDownloadedApkSoRetryDoesNotReDownload() {
        val keep = "buaa-schedule-0.2.0.apk"
        // 装完之后重开：这一份已经验过 integrity，"重试安装"不该逼用户重下一遍
        assertFalse(isStaleDownload(keep, keep))
        // 半成品随时可能截断，一律算垃圾
        assertTrue(isStaleDownload("$keep.part", keep))
        assertTrue(isStaleDownload("APP.PART", keep))
        // 旧版本 / 换 tag 重发的残留
        assertTrue(isStaleDownload("buaa-schedule-0.1.0.apk", keep))
        // 首次下载时还没有保留目标，此时任何 APK 都还没有对应版本
        assertTrue(isStaleDownload(keep, null))
        // 非 APK 的文件名不归类为遗留下载（本目录只写 APK 与 .part，但判据不能顺手删别的）
        assertFalse(isStaleDownload("notes.txt", keep))
    }

    @Test
    fun apiUrlTargetsThisRepoWithMaximalPaging() {
        // per_page 是 Gitee v5 的上限 100：窗口小了最新发布压根不在返回的列表里，
        // 而"取比当前新的最大版本"就会退化成永远提示已是最新版本。
        assertEquals(
            "https://gitee.com/api/v5/repos/alyssumira/buaa-schedule/releases?per_page=100",
            releasesApiUrl(),
        )
    }
}
