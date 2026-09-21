package com.buaa.schedule.widget

import com.buaa.schedule.data.local.WidgetSnapshotEntity
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 一轮快照 sync 的**写**次数：`WidgetDataSynchronizer.sync()` 原先每轮把全学期每个 key 的
 * 快照重查重写一遍（审计 §2.4 收掉的是「节次表查 N 遍」，逐 key 无条件 upsert 那半没动），
 * 而开机、改时间、12 小时兜底、零点这几条路进来时**课表数据一个字都没改**。
 *
 * 现在只写脏的行，判据三条（任一成立才写）：没有凭据 / 载荷哈希不同 / 表里那一行的时间戳
 * 已经不被读侧认账。第三条是本卡唯一不那么显然的一条，它钉的是这条回退链：
 * `WidgetDataCache.get` 对超时快照**不回退读表、而是回退主库并当场补写**
 * （`WidgetDataCache.kt:71-88`），少了它就是把「一轮省 K 次 upsert」换成「每一次组件刷新
 * 都多付 3 趟主库查询 + 1 次补写」，净亏。
 *
 * ⚠️ 全程最高判据是**一个 key 都不许误删**：脏的只能是"哪几行要写"，永远不能脏到
 * "哪几个 key 存在"。`deleteKeysNotIn` 拿的名单、凭据的键集、排产的行集三者必须始终等于
 * 同一个 `keys`（空集合那条 `DELETE ... NOT IN ()` 是 prepare 即语法失败、整批回滚的地雷，
 * 论证抄在 `WidgetDataSynchronizer` 的类注释里）。所以这里既有纯函数级的断言，
 * 也有一条按源码形状核对"名单传的是 `keys` 而不是写集合"的守卫 —— 后者 JVM 跑不到，
 * 只能像 `ColdStartRebuildWiringTest` 那样读 .kt。
 *
 * 测的是 [WidgetDataSynchronizer.planSnapshotWrites] 与 [WidgetDataSynchronizer.planSnapshotRows]
 * 这两个不接 Context、不碰 Room 的入口：`:app` 的 JVM 单测里没有 Robolectric，
 * `sync()` 本体要真库。查了几趟由注入的读函数记数，写了几行由返回的行数记数 ——
 * 就是这两个数，所以钉住它们等于钉住口径。本文件零 `android` import。
 */
class WidgetSnapshotDirtyTest {

