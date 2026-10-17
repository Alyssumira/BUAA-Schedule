package com.buaa.schedule.domain.importing

/**
 * 教务「按周取课表」那一趟的**外壳形状**分档（T401①）。
 *
 * 纯 JVM 内核：零 `android.*` / `androidx.*`，不读时钟、不读权限、不读设备度量，也**不认识 DTO** ——
 * 三枚事实全部由调用点（`data/import/BuaaInPageFetcher.kt` 的 `fetchSemesterCourses`）从已经解开的
 * 外壳上读好后递进来。这一格是刻意的：闸门必须能在 JVM 里被打表，而打表不许拖着一份 WebView 上下文。
 *
 * ## 这一档表只回答一个问题：「这一周教务**答没答**」，不回答「这一周**有没有课**」
 *
 * 盘上这是两枚互相独立的事实，本档位表把它们分开钉：
 * - `datasPresent`（外壳里有没有 `datas` 那一格）= 服务器**答没答**这一周 ⇒ 这一格是**唯一**的放行判据；
 * - `arrangedListSize`（答了的那一格里有几门课）= 这一周**有没有课** ⇒ **非判据事实**，
 *   本内核一次都不读它。
 *
 * 为什么一次都不许读后者：合法的空课周长的正是「`datas` 在、`arrangedList` 是空表」那一形
 * （寒暑假、停课周，以及台账在册 #310 那一族「该周课全落在作息表之外」）。把空表判成失败会**同时**
 * 改坏两头 —— 连续三周的空课周会拧断 `MAX_CONSECUTIVE_FAILURES` 那道熔断、把剩余周次一律记成失败，
 * 而 `succeeded` 的报数也跟着少。所以这条界线由表驱动单测 `BuaaWeekShellPolicyTest` 逐格钉住：
 * 同一枚 `code` 下 `arrangedListSize` 取 0 / 1 / 7 三格恒给**同一档**。
 *
 * ## 改前的判据为什么挡不住它自己头上那条注释
 *
 * 改前那一行是 `it.datas != null || it.code == null || it.code == "0"`，而同一份文件里的
 * `Json { ignoreUnknownKeys = true }` 加上 DTO 字段的默认值，使得任意**没有** `code` 的 JSON
 * 都解得开、且 `datas` 与 `code` 两枚都是 null —— 于是 `{"code":null,"datas":null}` 与 `{}`
 * 都被第二枚「或」支放行进成功那一支。放行之后给的是「清零连败计数 + 成功数加一 + 往课表里并入一枚
 * 空列表」：那一周既不在失败周次里、也没往课表里放任何一门课，而下游两枚覆盖导入闸门
 * （首次导入那一路读失败周次、静默刷新那一路读 `isComplete`）只看失败周次本物，于是那几周被
 * 「先删该学期再插入」的覆盖导入**静默删掉**，屏上念的是绿色成功。
 *
 * 现在 `code` 只剩下**给档位起名**这一手（[WeekShellTier.ErrorEnvelope] 与
 * [WeekShellTier.ShellWithoutDatas] 分家，供调用点落一行只念本机可见事实的日志），
 * 它**单独放行不了**任何一格。
 */
enum class WeekShellTier {
    /**
     * 外壳里有 `datas` 那一格：教务答了这一周。**唯一可进覆盖导入的一档**。
     *
     * `arrangedList` 是空表也算这一档 —— 那是「这一周确实没课」，不是「这一周没抓到」。
     */
    DataGiven,

    /**
     * 外壳里没有 `datas`，而 `code` 给了明确的非成功值：教务自己答了「这一周不给你数据」
     * （在册注释点名的限流 / 会话半失效那一形）。不可进覆盖导入。
     */
    ErrorEnvelope,

    /**
     * 外壳里没有 `datas`，而 `code` 要么缺省、要么就是成功值：既没数据、也没报错。
     *
     * 改前被放行的正是这一档（`{"code":null,"datas":null}`、`{}`、任意没带 `code` 的 JSON）。
     * 不可进覆盖导入。
     */
    ShellWithoutDatas,
    ;

    /**
     * 这一周能不能进「先清空该学期再写入」的覆盖导入。
     *
     * 只有 [DataGiven]。另两档都意味着「这一周的课表本机没拿到」，记成成功就等于把那一周
     * 既不计进失败周次、也不交回任何一门课 —— 覆盖导入会把既有课程删掉再写回一份没有它的表。
     */
    val importable: Boolean get() = this == DataGiven
}

/**
 * 外壳形状 → 档位的判据本体（全仓只有这一把尺；调用点不许就地复刻，由
 * `BuaaWeekShellImportGateGuardTest` 按源码形状钉着）。
 */
object BuaaWeekShellPolicy {

    /** 教务给「成功」时用的那一枚值：本机只看**等不等**，别的一个字都不猜。 */
    private const val SUCCESS_CODE = "0"

    /**
     * @param datasPresent 外壳里有没有 `datas` 那一格 —— 唯一的放行判据（= 教务答没答这一周）
     * @param arrangedListSize 答了的那一格里 `arrangedList` 的长度；**非判据事实**，本函数一次都不读它，
     *   取 null（那一格本身没给）/ 0（合法的空课周）/ 正数 都不得改变结论
     * @param code 外壳里的 `code`；只用来给「没给 datas」的两档起名，不参与放行判据
     */
    fun tier(datasPresent: Boolean, arrangedListSize: Int?, code: String?): WeekShellTier = when {
        datasPresent -> WeekShellTier.DataGiven
        code != null && code != SUCCESS_CODE -> WeekShellTier.ErrorEnvelope
        else -> WeekShellTier.ShellWithoutDatas
    }
}
