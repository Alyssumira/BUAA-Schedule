package com.buaa.schedule.widget

import android.content.Context
import androidx.room.withTransaction
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.local.WidgetSnapshotEntity
import com.buaa.schedule.data.repository.ScheduleRepository
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

    /** 同步当前学期 + 全部已导入学期 */
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
            keys.forEach { key ->
                val semester = if (key == "current") current else semesters.firstOrNull { it.termCode == key }
                val data = WidgetData(
                    semester = semester,
                    courses = repository.getDisplayCourses(semester),
                    timeSlots = repository.getTimeSlots(),
                )
                dao.upsert(
                    WidgetSnapshotEntity(
                        key = key,
                        dataJson = json.encodeToString(data.toSnapshotDto()),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            // 已删除的学期（或历史遗留 key）对应的快照行一并清掉，
            // 否则反复换学期会让 widget_snapshots 持续累积无用数据。
            dao.deleteKeysNotIn(keys.toList())
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