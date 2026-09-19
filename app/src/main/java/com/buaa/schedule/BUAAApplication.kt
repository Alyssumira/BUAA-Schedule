package com.buaa.schedule

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.work.Configuration
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.repository.ScheduleRepository
import com.buaa.schedule.widget.BackgroundSync
import com.buaa.schedule.widget.ColdStartRebuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [Configuration.Provider] 是 WorkManager 按需初始化的另一半：清单里摘掉
 * `androidx.work.WorkManagerInitializer` 之后，work-runtime 2.9.1 的
 * `WorkManagerImpl.getInstance(Context)` 在「还没初始化」这一支上**不**自我初始化，
 * 而是问 `applicationContext is Configuration.Provider`，拿不到就抛
 * IllegalStateException（反编译过字节码确认，两条分支都看着 sLock 里面）。
 * 所以这两处改动是一个整体：只摘 initializer 会直接把兜底任务变成"永远注册不上"。
 */
class BUAAApplication : Application(), Configuration.Provider {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: ScheduleRepository by lazy { ScheduleRepository(database) }

    /**
     * 进程级作用域：给"活不过调用方"的后台收尾用（例如实况服务 stopSelf 前
     * 排下的下一节课窗口）。服务/广播Receiver 不要自建常驻作用域。
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * WorkManager 按需初始化时读到的那份配置。
     *
     * 与被摘掉的 `androidx.work.WorkManagerInitializer.create()` **逐字相同**
     * （反编译看过：它就是 `WorkManager.initialize(context, new Configuration.Builder().build())`）：
     * 默认日志档、默认后台 executor、默认的反射 WorkerFactory。
     * [com.buaa.schedule.widget.WidgetFallbackWorker] 用的是 `(Context, WorkerParameters)`
     * 这个标准构造，反射工厂建得起来，所以这边不需要自定义工厂。
     * 保持等价是有意为之：这张卡要改的只有"什么时候初始化"，不是"用什么初始化"。
     *
     * ⚠️ 这个 getter 由**第一个调到 `WorkManager.getInstance(context)` 的线程**执行，
     * 而且是在 WorkManager 那把静态 `sLock` 里面执行的（见 WorkManagerImpl.getInstance
     * 的字节码：monitorenter 之后才 checkcast Configuration.Provider）。
     * 因此它不许碰 WorkManager 自己、不许读盘、不许等任何人 —— 只构造一个对象就返回。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        com.buaa.schedule.data.import.BuaaWebSession.init(this)
        // 智学北航只寄存 Context：读盘是懒的，冷启动不为此多解一次密文
        com.buaa.schedule.data.import.SpocSession.init(this)
        Personalization.load(this)
        // 通知渠道要在任何通知发出前就位（渠道重要性一旦创建只能由用户改）
        com.buaa.schedule.reminder.ReminderNotifications.ensureChannels(this)
        // 应用启动/升级（升级会清掉已注册闹钟）：重排提醒并调度零点刷新。
        // WorkManager 第一次 getInstance 会初始化它自己的数据库，属于磁盘活，
        // 因此连同"旧版周期轮询退出"和兜底任务注册一起放这里，不占 onCreate 主线程。
        //
        // T18 之后这段话从"顺便说说"变成了**这条链存在的理由之一**：清单里
        // `androidx.work.WorkManagerInitializer` 已被摘掉（按需初始化），
        // 于是"进程里第一次 `WorkManager.getInstance` 的那个线程"就是付初始化钱的人。
        // 这个块跑在 Dispatchers.IO 上，而且是 `Application.onCreate` 里排出去的，
        // 比任何广播/服务回调都早 —— 冷进程被组件广播唤醒时，钱付在这里而不是
        // 付在 `onReceive` 的主线程上。另外六个 Provider 的 onEnabled/onDisabled
        // 也已经挪进后台协程（见 WidgetCommon 的那两个 *FromReceiver 入口），
        // 所以主线程侧根本没有调到 getInstance 的路径，不需要额外的门闩。
        applicationScope.launch {
            // 这个作用域是 SupervisorJob 且没有 CoroutineExceptionHandler：块内任何
            // 未捕获异常都会落到线程的默认处理器，直接杀进程。
            // ⚠️ 逐步兜住而不是整块一个 runCatching：探测桌面组件要跨 binder 问 Launcher
            // （MIUI 上抛 DeadObjectException），整块兜时它一抛，后面的明日预告与
            // WidgetFallbackWorker 整轮不注册 —— 而 HyperOS 清掉第三方精确闹钟的机型，
            // 恰恰全靠那条兜底刷新活着。每步自己吞异常 + 留痕（同 WidgetCommon 的口径；
            // 组件那四步的逐步兜住已经挪进 BackgroundSync.runColdStartWidgetSteps 里面）。
            suspend fun step(label: String, block: suspend () -> Unit) {
                runCatching { block() }.onFailure {
                    android.util.Log.w("BUAAApplication", "后台链路初始化失败：$label", it)
                }
            }
            // 旧版本的固定周期 Widget 轮询退出，改为事件驱动 + 每日零点刷新
            step("cancelLegacyPeriodicWork") {
                BackgroundSync.cancelLegacyPeriodicWork(this@BUAAApplication)
            }
            // 冷启动自愈：勿扰记录已过恢复期限还没等到下课铃（含旧版本无期限的残留）时恢复；
            // 正在上课的那节课期限未到，不会被误恢复
            step("dndSelfCheck") {
                com.buaa.schedule.reminder.ClassProgressDnd.selfCheck(this@BUAAApplication)
            }
            // 重建整链（重排提醒 + 课堂铃兜底 → 组件那四步）：三把钥匙都说"无事可做"时才跳过。
            // 绝大多数冷启动是"某条闹钟把进程从零拉起来投递广播"，那一刻课表一个字都没变，
            // 而这套动作原先每一次冷进程启动都跑全套（审计 §2.1）。判据、按人群探测哪一头闹钟、
            // 24 小时硬上限，以及"只有整链干净跑完才记一次成功"写在 ColdStartRebuild 的类注释里。
            // 步骤顺序就是原来的顺序：提醒链 → 组件四步（刷组件 + 零点闹钟 + 明日预告 +
            // 兜底任务登记，四步共一次组件探测，口径见 BackgroundSync.runColdStartWidgetSteps）。
            step("coldStartRebuild") {
                ColdStartRebuild.run(this@BUAAApplication)
            }
        }
    }

    /**
     * 内存回收信号：交出最大的两块常驻内存。
     *
     * 隐藏会话 WebView 挂在 Chromium 堆上（几十 MB 量级），是全应用最大的单个常驻块；
     * Cookie 已经由 [com.buaa.schedule.data.import.BuaaCookieStore] 落盘，
     * 销毁后下次回前台按 Cookie 重建即可。
     *
     * 只处理 BACKGROUND / MODERATE / COMPLETE 这三档 —— 它们只会发给**已在后台**的进程。
     * RUNNING_CRITICAL 虽然名字更吓人，却是发给前台进程的，而前台正是用户可能正在
     * 抓课表的时候（[com.buaa.schedule.data.import.BuaaWebSession] 的取数就走这个
     * WebView），那时拆掉等于把这次导入打断。UI_HIDDEN 同样不动：
     * 切出去又立刻回来时，重跑一遍 SSO 页面比省下的那点内存更贵。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return
        com.buaa.schedule.data.import.BuaaWebSession.releaseForMemory()
        com.buaa.schedule.widget.WidgetDataCache.invalidate()
    }
}
