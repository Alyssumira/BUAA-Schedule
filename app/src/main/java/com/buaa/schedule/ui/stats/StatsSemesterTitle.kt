package com.buaa.schedule.ui.stats

/**
 * 统计页那行"这是哪一学期"的判据内核（T81）。
 *
 * 零 android import、零时钟与设备读取（本仓口径）：两枚事实 `termName` / `termCode`
 * 由调用点当参数递进来，这里只判"这个值能不能当学期名吹"。
 *
 * ## 为什么要判：两个已知的降级值
 *
 * 1. [com.buaa.schedule.ui.ScheduleViewModel] 的 `buildFallbackSemester` 在教务给了一个
 *    本地还没有学期行的 termCode 时，把 `termName` **写成 termCode**
 *    （ScheduleViewModel.kt:205）—— 于是"学期名"其实是 `2026-2027-1` 这串代码；
 * 2. 设置页在名称留空时写死 `"未命名学期"`（SettingsScreen.kt:461）—— 那是一枚占位，
 *    它不携带任何"哪一学期"的信息。
 *
 * 照抄 `semester.termName` 就会把这两枚当学期名吹出去：前者让用户以为 `2026-2027-1`
 * 是这学期的名字，后者画出一行「学期 未命名学期」的废话。所以这里分三档答，
 * 界面上按档位换措辞 —— **降级不等于该隐身**：学期代码本身正好回答"这是哪一学期"，
 * 只是不许顶着"学期名"的名头出场，所以选"照画但标成学期代码"而不是"整行不画"
 * （不画等于把唯一一个能定位学期的事实也扔了，而这一页连标题都不带学期）。
 *
 * `"未命名学期"` 这一档反过来：代码才是有信息的那一枚，所以退到代码档去画。
 */

/** 那一行该用什么措辞出场 */
internal enum class SemesterTitleKind {
    /** 真有一个名字：直接画名字 */
    Named,

    /** 只有学期代码（termName 与 termCode 同一串，或名称是设置页的占位）：标成"学期代码" */
    CodeOnly,
}

/**
 * @param text 要画的文字（名字本身，或那串学期代码）
 * @param kind 措辞档位：界面上 `CodeOnly` 必须带上"学期代码"三个字
 */
internal data class SemesterTitle(val text: String, val kind: SemesterTitleKind)

/**
 * 设置页在名称留空时写进去的占位名。
 *
 * ⚠️ 这里是**照抄那一处的字面量**、不是引用它：那一枚写在 `SettingsScreen` 的保存回调里
 * （SettingsScreen.kt:461，在 Compose 的 onClick 里），domain/ui-stats 伸手去拿它得反过来
 * 依赖设置页。改那串字面量时这里不跟着改，最坏的退化是"未命名学期"又被当成名字画一次
 * —— 不是假数据，所以留个警告而不是加一条跨页依赖。
 */
internal const val UNNAMED_SEMESTER_PLACEHOLDER = "未命名学期"

/**
 * 这一页该说哪一句、用哪一档措辞；无内容可说时 null（整行不画）。
 *
 * 顺序就是可信度顺序：先看名称是不是压根没有 → 再看它是不是那枚占位 →
 * 最后看它是不是与代码同一串。
 */
internal fun semesterTitleOf(termName: String?, termCode: String?): SemesterTitle? {
    val name = termName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val code = termCode?.trim()?.takeIf { it.isNotEmpty() }
    if (name == UNNAMED_SEMESTER_PLACEHOLDER) {
        return code?.let { SemesterTitle(it, SemesterTitleKind.CodeOnly) }
    }
    return if (code != null && name == code) {
        SemesterTitle(code, SemesterTitleKind.CodeOnly)
    } else {
        SemesterTitle(name, SemesterTitleKind.Named)
    }
}
