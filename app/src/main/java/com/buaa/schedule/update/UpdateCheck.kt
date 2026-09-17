package com.buaa.schedule.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.buaa.schedule.BuildConfig
import com.buaa.schedule.core.FirstRun
import com.buaa.schedule.core.openExternalUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.CancellationException

/** 更新流程的界面状态；manual = 由用户在设置里手动触发，失败/无更新也要出声 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val info: UpdateInfo, val manual: Boolean) : UpdateUiState
    data class UpToDate(val manual: Boolean) : UpdateUiState
    data class Failed(val message: String, val manual: Boolean) : UpdateUiState

    /** [totalBytes] 取自下载响应的 Content-Length：接口不给附件体积，只能下起来才知道 */
    data class Downloading(val info: UpdateInfo, val percent: Int, val totalBytes: Long = 0L) : UpdateUiState

    /** 下载已完成并通过校验，只差系统放行"安装未知应用" */
    data class NeedsInstallPermission(val info: UpdateInfo) : UpdateUiState

    /** 包本身没问题，但装在机器上必然被拒（签名不同）——提前说清代价和退路 */
    data class InstallBlocked(val info: UpdateInfo, val reason: String) : UpdateUiState

    /**
     * 是否值得弹窗。自动检测只有"发现新版本"才出声，
     * 无更新和失败都静默（否则每天第一次打开都要被一个网络错误打断）；
     * 手动检测两者都要反馈，判据是 [manual]。
     * 已经下好的包（[NeedsInstallPermission] / [InstallBlocked]）无论何时都要弹窗：
     * 那是用户付了流量换来的结果，静默掉等于白下。
     */
    val visible: Boolean
        get() = when (this) {
            is Available, is Downloading, is NeedsInstallPermission, is InstallBlocked -> true
            is UpToDate -> manual
            is Failed -> manual
            Idle, Checking -> false
        }
}

/**
 * Gitee Releases 更新检测。
 *
 * 纯逻辑（解析、版本比较、每日节流、下载域名白名单、安装包格式判定）都在 UpdateInfo.kt 里，
 * 这个对象只负责网络、磁盘、偏好读写和状态广播 —— 单测跑 JVM，碰不到 Android 侧。
 *
 * 对端行为的实测结论（2026-09-16，公开仓库、无 token）：
 * 附件直链要经**两跳 302**（`releases/download/…` → `attach_files/…/download` →
 * `foruda.gitee.com/attach_file/…?token=…`）才拿到 `application/zip`；CDN 域名是
 * 拼不出白名单的；Range 头会被忽略（**不支持断点续传**）；自定义 UA 不受影响。
 */
object UpdateCheck {

    private const val PREFS_NAME = "schedule_settings"
    private const val KEY_LAST_DAY = "update_last_check_day"
    private const val KEY_LAST_AT = "update_last_check_at"
    private const val KEY_SKIPPED = "update_skipped_version"
    private const val KEY_FAILURE_AT = "update_last_failure_at"
    private const val KEY_PENDING = "update_pending_version"

    private const val MAX_REDIRECTS = 5
    private const val APK_HEAD_BYTES = 8

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /**
     * 下载挂在对象级 scope 上，而不是调用方的 composition scope：
     * Activity 重建（旋转、后台被回收）会取消 composition scope 的协程，
     * 于是 APK 下载到 80% 静默夭折，用户只看到弹窗自己消失了。
     */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null

    /** 已过校验、正等系统放行安装的包：留在内存里，用户开完权限回来不必重下 */
    private var pendingInstall: Pair<UpdateInfo, File>? = null

