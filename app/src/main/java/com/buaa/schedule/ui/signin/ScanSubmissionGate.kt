package com.buaa.schedule.ui.signin

/**
 * 相机帧的提交闸门（[QrCodeAnalyzer] 里那道锁）的判据。
 *
 * 零 android import：这是纯 JVM 函数，墙钟与"上一次放行了什么"都由调用点当参数传进来
 * （仓库口径，同 `WidgetDataSynchronizer.planSnapshotWrites` —— 设备事实留在调用点读，
 * 判据本体只吃参数，这样才能在没有 Robolectric 的 `:app` 单测里把每一档都跑一遍）。
 *
 * 为什么要换掉原先那颗 `consumed: Boolean`：它只在 `SignInState.Idle` 那一档被清，
 * 而 Idle 只有用户按下结果卡上的「重新扫码 / 继续扫码」才回得来。于是**任何一次放行之后**
 * （哪怕那次提交因为 `inFlight` 或状态值相等而根本没让界面发生变化），后续每一帧都在
 * 闸门处被静默丢掉，预览看着完全活着、镜头再对准一张码也不会有任何反应 —— 用户报的
 * 「扫码没反应」就是这一条。
 *
 * 但直接把闸门改成"失败了也照样放行"会把另一头的病招回来：同一张解不开的码会被每一帧
 * 重投，结果卡闪、电白耗。所以闸门记的是 **payload + 时刻**，不是开/关。
 */

/**
 * 一次放行：解码原文 + 放行时刻（墙钟毫秒）。
 *
 * ⚠️ 不可变，而且闸门那侧永远**整枚换引用**、不原地改字段 —— 写它的是 ML Kit 的回调线程，
 * 读它的是 `analysisExecutor`，清它的是主线程。两个字段各自 `@Volatile` 的话能读出
 * "新原文 + 旧时刻"这种从来没发生过的组合，冷却期就会被算错。
 */
internal data class ScanHandled(val payload: String, val atMillis: Long)

/**
 * "这一帧什么码都没解出来"以外的第二种留痕：解码器自己报了失败。
 *
 * 失败回调拿不到原文（那次解码根本没产出东西），所以用一个真实 QR 载荷不可能等于的常量占位：
 * 之后**同一档失败**按"同一张码"处理（受 [RescanCooldownMillis] 与
 * [shouldSubmitScan] 的 `awaitingUserAction` 管），而一旦真的解出了一张码，
 * payload 一变就放行 —— 相机坏过一会儿又好了那种情形不需要谁来重置。
 */
internal const val DecodeFailurePayload = "\u0000decode-failed"

/**
 * 同一份原文两次放行之间的最小间隔。
 *
 * 1500ms 的账：这颗闸门要盖住的是"一次提交在飞 + 结果卡刚立起来"那一小段抖动 ——
 * 教室里的节奏是，人对准一张码到看见结果卡不超过一秒，而换一张投影上的码（这才是
 * 闸门该放行的那一档）中间必然要移开镜头、至少两三秒。所以这个数只需要**短于换码动作、
 * 长于一次提交**：再短会在结果卡刚出现时又递进去一帧，再长就跟不上人换码的手。
 * 它不是任何正确性判据（正确的提交由 [shouldSubmitScan] 的 payload 分支和状态机自己的
 * `inFlight` 保证），只是一个不闪、不烧电的经验值。
 */
internal const val RescanCooldownMillis = 1_500L

/**
 * 这一帧解出来的原文能不能递给状态机。
 *
 * 放行条件（任一成立）：
 * 1. 从没放行过 —— 进入页面后的第一帧；
 * 2. 原文与上一次不同 —— 用户换了一张码（这正是被旧闸门锁死时唯一走不通的那条路）；
 * 3. 与上一次同一份原文，但 [RescanCooldownMillis] 已经过完，而且界面没有在等用户点按钮。
 *
 * 第 3 条里 `awaitingUserAction` 那一半不是可有可无的保险：结果卡（`Failed` / `Signed`）
 * 挂在那儿的时候，状态机回到 Idle 唯一的出口就是卡上那颗按钮，而 `MutableStateFlow` 对
 * **相等**的状态值不重发 —— 同一张解不开的码重投出来的还是同一个 `Failed`，
 * `LaunchedEffect(state)` 因此永远不会再触发清闸门。少了这一半，"只看冷却期"就等于
 * 每 1500ms 把同一张码真提交一次、一直刷到用户离开这一页。
 *
 * 时钟倒退（NTP 校时、用户改表）时第 3 条判不出"已过冷却"，方向是**少投一次**，
 * 与这颗闸门历来偏保守的口径一致。
 *
 * @param handled 上一次放行（null = 这轮还没放行过任何东西）
 * @param raw 本帧解出的原文
 * @param nowMillis 调用点读的墙钟
 * @param awaitingUserAction 屏幕上是否挂着等用户按的结果卡
 * @param cooldownMillis 同一份原文的冷却期，抽成参数只为让单测能把时间钉住
 */
internal fun shouldSubmitScan(
    handled: ScanHandled?,
    raw: String,
    nowMillis: Long,
    awaitingUserAction: Boolean,
    cooldownMillis: Long = RescanCooldownMillis,
): Boolean {
    val last = handled ?: return true
    if (raw != last.payload) return true
    if (awaitingUserAction) return false
    return nowMillis - last.atMillis >= cooldownMillis
}
