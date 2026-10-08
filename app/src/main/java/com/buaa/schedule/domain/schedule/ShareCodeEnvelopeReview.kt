package com.buaa.schedule.domain.schedule

/**
 * 分享口令的**信封判据**（台账 #530 发现 3）：这条口令我读全了没有？
 *
 * ## 病在哪（改前的两行连起来才成立）
 *
 * `data/share/ScheduleShareCodec.kt` 用的是 `Json { ignoreUnknownKeys = true }`，而
 * `data/backup/BackupModels.kt` 里 `BackupData` 的**每一枚**字段都带默认值
 * （`version: Int = BACKUP_FORMAT_VERSION` / 三张表 `= emptyList()`）。于是：
 *
 * - 口令把 `timeSlots` 写成 `slotTimes` ⇒ 模型里 `timeSlots` 静默成空表 ⇒ 递给
 *   `RestoreVolumeGate.capacityViolation` 的 `timeSlotCount = 0`、递给 `decide` 的也是 0
 *   ⇒ 「没预览过而这一趟真要动节次表就要先问一句」那一档（`previewed == false && timeSlotCount > 0`）
 *   连带失效：一次弹窗都不过，屏幕上是一句绿色的「口令导入成功：N 条课程」。
 * - 口令不带 `version` 键 ⇒ 默认成"当前版本" ⇒ `ScheduleViewModel` 那句
 *   `if (data.version > BACKUP_FORMAT_VERSION)` 永远不响（「来自更新版本的应用」那一档说不出口）。
 *
 * ## 判法（第⓪问裁的**乙**档）
 *
 * **不动** `ignoreUnknownKeys`（全仓 app/src/main 六枚 JSON 读者清一色 lenient，
 * 且 `data/backup/BackupCreditCompatTest` 把"新版本加字段老应用必须还能读"当成契约钉着 ——
 * 收紧成甲档就是这两处的反面）。改的是**条数的来路**：条数由调用方从**原始 JSON** 上数好递进来，
 * 不信模型的默认值；本文件只看这些数，判断"读全了没有"。
 * 同族先例：`data/import/TeachingScheduleParser.kt` 同样是 lenient Json，
 * 但它判的是 `json.parseToJsonElement(raw)` 之后的**原始键名**（`obj.entries.firstOrNull { DATE_KEY.matches(it.key) }`），
 * 不是"模型替它兜底出来的那个值"。
 *
 * ## 本文件的规矩
 *
 * 纯 JVM：只吃调用方算好的值（键名清单 / 各表行数 / 版本键在不在与其值）。
 * 不碰 kotlinx、不碰 `BackupData`、不自己 `List.size`、不碰 Room、不读时钟/权限/机型/设备。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 「来自更新版本」「有本应用读不懂的表」「什么表都没带」「读全了」四档要劝的是四种不同的下一步
 * （升级 App / 让对方同版本重导 / 那就是张空课表 / 往下走）。折成 Boolean 就把前三档重新合体，
 * 而合体之后调用点只会说一句「口令无法识别」—— 那正是本卡要拆掉的假话。
 */

/** 闸门要数的三张表（与 [RestoreVolumeGate] 那本册子同一套，不另起名字）。 */
enum class ShareCodeGatedTable {
    Courses,
    TimeSlots,
    Reminders,
    ;

    /** 点名的时候用条数闸门那本册子里的表名，免得第二处再写一遍「课程/节次/提醒」 */
    val capacityTable: OverCapacityTable
        get() = when (this) {
            Courses -> OverCapacityTable.Courses
            TimeSlots -> OverCapacityTable.TimeSlots
            Reminders -> OverCapacityTable.Reminders
        }
}

/**
 * 一张**受闸门管的表**在原始 JSON 里的读数（由调用方数好递进来）。
 *
 * [present] = 口令顶层有没有这枚键；[rows] = 该键的值是数组时的行数，
 * 键在而值不是数组时是 null（这一等的形状不是本应用认得的那一套）。
 */
data class ShareCodeGatedTableRow(
    val slot: ShareCodeGatedTable,
    val wireKey: String,
    val present: Boolean,
    val rows: Int?,
)

