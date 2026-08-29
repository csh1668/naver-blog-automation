package com.csh.blogwriter.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val pendingJobId: String?,
    val publishedUrl: String?,
    @ColumnInfo(defaultValue = "WRITE") val mode: String = "WRITE",
)
