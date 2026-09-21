package com.buaa.schedule.widget

import android.content.Context
import androidx.room.withTransaction
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.local.WidgetSnapshotEntity
import com.buaa.schedule.data.repository.ScheduleRepository
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** [WidgetDataSynchronizer.load] 的结果：快照内容 + 它的写入时刻（新鲜度判断用） */
data class WidgetSnapshot(val data: WidgetData, val updatedAt: Long)

/**
 * 一个 key 上一次真正落进 `widget_snapshots` 的凭据：载荷哈希 + 那一行的写入时刻。
 *
 * 只存哈希不存 JSON：一份全学期课表的 JSON 是 KB 量级，按学期数留全文等于把快照表
 * 在内存里再抄一遍；SHA-256 那 32 字节留着就够判断"这一行的内容我一个字都没改"。
 *
 * [writtenAt] 存的是**当时那一行写进去的 `updatedAt`**，不是"记这条凭据的时间"：
 * 脏判定要复用的正是读侧那条新鲜度规则（见 [snapshotStampStillFresh]），
 * 拿两个时刻会判出"表里那行其实已经被读侧拒收"的假阴性。
 */
internal data class SnapshotFingerprint(val payloadHash: String, val writtenAt: Long)

/** [WidgetDataSynchronizer.planSnapshotWrites] 的结果 */
internal data class SnapshotWrites(
    /** 本轮真的要 upsert 的行：内容变了、或表里那行的时间戳已经不被读侧认账 */
    val rows: List<WidgetSnapshotEntity>,
    /** 本轮跳过的 key（只为日志与单测数得清，不参与任何删除判据） */
    val skippedKeys: List<String>,
    /**
     * 本轮之后每个 key 的凭据。键集**恒等于排产的行集**（= `sync` 里那个 `keys`），
     * 所以被删掉的学期会一并从凭据里消失：表里那行已经被 `deleteKeysNotIn` 清了，
     * 留着凭据就是"同一个学期删了又改回来"时谎报"写过、还新鲜"。
     */
    val fingerprints: Map<String, SnapshotFingerprint>,
)

/**
 * 把主库课表同步为 Widget 独立快照（Room 表）。
 *
 * 数据变化后调用 [sync]，之后 Widget 数据源优先读取快照，减少多个组件
 * 各自查主库课程表造成的重复 IO 与对象装配。
 */
object WidgetDataSynchronizer {

    private val json = Json { ignoreUnknownKeys = true }

    /** 读侧认账的快照窗口，与 `WidgetDataCache.SNAPSHOT_TTL_MILLIS` 同一个数（理由见 [snapshotStampStillFresh]） */
    internal const val SNAPSHOT_FRESH_MILLIS = 15 * 60_000L

    /**
     * 进程内凭据：key → 上一轮真的写进表里的那一行（载荷哈希 + 写入时刻）。
     *
     * 为什么用进程内内存、不去 `SELECT` 表里那行回来比：读回 K 行（K = 学期数 + 1）就把
     * "省下的那次写"换成了 K 趟单行查询，而查询拿到的 `updatedAt` 仍然要和本进程的时钟比，
     * 一分钱不买 —— 而且比不过"表里那行是谁写的都不认、只认自己写过的"这条更硬的口径。
     * 组件的数据读取、快照的补写（[save]）都跑在同一个应用进程里，凭据的作用域正好对得上。
     *
     * 进程重启 / 凭据缺失的后果只有一个：那一轮照旧全量重写，**方向是安全的**（多写不少写）。
     *
     * 反过来"凭据说有、表里其实没有"也可达（系统清空库、导库把 `widget_snapshots` 整表换掉），
     * 后果仍然是安全的：读侧对"表里没有这一行"本来就有那条回退主库 + 当场补写的路
     * （`WidgetDataCache.kt:78-88`），而补写走 [save]、会把凭据一起改对。
     */
    private val lastWritten = ConcurrentHashMap<String, SnapshotFingerprint>()

    /** 本轮凭据整体换出去：键集跟着 `keys` 走，被删的学期从表里和从凭据里一起消失 */
    private fun remember(fingerprints: Map<String, SnapshotFingerprint>) {
        lastWritten.keys.filterNot { it in fingerprints }.forEach { lastWritten.remove(it) }
        lastWritten.putAll(fingerprints)
    }

