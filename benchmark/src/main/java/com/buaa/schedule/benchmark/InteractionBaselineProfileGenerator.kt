package com.buaa.schedule.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * 日常交互这一半的 Baseline Profile 场景。
 *
 * 为什么要有这个类：[BaselineProfileGenerator] 只采冷启动那一段（它是全工程唯一带
 * `includeInStartupProfile = true` 的），于是产出的 profile 里只有启动路径上的方法，
 * 用户真正天天做的「翻周表 / 切时间轴 / 看今日 / 进设置页」一个都不在里面。
 * 对本应用这笔账尤其划算 —— 自分发 + 应用内自更新会把系统的安装过滤打回 verify，
 * 而云端 ART profile 是 Google Play 专属通道，我们结构上拿不到，profile 是唯一补偿。
 *
 * 四条 CUJ、五个采集方法：「切时间轴」在真机上是**两件不同的控件**（周课表侧那颗胶囊
 * 与今日页那个「列表/时间轴」分段控件），锚点完全不同，合在一个方法里就必有一边空跑，
 * 所以拆成 [toggleWeekTimeAxisMode] 与 [toggleTodayListTimeline]。
 *
 * 五条写在代码里的取舍：
 *
 * 1. **扫码页与 WebView 导入页不进 profile。** 扫码是 CameraX + MLKit 的路径，
 *    帧回调与解码方法一旦进 profile，冷启动首帧就要为它们付编译成本，而绝大多数
 *    启动根本不会走到那里；导入页的 WebView 更不用 —— 那是系统组件在编译，
 *    ART 也管不着。所以这里刻意不点底栏的「导入」。
 * 2. **每个场景都从 `startActivityAndWait()` 起、在已播种的库上跑，并且自己声明起点页**
 *    （[ensureOnWeekPage] / [ensureOnTodayPage]）。生成前的播种条件见 docs/STATUS.md。
 *    不依赖 app 的默认落位是硬要求：`HomeScreen.kt:180` 写的是
 *    `selectedTab = if (hasTodayCourses) 1 else 0` —— **今天有课就直接落在「今日」页**，
 *    上一版两条"以为自己在周视图"的场景其实一直站在今日页上。
 * 3. **锚点只用 dump 里真存在的东西，点法一律走坐标。** 本工程没有任何
 *    `testTagsAsResourceId`，所以 `By.res()` 这条路对 Compose 节点无效；能用的只有
 *    `By.desc()`（对应 `contentDescription`）与 `By.text()`（对应 `Text` 节点）。
 *    为什么不能用 `UiObject2.click()`：见 [tapLowest] —— 真机实测这个应用的**文案节点
 *    全部 clickable=false**，按 `isClickable` 过滤就把场景筛成了零点击。
 * 4. **确认不了就抛异常，绝不停在"什么也没点"。** 静默空跑的采集会产出一份
 *    **看着成功其实没用**的 profile：Gradle 绿、文件有内容、方法表里却只有启动路径，
 *    而它的读法和"已经覆盖了日常交互"一模一样 —— 这比红一条测试危险得多，红至少会
 *    逼着人去看。所以下面每条场景在每一跳之后都要拿 dump 里看得见的证据确认一次
 *    （[waitUntil]），点不动的那一步会直接终止这一轮。
 * 5. **所有选择器都套 [inDesc]/[inText] 限定包名**：连着的设备上前台可能叠着别的东西
 *    （输入法、Toast 宿主窗口），不限定包名就会点到系统 UI 上，而那种失败在采集日志里
 *    读起来只像"profile 怎么是空的"。
 *
 * 锚点的可信度分两级，写在每个常量后面：**实测** = emulator-5554（API 36，
 * release nonMinified，已播种）上 `uiautomator dump` 亲眼见到；其余只核过源码行号。
 * 没验过的锚点一律不当**点击目标**用（例如设置页那颗「展开」，见 [scrollSettings]）。
 * 唯一的例外是 [segmentSelected] 依赖的 `selected` 标志 —— 那是今日页那两格唯一可读的
 * 状态证据，绕不开；它的 KDoc 里写明了红的时候该怎么复核。
 */
