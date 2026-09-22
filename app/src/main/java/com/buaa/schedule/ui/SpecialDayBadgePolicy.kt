package com.buaa.schedule.ui

/**
 * 「这一天该挂哪枚徽标」的判据内核（T62）。纯判据，**零 import**：不碰 android/androidx、
 * 不碰 `java.time`、不读 `MaterialTheme`、也不读时钟。
 *
 * 用户第二轮报的还是同一句「节假日没有标注出来」。第一轮（T60）修的是数据侧——抓不抓、
 * 抓哪几个月、停在哪一档，那一头现在已经能自己说话（`SpecialDays` 那条日志）。
 * 第二轮的真账在渲染侧：`specialDays` 全应用**只有周课表的表头一处**在消费
 * （`WeekView` 的 `specialDaysOfWeek` → 一格一枚字），于是数据就算按时到位了，
 * 用户也只有恰好停在"那一周、那一格、字号没被挤掉"的时候才看得到「休」。
 * 今日页、顶栏那行日期、桌面组件都不认它。
 *
 * 接的界面一多，就会立刻长出第二种病：**「休/班」的判据在两处界面各写一遍**。
 * 所以这一档沿用本仓那条收单硬判据（`ScanRecoveryPolicy` / `SpecialDayRefreshPolicy` 同形）：
 * 判定收进本文件这几个纯函数，界面那侧只递事实、只画结果。日期在这里只是一枚
 * [specialDayDateKey] 算出来的整数键与一个 ISO 星期序数——`LocalDate` 留在调用点，
 * 因为判据自己去碰 `LocalDate.now()` 或 `colorScheme` 的那一刻，下面这些支路
 * 就再也打不了表（而"周末那天到底挂哪枚"这件事恰恰是最需要被钉住的）。
 *
 * ⚠️ 产品口径不变（见 `SpecialDay` 的 KDoc）：**只标注、不参与任何计算**。
 * 本内核答的只是"这一格画不画字、画哪枚字"，周次计算与提醒调度一律不读它。
 */

/** 一年里的第几个工作日算周末：ISO 序数周一=1…周日=7，故 6（周六）起算周末 */
internal const val SpecialDayWeekendFromIsoDow = 6

/**
 * 把年月日压成一枚整数键：`2026-09-25 → 20260925`。
 *
 * 为什么不用 `LocalDate` 当键：那要把 `java.time` 引进判据（本文件的头一条禁令）。
 * 为什么不用字符串 `"2026-09-25"`：三处界面每次都比字符串、每次都在堆上造一枚，
 * 而周表头是一屏七格都在比。十进制压扁还有个附带的好处——它可比大小，
 * 于是"这一天前后有没有别的标注"这类问题将来要加判据时不必再换键的形态。
 *
 * 越界输入（month=13、day=40）不夹取：那种日期本就不存在，压出个怪键也匹配不上任何
 * 真实标注，兜底反而是把脏数据洗成"看起来对"的第二份真相。
 */
internal fun specialDayDateKey(year: Int, month: Int, dayOfMonth: Int): Int =
    year * 10000 + month * 100 + dayOfMonth

/** [SpecialDayWeekendFromIsoDow] 的反面：调用点只交进来 ISO 序数，周末这条判据留在这里 */
internal fun specialDayIsWeekend(isoDayOfWeek: Int): Boolean = isoDayOfWeek >= SpecialDayWeekendFromIsoDow

/**
 * 一条标注在判据这边的形态：日期已经压成键，"是休还是班"仍按数据侧的 `isHoliday` 说。
 *
 * 刻意不复用 `SpecialDay`：那一头带着 `LocalDate` 字段，引它进本文件就等于
 * 顺着字段把 `java.time` 又请回来了。
 */
internal data class SpecialDayMark(
    val dateKey: Int,
    /** true = 节假日（休）；false = 调休上班日（班） */
    val isHoliday: Boolean,
    val note: String? = null,
)

/** 挂哪一枚。文案只在 [label] 这一处定义，界面上不许再出现第二个「休」/「班」字面量 */
internal enum class SpecialDayBadgeKind(val label: String) {
    Holiday("休"),
    Workday("班"),
}

/**
 * 判据的结论：一枚徽标 + 它背后那句话。
 *
 * [note] 可以没有（教务只给了日期与性质）。要不要把这句话摆上界面是版面问题
 * （周表头一格 43dp，摆不下；今日页那一行有地方），所以由调用点决定，
 * 但**措辞的拼法**留在 [specialDayBadgeLabel]，免得三处各拼一次分隔符。
 */
internal data class SpecialDayBadge(val kind: SpecialDayBadgeKind, val note: String?)

/**
 * 这一天挂哪枚徽标；不挂就是 null。
 *
 * 三条支路各自都真出过问题，所以逐条钉：
 *
 * 1. **没有标注** → null。周末尤其不会因为"是周末"就自动获得一枚「休」：
 *    课表本来就不排课，七格里有两天常年挂着「休」只是噪音（同一口径见
 *    `WeekGridWidgetService` 那句"周末不标「无课」"）。
 * 2. **同一日期重复**（同种）→ 只出一枚，文案取**第一条带字**的那句 note。
 *    `SpecialDayCache.loadAll` 已经按月压过一道（后写覆盖），这里兜的是"调用点
 *    把跨月的原始列表直接拼起来"那种形状：不去重，表头与今日页就会挑到不同的名字。
 * 3. **同一日期既休又班** → 看这天本来是不是周末：
 *    - 周末判「班」。调休只发生在周末，而"这周六要上课"是界面上后果最重的一句
 *      ——挂成「休」会让人缺席一次真实存在的课。
 *    - 工作日判「休」。工作日同时被标成补班日，只能是教务那两条数组打架；
 *      这种日子按节假日显示才是安全的读法（顶多白欢喜，不会误上课）。
 *
 * @param dateKey 目标日期，[specialDayDateKey] 算
 * @param marks 全部标注（未去重、未排序都无所谓，本函数自己扫）
 * @param isWeekend 目标日期是不是周六/周日，[specialDayIsWeekend] 算
 */
internal fun specialDayBadgeAt(
    dateKey: Int,
    marks: List<SpecialDayMark>,
    isWeekend: Boolean,
): SpecialDayBadge? {
    val candidates = marks.filter { it.dateKey == dateKey }
    if (candidates.isEmpty()) return null
    val workdays = candidates.filter { !it.isHoliday }
    val holidays = candidates.filter { it.isHoliday }
    val preferWorkday = workdays.isNotEmpty() && (holidays.isEmpty() || isWeekend)
    val kind = if (preferWorkday) SpecialDayBadgeKind.Workday else SpecialDayBadgeKind.Holiday
    val pool = if (preferWorkday) workdays else holidays
    // 空白 note 与没有 note 同义：教务那几个字段里有的是空串，摆到界上就是一个悬空的分隔符
    val note = pool.firstNotNullOfOrNull { mark -> mark.note?.takeIf { it.isNotBlank() } }
    return SpecialDayBadge(kind = kind, note = note)
}

/**
 * 徽标那格到底写什么字。
 *
 * [withNote] 只在版面装得下的那一处开（今日页那一行）；周表头七列每格 ~43dp，
 * 带上"国庆节调休上班"这种七个字就把日期本身挤没了。分隔符只在这里拼一次。
 */
internal fun specialDayBadgeLabel(badge: SpecialDayBadge, withNote: Boolean = false): String {
    val note = badge.note?.takeIf { it.isNotBlank() }
    return if (withNote && note != null) "${badge.kind.label}· $note" else badge.kind.label
}