    /**
     * 同步当前学期 + 全部已导入学期。
     *
     * 每轮仍然给每个 key 排一行（读侧要按整行 JSON 取快照，缺一行就要回退主库），
     * 但**只把脏的行写进 `widget_snapshots`**（见 [planSnapshotWrites]）：
     * 内容与凭据里那份逐字节相同、且表里那一行的时间戳还没过读侧的新鲜期 ⇒ 跳过那次 upsert。
     *
     * 审计 §2.4 的另一半建议「只写被组件引用的 key」（编码与 upsert 从 N 份降到 1–2 份），
     * 本轮**还是**不做，两条取证：
     *
     * 1. **被引用的 key 这一侧拿不到。** [WidgetBindingStore] 全部按 `appWidgetId` 逐实例读写
     *    （`WidgetBindingStore.kt:40-84`），没有任何枚举接口；能枚举的只有
     *    `BackgroundSync.hasAnyWidget` 那种跨 binder 问 Launcher 的 `getAppWidgetIds`
     *    （`BackgroundSync.kt:200-210`），而它对探测失败的既定口径是「按有组件处理，宁可多刷一次」
     *    （`BackgroundSync.kt:212-220`）。拿同一个探测结果当**写入范围**时方向正好反过来：
     *    一次返回空集，本轮就一行都不写，而 `deleteKeysNotIn(空集合)` 铺出来的是
     *    ``DELETE FROM widget_snapshots WHERE `key` NOT IN ()``（Room 按集合大小生成占位符，
     *    没有空集合守卫，`WidgetSnapshotDao.kt:20-21`），prepare 即语法失败、整批事务回滚。
     *    现在 `keys` 恒含 `"current"`，这条路走不到；收窄写集合就把它接通了。
     * 2. **"组件引用了、本轮 keys 不含"是可达状态。** 学期重命名会把旧 termCode 的学期行删掉
     *    （`ScheduleRepository.kt:400`，入口 `ScheduleViewModel.kt:482`），而组件的绑定原样留着
     *    那个字符串（`WidgetBindingStore.kt:49` 从 prefs 读回，只有 `onDeleted` 会清，如
     *    `TodayWidgetProvider.kt:54`）。于是该 key 每轮被 `deleteKeysNotIn` 删掉、再由读侧回退
     *    主库并就地补写（`WidgetDataCache.kt:78-88`）—— 快照表里本来就存着 keys 之外的行，
     *    "keys == 表里全部行"这个前提不成立；反过来 keys 里也常有空学期没人绑。
     *    两个集合互不包含，所以"只写被引用的 key"不是省几份编码，而是要先造一套引用枚举、
     *    再重定 delete 范围（就是第 1 条那个拿不稳的东西），量级完全超出"搬家"。
     *
     * ⚠️ 上面第 1 条对**这一轮的脏判定同样是最高判据**：脏的是"哪几行要写"，
     * 永远不能脏到"哪几个 key 存在"。`keys` 的算法、`deleteKeysNotIn` 拿的那份名单、
     * 以及"每个 key 都要有一行排产"这条不变式，一个字都没动，也不许跟着写集合一起收窄
     * （判据钉在 `WidgetSnapshotDirtyTest` 与 `WidgetSnapshotPlanTest`）。
     *
     * 查询侧的口径不变：每轮 1 趟节次表 + K 趟课程（[planSnapshotRows]），
     * 写侧从 K 次 upsert 降到"变了几行写几行"，删侧仍是那 1 条 `deleteKeysNotIn`。
     */
    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val repository = (appContext as? com.buaa.schedule.BUAAApplication)?.repository
            ?: ScheduleRepository(AppDatabase.getInstance(appContext))
        val db = AppDatabase.getInstance(appContext)
        val dao = db.widgetSnapshotDao()
        val semesters = repository.getAllSemesters()
        val current = repository.getCurrentSemester()

        val keys = LinkedHashSet<String>().apply {
            add("current")
            semesters.forEach { add(it.termCode) }
        }

