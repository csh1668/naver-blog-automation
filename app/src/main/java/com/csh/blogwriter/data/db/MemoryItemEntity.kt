package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_item")
data class MemoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val text: String,
    val source: String,
    val createdAt: Long,
    val enabled: Boolean,
    val lastUsedAt: Long?,
)