@RunWith(AndroidJUnit4::class)
class InteractionBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /**
     * CUJ 1 · 周课表纵向滚动 + 翻周。
     *
     * 周视图的滚动容器是 `WeekView.kt:679` 的 `verticalScroll(gridScrollState)`，
     * 网格按节次分行、内容比一屏长，所以滚动真的会绑定新行。
     *
     * 翻周走顶栏的「下一周」（`HomeScreen.kt:886` 的 contentDescription，与实测过的
     * 「后一天」同一种节点）：它 `enabled = displayWeek < totalWeeks`，所以播种的学期
     * 必须处在中间周（条件见 docs/STATUS.md）。翻完用周次标题（「第3周」→「第4周（浏览）」，
     * `HomeScreen.kt:965-969` 的 `weekHeadline()`）当证据 —— 这是这一跳唯一点得出来的物证，
     * 标题没变就是没点上。
     */
    @Test
    fun scrollWeekGrid() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.waitForIdle()

        device.ensureOnWeekPage()

        val headlineBefore = device.weekHeadlines()
        if (device.tapLowest(inDesc(DESC_NEXT_WEEK)) != null) {
            device.waitUntil(
                "翻周：周次标题从 $headlineBefore 变走。两次都是空集的话，说明 " +
                    "WEEK_HEADLINE_PATTERN 在这台设备上没匹配到任何节点，得回源码对文案格式",
            ) { device.weekHeadlines() != headlineBefore }
        } else {
            // 两种"没点着"必须分开：锚点存在但是禁用态 = 已经翻到最后一周，跳过这步是
            // 诚实的；锚点根本不存在 = 这条场景会静默空跑，必须红。
            check(device.hasDesc(DESC_NEXT_WEEK)) {
                "周课表页上没有 content-desc「$DESC_NEXT_WEEK」（HomeScreen.kt:886）—— " +
                    "要么这颗按钮的 desc 没进 accessibility 树，要么这一步压根没站在周课表页。" +
                    "翻周这一跳会静默空跑，宁可直接红"
            }
        }

        repeat(SCROLL_STEPS) { device.scrollContentDown() }
        device.scrollContentUp()
    }

    /**
     * CUJ 2a · 周课表侧「课次行 ↔ 24 小时时间轴」。
     *
     * 这颗胶囊的两个状态是**同一个节点的同一个 contentDescription 位**在变：
     * `HomeScreen.kt:817` 写的是 `if (timeMode) "时间轴视图" else "课次行视图"`。
     * 所以"当前是哪一态"能从 dump 里读出来，不用猜：先按当前态的 desc 找它、点一次，
     * 再按新的 desc 点回来。**来回**是这条场景的自检装置 —— 第二跳找得到第一跳留下的
     * 那个态，就说明第一跳真的点了；第一跳点空了，第二跳就找不到，[waitUntil] 直接红。
     *
     * 切换会整表重组（时间轴模式下每行的高度、刻度都要重算），这是这条 CUJ 的价值所在。
     * 模式是落盘的（`HomeScreen.kt:814` 的 `Personalization.save`），所以这里必须回到
     * 出发态收尾，否则下一轮采集从另一态起步。
     */
    @Test
    fun toggleWeekTimeAxisMode() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.waitForIdle()

        device.ensureOnWeekPage()

        val from = when {
            device.hasDesc(DESC_WEEK_VIEW_PERIOD) -> DESC_WEEK_VIEW_PERIOD
            device.hasDesc(DESC_WEEK_VIEW_TIME) -> DESC_WEEK_VIEW_TIME
            else -> throw IllegalStateException(
                "周课表页上「$DESC_WEEK_VIEW_PERIOD」和「$DESC_WEEK_VIEW_TIME」两个 content-desc " +
                    "都不在 dump 里（HomeScreen.kt:817 那颗胶囊）—— 锚点不成立，这条场景只能空跑，" +
                    "先拿 uiautomator dump 确认这颗胶囊到底暴露了什么"
            )
        }
        val to = if (from == DESC_WEEK_VIEW_PERIOD) DESC_WEEK_VIEW_TIME else DESC_WEEK_VIEW_PERIOD

        device.tapOrThrow(
            inDesc(from),
            "模式胶囊（当前态「$from」）的可点区域",
        )
        device.waitUntil("胶囊翻到「$to」：旧 desc 消失、新 desc 出现") {
            device.hasDesc(to) && !device.hasDesc(from)
        }

        device.tapOrThrow(inDesc(to), "模式胶囊（当前态「$to」）的可点区域")
        device.waitUntil("胶囊回到「$from」") { device.hasDesc(from) && !device.hasDesc(to) }

        repeat(2) { device.scrollContentDown() }
    }

    /**
     * CUJ 2b · 今日页「列表 ↔ 时间轴」。
     *
     * 这颗控件与 2a 那件不是一回事：`DayView.kt:248` 的
     * `GlassSegmentedControl(options = listOf("列表", "时间轴"))`，只有文字、没有
     * content-desc，而文字不随状态改（选中态只体现在颜色/字重上，dump 里读不出来）。
     * 所以这里的判据换成 [segmentSelected]：按几何关系找「这格对应的可点容器」有没有
     * 带 `selected=true`。
     *
     * **三跳而不是两跳**：起点态读不出来（2a 能把当前态写在 desc 上，这里不能），
     * 所以固定从「列表」起步、回到「列表」收尾 —— 与上一轮停在哪个态无关，且每一跳
     * 后面都有自己的判据。两跳的话结尾落在哪一态就说不清了，而日视图的内容树整棵要按
     * 模式重组（`DayView.kt:423` 的 `Crossfade(targetState = timelineMode)`：列表侧是
     * LazyColumn，时间轴侧是 `Column + verticalScroll`，`DayView.kt:523`），
     * 落回默认态才算把播种环境还原。
     *
     * 锚点不依赖数据：`DayView.kt:247` 那个 `GlassSegmentedControl` 直接挂在页头
     * `Column` 里，**没有**"这一天有课才渲染"的门，所以没课的日子这两格也在、这一条也跑得通。
     * 但播种仍要让今天有课 —— 空的一天内容区走 `DayView.kt` 的 `EmptyState` 分支，
     * 整表重组那条路径就没东西可重组，这一跳只是切了两格的颜色而已。
     */
    @Test
    fun toggleTodayListTimeline() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.waitForIdle()

        device.ensureOnTodayPage()

        for (label in listOf(LABEL_DAY_LIST, LABEL_DAY_TIMELINE, LABEL_DAY_LIST)) {
            device.tapOrThrow(inText(label), "今日页分段「$label」（DayView.kt:248 的 options）")
            device.waitUntil("「$label」成为选中格") { device.segmentSelected(label) }
        }

        repeat(2) { device.scrollContentDown() }
    }

    /**
     * CUJ 3 · 今日视图。
     *
     * 顶栏那枚分段控件是 `HomeScreen.kt:421` 的 `options = listOf("周课表", "今日")`，
     * 格子文本走 `Text(label)`（`GlassSegmentedControl.kt:254`），所以是 `By.text` 命中
     * （**实测**：这两格在 dump 里就是 clickable=false 的 Text 节点，必须坐标点击）。
     * 两段文本各自唯一：「今日」是精确匹配，不会撞上标题那句「今日课表」。
     *
     * 起点刻意声明在周课表侧：本应用今天有课时**默认就落在今日页**（`HomeScreen.kt:180`），
     * 不先站到周课表上，这一跳就只是重复点一次已经在的格子，切页那条组合路径根本没走到。
     *
     * 「今日」这一侧的内容是 `DayView.kt` 的 LazyColumn + verticalScroll，
     * 与周视图是两条完全不同的组合路径 —— 这正是它值得单列一个场景的原因。
     */
    @Test
    fun openTodayView() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.waitForIdle()

        device.ensureOnWeekPage()

        device.tapOrThrow(inText(LABEL_TODAY_SEGMENT), "顶栏分段「$LABEL_TODAY_SEGMENT」")
        device.waitUntil("切到今日页：出现 content-desc「$DESC_PREV_DAY」/「$DESC_NEXT_DAY」") {
            device.onTodayPage()
        }

        repeat(SCROLL_STEPS) { device.scrollContentDown() }

        device.tapOrThrow(inText(LABEL_WEEK_SEGMENT), "顶栏分段「$LABEL_WEEK_SEGMENT」")
        device.waitUntil("切回周课表：出现 content-desc「$DESC_PREV_WEEK」/「$DESC_NEXT_WEEK」") {
            device.onWeekPage()
        }
    }

    /**
     * CUJ 4 · 设置页。
     *
     * 入口是底栏的「设置」（`MainActivity.kt:312` 的 navItems，文案取
     * R.string.tab_settings = 设置）。之所以用 [tapLowest] 而不是直接 findObject：
     * 进了设置页以后顶栏标题也叫「设置」（`SettingsScreen.kt:354`），
     * 页面上会同时存在两个同名节点，取最靠下的那个才是底栏。
     *
     * **这一页的证据用「返回」而不是「设置」**：设置页的 `GlassTopBar` 无条件收到一个
     * `onBack`（`SettingsScreen.kt:355`），于是 `GlassTopBar.kt:73` 那颗
     * `Text("返回")` 一定会渲染，而课表页与导入页都没有这两个字 —— 比数「设置」的
     * 个数干净（底栏本来就有一个）。
     *
     * **滚动的价值要说清楚，别写得比实际更强**：设置页改成"分类子界面"之后
     * （`SettingsScreen.kt:379`），根界面只列 6 条分类入口，其余分组全靠
     * `visibleWhen = section == …` 整组跳过，所以根界面上那几记坐标 swipe
     * 大概率推不动任何东西（一屏就装完了）。这一条 CUJ 真正吃到的组合是**进入设置页**
     * 这一下（`SettingsScreen.kt:363` 的 verticalScroll + 六个玻璃行 + 顶栏）——
     * 上一版它连点都没点成，等于整页没进。
     *
     * 「展开」保持**命中就点、点不到就跳过**：它是 `SettingsStack.kt:228` 那颗 chevron
     * 的 contentDescription，而 `SettingsGroup` 的 `collapsible` 默认是 false，根界面
     * 唯一一个组还是 `SettingsGroup(title = null)`（`SettingsScreen.kt:384`）——
     * 按源码根界面不会渲染它。真机 dump 里我也**没有**验过它作为 content-desc 出现，
     * 所以它既不当锚点、也不加断言。
     */
    @Test
    fun scrollSettings() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.waitForIdle()

        device.tapOrThrow(inText(LABEL_SETTINGS_TAB), "底栏「$LABEL_SETTINGS_TAB」")
        device.waitUntil("进设置页：顶栏出现「$LABEL_BACK」") {
            device.findObject(inText(LABEL_BACK)) != null
        }

        repeat(SCROLL_STEPS) {
            device.scrollContentDown()
            // 遇到可展开的分组就开一下：这条路径会重组整组 item，值得进 profile。
            // 现在必然点不到（理由见上面 KDoc），留着是为了以后场景伸进子界面时不用重写。
            device.tapLowest(inDesc(DESC_SETTINGS_EXPAND))
        }
        repeat(2) { device.scrollContentUp() }

        // 回底栏「课表」：设置根界面第一条分类入口的标题也叫「课表」
        // （SettingsScreen.kt:140 的 SCHEDULE），[tapLowest] 取 centerY 最大的那个 = 底栏。
        device.tapOrThrow(inText(LABEL_SCHEDULE_TAB), "底栏「$LABEL_SCHEDULE_TAB」")
        device.waitUntil("回到课表页：首页的日期/周次导航回来了") {
            device.onWeekPage() || device.onTodayPage()
        }
    }
}

