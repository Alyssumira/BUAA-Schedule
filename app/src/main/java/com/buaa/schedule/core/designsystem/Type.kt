package com.buaa.schedule.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * BUAA Schedule 排版刻度。
 *
 * 必须覆盖项目实际用到的全部 10 级，否则未定义的级别会静默回落到 Material 3 默认值，
 * 造成“名义 12sp、实际 11sp”这类排版断链（课程卡节次/地点文字曾因此渲染成 M3 默认 11sp）。
 *
 * 字号 / 行高配对：
 * - 26/34 页面级标题、22/28 区块级标题（原来差 2sp，两级实际可互换 → 审查②V-08）；
 * - 16/24 用于分组标题与正文强调；
 * - 14/20 用于次级标题与常规正文；
 * - 12/16 用于课程卡正文、胶囊与辅助说明。
 *
 * 12sp 这一档同时承担"课程卡正文 / 胶囊标签 / 辅助说明"三级、只靠 400 与 500 字重区分，
 * 是已知的取舍：系统字体下这两档差距很小，1.5x 放大后更分不出。
 * 审查②V-08 给的解法之一是"让 labelSmall 回到 M3 原意的 11sp"，这条路没走：
 * 12sp 这个钉值本身就是上一次"名义 12sp、实际 11sp"断链的修复，再降一档等于把它拆开。
 */
val ScheduleTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // labelMedium 的别名：与它逐字段相同，保留只为钉住 M3 默认的 11sp 静默降级
    // （不定义就会有一路文字悄悄掉到 11sp）。调用点已全部迁到 labelMedium（②V-08），
    // 新代码不要再选它——两个名字一个样式，选哪个纯凭手感就是这么来的。
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
