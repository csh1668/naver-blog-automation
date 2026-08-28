package com.csh.blogwriter.data.repo

import com.csh.blogwriter.BuildConfig
import com.csh.blogwriter.data.db.FailureLogDao
import com.csh.blogwriter.data.db.FailureLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class FailureLogItem(val id: Long, val at: Long, val stage: String, val message: String, val detail: String, val appVersion: String)

interface FailureLogRepository {
    fun observeAll(): Flow<List<FailureLogItem>>
    suspend fun add(stage: String, message: String, detail: String)
}

class RoomFailureLogRepository @Inject constructor(private val dao: FailureLogDao) : FailureLogRepository {
    override fun observeAll(): Flow<List<FailureLogItem>> = dao.observeAll().map { rows ->
        rows.map { FailureLogItem(it.id, it.at, it.stage, it.message, it.detail, it.appVersion) }
    }
    override suspend fun add(stage: String, message: String, detail: String) {
        dao.insert(FailureLogEntity(at = System.currentTimeMillis(), stage = stage, message = message, detail = detail, appVersion = BuildConfig.VERSION_NAME))
    }
}
