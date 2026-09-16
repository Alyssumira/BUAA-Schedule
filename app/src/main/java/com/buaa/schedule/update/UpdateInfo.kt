package com.buaa.schedule.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Gitee Releases API（v5）的一条 release。
 *
 * 字段与 GitHub **近似但不兼容**：2026-09-16 用真实响应复核过（公开仓库、无 token），
 * 匿名列表接口只返回 `tag_name / name / body / prerelease / created_at / author / id /
 * target_commitish / assets`，而 GitHub 那套的 `html_url`、`published_at`、`draft`
 * 一个都没有。所以这三个字段只作"如果哪天有了就用"的兼容读取，
 * 任何逻辑都不能依赖它们非空（缺失时的取值见 [toUpdateOrNull]）。
 *
 * 全部字段给可空默认值：Gitee 会把没填的字段返回成 `null`（而不是缺字段），
 * 非空默认值会在解码时直接抛异常。
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
    /** 实测唯一稳定存在的发布时间字段，形如 `2021-01-24T02:19:44+08:00` */
    @SerialName("created_at") val createdAt: String? = null,
    val assets: List<GiteeAsset>? = null,
)

/**
 * release 的一个附件。实测只有两个字段：
 * `{"browser_download_url": "https://gitee.com/o/r/releases/download/v1.0/f.apk", "name": "f.apk"}`
 * —— 没有 `size`，也没有 `content_type`，所以安装包体积**无法预知**，
 * 只能等下载响应头的 Content-Length；[UpdateInfo.apkSize] 在真实数据下恒为 0。
 */
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
    /** release 的发布时间原文（ISO-8601）；解析不出日期时弹窗该行隐藏 */
    val publishedAt: String? = null,
)

const val PROJECT_GITEE_URL = "https://gitee.com/alyssumira/buaa-schedule"
const val PROJECT_GITHUB_URL = "https://github.com/alyssumira/buaa-schedule"
const val AUTHOR_GITEE_URL = "https://gitee.com/alyssumira"
const val AUTHOR_GITHUB_URL = "https://github.com/alyssumira"
const val RELEASES_PAGE_URL = "$PROJECT_GITEE_URL/releases"

/**
 * `per_page=100` 是 Gitee v5 的上限。[pickLatestUpdate] 取的是"比当前新的最大版本"，
 * 分页窗口不够大时最新发布可能根本不在返回的那一页里，于是永远提示"已是最新版本"。
 */
private const val RELEASES_API_URL =
    "https://gitee.com/api/v5/repos/alyssumira/buaa-schedule/releases?per_page=100"

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

/**
 * tag → 发布页直链。只接受 URL 安全字符的 tag，出现别的一律退回版本列表页：
 * 拼出一个 404 链接比退回列表页更糟——用户会以为发布页整个没了。
 */
internal fun releasePageUrl(tag: String): String =
    if (tag.isNotEmpty() && tag.all { it.isLetterOrDigit() || it in "._-~" }) {
        "$RELEASES_PAGE_URL/tag/$tag"
    } else {
        RELEASES_PAGE_URL
    }

/** 手动跟随重定向的底线：只允许 https。明文链路上下 APK 等于把"装什么"交给中间人 */
internal fun isHttpsUrl(url: String?): Boolean =
    runCatching { URI(url.orEmpty()).scheme?.equals("https", ignoreCase = true) == true }.getOrDefault(false)

/**
 * 小于这个体积的不可能是本应用的安装包（release APK 约 2.6 MB）。
 * 拿 64 KB 当门槛是为了把"登录页 / 错误 JSON / 空响应"挡在安装器之前，
 * 而不是精确描述 APK。
 */
internal const val MIN_APK_BYTES = 64L * 1024L

/** ZIP 本地文件头 `PK\x03\x04`：APK 就是 zip，且首条目是文件而非空包 */
private val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/** 文件头里读到的前若干字节 → 是否像一份文本响应（HTML 登录页 / JSON 错误） */
private val TEXT_OPENINGS = listOf("<!doctype", "<html", "<?xml", "{")

