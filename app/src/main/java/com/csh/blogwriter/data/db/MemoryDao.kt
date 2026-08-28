package com.csh.blogwriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert suspend fun insert(item: MemoryItemEntity): Long
    @Query("SELECT * FROM memory_item ORDER BY createdAt DESC") fun observeAll(): Flow<List<MemoryItemEntity>>
    @Query("SELECT * FROM memory_item WHERE enabled = 1 ORDER BY COALESCE(lastUsedAt, createdAt) DESC LIMIT :limit") suspend fun active(limit: Int): List<MemoryItemEntity>
    @Query("UPDATE memory_item SET text = :text WHERE id = :id") suspend fun updateText(id: Long, text: String)
    @Query("UPDATE memory_item SET enabled = :enabled WHERE id = :id") suspend fun setEnabled(id: Long, enabled: Boolean)
    @Query("UPDATE memory_item SET lastUsedAt = :at WHERE id IN (:ids)") suspend fun touch(ids: List<Long>, at: Long)
    @Query("DELETE FROM memory_item WHERE id = :id") suspend fun delete(id: Long)
}
