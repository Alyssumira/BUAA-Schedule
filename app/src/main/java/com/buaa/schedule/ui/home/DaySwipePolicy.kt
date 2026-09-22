package com.buaa.schedule.ui.home

/**
 * 「日视图左右滑动」这一滑的判据内核（T70）。纯判据：零 android import、零时钟读取
 * （仓库口径，见 [inUseBrowseDate] 与 [glassJankWindowAction]）：手势累计像素、
 * 阈值像素、reduce-motion 开关、这一滑到底有没有翻出去 —— 一律由调用点读出来当参数传进来。
 *
 * 用户报的是表现：「今日课表模式左右滑动动画有点卡顿」。这台镜像上先把四组数摆出来
 * （同一坐标、同一 300ms 时长、同一 14 次/轮，取中位数；口径见报告）：
 *
 * | 手势 | 帧数 | jank% | p50 帧时 |
 * |---|---|---|---|
 * | 越阈值的左右交替（会翻日） | 149 | 62.08 | 79ms |
 * | **不越阈值**（一次都不翻日） | 130 | 73.81 | **109ms** |
 * | 越阈值但两页都没有 Hero | 209 | 64.11 | 81ms |
 * | 周视图同一次数（HorizontalPager） | 59 | 94.44 | **500ms** |
 *
 * 第二行把「卡是因为 AnimatedContent 提交帧时新旧两份整页组合」这条假设**证伪**了：
 * 一次都不换天的手势反而更慢。第四行把「换成 Pager 就不卡」这条也证伪了：
 * 同一次手势在 Pager 那条路上每帧贵 6 倍。所以这一卡能动的不是"换哪个翻页控件"，
 * 而是滑动路径上那三处白烧 UI 线程的写法（下面逐条对着判据写）。
 *
 * ⚠️ 这一维只管「这一滑算翻到哪一天、正文该跟手走多少」，不管「这一帧画哪一天」：
 * 后者仍是 HomeScreen 那一份 `browseDateOnScreen`（T68）。内核不许读今天，
 * DayView 也不许拿这里的判据自己存日期。
 */

/** 一次横滑落定以后翻去哪一天（[daySwipeCommit] 的返回值） */
internal enum class DaySwipeCommit {
    /** 向左拖过阈值：看后一天 */
    NextDay,

    /** 向右拖过阈值：看前一天 */
    PreviousDay,

    /** 没到阈值：还在这一页，正文弹回去 */
    Stay,
}

/**
 * 跟手只映 0.35 倍：整幅跟随会让正文与两侧的箭头脱开，读成"箭头没跟着走"。
 *
 * 这一档在 T70 之后只描述**未翻出去那一侧**的手感：翻出去那一侧的位移交给
 * [dayAxisTransition] 的共享轴，不再由拖拽位移与转场位移叠加（见 [dayDragShouldSettle]）。
 */
internal const val DAY_DRAG_FOLLOW_RATIO = 0.35f

/**
 * 这一滑翻到哪一天。两侧对称、阈值含边界（`<=` / `>=`）：
 * 恰好拖到 72dp 就算翻出去，与改前的 `swipeDrag <= -swipeThreshold` 逐字同口径。
 *
 * @param totalDragPx 手势累计的水平位移（左拖为负），由调用点从 `dragAmount` 累加
 * @param thresholdPx 阈值换算成的像素——**density 留在调用点折**，内核不碰 LocalDensity
 */
internal fun daySwipeCommit(totalDragPx: Float, thresholdPx: Float): DaySwipeCommit = when {
    totalDragPx <= -thresholdPx -> DaySwipeCommit.NextDay
    totalDragPx >= thresholdPx -> DaySwipeCommit.PreviousDay
    else -> DaySwipeCommit.Stay
}

