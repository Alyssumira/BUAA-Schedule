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

/** [WidgetDataSynchronizer.load] 的结果：快照内容 + 它的写入时刻（新鲜度判断用） */
data class WidgetSnapshot(val data: WidgetData, val updatedAt: Long)

/**
 * 把主库课表同步为 Widget 独立快照（Room 表）。
 *
 * 数据变化后调用 [sync]，之后 Widget 数据源优先读取快照，减少多个组件
 * 各自查主库课程表造成的重复 IO 与对象装配。
 */
object WidgetDataSynchronizer {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 同步当前学期 + 全部已导入学期。
     *
     * 审计 §2.4 的另一半建议「只写被组件引用的 key」（编码与 upsert 从 N 份降到 1–2 份），
     * 本轮不做，两条取证：
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
     * 写盘量的上界是「学期数 + 1」且整批在一个事务里；一个组件都没放时整条 `refreshWidgets`
     * 已经在 `BackgroundSync.kt:117` 早退。剩下的收益是个位数的 upsert，换不掉上面两条口径。
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
        db.withTransaction {
            planSnapshotRows(
                keys = keys,
                semesters = semesters,
                current = current,
                getTimeSlots = { repository.getTimeSlots() },
                getDisplayCourses = { repository.getDisplayCourses(it) },
            ).forEach { dao.upsert(it) }
            // 已删除的学期（或历史遗留 key）对应的快照行一并清掉，
            // 否则反复换学期会让 widget_snapshots 持续累积无用数据。
            dao.deleteKeysNotIn(keys.toList())
        }
    }

    /**
     * 一轮 sync 的排产：key → 待 upsert 的快照行。
     *
     * [getTimeSlots] 由整轮取一次：节次表全库只有一张（`AppDatabase.kt:28`），与 key 无关，
     * 原先写在 `keys.forEach` 里等于 N 个学期查 N 遍同一张表（审计 §2.4）。
     * 每 key 真正各自要查的只有该学期的课程，那一次没动。
     * 抽成注入两个读的函数，是为了让「查了几遍」这件事能在 JVM 单测里数出来
     * （见 `WidgetSnapshotPlanTest`）。行内容、事务边界、upsert 次数与搬家前逐条一致，
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
        dao.upsert(
            WidgetSnapshotEntity(
                key = key,
                dataJson = json.encodeToString(data.toSnapshotDto()),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

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