package com.csh.blogwriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FailureLogDao {
    @Insert suspend fun insert(entity: FailureLogEntity): Long
    @Query("SELECT * FROM failure_log ORDER BY at DESC LIMIT 200") fun observeAll(): Flow<List<FailureLogEntity>>
}
