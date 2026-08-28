package com.csh.blogwriter.data.repo

import com.csh.blogwriter.data.db.ChatDao
import com.csh.blogwriter.data.db.ChatMessageEntity
import com.csh.blogwriter.data.db.ChatSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

enum class SessionStatus { DRAFTING, PUBLISHING, PUBLISHED, ARCHIVED }
enum class MessageRole { USER, ASSISTANT, SYSTEM }
enum class MessageKind { TEXT, PHOTOS, PLAN, POST, SYSTEM }

data class ChatSession(val id: String, val title: String?, val createdAt: Long, val updatedAt: Long, val status: SessionStatus, val pendingJobId: String?, val publishedUrl: String?)
data class ChatMessage(val id: Long, val sessionId: String, val seq: Int, val role: MessageRole, val kind: MessageKind, val payloadJson: String, val createdAt: Long)

interface ChatRepository {
    fun observeSessions(): Flow<List<ChatSession>>
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>
    suspend fun createSession(): ChatSession
    suspend fun getSession(id: String): ChatSession?
    suspend fun updateSession(session: ChatSession)
    suspend fun appendMessage(sessionId: String, role: MessageRole, kind: MessageKind, payloadJson: String): ChatMessage
    suspend fun messagesOnce(sessionId: String): List<ChatMessage>
    suspend fun deleteSession(id: String)
}

class RoomChatRepository @Inject constructor(private val dao: ChatDao) : ChatRepository {
    private fun ChatSessionEntity.toModel() = ChatSession(id, title, createdAt, updatedAt, SessionStatus.valueOf(status), pendingJobId, publishedUrl)
    private fun ChatSession.toEntity() = ChatSessionEntity(id, title, createdAt, updatedAt, status.name, pendingJobId, publishedUrl)
    private fun ChatMessageEntity.toModel() = ChatMessage(id, sessionId, seq, MessageRole.valueOf(role), MessageKind.valueOf(kind), payloadJson, createdAt)

    override fun observeSessions() = dao.observeSessions().map { list -> list.map { it.toModel() } }
    override fun observeMessages(sessionId: String) = dao.observeMessages(sessionId).map { list -> list.map { it.toModel() } }
    override suspend fun createSession(): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(UUID.randomUUID().toString(), null, now, now, SessionStatus.DRAFTING, null, null)
        dao.upsertSession(session.toEntity()); return session
    }
    override suspend fun getSession(id: String) = dao.getSession(id)?.toModel()
    override suspend fun updateSession(session: ChatSession) = dao.upsertSession(session.copy(updatedAt = System.currentTimeMillis()).toEntity())
    override suspend fun appendMessage(sessionId: String, role: MessageRole, kind: MessageKind, payloadJson: String): ChatMessage {
        val seq = (dao.maxSeq(sessionId) ?: -1) + 1
        val now = System.currentTimeMillis()
        val id = dao.insertMessage(ChatMessageEntity(sessionId = sessionId, seq = seq, role = role.name, kind = kind.name, payloadJson = payloadJson, createdAt = now))
        dao.getSession(sessionId)?.let { dao.upsertSession(it.copy(updatedAt = now)) }
        return ChatMessage(id, sessionId, seq, role, kind, payloadJson, now)
    }
    override suspend fun messagesOnce(sessionId: String) = dao.messages(sessionId).map { it.toModel() }
    override suspend fun deleteSession(id: String) = dao.deleteSession(id)
}
