package com.buaa.schedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Widget 独立快照：避免多个桌面组件每次直接查主库课程表。
 * 数据变化时由 WidgetDataSynchronizer 写入，Widget 数据源优先读这里。
 */
@Entity(tableName = "widget_snapshots")
data class WidgetSnapshotEntity(
    @PrimaryKey val key: String,
    val dataJson: String,
    val updatedAt: Long,
)