package com.buaa.schedule.data.import

/**
 * 「`getTermWeeks` 那一周才是第 1 周」这一判的内核（T193）。
 *
 * 纯判据：零 android import、零时钟、不 parse 日期、不碰 WebView 也不碰数据库。输入是
 * [BuaaWeekRow] 这一枚极简壳（只有 `serialNumber` 与 `startDate` 两个可空字段），
 * **不吃** [com.buaa.schedule.data.import.BuaaTermWeekDto] —— 那是 `@Serializable` 的网络
 * DTO，内核吃它就等于把序列化面拖进判据（多一个字段就得重判一次）。调用点
 * `BuaaInPageFetcher.fetchSemesterInfo` 把 DTO 映射成壳再交进来，拿结论组 `SemesterInfo`。
 *
 * ## 病（本卡登记的三条事实）
 *
 * 改之前那一段（`BuaaInPageFetcher.kt:269-272`）是：
 *
 * ```
 * if (weeks.isEmpty()) return SemesterInfo(20, null)
 * // 第1周（serialNumber 最小 / startDate 最早）的 startDate 即开学日
 * val first = weeks.minByOrNull { it.serialNumber ?: Int.MAX_VALUE } ?: weeks.first()
 * SemesterInfo(weeks.size, first.startDate?.substringBefore(" "))
 * ```
 *
 * 1. 注释承诺**两条**判据（`serialNumber 最小` / `startDate 最早`），代码只实现了第一条。
 * 2. `?: weeks.first()` 是**不可达支**：上一行已经保证 `weeks` 非空，而 `minByOrNull`
 *    在非空集合上必回非 null。这枚从未执行过的兜底掩盖了第 3 条。
 * 3. 真正会走到的退化是 `minByOrNull` 的**并列语义**：当**所有** `serialNumber` 都是 null 时
 *    （`BuaaTermWeekDto.serialNumber: Int?` 与 `startDate: String?` 两枚都可空，接口不保证给号），
 *    每一行的键都算成 `Int.MAX_VALUE` ⇒ 键全部并列 ⇒ `minByOrNull` 回**第一个**最小键的行，
 *    也就是**列表首行**。教务只是没给序号，代码却把它当「第 1 周」用。
 *
 * 后果链：列表首行只要带一枚**合法可 parse** 的 `startDate`，哪怕它其实是第 8 周，
 * 下游两路都拦不住 —— 首次导入那一路 `ui/importing/BuaaLoginScreen.kt:183`
 * 的 `resolveBuaaSemesterStart(info.firstWeekMonday)`（T180 内核）只看**这枚串本身合不合法**，
 * 合法就 `Resolved`，它手里没有第二条来源可比；静默刷新那一路
 * `data/import/BuaaWebSession.kt:400-404` 的 `parse → mondayOf → ?: existingStartDate ?: 失败`
 * 同理，串合法就不走失败支。两边都会把整张课表的日期与周次编号整体偏移，而
 * `BuaaWebSession.kt:384-387` 的 KDoc 自己写着这正是要避免的后果（「写一个猜出来的日期会让
 * 整张课表的日期与周次编号整体偏移…而调用方紧接着就删旧插新，没有回滚路径」）。
 * **旧代码干的就是这一件事，只是猜法从「今天」换成了「列表首行」。**
 *
 * ## 判据（严格这个顺序；逐条对应 `:270` 那句注释承诺的两条）
 *
 * 1. **候选** = `startDate?.trim()?.takeIf { it.isNotEmpty() } != null` 的那些行。
 *    候选为空 ⇒ [BuaaFirstWeek.Undetermined]（[BuaaFirstWeek.UndeterminedReason.NoCandidateStartDate]）。
 *    纯空白 `"   "` 与 `""` 都不进候选 —— 它们是「没给日期」，不是「给了个能用的日期」。
 * 2. 候选里**存在** `serialNumber != null` 的行 ⇒ 按 `serialNumber` 最小取一枚（注释承诺的第一条）。
 *    - **并列 ⇒ Undetermined**（[BuaaFirstWeek.UndeterminedReason.AmbiguousSerialNumber]）：
 *      两行报同一个号就是数据本身坏了，`minByOrNull` 那种「静默取第一个最小键」正是本卡要消灭的猜法。
 *    - **交叉核对**：同一批候选里按 `startDate` 取「最早」的那一枚，若与 2 挑出的**不是同一枚**
 *      ⇒ Undetermined（[BuaaFirstWeek.UndeterminedReason.SourcesDiverge]）。两条来源分家 = 不可信，
 *      让调用点诚实失败；**不许**「信 serialNumber 那条」。这一条是本卡相对旧代码新增的判据，
 *      旧代码只有来源 2、且从不核对。
 * 3. 候选里**所有** `serialNumber` 都是 null ⇒ 按 `startDate` 最早取（注释承诺的第二条，
 *    旧代码从没实现过的那条）。要求「最早」**唯一**：并列 ⇒ Undetermined
 *    （[BuaaFirstWeek.UndeterminedReason.AmbiguousEarliestStartDate]）。
 *    **这一档就是病灶本身**：旧代码在这里回的是列表首行。
 * 4. 返回的串 = 挑中那一行 `startDate.trim().substringBefore(" ")` 的**原样** —— 内核里不 parse、
 *    不 `mondayOf`、不读时钟；认不认得这枚串是 T180 内核 `resolveBuaaSemesterStart` 的事，
 *    两判各管一段（本卡只管「挑哪一行」，那段管「这枚串能不能当锚点」）。
 *
 * ## 为什么串序就够、为什么不需要 parse
 *
 * 教务给的是 `yyyy-MM-dd`（后跟可选时间尾巴）的**定宽**串：四位年、两位月、两位日、高位在前，
 * 于是字典序与时间序**同构** —— 越小的串就是越早的日期。所以本内核只做串比较，不做
 * `LocalDate.parse`：一旦 parse，就得多答一个问题「parse 失败的那一行算不算候选」，而那
 * 等于往「挑第 1 周」这一步里掺进「这枚日期合不合法」的抢救（斜杠、中文年月、补 01），
 * 抢救 = 猜。本卡的口径是把猜的机会全部关掉：能按串序唯一挑出来就交原样，挑不出就
 * [BuaaFirstWeek.Undetermined]，合法性交给下游 T180 那一判，那一判认不出来就不动学期。
 * 比较用的键与最终交出去的那枚串是**同一枚**（都是 `trim().substringBefore(" ")` 的产物），
 * 所以「按哪枚比」与「返回哪枚」之间没有第二种口径可以漂移。
 *
 * ## 「有号有没号混着」那一档选了哪种解（两解同值的交代）
 *
 * 判据 2 的比较面是**带号的那些行**，没号的行不参与「谁最小」的比较。这与旧写法
 * `it.serialNumber ?: Int.MAX_VALUE` 的取值结果等价（把没号算成 `Int.MAX_VALUE` 时，
 * 只要存在至少一枚真号，最小键必然落在带号的行上；两解在「只有一枚非 null」时更是同值）。
 * 选这一种叙述是因为它说实话：没号的行**不当第 1 周**，而不是「没号的行当第 `MAX_VALUE` 周」——
 * 后一种讲法会让人误以为并列那一档真有可能落到没号的行上。
 * 另外，判据 2 里「取到的那枚必须有非空 `startDate`」这一手在本实现里**由构造保证**：候选过滤
 * 已经要求 `trim()` 之后非空，而 `trim()` 过的非空串按空格切出来的前段必非空 —— 所以这里
 * 不存在「挑中一枚空日期」的状态。我没有为它写第二枚 `?:`：本卡登记的三条事实里第 2 条正是
 * 「不可达支掩盖真退化」，再补一枚不可达兜底等于把病灶换个位置重埋一次。
 *
 * ## 证据档位（如实写明）
 *
 * JVM 表驱动单测 `BuaaFirstWeekPolicyTest`（判据两侧各摆格子）+ 源码核对守卫
 * `BuaaFirstWeekPolicyGuardTest`（钉住病灶那一形在 `BuaaInPageFetcher.kt` 0 处、判据唯一落点、
 * 同族写法在全 `app/src/main` 的枚数册子、内核零 android 零时钟、调用点真的读内核产物）。
 * `:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric**，所以「教务真的回一批没号的行时
 * `getTermWeeks` 页面长什么样」这一档**没做**（红线零设备），见回执的未验到清单。
 */
