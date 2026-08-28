package com.csh.blogwriter.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MemoryDaoTest {
    private lateinit var db: AppDatabase
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test
    fun activeItemsExcludeDisabledAndRespectLimit() = runTest {
        val dao = db.memoryDao()
        repeat(3) { i -> dao.insert(MemoryItemEntity(kind = "PREFERENCE", text = "항목 $i", source = "chat", createdAt = i.toLong(), enabled = true, lastUsedAt = null)) }
        val disabled = dao.insert(MemoryItemEntity(kind = "FACT", text = "꺼진 항목", source = "chat", createdAt = 10, enabled = true, lastUsedAt = null))
        dao.setEnabled(disabled, false)
        val active = dao.active(limit = 2)
        assertEquals(2, active.size)
        assertEquals(listOf("항목 2", "항목 1"), active.map { it.text })
        dao.updateText(active[0].id, "고친 항목")
        assertEquals("고친 항목", dao.observeAll().first().first { it.id == active[0].id }.text)
    }
}
