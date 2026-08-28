package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "failure_log")
data class FailureLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val stage: String,
    val message: String,
    val detail: String,
    val appVersion: String,
)
