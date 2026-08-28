package com.csh.blogwriter.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatDaoTest {
    private lateinit var db: AppDatabase
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test
    fun sessionsOrderedByUpdatedAtDescAndMessagesBySeq() = runTest {
        val dao = db.chatDao()
        dao.upsertSession(ChatSessionEntity("s1", null, createdAt = 1, updatedAt = 1, status = "DRAFTING", pendingJobId = null, publishedUrl = null))
        dao.upsertSession(ChatSessionEntity("s2", "두 번째", createdAt = 2, updatedAt = 5, status = "DRAFTING", pendingJobId = null, publishedUrl = null))
        assertEquals(listOf("s2", "s1"), dao.observeSessions().first().map { it.id })

        dao.insertMessage(ChatMessageEntity(sessionId = "s1", seq = 0, role = "USER", kind = "TEXT", payloadJson = "{\"text\":\"안녕\"}", createdAt = 1))
        dao.insertMessage(ChatMessageEntity(sessionId = "s1", seq = 1, role = "ASSISTANT", kind = "PLAN", payloadJson = "{}", createdAt = 2))
        assertEquals(listOf(0, 1), dao.observeMessages("s1").first().map { it.seq })
        assertEquals(1, dao.maxSeq("s1"))
        assertNull(dao.maxSeq("s2"))

        dao.deleteSession("s1")
        assertEquals(0, dao.observeMessages("s1").first().size)
    }
}
