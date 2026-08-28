package com.csh.blogwriter.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PublishHistoryEntity::class, FailureLogEntity::class, PendingJobEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun publishHistoryDao(): PublishHistoryDao
    abstract fun failureLogDao(): FailureLogDao
    abstract fun pendingJobDao(): PendingJobDao
}
