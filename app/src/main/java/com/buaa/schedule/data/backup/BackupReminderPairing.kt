package com.buaa.schedule.data.backup

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.ImportPlanner

/**
 * 备份恢复时「提醒 → 课程行」的配对内核（T229 ②，台账 #303）。
 *
 * ## 它治的是哪一件
 *
 * 提醒在备份里按 [BackupReminder.courseKey]（课程内容签名）寻址，而那把键的**七维里没有 `weeks`**
 * （见 [ImportPlanner.courseKey]）⇒ 同一门课被「这周调课/代课」拆成的多枚片段**共用同一枚键**。
 * 写侧逐条发（每枚片段一条 [BackupReminder]，[com.buaa.schedule.data.repository.ScheduleRepository.updateCoursePartialWeeks]
 * 本来就给拆出来的新行各挂一枚提醒），读侧以前却是两把 `associate` —— **后写吃前写**，
 * 恢复后只有一枚片段领得到提醒：关掉的那半重新响、改过提前量的那半回默认，
 * 而"谁活下来"由两张**无 ORDER BY** 的查询（`ReminderDao.getAll` / `CourseDao.getBySemestersOrManual`）
 * 的行序决定。
 *
 * ## 判据（三条，逐条可测）
 *
 * 1. **一枚都不许多吃、一枚都不许静默少吃**：同键多枚 ⇒ 全部保留、逐枚配对，不再"一个键只落一行"。
 * 2. **配对只看内容与片段依据，不看任何一侧的行序**：备份文件的条目序与数据库返回的行序都不作依据
 *    （两侧各自排成确定次序后再配），所以「重导一次换一枚活下来」那一档在这里结构性地不存在。
 * 3. **每一枚结论自带出处**（[Assignment.basis]）：精确命中 / 片段依据对不上退到键组 / 老备份无依据按组内配对。
 *    退到哪一档都要看得见 —— 本仓口径：兜底不许比判据更安静。
 *
 * ## 纯 JVM
 *
 * 零 android、零时钟、零库：入参只有备份里的设置条目与恢复出来的课程行，两样都由调用点当参数递进来。
 * 表驱动单测在 `data/backup/BackupReminderPairingTest`，接线与形状守卫在
 * `data/backup/BackupReminderPairingGuardTest`。
 */
object BackupReminderPairing {

    /** 备份里的一条提醒设置。[fragmentWeeks] 是 T229 补的「片段依据」，老备份没有这个键 ⇒ null */
    data class Setting(
        val courseKey: String,
        val fragmentWeeks: List<Int>?,
        val enabled: Boolean,
        val advanceMinutes: Int,
    )

    /** 恢复出来的一枚课程行（片段） */
    data class Row(
        val courseId: Long,
        val courseKey: String,
        val weeks: List<Int>,
    )

    /** 这一枚配对是怎么来的 —— 结论必须说得出自己是哪一档 */
    enum class Basis {
        /** 片段依据（周次签名）精确命中那一行：新备份的正常路 */
        FragmentMatched,

        /** 备份带了片段依据，但库里这一组没有周次对得上的行（手改过备份 / 课程被删过）⇒ 退到组内配对 */
        SignatureUnmatched,

        /** 老备份没有片段依据 ⇒ 同键组内逐枚配对（枚数保住，落到哪一行是组内确定序推出来的） */
        LegacyNoSignature,
    }

    data class Assignment(
        val courseId: Long,
        val enabled: Boolean,
        val advanceMinutes: Int,
        val basis: Basis,
    )

    /**
     * @param assignments 按 courseId 升序（**不**按输入行序）
     * @param unplaced    备份里有、库里对不出任何课程行的那些枚（按内容确定序）
     */
    data class Outcome(
        val assignments: List<Assignment>,
        val unplaced: List<Setting>,
    )

    /** 便捷投影：从 [BackupReminder] 取内核的输入（写侧/读侧共用一枚口径，免得两处各自规范化周次） */
    fun settingOf(reminder: BackupReminder): Setting = Setting(
        courseKey = reminder.courseKey,
        fragmentWeeks = reminder.fragmentWeeks?.let { canonicalWeeks(it) },
        enabled = reminder.enabled,
        advanceMinutes = reminder.advanceMinutes,
    )

    /** 便捷投影：从恢复出来的课程行取内核的输入 */
    fun rowOf(course: Course): Row = Row(
        courseId = course.id,
        courseKey = ImportPlanner.courseKey(course),
        weeks = canonicalWeeks(course.weeks),
    )

    /** 周次签名：去重 + 升序。两侧都过这一手，`1,2,3` 与 `3,1,2` 才是同一枚片段 */
    internal fun canonicalWeeks(weeks: List<Int>): List<Int> = weeks.distinct().sorted()

