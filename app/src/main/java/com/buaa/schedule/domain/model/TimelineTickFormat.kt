package com.buaa.schedule.domain.model

/**
 * 时间轴小时刻度列的文案内核：整点 → `00:00`…`23:00`。
 *
 * 零 android import 是本仓硬边界（判据函数必须能在纯 JVM 上测，设备事实只当参数传进来）：
 * 这一枚不读时钟、不读 locale、不接 Composable，"现在是几点"由调用点量好后注入。
 *
 * ⚠️ 补零只走 `padStart`，不许换回 `"%02d".format(hour)`：Kotlin 里没有 `Locale` 参数的
 * `String.format` 扩展走 `java.util.Formatter` → `Locale.getDefault(Locale.Category.FORMAT)`，
 * 于是数字系统跟着设备 locale 走。JVM 21 码点实测（打印码点不打印字形）：
 * `"%02d"` 喂 8 在 `ar-EG` 出 U+0660 U+0668、在 `fa-IR` 出 U+06F0 U+06F8，
 * 而 `zh-CN`／`en-US`／`de-DE`／`th-TH` 恒为 U+0030 U+0038。
 * 同屏另两路时刻来源是钉死 locale 的（`reminder/ReminderNotifications.kt` 的 `clockOf` 用
 * `DateTimeFormatter.ofPattern("HH:mm", Locale.US)`，`widget/TwoDayWidgetProvider.kt` 的
 * `TWO_DAY_CLOCK` 同理）——混用就是一屏两套数字字形。
 *
 * 这条口径不是这里新立的：成文先例见 `data/local/SpecialDayCache.kt` 的 `fileNameOf`
 * （"用 padStart 而不是 String.format(\"%02d\")，后者受默认 Locale 影响"）与
 * `ui/SpecialDayRefreshPolicy.kt` 的 `specialDayMonthsLabel`（同一句理由）。
 * 时间轴只是这条口径 currently 漏掉的那一处。
 *
 * 量程：调用点只该给 0..23。越界**不夹也不藏**——夹了就等于给时间轴第二把尺子，
 * 界面会画出窗口里根本不存在的一格；这笔账归窗口吸附那一头
 * （见 `ui/home/DayTimelineAxis.kt` 的 `dayTimelineWindow`）。
 */
fun hourTickLabel(hour: Int): String = "${hour.toString().padStart(2, '0')}:00"