    fun lastCheckAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_AT, 0L)

    /**
     * 有更新待处理：检查发现新版本时置位，用户真的「忽略此版本」时才清除。
     *
     * 弹窗被"稍后"关掉不等于用户知道了——设置页那个红点是唯一的回访线索。
     * 每次读取都重新和已装版本比一次，所以装上之后红点自己就消失了，不需要谁去清。
     * 反过来讲：下载/安装交接期间**不能**提前清，否则一旦装失败，用户连"这里有个更新没装完"
     * 的提示都看不到了。
     */
    fun pendingUpdateVersion(context: Context): String? {
        val version = prefs(context).getString(KEY_PENDING, null) ?: return null
        return version.takeIf { compareVersions(it, BuildConfig.VERSION_NAME) > 0 }
    }

    /** 每天第一次打开应用查一次；force（手动检查）不受限 */
    suspend fun check(context: Context, force: Boolean): UpdateUiState {
        // 全应用只有这里出网，所以隐私门放在这一层而不是每个调用方：
        // 引导页、设置页、冷启动三条路径共用同一个闸口，漏不掉。
        if (!FirstRun.privacyAccepted(context)) return _state.value
        val store = prefs(context)
        val today = LocalDate.now().toString()
        val now = System.currentTimeMillis()
        if (!shouldCheckToday(store.getString(KEY_LAST_DAY, null), store.getLong(KEY_FAILURE_AT, 0L), today, now, force)) {
            return _state.value
        }
        val current = _state.value
        // 正在下载、以及"包已下好等用户处理"的两种状态都不该被一次后台检查覆盖：
        // 后者一旦被改写成 UpToDate，用户手里的安装包就再没有入口了。
        if (current is UpdateUiState.Checking || current is UpdateUiState.Downloading ||
            current is UpdateUiState.NeedsInstallPermission || current is UpdateUiState.InstallBlocked
        ) return current

        _state.value = UpdateUiState.Checking
        val outcome = runCatching { fetchReleases() }
        val skipped = if (force) null else store.getString(KEY_SKIPPED, null)
        val next = outcome.fold(
            onSuccess = { releases ->
                pickLatestUpdate(releases, BuildConfig.VERSION_NAME, skipped)
                    ?.let { UpdateUiState.Available(it, manual = force) }
                    ?: UpdateUiState.UpToDate(manual = force)
            },
            onFailure = { UpdateUiState.Failed(readable(it), manual = force) },
        )
        // "今天查过了"只在**成功**时落下；失败改记时间戳，隔 FAILURE_RETRY_MILLIS 再试。
        // 以前失败也算查过：断网时打开一次应用，当天剩下的每次启动都再也查不到更新。
        store.edit {
            putLong(KEY_LAST_AT, now)
            if (next is UpdateUiState.Failed) {
                putLong(KEY_FAILURE_AT, now)
            } else {
                putString(KEY_LAST_DAY, today)
                remove(KEY_FAILURE_AT)
            }
            if (next is UpdateUiState.Available) putString(KEY_PENDING, next.info.version)
        }
        _state.value = next
        return next
    }

    /** 「忽略此版本」：记下版本号，之后自动检测不再提示它（手动检查仍会提示） */
    fun ignore(context: Context, info: UpdateInfo) {
        prefs(context).edit {
            putString(KEY_SKIPPED, info.version)
            remove(KEY_PENDING)
        }
        pendingInstall = null
        _state.value = UpdateUiState.Idle
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    /** 用户点「立即下载」。重复点击不会起第二个下载。 */
    fun startDownload(context: Context, info: UpdateInfo) {
        if (downloadJob?.isActive == true) return
        downloadJob = downloadScope.launch { download(context, info) }
    }

    /** 「我已开好权限，重试安装」：包还在盘上就只重跑交接，不再走一次网络 */
    fun retryInstall(context: Context) {
        val (info, file) = pendingInstall ?: run {
            _state.value = UpdateUiState.Failed("安装包已失效，请重新下载", manual = true)
            return
        }
        if (downloadJob?.isActive == true) return
        downloadJob = downloadScope.launch {
            val usable = withContext(Dispatchers.IO) { file.takeIf { it.isApkOnDisk() } }
            if (usable == null) {
                // 被系统清缓存清掉了：退回"发现新版本"弹窗，让「立即下载」重新可用
                _state.value = UpdateUiState.Available(info, manual = true)
            } else {
                install(context, info, usable)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateUiState.Idle
    }

    private suspend fun download(context: Context, info: UpdateInfo) {
        val url = info.apkUrl?.takeIf { isTrustedDownloadUrl(it) }
        if (url == null) {
            // 没有可信直链不算失败：把用户送到发布页，他自己能找到包
            openExternalUrl(context, info.pageUrl)
            _state.value = UpdateUiState.Failed("这个版本没有可直接下载的可信链接，已转浏览器打开发布页", manual = true)
            return
        }
        _state.value = UpdateUiState.Downloading(info, percent = -1)
        val result = withContext(Dispatchers.IO) {
            // runCatching 的块是普通 lambda，拿不到挂起上下文，
            // 所以在这里抓住 CoroutineScope 接收者，循环里用非挂起的 ensureActive()
            val scope = this
            runCatching { fetchApk(context, info, url, scope) }
        }
        result.fold(
            onSuccess = { install(context, info, it) },
            onFailure = { error ->
                // runCatching 连协程取消也一起吞了：用户点取消不是"下载失败"，不该弹窗
                if (error is CancellationException) throw error
                _state.value = UpdateUiState.Failed(readable(error), manual = true)
            },
        )
    }

    /**
     * 取回并**验明**这个版本的安装包，返回可直接交给安装器的文件。
     *
     * 写盘走 `.part` 再改名：下载中途被杀进程/断网留下的半截文件，
     * 绝不能以下一次"看起来已经下好了"的身份被复用。
     */
    private fun fetchApk(context: Context, info: UpdateInfo, url: String, scope: CoroutineScope): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "buaa-schedule-${info.version}.apk")
        if (target.isApkOnDisk()) return target
        dir.listFiles()?.forEach {
            if (isStaleDownload(it.name, target.name)) it.delete()
        }
        val part = File(dir, "${target.name}.part")
        try {
            val connection = connectForDownload(url)
            var written = 0L
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException(downloadHttpMessage(code))
                // 被登录墙/防盗链挡下时服务端给的是 200 + 一段 HTML。
                // 这里先按 Content-Type 拦一道，报出来的原因比"不是 APK"具体得多。
                val type = connection.contentType.orEmpty()
                if (type.contains("text/html", true) || type.contains("application/json", true)) {
                    throw IOException("服务端返回的是页面而不是安装包（Content-Type: $type）")
                }
                val total = connection.contentLengthLong.coerceAtLeast(0L)
                // -2 而不是 -1：-1 是"已拿到链接、还没开始收字节"的那个状态值
                var lastPercent = -2
                connection.inputStream.use { input ->
                    part.outputStream().buffered(64 * 1024).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                // 进度按**整数百分比**去重再发：这个循环按 32KB 一块跑，
                                // 一个 APK 下来能产生上千次赋值。StateFlow 虽然挡得住
                                // 结构相等的值，但每次仍要新建实例再比较，而且百分比
                                // 没动的这些通知对 UI 毫无意义（P2：与下载块数解耦）。
                                val percent = ((written * 100) / total).toInt().coerceAtMost(100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    _state.value = UpdateUiState.Downloading(
                                        info,
                                        percent = percent,
                                        totalBytes = total,
                                    )
                                }
                            }
                            // socket read 自己不看 Job：不主动检查的话，
                            // 点"取消"要等整个 APK 传完才停得下来
                            scope.ensureActive()
                        }
                    }
                }
                if (total > 0 && written < total) throw IOException("下载中断（$written/$total 字节）")
            } finally {
                connection.disconnect()
            }
            // 真正的验收看字节：zip 文件头 + 体积下限。CDN 的 Content-Type 说什么都没用。
            apkIntegrityProblem(readHead(part, APK_HEAD_BYTES), part.length())?.let { throw IOException(it) }
            if (!part.renameTo(target)) {
                part.inputStream().use { src -> target.outputStream().use(src::copyTo) }
                part.delete()
            }
            return target
        } finally {
            // 失败路径上别把 HTML/半截包留在盘上冒充安装包
            if (!target.isFile) part.delete()
        }
    }

    /**
     * 逐跳跟随下载重定向。
     *
     * 实测 Gitee 附件直链要两跳 302 才落到带 `token` 的 CDN。继续用
     * `instanceFollowRedirects` 的话，跨协议降级不报错、出错时也说不清停在哪一跳，
     * 所以这里自己走：只允许 https，最多 [MAX_REDIRECTS] 跳，失败信息带上最后一跳地址。
     */
    private fun connectForDownload(url: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = open(current, readTimeoutMillis = 60_000, accept = "*/*", followRedirects = false)
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            val next = runCatching {
                location?.takeIf { it.isNotBlank() }?.let { URI(current).resolve(it).toString() }
            }.getOrNull()
            if (next == null) throw IOException("下载被重定向但没有给出目标地址")
            if (!isHttpsUrl(next)) throw IOException("下载被重定向到非加密地址，已中止")
            current = next
        }
        throw IOException("下载重定向次数过多（最后停在 $current）")
    }

    /** 是否已被授予"安装未知应用"；未授予时给出 [UpdateUiState.NeedsInstallPermission] 让用户去开 */
    private suspend fun install(context: Context, info: UpdateInfo, file: File) {
        if (!file.isFile) {
            _state.value = UpdateUiState.Failed("安装包已丢失，请重新下载", manual = true)
            return
        }
        // 签名不同的包会被安装器拒成一句"未安装"，而用户真正的出路是先卸载再装——
        // 卸载会清空课表。这个代价必须提前讲，而不是等系统弹窗把用户丢在那儿。
        signatureMismatch(context, file)?.let { reason ->
            pendingInstall = info to file
            _state.value = UpdateUiState.InstallBlocked(info, reason)
            return
        }
        if (!canRequestInstall(context)) {
            pendingInstall = info to file
            _state.value = UpdateUiState.NeedsInstallPermission(info)
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse {
            _state.value = UpdateUiState.Failed("安装包路径不可用：${it.message}", manual = true)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (runCatching { context.startActivity(intent) }.isFailure) {
            openExternalUrl(context, info.pageUrl)
            _state.value = UpdateUiState.Failed("系统安装器不可用，已转浏览器打开发布页", manual = true)
            return
        }
        pendingInstall = null
        _state.value = UpdateUiState.Idle
    }

    fun canRequestInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * 跳到系统「安装未知应用」授权页（本应用条目）。
     *
     * 带 package 的写法在部分厂商 ROM 上没人接（HyperOS 把它挪进了
     * 「设置 → 隐私保护 → 特殊应用权限」，MIUI 老版本只有裸 action），
     * 所以三档依次试：本应用条目 → 总开关页 → 应用信息页。
     * 以前只试第一档、异常还整个吞掉，用户点「去开启」就会看到"什么也没发生"。
     */
    fun openInstallPermissionSettings(context: Context) {
        val packageUri = "package:${context.packageName}".toUri()
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    /**
     * 新包与本机已装包签名不兼容时返回给用户看的理由。
     *
     * 任何一侧读不到证书都返回 null 放行：预检的意义是"把能预见的失败讲清楚"，
     * 不该因为自己解析不出来而拦住一次正常更新。
     */
    private suspend fun signatureMismatch(context: Context, file: File): String? {
        val installed = withContext(Dispatchers.IO) { installedSigners(context) } ?: return null
        val incoming = withContext(Dispatchers.IO) { archiveSigners(context, file) } ?: return null
        val same = installed.any { local -> incoming.any { it.contentEquals(local) } }
        if (same) return null
        return "这个安装包与本机已装版本用的不是同一个签名密钥，系统会直接拒绝安装。" +
            "只能先卸载再装，而卸载会清空课表——请先去「设置 → 数据与同步 → 导出备份」存一份，" +
            "卸载装好后再「导入备份」还原。"
    }

    @Suppress("DEPRECATION")
    private fun installedSigners(context: Context): List<ByteArray>? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
                ?.map { it.toByteArray() }
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archiveSigners(context: Context, file: File): List<ByteArray>? = runCatching {
        val path = file.absolutePath
        val atLeastP = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val parsed: PackageInfo? = context.packageManager.getPackageArchiveInfo(
            path,
            if (atLeastP) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        )
        if (atLeastP) parsed?.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        else parsed?.signatures?.map { it.toByteArray() }
    }.getOrNull()

    private suspend fun fetchReleases(): List<GiteeRelease> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = open(releasesApiUrl(), readTimeoutMillis = 15_000, accept = "application/json")
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException(if (code == 404) "发布页不可访问（仓库可能仍是私有的）" else "HTTP $code")
            }
            parseReleases(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }, json)
        } finally {
            connection?.disconnect()
        }
    }

    private fun open(
        url: String,
        readTimeoutMillis: Int,
        accept: String,
        followRedirects: Boolean = true,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = followRedirects
            useCaches = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "BUAA-Schedule/${BuildConfig.VERSION_NAME}")
        }

    private fun downloadHttpMessage(code: Int): String = when (code) {
        403 -> "Gitee 拒绝了这个附件（403，可能要登录或附件未公开）"
        404 -> "Gitee 上找不到这个安装包（404，附件可能被删了）"
        else -> "HTTP $code"
    }

    /** 文件头几个字节，读不到就返回空数组（交给 [apkIntegrityProblem] 判成非法） */
    private fun readHead(file: File, count: Int): ByteArray = runCatching {
        val buffer = ByteArray(count)
        file.inputStream().use { input ->
            var read = 0
            while (read < count) {
                val n = input.read(buffer, read, count - read)
                if (n <= 0) break
                read += n
            }
        }
        buffer
    }.getOrDefault(ByteArray(0))

    /** 盘上这个文件是不是一个完整到可以交给安装器的 APK（只验文件头与体积） */
    private fun File.isApkOnDisk(): Boolean =
        isFile && apkIntegrityProblem(readHead(this, APK_HEAD_BYTES), length()) == null

    private fun readable(error: Throwable): String = when {
        error is java.net.SocketTimeoutException -> "连接超时"
        error is java.net.UnknownHostException -> "无法解析服务器地址"
        else -> error.message ?: "网络异常"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
