# 채팅형 글쓰기 (SP2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사진과 아이디어를 채팅으로 주면 Gemini가 계획을 제안하고 다듬은 뒤 "초안 작성" 시 SP1 발행 패널에 글을 채워 넣는다. API 키 다중 등록·로테이션, 개인화 메모리, WebView 자료 검색, 편집 가능한 프롬프트를 포함한다.

**Architecture:** SP1의 발행 엔진(`PublishPanel`/`PublishViewModel`/상태 기계)은 그대로 두고, 그 앞단에 `llm/`(Gemini REST 클라이언트·키 관리·로테이션), `chat/`(대화 영속·프롬프트 조립·턴 실행), `research/`(숨은 WebView 검색 툴), `memory/`(개인화 메모리)를 추가한다. 어시스턴트 턴은 JSON 스키마(`TurnResponse`)로 강제하고, 초안 턴의 `post`는 SP1의 `PostContent`다. 채팅 화면은 가로 3단(대화 기록 | 채팅 | 발행 패널).

**Tech Stack:** SP1 스택 + OkHttp 4.x(FR-12 브랜치에서 추가됨), MockWebServer(test), Android Keystore(AES-GCM), `SpeechRecognizer`. 모델 기본값 `gemini-3.7-flash` / `gemini-3.5-flash-lite`.

**Spec:** `docs/superpowers/specs/2026-08-28-chat-writing-design.md` (사용자 1차 검토 반영본). 디자인: `docs/design-guide.md` §8. 발행 규칙: `spike/findings.md`.

## Global Constraints

- 패키지 루트 `com.csh.blogwriter`; 새 의존성은 `gradle/libs.versions.toml`에만 추가.
- 빌드/테스트: `./gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"` / `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` (환경변수 불필요, 600 s 타임아웃, 노이즈는 `2>&1 | grep -vE "WARNING|Daemon|honour"`).
- Gemini 호출: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`, 헤더 `x-goog-api-key: <key>`, `Content-Type: application/json`. 요청/응답 필드명은 Task 4의 DTO가 유일한 정의.
- 키는 로그·예외 메시지·보고서에 절대 출력하지 않는다(마스킹 `…끝 4자`). Keystore로 암호화해 저장한다.
- 한도(실측): 3.7-flash RPM 5 / RPD 20, 3.5-flash-lite RPM 15 / RPD 500, 프로젝트 단위. 키 7개는 서로 다른 프로젝트.
- 사용자 화면 문구 "~해요"체, 기술 용어 금지(관리자 화면 예외). 화면은 `ui/theme` 토큰과 `ui/components`만 사용. 터치 타겟 56dp, CTA 60dp.
- 어시스턴트 턴 응답은 §6 `TurnResponse` JSON 스키마로 강제(`responseMimeType: application/json` + `responseJsonSchema`). 도구와 스키마를 한 요청에 함께 쓴다; API가 400으로 거부하면 스키마 없이 재요청하고 JSON을 관대하게 파싱한다(Task 7).
- 도구: `web_search`(네이버 우선, 구글 폴백), `open_page`, `remember`(즉시 저장 + 보고). 턴당 web_search 2회·open_page 2회·remember 2회, 툴 루프 최대 6회.
- 메모리: 자동 저장, 편집·삭제 가능, 프롬프트에 최대 40개.
- 프롬프트: `assets/prompts/*.md` 기본값 + DataStore 오버라이드 + 기본값 복원. 화자 = 40대 여성 블로거. 글 길이 기본 900~1,400자.
- Room 버전 1 → 2 마이그레이션(테이블 추가만). 기존 설치(에뮬레이터·태블릿)를 지우지 않는다.
- **스트리밍(사용자 결정)**: 어시스턴트 턴은 `streamGenerateContent?alt=sse` 로 받아 청크 단위로 UI 를 갱신한다. `say` 는 부분 JSON 에서 점진 추출해 말풍선에 타이핑되듯 표시하고, `plan`/`post` 는 응답 완료 후 렌더링한다. 도구 호출 청크는 누적 후 실행.
- 발행 버튼은 자동으로 누르지 않는다. 커밋 메시지 끝 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Robolectric `sdk=35`; 순수 Kotlin 테스트는 JUnit4; `isReturnDefaultValues=true`이므로 `Log`는 테스트에서 no-op.

---

### Task 1: Room v2 — 대화·메시지·메모리 저장소

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/data/db/ChatSessionEntity.kt`, `ChatMessageEntity.kt`, `MemoryItemEntity.kt`, `ChatDao.kt`, `MemoryDao.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/data/db/AppDatabase.kt` (version 2, 엔티티 추가), `app/src/main/java/com/csh/blogwriter/di/DatabaseModule.kt` (마이그레이션 등록, DAO 제공)
- Create: `app/src/main/java/com/csh/blogwriter/data/db/Migrations.kt`
- Create: `app/src/main/java/com/csh/blogwriter/data/repo/ChatRepository.kt`, `MemoryRepository.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/di/DataModule.kt` (Binds)
- Test: `app/src/test/java/com/csh/blogwriter/data/db/ChatDaoTest.kt`, `MemoryDaoTest.kt`, `MigrationTest.kt`

**Interfaces:**
- Produces:
  - `ChatSession(id, title: String?, createdAt, updatedAt, status: SessionStatus, pendingJobId: String?, publishedUrl: String?)`, `enum SessionStatus { DRAFTING, PUBLISHING, PUBLISHED, ARCHIVED }`
  - `ChatMessage(id: Long, sessionId, seq: Int, role: MessageRole, kind: MessageKind, payloadJson: String, createdAt)`, `enum MessageRole { USER, ASSISTANT, SYSTEM }`, `enum MessageKind { TEXT, PHOTOS, PLAN, POST, SYSTEM }`
  - `ChatRepository { fun observeSessions(): Flow<List<ChatSession>>; fun observeMessages(sessionId): Flow<List<ChatMessage>>; suspend fun createSession(): ChatSession; suspend fun getSession(id): ChatSession?; suspend fun updateSession(session); suspend fun appendMessage(sessionId, role, kind, payloadJson): ChatMessage; suspend fun messagesOnce(sessionId): List<ChatMessage>; suspend fun deleteSession(id) }`
  - `MemoryItem(id: Long, kind: MemoryKind, text, source: String, createdAt, enabled: Boolean, lastUsedAt: Long?)`, `enum MemoryKind { STYLE, PREFERENCE, FACT, EXPRESSION }`
  - `MemoryRepository { fun observeAll(): Flow<List<MemoryItem>>; suspend fun activeItems(limit = 40): List<MemoryItem>; suspend fun add(kind, text, source): MemoryItem; suspend fun update(id, text); suspend fun setEnabled(id, enabled); suspend fun delete(id); suspend fun touch(ids: List<Long>) }`
  - `MIGRATION_1_2`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/data/db/ChatDaoTest.kt
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
```

```kotlin
// app/src/test/java/com/csh/blogwriter/data/db/MemoryDaoTest.kt
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
```

```kotlin
// app/src/test/java/com/csh/blogwriter/data/db/MigrationTest.kt
package com.csh.blogwriter.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2AddsTablesAndKeepsRows() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO publish_history (title, logNo, url, publishedAt, imageCount) VALUES ('t', '1', 'u', 1, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test", 2, true, MIGRATION_1_2)
        db.query("SELECT COUNT(*) FROM publish_history").use { it.moveToFirst(); assertTrue(it.getInt(0) == 1) }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('chat_session','chat_message','memory_item')").use { assertTrue(it.count == 3) }
    }
}
```
(`androidx.room:room-testing` 과 `androidx.test:runner`/`core` 를 `testImplementation` 으로 추가: 카탈로그에 `room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }`, `androidx-test-core = { group = "androidx.test", name = "core", version = "1.7.0" }`, `androidx-test-runner = { group = "androidx.test", name = "runner", version = "1.7.0" }`. 버전이 없으면 `curl -s https://dl.google.com/dl/android/maven2/androidx/test/core/maven-metadata.xml` 로 최신 안정판 확인.)

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.data.db.*"` → 컴파일 실패.

- [ ] **Step 3: 엔티티·DAO·마이그레이션 구현**

```kotlin
// data/db/ChatSessionEntity.kt
package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val pendingJobId: String?,
    val publishedUrl: String?,
)
```

```kotlin
// data/db/ChatMessageEntity.kt
package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_message", indices = [Index("sessionId", "seq")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val seq: Int,
    val role: String,
    val kind: String,
    val payloadJson: String,
    val createdAt: Long,
)
```

```kotlin
// data/db/MemoryItemEntity.kt
package com.csh.blogwriter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_item")
data class MemoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val text: String,
    val source: String,
    val createdAt: Long,
    val enabled: Boolean,
    val lastUsedAt: Long?,
)
```

```kotlin
// data/db/ChatDao.kt
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
```

```kotlin
// data/db/MemoryDao.kt
package com.csh.blogwriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert suspend fun insert(item: MemoryItemEntity): Long
    @Query("SELECT * FROM memory_item ORDER BY createdAt DESC") fun observeAll(): Flow<List<MemoryItemEntity>>
    @Query("SELECT * FROM memory_item WHERE enabled = 1 ORDER BY COALESCE(lastUsedAt, createdAt) DESC LIMIT :limit") suspend fun active(limit: Int): List<MemoryItemEntity>
    @Query("UPDATE memory_item SET text = :text WHERE id = :id") suspend fun updateText(id: Long, text: String)
    @Query("UPDATE memory_item SET enabled = :enabled WHERE id = :id") suspend fun setEnabled(id: Long, enabled: Boolean)
    @Query("UPDATE memory_item SET lastUsedAt = :at WHERE id IN (:ids)") suspend fun touch(ids: List<Long>, at: Long)
    @Query("DELETE FROM memory_item WHERE id = :id") suspend fun delete(id: Long)
}
```

```kotlin
// data/db/Migrations.kt
package com.csh.blogwriter.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `chat_session` (`id` TEXT NOT NULL, `title` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `pendingJobId` TEXT, `publishedUrl` TEXT, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `chat_message` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `role` TEXT NOT NULL, `kind` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_message_sessionId_seq` ON `chat_message` (`sessionId`, `seq`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `memory_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `lastUsedAt` INTEGER)")
    }
}
```

`AppDatabase`: `entities = [PublishHistoryEntity, FailureLogEntity, PendingJobEntity, ChatSessionEntity, ChatMessageEntity, MemoryItemEntity]`, `version = 2`, `abstract fun chatDao(): ChatDao`, `abstract fun memoryDao(): MemoryDao`. `DatabaseModule.provideDatabase`: `.addMigrations(MIGRATION_1_2)`; `@Provides fun chatDao(db) = db.chatDao()`, `@Provides fun memoryDao(db) = db.memoryDao()`. 스키마 JSON `app/schemas/.../2.json` 이 생성되어야 하며 `MigrationTest` 는 `1.json`/`2.json` 을 읽으므로 `app/build.gradle.kts` 의 `sourceSets["test"].assets.srcDirs("$projectDir/schemas")` 를 추가한다(테스트 애셋에 스키마 노출). Room 이 생성한 2.json 의 CREATE 문과 위 마이그레이션 SQL 이 다르면 `runMigrationsAndValidate` 가 실패한다 — 그때는 2.json 의 SQL 을 그대로 복사한다.

- [ ] **Step 4: 리포지토리 구현**

```kotlin
// data/repo/ChatRepository.kt
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
```

```kotlin
// data/repo/MemoryRepository.kt
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
```

`DataModule`: `@Binds abstract fun chatRepository(impl: RoomChatRepository): ChatRepository`, `@Binds abstract fun memoryRepository(impl: RoomMemoryRepository): MemoryRepository`.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.data.*" :app:assembleDebug` → PASS (기존 4 + 새 3). `app/schemas/com.csh.blogwriter.data.db.AppDatabase/2.json` 이 생성되었는지 확인하고 커밋에 포함.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/data app/src/main/java/com/csh/blogwriter/di app/src/test/java/com/csh/blogwriter/data app/schemas app/build.gradle.kts gradle/libs.versions.toml
git commit -m "Add chat session, message and memory storage with Room v2 migration"
```

---

### Task 2: ApiKeyParser + KeyRotator (순수 Kotlin)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/llm/ApiKeyParser.kt`, `app/src/main/java/com/csh/blogwriter/llm/KeyRotator.kt`, `app/src/main/java/com/csh/blogwriter/llm/ModelPolicy.kt`
- Test: `app/src/test/java/com/csh/blogwriter/llm/ApiKeyParserTest.kt`, `KeyRotatorTest.kt`

**Interfaces:**
- Produces: `ApiKeyParser.parse(text: String, existing: Set<String> = emptySet()): List<String>`; `ModelPolicy(models: List<String>, temperature: Double = 0.7, targetLength: IntRange = 900..1400)` with `ModelPolicy.DEFAULT`; `KeyRotator(keyIds: List<String>, models: List<String>, clock: () -> Long)`: `next(): Pick?` (`Pick(keyId, model)`), `report(pick, outcome: Outcome)`, `enum Outcome { SUCCESS, RATE_LIMITED, INVALID_KEY, TRANSIENT }`, `nextAvailableAt(): Long?`, `disabledKeys(): Set<String>`, constants `KEY_COOLDOWN_MS = 60_000`, `MODEL_COOLDOWN_MS = 600_000`, `DAILY_CAP = 20`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/llm/ApiKeyParserTest.kt
package com.csh.blogwriter.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeyParserTest {
    @Test
    fun splitsOnNewlinesCommasSpacesAndStripsDecoration() {
        val text = """
            AQ.Ab8RN6abcdefghijklmnopqrstu, "AIzaSyD1234567890abcdefghijklmnop"
            key=AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz ; short
            AQ.Ab8RN6abcdefghijklmnopqrstu
        """.trimIndent()
        assertEquals(
            listOf("AQ.Ab8RN6abcdefghijklmnopqrstu", "AIzaSyD1234567890abcdefghijklmnop", "AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz"),
            ApiKeyParser.parse(text),
        )
    }

    @Test
    fun excludesAlreadyRegisteredAndBlank() {
        assertEquals(emptyList<String>(), ApiKeyParser.parse("   \n"))
        assertEquals(listOf("AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz"), ApiKeyParser.parse("AQ.Ab8RN6abcdefghijklmnopqrstu\nAQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz", existing = setOf("AQ.Ab8RN6abcdefghijklmnopqrstu")))
    }
}
```

```kotlin
// app/src/test/java/com/csh/blogwriter/llm/KeyRotatorTest.kt
package com.csh.blogwriter.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyRotatorTest {
    private var now = 1_000_000L
    private fun rotator(keys: List<String> = listOf("k1", "k2", "k3"), models: List<String> = listOf("flash", "lite")) =
        KeyRotator(keys, models) { now }

    @Test
    fun roundRobinContinuesFromLastSuccess() {
        val r = rotator()
        val first = r.next()!!; assertEquals("k1" to "flash", first.keyId to first.model)
        r.report(first, KeyRotator.Outcome.SUCCESS)
        assertEquals("k2", r.next()!!.keyId)
    }

    @Test
    fun rateLimitedKeyCoolsDownAndModelDowngradesWhenAllKeysCool() {
        val r = rotator(keys = listOf("k1", "k2"))
        val p1 = r.next()!!; r.report(p1, KeyRotator.Outcome.RATE_LIMITED)
        val p2 = r.next()!!; assertEquals("k2", p2.keyId); assertEquals("flash", p2.model)
        r.report(p2, KeyRotator.Outcome.RATE_LIMITED)
        val p3 = r.next()!!; assertEquals("lite", p3.model)
        now += KeyRotator.KEY_COOLDOWN_MS + 1
        assertEquals("lite", r.next()!!.model)    // 키 쿨다운은 풀렸지만 모델 쿨다운(10분)은 유지
        now += KeyRotator.MODEL_COOLDOWN_MS
        assertEquals("flash", r.next()!!.model)   // 모델 쿨다운 해제 → primary 복귀
    }

    @Test
    fun modelCooldownKeepsFallbackUntilExpiry() {
        val r = rotator(keys = listOf("k1"))
        repeat(1) { val p = r.next()!!; r.report(p, KeyRotator.Outcome.RATE_LIMITED) }
        assertEquals("lite", r.next()!!.model)
        now += KeyRotator.KEY_COOLDOWN_MS + 1
        assertEquals("lite", r.next()!!.model)    // 모델 쿨다운(10분)은 아직
        now += KeyRotator.MODEL_COOLDOWN_MS
        assertEquals("flash", r.next()!!.model)
    }

    @Test
    fun invalidKeyIsDisabledAndAllExhaustedReturnsNullWithNextTime() {
        val r = rotator(keys = listOf("k1", "k2"), models = listOf("flash"))
        r.report(r.next()!!, KeyRotator.Outcome.INVALID_KEY)
        assertEquals(setOf("k1"), r.disabledKeys())
        r.report(r.next()!!, KeyRotator.Outcome.RATE_LIMITED)
        assertNull(r.next())
        assertEquals(now + KeyRotator.KEY_COOLDOWN_MS, r.nextAvailableAt())
    }

    @Test
    fun dailyCapDemotesKeyUntilReset() {
        val r = rotator(keys = listOf("k1", "k2"), models = listOf("flash"))
        repeat(KeyRotator.DAILY_CAP) { val p = r.next()!!; assertEquals("k1", p.keyId); r.report(p, KeyRotator.Outcome.SUCCESS); r.report(r.next()!!, KeyRotator.Outcome.SUCCESS) }
        // k1, k2 모두 20회 성공 → 둘 다 하루 상한 → 그래도 null 이 아니라 상한 키를 마지막 순위로 시도한다
        assertEquals("k1", r.next()!!.keyId)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인** — 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// llm/ApiKeyParser.kt
package com.csh.blogwriter.llm

/** 붙여넣은 텍스트에서 키 후보를 뽑는다. 접두 형태는 검사하지 않는다(발급 형식이 바뀐다). 유효성은 검증 호출로만 판단. */
object ApiKeyParser {
    private const val MIN_LENGTH = 20
    fun parse(text: String, existing: Set<String> = emptySet()): List<String> =
        text.split('\n', ',', ';', ' ', '\t')
            .map { it.trim().trim('"', '\'', '`').removePrefix("key=").removePrefix("KEY=").trim() }
            .filter { it.length >= MIN_LENGTH && it.none(Char::isWhitespace) }
            .distinct()
            .filterNot { it in existing }
}
```

```kotlin
// llm/ModelPolicy.kt
package com.csh.blogwriter.llm

