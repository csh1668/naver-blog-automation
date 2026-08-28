package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_job")
data class PendingJobEntity(
    @PrimaryKey val id: String,
    val contentJson: String,
    val imageUrisJson: String,
    val preparedPathsJson: String?,
    val createdAt: Long,
    val lastFailure: String?,
)