/**
 * 这份下载结果到底是不是 APK？没问题返回 null，否则返回给用户看的中文理由。
 *
 * 存在的全部理由：Gitee 的附件直链是**两跳 302** 才落到带 `token` 的 CDN，
 * 中途任何一个环节把用户挡在登录页后面，应用拿到的就是一段 `text/html`
 * 或一个 JSON 错误体，而它同样会被写成 `.apk` 交给系统安装器——
 * 那时用户看到的是"解析软件包时出现问题"，查不到原因。
 * 接口里没有校验和可用（assets 只给 name 和 url），所以格式判定只能落在文件本身。
 */
internal fun apkIntegrityProblem(head: ByteArray, fileSize: Long): String? {
    if (fileSize < MIN_APK_BYTES) {
        val asText = head.toString(Charsets.US_ASCII).lowercase()
        val looksLikeText = TEXT_OPENINGS.any { asText.startsWith(it) }
        return if (looksLikeText) {
            "下载到的是一份网页而不是安装包（$fileSize 字节）：Gitee 可能要求登录，或附件未公开"
        } else {
            "下载不完整（只有 $fileSize 字节）"
        }
    }
    val magicOk = head.size >= ZIP_LOCAL_HEADER.size &&
        ZIP_LOCAL_HEADER.indices.all { head[it] == ZIP_LOCAL_HEADER[it] }
    return if (!magicOk) "下载到的文件不是 APK 格式（缺少 zip 文件头）" else null
}

/**
 * 下载目录清理判定：保留本版本已下好的 APK（"重试安装"不该逼用户重下一遍），
 * 其余 APK 与所有半成品 `.part` 一律删掉。
 */
internal fun isStaleDownload(fileName: String, keepFileName: String?): Boolean =
    fileName.endsWith(".part", ignoreCase = true) ||
        (fileName.endsWith(".apk", ignoreCase = true) && fileName != keepFileName)

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

/** 正式包在 `dist/` 与 Gitee 附件里都用这个名字（`:app:releasePackage` 产出的即是）。 */
internal fun canonicalApkName(version: String) = "buaa-schedule-$version.apk"

/**
 * 同一个 release 允许同时挂正式包和调试包（真机自测要装 debug 签名的那份），
 * 所以选包不能依赖 assets 的返回顺序：调试签名覆盖不了正式签名，用户拿到的
 * 要么是"未安装成功"，要么被引导去卸载重装 —— 而卸载会把课表数据库一起带走。
 * 只剩调试包时返回 null：少提示一次更新是可以修的，把错签名的包发出去不是。
 */
internal fun pickApkAsset(assets: List<GiteeAsset>, version: String): GiteeAsset? {
    val candidates = assets.filter {
        isTrustedDownloadUrl(it.browserDownloadUrl) &&
            (it.name?.endsWith(".apk", ignoreCase = true) == true ||
                it.contentType == "application/vnd.android.package-archive")
    }
    val canonical = canonicalApkName(version)
    return candidates.firstOrNull { it.name.equals(canonical, ignoreCase = true) }
        ?: candidates.firstOrNull { it.name?.contains("debug", ignoreCase = true) != true }
}

