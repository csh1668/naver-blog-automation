package com.csh.blogwriter.data.repo

import com.csh.blogwriter.data.db.MemoryDao
import com.csh.blogwriter.data.db.MemoryItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

enum class MemoryKind { STYLE, PREFERENCE, FACT, EXPRESSION }
data class MemoryItem(val id: Long, val kind: MemoryKind, val text: String, val source: String, val createdAt: Long, val enabled: Boolean, val lastUsedAt: Long?)

interface MemoryRepository {
    fun observeAll(): Flow<List<MemoryItem>>
    suspend fun activeItems(limit: Int = 40): List<MemoryItem>
    suspend fun add(kind: MemoryKind, text: String, source: String): MemoryItem
    suspend fun update(id: Long, text: String)
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun delete(id: Long)
    suspend fun touch(ids: List<Long>)
}

class RoomMemoryRepository @Inject constructor(private val dao: MemoryDao) : MemoryRepository {
    private fun MemoryItemEntity.toModel() = MemoryItem(id, MemoryKind.valueOf(kind), text, source, createdAt, enabled, lastUsedAt)
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toModel() } }
    override suspend fun activeItems(limit: Int) = dao.active(limit).map { it.toModel() }
    override suspend fun add(kind: MemoryKind, text: String, source: String): MemoryItem {
        val now = System.currentTimeMillis()
        val id = dao.insert(MemoryItemEntity(kind = kind.name, text = text.trim(), source = source, createdAt = now, enabled = true, lastUsedAt = null))
        return MemoryItem(id, kind, text.trim(), source, now, true, null)
    }
    override suspend fun update(id: Long, text: String) = dao.updateText(id, text.trim())
    override suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun touch(ids: List<Long>) { if (ids.isNotEmpty()) dao.touch(ids, System.currentTimeMillis()) }
}