// ── 一次采集里滚几屏。再多只是拉长每一轮，重复滚动不再新增待编译方法 ──
private const val SCROLL_STEPS = 4

/** 起点页确认最多试几次：第 1 遍可能撞在分段切换的展开/收起动画上，见 [ensurePage] */
private const val PAGE_ATTEMPTS = 2

/** 轮询证据的间隔与两档超时 */
private const val POLL_MS = 200L
private const val CONFIRM_TIMEOUT_MS = 6_000L
private const val PAGE_TIMEOUT_MS = 3_000L

/** 点完之后再多等这么久复查一次，避开"首帧页签事后被改掉"那个竞态 */
private const val SETTLE_MS = 800L

// 周课表顶栏那颗模式胶囊的两个状态（同一节点、两个态；HomeScreen.kt:817）
private const val DESC_WEEK_VIEW_PERIOD = "课次行视图"
private const val DESC_WEEK_VIEW_TIME = "时间轴视图"

private const val DESC_NEXT_WEEK = "下一周"        // HomeScreen.kt:886
private const val DESC_PREV_WEEK = "上一周"        // HomeScreen.kt:844（不是「前一周」）
private const val DESC_PREV_DAY = "前一天"         // DayView.kt:201  实测
private const val DESC_NEXT_DAY = "后一天"         // DayView.kt:236  实测
private const val DESC_SETTINGS_EXPAND = "展开"    // SettingsStack.kt:228（未实测，不当判据）

