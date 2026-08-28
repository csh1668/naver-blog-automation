package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "publish_history")
data class PublishHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val logNo: String,
    val url: String,
    val publishedAt: Long,
    val imageCount: Int,
)