internal sealed interface BuaaFirstWeek {

    /** 用的是哪一条判据（念出来留取证用；对应 `:270` 那句注释承诺的两条） */
    enum class Via {
        /** 判据 2：候选里 `serialNumber` 最小的那一行（并与最早日期交叉核对过） */
        SerialNumber,

        /** 判据 3：候选里所有 `serialNumber` 都没给，按 `startDate` 最早且唯一挑出的那一行 */
        EarliestStartDate,
    }

    /** 判不出第 1 周的那一档（报错消息要能让人看出是哪一档，所以带原因） */
    enum class UndeterminedReason {

        /** 判据 1：没有任何一行带着非空白的 `startDate` */
        NoCandidateStartDate,

        /** 判据 2：最小 `serialNumber` 被两行以上并列占着 */
        AmbiguousSerialNumber,

        /** 判据 2 的交叉核对：序号那条与日期那条分家了 */
        SourcesDiverge,

        /** 判据 3：最早日期被两枚以上不同串并列占着 */
        AmbiguousEarliestStartDate,
    }

    /**
     * 挑得出来。[startDate] = 挑中那一行 `startDate.trim().substringBefore(" ")` 的原样
     * （**没有** parse、**没有**归一成周一、**没有**读时钟），[via] 念出用的是哪条判据。
     */
    data class Resolved(val startDate: String, val via: Via) : BuaaFirstWeek