data class ModelPolicy(
    val models: List<String>,
    val temperature: Double = 0.7,
    val targetLength: IntRange = 900..1400,
) {
    companion object {
        val DEFAULT = ModelPolicy(models = listOf("gemini-3.7-flash", "gemini-3.5-flash-lite"))
    }
}
```

```kotlin
// llm/KeyRotator.kt
package com.csh.blogwriter.llm

/**
 * 키·모델 선택 정책 (spec §4.3). 상태는 메모리에만 두며 앱 재시작 시 초기화된다(쿨다운은 짧고, 일일 상한은 보수적 회피용).
 * 순수 Kotlin — 시계는 주입.
 */
class KeyRotator(keyIds: List<String>, private val models: List<String>, private val clock: () -> Long) {
    data class Pick(val keyId: String, val model: String)
    enum class Outcome { SUCCESS, RATE_LIMITED, INVALID_KEY, TRANSIENT }

    companion object {
        const val KEY_COOLDOWN_MS = 60_000L
        const val MODEL_COOLDOWN_MS = 600_000L
        const val DAILY_CAP = 20
    }

    private class KeyState(val id: String) { var cooldownUntil = 0L; var disabled = false; var successesToday = 0; var dayStamp = 0L }
    private val keys = keyIds.map { KeyState(it) }
    private val modelCooldownUntil = HashMap<String, Long>()
    private var startIndex = 0

    fun next(): Pick? {
        val now = clock()
        rollDay(now)
        for (model in models) {
            if ((modelCooldownUntil[model] ?: 0L) > now) continue
            val order = (keys.indices).map { keys[(startIndex + it) % keys.size] }
                .filter { !it.disabled && it.cooldownUntil <= now }
                .sortedBy { if (it.successesToday >= DAILY_CAP) 1 else 0 }   // 상한 키는 마지막 순위
            val key = order.firstOrNull() ?: continue
            return Pick(key.id, model)
        }
        return null
    }

    fun report(pick: Pick, outcome: Outcome) {
        val now = clock()
        val key = keys.first { it.id == pick.keyId }
        when (outcome) {
            Outcome.SUCCESS -> { key.successesToday++; startIndex = (keys.indexOf(key) + 1) % keys.size }
            Outcome.RATE_LIMITED -> {
                key.cooldownUntil = now + KEY_COOLDOWN_MS
                if (keys.all { it.disabled || it.cooldownUntil > now }) modelCooldownUntil[pick.model] = now + MODEL_COOLDOWN_MS
            }
            Outcome.INVALID_KEY -> key.disabled = true
            Outcome.TRANSIENT -> startIndex = (keys.indexOf(key) + 1) % keys.size
        }
    }

    fun nextAvailableAt(): Long? {
        val candidates = keys.filter { !it.disabled }.map { it.cooldownUntil } + modelCooldownUntil.values
        return candidates.filter { it > clock() }.minOrNull()
    }

    fun disabledKeys(): Set<String> = keys.filter { it.disabled }.map { it.id }.toSet()

    private fun rollDay(now: Long) {
        val day = now / 86_400_000L
        keys.forEach { if (it.dayStamp != day) { it.dayStamp = day; it.successesToday = 0 } }
    }
}
```
주: `modelCooldownUntil` 판정은 "같은 모델에서 모든 키가 쿨다운"일 때만이며, 키 쿨다운(60초)이 풀려도 모델 쿨다운(10분)은 유지된다 — `modelCooldownKeepsFallbackUntilExpiry` 가 이를 검증한다. `dailyCapDemotesKeyUntilReset` 의 기대값이 구현과 어긋나면 테스트의 의도(상한 키를 마지막 순위로 두되 아예 제외하지는 않음)에 맞게 구현을 고친다, 테스트를 고치지 않는다.

- [ ] **Step 4: 테스트 통과** — `--tests "com.csh.blogwriter.llm.*"` PASS (7).

- [ ] **Step 5: 커밋** — `git commit -m "Add API key parser and key/model rotation policy"`

---

### Task 3: ApiKeyStore (Keystore 암호화 저장)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/llm/SecretCipher.kt` (인터페이스 + `AndroidKeystoreCipher`), `app/src/main/java/com/csh/blogwriter/llm/ApiKeyStore.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/di/DataModule.kt` (Binds), `app/src/main/java/com/csh/blogwriter/di/DatabaseModule.kt` 또는 새 `di/LlmModule.kt` (`SecretCipher` 제공)
- Test: `app/src/test/java/com/csh/blogwriter/llm/ApiKeyStoreTest.kt`

**Interfaces:**
- Produces: `interface SecretCipher { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(blob: ByteArray): ByteArray }`; `AndroidKeystoreCipher(alias = "blogwriter.apikeys")` (AES/GCM/NoPadding, 12바이트 IV를 앞에 붙임); `ApiKey(id: String, secret: String, addedAt: Long, lastOkAt: Long?, lastLimitedAt: Long?, disabled: Boolean)` with `masked: String` (`…끝 4자`); `interface ApiKeyStore { val keys: Flow<List<ApiKey>>; val hasUsableKey: Flow<Boolean>; suspend fun keysOnce(): List<ApiKey>; suspend fun add(secrets: List<String>): List<ApiKey>; suspend fun remove(id); suspend fun markOk(id); suspend fun markLimited(id); suspend fun markInvalid(id) }`; `DataStoreApiKeyStore(dataStore, cipher)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/llm/ApiKeyStoreTest.kt
package com.csh.blogwriter.llm

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiKeyStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** 테스트용: XOR 로 뒤집기만 하는 가짜 암호기 — 평문이 그대로 저장되지 않음을 확인하는 데 충분하다. */
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray) = blob.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
    private fun store(): ApiKeyStore = DataStoreApiKeyStore(PreferenceDataStoreFactory.create { folder.newFile("k.preferences_pb") }, cipher)

    @Test
    fun addsMasksAndRoundTripsThroughCipher() = runTest {
        val s = store()
        assertFalse(s.hasUsableKey.first())
        val added = s.add(listOf("AQ.Ab8RN6abcdefghijklmnopqrstu", "AQ.Ab8RN6abcdefghijklmnopqrstu", "AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz"))
        assertEquals(2, added.size)
        assertEquals("…rstu", added[0].masked)
        assertEquals(listOf("AQ.Ab8RN6abcdefghijklmnopqrstu", "AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz"), s.keysOnce().map { it.secret })
        assertFalse(s.hasUsableKey.first())          // 검증 전
        s.markOk(added[0].id)
        assertTrue(s.hasUsableKey.first())
        s.markInvalid(added[0].id)
        assertFalse(s.hasUsableKey.first())
        s.remove(added[1].id)
        assertEquals(1, s.keysOnce().size)
    }

    @Test
    fun storedBlobIsNotPlaintext() = runTest {
        val ds = PreferenceDataStoreFactory.create { folder.newFile("k2.preferences_pb") }
        val s = DataStoreApiKeyStore(ds, cipher)
        s.add(listOf("AQ.Ab8RN6abcdefghijklmnopqrstu"))
        val raw = ds.data.first().asMap().values.joinToString()
        assertFalse(raw.contains("AQ.Ab8RN6"))
    }
}
```

- [ ] **Step 2: 실패 확인** — 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// llm/SecretCipher.kt
package com.csh.blogwriter.llm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM, 키는 AndroidKeyStore 에만 존재. blob = IV(12) + ciphertext+tag. */
class AndroidKeystoreCipher(private val alias: String = "blogwriter.apikeys") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }
}
```

```kotlin
// llm/ApiKeyStore.kt
package com.csh.blogwriter.llm

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@Serializable
data class ApiKey(
    val id: String,
    val secret: String,
    val addedAt: Long,
    val lastOkAt: Long? = null,
    val lastLimitedAt: Long? = null,
    val disabled: Boolean = false,
) {
    val masked: String get() = "…" + secret.takeLast(4)
    val usable: Boolean get() = !disabled && lastOkAt != null
}

interface ApiKeyStore {
    val keys: Flow<List<ApiKey>>
    val hasUsableKey: Flow<Boolean>
    suspend fun keysOnce(): List<ApiKey> = keys.first()
    suspend fun add(secrets: List<String>): List<ApiKey>
    suspend fun remove(id: String)
    suspend fun markOk(id: String)
    suspend fun markLimited(id: String)
    suspend fun markInvalid(id: String)
}

class DataStoreApiKeyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : ApiKeyStore {
    private val blobKey = stringPreferencesKey("api_keys_blob")
    private val serializer = ListSerializer(ApiKey.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(prefs: Preferences): List<ApiKey> {
        val b64 = prefs[blobKey] ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, String(cipher.decrypt(Base64.decode(b64, Base64.NO_WRAP)))) }.getOrDefault(emptyList())
    }

    private suspend fun write(transform: (List<ApiKey>) -> List<ApiKey>) {
        dataStore.edit { prefs ->
            val next = transform(decode(prefs))
            prefs[blobKey] = Base64.encodeToString(cipher.encrypt(json.encodeToString(serializer, next).toByteArray()), Base64.NO_WRAP)
        }
    }

    override val keys: Flow<List<ApiKey>> = dataStore.data.map(::decode)
    override val hasUsableKey: Flow<Boolean> = keys.map { list -> list.any { it.usable } }

    override suspend fun add(secrets: List<String>): List<ApiKey> {
        val now = System.currentTimeMillis()
        var added: List<ApiKey> = emptyList()
        write { current ->
            val known = current.map { it.secret }.toSet()
            added = secrets.distinct().filterNot { it in known }.map { ApiKey(UUID.randomUUID().toString(), it, now) }
            current + added
        }
        return added
    }
    override suspend fun remove(id: String) = write { it.filterNot { k -> k.id == id } }
    override suspend fun markOk(id: String) = write { it.map { k -> if (k.id == id) k.copy(lastOkAt = System.currentTimeMillis(), disabled = false) else k } }
    override suspend fun markLimited(id: String) = write { it.map { k -> if (k.id == id) k.copy(lastLimitedAt = System.currentTimeMillis()) else k } }
    override suspend fun markInvalid(id: String) = write { it.map { k -> if (k.id == id) k.copy(disabled = true) else k } }
}
```
`android.util.Base64` 는 Robolectric 없이도 JVM 테스트에서 `isReturnDefaultValues` 때문에 **null/0 을 반환**해 라운드트립이 깨진다 → `java.util.Base64` 를 쓴다(API 26+; minSdk 33 이므로 OK). 위 코드의 `android.util.Base64` 두 곳을 `java.util.Base64.getEncoder().encodeToString(...)` / `java.util.Base64.getDecoder().decode(...)` 로 바꿔 구현한다.

DI: `di/LlmModule.kt`
```kotlin
@Module @InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides @Singleton fun secretCipher(): SecretCipher = AndroidKeystoreCipher()
}
```
`DataModule`: `@Binds @Singleton abstract fun apiKeyStore(impl: DataStoreApiKeyStore): ApiKeyStore`.

- [ ] **Step 4: 테스트 통과** — `--tests "com.csh.blogwriter.llm.ApiKeyStoreTest"` PASS (2) + `assembleDebug`.

- [ ] **Step 5: 커밋** — `git commit -m "Add encrypted API key store"`

---

### Task 4: Gemini DTO + GeminiClient (REST)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/llm/GeminiModels.kt`, `app/src/main/java/com/csh/blogwriter/llm/GeminiClient.kt`, `app/src/main/java/com/csh/blogwriter/llm/GeminiException.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (okhttp/mockwebserver — FR-12 브랜치가 먼저 병합되었으면 이미 존재; 없으면 추가: `okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version = "4.12.0" }`, `mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version = "4.12.0" }` — 최신 4.x 는 `curl -s https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml` 로 확인), `di/LlmModule.kt` (`OkHttpClient` 싱글턴이 FR-12 의 `NetworkModule` 에 이미 있으면 재사용)
- Test: `app/src/test/java/com/csh/blogwriter/llm/GeminiClientTest.kt`

**Interfaces:**
- Produces (모두 `@Serializable`, 필드명은 REST 와 동일):
  - `GPart(text: String? = null, inlineData: GInlineData? = null, functionCall: GFunctionCall? = null, functionResponse: GFunctionResponse? = null)`, `GInlineData(mimeType, data)`, `GFunctionCall(name, args: JsonObject)`, `GFunctionResponse(name, response: JsonObject)`
  - `GContent(role: String, parts: List<GPart>)`, `GSystemInstruction(parts: List<GPart>)`
  - `GFunctionDeclaration(name, description, parameters: JsonObject)`, `GTool(functionDeclarations: List<GFunctionDeclaration>)`, `GToolConfig(functionCallingConfig: GFunctionCallingConfig)`, `GFunctionCallingConfig(mode: String)`
  - `GGenerationConfig(temperature: Double? = null, maxOutputTokens: Int? = null, responseMimeType: String? = null, responseJsonSchema: JsonObject? = null)`
  - `GRequest(contents, systemInstruction? , tools? , toolConfig?, generationConfig?)`
  - `GResponse(candidates: List<GCandidate> = emptyList(), usageMetadata: GUsage? = null, promptFeedback: JsonObject? = null)`, `GCandidate(content: GContent? = null, finishReason: String? = null)`, `GUsage(promptTokenCount: Int? = null, candidatesTokenCount: Int? = null, totalTokenCount: Int? = null)`
  - `GeminiException(code: Int, status: String?, message: String) : Exception` with `val kind: Kind` (`RATE_LIMITED` for 429, `INVALID_KEY` for 400 "API key not valid"/401/403, `BAD_REQUEST` for other 400, `SERVER` for 5xx, `NETWORK`)
  - `class GeminiClient(client: OkHttpClient, baseUrl: String = "https://generativelanguage.googleapis.com", json: Json)`: `suspend fun generate(apiKey: String, model: String, request: GRequest): GResponse`, **`fun generateStream(apiKey, model, request): Flow<GResponse>`** (`POST …:streamGenerateContent?alt=sse`, 본문은 `data: {json}` 줄 단위 SSE; 각 청크를 `GResponse` 로 디코드해 emit; HTTP 오류는 `GeminiException` 으로 throw; 스트림 중간 오류 청크 `{"error":…}` 도 throw), `suspend fun listModels(apiKey: String): Boolean` (키 검증: 200 → true, 400/401/403 → `GeminiException(INVALID_KEY)`, 429 → true).

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/llm/GeminiClientTest.kt
package com.csh.blogwriter.llm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeminiClientTest {
    private val server = MockWebServer()
    private lateinit var client: GeminiClient

    @Before fun setUp() {
        server.start()
        client = GeminiClient(OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(), server.url("/").toString().trimEnd('/'), Json { ignoreUnknownKeys = true })
    }
    @After fun tearDown() = server.shutdown()

    @Test
    fun sendsKeyHeaderAndParsesText() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"role":"model","parts":[{"text":"안녕하세요"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}}"""))
        val req = GRequest(contents = listOf(GContent("user", listOf(GPart(text = "hi")))))
        val res = client.generate("SECRET-KEY", "gemini-3.7-flash", req)
        assertEquals("안녕하세요", res.candidates[0].content!!.parts[0].text)
        assertEquals(5, res.usageMetadata!!.totalTokenCount)
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.7-flash:generateContent", recorded.path)
        assertEquals("SECRET-KEY", recorded.getHeader("x-goog-api-key"))
        assertTrue(recorded.body.readUtf8().contains("\"text\":\"hi\""))
    }

    @Test
    fun parsesFunctionCallParts() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"web_search","args":{"query":"원주 한우"}}}]}}]}"""))
        val res = client.generate("k", "m", GRequest(contents = emptyList()))
        val call = res.candidates[0].content!!.parts[0].functionCall!!
        assertEquals("web_search", call.name)
        assertEquals("원주 한우", call.args["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapsErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota"}}"""))
        try { client.generate("k", "m", GRequest(contents = emptyList())); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.RATE_LIMITED, e.kind) }
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"API key not valid. Please pass a valid API key."}}"""))
        try { client.generate("k", "m", GRequest(contents = emptyList())); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.INVALID_KEY, e.kind) }
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":503,"status":"UNAVAILABLE","message":"x"}}"""))
        try { client.generate("k", "m", GRequest(contents = emptyList())); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.SERVER, e.kind) }
    }

    @Test
    fun listModelsValidatesKey() = runTest {
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        assertTrue(client.listModels("k"))
        assertEquals("/v1beta/models", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"status":"PERMISSION_DENIED","message":"denied"}}"""))
        try { client.listModels("k"); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.INVALID_KEY, e.kind) }
    }

    @Test
    fun streamsSseChunksInOrder() = runTest {
        val chunk1 = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"say\":\"안녕"}]}}]}"""
        val chunk2 = """{"candidates":[{"content":{"role":"model","parts":[{"text":"하세요\"}"}]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":9}}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk1\n\ndata: $chunk2\n\n"))
        val chunks = client.generateStream("k", "m", GRequest(contents = emptyList())).toList()
        assertEquals(2, chunks.size)
        assertEquals("{\"say\":\"안녕", chunks[0].text)
        assertEquals("STOP", chunks[1].candidates[0].finishReason)
        assertEquals("/v1beta/models/m:streamGenerateContent?alt=sse", server.takeRequest().path)
    }

    @Test
    fun streamHttpErrorThrows() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"q"}}"""))
        try { client.generateStream("k", "m", GRequest(contents = emptyList())).toList(); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.RATE_LIMITED, e.kind) }
    }

    @Test
    fun requestSerializesSchemaAndTools() {
        val req = GRequest(
            contents = listOf(GContent("user", listOf(GPart(text = "x")))),
            systemInstruction = GSystemInstruction(listOf(GPart(text = "sys"))),
            tools = listOf(GTool(listOf(GFunctionDeclaration("web_search", "검색", buildJsonObject { put("type", "object") })))),
            toolConfig = GToolConfig(GFunctionCallingConfig("AUTO")),
            generationConfig = GGenerationConfig(temperature = 0.7, responseMimeType = "application/json", responseJsonSchema = buildJsonObject { put("type", "object") }),
        )
        val text = Json.encodeToString(GRequest.serializer(), req)
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("application/json", obj["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(text.contains("\"functionDeclarations\""))
        assertTrue(!text.contains("\"inlineData\":null"))   // null 필드는 직렬화하지 않는다
    }
}
```

