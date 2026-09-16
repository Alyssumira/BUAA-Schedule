package com.buaa.schedule.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.buaa.schedule.BuildConfig
import com.buaa.schedule.core.openExternalUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
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
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateUiState

    /**
     * 是否值得弹窗。自动检测只有"发现新版本"才出声，
     * 无更新和失败都静默（否则每天第一次打开都要被一个网络错误打断）；
     * 手动检测两者都要反馈，判据是 [manual]。
     */
    val visible: Boolean
        get() = when (this) {
            is Available, is Downloading -> true
            is UpToDate -> manual
            is Failed -> manual
            Idle, Checking -> false
        }
}

/**
 * Gitee Releases 更新检测。
 *
 * 纯逻辑（解析、版本比较、每日节流、下载域名白名单）都在 UpdateInfo.kt 里，
 * 这个对象只负责网络、偏好读写和状态广播 —— 单测跑 JVM，碰不到 Android 侧。
 */
object UpdateCheck {

    private const val PREFS_NAME = "schedule_settings"
    private const val KEY_LAST_DAY = "update_last_check_day"
    private const val KEY_LAST_AT = "update_last_check_at"
    private const val KEY_SKIPPED = "update_skipped_version"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun lastCheckAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_AT, 0L)

    /** 每天第一次打开应用查一次；force（手动检查）不受限 */
    suspend fun check(context: Context, force: Boolean): UpdateUiState {
        val store = prefs(context)
        val today = LocalDate.now().toString()
        if (!shouldCheckToday(store.getString(KEY_LAST_DAY, null), today, force)) return _state.value
        val current = _state.value
        if (current is UpdateUiState.Checking || current is UpdateUiState.Downloading) return current

        _state.value = UpdateUiState.Checking
        val outcome = runCatching { fetchReleases() }
        // 时间戳先落盘：失败也算"今天查过了"，避免每次打开应用都重复打网络
        store.edit {
            putString(KEY_LAST_DAY, today)
            putLong(KEY_LAST_AT, System.currentTimeMillis())
        }
        val skipped = if (force) null else store.getString(KEY_SKIPPED, null)
        val next = outcome.fold(
            onSuccess = { releases ->
                pickLatestUpdate(releases, BuildConfig.VERSION_NAME, skipped)
                    ?.let { UpdateUiState.Available(it, manual = force) }
                    ?: UpdateUiState.UpToDate(manual = force)
            },
            onFailure = { UpdateUiState.Failed(readable(it), manual = force) },
        )
        _state.value = next
        return next
    }

    /** 「忽略此版本」：记下版本号，之后自动检测不再提示它（手动检查仍会提示） */
    fun ignore(context: Context, info: UpdateInfo) {
        prefs(context).edit { putString(KEY_SKIPPED, info.version) }
        _state.value = UpdateUiState.Idle
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    suspend fun download(context: Context, info: UpdateInfo) {
        val url = info.apkUrl?.takeIf { isTrustedDownloadUrl(it) } ?: return
        _state.value = UpdateUiState.Downloading(info, percent = -1)
        val result = withContext(Dispatchers.IO) {
            // runCatching 的块是普通 lambda，拿不到挂起上下文，
            // 所以在这里抓住 CoroutineScope 接收者，循环里用非挂起的 ensureActive()
            val scope = this
            runCatching {
                val dir = File(context.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "buaa-schedule-${info.version}.apk")
                var connection: HttpURLConnection? = null
                try {
                    connection = open(url, readTimeoutMillis = 60_000)
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    val total = connection.contentLength.coerceAtLeast(0)
                    var written = 0L
                    connection.inputStream.use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    _state.value = UpdateUiState.Downloading(
                                        info,
                                        percent = ((written * 100) / total).toInt().coerceAtMost(100),
                                    )
                                }
                                // socket read 自己不看 Job：不主动检查的话，
                                // 点"取消"要等整个 APK 传完才停得下来
                                scope.ensureActive()
                            }
                        }
                    }
                    if (total > 0 && written < total) throw IOException("下载中断")
                    target
                } finally {
                    connection?.disconnect()
                }
            }
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

    /** 是否已被授予"安装未知应用"；未授予时先引导去系统开关，再退回浏览器下载 */
    fun install(context: Context, info: UpdateInfo, file: File) {
        if (!canRequestInstall(context)) {
            openInstallPermissionSettings(context)
            _state.value = UpdateUiState.Failed("需要允许本应用安装未知应用，开启后请重新点下载", manual = true)
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
        _state.value = UpdateUiState.Idle
    }

    fun canRequestInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    private fun openInstallPermissionSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private suspend fun fetchReleases(): List<GiteeRelease> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = open(releasesApiUrl(), readTimeoutMillis = 15_000)
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException(if (code == 404) "发布页不可访问（仓库可能仍是私有的）" else "HTTP $code")
            }
            parseReleases(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }, json)
        } finally {
            connection?.disconnect()
        }
    }

    private fun open(url: String, readTimeoutMillis: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "BUAA-Schedule/${BuildConfig.VERSION_NAME}")
        }

    private fun readable(error: Throwable): String = when {
        error is java.net.SocketTimeoutException -> "连接超时"
        error is java.net.UnknownHostException -> "无法解析服务器地址"
        else -> error.message ?: "网络异常"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