/** 口令信封的原始读数清单。 */
data class ShareCodeEnvelopeFacts(
    /** 三枚闸门表各自的读数（**必须三枚都给**，缺一枚就是少递一张表 = 那张表重新零上限） */
    val gatedRows: List<ShareCodeGatedTableRow>,
    /** 认得、且值是数组的其它顶层表（如 `semesters`）的行数；用于「这条口令到底带没带东西」 */
    val knownOtherArrayRows: List<Pair<String, Int>>,
    /** 口令顶层出现、本应用册子里没有的键，且值是数组 ⇒ 十有八九是改了名的表 */
    val unreadableArrayRows: List<Pair<String, Int>>,
    /** 口令顶层出现、本应用册子里没有、值不是数组的键（纯新增的可选项多半在这一档） */
    val unreadableOtherKeys: List<String>,
    /** `version` 这枚键在不在（注意是**在不在**，不是它的默认值） */
    val versionKeyPresent: Boolean,
    /** `version` 的整数值；键不在、或不是整数 ⇒ null（不是整数那一等交给模型解码去拒） */
    val versionCode: Int?,
    /** 本应用当前读得的格式版本（调用方从 `BACKUP_FORMAT_VERSION` 递进来，本文件不复制一份） */
    val currentFormatVersion: Int,
)

sealed interface ShareCodeEnvelopeDecision {

    /**
     * 读全了：三张表的行数是**原始 JSON** 上的行数。
     *
     * [extraUnknownKeys] 非空表示口令还带了本应用不认得的**非数组**键（纯新增的可选项），
     * 按 `ignoreUnknownKeys` 的既有契约照单放行，但这一枚要留在结论里以便留痕。
     */
    data class Understood(
        val counts: RestoreCounts,
        val extraUnknownKeys: List<String>,
        val versionKeyPresent: Boolean,
    ) : ShareCodeEnvelopeDecision

    /** 口令明说自己来自更高的格式版本 ⇒ 与 `data.version > BACKUP_FORMAT_VERSION` 同一句话的口径 */
    data class NewerFormat(val versionCode: Int, val currentVersion: Int) : ShareCodeEnvelopeDecision

    /**
     * 口令里有本应用读不懂的**表**，而它恰好没带本应用认得的某张闸门表 ⇒ 改名的那一等。
     * 条数闸门拿不到真值，一次都不许落库。
     */
    data class UnreadableTables(
        val unreadableArrayKeys: List<String>,
        val unreadableArrayRows: Int,
        val missingGatedTableKeys: List<String>,
        /** 认得的键，但值不是数组（形状换了套） */
        val reshapedKnownKeys: List<String>,
        val counts: RestoreCounts,
    ) : ShareCodeEnvelopeDecision

    /** 口令里一张表都没带（空课表与"全读不懂"之外没有第三种解释） */
    data class NothingNamed(val unknownKeys: List<String>, val versionKeyPresent: Boolean) :
        ShareCodeEnvelopeDecision
}

object ShareCodeEnvelopeReview {

    /**
     * 判据顺序（顺序即语义）：**先问版本，再问读没读全，再问带没带东西，最后才数条数**。
     *
     * 版本排最前：`{"version":3,"courses":[]}` 那种口令，说"来自更新版本的应用"比说
     * "什么表都没带"更贴近用户下一步要做的事（升级 App）。
     *
     * UnreadableTables 必须排在 NothingNamed **之前**：一条"什么表都没带 + 还夹着读不懂的数组键"
     * 的口令，病在"表名读不懂"那一头，句子里必须点名那枚键；先判"什么都没带"就是把它的病因说没了
     * （#530 那一等交回来的正是被清零的条数）。
     */
    fun review(facts: ShareCodeEnvelopeFacts): ShareCodeEnvelopeDecision {
        facts.versionCode?.let { code ->
            if (facts.versionKeyPresent && code > facts.currentFormatVersion) {
                return ShareCodeEnvelopeDecision.NewerFormat(code, facts.currentFormatVersion)
            }
        }
        val counts = RestoreCounts(
            courseCount = facts.gatedRows.firstOrNull { it.slot == ShareCodeGatedTable.Courses }?.rows ?: 0,
            timeSlotCount = facts.gatedRows.firstOrNull { it.slot == ShareCodeGatedTable.TimeSlots }?.rows ?: 0,
            reminderCount = facts.gatedRows.firstOrNull { it.slot == ShareCodeGatedTable.Reminders }?.rows ?: 0,
        )
        val knownRowsTotal = counts.courseCount + counts.timeSlotCount + counts.reminderCount +
            facts.knownOtherArrayRows.sumOf { it.second }
        val missingGated = facts.gatedRows.filter { !it.present }.map { it.wireKey }
        val reshapedKnown = facts.gatedRows.filter { it.present && it.rows == null }.map { it.wireKey }
        val unreadableArrays = facts.unreadableArrayRows
        if (unreadableArrays.isNotEmpty() && (missingGated.isNotEmpty() || knownRowsTotal == 0)) {
            return ShareCodeEnvelopeDecision.UnreadableTables(
                unreadableArrayKeys = unreadableArrays.map { it.first },
                unreadableArrayRows = unreadableArrays.sumOf { it.second },
                missingGatedTableKeys = missingGated,
                reshapedKnownKeys = reshapedKnown,
                counts = counts,
            )
        }
        if (reshapedKnown.isNotEmpty()) {
            return ShareCodeEnvelopeDecision.UnreadableTables(
                unreadableArrayKeys = unreadableArrays.map { it.first },
                unreadableArrayRows = unreadableArrays.sumOf { it.second },
                missingGatedTableKeys = missingGated,
                reshapedKnownKeys = reshapedKnown,
                counts = counts,
            )
        }
        if (knownRowsTotal == 0) {
            // 「什么都没带」有两种解释，分开说：
            // - 三张闸门表的键**都在**而全是空数组 ⇒ 写侧明说"这张表就是空的"（真空课表）→ NothingNamed；
            // - 键压根没出现 ⇒ 那是本仓写侧的形状（`encodeDefaults = false` ⇒ 空表不写键，
            //   `BackupExportContractTest` 逐字钉着"当前版本号等于默认值，序列化时会被省略"），
            //   这条口令只是没带内容而已，交给调用点的空备份那一档说话，这里不许抢。
            return if (missingGated.isEmpty()) {
                ShareCodeEnvelopeDecision.NothingNamed(
                    unknownKeys = facts.unreadableOtherKeys,
                    versionKeyPresent = facts.versionKeyPresent,
                )
            } else {
                ShareCodeEnvelopeDecision.Understood(
                    counts = counts,
                    extraUnknownKeys = facts.unreadableOtherKeys,
                    versionKeyPresent = facts.versionKeyPresent,
                )
            }
        }
        return ShareCodeEnvelopeDecision.Understood(
            counts = counts,
            extraUnknownKeys = facts.unreadableOtherKeys,
            versionKeyPresent = facts.versionKeyPresent,
        )
    }
}

