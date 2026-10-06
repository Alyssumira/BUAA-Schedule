package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.TimeSlotProfile
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T192①：把「ICS 的一枚钟点该落成第几节」那一判钉成表驱动单测（纯 JVM、零时钟注入面）。
 *
 * 判据本体在 `data/import/IcsSectionPolicy.kt` 的 [resolveIcsPeriod]。病灶是旧
 * `IcsParser.sectionOf` 的最后一行 `return previous?.number ?: 1` —— 早于第一节课的活动
 * （早自习、晨训、7:00 的实验室开放）走到 `previous == null` 那一支被凭空安成**第 1 节**，
 * 于是假节次真的进 `courses`、真的写库、真的上课表；而它自己的注释只授权了
 * 「用最近的前一个节次」，从没授权「凭空造第 1 节」。同族先例判定：T27。
 *
 * 第二枚病（事实 2 的钉）：旧写法的「最近的」用的是 `lastOrNull{}`，那是**列表序**不是时间序，
 * 而这条链上没人保证节次表按 start 升序 —— `data/local/TimeSlotDao.getAll()` 的 SQL 是
 * `ORDER BY number`（节次号升序），`ui/settings/SettingsScreen` 的节次草稿又能逐格自由填
 * `HH:mm`。表 ⑤ 把 DEFAULT 表按四种打乱喂进去，断言结果与升序输入**逐格相同**；
 * 表 ④ 更狠：一张「节次号升序但 start 不升序」的表（就是这条链真实允许的表），
 * 新内核按时间序给第 1 节，旧的列表序写法给第 2 节。
 *
 * 档位（如实写明）：`:app` 的 JVM 测试面只有 `junit`（无 Robolectric、未开 `returnDefaultValues`），
 * 所以本文件钉的是**判据**；调用点是否真的按判据走「整条跳过」由
 * [IcsParserSectionWiringTest]（行为）与 [IcsSectionPolicyGuardTest]（源码核对）钉。
 *
 * 两侧的红臂（本表自己也得是活的）：
 * - 朝**宽**扭一次（把 [IcsPeriodResolution.BeforeFirstSlot] 那一支换回
 *   `previous?.number ?: 1` —— 就是旧形状）⇒ 表 ③ 早于第一节的四格与表 ② 的边界格一起红，
 *   守卫那一层的「病灶不回潮」同步红；
 * - 朝**窄**扭一次（把「取 start 最晚」换成 `firstOrNull` 那种列表序）⇒ 表 ④、表 ⑤ 全红
 *   （这两表就是事实 2 的证据），表 ⑥ 的重叠格也红。
 */
class IcsSectionPolicyTest {

    // ─────────────── ① 判据表：一格一笔，每格写清「为什么」 ───────────────

    @Test
    fun `判据表 每一格都按预期给出结论`() {
        for (cell in table) {
            val actual = resolveIcsPeriod(cell.time, cell.slots)
            assertEquals(
                "格子「${cell.name}」：节次表 = ${describeSlots(cell.slots)}，钟点 = ${cell.time} " +
                    "⇒ 期望 ${cell.expectedLabel}。实际得到 ${describe(actual)} —— ${cell.why}\n" +
                    "这一格红了说明判据朝${cell.mutantDirection}的方向跑掉了",
                cell.expected,
                actual,
            )
        }
    }

    /** 早于第一节课的那一档必须整片站住：这一组就是"朝宽"那一扭的靶子 */
    @Test
    fun `早于第一节课的活动一律不猜节次 不许造出第 1 节`() {
        val beforeFirst = table.filter { it.expected === IcsPeriodResolution.BeforeFirstSlot }
        assertTrue("表里没有 BeforeFirstSlot 那一档的格子了：本守卫就成了空转", beforeFirst.isNotEmpty())
        for (cell in beforeFirst) {
            val actual = resolveIcsPeriod(cell.time, cell.slots)
            assertEquals(
                "「${cell.name}」（钟点 ${cell.time}，第一节 ${cell.slots.minOf { it.start }} 才上课）必须判成 " +
                    "BeforeFirstSlot，实到 ${describe(actual)}。红了就是判据又朝宽跑了一档：" +
                    "旧形状 `previous?.number ?: 1` 在这一等交第 1 节出去，早自习于是被安成第 1 节课、" +
                    "真的写库、真的出现在课表上（本卡的两条承重之一）",
                IcsPeriodResolution.BeforeFirstSlot,
                actual,
            )
        }
    }

