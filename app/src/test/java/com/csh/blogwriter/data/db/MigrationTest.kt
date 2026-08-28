package com.csh.blogwriter.data.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val DB_NAME = "migration-test.db"

/**
 * androidx.room:room-testing 의 MigrationTestHelper 는 Windows 에서 androidx.sqlite:sqlite-framework:2.6.2
 * 의 SupportSQLiteDriver.open() 이 파일 경로 비교에 substringAfterLast('/') 만 사용해(백슬래시 미대응)
 * "This driver is configured to open a database named 'X' but 'Y' was requested." 예외를 던지는 버그가 있어
 * 사용할 수 없다(task-1-report.md 참조). 대신 실제 v1 DB 파일을 FrameworkSQLiteOpenHelperFactory 로 직접
 * 만들고, Room.databaseBuilder 로 열어 MIGRATION_1_2 가 실제로 적용되는지 검증한다.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrate1To2AddsTablesAndKeepsRows() = runTest {
        context.deleteDatabase(DB_NAME)

        // v1 스키마를 app/schemas/.../1.json 의 CREATE 문 그대로 만든다.
        val v1Callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `publish_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `logNo` TEXT NOT NULL, `url` TEXT NOT NULL, `publishedAt` INTEGER NOT NULL, `imageCount` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `failure_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `stage` TEXT NOT NULL, `message` TEXT NOT NULL, `detail` TEXT NOT NULL, `appVersion` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pending_job` (`id` TEXT NOT NULL, `contentJson` TEXT NOT NULL, `imageUrisJson` TEXT NOT NULL, `preparedPathsJson` TEXT, `createdAt` INTEGER NOT NULL, `lastFailure` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ca6c3e305f87c2cd08f0fa29a53519bc')")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val v1Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(DB_NAME).callback(v1Callback).build()
        )
        v1Helper.writableDatabase.apply {
            execSQL("INSERT INTO publish_history (title, logNo, url, publishedAt, imageCount) VALUES ('t', '1', 'u', 1, 0)")
            close()
        }

        // 같은 파일을 Room 으로 열면 MIGRATION_1_2 가 실행되고, 스키마 검증까지 통과해야 한다(불일치 시 IllegalStateException).
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        assertEquals(1, db.publishHistoryDao().observeAll().first().size)
        assertTrue(db.chatDao().observeSessions().first().isEmpty())

        val memoryId = db.memoryDao().insert(MemoryItemEntity(kind = "FACT", text = "마이그레이션 확인", source = "test", createdAt = 1, enabled = true, lastUsedAt = null))
        assertEquals("마이그레이션 확인", db.memoryDao().observeAll().first().first { it.id == memoryId }.text)

        db.close()
    }
}