private const val LABEL_WEEK_SEGMENT = "周课表"    // HomeScreen.kt:421  实测
private const val LABEL_TODAY_SEGMENT = "今日"     // HomeScreen.kt:421  实测
private const val LABEL_DAY_LIST = "列表"          // DayView.kt:248     实测
private const val LABEL_DAY_TIMELINE = "时间轴"    // DayView.kt:248     实测
private const val LABEL_SETTINGS_TAB = "设置"      // R.string.tab_settings
private const val LABEL_SCHEDULE_TAB = "课表"      // R.string.tab_home
private const val LABEL_BACK = "返回"              // GlassTopBar.kt:73，设置页才有

/** 周次标题：`weekHeadline()` 产出「第3周」/「第4周（浏览）」，全等匹配 */
private val WEEK_HEADLINE_PATTERN: Pattern = Pattern.compile("第\\d+周(?:（浏览）)?")

/**
 * 限定包名 + contentDescription + 只取可用节点（用于**点击**）。
 *
 * `enabled(true)` 在这里仍然必要，而且必要性变了：以前是防 `UiObject2.click()` 抛异常，
 * 现在是坐标点击**不会**报错 —— 点在禁用态上就是什么都不发生，正好是我们要治的那种
 * 静默失败。所以禁用态必须在**选择器**这一层就排除掉，让它落到"没命中"那条分支上，
 * 由调用方去区分"锚点不存在（红）"与"按钮禁用（跳过）"。
 */