internal fun GiteeRelease.toUpdateOrNull(): UpdateInfo? {
    val tag = tagName?.trim().orEmpty()
    val version = normalizeVersion(tag)
    if (version.isEmpty() || !version.first().isDigit()) return null
    if (draft == true || prerelease == true) return null
    val apk = pickApkAsset(assets.orEmpty(), version)
    return UpdateInfo(
        version = version,
        title = name?.trim().orEmpty().ifEmpty { tag },
        notes = body?.trim().orEmpty(),
        apkUrl = apk?.browserDownloadUrl,
        // html_url 实测缺席：自己按 tag 拼发布页直链，跳列表页会把用户丢在一堆旧版本里
        pageUrl = htmlUrl?.takeIf { it.isNotBlank() } ?: releasePageUrl(tag),
        apkSize = apk?.size ?: 0L,
        // published_at 也实测缺席，Gitee 只给 created_at
        publishedAt = publishedAt?.trim()?.takeIf { it.isNotEmpty() }
            ?: createdAt?.trim()?.takeIf { it.isNotEmpty() },
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

/**
 * 今天成功查过就拦下；手动检查（force）永远放行；
 * 上次是**失败**的话，过了 [FAILURE_RETRY_MILLIS] 重新开门。
 */
internal fun shouldCheckToday(
    lastCheckedDay: String?,
    lastFailureAt: Long,
    today: String,
    nowMillis: Long,
    force: Boolean,
): Boolean =
    force ||
        lastCheckedDay != today ||
        // 失败不占配额：断网时打开一次 App，不该让当天剩下的所有次启动都查不到更新。
        // lastFailureAt == 0 是"从没失败过"，不能当成"1970 年失败过一次"
        (lastFailureAt > 0L && nowMillis - lastFailureAt >= FAILURE_RETRY_MILLIS)

/** 检查失败后的重试间隔；与"当天只查一次"的正常节流取更宽松的一侧 */
internal const val FAILURE_RETRY_MILLIS = 30L * 60L * 1000L

/** 更新说明里的一行，四类足以覆盖 release 正文常见的 markdown 写法 */
sealed interface ChangelogLine {
    data class Heading(val text: String) : ChangelogLine
    data class Bullet(val text: String) : ChangelogLine
    data class Paragraph(val text: String) : ChangelogLine
    data object Blank : ChangelogLine
}

/**
 * release 正文 → 逐行结构。
 *
 * `#` 开头算标题，`- ` / `* ` 算项目符号，其余非空行是正文段落，空行保留为间距。
 * 行内的 markdown 修饰统一由 [markdownPlainText] 去掉。
 */
internal fun changelogLines(notes: String): List<ChangelogLine> {
    if (notes.isBlank()) return emptyList()
    return notes.trim().lines().mapNotNull { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> ChangelogLine.Blank
            line.startsWith("#") -> ChangelogLine.Heading(markdownPlainText(line.trimStart('#').trim()))
            line.startsWith("- ") || line.startsWith("* ") ->
                ChangelogLine.Bullet(markdownPlainText(line.drop(2).trim()))
            else -> ChangelogLine.Paragraph(markdownPlainText(line))
        }
    }
}

/** 去掉行内 markdown 修饰：链接保留文字并把地址附在括号里，粗体/斜体/行内代码只剥记号 */
internal fun markdownPlainText(source: String): String = source
    .replace(MARKDOWN_LINK) { match -> "${match.groupValues[1]} (${match.groupValues[2]})" }
    .replace(MARKDOWN_EMPHASIS) { match -> match.groupValues[1] }
    .replace("`", "")

private val MARKDOWN_LINK = Regex("""\[([^]]+)]\(([^)]+)\)""")

/**
 * `*` 系的粗体/斜体（1~3 个星号一口气包住同一段文字）。
 *
 * 只逐个 replace 掉 "**" 会把 `***文字***` 剩成 `*文字*`，所以整对匹配、只留内容。
 * `_` 系**故意**不处理：release 正文会引用 `update_last_check_day` 这类下划线标识符，
 * 按下划线配对清理会把它啃成 `update last check day`。
 */
private val MARKDOWN_EMPHASIS = Regex("""\*{1,3}([^*\s](?:[^*]*[^*\s])?)\*{1,3}""")

/**
 * 发布时间原文（Gitee 给的 ISO-8601，可能带时区、可能是 `yyyy-MM-ddTHH:mm:ssZ`）
 * → 「M月d日」；解析不了就返回 null，让弹窗那一行整行消失而不是显示一个 1970。
 */
internal fun formatReleaseDate(publishedAt: String?): String? {
    val text = publishedAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val date = runCatching {
        java.time.OffsetDateTime.parse(text).toLocalDate()
    }.getOrNull() ?: runCatching {
        java.time.LocalDateTime.parse(text.take(19)).toLocalDate()
    }.getOrNull() ?: runCatching {
        java.time.LocalDate.parse(text.take(10))
    }.getOrNull() ?: return null
    return "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}
