package com.buaa.schedule.domain.importing

/**
 * 「这一轮刷新到底取哪一枚学期」的结论档（T403①，在册 #347）。
 *
 * 三档不许折成 Boolean（本仓硬口径）：「取哪一枚」「点名的那一枚为什么取不到」「压根没有可取的清单」
 * 是三件不同的事，调用点要按档各念一句用户句子。折成一枚布尔就把
 * 「教务本轮没给这一枚学期」（要用户改代码）与「教务没答出学期清单」（要重新登录）压成同一句
 * 「刷新失败」—— 那两档的下一手完全不同，压成一句就是把第二维又丢回静默（同 `SemesterInfoReason` 那一族
 * 在首次导入那一路分五档的理由，只是这里只有三档）。
 */
sealed class RefreshTermChoice {

    /**
     * 本轮就取这一枚。
     *
     * ⚠️ 不变式：[termCode] **恒是** [RefreshTermSelection.choose] 的 `offeredCodes` 里的一枚。
     * 这一格是「用户递进来的串不会绕过清单直接进页面内 fetch」的全部凭据 ——
     * 对照 `data/import/BuaaInPageFetcher.kt` 里那两趟：`fetchSemesterInfo`（裸 :299）有
     * `TERM_CODE_PATTERN` 那枚白名单正则兜着，`fetchSemesterCourses`（裸 :230 那句
     * `"termCode=$termCode&campusCode=$campusCode&type=week&week=$week"`）**没有** ⇒
     * 清单就是这一路唯一的白名单来源，缺了它等于把任意串拼进页面内 JS 的 URL。
     */
    data class Fetch(val termCode: String) : RefreshTermChoice()

    /**
     * 用户点名、而教务这一轮的清单里没有这一枚 ⇒ 一个字都不取、不落库。
     *
     * 为什么必须拒绝、不许"退回服务器那一份接着刷"：退回就是 #347 本体（下拉念 A、库里写 B）。
     * 拒绝至少让用户看见「这一轮没有 A」这一句可执行的话 —— 他手打的那枚框（`ImportScreen` 的
     * 「学期代码」）本来就能改。
     */
    data class NotOffered(val requested: String) : RefreshTermChoice()

    /** 教务压根没答出学期清单（`fetchTermList` 回空）⇒ 没有任何可取的档 */
    data object NoOfferedList : RefreshTermChoice()
}

/**
 * 静默刷新那一路「取哪一枚学期」的全仓唯一判据（T403①，在册 #347）。
 *
 * ## 病
 * 改前 `data/import/BuaaWebSession.kt` 的 `refreshSchedule` 参数表里**没有** termCode 那一格
 * （三枚形参只有 existingStartDate 与 onProgress），体内自己写
 * `terms.firstOrNull { it.selected }?.code ?: terms.firstOrNull()?.code` 取服务器那一份。
 * 用户在首页「学期切换」下拉（`ui/home/HomeScreen.kt` 的 `onTermSelected`）或导入页那枚可手打的
 * 「学期代码」框（`ui/importing/ImportScreen.kt` 的 `onRefresh`）递进来的那一枚码，
 * 只在 `ui/ScheduleViewModel.kt` 落进一枚**显示用**的 StateFlow（`_buaaTermCode`），取数那一趟从头到尾
 * 不认识它 ⇒ 「选 A 刷的却是 B」，确定性错配、不是竞态。
 * 落库那一跳（`replaceSemesterCourses` → `deleteByTermCode` + `insert`）把 B 顶成 id 最大的一行，
 * 而「当前学期 = id 最大那一行」是 `data/repository/ScheduleRepository.kt` 与 `data/local/SemesterDao.kt`
 * 都陈过的口径 ⇒ 屏上也换成 B，下拉与网格是两本不同源的册子，不是一处标签说谎。
 *
 * ## 裁的是什么（四档，次序即优先级）
 * 1. 清单为空 ⇒ [NoOfferedList]。**排在点名判定之前**是因为那一档下「你的代码不在清单里」是一句假话
 *    （清单本身没到），而用户据此能做的那一手（改代码）根本不治因。
 * 2. 没点名（null 或全空白）⇒ [RefreshTermChoice.Fetch] 认服务器标 selected 的那一枚；selected 缺席、
 *    或它自己都不在清单里，才退清单第一枚（沿用改前 `?: terms.firstOrNull()?.code` 那一档，不改口径）。
 *    这一档就是首页菜单「刷新课表」那枚**不递学期**的调用点。
 * 3. 点名且在清单里 ⇒ [RefreshTermChoice.Fetch] 认点名那一枚。
 * 4. 点名而清单里没有 ⇒ [RefreshTermChoice.NotOffered]，**不退回 selected**（见那一档自己的 KDoc）。
 *
 * ## 纯 JVM 边界（本仓硬规则）
 * 零 `android.*`／`androidx.*` import、零时钟、零设备读数、不读盘、不 import `BuaaInPageFetcher`
 * （那是 WebView + Log 的宿主，引进来这一份文件就出不了 JVM 单测）。教务答回来的清单由调用点
 * **投影成 `List<String>` 再递进来**，服务器 selected 那一枚也当参数递进来 —— 内核自己没有能力问任何一扇门。
 * 「返回布尔后调用点各自判一次」也算违反 ⇒ 答案一律是具名档，且全仓只有 `refreshSchedule` 一枚调用点。
 */
object RefreshTermSelection {

    /**
     * @param requested    用户点名要刷的那一枚学期代码（首页下拉的 code / 导入页手打框 trim 后的值）；
     *                     null 或空白 = 没点名 ⇒ 走服务器 selected 那一档
     * @param offeredCodes 教务**这一轮**答回来的学期代码清单（`fetchTermList` 的返回值投影）
     * @param selectedCode 清单里被服务器标成 selected 的那一枚，可为 null
     */
    fun choose(
        requested: String?,
        offeredCodes: List<String>,
        selectedCode: String?,
    ): RefreshTermChoice {
        if (offeredCodes.isEmpty()) return RefreshTermChoice.NoOfferedList
        val asked = requested?.trim() ?: ""
        if (asked.isEmpty()) {
            return RefreshTermChoice.Fetch(
                selectedCode?.takeIf { it in offeredCodes } ?: offeredCodes.first(),
            )
        }
        return if (asked in offeredCodes) {
            RefreshTermChoice.Fetch(asked)
        } else {
            RefreshTermChoice.NotOffered(asked)
        }
    }
}