    /**
     * 挑不出来。**不许**被折算成任何一个日期或任何一行：调用点只能把
     * `SemesterInfo.firstWeekMonday` 回成 null（下游两路各自的诚实失败链已经在位）。
     *
     * [detail] 只用于把实到的形状报进日志（诊断用），不参与任何判定。
     */
    data class Undetermined(val reason: UndeterminedReason, val detail: String) : BuaaFirstWeek
}

/**
 * 判据的入参壳：只带判据用得着的两枚可空字段。
 *
 * 刻意不是 [BuaaTermWeekDto] —— 见文件段首。映射由调用点做（`fetchSemesterInfo` 里
 * `weeks.map { BuaaWeekRow(it.serialNumber, it.startDate) }`），序列化面留在 DTO 那一侧。
 */
internal data class BuaaWeekRow(
    val serialNumber: Int?,
    val startDate: String?,
)

/**
 * 判据本体：一批周次行里哪一枚是第 1 周（判不出就诚实回 [BuaaFirstWeek.Undetermined]）。
 *
 * 全仓**唯一一份**这一判的本体。同族的第二处写法 `BuaaScheduleParser.parseTermWeeks`
 * （`app/src/main/java/com/buaa/schedule/data/import/BuaaScheduleParser.kt:215-217`）普查显示
 * **零生产调用方**（只有 `BuaaTermWeeksParserTest` 在叫），属「死入口」另案，本卡不动它 ——
 * 它的枚数由 `BuaaFirstWeekPolicyGuardTest` 钉在册子上，将来谁再长一枚就红。
 *
 * 判据逐条见 [BuaaFirstWeek] 的段首；次序是「候选 → 序号那条（含交叉核对）→ 日期那条」。
 */
internal fun resolveBuaaFirstWeek(rows: List<BuaaWeekRow>): BuaaFirstWeek {
    // 判据 1：候选 = startDate trim 之后非空的那些行；比较键就是最终要交出去的那枚串
    val candidates = rows.mapNotNull { row ->
        row.startDate?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { Candidate(serialNumber = row.serialNumber, dateKey = it.substringBefore(" ")) }
    }
    if (candidates.isEmpty()) {
        return BuaaFirstWeek.Undetermined(
            BuaaFirstWeek.UndeterminedReason.NoCandidateStartDate,
            "共 ${rows.size} 行，没有一行带着非空白的 startDate",
        )
    }

    // 判据 2：候选里只要有人给了序号，就按序号最小挑（没号的行不参与「谁最小」的比较）
    val numbered = candidates.filter { it.serialNumber != null }
    if (numbered.isNotEmpty()) {
        val smallest = numbered.mapNotNull { it.serialNumber }.min()
        val holders = numbered.filter { it.serialNumber == smallest }
        if (holders.size > 1) {
            return BuaaFirstWeek.Undetermined(
                BuaaFirstWeek.UndeterminedReason.AmbiguousSerialNumber,
                "serialNumber=$smallest 被 ${holders.size} 行并列占着（日期分别是 " +
                    holders.joinToString("、") { "「${it.dateKey}」" } + "）",
            )
        }
        val picked = holders.single()
        // 交叉核对：同一批候选按 startDate 串序取「最早」，与序号那条不是同一枚就不可信
        val earliest = candidates.minOf { it.dateKey }
        if (picked.dateKey != earliest) {
            return BuaaFirstWeek.Undetermined(
                BuaaFirstWeek.UndeterminedReason.SourcesDiverge,
                "serialNumber=$smallest 那行的日期是「${picked.dateKey}」，" +
                    "而候选里最早的一枚是「$earliest」—— 两条来源分家，不许只信其中一条",
            )
        }
        return BuaaFirstWeek.Resolved(picked.dateKey, BuaaFirstWeek.Via.SerialNumber)
    }

    // 判据 3：候选全都没号 ⇒ 走注释承诺的第二条（startDate 最早），且要求「最早」唯一
    val earliest = candidates.minOf { it.dateKey }
    val tied = candidates.filter { it.dateKey == earliest }
    if (tied.size > 1) {
        return BuaaFirstWeek.Undetermined(
            BuaaFirstWeek.UndeterminedReason.AmbiguousEarliestStartDate,
            "${rows.size} 行全无 serialNumber，而最早日期「$earliest」被 ${tied.size} 行并列占着",
        )
    }
    return BuaaFirstWeek.Resolved(earliest, BuaaFirstWeek.Via.EarliestStartDate)
}

/** 候选行：判据用得着的那两枚值。[dateKey] 非空由候选过滤保证，比较键与产物同一枚。 */
private data class Candidate(val serialNumber: Int?, val dateKey: String)
