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
enum class SessionMode { WRITE, ADVICE }
/** [PHOTO_GROUPS] 는 사용자가 정한 사진 묶음, [BLOG_POSTS]/[POST_VIEW] 는 최근 글 목록·글 보기 — 셋 다 모델 히스토리에 싣지 않고 화면·복원에만 쓴다. */
enum class MessageKind { TEXT, PHOTOS, PLAN, POST, SYSTEM, PHOTO_GROUPS, BLOG_POSTS, POST_VIEW }

data class ChatSession(val id: String, val title: String?, val createdAt: Long, val updatedAt: Long, val status: SessionStatus, val pendingJobId: String?, val publishedUrl: String?, val mode: SessionMode = SessionMode.WRITE)
data class ChatMessage(val id: Long, val sessionId: String, val seq: Int, val role: MessageRole, val kind: MessageKind, val payloadJson: String, val createdAt: Long)

interface ChatRepository {
    fun observeSessions(): Flow<List<ChatSession>>
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>
    suspend fun createSession(mode: SessionMode = SessionMode.WRITE): ChatSession
    suspend fun getSession(id: String): ChatSession?
    suspend fun updateSession(session: ChatSession)
    suspend fun appendMessage(sessionId: String, role: MessageRole, kind: MessageKind, payloadJson: String): ChatMessage
    suspend fun messagesOnce(sessionId: String): List<ChatMessage>
    suspend fun deleteSession(id: String)
    /** 제목만 바꾼다 — [updateSession] 과 달리 updatedAt 을 건드리지 않아 목록 순서가 그대로다. */
    suspend fun setTitle(id: String, title: String)
}

class RoomChatRepository @Inject constructor(private val dao: ChatDao) : ChatRepository {
    private fun ChatSessionEntity.toModel() = ChatSession(id, title, createdAt, updatedAt, SessionStatus.valueOf(status), pendingJobId, publishedUrl, SessionMode.valueOf(mode))
    private fun ChatSession.toEntity() = ChatSessionEntity(id, title, createdAt, updatedAt, status.name, pendingJobId, publishedUrl, mode.name)
    private fun ChatMessageEntity.toModel() = ChatMessage(id, sessionId, seq, MessageRole.valueOf(role), MessageKind.valueOf(kind), payloadJson, createdAt)

    override fun observeSessions() = dao.observeSessions().map { list -> list.map { it.toModel() } }
    override fun observeMessages(sessionId: String) = dao.observeMessages(sessionId).map { list -> list.map { it.toModel() } }
    override suspend fun createSession(mode: SessionMode): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(UUID.randomUUID().toString(), null, now, now, SessionStatus.DRAFTING, null, null, mode)
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
    override suspend fun setTitle(id: String, title: String) = dao.setTitle(id, title)
}