private fun inDesc(description: String): BySelector =
    By.pkg(PACKAGE_NAME).desc(description).enabled(true)

/** 限定包名 + 文本 + 只取可用节点。与 [inDesc] 同一口径，理由同上 */
private fun inText(label: String): BySelector =
    By.pkg(PACKAGE_NAME).text(label).enabled(true)

/**
 * 只问"这个 content-desc 存不存在"，**不带** enabled 过滤。
 *
 * 证据判据要的是存在性而不是可点性：禁用态的「上一周」仍然证明"这是周课表页"，
 * 加了 enabled 过滤就会在学期最后一周把这条证据读成"页面不对"。
 */
private fun atDesc(description: String): BySelector =
    By.pkg(PACKAGE_NAME).desc(description)

/** 这个 content-desc 在不在（不关心可点性） */
private fun UiDevice.hasDesc(description: String): Boolean =
    findObject(atDesc(description)) != null

/** 周课表页的证据：只有它会渲染的上一周/下一周导航（HomeScreen.kt:844/886） */
private fun UiDevice.onWeekPage(): Boolean =
    hasDesc(DESC_PREV_WEEK) || hasDesc(DESC_NEXT_WEEK)

/** 今日页的证据：只有它会渲染的日期导航（DayView.kt:201/236，实测） */
private fun UiDevice.onTodayPage(): Boolean =
    hasDesc(DESC_PREV_DAY) || hasDesc(DESC_NEXT_DAY)

