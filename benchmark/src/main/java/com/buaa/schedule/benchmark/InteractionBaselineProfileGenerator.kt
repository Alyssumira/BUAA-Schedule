package com.buaa.schedule.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 日常交互这一半的 Baseline Profile 场景。
 *
 * 为什么要有这个类：[BaselineProfileGenerator] 只采冷启动那一段（它是唯一带
 * `includeInStartupProfile = true` 的），于是产出的 profile 里只有启动路径上的方法，
 * 用户真正天天做的「翻周表 / 切时间轴 / 看今日 / 滚设置」一个都不在里面。
 * 对本应用这笔账尤其划算 —— 自分发 + 应用内自更新会把系统的安装过滤打回 verify，
 * 而云端 ART profile 是 Google Play 专属通道，我们结构上拿不到，profile 是唯一补偿。
 *
 * 三条写在代码里的取舍：
 *
 * 1. **扫码页与 WebView 导入页不进 profile。** 扫码是 CameraX + MLKit 的路径，
 *    帧回调与解码方法一旦进 profile，冷启动首帧就要为它们付编译成本，而绝大多数
 *    启动根本不会走到那里；导入页的 WebView 更不用 —— 那是系统组件在编译，
 *    ART 也管不着。所以这里刻意不点底栏的「导入」。
 * 2. **每个场景都从 `startActivityAndWait()` 起、在已播种的库上跑。** 生成前必须先
 *    播种（命令见 docs/STATUS.md 的编排者清单）：库里要有一个学期、有多门课，
 *    否则周表是空的、滚不动，采到的 profile 也就是空的。
 * 3. **锚点取不到就退化成坐标级滚动，不猜 text。** 本工程没有任何
 *    `testTagsAsResourceId`，所以 `By.res()` 这条路对 Compose 节点无效；
 *    能用的只有 `By.desc()`（对应 `contentDescription`）与 `By.text()`
 *    （对应 `Text` 节点）。下面每个锚点都注了来源文件行号，能确认的才用；
 *    确认不了的（见 [scrollSettings]）一律走坐标级 swipe 并在 KDoc 里标明。
 *
 * 所有选择器都套 [inDesc]/[inText] 限定包名：连着的设备上前台可能叠着别的东西
 * （输入法、Toast 宿主窗口），不限定包名就会点到系统 UI 上，而那种失败在采集日志里
 * 读起来只像"profile 怎么是空的"。
 */
