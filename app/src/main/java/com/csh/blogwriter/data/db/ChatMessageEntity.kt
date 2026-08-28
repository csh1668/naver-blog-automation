package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_message", indices = [Index("sessionId", "seq")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val seq: Int,
    val role: String,
    val kind: String,
    val payloadJson: String,
    val createdAt: Long,
)
