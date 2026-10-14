package com.buaa.schedule.domain.session

/**
 * 「用户点退出登录那一刻，恢复链还在飞的那一枚 WebView 归谁拆」的**唯一一份**判据（T390①，乙档）。
 *
 * ## 纯度（硬边界）
 *
 * 零 android / androidx import：不读 `WebView`、不读时钟、不读 `Build.VERSION`、不读权限、不查库。
 * 两枚事实（手里有没有那一枚在飞的实例、它是不是会话槽里的同一枚）全部由调用点
 * （`data/import/BuaaWebSession` 的 `clear`）当参数递进来，本文件一枚都不许自己去取。
 *
 * ## 病在哪（基点 `81c111f` 的盘面，两手都在同一枚函数体里）
 *
 * `data/import/BuaaWebSession` 的 `clear()` 起手 `restoreInFlight = false`（裸 :548）再
 * `cancelRestoreGuard()`（裸 :549）。而那枚超时闸的闭包（裸 :736—:738
 * `val guard = Runnable { if (sessionWebView !== web) abandonRestore(web, "加载超时") }`）
 * 是恢复链新建的那枚实例（裸 :729 `val web = createSessionWebView(appCtx)`）**唯一一枚持有者**：
 * 闭包被 `removeCallbacks`（裸 :627）从主线程队列里摘走之后，没有任何字段还认得那一枚，
 * 而 `clear()` 紧接着读的是会话槽（裸 :550 `val web = sessionWebView`，恢复没跑完时它恒为 null）
 * ⇒ 摘树／`stopLoading`／`destroy` 那一整块（裸 :553—:562）对在飞的那一枚一手都不做。
 *
 * 两档后果：
 *
 * 1. **没人拆**：那一枚已经挂进隐藏宿主（裸 :812）且已经发出加载（裸 :813），而退出登录只是把
 *    **宿主**从窗口上摘走（裸 :563）。本文件头部（裸 :41—:46）自己写着：只是把 WebView 从视图树
 *    detach 既不回收它、也停不下它的回调；而那枚实例脚下的渲染堆按裸 :633—:635 的口径是
 *    「整个应用里最大的一块单体驻留内存」。
 * 2. **反咬一口**：它的 `onPageFinished`（裸 :741 那个匿名 `WebViewClient`）仍可能在几秒后经裸 :750
 *    那一手上缴。那一刻 `domain/session/SessionRetainGate` 的 `decide`（裸 :164）只收到五枚事实，
 *    读到的是「教务 origin ＋ 会话槽是空的 ＋ 旗不在飞」⇒ 给 [SessionRetainVerdict.Accepted]，
 *    于是一枚**一出生就带着退出之前那份 Cookie** 的实例成了新会话。
 *
 * ## 修法（第 ⓪ 问裁的是乙：不扩 `decide` 的形参，只给那枚实例补一个主人）
 *
 * `data/import/BuaaWebSession` 多一枚类体属性握着在飞的那一枚，`clear()` 在**拆闸之前**先把它
 * 交给自己拆。本内核答的是那一手里唯一的一问：此刻动不动手、动的是哪一枚 —— 三档，
 * [NothingHeld]（空转）、[LeaveToSessionTeardown]（那一枚就是会话槽本身，会话那一手会拆它，
 * 再拆一遍就是 double destroy）、[ReapNow]（除了本次退出登录，再没有谁会把它摘树＋拆掉）。
 *
 * `SessionRetainGate.decide` 的形参表**一字未动**，`SessionRetainVerdict` 没加档，七枚 `retain`
 * 调用形状没动 —— 甲案（给内核加一枚「本次上缴属不属于当前世代」）被第 ⓪ 问否决的理由摆在那里：
 * 凭证那一手（`putBoolean(PREF_RETAINED, true)` 与 `persistCookies()`，裸 :370—:374）长在两档之外，
 * 新增一枚拒收档照样落到它手上，而世代号在六枚登录页调用点上是恒真的一维。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 调用点要做的是**两件不同的事**（要不要拆在飞那一枚、会话槽那一手要不要跟着拆同一枚），
 * 折成布尔就把「手里没有在飞的」与「在飞的就是会话自己」念成同一句话 —— 而这两句在
 * 「恢复早已收场」与「恢复跑成了会话」两条真路上各占一边，正是本卡要分开的那一维。
 *
 * ## 可达性口径（别把后果说过头）
 *
 * 在飞窗口按盘面现值是 30 秒（`data/import/BuaaWebSession` 裸 :178 的
 * `private const val RESTORE_TIMEOUT_MS = 30_000L`），而 `restore()` 由 `MainActivity` 的 `onStart`
 * 递进来（裸 :283 → 裸 :305）⇒ 每次回前台都能重新开一扇这样的窗。退出登录那颗按钮**一格 enabled
 * 都没有**（`ui/importing/ImportScreen` 裸 :722—:724，而同组上一手裸 :697 那枚刷新按钮写着
 * `enabled = hasSession`）。`clear()` 与 `onPageFinished` 都跑在主线程（裸 :180 的 `mainHandler`
 * 与创建那枚 WebView 的那条线程），所以这一格**不是数据竞争而是消息顺序**：两条消息谁先入队都行。
 * 但「用户真在那 30 秒里按了退出没有」「`destroy()` 之后 Chromium 还投不投回调」全部没有 main 侧
 * 证据：本卡零 adb，运行期分档归装机，不在这里主张。
 */
enum class InFlightRelease {

    /** 手里没有在飞的那一枚（恢复没在跑，或早已收场）：空转，一手都不许做 */
    NothingHeld,

    /** 在飞的那一枚就是会话槽里的那一枚：会话那一手会拆它，本档再拆就是同一枚拆第二遍 */
    LeaveToSessionTeardown,

    /** 闸正由本次退出登录拆掉，而在飞的那一枚另有其枚：摘树＋`stopLoading`＋`destroy` 只能在这里做 */
    ReapNow,
}

/**
 * 把两枚已经算好的事实折成一档结论。纯函数：同样的输入永远给同样的档位，不读任何设备面。
 *
 * **顺序即语义**：先问「手里有没有」，再问「是不是同一枚」。
 * `inFlightIsSession` 在 `inFlightHeld == false` 时是无意义的一维（两枚 `null` 比出来的是 `true`），
 * 所以那一问必须排在后面 —— 否则「冷启动即退出」那一档会被读成「同一枚，交给会话那一手」，
 * 而那一刻会话槽里也是空的，结果就是本卡要修的「没人拆」原样留在盘上。
 */
object RestoreInFlightReleaseGate {

    fun decide(
        inFlightHeld: Boolean,
        inFlightIsSession: Boolean,
    ): InFlightRelease = when {
        !inFlightHeld -> InFlightRelease.NothingHeld
        inFlightIsSession -> InFlightRelease.LeaveToSessionTeardown
        else -> InFlightRelease.ReapNow
    }
}