- [ ] **Step 2: 실패 확인** — 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// llm/GeminiModels.kt
package com.csh.blogwriter.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class GInlineData(val mimeType: String, val data: String)
@Serializable data class GFunctionCall(val name: String, val args: JsonObject = JsonObject(emptyMap()))
@Serializable data class GFunctionResponse(val name: String, val response: JsonObject)
@Serializable data class GPart(
    val text: String? = null,
    val inlineData: GInlineData? = null,
    val functionCall: GFunctionCall? = null,
    val functionResponse: GFunctionResponse? = null,
)
@Serializable data class GContent(val role: String, val parts: List<GPart>)
@Serializable data class GSystemInstruction(val parts: List<GPart>)
@Serializable data class GFunctionDeclaration(val name: String, val description: String, val parameters: JsonObject)
@Serializable data class GTool(val functionDeclarations: List<GFunctionDeclaration>)
@Serializable data class GFunctionCallingConfig(val mode: String)
@Serializable data class GToolConfig(val functionCallingConfig: GFunctionCallingConfig)
@Serializable data class GGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null,
    val responseJsonSchema: JsonObject? = null,
)
@Serializable data class GRequest(
    val contents: List<GContent>,
    val systemInstruction: GSystemInstruction? = null,
    val tools: List<GTool>? = null,
    val toolConfig: GToolConfig? = null,
    val generationConfig: GGenerationConfig? = null,
)
@Serializable data class GUsage(val promptTokenCount: Int? = null, val candidatesTokenCount: Int? = null, val totalTokenCount: Int? = null)
@Serializable data class GCandidate(val content: GContent? = null, val finishReason: String? = null)
@Serializable data class GResponse(val candidates: List<GCandidate> = emptyList(), val usageMetadata: GUsage? = null, val promptFeedback: JsonObject? = null) {
    val text: String? get() = candidates.firstOrNull()?.content?.parts?.mapNotNull { it.text }?.joinToString("")?.takeIf { it.isNotEmpty() }
    val functionCalls: List<GFunctionCall> get() = candidates.firstOrNull()?.content?.parts?.mapNotNull { it.functionCall } ?: emptyList()
}
```

```kotlin
// llm/GeminiException.kt
package com.csh.blogwriter.llm

class GeminiException(val code: Int, val status: String?, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { RATE_LIMITED, INVALID_KEY, BAD_REQUEST, SERVER, NETWORK }
    val kind: Kind = when {
        code == 0 -> Kind.NETWORK
        code == 429 -> Kind.RATE_LIMITED
        code == 401 || code == 403 -> Kind.INVALID_KEY
        code == 400 && (message.contains("API key", ignoreCase = true)) -> Kind.INVALID_KEY
        code in 400..499 -> Kind.BAD_REQUEST
        else -> Kind.SERVER
    }
}
```

```kotlin
// llm/GeminiClient.kt
package com.csh.blogwriter.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Gemini REST (`v1beta` generateContent). 키는 헤더로만 보내고 어디에도 기록하지 않는다. */
class GeminiClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generate(apiKey: String, model: String, request: GRequest): GResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(GRequest.serializer(), request).toRequestBody(mediaType)
        val req = Request.Builder().url("$baseUrl/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey).header("Content-Type", "application/json").post(body).build()
        val text = execute(req)
        json.decodeFromString(GResponse.serializer(), text)
    }

    /** SSE 스트리밍. `data:` 줄마다 GResponse 로 디코드해 emit. 오류 청크(`{"error":…}`)는 예외로. */
    fun generateStream(apiKey: String, model: String, request: GRequest): Flow<GResponse> = flow {
        val body = json.encodeToString(GRequest.serializer(), request).toRequestBody(mediaType)
        val req = Request.Builder().url("$baseUrl/v1beta/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey).header("Content-Type", "application/json").post(body).build()
        val response = try { http.newCall(req).execute() } catch (e: IOException) { throw GeminiException(0, null, "네트워크 오류", e) }
        response.use { res ->
            if (!res.isSuccessful) throw parseError(res.code, res.body?.string().orEmpty())
            val source = res.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                if (payload.startsWith("{\"error\"")) throw parseError(500, payload)
                emit(json.decodeFromString(GResponse.serializer(), payload))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun parseError(httpCode: Int, text: String): GeminiException {
        val err = runCatching { json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject }.getOrNull()
        val code = err?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: httpCode
        return GeminiException(code, err?.get("status")?.jsonPrimitive?.content, err?.get("message")?.jsonPrimitive?.content ?: text.take(200))
    }

    suspend fun listModels(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/v1beta/models").header("x-goog-api-key", apiKey).get().build()
        try { execute(req); true } catch (e: GeminiException) { if (e.kind == GeminiException.Kind.RATE_LIMITED) true else throw e }
    }

    private fun execute(req: Request): String {
        val response = try { http.newCall(req).execute() } catch (e: IOException) { throw GeminiException(0, null, "네트워크 오류", e) }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return text
            val (status, message) = runCatching {
                val err = json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject
                err["status"]?.jsonPrimitive?.content to (err["message"]?.jsonPrimitive?.content ?: "")
            }.getOrDefault(null to text.take(200))
            throw GeminiException(it.code, status, message)
        }
    }
}
```
`Json` 설정 `explicitNulls = false` 가 null 필드 생략의 핵심(테스트 `requestSerializesSchemaAndTools` 는 기본 `Json` 으로 인코딩하므로 DTO 에 `@EncodeDefault` 없이 기본값 null 인 필드가 생략되도록 테스트의 `Json` 도 `Json { explicitNulls = false }` 로 바꾼다 — 테스트 수정 허용).

DI (`LlmModule`): `@Provides @Singleton fun geminiClient(http: OkHttpClient) = GeminiClient(http)`; `OkHttpClient` 는 FR-12 의 `NetworkModule` 것을 재사용(없으면 여기서 `OkHttpClient.Builder().connectTimeout(10, SECONDS).readTimeout(90, SECONDS).build()`).

- [ ] **Step 4: 테스트 통과** — `--tests "com.csh.blogwriter.llm.GeminiClientTest"` PASS (7). (`execute()` 의 오류 분기도 `parseError` 를 쓰도록 정리; 테스트 import 에 `kotlinx.coroutines.flow.toList` 추가.)

- [ ] **Step 5: 커밋** — `git commit -m "Add Gemini REST client and DTOs"`

---

### Task 5: 프롬프트 리소스 + PromptStore + PromptBuilder

**Files:**
- Create: `app/src/main/assets/prompts/01_role.md`, `02_audience.md`, `03_style.md`(템플릿), `04_memory.md`(템플릿), `05_structure.md`, `06_conversation.md`, `07_output.md`, `08_selfcheck.md`
- Create: `app/src/main/java/com/csh/blogwriter/chat/PromptStore.kt` (기본값 + DataStore 오버라이드), `app/src/main/java/com/csh/blogwriter/chat/PromptBuilder.kt`
- Test: `app/src/test/java/com/csh/blogwriter/chat/PromptBuilderTest.kt`, `PromptStoreTest.kt`

**Interfaces:**
- Produces: `enum PromptSection(val file: String, val title: String) { ROLE, AUDIENCE, STYLE, MEMORY, STRUCTURE, CONVERSATION, OUTPUT, SELFCHECK }`; `interface PromptStore { suspend fun text(section): String; fun observe(section): Flow<String>; suspend fun override(section, text: String?) /* null = 기본값 복원 */; suspend fun isOverridden(section): Boolean }`; `AssetPromptStore(context, dataStore)`; `PromptBuilder(store).system(memory: List<MemoryItem>, style: String?, targetLength: IntRange, draftTurn: Boolean): String`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/chat/PromptBuilderTest.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val texts = mapOf(
        PromptSection.ROLE to "역할 문안", PromptSection.AUDIENCE to "독자 문안",
        PromptSection.STYLE to "스타일: {{style}}", PromptSection.MEMORY to "기억:\n{{memory}}",
        PromptSection.STRUCTURE to "구조 문안 길이 {{minLen}}~{{maxLen}}자", PromptSection.CONVERSATION to "대화 문안",
        PromptSection.OUTPUT to "출력 문안", PromptSection.SELFCHECK to "점검 문안",
    )
    private val store = object : PromptStore {
        override suspend fun text(section: PromptSection) = texts.getValue(section)
        override fun observe(section: PromptSection): Flow<String> = flowOf(texts.getValue(section))
        override suspend fun override(section: PromptSection, text: String?) {}
        override suspend fun isOverridden(section: PromptSection) = false
    }
    private fun mem(i: Int, kind: MemoryKind = MemoryKind.PREFERENCE) = MemoryItem(i.toLong(), kind, "항목$i", "chat", i.toLong(), true, null)

    @Test
    fun assemblesSectionsInOrderWithSubstitutions() = runTest {
        val s = PromptBuilder(store).system(memory = listOf(mem(1), mem(2, MemoryKind.EXPRESSION)), style = "존댓말", targetLength = 900..1400, draftTurn = false)
        val idx = listOf("역할 문안", "독자 문안", "스타일: 존댓말", "기억:", "- PREFERENCE: 항목1", "- EXPRESSION: 항목2", "구조 문안 길이 900~1400자", "대화 문안", "출력 문안").map { s.indexOf(it) }
        assertTrue(idx.all { it >= 0 })
        assertEquals(idx, idx.sorted())
        assertFalse(s.contains("점검 문안"))
    }

    @Test
    fun draftTurnAppendsSelfCheckAndCapsMemory() = runTest {
        val s = PromptBuilder(store).system(memory = (1..50).map { mem(it) }, style = null, targetLength = 900..1400, draftTurn = true)
        assertTrue(s.contains("점검 문안"))
        assertEquals(40, Regex("- PREFERENCE: 항목").findAll(s).count())
        assertTrue(s.contains("스타일: (아직 없음)"))
    }
}
```

```kotlin
// app/src/test/java/com/csh/blogwriter/chat/PromptStoreTest.kt
package com.csh.blogwriter.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PromptStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun defaultsComeFromAssetsAndOverridesRoundTrip() = runTest {
        val store = AssetPromptStore(RuntimeEnvironment.getApplication(), PreferenceDataStoreFactory.create { folder.newFile("p.preferences_pb") })
        val default = store.text(PromptSection.ROLE)
        assertTrue(default.contains("40대"))
        assertFalse(store.isOverridden(PromptSection.ROLE))
        store.override(PromptSection.ROLE, "내 역할")
        assertEquals("내 역할", store.observe(PromptSection.ROLE).first())
        assertTrue(store.isOverridden(PromptSection.ROLE))
        store.override(PromptSection.ROLE, null)
        assertEquals(default, store.text(PromptSection.ROLE))
    }
}
```

- [ ] **Step 2: 실패 확인** — 컴파일 실패.

- [ ] **Step 3: 프롬프트 리소스 작성** (`app/src/main/assets/prompts/`, UTF-8, 그대로 사용)

`01_role.md`
```
당신은 한국어 네이버 블로그 글쓰기 도우미입니다. 40대 여성 블로거(사용자, "사용자")의 목소리로 글을 씁니다.
대화는 짧고 다정하게 합니다. 사용자는 컴퓨터에 익숙하지 않으므로 기술 용어를 쓰지 않습니다.
한 번에 질문은 하나만 합니다. 사용자가 이미 답한 것을 다시 묻지 않습니다.
```
`02_audience.md`
```
독자는 지인과 같은 관심사를 가진 이웃 블로거입니다. 글의 목적은 기록과 공유이며 과장 광고나 홍보 문구는 쓰지 않습니다.
```
`03_style.md`
```
[글 스타일]
{{style}}
```
`04_memory.md`
```
[사용자에 대해 기억하는 것]
아래 항목은 사용자가 좋아하거나 자주 쓰는 것들입니다. 글과 대화에 자연스럽게 반영하세요. 항목이 없으면 이 절은 무시합니다.
{{memory}}
```
`05_structure.md`
```
[글 구조 규칙]
- 제목: 핵심 키워드를 앞에 두고 20~30자. 낚시성 표현 금지. 제목 후보는 3개를 서로 다른 관점(장소 중심 / 감정 중심 / 정보 중심)으로 제안합니다.
- 도입 2~3문장(왜 갔는지, 무슨 날인지) → 본문은 사진 흐름을 따라 3~5개 소제목(굵게, 큰 글씨) → 마무리(감상 + 한 줄 추천이나 팁).
- 문단은 2~4문장. 사진마다 그 사진에 대한 설명 1~2문장을 바로 앞 또는 뒤에 둡니다. 사진 내용은 실제로 보이는 것만 서술하고 추측하지 않습니다.
- 서식: 굵게는 문단당 최대 1회, 형광펜은 글 전체 2회 이하, 인용구는 한 줄 감상에 1회, 목록은 정보(메뉴·가격·주소)에만 씁니다.
- 사실 정보(주소, 가격, 영업시간, 날짜)는 사용자가 말했거나 도구로 확인한 것만 씁니다. 모르면 쓰지 말거나 사용자에게 묻습니다.
- 길이: 본문 {{minLen}}~{{maxLen}}자. 사용자가 "짧게/길게"라고 하면 40% 정도 줄이거나 늘립니다.
```
`06_conversation.md`
```
[대화 규칙]
- 첫 응답에는 반드시 plan(제목 후보 3개 + 개요 + 톤)을 넣고, "이렇게 써 볼까요?"처럼 제안합니다.
- 사용자가 고른 제목과 수정 요청을 다음 plan에 반영합니다. quickReplies에는 사용자가 탭 한 번으로 답할 수 있는 짧은 선택지를 2~4개 넣습니다.
- readyToDraft는 제목이 정해지고 개요에 이의가 없을 때만 true로 둡니다.
- 도구는 사실 확인이 필요할 때만 씁니다(영업시간, 주소, 가격, 행사 날짜). 검색 결과를 본문에 출처 형태로 끼워 넣지 않습니다.
- 사용자가 자기 취향이나 습관을 말하면 remember 도구로 저장하고, 그 턴의 say에 "기억해 둘게요: …" 한 줄로 알립니다.
- 초안을 요청받은 턴에는 post를 채우고 say는 한 문장으로 짧게 씁니다. 그 밖의 턴에서는 post를 null로 둡니다.
```
`07_output.md`
```
[출력 형식]
항상 지정된 JSON 스키마로만 답합니다. 문자열 안에 마크다운 기호를 넣지 않습니다.
post.blocks의 image ref는 첨부된 사진 목록의 ref만 사용하고, 각 사진을 정확히 한 번씩 씁니다.
소제목은 paragraph의 run에 size "TITLE"과 bold true를 주어 표현합니다. 본문 run은 size "BODY"입니다.
```
`08_selfcheck.md`
```
[제출 전 점검 — 초안 턴 전용]
1) 모든 사진 ref가 정확히 한 번씩 쓰였는가. 2) 소제목이 사진 흐름과 맞는가. 3) 추측한 사실이 없는가. 4) 본문 글자 수가 범위 안인가. 5) 제목이 20~30자인가.
문제가 있으면 고친 뒤 제출합니다.
```

- [ ] **Step 4: PromptStore / PromptBuilder 구현**

```kotlin
// chat/PromptStore.kt
package com.csh.blogwriter.chat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

enum class PromptSection(val file: String, val title: String) {
    ROLE("01_role.md", "역할"), AUDIENCE("02_audience.md", "독자"), STYLE("03_style.md", "글 스타일"), MEMORY("04_memory.md", "기억"),
    STRUCTURE("05_structure.md", "글 구조 규칙"), CONVERSATION("06_conversation.md", "대화 규칙"), OUTPUT("07_output.md", "출력 형식"), SELFCHECK("08_selfcheck.md", "제출 전 점검"),
}

interface PromptStore {
    suspend fun text(section: PromptSection): String
    fun observe(section: PromptSection): Flow<String>
    suspend fun override(section: PromptSection, text: String?)
    suspend fun isOverridden(section: PromptSection): Boolean
}

class AssetPromptStore @Inject constructor(@ApplicationContext private val context: Context, private val dataStore: DataStore<Preferences>) : PromptStore {
    private fun key(s: PromptSection) = stringPreferencesKey("prompt_override_${s.name}")
    private val defaults = HashMap<PromptSection, String>()
    private fun default(s: PromptSection) = defaults.getOrPut(s) { context.assets.open("prompts/${s.file}").bufferedReader().readText().trim() }

    override suspend fun text(section: PromptSection) = dataStore.data.first()[key(section)] ?: default(section)
    override fun observe(section: PromptSection) = dataStore.data.map { it[key(section)] ?: default(section) }
    override suspend fun override(section: PromptSection, text: String?) { dataStore.edit { if (text.isNullOrBlank()) it.remove(key(section)) else it[key(section)] = text.trim() } }
    override suspend fun isOverridden(section: PromptSection) = dataStore.data.first().contains(key(section))
}
```

```kotlin
// chat/PromptBuilder.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.MemoryItem
import javax.inject.Inject

class PromptBuilder @Inject constructor(private val store: PromptStore) {
    companion object { const val MEMORY_CAP = 40 }

    suspend fun system(memory: List<MemoryItem>, style: String?, targetLength: IntRange, draftTurn: Boolean): String {
        val memoryLines = memory.take(MEMORY_CAP).joinToString("\n") { "- ${it.kind.name}: ${it.text}" }.ifEmpty { "(없음)" }
        val sections = buildList {
            add(store.text(PromptSection.ROLE))
            add(store.text(PromptSection.AUDIENCE))
            add(store.text(PromptSection.STYLE).replace("{{style}}", style ?: "(아직 없음)"))
            add(store.text(PromptSection.MEMORY).replace("{{memory}}", memoryLines))
            add(store.text(PromptSection.STRUCTURE).replace("{{minLen}}", targetLength.first.toString()).replace("{{maxLen}}", targetLength.last.toString()))
            add(store.text(PromptSection.CONVERSATION))
            add(store.text(PromptSection.OUTPUT))
            if (draftTurn) add(store.text(PromptSection.SELFCHECK))
        }
        return sections.joinToString("\n\n")
    }
}
```
DI: `DataModule` `@Binds abstract fun promptStore(impl: AssetPromptStore): PromptStore`.

- [ ] **Step 5: 테스트 통과** — `--tests "com.csh.blogwriter.chat.*"` PASS (3).

- [ ] **Step 6: 커밋** — `git commit -m "Add editable prompt sections and system prompt builder"`

---

### Task 6: TurnResponse 스키마 + 파싱 + PostContent 보정

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/chat/TurnResponse.kt`, `app/src/main/java/com/csh/blogwriter/chat/TurnSchemas.kt`, `app/src/main/java/com/csh/blogwriter/chat/PostContentRepair.kt`
- Test: `app/src/test/java/com/csh/blogwriter/chat/TurnResponseTest.kt`, `PostContentRepairTest.kt`

**Interfaces:**
- Produces: `@Serializable TurnResponse(say: String, plan: Plan? = null, question: String? = null, quickReplies: List<String> = emptyList(), readyToDraft: Boolean = false, post: PostContent? = null)`, `@Serializable Plan(titleCandidates: List<String>, outline: List<OutlineItem>, tone: String)`, `@Serializable OutlineItem(heading: String, summary: String, photoRefs: List<String> = emptyList())`; `TurnResponseJson.decode(text: String): TurnResponse` (앞뒤의 ``` 펜스·설명 문장을 벗기고 첫 `{`부터 마지막 `}`까지 파싱하는 관대 모드), `TurnSchemas.turnResponseJsonSchema(): JsonObject` (Gemini `responseJsonSchema`용 표준 JSON Schema; `post`는 SP1 `PostContent` 구조를 그대로 기술), `TurnSchemas.functionDeclarations(): List<GFunctionDeclaration>` (web_search/open_page/remember); `PostContentRepair.repair(post, attachedRefs: List<String>): Repaired(content, fixes: List<String>)` — 없는 ref 제거, 빠진 사진은 끝에 `Block.Image`로 추가, 중복 ref는 첫 것만 유지, 빈 제목이면 첫 문단 앞 30자.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/chat/TurnResponseTest.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.Block
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnResponseTest {
    @Test
    fun decodesFullTurnWithPlan() {
        val text = """{"say":"이렇게 써 볼까요?","plan":{"titleCandidates":["a","b","c"],"outline":[{"heading":"도입","summary":"왜 갔는지","photoRefs":["img_001"]}],"tone":"따뜻한 존댓말"},"question":"제목은요?","quickReplies":["1번","더 짧게"],"readyToDraft":false,"post":null}"""
        val t = TurnResponseJson.decode(text)
        assertEquals(3, t.plan!!.titleCandidates.size)
        assertEquals(listOf("img_001"), t.plan!!.outline[0].photoRefs)
        assertNull(t.post)
    }

    @Test
    fun decodesDraftTurnWithPostAndToleratesFencesAndMissingFields() {
        val text = "```json\n{\"say\":\"초안이에요\",\"readyToDraft\":true,\"post\":{\"title\":\"제목\",\"blocks\":[{\"type\":\"paragraph\",\"runs\":[{\"text\":\"본문\"}]},{\"type\":\"image\",\"ref\":\"img_001\"}]}}\n```"
        val t = TurnResponseJson.decode(text)
        assertEquals("제목", t.post!!.title)
        assertTrue(t.post!!.blocks[1] is Block.Image)
        assertEquals(emptyList<String>(), t.quickReplies)
    }

    @Test
    fun schemaDescribesTopLevelFieldsAndPost() {
        val schema = TurnSchemas.turnResponseJsonSchema()
        val props = schema["properties"]!!.jsonObject
        assertEquals(setOf("say", "plan", "question", "quickReplies", "readyToDraft", "post"), props.keys)
        assertEquals("say", schema["required"]!!.jsonArray[0].jsonPrimitive.content)
        val blocks = props["post"]!!.jsonObject["anyOf"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject["blocks"]!!.jsonObject
        assertEquals("array", blocks["type"]!!.jsonPrimitive.content)
        assertEquals(3, TurnSchemas.functionDeclarations().size)
        assertEquals(listOf("web_search", "open_page", "remember"), TurnSchemas.functionDeclarations().map { it.name })
    }
}
```

```kotlin
// app/src/test/java/com/csh/blogwriter/chat/PostContentRepairTest.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class PostContentRepairTest {
    @Test
    fun dropsUnknownRefsAppendsMissingAndDedupes() {
        val post = PostContent("제목", listOf(
            Block.Paragraph(listOf(Run("a"))), Block.Image("img_009"), Block.Image("img_001"), Block.Paragraph(listOf(Run("b"))), Block.Image("img_001"),
        ))
        val r = PostContentRepair.repair(post, attachedRefs = listOf("img_001", "img_002"))
        assertEquals(listOf("paragraph", "image:img_001", "paragraph", "image:img_002"), r.content.blocks.map { b -> when (b) { is Block.Image -> "image:${b.ref}"; is Block.Paragraph -> "paragraph"; is Block.Quote -> "quote" } })
        assertEquals(3, r.fixes.size)
    }

    @Test
    fun fillsEmptyTitleFromFirstParagraph() {
        val r = PostContentRepair.repair(PostContent("  ", listOf(Block.Paragraph(listOf(Run("오늘은 원주에 다녀왔어요. 정말 좋았답니다."))))), emptyList())
        assertEquals("오늘은 원주에 다녀왔어요. 정말 좋았답니다.".take(30), r.content.title)
    }
}
```

- [ ] **Step 2: 실패 확인** — 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// chat/TurnResponse.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.PostContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class OutlineItem(val heading: String, val summary: String, val photoRefs: List<String> = emptyList())
@Serializable data class Plan(val titleCandidates: List<String>, val outline: List<OutlineItem>, val tone: String)
@Serializable data class TurnResponse(
    val say: String,
    val plan: Plan? = null,
    val question: String? = null,
    val quickReplies: List<String> = emptyList(),
    val readyToDraft: Boolean = false,
    val post: PostContent? = null,
)

object TurnResponseJson {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type"; coerceInputValues = true; isLenient = true }
    fun decode(text: String): TurnResponse {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "JSON 객체를 찾지 못했습니다" }
        return json.decodeFromString(TurnResponse.serializer(), text.substring(start, end + 1))
    }
    fun encode(t: TurnResponse): String = json.encodeToString(TurnResponse.serializer(), t)
}
```