@RunWith(AndroidJUnit4::class)
class InteractionBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /**
     * 周课表纵向滚动 + 翻周。
     *
     * 周视图的滚动容器是 `WeekView.kt:679` 那个 `verticalScroll(gridScrollState)`，
     * 网格按节次分行、内容比一屏长，所以滚动真的会绑定新行。
     * 翻周走顶栏的「下一周」（`HomeScreen.kt:886` 的 contentDescription）：它
     * `enabled = displayWeek < totalWeeks`，播种的学期处在中间周时才可点，所以选择器
     * 里就带上 `enabled(true)` —— 点不动等于这步跳过，不让整个采集红掉。
     */
    @Test
    fun scrollWeekGrid() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.waitForIdle()

        device.findObject(inDesc(DESC_NEXT_WEEK))?.click()
        device.waitForIdle()

        repeat(SCROLL_STEPS) { device.scrollContentDown() }
        device.scrollContentUp()
    }

    /**
     * 切换时间轴（课次行 ↔ 24 小时时间轴）。
     *
     * 这颗胶囊的两个状态是**同一个节点的同一个 contentDescription 位**在变：
     * `HomeScreen.kt:817` 写的是 `if (timeMode) "时间轴视图" else "课次行视图"`。
     * 所以下面先按「当前态」找到它（两种 desc 各试一次），点一次，再按新的 desc
     * 点回来 —— 来回一趟就把两条路径都采进来了，而且第二跳本身就是第一跳的自检。
     *
     * 切换会整表重组（时间轴模式下每行的高度、刻度都要重算），这是这条 CUJ 的价值所在。
     */
    @Test
    fun toggleTimeAxisMode() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.waitForIdle()

        val before = device.findObject(inDesc(DESC_WEEK_VIEW_PERIOD))
            ?: device.findObject(inDesc(DESC_WEEK_VIEW_TIME))
        before?.click()
        device.waitForIdle()

        // 换到另一头点回来：before 为 null 时这里同样为 null，
        // 不会出现"凭猜测写死一个不存在的锚点然后崩"。
        val after = device.findObject(inDesc(DESC_WEEK_VIEW_TIME))
            ?: device.findObject(inDesc(DESC_WEEK_VIEW_PERIOD))
        after?.click()
        device.waitForIdle()

        repeat(2) { device.scrollContentDown() }
    }

    /**
     * 今日视图。
     *
     * 顶栏那枚分段控件是 `HomeScreen.kt:421` 的 `options = listOf("周课表", "今日")`，
     * 格子文本走 `Text(label)`（`GlassSegmentedControl.kt:254`），所以是 `By.text` 命中。
     * 两段文本各自唯一：「今日」是精确匹配，不会撞上表头那句「今日课表」。
     *
     * 「今日」这一侧的内容是 `DayView.kt` 的 LazyColumn + verticalScroll，
     * 与周视图是两条完全不同的组合路径 —— 这正是它值得单列一个场景的原因。
     * 末尾点回「周课表」，让下一次采集从默认态起步。
     */
    @Test
    fun openTodayView() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.waitForIdle()

        device.tapLowest(inText(LABEL_TODAY_SEGMENT))
        device.waitForIdle()
        repeat(SCROLL_STEPS) { device.scrollContentDown() }

        device.tapLowest(inText(LABEL_WEEK_SEGMENT))
        device.waitForIdle()
    }

    /**
     * 设置页滚动。
     *
     * 入口是底栏的「设置」（`MainActivity.kt:312` 的 navItems，文案取
     * `R.string.tab_settings` = 设置）。之所以用 [tapLowest] 而不是直接 findObject：
     * 进了设置页以后顶栏标题也叫「设置」（`SettingsScreen.kt:354`），
     * 页面上会同时存在两个同名节点，取最靠下的那个才是底栏。
     *
     * 页面本体是 `SettingsScreen.kt:363` 的 `verticalScroll`，一屏装不下全部
     * SettingsGroup，滚动会持续绑定新分组。
     *
     * **锚点待设备侧确认**：分组标题（「学期设置」「课程提醒」「提醒可靠性」…）是
     * `SettingsGroup` 的 title，我没有设备可以核对它们在 uiautomator dump 里到底是
     * 独立 Text 节点还是被并进了外层 GlassSurface，所以这里不拿它们当锚点，
     * 滚动一律走坐标级 swipe。同理，可展开分组那个「展开」/「收起」
     * （`SettingsStack.kt:228`）命中就点、点不到就跳过。
     */
    @Test
    fun scrollSettings() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.waitForIdle()

        device.tapLowest(inText(LABEL_SETTINGS_TAB))
        device.waitForIdle()

        repeat(SCROLL_STEPS) {
            device.scrollContentDown()
            // 遇到可展开的分组就开一下：这条路径会重组整组 item，值得进 profile。
            device.findObject(inDesc(DESC_SETTINGS_EXPAND))?.click()
        }
        repeat(2) { device.scrollContentUp() }

        device.tapLowest(inText(LABEL_SCHEDULE_TAB))
        device.waitForIdle()
    }

    companion object {
        /** 一次采集里滚几屏。再多只是拉长每一轮，重复滚动不再新增待编译方法 */
        private const val SCROLL_STEPS = 4

        // 周课表顶栏那颗模式胶囊的两个状态（同一节点、两个态；HomeScreen.kt:817）
        private const val DESC_WEEK_VIEW_PERIOD = "课次行视图"
        private const val DESC_WEEK_VIEW_TIME = "时间轴视图"

        private const val DESC_NEXT_WEEK = "下一周"                // HomeScreen.kt:886
        private const val DESC_SETTINGS_EXPAND = "展开"             // SettingsStack.kt:228

        private const val LABEL_WEEK_SEGMENT = "周课表"             // HomeScreen.kt:421
        private const val LABEL_TODAY_SEGMENT = "今日"              // HomeScreen.kt:421
        private const val LABEL_SETTINGS_TAB = "设置"               // R.string.tab_settings
        private const val LABEL_SCHEDULE_TAB = "课表"               // R.string.tab_home
    }
}

/**
 * 限定包名 + contentDescription + 只取可用节点。
 *
 * 与 [inText] 同一口径：「下一周」翻到头就是禁用态，而 `click()` 在不可点的节点上
 * 是抛异常、不是返回 false，所以禁用态必须在**选择器**这一层就排除掉。
 */
private fun inDesc(description: String): BySelector =
    By.pkg(PACKAGE_NAME).desc(description).enabled(true)

/**
 * 限定包名 + 文本 + 只取可用节点。
 *
 * `enabled(true)` 是必须的而不是防御性的：底栏与分段控件都可能有禁用态（例如
 * 「下一周」翻到头），而 `UiObject2.click()` 在节点不可点时是抛异常、不是返回 false。
 */
private fun inText(label: String): BySelector =
    By.pkg(PACKAGE_NAME).text(label).enabled(true)

/**
 * 内容区坐标级滚动：从屏幕 [fromFraction] 高度拖到 [toFraction] 高度。
 *
 * 刻意不依赖 `By.scrollable(true)` —— 首屏上「可滚动」的节点到底有几个（周表容器、
 * 背景层）我没法在没有设备的情况下确认，选错节点就是白滚一趟。
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

/**
 * 点命中的这批节点里**屏幕位置最低**的那个。
 *
 * 底栏标签与页面内同名文本会撞车（见 [InteractionBaselineProfileGenerator.scrollSettings]），
 * 而 `findObject` 返回哪一个由遍历顺序决定、不可靠，所以自己按 centerY 挑。
 * 只有一个命中时退化为原行为。
 *
 * 只点 `isClickable` 的那批：采集阶段一次抛异常就把整个 iteration（连同这一轮
 * 已经采到的东西）带崩，宁可不点这一步。
 */
private fun UiDevice.tapLowest(matcher: BySelector) {
    findObjects(matcher)
        .filter { it.isEnabled && it.isClickable }
        .maxByOrNull { it.visibleBounds.centerY() }
        ?.click()
}
