package com.buaa.schedule.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.buaa.schedule.BuildConfig
import com.buaa.schedule.core.FirstRun
import com.buaa.schedule.core.openExternalUrl
import com.buaa.schedule.domain.update.UpdateCheckGate
import com.buaa.schedule.domain.update.UpdateCheckTrigger
import com.buaa.schedule.domain.update.UpdateCheckVerdict
import com.buaa.schedule.domain.update.UpdateFetchSettlement
import com.buaa.schedule.domain.update.UpdateLastOutcome
import com.buaa.schedule.domain.update.UpdateRestingTier
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

    /**
     * 这一手没有拿到可安装的更新，但它有自己那一句话要说。
     *
     * 两档走到这里：被隐私闸门挡下（**根本没出网**）、查到的最新版正是用户忽略过的那一版。
     * 改前这两档都直接把旧状态原样交回，于是「检查更新」对未同意隐私的用户是彻底的 no-op，
     * 而「忽略此版本」写进偏好后再没有任何地方回读它（T261 的第 1 与第 3 处静默）。
     * 话由 `domain/update/UpdateCheckGate.kt` 一处写完，弹窗正文与设置页那一行念同一句。
     */
    data class Blocked(val title: String, val message: String, val manual: Boolean) : UpdateUiState

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
     * [Blocked] 与 [Failed] / [UpToDate] 同一口径：**手动那一手必须出声**（T261 第 1 处静默）。
     */
    val visible: Boolean
        get() = when (this) {
            is Available, is Downloading, is NeedsInstallPermission, is InstallBlocked -> true
            is UpToDate -> manual
            is Failed -> manual
            is Blocked -> manual
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

    /**
     * 上一手的**结果档**（T261 第 2 处静默）。改前偏好里只有两枚时间戳，
     * 进程一死，失败与成功在设置页那一行念的是同一句话——时间戳说不出"那一次成没成"。
     * 落档之后同一枚 [KEY_LAST_AT] 才不再歧义：成功档下的它就是成功时刻。
     */
    private const val KEY_LAST_OUTCOME = "update_last_outcome"
    private const val OUTCOME_SUCCEEDED = "succeeded"
    private const val OUTCOME_FAILED = "failed"

    private const val TAG = "UpdateCheck"

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
        val store = prefs(context)
        val today = LocalDate.now().toString()
        val now = System.currentTimeMillis()
        val trigger = if (force) UpdateCheckTrigger.Manual else UpdateCheckTrigger.Automatic
        // 全应用只有这里出网，所以隐私闸放在这一层而不是每个调用方：
        // 引导页、设置页、冷启动三条路径共用同一个闸口，漏不掉。
        // ⚠️ 隐私是**递给判据的一枚输入**，不是一道 return（T261 第 1 处静默）：改前那一手
        //    「未同意就把当前状态原样交回」排在 force 判据之前，于是设置页那颗「检查更新」
        //    对没同意过隐私的用户是彻底的 no-op —— 不弹窗、副标题不变、一条日志都不留，
        //    而同一个文件顶部正承诺「失败/无更新也要出声」。
        //    改前逐字留此做取证：if (!FirstRun.privacyAccepted(context)) return _state.value
        //    出不出网由判据定，说不说话由档位定，两者不再互相顶掉。
        val admission = UpdateCheckGate.admit(
            privacyAccepted = FirstRun.privacyAccepted(context),
            trigger = trigger,
            throttleAllowsCheck = shouldCheckToday(
                store.getString(KEY_LAST_DAY, null),
                store.getLong(KEY_FAILURE_AT, 0L),
                today,
                now,
                force,
            ),
            lastOutcome = readOutcome(store),
        )
        if (admission !is UpdateCheckVerdict.RunCheck) {
            val next = when (admission) {
                is UpdateCheckVerdict.BlockedByPrivacy -> {
                    val copy = admission.spoken
                    if (force) {
                        Log.w(TAG, "检查更新被隐私闸门挡下（用户手动按下，已出声）：" + copy.message)
                    } else {
                        Log.i(TAG, "检查更新被隐私闸门挡下（自动触发，按合规要求不弹窗、只留痕）")
                    }
                    UpdateUiState.Blocked(copy.title, copy.message, manual = force)
                }

                is UpdateCheckVerdict.HeldByThrottle -> {
                    Log.i(TAG, "跳过本次检查（每日节流）：上一手结果档=" + admission.lastOutcome)
                    _state.value
                }

                // 那四档不会从 admit 里出来；留着是为了 when 穷尽，原样交回而不伪装成任何一种结果
                is UpdateCheckVerdict.RunCheck, is UpdateCheckVerdict.NewVersionFound,
                is UpdateCheckVerdict.SuppressedByIgnore, is UpdateCheckVerdict.NothingNewer,
                is UpdateCheckVerdict.CheckFailed,
                -> _state.value
            }
            _state.value = next
            return next
        }

        val current = _state.value
        // 正在下载、以及"包已下好等用户处理"的两种状态都不该被一次后台检查覆盖：
        // 后者一旦被改写成 UpToDate，用户手里的安装包就再没有入口了。
        if (current is UpdateUiState.Checking || current is UpdateUiState.Downloading ||
            current is UpdateUiState.NeedsInstallPermission || current is UpdateUiState.InstallBlocked
        ) return current

        _state.value = UpdateUiState.Checking
        // 包住 fetchReleases() 的这一手必须**只吃非取消的失败**。标准库的 runCatching 兜的是 Throwable，
        // 连协程取消也一起吞（同文件 download() 里那一手与 ScheduleViewModel 的 suspendCatching 都点破了
        // 同一形状）：转屏把 UpdateDialogHost 连同它的 LaunchedEffect(Unit) 摘掉、离开设置页把
        // settingsScope 撤销，都落在那枚 15 秒读超时给的暴露窗口里。吞下来的取消在原盘面上
        // 同时造出两句假话——
        //   屏上弹「检查更新失败」（present 看见非 null 的 failureReason 就交回 CheckFailed，
        //   而 readable 的 else 那一档对任何异常都交出非 null），
        //   偏好里落一枚失败档 + 一枚从没发生过的检查时刻 + 一枚失败时间戳（下面那段一次都不该写）。
        // 归档与三格结论（落不落盘 / 内存摆哪一档 / 要不要复投）一律由
        // domain/update/UpdateFetchSettlement.kt 定，调用点不自己分第二把尺。
        val outcome = runCatching { fetchReleases() }
        val error = outcome.exceptionOrNull()
        val settlement = UpdateFetchSettlement.of(error) { readable(it) }
        if (settlement.restingTier == UpdateRestingTier.RestInIdle) {
            // 取消那一手先把「正在检查」收回 Idle，不许留在内存里：Checking 挂在那儿，
            // 之后每一次检查（自动与手动）都会在上面那道「正在检查/正在下载就不覆盖」的同状态闸门上
            // 原样返回 ⇒ 设置页那颗「检查更新」成了彻底的静默 no-op —— 正是 T261 第 1 处静默换了件衣服。
            // 收回 Idle 之后设置页那一行念的是偏好里上一手真实的结果档，而这一次它一个字都没写。
            _state.value = UpdateUiState.Idle
            // CancellationException 复投那一手：内核说这一档要交回上层，且手上确实是取消
            //（同文件 download() 里那一手同一形状）。取消是控制流不是错误，调用方的协程
            // 必须以「被取消」收场，不能以「正常返回一次结果」收场。
            if (settlement.rethrowsCancellation && error is CancellationException) throw error
        }
        val newestInfo = outcome.getOrNull()?.let { pickLatestUpdate(it, BuildConfig.VERSION_NAME, null) }
        val verdict = UpdateCheckGate.present(
            trigger = trigger,
            // 「忽略此版本」从此是**判据的一枚输入**，不再是选版本时那一层静默过滤
            //（T261 第 3 处静默）：被忽略的那一版有自己的档位与那句话，撤销落在设置页「版本」那一组。
            // 顺带改掉的是"忽略了最新版就被 nag 上一个更旧的版本"那一形——
            // 交回旧号会让用户以为新的已经装上了，宁可说一句"这一版被你忽略了"。
            ignoredVersion = store.getString(KEY_SKIPPED, null),
            newestAboveInstalled = newestInfo?.version,
            // 措辞同源、归档也同源：这一枚只可能由真失败那一档交出来，取消早在上面那一手复投出去了。
            // 换掉它之前先想清楚：readable 对任何异常都交出非 null，所以「failureReason 有没有非 null」
            // 就是 present 那把尺上唯一认得出失败的一格——它再也不许吞进取消。
            failureReason = settlement.reason,
        )
        val next = when (verdict) {
            is UpdateCheckVerdict.NewVersionFound -> UpdateUiState.Available(
                requireNotNull(newestInfo) { "判据交出了版本号，却没有对应的 release" },
                manual = force,
            )

            is UpdateCheckVerdict.SuppressedByIgnore -> {
                Log.i(TAG, "最新版 v${verdict.version} 已被用户忽略：${verdict.spoken.message}")
                UpdateUiState.Blocked(verdict.spoken.title, verdict.spoken.message, manual = force)
            }

            is UpdateCheckVerdict.NothingNewer -> UpdateUiState.UpToDate(manual = force)

            is UpdateCheckVerdict.CheckFailed -> {
                Log.w(TAG, "检查更新失败（manual=$force）：${verdict.reason}")
                UpdateUiState.Failed(verdict.reason, manual = force)
            }

            // 准入那一手的三档不会从 present 里出来；同上，原样交回
            is UpdateCheckVerdict.RunCheck, is UpdateCheckVerdict.BlockedByPrivacy,
            is UpdateCheckVerdict.HeldByThrottle,
            -> _state.value
        }
        // "今天查过了"只在**成功**时落下；失败改记时间戳，隔 FAILURE_RETRY_MILLIS 再试。
        // 以前失败也算查过：断网时打开一次应用，当天剩下的每次启动都再也查不到更新。
        // 两档都要落下**结果档**：这是设置页那一行在进程死后唯一还能读出"那一次成没成"的东西。
        // ⚠️ 还有第三档：**取消**一个字都不落（settlement.persistsRun 为假）。这一手既没拿到答复也没
        //    失败过，落下去的三枚键（检查时刻 / 失败时刻 / 失败档）句句都是假话，还会把上一手真实
        //    的检查时刻盖掉——盖掉的这一格杀进程也回不来，比弹窗狠。上面那一手已经把取消复投出去，
        //    所以今天这一道闸对取消还读不到第二次；留着它是因为「落不落盘」这一格归内核管：
        //    写侧照内核的结论办事，而不是照「我猜这一手走不到这里」办事。
        if (settlement.persistsRun) store.edit {
            putLong(KEY_LAST_AT, now)
            if (next is UpdateUiState.Failed) {
                putLong(KEY_FAILURE_AT, now)
                putString(KEY_LAST_OUTCOME, OUTCOME_FAILED)
            } else {
                putString(KEY_LAST_DAY, today)
                remove(KEY_FAILURE_AT)
                putString(KEY_LAST_OUTCOME, OUTCOME_SUCCEEDED)
            }
            if (next is UpdateUiState.Available) putString(KEY_PENDING, next.info.version)
        }
        _state.value = next
        return next
    }

    /**
     * 上一手的结果档（成功 / 失败 / 从没查过）。
     *
     * 设置页那一行在状态还是 Idle（进程刚起来）时读它 —— 改前那里只有一枚时间戳，
     * 失败与成功在屏上同形（T261 第 2 处静默）。
     */
    fun lastOutcome(context: Context): UpdateLastOutcome = readOutcome(prefs(context))

    /**
     * 用户「忽略此版本」留下的版本号，只在它确实比已装版本新时交回
     * （与 [pendingUpdateVersion] 同一把尺：装上之后这一格自己就消失，不需要谁去清）。
     */
    fun ignoredVersion(context: Context): String? {
        val version = prefs(context).getString(KEY_SKIPPED, null) ?: return null
        return version.takeIf { compareVersions(it, BuildConfig.VERSION_NAME) > 0 }
    }

    /**
     * 撤销「忽略此版本」：把偏好里那一枚记录清掉，返回被撤销的版本号（本来就没有就返回 null）。
     *
     * 改前 [KEY_SKIPPED] 全仓只有定义 / 读 / 写三处命中，零清除路径、UI 零回读 ——
     * 点一次「忽略此版本」就再也没有任何办法让它重新提示（T261 第 3 处静默）。
     */
    fun restoreIgnored(context: Context): String? {
        val store = prefs(context)
        val version = store.getString(KEY_SKIPPED, null)
        if (version == null) {
            Log.i(TAG, "撤销「忽略此版本」：本来就没有忽略记录（重复点按或已随装好而失效）")
            return null
        }
        store.edit { remove(KEY_SKIPPED) }
        Log.i(TAG, "已撤销「忽略此版本」：v$version 会随下一次检查重新提示")
        return version
    }

    private fun readOutcome(store: SharedPreferences): UpdateLastOutcome =
        when (store.getString(KEY_LAST_OUTCOME, null)) {
            OUTCOME_SUCCEEDED -> UpdateLastOutcome.Succeeded
            OUTCOME_FAILED -> UpdateLastOutcome.Failed
            else -> UpdateLastOutcome.Never
        }

    /**
     * 「忽略此版本」：记下版本号，之后自动检测不再提示它；手动检查那一手念的是
     * 「这一版被你忽略了、到「设置 → 版本」里可以撤销」，不再把它当成新版本弹一遍。
     *
     * 这一手必须有对偶（[restoreIgnored]）：改前偏好里写进去就再没有清除路径、界面也从不回读，
     * 点一次就是一扇单向门（T261 第 3 处静默）。
     */
    fun ignore(context: Context, info: UpdateInfo) {
        prefs(context).edit {
            putString(KEY_SKIPPED, info.version)
            remove(KEY_PENDING)
        }
        pendingInstall = null
        _state.value = UpdateUiState.Idle
        Log.i(TAG, "用户忽略 v${info.version}：不再提示它，撤销落点是设置页「版本」那一组的「恢复更新提醒」")
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
