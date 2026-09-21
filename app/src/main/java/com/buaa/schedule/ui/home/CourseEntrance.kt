package com.buaa.schedule.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.LocalReduceMotion
import com.buaa.schedule.core.designsystem.MotionTokens
import com.buaa.schedule.core.designsystem.motionSpec

/**
 * 课次卡片进场（T52②）的挂接件：淡入 + 轻微上浮，**每次真正进入这一屏只播一遍**。
 *
 * 四件设计决定，都是被仓库里已经吵过的那几本账逼出来的：
 *
 * 1. **一屏一条驱动，卡片只切窗口**：[rememberCourseEntrance] 在页面层起**一条**
 *    `animateFloatAsState`（0→1），每张卡从这条驱动里切自己那一段（[entranceSlotProgress]）。
 *    逐卡各挂一条动画是 [com.buaa.schedule.core.designsystem.WeekDensityStrip] 里
 *    已经否掉过的写法（"19 个 composable 各起一个动画，而它们本来就是同一次进场"），
 *    而周视图一屏几十张卡更甚。切窗口还顺手把"总长封顶"变成构造性质：
 *    末格永远在驱动收尾那一刻落定，条目再多也只是步长变短（见 [entranceSlotProgress] 的推导）。
 * 2. **播一次由进程级记忆决定，不由组合决定**：见 [EntrancePlaybook]。
 *    第二次进入这一屏时 `mounted` 初值就是 1，驱动压根不会启动。
 * 3. **时长只有一个来源**：驱动走 [MotionTokens.DURATION_LONG]（380ms，"强调型进出场：
 *    弹窗、大面板"档——一次进场铺开的是整屏卡片，是这里最大的面积）。
 *    这里不出现任何新的毫秒数。
 * 4. **动画值只在绘制期读**：`drive.value` 读在 `graphicsLayer {}` 块里（[CourseEntrance.applyTo]，
 *    layer 由调用点持有）。读在组合期等于补间那 380ms 让每张卡每帧重组一次
 *    （同族约束：WeekView 的 pulse、分段控件的胶囊尺寸、网格空档高度）。
 *    落定后 alpha=1、translationY=0，恒等变换不另起离屏缓冲，
 *    静止画面与改前逐像素一致。
 *
 * 可打断（T52⑤）：驱动是 `animateFloatAsState`，目标值变了就从**当前值**续，不回零、不重启；
 * 而 [EntrancePlaybook] 让第二次进入这一屏连播都不播，于是"来回切页签"既不会重播、
 * 也不会留下半截动画。
 */
@Composable
internal fun rememberCourseEntrance(playKey: String): CourseEntrance {
    val reduceMotion = LocalReduceMotion.current
    // 组合进来一次问一次：第二次进来 plays=false，mounted 直接从 1 起，整条动画不存在
    val plays = remember(playKey) { !reduceMotion && EntrancePlaybook.claim(playKey) }
    var mounted by remember(playKey) { mutableStateOf(!plays) }
    LaunchedEffect(playKey, plays) { if (plays) mounted = true }
    val drive = animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        // 规格只从 motionSpec 取：reduce-motion 与"治理判定本机跑不动玻璃"两条开关
        // 都在那里生效，reduce 档下 mounted 直接 snap 到位（播不播由上面的 plays 决定）
        animationSpec = motionSpec<Float>(MotionTokens.DURATION_LONG),
        label = "courseEntrance",
    )
    return CourseEntrance(drive)
}

/**
 * 一次进场的驱动：包一层只暴露 [applyTo]，页面层拿到的是 State 引用而不是值——
 * 读值发生在绘制期，驱动动了不会让宿主整棵子树重组。
 */
internal class CourseEntrance internal constructor(private val drive: State<Float>) {

    /**
     * 把第 [slot] 格（共 [slotCount] 格）的进场变换（淡入 + [CourseEntranceRise] 上浮）
     * 写进调用点持有的 `graphicsLayer {}` 的 [layer] 里。
     *
     * 只有这一种挂法，没有"返回 Modifier 的工厂"两条路可走，各有账目：
     * - 仓库的 compose lint 把非 Modifier 扩展的 modifier 工厂判红
     *   （ModifierFactoryExtensionFunction），而挂接本来就要一层 graphicsLayer；
     * - 周视图 CourseCell 自带按压/脉冲的缩放层，那里不能叠第二层——两层各自持有
     *   alpha，谁也别想把谁覆盖掉这条账就只能靠"落定后恒等"兜着。
     * 于是今日页两处挂自己的 layer，周网格并进既有 layer，共用这一条算式；
     * 读 [drive] 永远发生在绘制期（layer 块内）。
     *
     * [slotCount] ≤ 0 时是恒等变换：空列表不该有任何东西可淡，
     * 但也绝不该把调用点原本挂着的图层参数（比如别处设过的 alpha）改写成 0。
     */
    fun applyTo(layer: GraphicsLayerScope, slot: Int, slotCount: Int) {
        if (slotCount <= 0) return
        val progress = entranceSlotProgress(drive.value, slot, slotCount)
        layer.alpha = progress
        layer.translationY = (1f - progress) * with(layer) { CourseEntranceRise.toPx() }
    }
}

/**
 * 进场时一张卡自己的上浮量：8dp。
 *
 * 这是**绘制期位移**，不参与测量与布局，所以它不是版面刻度、不进 DesignTokens，
 * 也不影响任何静止状态下的像素（落定后恒为 0）。取 8dp 而不是翻日期那种
 * 「屏宽 1/8」：那一条是在换一天，这一条只是同一天把卡片摆上桌。
 *
 * 在 `graphicsLayer {}` 里直接 `toPx()`：那块作用域本身就是 [androidx.compose.ui.unit.Density]，
 * 不必再从组合期把密度传出来。
 */
private val CourseEntranceRise = 8.dp