    /** 签名的可比字符串（null ⇒ 一个不等于任何数字串的哨兵，供确定序用） */
    private fun signatureOf(setting: Setting): String =
        setting.fragmentWeeks?.joinToString(",") ?: NO_SIGNATURE

    private fun weeksOf(row: Row): String = row.weeks.joinToString(",")

    private const val NO_SIGNATURE = "\u2205"

    /** 设置侧的确定序：键 → 片段依据 → 开关 → 提前量。**与备份文件里的条目序无关** */
    private val SETTING_ORDER: Comparator<Setting> = compareBy(
        { it.courseKey },
        { signatureOf(it) },
        { if (it.enabled) 1 else 0 },
        { it.advanceMinutes },
    )

    /** 课程行的确定序：周次签名（逐格数值比）→ id。**与查询返回的行序无关** */
    private val ROW_ORDER: Comparator<Row> = Comparator { a, b ->
        val byWeeks = compareWeeks(a.weeks, b.weeks)
        if (byWeeks != 0) byWeeks else a.courseId.compareTo(b.courseId)
    }

    private fun compareWeeks(left: List<Int>, right: List<Int>): Int {
        val shared = minOf(left.size, right.size)
        for (i in 0 until shared) {
            val c = left[i].compareTo(right[i])
            if (c != 0) return c
        }
        return left.size.compareTo(right.size)
    }

    /**
     * 主判据。
     *
     * 进门先各自规范化一次周次（[canonicalWeeks]）：两侧的周次都可能是乱序 / 带重复的
     * （备份可被手改，库里的行也可能来自老版本的展开），规范化之后签名才可比 ——
     * 这一步长在**内核**而不是长在 [settingOf] / [rowOf] 那两枚投影上，
     * 是因为表驱动单测直接构造 [Setting] / [Row]，投影规范化会让内核在真实调用点上"看着对"
     * 而单测里另一套。
     *
     * 两遍：
     * - **第一遍**只认片段依据精确命中（[Basis.FragmentMatched]）。同一枚签名撞上多行（库里
     *   本来就不该有，出现了也如实各领一枚）就把设置发给所有还没被领走的命中行。
     * - **第二遍**把剩下的枚（老备份的、以及签名对不上退档的）按组内确定序**逐枚**发给
     *   还没被领走的行。这一步就是 #303 的直接修法：改前这里是"同键只有一枚设置、只落一行"。
     *
     * 组里行数不够时，多出来的枚进 [Outcome.unplaced]，**不**回头去覆盖已经配上号的行 ——
     * 覆盖就是第二次的"后写吃前写"。
     */
    fun pair(settings: List<Setting>, rows: List<Row>): Outcome {
        val normalizedRows = rows.map { it.copy(weeks = canonicalWeeks(it.weeks)) }
        val normalizedSettings = settings.map { setting ->
            setting.copy(fragmentWeeks = setting.fragmentWeeks?.let(::canonicalWeeks))
        }
        val rowsByKey = normalizedRows.groupBy { it.courseKey }
        val out = LinkedHashMap<Long, Assignment>()
        val unplaced = mutableListOf<Setting>()

        normalizedSettings.groupBy { it.courseKey }.forEach { (key, group) ->
            val candidates = rowsByKey[key].orEmpty().sortedWith(ROW_ORDER)
            if (candidates.isEmpty()) {
                unplaced += group
                return@forEach
            }
            val taken = mutableSetOf<Long>()
            val ordered = group.sortedWith(SETTING_ORDER)
            val signed = ordered.filter { it.fragmentWeeks != null }
            val unsigned = ordered.filter { it.fragmentWeeks == null }

            val signedUnmatched = mutableListOf<Setting>()
            signed.forEach { setting ->
                val signature = signatureOf(setting)
                val hits = candidates.filter { it.courseId !in taken && weeksOf(it) == signature }
                if (hits.isEmpty()) {
                    signedUnmatched += setting
                } else {
                    hits.forEach { row ->
                        taken += row.courseId
                        out[row.courseId] = setting.assignTo(row, Basis.FragmentMatched)
                    }
                }
            }

            (signedUnmatched + unsigned).forEach { setting ->
                val next = candidates.firstOrNull { it.courseId !in taken }
                if (next == null) {
                    unplaced += setting
                } else {
                    taken += next.courseId
                    out[next.courseId] = setting.assignTo(
                        next,
                        if (setting.fragmentWeeks == null) Basis.LegacyNoSignature else Basis.SignatureUnmatched,
                    )
                }
            }
        }

        return Outcome(
            assignments = out.values.sortedBy { it.courseId },
            unplaced = unplaced.sortedWith(SETTING_ORDER),
        )
    }

    private fun Setting.assignTo(row: Row, basis: Basis) = Assignment(
        courseId = row.courseId,
        enabled = enabled,
        advanceMinutes = advanceMinutes,
        basis = basis,
    )
}