/**
 * dump 里现在看得见的周次标题集合。
 *
 * 按 Pattern 选文本的工厂方法是 `By.text(Pattern)`（整串匹配），**不是** `textMatches` ——
 * uiautomator 2.3.0 里没有后者，写了就是编译期 Unresolved reference。
 */
private fun UiDevice.weekHeadlines(): Set<String> =
    findObjects(By.pkg(PACKAGE_NAME).text(WEEK_HEADLINE_PATTERN))
        .mapNotNull { it.text?.toString() }
        .toSet()

/**
 * 分段控件里 [label] 这一格是不是当前选中格。
 *
 * 今日页那两格（`GlassSegmentedControl(options = listOf("列表", "时间轴"))`）的文本
 * 不随状态改，颜色/字重又不在 dump 里，唯一可读的是 `.selectable(selected = …)`
 * （`GlassSegmentedControl.kt:244`）带出的 `selected` 标志。但**文本和这个标志不在
 * 同一个节点上**（字是段的子节点，:254），所以只能按几何关系认：取所有
 * `selected=true` 的节点，看有没有哪个的 bounds 罩住了这格文字的中心 ——
 * 与 [tapLowest] 同一个道理，这里信的是 bounds，不是名字也不是 clickable。
 *
 * ⚠️ 这是本文件唯一一处**没有在真机 dump 上验过**的判据（编排者那次 dump 没看
 * `selected` 属性）。如果 Compose 没把 Role.Tab 的选中态映射到
 * AccessibilityNodeInfo.isSelected，这条场景会红在 [toggleTodayListTimeline] 的
 * `waitUntil`，消息就是"「列表」成为选中格" —— 那时候的复核动作是
 * `uiautomator dump` 后 grep `selected="true"` 的条数，而不是改判据的写法。
 * 判据读不出来就红，是故意的：静默空跑才是不能接受的结局。
 */
private fun UiDevice.segmentSelected(label: String): Boolean {
    val text = findObject(inText(label)) ?: return false
    val x = text.visibleBounds.centerX()
    val y = text.visibleBounds.centerY()
    return findObjects(By.pkg(PACKAGE_NAME).selected(true)).any { it.visibleBounds.contains(x, y) }
}

/**
 * 站到周课表页，并确认站上了。
 *
 * **先刻意切一次今日页、再切回来**，这一遍不是冗余，是把 app 自己那一下改页签的
 * `LaunchedEffect`（`HomeScreen.kt:178-183`）吃掉：它在首次数据到位时把
 * `selectedTab` 改成 1（今日）并置 `tabDecided`，此后永不再动 —— 也就是说全应用
 * 只有"往今日页去"的自动改页签。而首帧 `selectedTab` 的初值就是 0，周课表那一簇
 * （学期/周次步进/胶囊）已经在屏幕上，于是"点分段 + 确认到周课表证据"这一趟
 * 在加载慢的机器上会**假通过**：点之前就在、点之后还在，什么都不变，然后加载完成
 * 把我们拽去今日页，下一跳的「下一周」就点在已经不存在的锚点上。
 * 先站一次今日页，那次自动改页签就落在我们已经在的地方，之后再手动切回周课表，
 * 就没有谁能再把这一页换掉。
 *
 * 见 [ensurePage]：不依赖 app 的默认落位（今天有课会直接落在今日页），
 * 也不接受"大概切过去了"。
 */
private fun UiDevice.ensureOnWeekPage() {
    ensurePage(
        segment = LABEL_TODAY_SEGMENT,
        evidence = "content-desc「$DESC_PREV_DAY」/「$DESC_NEXT_DAY」",
        probe = { onTodayPage() },
    )
    ensurePage(
        segment = LABEL_WEEK_SEGMENT,
        evidence = "content-desc「$DESC_PREV_WEEK」/「$DESC_NEXT_WEEK」",
        probe = { onWeekPage() },
    )
}

