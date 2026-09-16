package com.buaa.schedule.data.calendar

import com.buaa.schedule.data.local.CalendarSyncEntity
import com.buaa.schedule.domain.schedule.Occurrence

/**
 * 日历同步差异计算（纯函数）：
 * 期望课次 vs 本应用已创建的事件映射 → 新增 / 更新 / 删除 / 不变。
 */
object CalendarSyncPlanner {

    data class Diff(
        val toInsert: List<Occurrence>,
        val toUpdate: List<Pair<CalendarSyncEntity, Occurrence>>,
        val toDelete: List<CalendarSyncEntity>,
        val unchangedCount: Int,
    ) {
        val isEmpty: Boolean get() = toInsert.isEmpty() && toUpdate.isEmpty() && toDelete.isEmpty()
        val totalChanged: Int get() = toInsert.size + toUpdate.size + toDelete.size
    }

    fun compute(
        desired: List<Occurrence>,
        existing: List<CalendarSyncEntity>,
    ): Diff {
        val desiredById = desired.associateBy { it.stableId }
        val existingById = existing.associateBy { it.occurrenceId }

        val toInsert = desired.filter { it.stableId !in existingById }
        val toUpdate = desired.mapNotNull { occurrence ->
            val mapping = existingById[occurrence.stableId]
            // 内容摘要变化（名称/地点/教师/时间等）才更新，避免无谓写入
            if (mapping != null && mapping.contentHash != occurrence.contentHash) {
                mapping to occurrence
            } else {
                null
            }
        }
        val toDelete = existing.filter { it.occurrenceId !in desiredById }
        val unchanged = desired.count { occurrence ->
            val mapping = existingById[occurrence.stableId]
            mapping != null && mapping.contentHash == occurrence.contentHash
        }
        return Diff(toInsert, toUpdate, toDelete, unchanged)
    }
}