// ─────────────────── 文案（档位与句子同源，抄进 ui 就是第二把尺）───────────────────

private val SHARE_CODE_ENTRY_LABEL = RestoreEntry.ShareCode.label

/** [ShareCodeEnvelopeDecision.NewerFormat]：与 VM 里 `data.version > BACKUP_FORMAT_VERSION` 同一句口径。 */
fun shareCodeNewerFormatCopy(decision: ShareCodeEnvelopeDecision.NewerFormat): String =
    "${SHARE_CODE_ENTRY_LABEL}已拒绝：这条口令来自更新版本的应用（v${decision.versionCode}），" +
        "本应用读到的是 v${decision.currentVersion}。一条都没有写入，你的课程与节次表还是原样 —— " +
        "请先升级本应用，或让对方用同版本 App 重新导出口令"

/**
 * [ShareCodeEnvelopeDecision.UnreadableTables]：点得出哪些表名读不懂、那些表里有多少行、
 * 以及本应用因此没看到哪几张表（改前这一等交回的是 `Valid` + 三枚 0）。
 */
fun shareCodeUnreadableTablesCopy(decision: ShareCodeEnvelopeDecision.UnreadableTables): String =
    "${SHARE_CODE_ENTRY_LABEL}已拒绝：这条口令里有本应用读不懂的表 " +
        "${decision.unreadableArrayKeys.joinToString("/")}" +
        "（共 ${decision.unreadableArrayRows} 行），而它没带本应用认得的 " +
        "${decision.missingGatedTableKeys.joinToString("/")}" +
        (if (decision.reshapedKnownKeys.isNotEmpty())
            "；另外 ${decision.reshapedKnownKeys.joinToString("/")} 的形状不是本应用认得的列表" else "") +
        "。条数闸门拿不到真实行数 ⇒ 一条都没有写入，你的课程与节次表还是原样。" +
        "请让对方用与本应用相同的版本重新导出口令"

/** [ShareCodeEnvelopeDecision.NothingNamed]：可能是真空课表，也可能是整套表名都不认识。 */
fun shareCodeNothingNamedCopy(decision: ShareCodeEnvelopeDecision.NothingNamed): String =
    "${SHARE_CODE_ENTRY_LABEL}已拒绝：这条口令里没有带任何课程、节次或提醒表 ⇒ 没有可恢复的内容，" +
        "一条都没有写入，你的课表还是原样。" +
        (if (decision.unknownKeys.isNotEmpty())
            "它带的表名（${decision.unknownKeys.joinToString("/")}）本应用读不懂；" else "") +
        "对方课表本来就是空的则属正常，否则请让对方重新导出一条完整的口令"
