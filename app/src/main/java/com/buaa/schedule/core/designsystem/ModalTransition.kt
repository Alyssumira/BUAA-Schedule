package com.buaa.schedule.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 模态过渡外壳：把「该不该开」与「窗口还挂不挂」拆成两件事。
 *
 * 直接写 `AnimatedVisibility(open) { AlertDialog(…) }` 是不够的，卡点在窗口边界上：
 * Dialog / Popup 各自开一个**独立窗口**（`Dialog` 只把自带的 `Modifier.semantics`
 * 交给窗口根的 DialogLayout，父级组合传进来的 graphicsLayer 进不了那个窗口），
 * 而它们在父组合里连一个 measurable 都不产生。于是外面这层 AnimatedVisibility
 * 只决定"子树何时存在"，动效一点也落不到弹窗本体上——得到的正是
 * "晚一点卸载、照样硬切"，比不写还更难查。
 *
 * 所以分两段，各用各的机制：
 * 1. **外壳**（[AnimatedVisibility]，内建进出场都是 `None`）只当挂载闸门。
 *    它在**所有**内建与自定义收场动画播完之前不会移除 content，播完立刻移除——
 *    M3 的 AlertDialog 默认 `usePlatformDefaultWidth = false`，窗口是满屏的，
 *    收场后还挂着就等于在界面上留一层吃点击的透明板子。卸载时机必须由动画自身的
 *    结束语义给出，不能拿"再等一会儿"的定时兜底：reduce-motion 下 [dialogExit]
 *    是 `snap()`，定时那套会让弹窗凭空多占一帧到几百毫秒。
 * 2. **交给调用方的那份 [Modifier]**（`animateEnterExit` + [dialogEnter] / [dialogExit]）
 *    落在弹窗自己窗口里的 Surface/Box 上——真正在看的动画只有这一条路进得去窗口。
 *
 * 时长、缓动、0.92 这些值仍然只写在 [dialogEnter] / [dialogExit] 一处，
 * reduce-motion 由 `motionSpec()` 在那里兜底；调用点不出现任何数字。
 *
 * 容器一个都不换：见 docs/DESIGN_SYSTEM.md §3.1「玻璃层 = 导航与浏览，M3 层 = 模态与决策」。
 *
 * @param modifier 外层 [AnimatedVisibility] 自己的修饰符。**Popup 必须传
 *   `Modifier.matchParentSize()`**：浮层的定位锚是它在父组合里的那个 layout node，
 *   而 AnimatedVisibility 的节点没有子 measurable（Popup 同理），不补这一句锚点会
 *   缩成 0×0，`Alignment.TopEnd` 就变成"往左挪一个自身宽度"。
 *   AlertDialog 不需要——它的窗口根本不参与父布局。
 * @param enter 真正播给窗口里那份内容的进场，默认 [dialogEnter]。要换档只换**时长**
 *   （小面积浮层用 `dialogEnter(MotionTokens.DURATION_MENU)`，理由见 MotionTokens 里
 *   U-13 那条"越小的东西越先停"）——不要在这里手写 `fadeIn(tween(…))`：
 *   只有 dialogEnter/dialogExit 走 `motionSpec()`，reduce-motion 才还在。
 * @param content 收到的那份 [Modifier] **必须**挂到弹窗容器的 `modifier` 参数上
 *   （M3 的 AlertDialog 会把它带进窗口内的根 Box）；漏挂只得到延迟卸载，没有动画。
 */
@Composable
fun ModalTransition(
    open: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = dialogEnter(),
    exit: ExitTransition = dialogExit(),
    content: @Composable (Modifier) -> Unit,
) {
    AnimatedVisibility(
        visible = open,
        modifier = modifier,
        // 见文件头 1：这一层不画任何东西，动画在下面那份交给弹窗的 modifier 里
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        label = "ModalTransition",
    ) {
        content(
            Modifier.animateEnterExit(
                enter = enter,
                exit = exit,
                label = "ModalTransition",
            ),
        )
    }
}

/**
 * 数据负载版：弹窗内容由一个可空对象驱动时（`pendingDelete?.let { … }`）用它。
 *
 * 这类写法在对象变 null 的那一刻就把整个子树摘走了，外壳连"收场的第一帧"都活不到，
 * 动画等于没写。所以这里把最后一次的非空值锚在外壳里，收场期间继续画**上一次**的内容
 * ——顺带修掉另一半：点「删除」后课表里已经没有这门课，淡出的那张卡片不该当场空掉。
 */
@Composable
fun <T : Any> ModalTransition(
    payload: T?,
    modifier: Modifier = Modifier,
    enter: EnterTransition = dialogEnter(),
    exit: ExitTransition = dialogExit(),
    content: @Composable (T, Modifier) -> Unit,
) {
    var anchored by remember { mutableStateOf(payload) }
    // 只在非空时写：写回同一个值不会触发无效化，null 的那几帧就沿用锚住的负载播收场
    if (payload != null) anchored = payload
    ModalTransition(
        open = payload != null,
        modifier = modifier,
        enter = enter,
        exit = exit,
    ) { modal ->
        anchored?.let { content(it, modal) }
    }
}
