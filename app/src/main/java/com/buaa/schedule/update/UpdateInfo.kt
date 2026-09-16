package com.buaa.schedule.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Gitee Releases API（v5）的一条 release。
 *
 * 字段与 GitHub 兼容；全部给可空默认值，因为 Gitee 会把没有填的字段返回成
 * `null`（而不是缺字段），非空默认值会在解码时直接抛异常。
 * ⚠️ 仓库转公开后需用真实响应复核一次字段名。
 */
@Serializable
data class GiteeRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean? = null,
    val prerelease: Boolean? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GiteeAsset>? = null,
)

@Serializable
data class GiteeAsset(
    val name: String? = null,
    val label: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    val size: Long? = null,
)

/** 一条可安装的更新候选 */
data class UpdateInfo(
    /** 规范化后的版本号（去掉 tag 的 v 前缀） */
    val version: String,
    val title: String,
    val notes: String,
    /** 可信域名下的 APK 直链；为空表示只能跳浏览器下载 */
    val apkUrl: String?,
    val pageUrl: String,
    val apkSize: Long,
)

const val PROJECT_GITEE_URL = "https://gitee.com/alyssumira/buaa-schedule"
const val PROJECT_GITHUB_URL = "https://github.com/alyssumira/buaa-schedule"
const val AUTHOR_GITEE_URL = "https://gitee.com/alyssumira"
const val AUTHOR_GITHUB_URL = "https://github.com/alyssumira"
const val RELEASES_PAGE_URL = "$PROJECT_GITEE_URL/releases"

private const val RELEASES_API_URL =
    "https://gitee.com/api/v5/repos/alyssumira/buaa-schedule/releases?per_page=30"

/** 应用内下载只认 Gitee 自己的域名：直链被劫持等于把"装什么 APK"交给对端 */
private val TRUSTED_DOWNLOAD_HOSTS = listOf("gitee.com")

fun releasesApiUrl(): String = RELEASES_API_URL

internal fun parseReleases(text: String, json: Json): List<GiteeRelease> =
    json.decodeFromString<List<GiteeRelease>>(text)

internal fun isTrustedDownloadUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host != null &&
            TRUSTED_DOWNLOAD_HOSTS.any { uri.host.equals(it, true) || uri.host.endsWith(".$it", true) }
    }.getOrDefault(false)
}

internal fun normalizeVersion(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.startsWith("v") || trimmed.startsWith("V")) trimmed.substring(1) else trimmed
}

/**
 * 逐段数值比较；缺的段按 0 算，所以 "1.0" == "1.0.0"。
 * 只吃数字前缀，"1.2.0-beta" 与 "1.2.0" 判等 —— 预发布版靠 release 的 prerelease 标志过滤，
 * 不在字符串里比大小，否则 "1.10.0" 会被字典序判成比 "1.9.0" 小。
 */
internal fun compareVersions(left: String, right: String): Int {
    val a = versionParts(left)
    val b = versionParts(right)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return if (x > y) 1 else -1
    }
    return 0
}

private fun versionParts(raw: String): List<Int> =
    normalizeVersion(raw)
        .takeWhile { it.isDigit() || it == '.' }
        .split('.')
        .mapNotNull { it.toIntOrNull() }

internal fun GiteeRelease.toUpdateOrNull(): UpdateInfo? {
    val tag = tagName?.trim().orEmpty()
    val version = normalizeVersion(tag)
    if (version.isEmpty() || !version.first().isDigit()) return null
    if (draft == true || prerelease == true) return null
    val apk = assets.orEmpty().firstOrNull {
        isTrustedDownloadUrl(it.browserDownloadUrl) &&
            (it.name?.endsWith(".apk", ignoreCase = true) == true ||
                it.contentType == "application/vnd.android.package-archive")
    }
    return UpdateInfo(
        version = version,
        title = name?.trim().orEmpty().ifEmpty { tag },
        notes = body?.trim().orEmpty(),
        apkUrl = apk?.browserDownloadUrl,
        pageUrl = htmlUrl?.takeIf { it.isNotBlank() } ?: RELEASES_PAGE_URL,
        apkSize = apk?.size ?: 0L,
    )
}

/** 取"比当前新、且没被用户忽略"的最大版本；没有则 null */
internal fun pickLatestUpdate(
    releases: List<GiteeRelease>,
    currentVersion: String,
    skippedVersion: String?,
): UpdateInfo? =
    releases.asSequence()
        .mapNotNull { it.toUpdateOrNull() }
        .filter { compareVersions(it.version, currentVersion) > 0 }
        .filter { it.version != skippedVersion }
        .sortedWith { x, y -> compareVersions(y.version, x.version) }
        .firstOrNull()

/** 只在"今天还没查过"时放行；手动检查（force）永远放行 */
internal fun shouldCheckToday(lastCheckedDay: String?, today: String, force: Boolean): Boolean =
    force || lastCheckedDay != today
