package com.csh.blogwriter.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PublishHistoryEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun publishHistoryDao(): PublishHistoryDao
}
