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
class PendingJobDaoTest {
    private lateinit var db: AppDatabase
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test
    fun upsertsAndObservesLatest() = runTest {
        val dao = db.pendingJobDao()
        dao.upsert(PendingJobEntity(id = "a", contentJson = "{}", imageUrisJson = "[]", preparedPathsJson = null, createdAt = 1, lastFailure = null))
        dao.upsert(PendingJobEntity(id = "b", contentJson = "{}", imageUrisJson = "[]", preparedPathsJson = null, createdAt = 2, lastFailure = null))
        assertEquals("b", dao.observeLatest().first()!!.id)
        dao.updatePreparedPaths("b", "[\"/tmp/x.jpg\"]")
        assertEquals("[\"/tmp/x.jpg\"]", dao.get("b")!!.preparedPathsJson)
        dao.delete("b"); dao.delete("a")
        assertNull(dao.observeLatest().first())
    }

    @Test
    fun failureLogNewestFirst() = runTest {
        val dao = db.failureLogDao()
        dao.insert(FailureLogEntity(at = 1, stage = "UPLOAD", message = "m1", detail = "", appVersion = "0.1.0"))
        dao.insert(FailureLogEntity(at = 2, stage = "INJECT", message = "m2", detail = "", appVersion = "0.1.0"))
        assertEquals(listOf("m2", "m1"), dao.observeAll().first().map { it.message })
    }
}