/**
 * 站到今日页，并确认站上了。见 [ensurePage]。
 *
 * 不需要 [ensureOnWeekPage] 那一遍"先去吃掉自动改页签"：那个 effect 的方向就是今日页，
 * 站在这里之后没有东西会再把我们带走。
 */
private fun UiDevice.ensureOnTodayPage() = ensurePage(
    segment = LABEL_TODAY_SEGMENT,
    evidence = "content-desc「$DESC_PREV_DAY」/「$DESC_NEXT_DAY」",
    probe = { onTodayPage() },
)

/**
 * 按文本点顶栏分段 [segment]，然后用 dump 里的证据确认已经切过去；确认不了就抛。
 *
 * 为什么要这个函数：`HomeScreen.kt:173` 的 `selectedTab` 默认 0，但 `:180` 那句
 * `selectedTab = if (hasTodayCourses) 1 else 0` 会在数据到位后把它改掉 —— 也就是说
 * **落在哪一页取决于今天有没有课**，而采集的前提是"今天有课"（不然日视图是空的）。
 * 于是上一版两条周视图场景实际站在今日页上：胶囊不在那一侧（它在
 * `showWeekNav = selectedTab == 0` 那一簇里），「下一周」也不在，两条场景一次点击
 * 都没发生，采集退化成在默认页面上做几次坐标滑动。
 *
 * 为什么还要复查一次 + 试两次：主防线是 [ensureOnWeekPage] 里"先绕一次今日页"，
 * 那道竞态不再靠这里兜；这里留的是第二道，管的是短时抖动 —— 分段切换要跑
 * `AnimatedVisibility` 的展开/收起动画（`HomeScreen.kt:786`），旧页的锚点在动画期间
 * 仍在屏幕上，而新页的锚点得等组合落定才出现。[settledProbe] 要求证据**出现并且再过
 * [SETTLE_MS] 仍在**，不成就重点一次；两遍都不成就红，而不是带着一个假"已确认"往下走。
 */
private fun UiDevice.ensurePage(segment: String, evidence: String, probe: () -> Boolean) {
    var reason = "从没点过分段「$segment」"
    repeat(PAGE_ATTEMPTS) {
        if (tapLowest(inText(segment)) == null) {
            reason = "分段「$segment」（HomeScreen.kt:421 的 options）在 dump 里没有文本节点"
        } else if (settledProbe(probe)) {
            return
        } else {
            reason = "点了「$segment」之后 dump 里没有稳定出现 $evidence"
        }
    }
    throw IllegalStateException(
        "无法站到「$segment」这一页：$reason。起点页确认不了就不该继续 —— " +
            "后面的每一跳都会点在已经不存在的锚点上，整轮采集静默空跑，" +
            "产出一份看着成功其实没覆盖交互的 profile"
    )
}

/** 证据出现，并且再多等 [SETTLE_MS] 之后仍然存在 */
private fun UiDevice.settledProbe(probe: () -> Boolean): Boolean {
    if (!probeWithin(PAGE_TIMEOUT_MS, probe)) return false
    Thread.sleep(SETTLE_MS)
    return probe()
}

/** 超时仍然没有证据的场合抛异常，而不是继续往下走 */
private fun UiDevice.waitUntil(what: String, probe: () -> Boolean) {
    check(probeWithin(CONFIRM_TIMEOUT_MS, probe)) {
        "等 ${CONFIRM_TIMEOUT_MS}ms 也没等到证据：$what —— 这一跳点空了，" +
            "继续走下去就是静默空跑"
    }
}

private fun UiDevice.probeWithin(timeoutMs: Long, probe: () -> Boolean): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        waitForIdle()
        if (probe()) return true
        Thread.sleep(POLL_MS)
    }
    return probe()
}