```kotlin
// chat/TurnSchemas.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.llm.GFunctionDeclaration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Gemini responseJsonSchema / functionDeclarations. PostContent 스키마는 SP1 모델(도메인 model/PostContent.kt)과 1:1. */
object TurnSchemas {
    private fun str(desc: String? = null) = buildJsonObject { put("type", "string"); if (desc != null) put("description", desc) }
    private fun nullable(schema: JsonObject) = buildJsonObject { putJsonArray("anyOf") { add(schema); add(buildJsonObject { put("type", "null") }) } }
    private fun arrayOf(items: JsonObject) = buildJsonObject { put("type", "array"); put("items", items) }

    private val run = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("text", str()); put("bold", buildJsonObject { put("type", "boolean") })
            put("color", nullable(str("#rrggbb"))); put("background", nullable(str("#rrggbb")))
            put("size", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("BODY"); add("TITLE") } })
        }
        putJsonArray("required") { add("text") }
    }
    private val block = buildJsonObject {
        putJsonArray("anyOf") {
            add(buildJsonObject { put("type", "object"); putJsonObject("properties") {
                put("type", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("paragraph") } }); put("runs", arrayOf(run))
                put("align", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("LEFT"); add("CENTER"); add("RIGHT") } })
                put("list", nullable(buildJsonObject { put("type", "string"); putJsonArray("enum") { add("BULLET"); add("DECIMAL") } }))
            }; putJsonArray("required") { add("type"); add("runs") } })
            add(buildJsonObject { put("type", "object"); putJsonObject("properties") {
                put("type", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("image") } }); put("ref", str("첨부 사진의 ref, 예: img_001"))
            }; putJsonArray("required") { add("type"); add("ref") } })
            add(buildJsonObject { put("type", "object"); putJsonObject("properties") {
                put("type", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("quote") } }); put("text", str()); put("source", nullable(str()))
            }; putJsonArray("required") { add("type"); add("text") } })
        }
    }
    val postContent: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { put("title", str()); put("blocks", arrayOf(block)) }
        putJsonArray("required") { add("title"); add("blocks") }
    }
    private val plan = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("titleCandidates", arrayOf(str()))
            put("outline", arrayOf(buildJsonObject { put("type", "object"); putJsonObject("properties") { put("heading", str()); put("summary", str()); put("photoRefs", arrayOf(str())) }; putJsonArray("required") { add("heading"); add("summary") } }))
            put("tone", str())
        }
        putJsonArray("required") { add("titleCandidates"); add("outline"); add("tone") }
    }

    fun turnResponseJsonSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("say", str("말풍선 본문, 2~4문장")); put("plan", nullable(plan)); put("question", nullable(str("한 턴에 질문 하나")))
            put("quickReplies", arrayOf(str())); put("readyToDraft", buildJsonObject { put("type", "boolean") }); put("post", nullable(postContent))
        }
        putJsonArray("required") { add("say"); add("quickReplies"); add("readyToDraft") }
    }

    fun functionDeclarations(): List<GFunctionDeclaration> = listOf(
        GFunctionDeclaration("web_search", "네이버(실패 시 구글)에서 검색해 제목·주소·요약 목록을 돌려준다. 영업시간·주소·가격·행사 날짜처럼 사실 확인이 필요할 때만 쓴다.",
            buildJsonObject { put("type", "object"); putJsonObject("properties") { put("query", str("검색어")) }; putJsonArray("required") { add("query") } }),
        GFunctionDeclaration("open_page", "웹 페이지를 열어 본문 텍스트(최대 4000자)를 돌려준다. web_search 결과의 url만 연다.",
            buildJsonObject { put("type", "object"); putJsonObject("properties") { put("url", str("http(s) 주소")) }; putJsonArray("required") { add("url") } }),
        GFunctionDeclaration("remember", "사용자의 취향·습관·자주 쓰는 표현·사실을 저장한다. 저장 후 say에 '기억해 둘게요: …' 로 알린다.",
            buildJsonObject { put("type", "object"); putJsonObject("properties") {
                put("kind", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("STYLE"); add("PREFERENCE"); add("FACT"); add("EXPRESSION") } }); put("text", str("한 문장"))
            }; putJsonArray("required") { add("kind"); add("text") } }),
    )
}
```

```kotlin
// chat/PostContentRepair.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run

object PostContentRepair {
    data class Repaired(val content: PostContent, val fixes: List<String>)

    fun repair(post: PostContent, attachedRefs: List<String>): Repaired {
        val fixes = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val blocks = post.blocks.filter { b ->
            if (b !is Block.Image) true
            else if (b.ref !in attachedRefs) { fixes += "없는 사진 제거: ${b.ref}"; false }
            else if (!seen.add(b.ref)) { fixes += "중복 사진 제거: ${b.ref}"; false }
            else true
        }.toMutableList()
        attachedRefs.filterNot { it in seen }.forEach { fixes += "누락 사진 추가: $it"; blocks += Block.Image(it) }
        var title = post.title.trim()
        if (title.isEmpty()) {
            title = blocks.filterIsInstance<Block.Paragraph>().firstOrNull()?.runs?.joinToString("") { it.text }?.trim()?.take(30).orEmpty().ifEmpty { "새 글" }
            fixes += "제목 보정"
        }
        return Repaired(PostContent(title, blocks), fixes)
    }
}
```

- [ ] **Step 4: 테스트 통과** — `--tests "com.csh.blogwriter.chat.*"` PASS.
- [ ] **Step 5: 커밋** — `git commit -m "Add turn response schema, lenient parser and post repair"`

---