        // 整批写进一个事务：逐 key 各自隐式事务时，中途进程被杀会留下
        // "一半学期是今天、一半还是昨天"的混合快照，而组件按整行 JSON 读快照，
        // 用户看到的就是两份数据并排（同 ScheduleRepository 批量写 withTransaction 的口径）。
        // 事务边界与改前一致：跳过的行只是少几条语句，不改变"这一轮是一个整体"。
        val plan = db.withTransaction {
            val writes = planSnapshotWrites(
                rows = planSnapshotRows(
                    keys = keys,
                    semesters = semesters,
                    current = current,
                    getTimeSlots = { repository.getTimeSlots() },
                    getDisplayCourses = { repository.getDisplayCourses(it) },
                ),
                previous = lastWritten.toMap(),
                sameLocalDayAsNow = ::sameLocalDayAsNow,
            )
            writes.rows.forEach { dao.upsert(it) }
            // 已删除的学期（或历史遗留 key）对应的快照行一并清掉，
            // 否则反复换学期会让 widget_snapshots 持续累积无用数据。
            // ⚠️ 名单恒为 keys（全部排产 key），不是 writes.rows 里那几行 —— 地雷见类注释第 1 条。
            dao.deleteKeysNotIn(keys.toList())
            writes
        }
        // 提交成功之后才更新凭据：事务里任何一步抛了（含 commit 本身）就等于那一行没落盘，
        // 提前记账会让下一轮把一行"其实不在表里"的快照判成不用写。
        remember(plan.fingerprints)
    }

    /**
     * 表里那一行的写入时刻，此刻还被读侧认账吗 —— 与 `WidgetDataCache.isSnapshotFresh`
     * 同一条判据（同一份 15 分钟窗口 + 同一个"跨天不认账"），因为要防的是同一件事。
     *
     * 为什么脏判定非带上这一段：读侧对超时/跨天的快照**不回退读表而是回退主库并当场补写**
     * （`WidgetDataCache.kt:71-88`）。若只比载荷就跳过写入，一行三天没变的快照会永远留在
     * 超时那一侧，于是每一次组件刷新都要重付一遍主库查询 + 一次补写 —— 那是把
     * "省一次 upsert"换成了"每次读都亏"，净亏。
     *
     * 两份常量之间的耦合是**故意的重复**：[WidgetDataCache] 那个 `SNAPSHOT_TTL_MILLIS` 是
     * private，读不到。它改小、这里改大 ⇒ 白写；它改大、这里改小 ⇒ 少省。
     * 两种都不是功能故障，所以不复用一份常量、由 `WidgetSnapshotDirtyTest` 按源码把这个窗口
     * 与读侧那个钉成同一个数来兜漂移。
     */
    internal fun snapshotStampStillFresh(
        writtenAt: Long,
        nowMillis: Long,
        freshMillis: Long = SNAPSHOT_FRESH_MILLIS,
        sameLocalDayAsNow: Boolean,
    ): Boolean {
        if (writtenAt > nowMillis) return false
        if (nowMillis - writtenAt > freshMillis) return false
        return sameLocalDayAsNow
    }

    /**
     * 一轮排产里哪些行真的需要写。
     *
     * 纯函数：不碰 Context、不碰 Room、不读时钟 —— `now` 就是每行自带的 `updatedAt`
     * （由 [planSnapshotRows] 在排产时落下），"是不是同一天"由调用点算好当参数传进来。
     * 为的是这件事能在 JVM 单测里数得出来（见 `WidgetSnapshotDirtyTest`）：
     * 一轮写几行、什么条件下少写、少写的那几行会不会连累 `deleteKeysNotIn` 的名单。
     *
     * 判据（任一成立即写，全不成立才跳过）：
     * 1. 没有凭据 —— 进程刚起来、这个 key 头一回出现、或上一轮压根没写成；
     * 2. 载荷哈希不同 —— 内容真的变了（逐字节相同才敢不写）；
     * 3. 表里那一行的时间戳已经不被读侧认账 —— 见 [snapshotStampStillFresh]，
     *    不重写就得让组件回退主库，那比这次 upsert 贵得多。
     *
     * 哈希用 SHA-256 而不是 `String.hashCode()`：后者是 32 位，不同载荷撞上就是
     * **少写一行**，组件从此停在旧课表（要到下一次内容变化才自愈）；
     * 前者撞上等于把整学期课表逐字节复原，构造不出来。
     * 误判的方向也要说清：哈希不同而内容相同只会**多写**，那条永远安全。
     */
    internal fun planSnapshotWrites(
        rows: List<WidgetSnapshotEntity>,
        previous: Map<String, SnapshotFingerprint>,
        sameLocalDayAsNow: (Long) -> Boolean,
    ): SnapshotWrites {
        val writes = ArrayList<WidgetSnapshotEntity>(rows.size)
        val skipped = ArrayList<String>(rows.size)
        val next = LinkedHashMap<String, SnapshotFingerprint>(rows.size)
        for (row in rows) {
            val hash = payloadHashOf(row.dataJson)
            val before = previous[row.key]
            if (
                before != null && before.payloadHash == hash && snapshotStampStillFresh(
                    writtenAt = before.writtenAt,
                    nowMillis = row.updatedAt,
                    sameLocalDayAsNow = sameLocalDayAsNow(before.writtenAt),
                )
            ) {
                skipped += row.key
                // 沿用**旧**凭据：那一行的 updatedAt 还停在表里，写成新的就等于
                // 承诺了一个没写出去的时刻，下一次判"还不还新鲜"会判错。
                next[row.key] = before
            } else {
                writes += row
                next[row.key] = SnapshotFingerprint(hash, row.updatedAt)
            }
        }
        return SnapshotWrites(rows = writes, skippedKeys = skipped, fingerprints = next)
    }

    /** 载荷指纹：只吃 [WidgetSnapshotEntity.dataJson]，`updatedAt` 不参与（它每轮都变） */
    internal fun payloadHashOf(dataJson: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(dataJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** 墙钟时刻是否落在与今天同一个日历日 —— 与读侧 `isSnapshotFresh` 那一句同一口径 */
    private fun sameLocalDayAsNow(at: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == LocalDate.now(zone)
    }

    /**
     * 一轮 sync 的排产：key → 待 upsert 的快照行。
     *
     * ⚠️ 一 key 一行，**行集恒等于 keys**。写侧后来只挑其中脏的几行落库，但排产这里
     * 一行都不能少：[SnapshotWrites.fingerprints] 的键集要以它为准，
     * `deleteKeysNotIn` 保留的也正是这一组 key（论证见 [sync] 的类注释）。
     *
     * [getTimeSlots] 由整轮取一次：节次表全库只有一张（`AppDatabase.kt:28`），与 key 无关，
     * 原先写在 `keys.forEach` 里等于 N 个学期查 N 遍同一张表（审计 §2.4）。
     * 每 key 真正各自要查的只有该学期的课程，那一次没动。
     * 抽成注入两个读的函数，是为了让「查了几遍」这件事能在 JVM 单测里数出来
     * （见 `WidgetSnapshotPlanTest`）。行内容、事务边界与搬家前逐条一致，
     * 唯一区别是 N 行 JSON 会同时在内存里存在一会儿，N = 学期数 + 1。
     */
    internal suspend fun planSnapshotRows(
        keys: Set<String>,
        semesters: List<Semester>,
        current: Semester?,
        getTimeSlots: suspend () -> List<TimeSlot>,
        getDisplayCourses: suspend (Semester?) -> List<Course>,
    ): List<WidgetSnapshotEntity> {
        val timeSlots = getTimeSlots()
        return keys.map { key ->
            val semester = if (key == "current") current else semesters.firstOrNull { it.termCode == key }
            val data = WidgetData(
                semester = semester,
                courses = getDisplayCourses(semester),
                timeSlots = timeSlots,
            )
            WidgetSnapshotEntity(
                key = key,
                dataJson = json.encodeToString(data.toSnapshotDto()),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /** 保存单个 key 的快照 */
    suspend fun save(context: Context, key: String, data: WidgetData) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getInstance(appContext).widgetSnapshotDao()
        val writtenAt = System.currentTimeMillis()
        val row = WidgetSnapshotEntity(
            key = key,
            dataJson = json.encodeToString(data.toSnapshotDto()),
            updatedAt = writtenAt,
        )
        dao.upsert(row)
        // 读侧回退主库时就地补写这一行（`WidgetDataCache.kt:87`），表里现在装的**就是**这份内容。
        // 凭据跟着更新，否则下一轮 sync 会拿一份已经过期的哈希去比，白写一次；
        // 更要紧的是别让凭据停在"上一轮写过"上而那一行已被这一行覆盖掉内容。
        lastWritten[key] = SnapshotFingerprint(payloadHashOf(row.dataJson), writtenAt)
    }

    /** 单测用的隔离口：本对象是进程级单例，凭据跨用例残留会让"第一轮该全写"那条断言假绿 */
    internal fun clearFingerprintsForTest() = lastWritten.clear()

    /** 读取某个 key 的快照；不存在或损坏返回 null */
    suspend fun load(context: Context, key: String): WidgetSnapshot? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getInstance(appContext).widgetSnapshotDao()
        val entity = dao.get(key) ?: return@withContext null
        runCatching {
            WidgetSnapshot(
                data = json.decodeFromString<WidgetSnapshotDto>(entity.dataJson).toWidgetData(),
                updatedAt = entity.updatedAt,
            )
        }.getOrNull()
    }
}