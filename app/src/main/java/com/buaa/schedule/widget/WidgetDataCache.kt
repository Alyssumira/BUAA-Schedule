package com.buaa.schedule.widget

import android.content.Context
import com.buaa.schedule.data.repository.scheduleRepository
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量 Widget 数据同步层（进程内快照）。
 *
 * 对应拾光 `WidgetDataSynchronizer` 的目标：多个 Widget 在同一轮刷新里避免各自反复查主库。
 * 读取顺序为 进程内短 TTL 快照 → Room 独立快照（[WidgetDataSynchronizer]，带时效与跨天校验）
 * → 主库，回退时顺手把下一层补齐。
 */
data class WidgetData(
    val semester: Semester?,
    val courses: List<Course>,
    val timeSlots: List<TimeSlot>,
)

object WidgetDataCache {

    private const val TTL_MILLIS = 5_000L

    /**
     * Room 快照的可用期限。
     *
     * 快照只在数据变化时由 `WidgetDataSynchronizer.sync()` 重写；一旦某个写路径漏了
     * 同步（或直接改了数据库），无条件优先读快照会让组件一直显示旧课表，
     * 最长拖到 12 小时兜底 Worker。超出期限即回退主库并立刻补写。
     */
    private const val SNAPSHOT_TTL_MILLIS = 15 * 60_000L

    /** 快照是否仍然新鲜：时钟被回拨/前进、或已经跨天时一律不认账。 */
    private fun isSnapshotFresh(at: Long, now: Long): Boolean {
        if (at > now || now - at > SNAPSHOT_TTL_MILLIS) return false
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == LocalDate.now(zone)
    }

    /**
     * 快照条目：写入时间 + 数据打包成**一个不可变对象**，整体发布。
     *
     * 此前是三个独立的 `@Volatile` 字段（cachedKey / cachedAt / cachedData），
     * 三者分三次写。`peek()` 只校验 key 就返回 data，于是存在这条竞态：
     * 刷新线程刚写完 `cachedKey = "2026-1"`、还没写 `cachedData`，
     * 主线程 `peek("2026-1")` 命中 key，拿到的是**上一个学期**的课程列表
     * —— 组件会短暂显示错学期的课表。
     * 单对象发布后，读到的一定是同一轮写入的完整二元组。
     *
     * 槽位按学期编码分开（R5 F-17）：一轮 `refreshWidgets()` 会逐个实例刷新，
     * 绑 `current` 的组件 A 与绑 `2025-2` 的组件 B 共用单槽时，B 的写入会把 A 挤掉，
     * A 随后在主线程 `onDataSetChanged` 里 peek 未命中 → 渲染空态，
     * 且再没有人通知 A → 一直"今天没有课"到零点或 12 小时兜底。
     */
    private data class Entry(val at: Long, val data: WidgetData)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun get(context: Context, semesterCode: String?): WidgetData {
        val key = semesterCode ?: CURRENT_KEY
        val now = System.currentTimeMillis()
        entries[key]?.takeIf { now - it.at < TTL_MILLIS }?.let { return it.data }

        // 优先读 Room 独立快照，避免每次 Widget 刷新都查主库——但只在快照仍然
        // 新鲜时认账（R5 F-26），否则任何漏掉 invalidate() 的写路径都会让组件
        // 一直显示旧课表，最长拖到 12 小时兜底 Worker。
        WidgetDataSynchronizer.load(context, key)?.takeIf { isSnapshotFresh(it.updatedAt, now) }?.let {
            entries[key] = Entry(now, it.data)
            return it.data
        }

        // 快照缺失（首次/数据刚变化未同步）时回退主库，并立即补写快照
        val repository = context.scheduleRepository()
        val semester = semesterCode?.let { repository.getSemesterByTermCode(it) }
            ?: repository.getCurrentSemester()
        val data = WidgetData(
            semester = semester,
            courses = repository.getDisplayCourses(semester),
            timeSlots = repository.getTimeSlots(),
        )
        WidgetDataSynchronizer.save(context, key, data)
        entries[key] = Entry(now, data)
        return data
    }

    /**
     * 非阻塞读取：只取**进程内该学期已就绪**的快照。
     *
     * 给 `RemoteViewsService.RemoteViewsFactory.onDataSetChanged()` 用 —— 那个回调跑在
     * 主线程上，里面 `runBlocking` 查库会直接卡住应用的 UI 线程（组件每次刷新都会触发）。
     * 正常流程里 provider 会先走 [get] 把数据准备好、再 notifyAppWidgetViewDataChanged，
     * 所以这里几乎总能命中；未命中时调用方**不能**画个空态就完事，
     * 必须异步补 [get] 再通知一次（R5 F-17）。
     */
    fun peek(semesterCode: String?): WidgetData? {
        val entry = entries[semesterCode ?: CURRENT_KEY] ?: return null
        // 与 [get] 认同一条窗口：entries 只有显式 invalidate() 才会清，而组件进程
        // 可能整程都不触发它（用户从不在应用内改课表），不设时限就等于每个绑过
        // 学期的组件各留一份全量课程列表到进程结束。过期 → 返回 null，
        // 调用方那条异步补数据 + 二次通知的路径（R5 F-17）会接管。
        if (System.currentTimeMillis() - entry.at > TTL_MILLIS) return null
        return entry.data
    }

    /** 数据变化后调用，避免刷新到旧快照 */
    fun invalidate() {
        entries.clear()
    }

    /** 未绑定具体学期的组件共用的槽位 key */
    private const val CURRENT_KEY = "current"
}