### Task 7: ConversationEngine (턴 실행 + 도구 루프 + 키 로테이션)

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/chat/ToolExecutor.kt` (인터페이스 + `ToolProgress`), `app/src/main/java/com/csh/blogwriter/chat/ConversationEngine.kt`, `app/src/main/java/com/csh/blogwriter/chat/ChatContext.kt`
- Test: `app/src/test/java/com/csh/blogwriter/chat/ConversationEngineTest.kt`

**Interfaces:**
- Consumes: `GeminiClient`, `GRequest`/`GResponse`, `KeyRotator`, `ApiKeyStore`, `ModelPolicy`, `PromptBuilder`, `MemoryRepository`, `TurnSchemas`, `TurnResponseJson`, `PostContentRepair`.
- Produces:
  - `interface ToolExecutor { suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit): JsonObject }` — 이름이 모르는 것이면 `{"error":"unknown tool"}`.
  - `data class Attachment(val ref: String, val jpegBase64: String, val mimeType: String = "image/jpeg")`
  - `data class ChatContext(val history: List<ChatMessage>, val attachments: List<Attachment>, val style: String?, val draftTurn: Boolean, val currentPost: PostContent?)`
  - `sealed interface TurnResult { data class Success(val response: TurnResponse, val repairs: List<String>, val usedModel: String) : TurnResult; data class Failure(val reason: Reason, val retryAt: Long? = null, val detail: String) : TurnResult; enum class Reason { NO_KEY, RATE_LIMITED, NETWORK, BAD_RESPONSE, OTHER } }`
  - `interface TurnRunner { suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult }`, `interface TurnListener { fun onToolStatus(text: String); fun onPartialSay(text: String) }` — **스트리밍**: 엔진은 `client.generateStream` 을 쓰고, 텍스트 청크를 누적하며 `PartialSayExtractor` 로 `"say":"…` 값의 현재 접두를 뽑아 `onPartialSay` 로 흘려보낸다(JSON 이스케이프 해제, 닫는 따옴표 전까지). 완료 후 전체 텍스트를 `TurnResponseJson.decode`. 테스트의 `onProgress` 람다 자리는 `TurnListener` 구현으로 바꾼다(`onToolStatus` 가 기존 progress).
  - `object PartialSayExtractor { fun extract(partialJson: String): String? }` (순수 Kotlin, 테스트).
  - `class ConversationEngine(client, keyStore, rotatorFactory: (List<String>, List<String>) -> KeyRotator, policyProvider: suspend () -> ModelPolicy, promptBuilder, memory, toolsFactory: () -> ToolExecutor, clock) : TurnRunner`. 규칙: 키 없음 → `NO_KEY`; `rotator.next()` null → `RATE_LIMITED(retryAt)`; 429 → `report(RATE_LIMITED)` 후 다음 pick으로 재시도(최대 키 수 × 모델 수); INVALID_KEY → `keyStore.markInvalid` + 다음; 5xx/네트워크 → 1회 재시도 후 다음 pick; 400(BAD_REQUEST)이고 요청에 `responseJsonSchema`가 있었으면 스키마 없이 같은 pick으로 1회 재요청("JSON만 출력" 지시 추가); 응답 `functionCalls` 있으면 실행 → `functionResponse` 파트 추가 → 최대 6회 루프; 텍스트 응답을 `TurnResponseJson.decode` — 실패 시 온도 0으로 1회 재요청 → 실패면 `BAD_RESPONSE`; 성공 시 `post`가 있으면 `PostContentRepair`; 사용된 메모리 `touch`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/chat/ConversationEngineTest.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.llm.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationEngineTest {
    private val server = MockWebServer()
    private val keys = MutableStateFlow(listOf(ApiKey("k1", "SECRET1", 0, lastOkAt = 1), ApiKey("k2", "SECRET2", 0, lastOkAt = 1)))
    private val keyStore = object : ApiKeyStore {
        override val keys: Flow<List<ApiKey>> = this@ConversationEngineTest.keys
        override val hasUsableKey: Flow<Boolean> = keys.map { l -> l.any { it.usable } }
        override suspend fun add(secrets: List<String>) = emptyList<ApiKey>()
        override suspend fun remove(id: String) {}
        override suspend fun markOk(id: String) {}
        override suspend fun markLimited(id: String) {}
        override suspend fun markInvalid(id: String) { this@ConversationEngineTest.keys.value = this@ConversationEngineTest.keys.value.map { if (it.id == id) it.copy(disabled = true) else it } }
    }
    private val memory = object : MemoryRepository {
        val touched = mutableListOf<Long>()
        override fun observeAll() = flowOf(emptyList<MemoryItem>())
        override suspend fun activeItems(limit: Int) = listOf(MemoryItem(7, MemoryKind.PREFERENCE, "가격은 정확히", "chat", 0, true, null))
        override suspend fun add(kind: MemoryKind, text: String, source: String) = MemoryItem(1, kind, text, source, 0, true, null)
        override suspend fun update(id: Long, text: String) {}
        override suspend fun setEnabled(id: Long, enabled: Boolean) {}
        override suspend fun delete(id: Long) {}
        override suspend fun touch(ids: List<Long>) { touched += ids }
    }
    private val promptStore = object : PromptStore {
        override suspend fun text(section: PromptSection) = "[${section.name}] {{style}} {{memory}} {{minLen}} {{maxLen}}"
        override fun observe(section: PromptSection): Flow<String> = flowOf(text(section))
        override suspend fun override(section: PromptSection, text: String?) {}
        override suspend fun isOverridden(section: PromptSection) = false
    }
    private val toolCalls = mutableListOf<String>()
    private val tools = object : ToolExecutor {
        override suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit): JsonObject {
            toolCalls += name; onProgress("검색 중"); return buildJsonObject { put("results", "원주 한우 주소 …") }
        }
    }
    private var now = 0L
    private lateinit var engine: ConversationEngine

    @Before fun setUp() {
        server.start()
        val client = GeminiClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        engine = ConversationEngine(client, keyStore, { k, m -> KeyRotator(k, m) { now } }, { ModelPolicy(listOf("flash", "lite")) }, PromptBuilder(promptStore), memory, tools) { now }
    }
    @After fun tearDown() = server.shutdown()

    private fun ctx(draft: Boolean = false) = ChatContext(
        history = listOf(ChatMessage(1, "s", 0, MessageRole.USER, MessageKind.TEXT, "{\"text\":\"원주 한우 다녀왔어요\"}", 0)),
        attachments = listOf(Attachment("img_001", "AAAA")), style = null, draftTurn = draft, currentPost = null,
    )
    private fun textResponse(json: String) = MockResponse().setBody("""{"candidates":[{"content":{"role":"model","parts":[{"text":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), json)}}]}}]}""")

    @Test
    fun happyTurnBuildsRequestAndParsesResponse() = runTest {
        server.enqueue(textResponse("""{"say":"이렇게 써 볼까요?","plan":{"titleCandidates":["a","b","c"],"outline":[],"tone":"t"},"quickReplies":["1번"],"readyToDraft":false}"""))
        val r = engine.runTurn(ctx()) {} as TurnResult.Success
        assertEquals("이렇게 써 볼까요?", r.response.say); assertEquals("flash", r.usedModel)
        val req = server.takeRequest(); val body = req.body.readUtf8()
        assertEquals("SECRET1", req.getHeader("x-goog-api-key"))
        assertTrue(body.contains("\"inlineData\"")); assertTrue(body.contains("\"responseJsonSchema\"")); assertTrue(body.contains("web_search"))
        assertTrue(body.contains("[ROLE]")); assertTrue(body.contains("가격은 정확히"))
        assertEquals(listOf(7L), memory.touched)
    }

    @Test
    fun toolLoopFeedsFunctionResponseBack() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"web_search","args":{"query":"원주 한우"}}}]}}]}"""))
        server.enqueue(textResponse("""{"say":"찾았어요","quickReplies":[],"readyToDraft":false}"""))
        val progress = mutableListOf<String>()
        val r = engine.runTurn(ctx()) { progress += it } as TurnResult.Success
        assertEquals("찾았어요", r.response.say); assertEquals(listOf("web_search"), toolCalls); assertEquals(listOf("검색 중"), progress)
        server.takeRequest(); val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("\"functionResponse\"")); assertTrue(second.contains("원주 한우 주소"))
    }

    @Test
    fun rateLimitRotatesToNextKeyAndInvalidKeyIsDisabled() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"q"}}"""))
        server.enqueue(textResponse("""{"say":"두 번째 키로","quickReplies":[],"readyToDraft":false}"""))
        val r = engine.runTurn(ctx()) {} as TurnResult.Success
        assertEquals("두 번째 키로", r.response.say)
        server.takeRequest(); assertEquals("SECRET2", server.takeRequest().getHeader("x-goog-api-key"))

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"status":"PERMISSION_DENIED","message":"bad"}}"""))
        server.enqueue(textResponse("""{"say":"ok","quickReplies":[],"readyToDraft":false}"""))
        engine.runTurn(ctx()) {}
        assertTrue(keys.value.any { it.disabled })
    }

    @Test
    fun allKeysExhaustedReturnsRateLimitedWithRetryAt() = runTest {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"q"}}""")) }
        repeat(2) { server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"q"}}""")) }  // lite 모델도
        val r = engine.runTurn(ctx()) {} as TurnResult.Failure
        assertEquals(TurnResult.Reason.RATE_LIMITED, r.reason); assertEquals(now + KeyRotator.KEY_COOLDOWN_MS, r.retryAt)
    }

    @Test
    fun schemaRejectionRetriesWithoutSchemaAndDraftIsRepaired() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"response_json_schema is not supported with tools"}}"""))
        server.enqueue(textResponse("""{"say":"초안이에요","quickReplies":[],"readyToDraft":true,"post":{"title":"","blocks":[{"type":"paragraph","runs":[{"text":"본문 문단"}]},{"type":"image","ref":"img_999"}]}}"""))
        val r = engine.runTurn(ctx(draft = true)) {} as TurnResult.Success
        server.takeRequest(); val second = server.takeRequest().body.readUtf8()
        assertTrue(!second.contains("responseJsonSchema"))
        assertEquals("본문 문단", r.response.post!!.title); assertEquals("img_001", (r.response.post!!.blocks[1] as com.csh.blogwriter.domain.model.Block.Image).ref)
        assertTrue(r.repairs.isNotEmpty())
    }

    @Test
    fun noUsableKeyFailsFast() = runTest {
        keys.value = emptyList()
        assertEquals(TurnResult.Reason.NO_KEY, (engine.runTurn(ctx()) {} as TurnResult.Failure).reason)
    }
}
```

- [ ] **Step 2: 실패 확인** — 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// chat/ToolExecutor.kt
package com.csh.blogwriter.chat

import kotlinx.serialization.json.JsonObject

interface ToolExecutor {
    /** 도구를 실행하고 결과 JSON을 돌려준다. 진행 문구는 onProgress 로 UI 에 전달. 절대 throw 하지 않는다(오류도 JSON 으로). */
    suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit): JsonObject
}
```

```kotlin
// chat/ChatContext.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.domain.model.PostContent

data class Attachment(val ref: String, val jpegBase64: String, val mimeType: String = "image/jpeg")

data class ChatContext(
    val history: List<ChatMessage>,
    val attachments: List<Attachment>,
    val style: String?,
    val draftTurn: Boolean,
    val currentPost: PostContent?,
)

sealed interface TurnResult {
    data class Success(val response: TurnResponse, val repairs: List<String>, val usedModel: String) : TurnResult
    data class Failure(val reason: Reason, val retryAt: Long? = null, val detail: String = "") : TurnResult
    enum class Reason { NO_KEY, RATE_LIMITED, NETWORK, BAD_RESPONSE, OTHER }
}
```

```kotlin
// chat/ConversationEngine.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.domain.model.PostContentJson
import com.csh.blogwriter.llm.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 한 턴 = 시스템 프롬프트 조립 → (키, 모델) 선택 → generateContent → 도구 루프 → JSON 파싱 → post 보정.
 * 키 로테이션·모델 다운그레이드는 KeyRotator, 도구 실행은 ToolExecutor 에 위임. UI 를 모른다.
 */
class ConversationEngine(
    private val client: GeminiClient,
    private val keyStore: ApiKeyStore,
    private val rotatorFactory: (keyIds: List<String>, models: List<String>) -> KeyRotator,
    private val policyProvider: suspend () -> ModelPolicy,
    private val promptBuilder: PromptBuilder,
    private val memory: MemoryRepository,
    private val tools: ToolExecutor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object { const val MAX_TOOL_ROUNDS = 6; private const val JSON_ONLY_HINT = "\n\n반드시 JSON 객체 하나만 출력하세요. 코드 펜스나 설명을 붙이지 마세요." }

    private var rotator: KeyRotator? = null
    private var rotatorKeys: List<String> = emptyList()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun runTurn(ctx: ChatContext, onProgress: (String) -> Unit): TurnResult {
        val keys = keyStore.keysOnce().filter { it.usable }
        if (keys.isEmpty()) return TurnResult.Failure(TurnResult.Reason.NO_KEY)
        val policy = policyProvider()
        val ids = keys.map { it.id }
        if (rotator == null || rotatorKeys != ids) { rotator = rotatorFactory(ids, policy.models); rotatorKeys = ids }
        val rot = rotator!!

        val memItems = memory.activeItems()
        val system = promptBuilder.system(memItems, ctx.style, policy.targetLength, ctx.draftTurn)
        val contents = buildContents(ctx)
        var useSchema = true
        var attempts = 0
        val maxAttempts = ids.size * policy.models.size + 2
        while (attempts++ < maxAttempts) {
            val pick = rot.next() ?: return TurnResult.Failure(TurnResult.Reason.RATE_LIMITED, rot.nextAvailableAt())
            val secret = keys.first { it.id == pick.keyId }.secret
            try {
                val result = runWithTools(secret, pick.model, system, contents, policy, useSchema, onProgress)
                rot.report(pick, KeyRotator.Outcome.SUCCESS)
                memory.touch(memItems.map { it.id })
                return result
            } catch (e: GeminiException) {
                when (e.kind) {
                    GeminiException.Kind.RATE_LIMITED -> { rot.report(pick, KeyRotator.Outcome.RATE_LIMITED); keyStore.markLimited(pick.keyId) }
                    GeminiException.Kind.INVALID_KEY -> { rot.report(pick, KeyRotator.Outcome.INVALID_KEY); keyStore.markInvalid(pick.keyId) }
                    GeminiException.Kind.BAD_REQUEST -> if (useSchema) { useSchema = false; attempts-- } else return TurnResult.Failure(TurnResult.Reason.OTHER, detail = e.message.orEmpty())
                    GeminiException.Kind.SERVER, GeminiException.Kind.NETWORK -> rot.report(pick, KeyRotator.Outcome.TRANSIENT)
                }
            } catch (e: BadResponse) {
                return TurnResult.Failure(TurnResult.Reason.BAD_RESPONSE, detail = e.message.orEmpty())
            }
        }
        return TurnResult.Failure(TurnResult.Reason.NETWORK, detail = "재시도 한도 초과")
    }

    private class BadResponse(msg: String) : Exception(msg)

    private suspend fun runWithTools(secret: String, model: String, system: String, base: List<GContent>, policy: ModelPolicy, useSchema: Boolean, onProgress: (String) -> Unit): TurnResult.Success {
        val contents = base.toMutableList()
        var temperature = policy.temperature
        var jsonRetry = false
        repeat(MAX_TOOL_ROUNDS + 2) {
            val req = GRequest(
                contents = contents,
                systemInstruction = GSystemInstruction(listOf(GPart(text = if (useSchema) system else system + JSON_ONLY_HINT))),
                tools = listOf(GTool(TurnSchemas.functionDeclarations())),
                toolConfig = GToolConfig(GFunctionCallingConfig("AUTO")),
                generationConfig = GGenerationConfig(temperature = temperature, maxOutputTokens = 8192,
                    responseMimeType = if (useSchema) "application/json" else null, responseJsonSchema = if (useSchema) TurnSchemas.turnResponseJsonSchema() else null),
            )
            val res = client.generate(secret, model, req)
            val calls = res.functionCalls
            if (calls.isNotEmpty()) {
                contents += res.candidates.first().content!!
                val responses = calls.map { call -> GPart(functionResponse = GFunctionResponse(call.name, tools.execute(call.name, call.args, onProgress))) }
                contents += GContent("user", responses)
                return@repeat
            }
            val text = res.text ?: throw BadResponse("빈 응답")
            val parsed = runCatching { TurnResponseJson.decode(text) }.getOrElse {
                if (!jsonRetry) { jsonRetry = true; temperature = 0.0; return@repeat }
                throw BadResponse("JSON 해석 실패: ${it.message}")
            }
            val attachedRefs = base.firstOrNull()?.let { extractRefs(base) } ?: emptyList()
            val repaired = parsed.post?.let { PostContentRepair.repair(it, attachedRefs) }
            return TurnResult.Success(if (repaired != null) parsed.copy(post = repaired.content) else parsed, repaired?.fixes ?: emptyList(), model)
        }
        throw BadResponse("도구 호출이 너무 많습니다")
    }

    private var lastAttachedRefs: List<String> = emptyList()
    private fun extractRefs(@Suppress("UNUSED_PARAMETER") contents: List<GContent>) = lastAttachedRefs

    /** 대화 기록 → contents. 사진은 첫 user 파트에 inlineData 로, ref 라벨을 텍스트로 함께 붙인다. 현재 post 가 있으면 마지막에 전문을 붙인다. */
    private fun buildContents(ctx: ChatContext): List<GContent> {
        lastAttachedRefs = ctx.attachments.map { it.ref }
        val out = mutableListOf<GContent>()
        if (ctx.attachments.isNotEmpty()) {
            val parts = mutableListOf<GPart>(GPart(text = "첨부 사진 (ref 순서대로):"))
            ctx.attachments.forEach { a -> parts += GPart(text = "ref=${a.ref}"); parts += GPart(inlineData = GInlineData(a.mimeType, a.jpegBase64)) }
            out += GContent("user", parts)
        }
        ctx.history.forEach { m ->
            val role = if (m.role == MessageRole.USER) "user" else "model"
            val text = when (m.kind) {
                MessageKind.TEXT -> runCatching { json.parseToJsonElement(m.payloadJson).jsonObject["text"]!!.jsonPrimitive.content }.getOrDefault(m.payloadJson)
                MessageKind.PHOTOS -> "(사진 ${runCatching { json.parseToJsonElement(m.payloadJson).jsonObject["count"]!!.jsonPrimitive.content }.getOrDefault("")}장 첨부)"
                MessageKind.PLAN, MessageKind.POST -> m.payloadJson
                MessageKind.SYSTEM -> return@forEach
            }
            if (m.role == MessageRole.SYSTEM) return@forEach
            out += GContent(role, listOf(GPart(text = text)))
        }
        if (ctx.currentPost != null) out += GContent("user", listOf(GPart(text = "현재 초안(JSON): " + PostContentJson.encode(ctx.currentPost) + "\n요청을 반영해 수정된 전체 post 를 다시 내 주세요.")))
        if (ctx.draftTurn) out += GContent("user", listOf(GPart(text = "이번 턴에는 post 를 채워 완성 초안을 내 주세요.")))
        return out
    }
}
```
구현 메모: `extractRefs` 의 임시 필드는 어색하다 — 실행자는 `runWithTools` 에 `attachedRefs: List<String>` 파라미터를 직접 넘기는 형태로 정리한다(테스트는 동작만 본다). 연속 같은 role 의 `contents` 는 Gemini 가 허용하지만, 안전하게 인접한 같은 role 을 하나로 합치는 `mergeAdjacent()` 를 추가해도 된다.