    /** 晚于最后一节那一档是**有意的**折算（旧注释授权的就是它）：不许被顺手改成「不猜」 */
    @Test
    fun `晚于最后一节的钟点折进最后一节 这是注释授权过的有意行为`() {
        val folded = table.filter { cell ->
            val expected = cell.expected
            expected is IcsPeriodResolution.Resolved &&
                expected.via == Via.NearestPrevious &&
                cell.slots == defaultSlots
        }
        assertTrue("表里没有「晚于最后一节 ⇒ NearestPrevious」的格子：事实 3 那一格被删了", folded.isNotEmpty())
        for (cell in folded) {
            val expected = cell.expected as IcsPeriodResolution.Resolved
            val actual = resolveIcsPeriod(cell.time, cell.slots)
            assertEquals(
                "「${cell.name}」：${cell.time} 在最后一节（${
                    defaultSlots.last().start
                }–${defaultSlots.last().end}）之后/之间，" +
                    "必须折成 Resolved(${expected.number}, NearestPrevious)，实到 ${describe(actual)}。" +
                    "这一格钉的是旧注释「找不到则用最近的前一个节次」那一等，T192 明令**不改它的方向** —— " +
                    "把它改成 BeforeFirstSlot / NoSlots 会让延自习、晚上 22:30 的班会整条不入库，" +
                    "那是把有意的一等当 bug 顺手改掉",
                expected,
                actual,
            )
        }
    }

    // ─────────────── ② 边界：区间右开 ───────────────

    /** 下课铃那一刻**不**属于上一节：`[start, end)` 的右开必须站着，导出/导入幂等就靠它 */
    @Test
    fun `压在 end 边界上的钟点不算那一节在内 折给最近的前一节`() {
        for (slot in defaultSlots) {
            val actual = resolveIcsPeriod(slot.end, defaultSlots)
            assertEquals(
                "第 ${slot.number} 节的下课时刻 ${slot.end} 不能再算第 ${slot.number} 节「在内」" +
                    "（InsideSlot 的区间是 [start, end) 右开），必须折给最近的前一节，实到 ${describe(actual)}。" +
                    "改成闭区间的话：一节课上到 08:45 的课与 08:45 才下课的活动会在两档里给出不同节次跨度，" +
                    "`IcsExporter` 导出的 DTEND 再导入回来就不再幂等",
                IcsPeriodResolution.Resolved(slot.number, Via.NearestPrevious),
                actual,
            )
            val startHit = resolveIcsPeriod(slot.start, defaultSlots)
            assertEquals(
                "第 ${slot.number} 节的上课铃那一刻 ${slot.start} 必须算第 ${slot.number} 节在内" +
                    "（区间左闭），实到 ${describe(startHit)}",
                IcsPeriodResolution.Resolved(slot.number, Via.InsideSlot),
                startHit,
            )
        }
    }

    // ─────────────── ③ 空表 ───────────────

    /** 表空（未配置且默认表也拿不到、或整表时间都被 mapNotNull 掉）时任何节次号都是编的 */
    @Test
    fun `空节次表给 NoSlots 而且优先于早于第一节的判定`() {
        for (time in listOf(LocalTime.MIDNIGHT, LocalTime.of(7, 30), LocalTime.of(8, 20), LocalTime.of(23, 59))) {
            val actual = resolveIcsPeriod(time, emptyList())
            assertEquals(
                "空表 + 钟点 $time 必须给 NoSlots（实到 ${describe(actual)}）：表里一枚节次都没有，" +
                    "「早于第一节」也无从谈起，两枚结论必须分开 —— 调用点留痕要说得出" +
                    "「是你没配节次表」还是「这活动真赶在第一课之前」，用户能做的事完全不同",
                IcsPeriodResolution.NoSlots,
                actual,
            )
        }
    }

    // ─────────────── ④ 时间序 vs 列表序（事实 2 的钉） ───────────────

    /**
     * 这条链真实允许的表：`TimeSlotDao.getAll()` 按 **number** 升序回，而
     * `SettingsScreen` 的节次草稿逐格自由填 `HH:mm`（校验只有 `isValidTimeSlot`：start < end），
     * 所以「第 1 节 09:00、第 2 节 08:00」是存得进去的 —— 列表序与时间序当场分家。
     */
    @Test
    fun `节次号升序而 start 不升序的表按时间序判 不按列表位置判`() {
        // 三节：number 升序，start 却是 09:00 / 08:00 / 13:00
        val nonMonotonic = listOf(
            IcsSlot(number = 1, start = LocalTime.of(9, 0), end = LocalTime.of(9, 45)),
            IcsSlot(number = 2, start = LocalTime.of(8, 0), end = LocalTime.of(8, 45)),
            IcsSlot(number = 3, start = LocalTime.of(13, 0), end = LocalTime.of(13, 45)),
        )
        val cases = listOf(
            // 12:30 落在两节之外：候选 1(09:00) 与 2(08:00) 里 start 最晚的是**第 1 节**，
            // 而旧写法的 `lastOrNull{}` 按列表序取到的是**第 2 节**（08:00 那枚）—— 分家就在这一格
            LocalTime.of(12, 30) to IcsPeriodResolution.Resolved(1, Via.NearestPrevious),
            LocalTime.of(8, 30) to IcsPeriodResolution.Resolved(2, Via.InsideSlot),
            LocalTime.of(9, 30) to IcsPeriodResolution.Resolved(1, Via.InsideSlot),
            LocalTime.of(13, 30) to IcsPeriodResolution.Resolved(3, Via.InsideSlot),
            LocalTime.of(18, 0) to IcsPeriodResolution.Resolved(3, Via.NearestPrevious),
            // 7:30 早于表里最早的开始（08:00）：列表序写法在这里也会走 null 支
            LocalTime.of(7, 30) to IcsPeriodResolution.BeforeFirstSlot,
        )
        for ((time, expected) in cases) {
            val actual = resolveIcsPeriod(time, nonMonotonic)
            assertEquals(
                "节次号升序 / start 不升序的表（${describeSlots(nonMonotonic)}）+ 钟点 $time 必须给 $expected，" +
                    "实到 ${describe(actual)}。判据若按列表位置取（`firstOrNull` / `lastOrNull`），" +
                    "12:30 那一格会答成第 2 节（列表里最后一个满足 start<=time 的），" +
                    "而「最近的前一节」按时间应当是第 1 节 —— 这就是事实 2 的账",
                expected,
                actual,
            )
        }
    }

    // ─────────────── ⑤ 打乱输入逐格相同（乱序就是这一格的钉） ───────────────

    /** 同一张 DEFAULT 表按四种打乱喂进去，每一枚探针钟点的结论必须与升序输入逐格相同 */
    @Test
    fun `同一张表打乱喂进去 逐格结果与升序输入相同`() {
        val permutations = listOf(
            "整体反转" to defaultSlots.reversed(),
            "旋转五格" to defaultSlots.drop(5) + defaultSlots.take(5),
            "奇偶交错" to defaultSlots.filterIndexed { i, _ -> i % 2 == 1 } +
                defaultSlots.filterIndexed { i, _ -> i % 2 == 0 },
            "首尾互换" to listOf(defaultSlots.last()) + defaultSlots.dropLast(1),
            "按节次号降序" to defaultSlots.sortedByDescending { it.number },
            "按开始时间降序" to defaultSlots.sortedByDescending { it.start },
        )
        val baseline = probeTimes.associateWith { resolveIcsPeriod(it, defaultSlots) }
        val diffs = mutableListOf<String>()
        for ((label, shuffled) in permutations) {
            assertEquals("打乱方式「$label」必须还是那 14 枚节次，一枚不许少", defaultSlots.size, shuffled.size)
            for (time in probeTimes) {
                val expected = baseline.getValue(time)
                val actual = resolveIcsPeriod(time, shuffled)
                if (actual != expected) {
                    diffs += "打乱「$label」× 钟点 $time ⇒ 升序输入给 ${describe(expected)}，打乱给 ${describe(actual)}"
                }
            }
        }
        assertEquals(
            "判据吃了列表顺序（${permutations.size} 种打乱 × ${probeTimes.size} 枚探针钟点里 " +
                diffs.size + " 格不同）：\n  " + diffs.joinToString("\n  ") +
                "\n这条链上没有任何东西保证节次表按 start 升序（TimeSlotDao 的 SQL 是 ORDER BY number，" +
                "SettingsScreen 的节次草稿逐格自由填 HH:mm），所以「按列表位置取」迟早会答错前一节 —— " +
                "T192 的两条承重之一",
            0,
            diffs.size,
        )
    }

    /** 打乱与升序必须真的在探针集合上覆盖到三枚结论，否则上面那一格是空转 */
    @Test
    fun `探针集合自己覆盖到三枚结论 不许空转`() {
        val seen = probeTimes.map { resolveIcsPeriod(it, defaultSlots) }.toSet()
        assertTrue(
            "探针钟点在 DEFAULT 表上必须同时给出 InsideSlot / NearestPrevious / BeforeFirstSlot 三枚结论" +
                "（实到 ${seen.map { describe(it) }}）：少一枚就说明上面两表是空转",
            seen.size >= 3 &&
                seen.any { it is IcsPeriodResolution.Resolved && it.via == Via.InsideSlot } &&
                seen.any { it is IcsPeriodResolution.Resolved && it.via == Via.NearestPrevious } &&
                seen.any { it === IcsPeriodResolution.BeforeFirstSlot },
        )
    }

    // ─────────────── ⑥ 重叠 / 并列 ───────────────

    /** 两枚节次同时罩住这枚钟点时取 **start 最晚**的那一枚（时间序，不看列表序） */
    @Test
    fun `节次表重叠时取 start 最晚的那一枚`() {
        val overlapping = listOf(
            IcsSlot(number = 2, start = LocalTime.of(8, 0), end = LocalTime.of(10, 0)),
            IcsSlot(number = 5, start = LocalTime.of(9, 0), end = LocalTime.of(9, 30)),
        )
        for (slots in listOf(overlapping, overlapping.reversed())) {
            assertEquals(
                "09:10 同时落在第 2 节（8:00–10:00）与第 5 节（9:00–9:30）里 ⇒ 取 start 最晚的第 5 节，" +
                    "与列表怎么排无关（实到 ${
                        describe(resolveIcsPeriod(LocalTime.of(9, 10), slots))
                    }）。按 `firstOrNull` 那种列表序取就会答成第 2 节",
                IcsPeriodResolution.Resolved(5, Via.InsideSlot),
                resolveIcsPeriod(LocalTime.of(9, 10), slots),
            )
        }
    }

    /** 两枚同 start（并列最晚）时取 **number 最小**的一枚：两条判据都同一破法 */
    @Test
    fun `两枚同 start 并列时取节次号最小的一枚 两条判据同一破法`() {
        val tied = listOf(
            IcsSlot(number = 4, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0)),
            IcsSlot(number = 2, start = LocalTime.of(9, 0), end = LocalTime.of(9, 30)),
        )
        for (slots in listOf(tied, tied.reversed())) {
            assertEquals(
                "InsideSlot 那一等：09:10 被两枚同为 09:00 开始的节次罩住，「谁更晚」没有答案，" +
                    "必须按节次号取最小的第 2 节（实到 ${describe(resolveIcsPeriod(LocalTime.of(9, 10), slots))}）：" +
                    "取列表序的第一个就是把刚拆掉的列表依赖请回来",
                IcsPeriodResolution.Resolved(2, Via.InsideSlot),
                resolveIcsPeriod(LocalTime.of(9, 10), slots),
            )
            assertEquals(
                "NearestPrevious 那一等：11:00 两枚都不在内，候选仍是并列的 09:00 两枚 ⇒ 同样取第 2 节" +
                    "（实到 ${describe(resolveIcsPeriod(LocalTime.of(11, 0), slots))}）",
                IcsPeriodResolution.Resolved(2, Via.NearestPrevious),
                resolveIcsPeriod(LocalTime.of(11, 0), slots),
            )
        }
    }

    // ─────────────── ⑦ 无状态：同一枚输入连判两次必须同结论 ───────────────

    @Test
    fun `判据不读时钟也不看状态 同输入连判两次同结论`() {
        for (cell in table) {
            assertEquals(
                "格子「${cell.name}」两次判定不一致 = 判据里有状态（时钟或列表被就地改过）：" +
                    "同一次导入里「跳过 / 不跳过」翻面，用户重试时读到的是另一套结论",
                resolveIcsPeriod(cell.time, cell.slots),
                resolveIcsPeriod(cell.time, cell.slots),
            )
        }
    }

    // ─────────────── 素材 ───────────────

    private fun describe(value: IcsPeriodResolution): String = when (value) {
        is IcsPeriodResolution.Resolved -> "Resolved(${value.number}, ${value.via})"
        IcsPeriodResolution.BeforeFirstSlot -> "BeforeFirstSlot"
        IcsPeriodResolution.NoSlots -> "NoSlots"
    }

    private fun describeSlots(slots: List<IcsSlot>): String =
        if (slots.isEmpty()) "空表" else slots.joinToString(prefix = "[", postfix = "]") { "${it.number}:${it.start}" }

    private data class Cell(
        val name: String,
        val time: LocalTime,
        val slots: List<IcsSlot>,
        val expected: IcsPeriodResolution,
        val why: String,
        val mutantDirection: String,
    ) {
        val expectedLabel: String
            get() = when (expected) {
                is IcsPeriodResolution.Resolved -> "Resolved(${expected.number}, ${expected.via})"
                IcsPeriodResolution.BeforeFirstSlot -> "BeforeFirstSlot"
                IcsPeriodResolution.NoSlots -> "NoSlots"
            }
    }

    private companion object {

        /** 与调用点同一份默认表：`IcsParser.slotTable` 未传节次时退回的就是 TimeSlotProfile.DEFAULT */
        val defaultSlots: List<IcsSlot> = TimeSlotProfile.DEFAULT.map {
            IcsSlot(number = it.number, start = LocalTime.parse(it.startTime), end = LocalTime.parse(it.endTime))
        }

        /** 打乱表与升序表逐格对比时用的探针钟点：每节的 start / start-1 / end / end+1 + 表外三枚 */
        val probeTimes: List<LocalTime> = buildList {
            add(LocalTime.MIDNIGHT)
            add(LocalTime.of(7, 0))
            add(LocalTime.of(7, 59))
            for (slot in defaultSlots) {
                add(slot.start)
                add(slot.start.minusMinutes(1))
                add(slot.end)
                add(slot.end.plusMinutes(1))
            }
            add(LocalTime.of(13, 0))
            add(LocalTime.of(23, 59))
        }.distinct()

        /**
         * 卡面点名的八档全在：正常命中 / 重叠取 start 最晚 / 乱序（另有独立那一格）/
         * 早于第一节 / 晚于最后一节 / 空表 / end 边界（右开）/ 两枚同 start 并列。
         */
        val table: List<Cell> = listOf(
            Cell(
                name = "第一节上课铃",
                time = LocalTime.of(8, 0),
                slots = defaultSlots,
                expected = IcsPeriodResolution.Resolved(1, Via.InsideSlot),
                why = "左闭：08:00 正在第 1 节里",
                mutantDirection = "更严的方向跑掉了（正面命中被判成落不进表）",
            ),
            Cell(
                name = "第一节中段",
                time = LocalTime.of(8, 20),
                slots = defaultSlots,
                expected = IcsPeriodResolution.Resolved(1, Via.InsideSlot),
                why = "最普通的一等：钟点落在 [08:00, 08:45) 内",
                mutantDirection = "更严/更松都不该碰这一等",
            ),
            Cell(
                name = "第六节下午第一节",
                time = LocalTime.of(14, 20),
                slots = defaultSlots,
                expected = IcsPeriodResolution.Resolved(6, Via.InsideSlot),
                why = "午休后的第一节 14:00–14:45，命中第 6 节",
                mutantDirection = "更松的方向跑掉了（午休空档被当成某一节）",
            ),
            Cell(
                name = "第十三节晚间",
                time = LocalTime.of(20, 50),
                slots = defaultSlots,
                expected = IcsPeriodResolution.Resolved(13, Via.InsideSlot),
                why = "20:40–21:25 是第 13 节，正课到晚间同样要正面命中",
                mutantDirection = "更严的方向跑掉了（晚间节次被判落不进表）",
            ),
            Cell(
                name = "第一节下课铃（右开边界）",
                time = LocalTime.of(8, 45),
                slots = defaultSlots,
                expected = IcsPeriodResolution.Resolved(1, Via.NearestPrevious),
                why = "08:45 是第 1 节的 end，右开 ⇒ 不算在内，折给最近的前一节（还是第 1 节，" +
                    "但来路必须是 NearestPrevious）：IcsExporter 写的 DTEND 正是这个时刻，" +
                    "导出一份上到 08:45 的课再导入回来必须还是第 1 节",
                mutantDirection = "闭区间那一头（把 end 当 InsideSlot）",
            ),
            Cell(
                name = "课间空档 13:00",
                time = LocalTime.of(13, 0),
                slots = defaultSlots,
                expected = IcsPeriodResolution.Resolved(5, Via.NearestPrevious),
                why = "12:15 下课到 14:00 上课之间的空档：折给第 5 节，跨节次按开始时间落入的节次处理",
                mutantDirection = "更松的方向跑掉了（空档被造出一节）",
            ),
            Cell(
                name = "最后一节之后 22:20",
                time = LocalTime.of(22, 20),
                slots = defaultSlots,
                expected = IcsPeriodResolution.Resolved(14, Via.NearestPrevious),
                why = "晚于最后一节的钟点折进最后一节 —— 旧注释「找不到则用最近的前一个节次」授权的就是" +
                    "这一等，T192 明令不改它的方向，本格是防后来人顺手改掉的钉",
                mutantDirection = "更严的方向跑掉了（把有意的一等改成 BeforeFirstSlot/NoSlots）",
            ),
            Cell(
                name = "深夜 23:59",
                time = LocalTime.of(23, 59),
                slots = defaultSlots,
                expected = IcsPeriodResolution.Resolved(14, Via.NearestPrevious),
                why = "同上：一天的最后一分钟仍折进第 14 节（判据不读时钟，只看喂进来的钟点）",
                mutantDirection = "更严的方向跑掉了",
            ),
            Cell(
                name = "早自习 7:00",
                time = LocalTime.of(7, 0),
                slots = defaultSlots,
                expected = IcsPeriodResolution.BeforeFirstSlot,
                why = "表里最早的一节 08:00 才开始：旧形状在这里交第 1 节（凭空造出一节），" +
                    "新内核交回「不猜」—— 本卡的两条承重之一",
                mutantDirection = "更松的方向跑掉了（旧 `?: 1` 那一等回潮）",
            ),
            Cell(
                name = "晨训 6:30",
                time = LocalTime.of(6, 30),
                slots = defaultSlots,
                expected = IcsPeriodResolution.BeforeFirstSlot,
                why = "同上，且比第一节早得更多：任何节次号都是编的",
                mutantDirection = "更松的方向跑掉了",
            ),
            Cell(
                name = "零点 00:00",
                time = LocalTime.MIDNIGHT,
                slots = defaultSlots,
                expected = IcsPeriodResolution.BeforeFirstSlot,
                why = "午夜的活动同样早于第一节；这一档以前也会被安成第 1 节，出现在周一第一节的位置上",
                mutantDirection = "更松的方向跑掉了",
            ),
            Cell(
                name = "第一节前一分钟 07:59",
                time = LocalTime.of(7, 59),
                slots = defaultSlots,
                expected = IcsPeriodResolution.BeforeFirstSlot,
                why = "只差一分钟也不给猜：`start <= time` 的节次一枚都没有就是没有",
                mutantDirection = "更松的方向跑掉了（凑整 / 容差一分钟的「抢救」）",
            ),
            Cell(
                name = "空表 × 正课时间",
                time = LocalTime.of(8, 20),
                slots = emptyList(),
                expected = IcsPeriodResolution.NoSlots,
                why = "表空（未配置且默认表也拿不到 / 整表时间被 mapNotNull 掉）时任何节次号都是编的",
                mutantDirection = "更松的方向跑掉了（退回硬编码 14 节或第 1 节）",
            ),
            Cell(
                name = "空表 × 零点",
                time = LocalTime.MIDNIGHT,
                slots = emptyList(),
                expected = IcsPeriodResolution.NoSlots,
                why = "判据顺序即语义：空表先给 NoSlots，不落进「早于第一节」那一档",
                mutantDirection = "更松/顺序被颠倒（NoSlots 被 BeforeFirstSlot 吃掉）",
            ),
            Cell(
                name = "重叠表取 start 最晚",
                time = LocalTime.of(9, 10),
                slots = listOf(
                    IcsSlot(number = 2, start = LocalTime.of(8, 0), end = LocalTime.of(10, 0)),
                    IcsSlot(number = 5, start = LocalTime.of(9, 0), end = LocalTime.of(9, 30)),
                ),
                expected = IcsPeriodResolution.Resolved(5, Via.InsideSlot),
                why = "两枚同时罩住 09:10 ⇒ 取 start 最晚的那一枚（第 5 节，09:00 开始），与列表序无关",
                mutantDirection = "列表序的方向跑掉了（`firstOrNull` 会答第 2 节）",
            ),
            Cell(
                name = "并列同 start",
                time = LocalTime.of(9, 10),
                slots = listOf(
                    IcsSlot(number = 4, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0)),
                    IcsSlot(number = 2, start = LocalTime.of(9, 0), end = LocalTime.of(9, 30)),
                ),
                expected = IcsPeriodResolution.Resolved(2, Via.InsideSlot),
                why = "start 并列时「谁更晚」没有答案 ⇒ 取节次号最小的一枚（课表上排在前面那一节），" +
                    "取列表序的第一个就是把列表依赖请回来",
                mutantDirection = "列表序的方向跑掉了（会答第 4 节）",
            ),
        )
    }
}
