package com.csh.blogwriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Upsert suspend fun upsertSession(session: ChatSessionEntity)
    @Query("SELECT * FROM chat_session WHERE id = :id") suspend fun getSession(id: String): ChatSessionEntity?
    @Query("SELECT * FROM chat_session ORDER BY updatedAt DESC") fun observeSessions(): Flow<List<ChatSessionEntity>>
    @Insert suspend fun insertMessage(message: ChatMessageEntity): Long
    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY seq ASC") fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>>
    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY seq ASC") suspend fun messages(sessionId: String): List<ChatMessageEntity>
    @Query("SELECT MAX(seq) FROM chat_message WHERE sessionId = :sessionId") suspend fun maxSeq(sessionId: String): Int?
    @Query("DELETE FROM chat_message WHERE sessionId = :sessionId") suspend fun deleteMessages(sessionId: String)
    @Query("DELETE FROM chat_session WHERE id = :sessionId") suspend fun deleteSessionRow(sessionId: String)
    suspend fun deleteSession(sessionId: String) { deleteMessages(sessionId); deleteSessionRow(sessionId) }
}