DI (`LlmModule`): `@Provides @Singleton fun conversationEngine(client, keyStore, promptBuilder, memory, tools: ToolExecutor, settings: SettingsStore) = ConversationEngine(client, keyStore, { k, m -> KeyRotator(k, m) { System.currentTimeMillis() } }, { settings.modelPolicyOnce() }, promptBuilder, memory, tools)` — `SettingsStore.modelPolicyOnce()` 는 Task 9 에서 추가되므로 이 태스크에서는 `{ ModelPolicy.DEFAULT }` 로 두고 Task 9 에서 교체한다. `ToolExecutor` 바인딩은 Task 8.

- [ ] **Step 3b: 스트리밍 반영** — 테스트의 `textResponse()` 를 SSE 두 청크(`say` 값이 중간에서 잘리도록, `Content-Type: text/event-stream`)로 바꾸고, `runTurn(ctx, listener)` 에서 `listener.onPartialSay` 가 최소 2번(누적 증가) 호출되며 마지막 값이 완성된 `say` 인지 검증하는 `streamsPartialSayWhileReceiving` 테스트를 추가한다. `PartialSayExtractorTest`: `{"say":"안녕` → `안녕`; `{"say":"안녕\"하세요` → `안녕"하세요`; `{"say":"다 왔어요","plan":` → `다 왔어요`; `{"plan":{` → null; `\u` 유니코드 이스케이프는 완성된 것만 해제. 구현: `runWithTools` 에서 `client.generateStream(...).collect { chunk -> text += chunk.text.orEmpty(); calls += chunk.functionCalls; finish = chunk.candidates.firstOrNull()?.finishReason ?: finish; PartialSayExtractor.extract(text)?.let(listener::onPartialSay) }` 로 누적한 뒤, `calls` 가 있으면 도구 루프(모델 파트는 누적한 functionCall 들로 `GContent("model", …)` 구성), 없으면 `text` 를 파싱한다. 도구 실행 중에는 `listener.onToolStatus`.

- [ ] **Step 4: 테스트 통과** — `--tests "com.csh.blogwriter.chat.ConversationEngineTest" --tests "com.csh.blogwriter.chat.PartialSayExtractorTest"` PASS.
- [ ] **Step 5: 커밋** — `git commit -m "Add conversation engine with tool loop and key rotation"`

---

### Task 8: WebResearchTool + ToolExecutor 구현

**Files:**
- Create: `app/src/main/assets/research_extract.js`, `app/src/main/java/com/csh/blogwriter/research/HiddenWebView.kt`, `app/src/main/java/com/csh/blogwriter/research/WebResearchTool.kt`, `app/src/main/java/com/csh/blogwriter/chat/DefaultToolExecutor.kt`
- Modify: `di/LlmModule.kt` (`ToolExecutor` 바인딩)
- Test: `app/src/test/java/com/csh/blogwriter/chat/DefaultToolExecutorTest.kt` (검색 툴은 인터페이스로 가짜 주입)

**Interfaces:**
- Produces: `interface ResearchTool { suspend fun search(query: String): List<SearchHit>; suspend fun openPage(url: String): PageText? }`, `SearchHit(title, url, snippet)`, `PageText(title, text)`; `WebResearchTool(context, enabled: suspend () -> Boolean)` (숨은 WebView, 메인 스레드에서 생성, 8/10초 제한, 네이버 → 구글 폴백); `DefaultToolExecutor(research: ResearchTool, memory: MemoryRepository, limits)`: `web_search` 턴당 2회·`open_page` 2회·`remember` 2회 초과 시 `{"error":"limit"}`; 진행 문구 spec §7.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/chat/DefaultToolExecutorTest.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.research.PageText
import com.csh.blogwriter.research.ResearchTool
import com.csh.blogwriter.research.SearchHit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultToolExecutorTest {
    private val research = object : ResearchTool {
        override suspend fun search(query: String) = listOf(SearchHit("원주 한우 맛집", "https://blog.naver.com/x/1", "요약"))
        override suspend fun openPage(url: String) = PageText("제목", "본문 텍스트")
    }
    private val added = mutableListOf<Pair<MemoryKind, String>>()
    private val memory = object : MemoryRepository {
        override fun observeAll() = flowOf(emptyList<MemoryItem>())
        override suspend fun activeItems(limit: Int) = emptyList<MemoryItem>()
        override suspend fun add(kind: MemoryKind, text: String, source: String) = MemoryItem(1, kind, text, source, 0, true, null).also { added += kind to text }
        override suspend fun update(id: Long, text: String) {}
        override suspend fun setEnabled(id: Long, enabled: Boolean) {}
        override suspend fun delete(id: Long) {}
        override suspend fun touch(ids: List<Long>) {}
    }

    @Test
    fun searchReturnsHitsWithProgressAndLimits() = runTest {
        val ex = DefaultToolExecutor(research, memory)
        val progress = mutableListOf<String>()
        val r = ex.execute("web_search", buildJsonObject { put("query", "원주 한우") }) { progress += it }
        assertEquals("원주 한우 맛집", r["results"]!!.jsonArray[0].let { it.jsonObject()["title"]!!.jsonPrimitive.content })
        assertTrue(progress[0].contains("네이버에서 '원주 한우'"))
        ex.execute("web_search", buildJsonObject { put("query", "b") }) {}
        val third = ex.execute("web_search", buildJsonObject { put("query", "c") }) {}
        assertEquals("limit", third["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun rememberStoresAndReportsAndUnknownToolErrors() = runTest {
        val ex = DefaultToolExecutor(research, memory)
        val r = ex.execute("remember", buildJsonObject { put("kind", "PREFERENCE"); put("text", "가격은 정확히 적기") }) {}
        assertEquals(true, r["saved"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf(MemoryKind.PREFERENCE to "가격은 정확히 적기"), added)
        assertEquals("unknown tool", ex.execute("nope", buildJsonObject {}) {}["error"]!!.jsonPrimitive.content)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObject() = kotlinx.serialization.json.jsonObject.let { this.kotlinx.serialization.json.jsonObject }
}
```
(마지막 헬퍼는 `import kotlinx.serialization.json.jsonObject` 로 대체하고 `it.jsonObject["title"]` 로 쓴다 — 실행자가 정리.)

- [ ] **Step 2: 실패 확인** — 컴파일 실패.

- [ ] **Step 3: 구현**

```kotlin
// research/ResearchTool.kt  (WebResearchTool.kt 상단에 두어도 됨)
package com.csh.blogwriter.research

data class SearchHit(val title: String, val url: String, val snippet: String)
data class PageText(val title: String, val text: String)

interface ResearchTool {
    suspend fun search(query: String): List<SearchHit>
    suspend fun openPage(url: String): PageText?
}
```

```kotlin
// research/HiddenWebView.kt
package com.csh.blogwriter.research

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.csh.blogwriter.publish.NaverWebViewConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 화면에 붙지 않는 WebView 하나를 재사용한다. 모든 호출은 메인 스레드에서 직렬로. */
class HiddenWebView(private val context: Context) {
    private var web: WebView? = null
    private var onLoaded: ((String) -> Unit)? = null

    private fun view(): WebView = web ?: WebView(context).also { w ->
        NaverWebViewConfig.apply(w)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) { onLoaded?.invoke(url) }
        }
        web = w
    }

    /** url 을 로드하고 onPageFinished 후 script 를 실행해 문자열 결과를 돌려준다. timeout 시 null. */
    suspend fun loadAndExtract(url: String, script: String, timeoutMs: Long): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            val w = view()
            suspendCancellableCoroutine<Unit> { cont -> onLoaded = { if (cont.isActive) cont.resume(Unit) }; w.loadUrl(url) }
            kotlinx.coroutines.delay(600) // 검색 결과가 JS 로 늦게 렌더되는 경우
            suspendCancellableCoroutine<String?> { cont -> w.evaluateJavascript(script) { r -> if (cont.isActive) cont.resume(r) } }
        }
    }

    fun destroy() { web?.destroy(); web = null }
}
```

```kotlin
// research/WebResearchTool.kt
package com.csh.blogwriter.research

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebResearchTool @Inject constructor(@ApplicationContext private val context: Context) : ResearchTool {
    private val hidden by lazy { HiddenWebView(context) }
    private val script by lazy { context.assets.open("research_extract.js").bufferedReader().readText() }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String): List<SearchHit> {
        val q = Uri.encode(query)
        val naver = extract("https://search.naver.com/search.naver?where=view&query=$q", "__research.searchNaver()", 8_000)
        if (naver.isNotEmpty()) return naver
        return extract("https://www.google.com/search?hl=ko&q=$q", "__research.searchGoogle()", 8_000)
    }

    override suspend fun openPage(url: String): PageText? {
        if (!url.startsWith("http")) return null
        val raw = hidden.loadAndExtract(url, "$script; __research.pageText()", 10_000) ?: return null
        val obj = runCatching { json.parseToJsonElement(unquote(raw)).jsonObject }.getOrNull() ?: return null
        return PageText(obj["title"]?.jsonPrimitive?.content.orEmpty(), obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000))
    }

    private suspend fun extract(url: String, call: String, timeout: Long): List<SearchHit> {
        val raw = hidden.loadAndExtract(url, "$script; $call", timeout) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(unquote(raw)).jsonArray.map { it.jsonObject }.map { SearchHit(it["title"]!!.jsonPrimitive.content, it["url"]!!.jsonPrimitive.content, it["snippet"]?.jsonPrimitive?.content.orEmpty()) }
        }.getOrDefault(emptyList()).take(5)
    }

    /** evaluateJavascript 는 문자열 결과를 JSON 문자열 리터럴로 돌려준다 → 한 겹 벗긴다. */
    private fun unquote(raw: String): String = runCatching { json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw)
}
```

```javascript
// app/src/main/assets/research_extract.js
// 검색 결과·본문 추출. 반환은 항상 JSON 문자열. 실패는 빈 배열/빈 객체.
window.__research = window.__research || (function () {
  function txt(el) { return (el && (el.innerText || el.textContent) || '').replace(/\s+/g, ' ').trim(); }
  function hits(anchors, limit) {
    var out = [], seen = {};
    for (var i = 0; i < anchors.length && out.length < limit; i++) {
      var a = anchors[i]; var href = a.href || ''; var title = txt(a);
      if (!/^https?:/.test(href) || !title || seen[href]) continue;
      if (/naver\.com\/search|google\.com\//.test(href)) continue;
      seen[href] = 1;
      var card = a.closest('li, article, div');
      var snippet = card ? txt(card).slice(0, 200) : '';
      out.push({ title: title.slice(0, 80), url: href, snippet: snippet });
    }
    return out;
  }
  function searchNaver() {
    try {
      var anchors = document.querySelectorAll('a.title_link, a.api_txt_lines, a[class*="title"], .view_wrap a, .total_wrap a');
      return JSON.stringify(hits(anchors, 5));
    } catch (e) { return '[]'; }
  }
  function searchGoogle() {
    try { return JSON.stringify(hits(document.querySelectorAll('a h3'), 5).map(function (h) { return h; })); } catch (e) { return '[]'; }
  }
  // 구글은 <a><h3>제목</h3></a> 구조: h3 의 부모 a 를 사용
  function searchGoogleFixed() {
    try {
      var h3s = document.querySelectorAll('a h3'); var anchors = [];
      for (var i = 0; i < h3s.length; i++) anchors.push(h3s[i].closest('a'));
      return JSON.stringify(hits(anchors, 5));
    } catch (e) { return '[]'; }
  }
  function pageText() {
    try {
      var root = document.querySelector('article, .se-main-container, #postViewArea, main, #content') || document.body;
      var clone = root.cloneNode(true);
      clone.querySelectorAll('script, style, nav, header, footer, aside, iframe').forEach(function (n) { n.remove(); });
      return JSON.stringify({ title: document.title, text: txt(clone).slice(0, 4000) });
    } catch (e) { return '{}'; }
  }
  return { searchNaver: searchNaver, searchGoogle: searchGoogleFixed, pageText: pageText };
})();
```
(`searchGoogle` 중간 버전은 지우고 `searchGoogleFixed` 만 남긴다 — 실행자 정리.)

```kotlin
// chat/DefaultToolExecutor.kt
package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.research.ResearchTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/** 턴마다 새로 만든다(횟수 제한은 턴 단위). */
class DefaultToolExecutor @Inject constructor(private val research: ResearchTool, private val memory: MemoryRepository) : ToolExecutor {
    private val counts = HashMap<String, Int>()
    private val limits = mapOf("web_search" to 2, "open_page" to 2, "remember" to 2)

    override suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit): JsonObject {
        val used = counts.getOrDefault(name, 0)
        val limit = limits[name] ?: return buildJsonObject { put("error", "unknown tool") }
        if (used >= limit) return buildJsonObject { put("error", "limit") }
        counts[name] = used + 1
        return try {
            when (name) {
                "web_search" -> {
                    val q = args["query"]?.jsonPrimitive?.content.orEmpty()
                    onProgress("네이버에서 '$q' 정보를 찾고 있어요…")
                    buildJsonObject { put("results", buildJsonArray { research.search(q).forEach { h -> add(buildJsonObject { put("title", h.title); put("url", h.url); put("snippet", h.snippet) }) } }) }
                }
                "open_page" -> {
                    val url = args["url"]?.jsonPrimitive?.content.orEmpty()
                    onProgress("'${runCatching { java.net.URI(url).host }.getOrNull() ?: url}' 페이지를 읽고 있어요…")
                    val page = research.openPage(url)
                    if (page == null) buildJsonObject { put("error", "페이지를 열 수 없음") } else buildJsonObject { put("title", page.title); put("text", page.text) }
                }
                "remember" -> {
                    onProgress("기억해 둘게요…")
                    val kind = runCatching { MemoryKind.valueOf(args["kind"]!!.jsonPrimitive.content) }.getOrDefault(MemoryKind.PREFERENCE)
                    val item = memory.add(kind, args["text"]?.jsonPrimitive?.content.orEmpty(), "chat")
                    buildJsonObject { put("saved", true); put("id", item.id) }
                }
                else -> buildJsonObject { put("error", "unknown tool") }
            }
        } catch (e: Exception) { buildJsonObject { put("error", e.message ?: "실패") } }
    }
}
```
DI: `@Binds abstract fun researchTool(impl: WebResearchTool): ResearchTool`; `ToolExecutor` 는 턴마다 새 인스턴스가 필요하므로 `ConversationEngine` 생성자에 `toolsFactory: () -> ToolExecutor` 를 받도록 Task 7 서명을 바꾼다(테스트에서는 `{ tools }`); Hilt 에서는 `Provider<DefaultToolExecutor>` 로 `{ provider.get() }`.

- [ ] **Step 4: 테스트 통과** — `--tests "com.csh.blogwriter.chat.DefaultToolExecutorTest"` PASS + `assembleDebug`. 에뮬레이터 수동 확인은 Task 13 에서.
- [ ] **Step 5: 커밋** — `git commit -m "Add hidden-WebView research tool and tool executor"`

---

### Task 9: SettingsStore 확장 + PIN 게이트 + 관리자 화면(설정 목록·API 키·모델)

**Files:**
- Modify: `app/src/main/java/com/csh/blogwriter/data/prefs/SettingsStore.kt` (PIN 해시·솔트, 모델 정책, 검색 툴 토글, 글 길이)
- Create: `app/src/main/java/com/csh/blogwriter/admin/PinManager.kt` (해시·잠금 정책, 순수 Kotlin + 저장소)
- Create: `app/src/main/java/com/csh/blogwriter/ui/admin/PinGateScreen.kt`, `PinViewModel.kt`, `SettingsScreen.kt`, `ApiKeysScreen.kt`, `ApiKeysViewModel.kt`, `ModelsScreen.kt`, `ModelsViewModel.kt`
- Modify: `ui/navigation/Routes.kt` (`Admin`, `ApiKeys`, `Models`, `Prompts`, `Memory`), `AppNavHost.kt` (톱니 → `Admin` = PinGate → Settings), `ui/home/HomeScreen.kt` (톱니 목적지 변경)
- Test: `app/src/test/java/com/csh/blogwriter/admin/PinManagerTest.kt`, `app/src/test/java/com/csh/blogwriter/ui/admin/ApiKeysViewModelTest.kt`

**Interfaces:**
- `SettingsStore` 추가: `pinHash: Flow<String?>`, `setPinHash(hash: String?)`, `modelPolicy: Flow<ModelPolicy>`, `setModelPolicy(policy)`, `suspend fun modelPolicyOnce()`, `researchEnabled: Flow<Boolean>`, `setResearchEnabled`. `ModelPolicy` 직렬화는 `models` 를 쉼표 문자열, `targetLength` 를 `min..max` 문자열로 저장.
- `PinManager(settings, clock)`: `suspend fun isSet(): Boolean`, `suspend fun set(pin)`, `suspend fun verify(pin): VerifyResult { OK, WRONG(remaining), LOCKED(untilMs) }` — SHA-256(salt+pin), 5회 실패 시 30초 잠금(메모리 상태), PIN은 4~6자리 숫자만.
- `ApiKeysViewModel(keyStore, client: GeminiClient)`: `uiState: StateFlow<ApiKeysUiState(keys: List<ApiKey>, candidates: List<Candidate>, busy: Boolean)>`, `Candidate(secret, status: PENDING|VALID|INVALID|LIMITED|ERROR)`, `onInput(text)` (파서 실행 → candidates), `register()` (후보별 `client.listModels` → 유효만 `keyStore.add` + `markOk`), `remove(id)`.
- `ModelsViewModel`: 모델 목록 편집(문자열 2개), 온도, 길이 범위, 검색 툴 토글.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/admin/PinManagerTest.kt
package com.csh.blogwriter.admin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.csh.blogwriter.data.prefs.DataStoreSettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinManagerTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 0L
    private fun manager() = PinManager(DataStoreSettingsStore(PreferenceDataStoreFactory.create { folder.newFile("s.preferences_pb") })) { now }

    @Test
    fun setVerifyAndLockout() = runTest {
        val m = manager()
        assertFalse(m.isSet())
        m.set("1234")
        assertTrue(m.isSet())
        assertEquals(PinManager.VerifyResult.OK, m.verify("1234"))
        repeat(4) { assertTrue(m.verify("0000") is PinManager.VerifyResult.WRONG) }
        val locked = m.verify("0000")
        assertTrue(locked is PinManager.VerifyResult.LOCKED)
        assertEquals(now + PinManager.LOCK_MS, (locked as PinManager.VerifyResult.LOCKED).untilMs)
        assertTrue(m.verify("1234") is PinManager.VerifyResult.LOCKED)
        now += PinManager.LOCK_MS + 1
        assertEquals(PinManager.VerifyResult.OK, m.verify("1234"))
    }

    @Test
    fun rejectsNonDigitsOrBadLength() = runTest {
        val m = manager()
        assertFalse(PinManager.isValidPin("12a4")); assertFalse(PinManager.isValidPin("123")); assertTrue(PinManager.isValidPin("123456"))
    }
}
```

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/admin/ApiKeysViewModelTest.kt
package com.csh.blogwriter.ui.admin