    private val timeSlots = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:55", endTime = "09:40"),
    )

    private fun semester(code: String) = Semester(
        termCode = code,
        termName = code,
        startDate = "2026-09-07",
        totalWeeks = 16,
    )

    private fun course(name: String) = Course(
        name = name,
        dayOfWeek = 1,
        periods = listOf(1),
        weeks = listOf(1),
    )

    /** 五个学期：`current` + 四个 termCode，K = 5 */
    private val semesters = listOf(
        semester("2026-2027-1"),
        semester("2026-2027-2"),
        semester("2025-2026-2"),
        semester("2025-2026-1"),
    )

    private fun keysOf(list: List<Semester>): Set<String> =
        LinkedHashSet<String>().apply {
            add("current")
            list.forEach { add(it.termCode) }
        }

    /** 一轮的账：两个读各被调了几趟（节次表记次数、课程记落在哪个学期） */
    private class Reads {
        var timeSlotsCalls = 0
        val coursesQueries = mutableListOf<String?>()
    }

    /** 跑一整轮排产 + 脏判定；[sameDay] 是"写入时刻是否还在今天"，判跨天用 */
    private suspend fun round(
        keys: Set<String>,
        list: List<Semester>,
        current: Semester?,
        courses: (Semester?) -> List<Course>,
        previous: Map<String, SnapshotFingerprint>,
        sameDay: (Long) -> Boolean = { true },
        reads: Reads = Reads(),
    ): Pair<SnapshotWrites, Reads> {
        val rows = WidgetDataSynchronizer.planSnapshotRows(
            keys = keys,
            semesters = list,
            current = current,
            getTimeSlots = {
                reads.timeSlotsCalls += 1
                timeSlots
            },
            getDisplayCourses = { semester ->
                reads.coursesQueries += semester?.termCode
                courses(semester)
            },
        )
        return WidgetDataSynchronizer.planSnapshotWrites(
            rows = rows,
            previous = previous,
            sameLocalDayAsNow = sameDay,
        ) to reads
    }

    private fun coursesOf(semester: Semester?): List<Course> =
        listOf(course(semester?.termCode ?: "全部"))

    /** 上一轮写出去的那份凭据（生产里就是提交成功后记下的那张表） */
    private fun fingerprintsOf(writes: SnapshotWrites): Map<String, SnapshotFingerprint> = writes.fingerprints

    private fun now(): Long = System.currentTimeMillis()

    @Before
    fun resetFingerprints() {
        WidgetDataSynchronizer.clearFingerprintsForTest()
    }

    // ---- 脏判定本体 ----

    @Test
    fun `载荷逐字节相同且仍然新鲜的一轮，一行都不写`() = runBlocking {
        val keys = keysOf(semesters)
        val first = round(keys, semesters, semesters.first(), ::coursesOf, emptyMap()).first
        assertEquals("冷进程第一轮：K 行全写", keys.size, first.rows.size)

        val second = round(keys, semesters, semesters.first(), ::coursesOf, fingerprintsOf(first)).first

        assertEquals("数据未变的一轮写了几行", 0, second.rows.size)
        assertEquals("跳过的正是全部 K 个 key", listOf(keys), listOf(second.skippedKeys.toSet()))
        assertEquals(keys.size, second.skippedKeys.size)
        assertEquals(keys, second.fingerprints.keys)
    }

    @Test
    fun `改一个字段只写那一个 key，current 与它自己的学期一起变`() = runBlocking {
        val current = semesters.first()
        val keys = keysOf(semesters)
        val previous = fingerprintsOf(round(keys, semesters, current, ::coursesOf, emptyMap()).first)

        // 改的是「非当前」那一个学期：只有它这一行的载荷变了
        val dirty = semesters[2].termCode
        val mutations = mutableMapOf<String, List<Course>>()
        fun courses(semester: Semester?): List<Course> =
            mutations[semester?.termCode] ?: coursesOf(semester)

        mutations[dirty] = listOf(course(dirty), course("多出来的一节"))
        assertEquals(listOf(dirty), round(keys, semesters, current, ::courses, previous).first.rows.map { it.key })

        // 再改当前学期：current 那一行与它自己的那一行是两份不同载荷，一起变；
        // 上一轮改过的那个学期这轮内容没动，不该跟着再写一遍
        val again = fingerprintsOf(round(keys, semesters, current, ::courses, previous).first)
        mutations[current.termCode] = listOf(course("换名"))
        val touchedCurrent = round(keys, semesters, current, ::courses, again).first
        assertEquals(
            "current 与当前学期各一行，两行都要写",
            listOf("current", current.termCode),
            touchedCurrent.rows.map { it.key },
        )
    }

    @Test
    fun `凭据缺失的每一种情形都照写，方向只会多写不会少写`() = runBlocking {
        val keys = keysOf(semesters)
        val written = fingerprintsOf(round(keys, semesters, semesters.first(), ::coursesOf, emptyMap()).first)

        // ① 这个 key 头一回出现（凭据里没有）
        assertEquals(
            1,
            round(keys, semesters, semesters.first(), ::coursesOf, written - keys.first()).first.rows.size,
        )
        // ② 载荷哈希不同：上一行写的是别的内容
        val foreign = written.toMutableMap()
        val victim = semesters[1].termCode
        foreign[victim] = SnapshotFingerprint(WidgetDataSynchronizer.payloadHashOf("{}"), now())
        assertEquals(listOf(victim), round(keys, semesters, semesters.first(), ::coursesOf, foreign).first.rows.map { it.key })
        // ③ 凭据在、内容也一样，但表里那一行已经不被读侧认账 —— 见下面三条时钟用例
    }

    @Test
    fun `新鲜度判据的边界与读侧同口径`() {
        val ttl = WidgetDataSynchronizer.SNAPSHOT_FRESH_MILLIS
        val now = 1_800_000_000_000L
        fun fresh(writtenAt: Long, sameDay: Boolean) = WidgetDataSynchronizer.snapshotStampStillFresh(
            writtenAt = writtenAt,
            nowMillis = now,
            sameLocalDayAsNow = sameDay,
        )
        assertTrue(fresh(now, true))
        assertTrue("差一毫秒到窗口边上也还认账", fresh(now - ttl, true))
        assertFalse("超一毫秒就不认账", fresh(now - ttl - 1, true))
        assertFalse("跨天不认账（读侧同一句判据）", fresh(now, false))
        assertFalse("时钟回拨到未来，宁可重写也不认", fresh(now + 1, true))
    }

    @Test
    fun `超出读侧认账窗口的一轮照旧全写，否则组件每次读都回退主库`() = runBlocking {
        val keys = keysOf(semesters)
        val ttl = WidgetDataSynchronizer.SNAPSHOT_FRESH_MILLIS
        val hash = fingerprintsOf(round(keys, semesters, semesters.first(), ::coursesOf, emptyMap()).first)
            .mapValues { it.value.payloadHash }

        assertEquals(
            "超时十分钟：一行都跳不过",
            keys.size,
            round(keys, semesters, semesters.first(), ::coursesOf, stampAll(hash, now() - ttl - MINUTE)).first.rows.size,
        )
        assertEquals(
            "窗口内留十分钟余量：一行都写得没必要",
            0,
            round(keys, semesters, semesters.first(), ::coursesOf, stampAll(hash, now() - ttl + MINUTE)).first.rows.size,
        )
    }

    private fun stampAll(hashes: Map<String, String>, writtenAt: Long): Map<String, SnapshotFingerprint> =
        hashes.mapValues { SnapshotFingerprint(it.value, writtenAt) }

    @Test
    fun `跨天与时钟回拨都不跳写`() = runBlocking {
        val keys = keysOf(semesters)
        val fresh = fingerprintsOf(round(keys, semesters, semesters.first(), ::coursesOf, emptyMap()).first)

        // 读侧同一天不认账（`isSnapshotFresh` 那一句），组件会回退主库
        assertEquals(keys.size, round(keys, semesters, semesters.first(), ::coursesOf, fresh, sameDay = { false }).first.rows.size)
        // 时钟被回拨：writtenAt 落在"现在"之后，宁可写也不能认
        val ahead = fresh.mapValues { SnapshotFingerprint(it.value.payloadHash, now() + TimeUnit.HOURS.toMillis(1)) }
        assertEquals(keys.size, round(keys, semesters, semesters.first(), ::coursesOf, ahead).first.rows.size)
        // 同一时刻本身仍然新鲜
        assertEquals(0, round(keys, semesters, semesters.first(), ::coursesOf, fresh).first.rows.size)
    }

    @Test
    fun `跳过的那一行沿用旧凭据，不谎报一个没写出去的时刻`() = runBlocking {
        val keys = keysOf(semesters)
        val first = round(keys, semesters, semesters.first(), ::coursesOf, emptyMap()).first
        val stampedAt = now() - TimeUnit.MINUTES.toMillis(3)
        val previous = first.fingerprints.mapValues { SnapshotFingerprint(it.value.payloadHash, stampedAt) }

        val second = round(keys, semesters, semesters.first(), ::coursesOf, previous).first

        assertEquals(0, second.rows.size)
        assertEquals("时刻原样留在旧值上", stampedAt, second.fingerprints.getValue("current").writtenAt)
        assertEquals(first.fingerprints.getValue("current").payloadHash, second.fingerprints.getValue("current").payloadHash)
        // 下一次那行终于要写时，写出去的时刻是新的
        val later = round(keys, semesters, semesters.first(), { listOf(course("改了")) }, fingerprintsOf(second)).first
        assertTrue(later.rows.all { it.updatedAt >= stampedAt })
    }

    // ---- 地雷：一个 key 都不许误删 ----

    @Test
    fun `空课程集与学期被删都不改变 key 集合，凭据键集恒等于排产行集`() = runBlocking {
        // ① 一门课都没有：载荷仍然逐 key 不同（学期字段在），一个 key 都不掉
        val keys = keysOf(semesters)
        val emptyRound = round(keys, semesters, semesters.first(), { emptyList() }, emptyMap()).first
        assertEquals(keys, emptyRound.rows.map { it.key }.toSet())
        assertEquals(keys, emptyRound.fingerprints.keys)
        assertEquals(0, round(keys, semesters, semesters.first(), { emptyList() }, fingerprintsOf(emptyRound)).first.rows.size)

        // ② 学期被删（重命名/删学期）：keys 少一个，那一行既不在写集合、也不在凭据里，
        //    于是它同时被 deleteKeysNotIn 清掉 —— 剩下的 key 一个都不能少
        val shrunk = semesters.drop(1)
        val shrunkKeys = keysOf(shrunk)
        val second = round(shrunkKeys, shrunk, shrunk.first(), ::coursesOf, fingerprintsOf(emptyRound)).first
        assertEquals(shrunkKeys, second.fingerprints.keys)
        assertEquals("被删的学期从凭据里一起消失", setOf(semesters[0].termCode), fingerprintsOf(emptyRound).keys - second.fingerprints.keys)
        assertEquals(shrunkKeys, second.rows.map { it.key }.toSet() + second.skippedKeys.toSet())

        // ③ keys 永不为空：`current` 恒在，所以 deleteKeysNotIn(空集合) 那条地雷走不到
        val noSemester = round(keysOf(emptyList()), emptyList(), null, { emptyList() }, emptyMap()).first
        assertEquals(listOf("current"), noSemester.rows.map { it.key })
        assertEquals(listOf("current"), noSemester.fingerprints.keys.toList())
    }

    @Test
    fun `写集合与保留名单是两码事：跳过的行仍然算在 keys 里`() = runBlocking {
        val keys = keysOf(semesters)
        val previous = fingerprintsOf(round(keys, semesters, semesters.first(), ::coursesOf, emptyMap()).first)
        val untouched = round(keys, semesters, semesters.first(), ::coursesOf, previous).first

        // 本轮一行都没写，但"哪些 key 该活着"这件事与写集合无关：
        // 名单如果跟着 rows 走，这一轮就是 deleteKeysNotIn(空列表) —— 地雷本体
        assertEquals(0, untouched.rows.size)
        assertEquals(keys, untouched.fingerprints.keys)
        assertNotEquals(
            "跳过全部行时写集合是空集，绝不能拿它当 deleteKeysNotIn 的名单",
            keys,
            untouched.rows.map { it.key }.toSet(),
        )
    }

    @Test
    fun `生产上 deleteKeysNotIn 传的仍是 keys，不是写出去的那几行`() {
        val source = File(findMainJavaDir(), SYNCHRONIZER_FILE)
        assertTrue("找不到 ${source.path}：文件挪过家的话这条守卫要跟着改路径", source.isFile)
        val transaction = blockAfter(source.readText(), "val plan = db.withTransaction {")
        assertTrue(
            "deleteKeysNotIn 的名单必须还是 keys.toList()：\n$transaction",
            Regex("deleteKeysNotIn\\(keys\\.toList\\(\\)\\)").containsMatchIn(transaction),
        )
        assertFalse(
            "名单被换成写集合/凭据键集的那一类派生物，空写集合就会把整表判空",
            Regex("deleteKeysNotIn\\((?!keys)").containsMatchIn(transaction),
        )
        assertTrue(
            "凭据只能在事务之后记：中途抛了等于那行没落盘",
            source.readText().indexOf("db.withTransaction {") < source.readText().indexOf("remember(plan.fingerprints)"),
        )
    }

    @Test
    fun `认账窗口与读侧那条 TTL 是同一个表达式`() {
        val mine = literal(File(findMainJavaDir(), SYNCHRONIZER_FILE).readText(), "SNAPSHOT_FRESH_MILLIS")
        val cache = File(findMainJavaDir(), CACHE_FILE)
        assertTrue("找不到 ${cache.path}：读侧那份 TTL 挪过家的话这条守卫要跟着改", cache.isFile)
        val theirs = literal(cache.readText(), "SNAPSHOT_TTL_MILLIS")
        assertEquals(
            "读侧超时就不认快照、回退主库并当场补写：这里比它小是白写、比它大就是把组件推进回退链",
            theirs,
            mine,
        )
        assertEquals("表达式与常量的值也要对得上", WidgetDataSynchronizer.SNAPSHOT_FRESH_MILLIS, evalMillis(mine))
    }

    /** 从 `XXX = 15 * 60_000L` 这一行里抠出那段表达式 */
    private fun literal(source: String, name: String): String =
        Regex("$name = (\\S[^\\n]*)").find(source)?.groupValues?.get(1)?.trim()
            ?: error("$name 改写法了，这条守卫要跟着改")

    private fun evalMillis(expression: String): Long =
        expression.removeSuffix("L").split("*").map { it.trim().replace("_", "").toLong() }.reduce(Long::times)

    // ---- 载荷哈希对比 ----

    @Test
    fun `哈希对逐字节敏感，同样的载荷给出同样的哈希`() {
        val a = """{"courses":[{"name":"高等数学"}],"semester":null}"""
        assertEquals(WidgetDataSynchronizer.payloadHashOf(a), WidgetDataSynchronizer.payloadHashOf(a))
        assertNotEquals(WidgetDataSynchronizer.payloadHashOf(a), WidgetDataSynchronizer.payloadHashOf(a.replace("高等", "线性")))
        assertNotEquals(
            "课程顺序变了也是不同载荷：多写一次而已，方向安全",
            WidgetDataSynchronizer.payloadHashOf("[A,B]"),
            WidgetDataSynchronizer.payloadHashOf("[B,A]"),
        )
        assertEquals("SHA-256 的十六进制长度", 64, WidgetDataSynchronizer.payloadHashOf(a).length)
    }

    // ---- 收益实数：这一轮到底查了几趟、写了几行 ----

    @Test
    fun `五 key 数据未变的一轮改后 5 查 0 写，改前是 5 查 5 写`() = runBlocking {
        val keys = keysOf(semesters)
        val coldReads = Reads()
        val cold = round(keys, semesters, semesters.first(), ::coursesOf, emptyMap(), reads = coldReads)
        val warmReads = Reads()
        val warm = round(keys, semesters, semesters.first(), ::coursesOf, fingerprintsOf(cold.first), reads = warmReads)

        assertEquals("整轮只查一遍节次表", 1, coldReads.timeSlotsCalls)
        assertEquals("每 key 各查一次课程，与写不写无关", keys.size, coldReads.coursesQueries.size)
        assertEquals("改前：K 次 upsert", keys.size, cold.first.rows.size)
        assertEquals("改后（同进程再来一轮）：0 次 upsert", 0, warm.first.rows.size)
        assertEquals("查询侧一次都没多付：凭据在内存里比，不去 SELECT 表", 1, warmReads.timeSlotsCalls)
        assertEquals(keys.size, warmReads.coursesQueries.size)
    }

    // ---- 源码核对工具（抄 ColdStartRebuildWiringTest 的办法）----

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    /** 从 [anchor] 之后第一个 `{` 起配平到对应右括号（含），返回括号内那段 */
    private fun blockAfter(source: String, anchor: String): String {
        val at = source.indexOf(anchor)
        check(at >= 0) { "找不到 $anchor：结构改过家，这条守卫要跟着改" }
        val open = source.indexOf('{', at + anchor.length - 1)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw IllegalStateException("$anchor 的花括号没配平")
    }

    private companion object {
        const val MINUTE = 60_000L
        const val SYNCHRONIZER_FILE = "com/buaa/schedule/widget/WidgetDataSynchronizer.kt"
        const val CACHE_FILE = "com/buaa/schedule/widget/WidgetDataCache.kt"
    }
}
