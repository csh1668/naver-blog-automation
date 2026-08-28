package com.csh.blogwriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PublishHistoryDao {
    @Insert
    suspend fun insert(entity: PublishHistoryEntity): Long

    @Query("SELECT * FROM publish_history ORDER BY publishedAt DESC")
    fun observeAll(): Flow<List<PublishHistoryEntity>>
}
