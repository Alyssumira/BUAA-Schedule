package com.buaa.schedule.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.domain.model.SpecialDay
import com.buaa.schedule.ui.SpecialDayBadge
import com.buaa.schedule.ui.SpecialDayBadgeKind
import com.buaa.schedule.ui.SpecialDayMark
import com.buaa.schedule.ui.specialDayBadgeAt
import com.buaa.schedule.ui.specialDayBadgeLabel
import com.buaa.schedule.ui.specialDayDateKey
import com.buaa.schedule.ui.specialDayIsWeekend
import java.time.LocalDate

/**
 * 「休 / 班」徽标的**唯一一处**渲染（T62②）。
 *
 * 以前这三样东西散在 `WeekView` 表头那 16 行里（`badgeOf` 挑字、`isHoliday` 挑色、
 * `padding(start = 2.dp)` 挑位置），于是"今日页要不要标""顶栏要不要标"这两个问题的答案
 * 一直是"再抄一遍"——而抄一遍的结果就是字与色从此分叉（同一枚「休」在两处一个红一个蓝，
 * 用户读成 app 出了 bug，而不是"这里没接上"）。这一档把周表头与今日页页头接进同一个渲染口
 * （顶栏那两行按本卡口径不接，与日头重复），
 * 判据本身（哪一天挂哪枚、冲突怎么取舍）在纯 JVM 的 `SpecialDayBadgePolicy` 里，
 * 本文件只负责两件事：把 `LocalDate` 折成判据要的整数键，以及把结论画出来。
 *
 * 产品口径照旧：**只标注、不计算**（`SpecialDay` 的 KDoc）。这里没有任何一处
 * 反过来影响周次或提醒。
 */

/**
 * 数据侧形态 → 判据侧形态。全应用只做这一处折算：`LocalDate` 到此为止，
 * 判据那头只见整数键（收单硬判据，理由见 `SpecialDayBadgePolicy` 的类注释）。
 */
internal fun specialDayMarksOf(days: List<SpecialDay>): List<SpecialDayMark> =
    days.map { day ->
        SpecialDayMark(
            dateKey = dateKeyOf(day.date),
            isHoliday = day.isHoliday,
            note = day.note,
        )
    }

/** 这一天挂哪枚徽标（null = 不挂）。几处界面问的都是这一个问题，所以只留这一个问法 */
internal fun specialDayBadgeOn(date: LocalDate, marks: List<SpecialDayMark>): SpecialDayBadge? =
    specialDayBadgeAt(
        dateKey = dateKeyOf(date),
        marks = marks,
        isWeekend = specialDayIsWeekend(date.dayOfWeek.value),
    )

/**
 * 摆一枚徽标。[date] 为 null（学期没读到、算不出这一格是哪天）时什么都不画——
 * 与 `WeekView` 以前"没有 weekStartDate 就不画标注"的降级方向一致。
 *
 * @param onAccentSurface 这一格是不是压在主题色底上（周表头的"今天"是填充胶囊，
 *   徽标得换成 `onPrimary` 才看得见）。这是版面事实、不是判据，所以留在调用点。
 * @param showNote 说明文字（如「中秋节」）摆不摆：周表头一格只有 ~43dp，摆就得把日期本身挤没；
 *   今日页那一档由版面内核 [specialDayHeaderSurface] 按实测宽度决定。
 */
@Composable
internal fun SpecialDayBadgeText(
    date: LocalDate?,
    marks: List<SpecialDayMark>,
    modifier: Modifier = Modifier,
    onAccentSurface: Boolean = false,
    showNote: Boolean = false,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    val badge = remember(date, marks) { date?.let { specialDayBadgeOn(it, marks) } } ?: return
    val text = specialDayBadgeLabel(badge, withNote = showNote)
    // 摆不进画面的不是字，是信息：全称被版面省下时交给读屏（「国庆节（节假日）」整句，
    // 休/班两种说法分得开），否则读屏用户永远不知道那个「休」到底是谁。
    // note 本来就没有时 [specialDayHiddenNoteDescription] 回 null，一格语义都不多加。
    val hiddenNote = specialDayHiddenNoteDescription(
        isHoliday = badge.kind == SpecialDayBadgeKind.Holiday,
        note = badge.note.takeIf { !showNote },
    )
    Text(
        text = text,
        style = style,
        fontWeight = FontWeight.Bold,
        color = specialDayBadgeColor(badge.kind, onAccentSurface),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // 字距取 spaceMicro（2dp）——正是从前表头那枚徽标的实测值，改成几处共用之后
        // 把它写死在这里，免得"离日期远了一格"这种微调在各处各调一次
        modifier = if (hiddenNote == null) {
            modifier.padding(start = DesignTokens.spaceMicro)
        } else {
            // 整句走卡上定的口径「国庆节（节假日）」：括号那半截就把休/班分开了，
            // 不再重复屏前那枚字——读屏若把可见文字与描述连读也不会拼出「休 休」
            modifier
                .semantics { contentDescription = hiddenNote }
                .padding(start = DesignTokens.spaceMicro)
        },
    )
}

/**
 * 配色：**休 = `error`、班 = `primary`**，这是 T62 之前就定下的配对（表头那 16 行是唯一实现），
 * 本函数把它从"一处实现"变成"一处定义"。不新造颜色、不写字面色值，全部走 `colorScheme`，
 * 深色/浅色/自定义主题都跟着主题走。
 */
@Composable
internal fun specialDayBadgeColor(kind: SpecialDayBadgeKind, onAccentSurface: Boolean): Color = when {
    onAccentSurface -> MaterialTheme.colorScheme.onPrimary
    kind == SpecialDayBadgeKind.Holiday -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

private fun dateKeyOf(date: LocalDate): Int =
    specialDayDateKey(date.year, date.monthValue, date.dayOfMonth)
