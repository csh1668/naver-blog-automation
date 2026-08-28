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
class PublishHistoryDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertedRowsAreObservedNewestFirst() = runTest {
        val dao = db.publishHistoryDao()
        dao.insert(PublishHistoryEntity(title = "old", logNo = "1", url = "u1", publishedAt = 100, imageCount = 0))
        dao.insert(PublishHistoryEntity(title = "new", logNo = "2", url = "u2", publishedAt = 200, imageCount = 3))

        val rows = dao.observeAll().first()

        assertEquals(listOf("new", "old"), rows.map { it.title })
    }
}