/**
 * 手势累计位移 → 正文这一帧该走的位移。
 *
 * 两件事按顺序做，顺序本身就是判据：
 * 1. reduce-motion 下恒为 0f —— 开关一开，正文不跟手，而阈值判定照旧（[daySwipeCommit] 不读这个开关）；
 * 2. 越过阈值的那一截不再增加位移 —— 继续拖已经不携带新信息，而松手回弹的距离一旦跟着变大，
 *    就成了新的干扰。
 *
 * @param thresholdPx 同一个阈值既当阻尼的天花板、又当判定的门槛：位移最多跟到 0.35×阈值，
 *   再拖就停在那儿，与改前 DayView 里那枚私有的 `dampedDragOffset` 逐字同口径。
 */
internal fun dayDragFollowOffset(
    totalDragPx: Float,
    thresholdPx: Float,
    reduceMotion: Boolean,
): Float {
    if (reduceMotion) return 0f
    return totalDragPx.coerceIn(-thresholdPx, thresholdPx) * DAY_DRAG_FOLLOW_RATIO
}

/** 松手以后那笔跟手位移怎么收回（[dayDragSettleMode] 的返回值） */
internal enum class DayDragSettleMode {
    /** 没翻出去：弹簧弹回 0，手感与改前逐字一致 */
    SpringBack,

    /**
     * 翻出去了：按**转场时长**线性收回，与 [dayAxisTransition] 的共享轴同起同落。
     *
     * 两套位移仍然叠加，但同方向、同时长：正文从 -0.35×阈值 走回 0，旧的这一页同时从 0
     * 退到 -1/8 屏宽 —— 合起来仍然是一路往左，不再"滑到一半自己抖回去"。
     */
    RideWithTransition,
}

/**
 * 松手以后正文位移走哪条收回路径。
 *
 * **这一条是本卡真正的行为改动**：改前 `onDragEnd` 无论翻没翻出去都挂同一根弹簧
 * （`motionSpringFor`），于是翻出去那一侧同时挂着两套**方向相反、时长也不同**的位移 ——
 * 拖拽位移在弹簧下慢慢往回走 0，[dayAxisTransition] 又让新的一天从同一方向滑进来。
 * 弹簧比 140ms 的转场长得多，而整页正文（Hero 那块采样背景的玻璃板 + 一根 1260dp 高、
 * 不懒测的时间轴）在这整段弹簧里每帧重画一次 —— 上表第一行量到的就是 149 帧 / 79ms 一帧。
 *
 * 改成：翻出去那一侧的收回与转场同起同落，把"每帧重画整页"的窗口从一根没有明确长度的弹簧
 * 收进 140ms；没翻出去那一侧不许动，手感与改前一致。
 */
internal fun dayDragSettleMode(commit: DaySwipeCommit): DayDragSettleMode =
    if (commit == DaySwipeCommit.Stay) DayDragSettleMode.SpringBack else DayDragSettleMode.RideWithTransition

/**
 * 拖拽期正文该不该跟着手走（reduce-motion 那一档的开关名）。
 *
 * 单独抽出来不是为了省一个 `!`：接线里"事件只累加、写位移交给帧回调"那条链需要一个
 * 明确的名字说明**为什么** reduce-motion 下连帧回调都不用起，而不是让人去猜那个 if。
 */
internal fun dayDragShouldFollow(reduceMotion: Boolean): Boolean = !reduceMotion

/**
 * 一帧之内最多写一次位移：事件率高于帧率时，多出来的那些写全部作废。
 *
 * 这台镜像上一次 `input swipe 300ms` 注入约 30 个 pointer 事件，而实测帧时 79ms
 * ——一帧里挤着 5 个事件。改前每个事件起一枚协程去 `Animatable.snapTo`，
 * 也就是每帧 5 次快照写 + 5 次 DrawState 失效 + 5 次树同步，屏幕只画得出其中 1 帧。
 * 判据把"这一帧要不要写"收在一处：帧回调里只要事件号动过就写一次，没动过就不写。
 *
 * @param lastWrittenEvent 上一次写位移时的事件序号
 * @param latestEvent 当前累计到的事件序号
 */
internal fun dayDragShouldWriteOffset(
    lastWrittenEvent: Long,
    latestEvent: Long,
): Boolean = latestEvent != lastWrittenEvent