/** 这一跳必须有节点可点，点不到就红：文案锚点本来就唯一，缺失说明前提变了 */
private fun UiDevice.tapOrThrow(matcher: BySelector, what: String) {
    check(tapLowest(matcher) != null) {
        "$what：这批选择器在这一次的 dump 里一个节点都没命中，这一步会静默空跑"
    }
}

/**
 * 点命中的这批节点里**屏幕位置最低**的那个，点法是坐标点击；返回被点的节点，
 * 一个都没命中时返回 null（**不抛**，让调用方去区分"锚点不存在"与"按钮禁用"）。
 *
 * 为什么必须是坐标点击：真机实测（emulator-5554 / API 36 / release nonMinified）
 * 这个应用**没有任何一个带文案的节点报 clickable=true** —— 底栏三个标签、顶栏
 * 「周课表|今日」、今日页「列表|时间轴」命中的 Text 节点全是 `clickable=false`、
 * `enabled=true`，而 dump 里 12 个 `clickable=true` 的节点 text 与 content-desc
 * **一律为空**（那是可点容器自己的裸 bounds）。Compose 把"字"和"能点的那块"放进了
 * 两个节点，于是 `UiObject2.click()` 这条路对本应用根本不成立：按文案找到的节点点不动，
 * 点得动的那块又没名字可读。上一版 [tapLowest] 里那句 `.filter { it.isEnabled && it.isClickable }`
 * 就是因此让 `openTodayView` 与 `scrollSettings` 两步一次点击都没发生的。
 *
 * 文案/图标节点自己不可点，但它一定**落在**那个可点容器的 bounds 内（文字与图标在
 * Compose 语义树里就是容器的子节点），所以点它的中心 == 点容器。这也是为什么这里
 * 连 `click()` 的返回值都不判：手势真没发出去，下一跳的证据判据会红，而那条消息里
 * 写的是使用者看得懂的东西（"哪个锚点没出现"），不是"injectEvent 返回 false"。
 *
 * 仍然按 centerY 取最低的一个：底栏标签与页内同名文本会撞车（底栏「设置」与设置页
 * 顶栏标题「设置」），而 `findObject` 返回哪一个由遍历顺序决定、不可靠。
 * 只有一个命中时退化为"点它"。
 */
private fun UiDevice.tapLowest(matcher: BySelector): UiObject2? {
    val target = findObjects(matcher)
        // visibleBounds 是空矩形 = 这个节点在屏幕上占不到一个像素，点了也没意义
        .filter { !it.visibleBounds.isEmpty }
        .maxByOrNull { it.visibleBounds.centerY() }
        ?: return null
    val bounds = target.visibleBounds
    click(bounds.centerX(), bounds.centerY())
    waitForIdle()
    return target
}

/**
 * 内容区坐标级滚动：从屏幕 [fromFraction] 高度拖到 [toFraction] 高度。
 *
 * 刻意不依赖 `By.scrollable(true)` —— 首屏上「可滚动」的节点到底有几个（周表容器、
 * 背景层）没法离线确认，选错节点就是白滚一趟。
 *
 * 用 `displayWidth`/`displayHeight`（像素）而不是 `getDisplaySizeDp()`：后者是 dp，
 * 拿去做 swipe 坐标会整体缩掉一个密度倍率，落点就跑进顶栏了。
 *
 * 上下都留开：上界避开顶栏那两行，下界避开悬浮玻璃底栏，这样落点一定在内容区。
 */
private fun UiDevice.swipeContent(fromFraction: Float, toFraction: Float) {
    val x = displayWidth / 2
    swipe(
        x, (displayHeight * fromFraction).toInt(),
        x, (displayHeight * toFraction).toInt(),
        // 拖得慢一点：太快就是一记 fling，列表靠惯性滑过的那几屏不会绑定内容，
        // 也就进不了 profile。
        20,
    )
}

/** 手指上滑 = 内容下滚 */
private fun UiDevice.scrollContentDown() = swipeContent(fromFraction = 0.78f, toFraction = 0.40f)

/** 手指下滑 = 内容上滚 */
private fun UiDevice.scrollContentUp() = swipeContent(fromFraction = 0.40f, toFraction = 0.78f)
