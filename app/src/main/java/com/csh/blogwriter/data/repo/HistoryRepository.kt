package com.csh.blogwriter.data.repo

import com.csh.blogwriter.data.db.PublishHistoryDao
import com.csh.blogwriter.data.db.PublishHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class PublishHistoryItem(val id: Long, val title: String, val logNo: String, val url: String, val publishedAt: Long, val imageCount: Int)

interface HistoryRepository {
    fun observeAll(): Flow<List<PublishHistoryItem>>
    suspend fun add(title: String, logNo: String, url: String, imageCount: Int)
}

class RoomHistoryRepository @Inject constructor(private val dao: PublishHistoryDao) : HistoryRepository {
    override fun observeAll(): Flow<List<PublishHistoryItem>> = dao.observeAll().map { rows ->
        rows.map { PublishHistoryItem(it.id, it.title, it.logNo, it.url, it.publishedAt, it.imageCount) }
    }
    override suspend fun add(title: String, logNo: String, url: String, imageCount: Int) {
        dao.insert(PublishHistoryEntity(title = title, logNo = logNo, url = url, publishedAt = System.currentTimeMillis(), imageCount = imageCount))
    }
}