import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GeminiClient
import com.csh.blogwriter.llm.GeminiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiKeysViewModelTest {
    private val server = MockWebServer()
    private val stored = MutableStateFlow<List<ApiKey>>(emptyList())
    private val keyStore = object : ApiKeyStore {
        override val keys: Flow<List<ApiKey>> = stored
        override val hasUsableKey: Flow<Boolean> = stored.map { l -> l.any { it.usable } }
        override suspend fun add(secrets: List<String>): List<ApiKey> { val added = secrets.map { ApiKey(it, it, 0) }; stored.value = stored.value + added; return added }
        override suspend fun remove(id: String) { stored.value = stored.value.filterNot { it.id == id } }
        override suspend fun markOk(id: String) { stored.value = stored.value.map { if (it.id == id) it.copy(lastOkAt = 1) else it } }
        override suspend fun markLimited(id: String) {}
        override suspend fun markInvalid(id: String) {}
    }
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()); server.start() }
    @After fun tearDown() { Dispatchers.resetMain(); server.shutdown() }

    @Test
    fun parsesCandidatesAndRegistersOnlyValidOnes() = runTest {
        val vm = ApiKeysViewModel(keyStore, GeminiClient(OkHttpClient(), server.url("/").toString().trimEnd('/')))
        vm.onInput("AQ.Ab8RN6validvalidvalidvalid\nAQ.Ab8RN6invalidinvalidinvalidx\nshort")
        assertEquals(2, vm.uiState.value.candidates.size)
        server.enqueue(MockResponse().setBody("{\"models\":[]}"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"status":"PERMISSION_DENIED","message":"no"}}"""))
        vm.register(); advanceUntilIdle()
        assertEquals(listOf(Candidate.Status.VALID, Candidate.Status.INVALID), vm.uiState.value.candidates.map { it.status })
        assertEquals(1, stored.value.size); assertEquals(true, stored.value[0].usable)
    }
}
```

- [ ] **Step 2: 실패 확인** — 컴파일 실패.

- [ ] **Step 3: 구현 (핵심 코드)**

`SettingsStore` 추가분:
```kotlin
val pinHash: Flow<String?>; suspend fun setPinHash(hash: String?)
val modelPolicy: Flow<ModelPolicy>; suspend fun setModelPolicy(policy: ModelPolicy); suspend fun modelPolicyOnce(): ModelPolicy = modelPolicy.first()
val researchEnabled: Flow<Boolean>; suspend fun setResearchEnabled(enabled: Boolean)
// DataStore 키: pin_hash, model_list("a,b"), model_temperature(String), target_length("900..1400"), research_enabled(Boolean, 기본 true)
```

```kotlin
// admin/PinManager.kt
package com.csh.blogwriter.admin

import com.csh.blogwriter.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import javax.inject.Inject

class PinManager @Inject constructor(private val settings: SettingsStore, private val clock: () -> Long = System::currentTimeMillis) {
    sealed interface VerifyResult { data object OK : VerifyResult; data class WRONG(val remaining: Int) : VerifyResult; data class LOCKED(val untilMs: Long) : VerifyResult }
    companion object {
        const val MAX_FAILURES = 5; const val LOCK_MS = 30_000L; private const val SALT = "blogwriter-pin-v1"
        fun isValidPin(pin: String) = pin.length in 4..6 && pin.all(Char::isDigit)
        fun hash(pin: String): String = MessageDigest.getInstance("SHA-256").digest((SALT + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private var failures = 0
    private var lockedUntil = 0L

    suspend fun isSet() = settings.pinHash.first() != null
    suspend fun set(pin: String) { require(isValidPin(pin)); settings.setPinHash(hash(pin)); failures = 0 }
    suspend fun verify(pin: String): VerifyResult {
        val now = clock()
        if (lockedUntil > now) return VerifyResult.LOCKED(lockedUntil)
        if (settings.pinHash.first() == hash(pin)) { failures = 0; return VerifyResult.OK }
        failures++
        if (failures >= MAX_FAILURES) { failures = 0; lockedUntil = now + LOCK_MS; return VerifyResult.LOCKED(lockedUntil) }
        return VerifyResult.WRONG(MAX_FAILURES - failures)
    }
}
```
(Hilt: `clock` 기본값을 쓰는 보조 생성자 또는 `@Provides` 로 제공.)

`ApiKeysViewModel`:
```kotlin
data class Candidate(val secret: String, val status: Status) { enum class Status { PENDING, VALID, INVALID, LIMITED, ERROR }; val masked get() = "…" + secret.takeLast(4) }
data class ApiKeysUiState(val keys: List<ApiKey> = emptyList(), val candidates: List<Candidate> = emptyList(), val busy: Boolean = false)

@HiltViewModel
class ApiKeysViewModel @Inject constructor(private val keyStore: ApiKeyStore, private val client: GeminiClient) : ViewModel() {
    private val candidates = MutableStateFlow<List<Candidate>>(emptyList()); private val busy = MutableStateFlow(false)
    val uiState = combine(keyStore.keys, candidates, busy) { k, c, b -> ApiKeysUiState(k, c, b) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApiKeysUiState())
    fun onInput(text: String) { viewModelScope.launch { val existing = keyStore.keysOnce().map { it.secret }.toSet(); candidates.value = ApiKeyParser.parse(text, existing).map { Candidate(it, Candidate.Status.PENDING) } } }
    fun register() = viewModelScope.launch {
        busy.value = true
        val results = candidates.value.map { c ->
            val status = try { if (client.listModels(c.secret)) Candidate.Status.VALID else Candidate.Status.ERROR }
            catch (e: GeminiException) { when (e.kind) { GeminiException.Kind.INVALID_KEY -> Candidate.Status.INVALID; GeminiException.Kind.RATE_LIMITED -> Candidate.Status.LIMITED; else -> Candidate.Status.ERROR } }
            c.copy(status = status)
        }
        val valid = results.filter { it.status == Candidate.Status.VALID || it.status == Candidate.Status.LIMITED }
        keyStore.add(valid.map { it.secret }).forEach { keyStore.markOk(it.id) }
        candidates.value = results; busy.value = false
    }
    fun remove(id: String) = viewModelScope.launch { keyStore.remove(id) }
}
```

화면(디자인 가이드 §5 컴포넌트만 사용):
- `PinGateScreen(onPassed)`: `title1` "관리자 확인" / PIN 미설정이면 "관리자 비밀번호를 정해 주세요"(두 번 입력) / 설정됐으면 숫자 입력(`AppTextField`, `KeyboardType.NumberPassword`) + BottomCta "확인"; 틀리면 `InlineBanner(Danger, "비밀번호가 달라요. N번 더 틀리면 30초 동안 잠겨요")`. 통과하면 `onPassed()` → `Settings`. 통과 상태는 `Admin` 백스택 엔트리의 `savedStateHandle` 에 두어 관리자 하위 화면 이동 시 다시 묻지 않는다.
- `SettingsScreen`: ListRow 목록 — "API 키"(등록 개수 부제), "모델과 글 길이", "프롬프트", "기억한 것들", "자료 검색 도구"(스위치), "실패 로그", "네이버 로그아웃"(ConfirmSheet → `NaverSession.logout()`).
- `ApiKeysScreen`: 여러 줄 `AppTextField`(label "키 붙여넣기 — 여러 개면 줄마다 하나씩", 안내 `caption` "키는 서로 다른 프로젝트에서 발급해야 해요"), 후보 칩 목록(상태별 색: PENDING grey / VALID success / INVALID danger / LIMITED warning), BottomCta "등록"(busy 시 loading), 등록된 키 목록(ListRow: `masked` / 부제 "마지막 확인 {relative}" + 429 있으면 "· 최근 한도 초과", 삭제 아이콘 → ConfirmSheet).
- `ModelsScreen`: 기본 모델·대체 모델 텍스트 필드, 온도(0.0~1.0 슬라이더는 쓰지 않고 `AppTextField` 숫자), 글 길이 최소/최대, 저장 CTA.

네비: `Routes.Admin` → `PinGateScreen` → 통과 시 `Routes.Settings`(관리자 그래프의 시작); `Routes.ApiKeys`, `Routes.Models`, `Routes.Prompts`(Task 10), `Routes.Memory`(Task 10), `Routes.FailureLogs`(기존). Home 톱니 → `Routes.Admin`.

- [ ] **Step 4: 테스트 통과** — `--tests "com.csh.blogwriter.admin.*" --tests "com.csh.blogwriter.ui.admin.*"` PASS + `assembleDebug`. 에뮬레이터: 톱니 → PIN 설정 → 설정 목록 → API 키 화면에서 임의 문자열 붙여넣기 → 후보 칩 표시 확인(등록은 실제 키가 있어야 함).
- [ ] **Step 5: 커밋** — `git commit -m "Add admin PIN gate, settings, API key registration and model settings"`

---

### Task 10: 프롬프트 편집 화면 + 메모리 화면

**Files:**
- Create: `ui/admin/PromptsScreen.kt`, `PromptsViewModel.kt`, `ui/memory/MemoryScreen.kt`, `MemoryViewModel.kt`
- Modify: `AppNavHost.kt`
- Test: `app/src/test/java/com/csh/blogwriter/ui/memory/MemoryViewModelTest.kt`, `app/src/test/java/com/csh/blogwriter/ui/admin/PromptsViewModelTest.kt`

**Interfaces:**
- `PromptsViewModel(store: PromptStore)`: `sections: StateFlow<List<PromptSectionState(section, text, overridden)>>`, `save(section, text)`, `reset(section)`.
- `MemoryViewModel(memory: MemoryRepository)`: `items: StateFlow<List<MemoryItem>>`, `edit(id, text)`, `toggle(id, enabled)`, `delete(id)`, `add(text)` (kind = PREFERENCE, source = "manual").

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/memory/MemoryViewModelTest.kt
package com.csh.blogwriter.ui.memory

import app.cash.turbine.test
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    private val items = MutableStateFlow<List<MemoryItem>>(emptyList())
    private val repo = object : MemoryRepository {
        override fun observeAll() = items
        override suspend fun activeItems(limit: Int) = items.value.filter { it.enabled }.take(limit)
        override suspend fun add(kind: MemoryKind, text: String, source: String): MemoryItem { val m = MemoryItem((items.value.size + 1).toLong(), kind, text, source, 0, true, null); items.value = items.value + m; return m }
        override suspend fun update(id: Long, text: String) { items.value = items.value.map { if (it.id == id) it.copy(text = text) else it } }
        override suspend fun setEnabled(id: Long, enabled: Boolean) { items.value = items.value.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
        override suspend fun touch(ids: List<Long>) {}
    }
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun addEditToggleDelete() = runTest {
        val vm = MemoryViewModel(repo)
        vm.items.test {
            assertEquals(0, awaitItem().size)
            vm.add("가격은 정확히 적기"); advanceUntilIdle()
            assertEquals(MemoryKind.PREFERENCE, awaitItem()[0].kind)
            vm.edit(1, "가격은 원 단위까지"); advanceUntilIdle()
            assertEquals("가격은 원 단위까지", awaitItem()[0].text)
            vm.toggle(1, false); advanceUntilIdle()
            assertEquals(false, awaitItem()[0].enabled)
            vm.delete(1); advanceUntilIdle()
            assertEquals(0, awaitItem().size)
        }
    }
}
```

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/admin/PromptsViewModelTest.kt
package com.csh.blogwriter.ui.admin

import com.csh.blogwriter.chat.PromptSection
import com.csh.blogwriter.chat.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PromptsViewModelTest {
    private val overrides = MutableStateFlow<Map<PromptSection, String>>(emptyMap())
    private val store = object : PromptStore {
        override suspend fun text(section: PromptSection) = overrides.value[section] ?: "기본 ${section.name}"
        override fun observe(section: PromptSection): Flow<String> = overrides.map { it[section] ?: "기본 ${section.name}" }
        override suspend fun override(section: PromptSection, text: String?) { overrides.value = if (text == null) overrides.value - section else overrides.value + (section to text) }
        override suspend fun isOverridden(section: PromptSection) = section in overrides.value
    }
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun saveAndResetReflectInState() = runTest {
        val vm = PromptsViewModel(store); advanceUntilIdle()
        assertEquals(PromptSection.entries.size, vm.sections.value.size)
        vm.save(PromptSection.ROLE, "내 역할"); advanceUntilIdle()
        val role = vm.sections.value.first { it.section == PromptSection.ROLE }
        assertEquals("내 역할", role.text); assertEquals(true, role.overridden)
        vm.reset(PromptSection.ROLE); advanceUntilIdle()
        assertEquals("기본 ROLE", vm.sections.value.first { it.section == PromptSection.ROLE }.text)
    }
}
```

- [ ] **Step 2: 실패 확인** — 컴파일 실패.

- [ ] **Step 3: 구현**

`PromptsViewModel`: `sections` 는 각 섹션의 `observe` + `isOverridden` 을 합친 `combine`(8개) 으로 만든다(`overridden` 은 `observe` 가 바뀔 때 `isOverridden` 재조회). `PromptsScreen`: 섹션별 카드 — 제목(`title3`), 여러 줄 `AppTextField`(현재 텍스트, 로컬 상태로 편집), "저장" `WeakButton` + 오버라이드 상태면 "기본값으로" `DangerButton`(ConfirmSheet). 상단 안내 `body2`: "여기 내용이 글을 만드는 규칙이에요. 바꾼 뒤 저장하면 다음 대화부터 적용돼요."

`MemoryViewModel` / `MemoryScreen`: 상단 `title1` "기억한 것들" + `body2` "글을 쓸 때 참고해요. 눌러서 고치거나 지울 수 있어요." + 입력창(`AppTextField` + `WeakButton("추가")`). 목록은 `ListRow` 대신 카드형 항목: 종류 칩(`caption`, `surfaceWeak`), 텍스트(`body1`), 오른쪽 스위치(활성), 탭하면 인라인 편집(같은 자리에 `AppTextField` + "저장/취소"), 길게 누르지 않고 편집 모드에 "지우기" `DangerButton`(ConfirmSheet). 사용자도 접근: 채팅 화면 상단 메뉴 "기억한 것들"(Task 11) 과 관리자 설정 목록 둘 다 `Routes.Memory` 로.

- [ ] **Step 4: 테스트 통과** — `--tests "com.csh.blogwriter.ui.memory.*" --tests "com.csh.blogwriter.ui.admin.PromptsViewModelTest"` PASS + `assembleDebug`.
- [ ] **Step 5: 커밋** — `git commit -m "Add prompt editor and memory management screens"`

---

### Task 11: 채팅 화면(3단) + ChatViewModel + 발행 패널 연동 + 재주입

**Files:**
- Create: `ui/chat/ChatScreen.kt`, `ChatViewModel.kt`, `ChatUiModels.kt`, `components/MessageBubble.kt`, `PlanCard.kt`, `QuickReplyChips.kt`, `Composer.kt`, `SessionListPane.kt`, `ToolStatusLine.kt`
- Create: `chat/PhotoAttachments.kt` (선택 사진 → 세션 캐시 1024px JPEG + base64, `ImagePreparer` 재사용 규칙)
- Create: `speech/SpeechInput.kt` (`SpeechRecognizer` 래퍼, Flow<String>)
- Modify: `domain/publish/PublishState.kt` + `PublishStateMachine.kt` (`PublishEvent.Reinject(content)` → `Reviewing` 에서 `Injecting`+`Inject` 효과; `uploaded` 유지), `ui/publish/PublishViewModel.kt` (`reinject(content)`: job 갱신 → `Reinject` 디스패치; 새 사진이 추가된 경우는 SP2 범위 밖 — 재주입은 텍스트 변경만), `ui/navigation/Routes.kt` (`Chat(sessionId)`), `AppNavHost.kt`
- Test: `app/src/test/java/com/csh/blogwriter/domain/publish/PublishStateMachineReinjectTest.kt`, `app/src/test/java/com/csh/blogwriter/ui/chat/ChatViewModelTest.kt`

**Interfaces:**
- `ChatUiState(session: ChatSession?, messages: List<ChatMessage>, attachments: List<AttachedPhoto(ref, uri, thumb)>, thinking: Boolean, streamingSay: String?, toolStatus: String?, error: String?, quickReplies: List<String>, panelJobId: String?, panelOpen: Boolean, listCollapsed: Boolean)`
- `ChatViewModel(chatRepo, engine, pendingJobs, photoAttachments, keyStore, settings, memory)`: `open(sessionId?)`, `send(text)`, `sendQuickReply(text)`, `attachPhotos(uris)`, `removePhoto(ref)`, `requestDraft()`, `onPostRevised(post)` (엔진이 post 를 낸 턴 처리 공통), `togglePanel()`, `toggleList()`, `onPublished(url)`.
- 턴 처리: USER 메시지 저장 → `thinking = true` → `runner.runTurn(ctx, listener)` — `listener.onPartialSay(text)` 는 `uiState.streamingSay` 를 갱신해 임시 어시스턴트 말풍선에 타이핑되듯 표시(완료 시 실제 TEXT 메시지로 교체하고 `streamingSay = null`), `onToolStatus` 는 `toolStatus` → `Success`: ASSISTANT `TEXT`(say) 저장, `plan` 있으면 `PLAN` 메시지, `post` 있으면 `POST` 메시지 + `PendingJob` 생성/갱신(`sessionId` 연결, `imageUris` = 첨부 원본 uri, 제목 = post.title) + 패널 열기(이미 열려 있으면 `publishViewModel.reinject(post)`), `quickReplies` 반영; `Failure`: 사유별 사용자 문구(spec §10) 를 `SYSTEM` 메시지로.
- 초안 요청 판정: `requestDraft()`(칩 "이대로 초안 써 줘" 또는 버튼) 또는 사용자 텍스트가 `초안|써 줘|작성해` 를 포함하고 `readyToDraft` 가 직전 턴에 true 였던 경우.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/com/csh/blogwriter/domain/publish/PublishStateMachineReinjectTest.kt
package com.csh.blogwriter.domain.publish

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class PublishStateMachineReinjectTest {
    private val post = PostContent("t", listOf(Block.Paragraph(listOf(Run("x")))))
    @Test
    fun reinjectFromReviewingGoesBackToInjecting() {
        val m = PublishStateMachine(totalImages = 0, expectedComponents = 2, blogId = "b")
        val t = m.reduce(PublishState.Reviewing, PublishEvent.Reinject(post))
        assertEquals(PublishState.Injecting, t.state); assertEquals(listOf(PublishEffect.Inject), t.effects)
    }
    @Test
    fun reinjectIgnoredElsewhere() {
        val m = PublishStateMachine(0, 2, "b")
        assertEquals(PublishState.LoadingEditor, m.reduce(PublishState.LoadingEditor, PublishEvent.Reinject(post)).state)
    }
}
```

```kotlin
// app/src/test/java/com/csh/blogwriter/ui/chat/ChatViewModelTest.kt  (핵심 흐름만; 가짜 엔진)
package com.csh.blogwriter.ui.chat

import com.csh.blogwriter.chat.*
import com.csh.blogwriter.data.repo.*
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    // 가짜: ChatRepository(메모리), PendingJobRepository(메모리), ApiKeyStore(usable 1개), SettingsStore(기본), MemoryRepository(빈), PhotoAttachments(no-op)
    // TurnRunner/TurnListener 는 Task 7 에서 정의됨 (스트리밍 부분 텍스트 + 도구 상태)
    private val turns = ArrayDeque<TurnResult>()
    private val progressLines = mutableListOf<String>()
    private val runner = object : TurnRunner { override suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult { listener.onPartialSay("이렇게"); listener.onPartialSay("이렇게 써 볼까요?"); listener.onToolStatus("네이버에서 찾고 있어요…"); return turns.removeFirst() } }
    // … 가짜 리포지토리 구현은 Task 10/14 테스트의 패턴을 그대로 (생략 없이 작성할 것)

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun sendStoresUserAndAssistantMessagesAndQuickReplies() = runTest {
        turns += TurnResult.Success(TurnResponse("이렇게 써 볼까요?", plan = Plan(listOf("a", "b", "c"), emptyList(), "t"), quickReplies = listOf("1번 제목으로")), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()
        val kinds = vm.uiState.value.messages.map { it.role to it.kind }
        assertEquals(listOf(MessageRole.USER to MessageKind.TEXT, MessageRole.ASSISTANT to MessageKind.TEXT, MessageRole.ASSISTANT to MessageKind.PLAN), kinds)
        assertEquals(listOf("1번 제목으로"), vm.uiState.value.quickReplies)
        assertEquals(false, vm.uiState.value.thinking)
        assertEquals(null, vm.uiState.value.streamingSay)   // 완료 후 임시 말풍선은 사라진다
    }

    @Test
    fun draftTurnCreatesPendingJobAndOpensPanel() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", readyToDraft = true, post = PostContent("제목", listOf(Block.Paragraph(listOf(Run("본문")))))), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        assertNotNull(vm.uiState.value.panelJobId); assertTrue(vm.uiState.value.panelOpen)
        assertEquals("제목", pendingJobs.value.single().content.title)
        assertEquals(SessionStatus.PUBLISHING, vm.uiState.value.session!!.status)
    }

    @Test
    fun failureShowsSystemMessageInUserLanguage() = runTest {
        turns += TurnResult.Failure(TurnResult.Reason.RATE_LIMITED, retryAt = 60_000)
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("안녕"); advanceUntilIdle()
        val last = vm.uiState.value.messages.last()
        assertEquals(MessageKind.SYSTEM, last.kind); assertTrue(last.payloadJson.contains("잠깐 쉬어야"))
    }
}
```
실행자는 `newViewModel()` 과 가짜 저장소(`pendingJobs: MutableStateFlow<List<PendingJob>>` 등)를 완전히 작성한다. `ConversationEngine` 은 `TurnRunner` 인터페이스를 구현하도록 Task 7 클래스에 `: TurnRunner` 를 추가한다.

- [ ] **Step 2: 실패 확인** — 컴파일 실패.

- [ ] **Step 3: 상태 기계 + PublishViewModel 재주입**

`PublishEvent.Reinject(val content: PostContent)`; `reduce`: `is PublishEvent.Reinject -> if (state is Reviewing) inject() else Transition(state, emptyList())`. `PublishViewModel.reinject(content)`: `job = job?.copy(content = content)`; `pendingJobs.save(job)`; `machine` 의 `expectedComponents` 는 생성자 값이므로 `machine = PublishStateMachine(images.size, DocumentModelConverter.expectedComponentCount(content), blogId!!)` 로 교체 후 `dispatch(Reinject(content))` (상태는 `_uiState` 에 있으므로 유지됨). `Inject` 효과는 기존 `uploaded` 맵을 그대로 쓴다.

- [ ] **Step 4: ChatViewModel + 화면**

핵심 구현 지침(코드는 Task 14 의 PublishViewModel 패턴을 따른다):
- `open(sessionId)`: null 이면 `chatRepo.createSession()`; `observeMessages` 수집; `session.pendingJobId` 가 있으면 `panelJobId` 복원(패널은 닫힌 상태로 시작, "초안 열기" 칩 표시).
- `send(text)`: 키 없음(`keyStore.hasUsableKey` false) → SYSTEM "글을 쓰려면 관리자가 열쇠를 등록해야 해요" 만 추가하고 중단. 아니면 USER TEXT 저장 → `runTurn`.
- `attachPhotos(uris)`: `PhotoAttachments.prepare(sessionId, uris)` → `AttachedPhoto` 목록(ref 는 기존 개수 이어서 `img_%03d`) + PHOTOS 메시지(`{"count":n,"refs":[…],"uris":[…]}`) 저장; 세션 캐시에 1024px JPEG 과 base64 를 보관(메모리 캐시 + 파일).
- 턴 컨텍스트: `history` = 세션 메시지(SYSTEM 제외), `attachments` = 세션의 모든 사진, `style` = `memory` 의 STYLE 항목을 줄로 합친 문자열(없으면 null), `draftTurn`, `currentPost` = 마지막 POST 메시지의 post(패널 열림 상태에서만).
- 실패 문구: NO_KEY → 위 문구; RATE_LIMITED → "지금은 잠깐 쉬어야 해요. {N}분 뒤에 다시 시도할게요." (+ "다시 시도" 칩); NETWORK → "인터넷이 연결되어 있지 않아요. 연결되면 다시 보내 주세요."; BAD_RESPONSE → "잘 못 알아들었어요. 다시 말해 주세요."; OTHER → "문제가 생겼어요. 관리자에게 알려 주세요."
- 화면: `ChatScreen(sessionId, onBack, onOpenMemory)` — `BoxWithConstraints`: 가로(>= 840dp) 3단 `Row`: `SessionListPane`(280dp / 접힘 72dp) + 채팅 `Column`(weight) + `AnimatedVisibility(panelOpen)` 으로 `PublishPanel(hiltViewModel(key = jobId), Modifier.width(max(520.dp, maxWidth/2)))`; 세로: 채팅 전체 + `ModalNavigationDrawer` 로 세션 목록, 패널은 `Box` 오버레이 전체 화면(상단 "채팅으로" 버튼). 채팅 `Column`: 상단 바(세션 제목 + "기억한 것들" 텍스트 버튼 + 패널 토글), `LazyColumn`(reverseLayout=false, 자동 스크롤 끝), `ToolStatusLine`(thinking 시 점 애니메이션 + toolStatus 문구), `QuickReplyChips`, `Composer`(첨부·텍스트·마이크·보내기). 메시지 스타일은 디자인 가이드 §8.
- `PlanCard`: 제목 후보 3개 `ListRow`(탭 → `sendQuickReply("N번 제목으로: {제목}")`), 개요 목록, 톤 한 줄.
- `Composer` 마이크: `SpeechInput.start()` → 부분 결과를 입력창에 반영, 종료 시 최종 텍스트. `RECORD_AUDIO` 런타임 권한 요청(`rememberLauncherForActivityResult(RequestPermission)`), 매니페스트에 권한 추가. 에뮬레이터에는 STT 엔진이 없을 수 있으니 실패 시 토스트 "이 기기에서는 음성 입력을 쓸 수 없어요".
- 발행 완료: `PublishPanel` 의 `onDone` 에서 `chatViewModel.onPublished(url)` → 세션 `PUBLISHED` + `publishedUrl` + SYSTEM "발행했어요 🎉 {url}" 메시지 + 패널 닫기 + (Task 12) 메모리 추출 트리거.

- [ ] **Step 5: 테스트 통과** — `--tests "com.csh.blogwriter.domain.publish.PublishStateMachineReinjectTest" --tests "com.csh.blogwriter.ui.chat.*"` + 전체 suite + `assembleDebug`.
- [ ] **Step 6: 커밋** — 두 커밋: `feat: 발행 상태 기계에 재주입 이벤트 추가`, `feat: 채팅형 글쓰기 화면과 대화 뷰모델`

---

### Task 12: 홈 통합, TestCompose 제거, 발행 후 메모리 추출

**Files:**
- Modify: `ui/home/HomeScreen.kt`, `HomeViewModel.kt` (새 글 → `Chat(null)`, "이어서 쓰기" 세션 목록 3개 + 더보기, 키 없음 배너 "글을 쓰려면 관리자가 열쇠를 등록해야 해요" → 톱니와 같은 `Admin`, 기존 pending 배너는 세션이 없는 옛 작업에만)
- Delete: `ui/compose/TestComposeScreen.kt`, `TestComposeViewModel.kt`, `TestPostBuilder.kt` + 테스트, `Routes.TestCompose`
- Create: `memory/MemoryExtractor.kt` (발행 후 대화 전체를 넣어 새 기억 0~3개를 JSON 스키마로 받아 `memory.add(kind, text, "publish")`, 그 결과를 SYSTEM 메시지 "이런 점을 기억해 둘게요: …" 로), 호출은 `ChatViewModel.onPublished`
- Test: `HomeViewModelTest` 갱신, `MemoryExtractorTest`(MockWebServer 로 응답 고정)

- [ ] **Step 1~4**: 테스트 → 구현 → 통과 → `git commit -m "feat: 홈을 채팅 세션 중심으로 바꾸고 발행 후 기억을 추출"`

---

### Task 13: 에뮬레이터 종단 검증 (실제 키 필요)

- 사용자가 관리자 화면에서 키를 붙여넣어 등록한다(서브에이전트는 키를 다루지 않는다).
- 시나리오 D: 새 글 → 사진 2장 첨부 + "원주 한우 다녀왔어요" → 계획 카드(제목 3개) → 칩으로 제목 선택 → "이대로 초안 써 줘" → 오른쪽 패널 열림 → 진행 → 에디터에 글 표시 → 채팅에서 "첫 문단 더 짧게" → 재주입 → 발행(비공개) → "발행했어요 🎉" + 기억 추출 메시지.
- 시나리오 E: 키 전부 삭제 → 홈 배너/채팅 차단 문구. 시나리오 F: 기내 모드 → 네트워크 문구, 입력 보존. 시나리오 G: 도구 — "영업시간 알아봐 줘" → 상태 줄 "네이버에서 …" 표시 → 답변.
- 발견 결함은 `fix:` 커밋으로.

---

### Task 14: 문서·릴리스 준비

- `README.md`: 채팅 사용법, 관리자 설정(키·PIN·프롬프트·메모리), 한도 안내.
- `docs/superpowers/specs/2026-08-28-chat-writing-design.md` 상태를 "구현 완료(차이점 목록)" 로 갱신.
- 버전 `0.2.0`, 태그는 사용자가.

---

## 완료 기준 (스펙 §1 대응)

| 기준 | 태스크 |
|---|---|
| 1 키 게이팅·다중 등록 | 2, 3, 9, 12 |
| 2 사진+아이디어 → 3턴 내 초안 → 발행 | 6, 7, 11, 13 |
| 3 부분 수정 재주입 | 11 |
| 4 429 로테이션·다운그레이드·안내 | 2, 7, 11 |
| 5 메모리 반영·관리 | 1, 5, 8, 10, 12 |
| 6 대화 영속 | 1, 11 |
