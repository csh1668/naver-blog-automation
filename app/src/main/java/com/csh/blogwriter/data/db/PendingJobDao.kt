package com.csh.blogwriter.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingJobDao {
    @Upsert suspend fun upsert(entity: PendingJobEntity)
    @Query("SELECT * FROM pending_job WHERE id = :id") suspend fun get(id: String): PendingJobEntity?
    @Query("SELECT * FROM pending_job ORDER BY createdAt DESC LIMIT 1") fun observeLatest(): Flow<PendingJobEntity?>
    @Query("UPDATE pending_job SET preparedPathsJson = :json WHERE id = :id") suspend fun updatePreparedPaths(id: String, json: String?)
    @Query("UPDATE pending_job SET lastFailure = :message WHERE id = :id") suspend fun updateLastFailure(id: String, message: String?)
    @Query("DELETE FROM pending_job WHERE id = :id") suspend fun delete(id: String)
}
