package com.buaa.schedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WidgetSnapshotDao {
    @Query("SELECT * FROM widget_snapshots WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): WidgetSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: WidgetSnapshotEntity)

    @Query("DELETE FROM widget_snapshots WHERE `key` = :key")
    suspend fun delete(key: String)

    /** 清理已不存在学期（或已失效 key）的快照行，避免换学期后无限累积垃圾 */
    @Query("DELETE FROM widget_snapshots WHERE `key` NOT IN (:keys)")
    suspend fun deleteKeysNotIn(keys: List<String>)

    @Query("DELETE FROM widget_snapshots")
    suspend fun clear()
}