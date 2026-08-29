# 조언 모드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 채팅 화면에서 "조언 모드" 세션을 열면 앱이 사용자의 최근 블로그 글을 읽고, 모델이 그 글의 실제 문장을 근거로 개선 조언을 하는 기능.

**Architecture:** 세션에 `mode`(WRITE/ADVICE)를 두고, 기존 `ConversationEngine`을 그대로 쓰되 모드에 따라 프롬프트 섹션 세트·도구·응답 스키마만 바꿔 끼운다. 새 클래스는 블로그를 읽는 `BlogReader`(OkHttp + Jsoup) 하나. 화면은 컴포저의 모드 칩, 세션 목록의 "조언" 표시, 오른쪽 패널의 글 보기(기존 `PublishedPostPanel` 재사용)만 더한다. **기능 허용 조건은 언제나 `mode == SessionMode.WRITE`일 때만 켠다**(`mode != ADVICE`로 쓰지 않는다).

**Tech Stack:** Kotlin, Jetpack Compose(Material3), Hilt, Room(v3), kotlinx-serialization, OkHttp 4, **Jsoup 1.18.3(신규)**, JUnit4 + Robolectric + MockWebServer + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-08-29-advice-mode-design.md` (리서치: `docs/advice-mode-research.md`)

## Global Constraints

- 패키지 `com.csh.blogwriter`, 소스 루트 `app/src/main/java/com/csh/blogwriter/`, 테스트 `app/src/test/java/com/csh/blogwriter/`, 테스트 리소스 `app/src/test/resources/`.
- 빌드·테스트: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug 2>&1 | grep -vE "WARNING|Daemon|honour" | tail -8` (timeout 600 s). 특정 클래스만: `--tests "com.csh.blogwriter.chat.PromptBuilderTest"`. **`installDebug` 금지**(설치는 오케스트레이터가 한다).
- 사용자 문구는 "~해요"체, 터치 타깃 56dp(`AppSpacing.touchTarget`). 기술 용어 금지.
- 조언 모드는 **글 개선 조언만**: 크리에이터 어드바이저 통계·운영 조언·메모리 제안·조언→글쓰기 전환은 넣지 않는다.
- 읽기 빈도: 사용자 메시지 1회당 `list_my_posts` 1회, `read_my_post` 3회. 같은 글은 세션 안에서 캐시. 백그라운드 갱신 없음.
- 저장소에 실제 블로그 id·글 제목·본문·logNo를 넣지 않는다(픽스처는 이미 가명화돼 있다).
- 커밋 메시지는 한국어, 트레일러 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. 자기 파일만 `git add`(`git add -A` 금지).
- 기존 코드 스타일(한 줄 긴 문장, 한국어 주석으로 "왜"를 적기)을 따른다. 무관한 리팩터링 금지.

---

### Task 1: 세션 모드·새 메시지 kind·마이그레이션·payload

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/blog/BlogModels.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/data/repo/ChatRepository.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/data/db/ChatSessionEntity.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/data/db/Migrations.kt`, `AppDatabase.kt`(version 3), `app/src/main/java/com/csh/blogwriter/di/DatabaseModule.kt:29`
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/ChatUiModels.kt` (`ChatPayloads`)
- Modify: `app/src/main/java/com/csh/blogwriter/chat/ConversationEngine.kt:262-271` (exhaustive `when`), `app/src/main/java/com/csh/blogwriter/ui/chat/ChatScreen.kt:524-563` (`MessageItem` exhaustive `when`)
- Modify: `app/src/test/java/com/csh/blogwriter/ui/chat/ChatViewModelTest.kt:59-70` (FakeChatRepository.createSession 시그니처)
- Test: `app/src/test/java/com/csh/blogwriter/data/db/MigrationTest.kt`, `app/src/test/java/com/csh/blogwriter/ui/chat/ChatPayloadsTest.kt`(신규)
- Commit: `app/schemas/com.csh.blogwriter.data.db.AppDatabase/3.json` (빌드가 생성)

**Interfaces:**
- Produces:
  ```kotlin
  // blog/BlogModels.kt
  package com.csh.blogwriter.blog
  data class PostSummary(val logNo: String, val title: String, val addedAt: Long, val comments: Int, val likes: Int, val brief: String, val photoCount: Int)
  data class PostText(val logNo: String, val title: String, val lines: List<String>, val imageCount: Int, val videoCount: Int) {
      /** 모델에 넘길 본문. [MAX_CHARS] 를 넘으면 자르고 "(이하 생략)" 을 붙인다. */
      fun text(): String
      companion object { const val MAX_CHARS = 6_000 }
  }
  /** 오른쪽 패널과 도구가 함께 쓰는 모바일 글 주소. */
  fun postUrl(blogId: String, logNo: String): String = "https://m.blog.naver.com/PostView.naver?blogId=$blogId&logNo=$logNo"
  ```
  ```kotlin
  // data/repo/ChatRepository.kt
  enum class SessionMode { WRITE, ADVICE }
  enum class MessageKind { TEXT, PHOTOS, PLAN, POST, SYSTEM, PHOTO_GROUPS, BLOG_POSTS, POST_VIEW }
  data class ChatSession(..., val publishedUrl: String?, val mode: SessionMode = SessionMode.WRITE)
  interface ChatRepository { suspend fun createSession(mode: SessionMode = SessionMode.WRITE): ChatSession; ... }
  ```
  ```kotlin
  // ui/chat/ChatUiModels.kt
  data class PostView(val logNo: String, val title: String)
  object ChatPayloads {
      fun blogPosts(posts: List<PostSummary>): String          // {"posts":[{logNo,title,addedAt,comments,likes,brief,photoCount}]}
      fun readBlogPosts(payload: String): List<PostSummary>?
      fun postView(view: PostView): String                     // {"logNo":"…","title":"…"}
      fun readPostView(payload: String): PostView?
  }
  ```

- [ ] **Step 1: 모델·enum·엔티티 추가**

`blog/BlogModels.kt`:
```kotlin
package com.csh.blogwriter.blog

/** 최근 글 목록의 한 줄 — `post-list` API 의 항목에서 조언에 필요한 것만. */
data class PostSummary(val logNo: String, val title: String, val addedAt: Long, val comments: Int, val likes: Int, val brief: String, val photoCount: Int)

/** 글 본문. 문단·인용·표는 줄 단위 텍스트로, 사진·동영상은 개수만 센다. */
data class PostText(val logNo: String, val title: String, val lines: List<String>, val imageCount: Int, val videoCount: Int) {
    fun text(): String {
        val joined = lines.joinToString("\n")
        return if (joined.length <= MAX_CHARS) joined else joined.take(MAX_CHARS) + "\n(이하 생략)"
    }
    companion object { const val MAX_CHARS = 6_000 }
}

fun postUrl(blogId: String, logNo: String): String = "https://m.blog.naver.com/PostView.naver?blogId=$blogId&logNo=$logNo"
```

`data/repo/ChatRepository.kt`: `SessionMode` enum, `MessageKind` 에 `BLOG_POSTS, POST_VIEW` 추가(주석: "둘 다 모델 히스토리에 싣지 않고 화면·복원에만 쓴다"), `ChatSession` 마지막 인자 `val mode: SessionMode = SessionMode.WRITE`, `createSession(mode: SessionMode = SessionMode.WRITE)`. `RoomChatRepository`: `toModel()` 에 `SessionMode.valueOf(mode)`, `toEntity()` 에 `mode.name`, `createSession(mode)` 가 `mode` 를 넣는다.

`data/db/ChatSessionEntity.kt`: 마지막에 `@ColumnInfo(defaultValue = "WRITE") val mode: String = "WRITE",` (import `androidx.room.ColumnInfo`).

`data/db/Migrations.kt`:
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_session` ADD COLUMN `mode` TEXT NOT NULL DEFAULT 'WRITE'")
    }
}
```
`AppDatabase.kt`: `version = 3`. `di/DatabaseModule.kt:29`: `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`.

- [ ] **Step 2: exhaustive `when` 두 곳 고치기**

`ConversationEngine.buildContents` (`ConversationEngine.kt:270`): `MessageKind.SYSTEM, MessageKind.PHOTO_GROUPS, MessageKind.BLOG_POSTS, MessageKind.POST_VIEW -> return@forEach`.

`ChatScreen.MessageItem`: `MessageKind.PHOTO_GROUPS` 분기 뒤에
```kotlin
        // 최근 글 목록은 모델 컨텍스트용 — 목록에는 읽었다는 한 줄만.
        MessageKind.BLOG_POSTS -> SystemMessage {
            val count = ChatPayloads.readBlogPosts(message.payloadJson)?.size ?: 0
            Text("최근 글 ${count}개를 읽었어요", style = AppTheme.typography.caption, color = AppTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs))
        }
        MessageKind.POST_VIEW -> Text(
            "'${ChatPayloads.readPostView(message.payloadJson)?.title.orEmpty()}' 글을 읽었어요 · 보기",
            style = AppTheme.typography.body2, color = AppTheme.colors.fillBrand,
            modifier = Modifier.fillMaxWidth().clickable(onClick = viewModel::openPanel).padding(horizontal = AppSpacing.sm, vertical = AppSpacing.md),
        )
```

- [ ] **Step 3: ChatPayloads 인코딩 + 실패 테스트**

`ChatPayloads` 에 (`@Serializable private data class PostSummaryDto(...)` 대신 `buildJsonObject`/`jsonArray` 로 손수 쓴다 — 기존 스타일):
```kotlin
    fun blogPosts(posts: List<PostSummary>): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        putJsonArray("posts") {
            posts.forEach { p -> add(buildJsonObject {
                put("logNo", p.logNo); put("title", p.title); put("addedAt", p.addedAt); put("comments", p.comments)
                put("likes", p.likes); put("brief", p.brief); put("photoCount", p.photoCount)
            }) }
        }
    })
    fun readBlogPosts(payload: String): List<PostSummary>? = runCatching {
        json.parseToJsonElement(payload).jsonObject["posts"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            PostSummary(
                o["logNo"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content, o["addedAt"]!!.jsonPrimitive.long,
                o["comments"]!!.jsonPrimitive.int, o["likes"]!!.jsonPrimitive.int, o["brief"]?.jsonPrimitive?.content.orEmpty(), o["photoCount"]?.jsonPrimitive?.int ?: 0,
            )
        }
    }.getOrNull()
    fun postView(view: PostView): String = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("logNo", view.logNo); put("title", view.title) })
    fun readPostView(payload: String): PostView? = runCatching {
        val o = json.parseToJsonElement(payload).jsonObject
        PostView(o["logNo"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content)
    }.getOrNull()
```
`data class PostView(val logNo: String, val title: String)` 는 `ChatUiModels.kt` 상단, `PhotosPayload` 옆에.

테스트 `ui/chat/ChatPayloadsTest.kt`:
```kotlin
class ChatPayloadsTest {
    @Test fun blogPostsRoundTrip() {
        val posts = listOf(PostSummary("100000000001", "원주 카페 늘봄", 1_787_000_000_000L, 2, 4, "요약", 8))
        assertEquals(posts, ChatPayloads.readBlogPosts(ChatPayloads.blogPosts(posts)))
        assertNull(ChatPayloads.readBlogPosts("{}"))
    }
    @Test fun postViewRoundTrip() {
        val view = PostView("100000000001", "원주 카페 늘봄")
        assertEquals(view, ChatPayloads.readPostView(ChatPayloads.postView(view)))
        assertNull(ChatPayloads.readPostView("not json"))
    }
}
```

- [ ] **Step 4: 마이그레이션 테스트(실패 확인 → 통과)**

`MigrationTest` 에 추가 — v2 스키마는 `app/schemas/com.csh.blogwriter.data.db.AppDatabase/2.json` 의 CREATE 문 그대로(identity hash `2dc72c1f1dbc851665d09b01d9de8622`):
```kotlin
    @Test
    fun migrate2To3AddsModeColumnWithWriteDefault() = runTest {
        context.deleteDatabase(DB_NAME)
        val v2Callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `publish_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `logNo` TEXT NOT NULL, `url` TEXT NOT NULL, `publishedAt` INTEGER NOT NULL, `imageCount` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `failure_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `stage` TEXT NOT NULL, `message` TEXT NOT NULL, `detail` TEXT NOT NULL, `appVersion` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pending_job` (`id` TEXT NOT NULL, `contentJson` TEXT NOT NULL, `imageUrisJson` TEXT NOT NULL, `preparedPathsJson` TEXT, `createdAt` INTEGER NOT NULL, `lastFailure` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_session` (`id` TEXT NOT NULL, `title` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `pendingJobId` TEXT, `publishedUrl` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_message` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `role` TEXT NOT NULL, `kind` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_message_sessionId_seq` ON `chat_message` (`sessionId`, `seq`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `memory_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `lastUsedAt` INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '2dc72c1f1dbc851665d09b01d9de8622')")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val v2Helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(DB_NAME).callback(v2Callback).build())
        v2Helper.writableDatabase.apply {
            execSQL("INSERT INTO chat_session (id, title, createdAt, updatedAt, status, pendingJobId, publishedUrl) VALUES ('s1', '옛 글', 1, 1, 'DRAFTING', NULL, NULL)")
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME).addMigrations(MIGRATION_1_2, MIGRATION_2_3).allowMainThreadQueries().build()
        val session = db.chatDao().getSession("s1")!!
        assertEquals("WRITE", session.mode)
        db.chatDao().upsertSession(session.copy(id = "s2", mode = "ADVICE"))
        assertEquals("ADVICE", db.chatDao().getSession("s2")!!.mode)
        db.close()
    }
```
기존 `migrate1To2AddsTablesAndKeepsRows` 의 `.addMigrations(MIGRATION_1_2)` 는 `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)` 로(1→3 두 단계).

- [ ] **Step 5: FakeChatRepository 시그니처 맞추기**

`ChatViewModelTest.FakeChatRepository.createSession` → `override suspend fun createSession(mode: SessionMode): ChatSession` 로 바꾸고 `ChatSession(..., null, mode)` 를 만든다(`import com.csh.blogwriter.data.repo.SessionMode`).

- [ ] **Step 6: 빌드·테스트**

Run: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug 2>&1 | grep -vE "WARNING|Daemon|honour" | tail -8`
Expected: BUILD SUCCESSFUL, `app/schemas/com.csh.blogwriter.data.db.AppDatabase/3.json` 생성됨(`chat_session` 에 `mode` 컬럼, `defaultValue: "'WRITE'"`).

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/blog/BlogModels.kt app/src/main/java/com/csh/blogwriter/data app/src/main/java/com/csh/blogwriter/di/DatabaseModule.kt app/src/main/java/com/csh/blogwriter/ui/chat/ChatUiModels.kt app/src/main/java/com/csh/blogwriter/chat/ConversationEngine.kt app/src/main/java/com/csh/blogwriter/ui/chat/ChatScreen.kt app/src/test/java/com/csh/blogwriter/data/db/MigrationTest.kt app/src/test/java/com/csh/blogwriter/ui/chat/ChatPayloadsTest.kt app/src/test/java/com/csh/blogwriter/ui/chat/ChatViewModelTest.kt app/schemas
git commit -m "feat(조언): 세션 모드(WRITE/ADVICE)·글 목록/글 보기 메시지 kind·DB v3 마이그레이션"
```

---

### Task 2: BlogReader — 최근 글 목록·본문 읽기 (OkHttp + Jsoup)

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`(dependencies)
- Create: `app/src/main/java/com/csh/blogwriter/blog/BlogReader.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/di/LlmModule.kt` (바인딩)
- Test: `app/src/test/java/com/csh/blogwriter/blog/BlogReaderTest.kt` (픽스처 `app/src/test/resources/blog/*` 는 이미 있음)

**Interfaces:**
- Consumes: `PostSummary`, `PostText`(Task 1)
- Produces:
  ```kotlin
  package com.csh.blogwriter.blog
  fun interface CookieSource { fun cookieHeader(url: String): String? }
  interface BlogReader {
      suspend fun listPosts(blogId: String, count: Int = 30): List<PostSummary>?   // 실패 null
      suspend fun readPost(blogId: String, logNo: String): PostText?               // 실패 null, 세션 캐시
  }
  class NaverBlogReader(http: OkHttpClient, cookies: CookieSource, baseUrl: String = "https://m.blog.naver.com") : BlogReader
  internal fun parsePostList(body: String): List<PostSummary>?
  internal fun parsePostView(html: String, logNo: String): PostText?
  ```

- [ ] **Step 1: 의존성**

`gradle/libs.versions.toml` `[versions]` 에 `jsoup = "1.18.3"`, `[libraries]` 에 `jsoup = { group = "org.jsoup", name = "jsoup", version.ref = "jsoup" }`. `app/build.gradle.kts` dependencies 에 `implementation(libs.jsoup)` (okhttp 줄 아래).

- [ ] **Step 2: 실패하는 테스트**

`blog/BlogReaderTest.kt`:
```kotlin
package com.csh.blogwriter.blog

class BlogReaderTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("blog/$name")!!.bufferedReader().readText()

    @Test fun parsesPostListItems() {
        val posts = parsePostList(fixture("post-list.json"))!!
        assertEquals(3, posts.size)
        val first = posts[0]
        assertEquals("100000000001", first.logNo)
        assertTrue(first.title.startsWith("원주 단계동 맛집 봄들식당"))
        assertEquals(1787989202986L, first.addedAt)
        assertEquals(2, first.comments); assertEquals(4, first.likes); assertEquals(33, first.photoCount)
        assertTrue(first.brief.contains("봄들식당"))
    }

    @Test fun postListErrorBodyGivesNull() {
        assertNull(parsePostList(fixture("post-list-error.json")))
        assertNull(parsePostList("<html>403</html>"))
    }

    @Test fun parsesPostViewIntoLinesAndCounts() {
        val post = parsePostView(fixture("post-view.html"), "100000000001")!!
        assertEquals("100000000001", post.logNo)
        assertTrue(post.title.startsWith("원주 단계동 맛집 봄들식당"))
        assertEquals(4, post.imageCount)   // 단독 1장 + 콜라주 3장
        assertEquals(1, post.videoCount)
        val text = post.text()
        assertTrue(text.contains("> 한눈에 보기"))                       // 인용은 "> " 접두
        assertTrue(text.contains("[사진 1장]")); assertTrue(text.contains("[사진 3장]")); assertTrue(text.contains("[동영상]"))
        assertTrue(text.contains("주소: 강원 원주시 단계동 000-0"))         // 표는 "항목: 값"
        assertFalse(text.contains("\n\n"))                               // 빈 문단은 버린다
        assertTrue(text.indexOf("한눈에 보기") < text.indexOf("방문 계기")) // 순서 유지
    }

    @Test fun postViewWithoutMainContainerGivesNull() {
        assertNull(parsePostView("<html><body><p>없음</p></body></html>", "1"))
    }

    @Test fun longBodyIsTruncated() {
        val long = PostText("1", "t", List(400) { "가".repeat(20) }, 0, 0)
        val text = long.text()
        assertTrue(text.endsWith("(이하 생략)"))
        assertTrue(text.length <= PostText.MAX_CHARS + 10)
    }

    @Test fun listPostsSendsRefererAndCookieAndCachesPost() = runTest {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse().setBody(fixture("post-list.json")))
        server.enqueue(MockResponse().setBody(fixture("post-view.html")))
        val reader = NaverBlogReader(OkHttpClient(), { "NID_AUT=x; NID_SES=y" }, baseUrl = server.url("/").toString().trimEnd('/'))

        val posts = reader.listPosts("sampleblog")!!
        assertEquals(3, posts.size)
        val listReq = server.takeRequest()
        assertEquals("/api/blogs/sampleblog/post-list?categoryNo=0&itemCount=30&page=1", listReq.path)
        assertEquals("https://m.blog.naver.com/sampleblog", listReq.getHeader("Referer"))
        assertEquals("NID_AUT=x; NID_SES=y", listReq.getHeader("Cookie"))

        val post = reader.readPost("sampleblog", "100000000001")!!
        assertEquals("/PostView.naver?blogId=sampleblog&logNo=100000000001", server.takeRequest().path)
        // 같은 글은 다시 받지 않는다.
        assertSame(post, reader.readPost("sampleblog", "100000000001"))
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test fun networkFailureGivesNull() = runTest {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"isSuccess\":false}"))
        val reader = NaverBlogReader(OkHttpClient(), { null }, baseUrl = server.url("/").toString().trimEnd('/'))
        assertNull(reader.listPosts("sampleblog"))
        server.shutdown()
    }
}
```

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.blog.BlogReaderTest" 2>&1 | tail -5` → 컴파일 실패(클래스 없음).

- [ ] **Step 3: 구현**

`blog/BlogReader.kt`:
```kotlin
package com.csh.blogwriter.blog

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 로그인 WebView 의 쿠키를 OkHttp 요청에 실어 주는 통로 — 이웃공개 글도 보이게. 테스트에선 람다. */
fun interface CookieSource { fun cookieHeader(url: String): String? }

interface BlogReader {
    suspend fun listPosts(blogId: String, count: Int = 30): List<PostSummary>?
    suspend fun readPost(blogId: String, logNo: String): PostText?
}

/**
 * 모바일 블로그는 목록 API(JSON)와 글 페이지(서버 렌더링 HTML)를 로그인 없이 준다 — 스펙 §9.
 * 실패는 전부 null + Log.w. 본문은 같은 글을 턴마다 다시 받지 않도록 최근 [CACHE_SIZE]편을 기억한다.
 */
class NaverBlogReader(
    private val http: OkHttpClient,
    private val cookies: CookieSource,
    private val baseUrl: String = "https://m.blog.naver.com",
) : BlogReader {
    private val cache = object : LinkedHashMap<String, PostText>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PostText>?) = size > CACHE_SIZE
    }

    override suspend fun listPosts(blogId: String, count: Int): List<PostSummary>? =
        get("$baseUrl/api/blogs/$blogId/post-list?categoryNo=0&itemCount=$count&page=1", "https://m.blog.naver.com/$blogId")?.let(::parsePostList)

    override suspend fun readPost(blogId: String, logNo: String): PostText? {
        val key = "$blogId/$logNo"
        synchronized(cache) { cache[key] }?.let { return it }
        val html = get("$baseUrl/PostView.naver?blogId=$blogId&logNo=$logNo", "https://m.blog.naver.com/$blogId") ?: return null
        return parsePostView(html, logNo)?.also { synchronized(cache) { cache[key] = it } }
    }

    private suspend fun get(url: String, referer: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("Referer", referer).header("User-Agent", UA)
                .apply { cookies.cookieHeader(url)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }.build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) { Log.w(TAG, "GET $url -> ${res.code}"); return@withContext null }
                res.body?.string()
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "GET $url failed", e); null }
    }

    companion object {
        private const val TAG = "BlogReader"
        private const val CACHE_SIZE = 20
        private const val UA = "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** `post-list` 응답. `isSuccess` 가 아니면 null. 스파이크 §9 의 필드명 그대로. */
internal fun parsePostList(body: String): List<PostSummary>? = runCatching {
    val root = json.parseToJsonElement(body).jsonObject
    if (root["isSuccess"]?.jsonPrimitive?.booleanOrNull != true) return null
    root["result"]!!.jsonObject["items"]!!.jsonArray.map { e ->
        val o = e.jsonObject
        PostSummary(
            logNo = o["logNo"]!!.jsonPrimitive.content,
            title = o["titleWithInspectMessage"]?.jsonPrimitive?.content.orEmpty().trim(),
            addedAt = o["addDate"]?.jsonPrimitive?.long ?: 0L,
            comments = o["commentCnt"]?.jsonPrimitive?.int ?: 0,
            likes = o["sympathyCnt"]?.jsonPrimitive?.int ?: 0,
            brief = o["briefContents"]?.jsonPrimitive?.content.orEmpty().trim(),
            photoCount = o["thumbnailCount"]?.jsonPrimitive?.int ?: 0,
        )
    }
}.getOrNull()

/**
 * 모바일 PostView. `div.se-main-container` 바로 아래 `div.se-component` 를 순서대로 훑는다:
 * se-text/se-quotation → 문단(인용은 "> "), se-table → "항목: 값" 줄, 사진/동영상은 개수만.
 */
internal fun parsePostView(html: String, logNo: String): PostText? = runCatching {
    val doc = Jsoup.parse(html)
    val main = doc.selectFirst("div.se-main-container") ?: return null
    val title = doc.selectFirst(".se-title-text")?.text()?.trim().orEmpty()
    val lines = mutableListOf<String>()
    var images = 0
    var videos = 0
    for (comp in main.children().filter { it.hasClass("se-component") }) {
        when {
            comp.hasClass("se-quotation") -> comp.paragraphs().forEach { lines += "> $it" }
            comp.hasClass("se-text") -> lines += comp.paragraphs()
            comp.hasClass("se-table") -> comp.select("tr").forEach { tr ->
                val cells = tr.select("td, th").map { it.text().trim() }.filter { it.isNotEmpty() }
                if (cells.isNotEmpty()) lines += if (cells.size >= 2) "${cells[0]}: ${cells.drop(1).joinToString(" / ")}" else cells[0]
            }
            comp.hasClass("se-image") || comp.hasClass("se-imageGroup") || comp.hasClass("se-imageStrip") -> {
                val n = comp.select("img").size.coerceAtLeast(1)
                images += n; lines += "[사진 ${n}장]"
            }
            comp.hasClass("se-video") -> { videos++; lines += "[동영상]" }
            // se-placesMap, se-horizontalLine, se-oglink 등은 조언에 필요 없다.
        }
    }
    PostText(logNo, title, lines, images, videos)
}.getOrNull()

private fun Element.paragraphs(): List<String> =
    select("p.se-text-paragraph").map { it.text().replace(' ', ' ').trim() }.filter { it.isNotEmpty() }
```

DI (`di/LlmModule.kt`): companion 에
```kotlin
        @Provides @Singleton fun blogReader(http: OkHttpClient): BlogReader =
            NaverBlogReader(http, { url -> android.webkit.CookieManager.getInstance().getCookie(url) })
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.blog.BlogReaderTest" 2>&1 | grep -E "FAILED|BUILD|tests completed"` → BUILD SUCCESSFUL. `imageCount` 가 4가 아니면 픽스처의 `se-image`(1) + `se-imageGroup`(3) 을 다시 센다 — 테스트 값을 바꾸지 말고 파서를 고친다.

- [ ] **Step 5: 커밋**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/csh/blogwriter/blog/BlogReader.kt app/src/main/java/com/csh/blogwriter/di/LlmModule.kt app/src/test/java/com/csh/blogwriter/blog/BlogReaderTest.kt
git commit -m "feat(조언): BlogReader — 모바일 블로그 글 목록·본문 읽기(OkHttp+Jsoup, Referer·쿠키, 캐시)"
```

---

### Task 3: 조언 프롬프트 섹션 3개 + PromptBuilder 모드

**Files:**
- Create: `app/src/main/assets/prompts/a1_advice_role.md`, `a2_advice_guards.md`, `a3_advice_output.md`
- Modify: `app/src/main/java/com/csh/blogwriter/chat/PromptStore.kt` (`PromptSection`)
- Modify: `app/src/main/java/com/csh/blogwriter/chat/PromptBuilder.kt`
- Test: `app/src/test/java/com/csh/blogwriter/chat/PromptBuilderTest.kt`, `PromptStoreTest.kt`(섹션 수를 세는 단언이 있으면 11개로)

**Interfaces:**
- Consumes: `PostSummary`, `SessionMode`
- Produces:
  ```kotlin
  enum class PromptGroup(val title: String) { WRITE("글쓰기"), ADVICE("조언") }
  enum class PromptSection(val file: String, val title: String, val group: PromptGroup = PromptGroup.WRITE) { ..., ADVICE_ROLE("a1_advice_role.md", "조언·역할", PromptGroup.ADVICE), ADVICE_GUARDS("a2_advice_guards.md", "조언·판단 규칙", PromptGroup.ADVICE), ADVICE_OUTPUT("a3_advice_output.md", "조언·출력 형식", PromptGroup.ADVICE) }
  class PromptBuilder {
      suspend fun system(memory: List<MemoryItem>, style: String?, targetLength: IntRange, draftTurn: Boolean, mode: SessionMode = SessionMode.WRITE): String
      /** 조언 시스템 프롬프트 끝에 붙는 [최근 글 목록] 표. null 이면 "(목록 없음 …)" 안내. */
      fun postsSection(posts: List<PostSummary>?): String
  }
  ```

- [ ] **Step 1: 프롬프트 파일**

`a1_advice_role.md`:
```
당신은 사용자(강원 원주에 사는 40대 여성 블로거)의 네이버 블로그를 함께 읽는 편집자입니다. 블로그는 "직접 방문하고 경험한 솔직 리뷰"가 간판이고, 주제는 원주 맛집·카페, 제품 후기, 쇼핑, 살림·일상입니다.
당신의 일은 사용자가 이미 올린 글을 읽고 다음 글을 더 낫게 쓰도록 돕는 것입니다. 대신 써 주지 않습니다. 새 글을 쓰자거나 초안을 내겠다고 하지 않습니다.
대화는 짧고 다정하게 합니다. 사용자는 컴퓨터에 익숙하지 않으므로 기술 용어(SEO, 알고리즘, 키워드 밀도 같은 말)를 쓰지 않습니다. 한 번에 질문은 하나만 합니다.
글을 읽기 전에는 평가하지 않습니다. 어떤 글인지 알면 read_my_post 로 본문을 읽고 답합니다. 어떤 글인지 불분명하면 최근 글 목록에서 후보 2~3개를 제목으로 되묻습니다.
```

`a2_advice_guards.md`:
```
[판단 규칙]
- 비판이 먼저입니다. 칭찬은 짧게, 고칠 점은 구체적으로. 고칠 점마다 그 글의 실제 문장을 따옴표로 인용하고, 왜 아쉬운지 한 줄, 어떻게 고칠지 한 줄을 붙입니다. 인용할 문장이 없으면 그 지적은 하지 않습니다.
- 사용자가 반박해도 근거 없이 입장을 바꾸지 않습니다. 사용자의 말이 맞으면 무엇이 맞는지 짚고 인정하고, 아니면 인용을 다시 보여 주며 이유를 설명합니다.
- 사용자의 말투와 표현은 출발점입니다. 관용구·말버릇·이모지 습관을 바꾸라고 하지 않습니다. 구조·구체성·순서·빠진 정보를 다룹니다.
- 하지 말아야 할 조언: 글을 많이 올리라, 같은 주제로 여러 편을 쓰라, 제목에 검색어를 반복하라, "이렇게 하면 조회수·순위가 오른다" 같은 결과 약속. 제목 후보 중 어느 것이 더 잘 될지 점치지 않습니다.
- 검색 노출 얘기는 제목과 첫 요약 문단(지역+상호+대표 메뉴가 한 번씩 들어갔는지)에만 합니다.
- 경험 신호 점검: 1인칭으로 직접 겪은 장면이 있는지, 직접 찍은 사진이 글의 흐름과 맞는지, 가격·시간·수량 같은 구체 숫자가 있는지, 아쉬운 점이 최소 한 줄 있는지.
- 기준 구조(글쓰기 규칙과 같음): 0 한눈에 보기(300자 안팎 요약) → 1 방문 계기 → 2 실제 방문 과정 → 3 매장/메뉴 경험 → 4 솔직한 느낌 → 5 좋았던 점/아쉬웠던 점 → 6 이런 분께 추천 → 7 한 줄 총평 → 8 가게 정보 표 + 해시태그. 이 순서를 그대로 강요하지 말고, 빠진 단이 있으면 그 단이 왜 필요한지 설명합니다.
- 통계(조회수·유입)는 모릅니다. 모른다고 말하고 추측하지 않습니다.
```

`a3_advice_output.md`:
```
[출력 형식]
항상 지정된 JSON 스키마({say})로만 답합니다. say 는 마크다운 없이 줄바꿈만 씁니다. 800자 안팎.
글 하나를 봤을 때:
잘한 점 1~2개 (각 한 줄)
고칠 점 최대 3개 — 각각 "원문: '…'" 한 줄, "왜: …" 한 줄, "이렇게: '…'" 한 줄(고친 예시는 사용자 말투 그대로)
마지막에 다음 글에서 딱 하나만 해 볼 것 한 줄.
여러 글을 봤을 때:
공통 경향 최대 3개 (각각 어느 글의 어떤 문장에서 그렇게 봤는지 인용)
다음 글에서 해 볼 것 1개.
글을 아직 읽지 않았으면 평가하지 말고, read_my_post 로 읽거나 어떤 글인지 되묻습니다. 목록에 없는 글을 말하면 list_my_posts 로 다시 확인합니다.
```

- [ ] **Step 2: PromptSection 확장**

`PromptStore.kt`:
```kotlin
enum class PromptGroup(val title: String) { WRITE("글쓰기"), ADVICE("조언") }

enum class PromptSection(val file: String, val title: String, val group: PromptGroup = PromptGroup.WRITE) {
    ROLE("01_role.md", "역할"), AUDIENCE("02_audience.md", "독자"), STYLE("03_style.md", "글 스타일"), MEMORY("04_memory.md", "기억"),
    STRUCTURE("05_structure.md", "글 구조 규칙"), CONVERSATION("06_conversation.md", "대화 규칙"), OUTPUT("07_output.md", "출력 형식"), SELFCHECK("08_selfcheck.md", "제출 전 점검"),
    ADVICE_ROLE("a1_advice_role.md", "조언·역할", PromptGroup.ADVICE), ADVICE_GUARDS("a2_advice_guards.md", "조언·판단 규칙", PromptGroup.ADVICE), ADVICE_OUTPUT("a3_advice_output.md", "조언·출력 형식", PromptGroup.ADVICE),
}
```

- [ ] **Step 3: 실패하는 테스트**

`PromptBuilderTest` 의 `texts` 맵에 `PromptSection.ADVICE_ROLE to "조언 역할", PromptSection.ADVICE_GUARDS to "조언 규칙", PromptSection.ADVICE_OUTPUT to "조언 출력"` 추가 후:
```kotlin
    @Test
    fun adviceModeAssemblesAdviceSectionsOnly() = runTest {
        val s = PromptBuilder(store).system(memory = listOf(mem(1)), style = "존댓말", targetLength = 900..1400, draftTurn = false, mode = SessionMode.ADVICE)
        val idx = listOf("조언 역할", "스타일: 존댓말", "- PREFERENCE: 항목1", "조언 규칙", "조언 출력").map { s.indexOf(it) }
        assertTrue(idx.all { it >= 0 })
        assertEquals(idx, idx.sorted())
        listOf("역할 문안", "독자 문안", "구조 문안", "대화 문안", "출력 문안", "점검 문안").forEach { assertFalse(it, s.contains(it)) }
    }

    @Test
    fun writeModeDoesNotIncludeAdviceSections() = runTest {
        val s = PromptBuilder(store).system(memory = emptyList(), style = null, targetLength = 900..1400, draftTurn = false)
        assertFalse(s.contains("조언 역할"))
    }

    @Test
    fun postsSectionRendersTableOrFallback() {
        val b = PromptBuilder(store)
        val posts = listOf(PostSummary("100000000001", "원주 카페 늘봄", 1_787_989_202_986L, 2, 4, "쑥라떼가 달지 않았어요", 8))
        val table = b.postsSection(posts)
        assertTrue(table.startsWith("[최근 글 목록]"))
        assertTrue(table.contains("100000000001 | 원주 카페 늘봄 | 2026-08-29 | 댓글 2 | 공감 4 | 사진 8"))
        assertTrue(table.contains("쑥라떼가 달지 않았어요"))
        val none = b.postsSection(null)
        assertTrue(none.contains("목록 없음"))
        assertTrue(none.contains("read_my_post"))
    }
```
(`addedAt` 1787989202986 = 2026-08-29 KST. 날짜는 `Asia/Seoul` 기준 `yyyy-MM-dd`.)

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.chat.PromptBuilderTest" 2>&1 | tail -5` → 컴파일 실패.

- [ ] **Step 4: 구현**

`PromptBuilder.kt`:
```kotlin
class PromptBuilder @Inject constructor(private val store: PromptStore) {
    companion object {
        const val MEMORY_CAP = 40
        const val NO_POSTS = "[최근 글 목록]\n(목록 없음 — 글 목록을 읽지 못했습니다. 사용자가 글을 지목하면 read_my_post 로 읽고, 목록이 필요하면 list_my_posts 를 부릅니다.)"
        private val DATE = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.of("Asia/Seoul"))
    }

    suspend fun system(memory: List<MemoryItem>, style: String?, targetLength: IntRange, draftTurn: Boolean, mode: SessionMode = SessionMode.WRITE): String {
        val memoryLines = memory.filterNot { it.kind == MemoryKind.STYLE }
            .take(MEMORY_CAP).joinToString("\n") { "- ${it.kind.name}: ${it.text}" }.ifEmpty { "(없음)" }
        val styleSection = store.text(PromptSection.STYLE).replace("{{style}}", style ?: "(아직 없음)")
        val memorySection = store.text(PromptSection.MEMORY).replace("{{memory}}", memoryLines)
        val sections = when (mode) {
            SessionMode.WRITE -> buildList {
                add(store.text(PromptSection.ROLE)); add(store.text(PromptSection.AUDIENCE)); add(styleSection); add(memorySection)
                add(store.text(PromptSection.STRUCTURE)); add(store.text(PromptSection.CONVERSATION)); add(store.text(PromptSection.OUTPUT))
                if (draftTurn) add(store.text(PromptSection.SELFCHECK))
            }
            // 조언은 글쓰기 규칙(구조·대화·출력·점검)을 싣지 않는다 — 스타일·기억만 공유해 "출발점"을 알게 한다.
            SessionMode.ADVICE -> listOf(store.text(PromptSection.ADVICE_ROLE), styleSection, memorySection, store.text(PromptSection.ADVICE_GUARDS), store.text(PromptSection.ADVICE_OUTPUT))
        }
        return sections.joinToString("\n\n").replace("{{minLen}}", targetLength.first.toString()).replace("{{maxLen}}", targetLength.last.toString())
    }

    fun postsSection(posts: List<PostSummary>?): String {
        if (posts == null) return NO_POSTS
        val rows = posts.joinToString("\n") { p ->
            "${p.logNo} | ${p.title} | ${DATE.format(java.time.Instant.ofEpochMilli(p.addedAt))} | 댓글 ${p.comments} | 공감 ${p.likes} | 사진 ${p.photoCount}" +
                (p.brief.takeIf { it.isNotBlank() }?.let { "\n    요약: ${it.take(160)}" } ?: "")
        }
        return "[최근 글 목록] (logNo | 제목 | 날짜 | 댓글 | 공감 | 사진 수) — 본문은 read_my_post(logNo) 로 읽는다\n$rows"
    }
}
```
(`java.time` 은 minSdk 26+ 이거나 desugaring 이 있어야 한다 — `app/build.gradle.kts` 의 `minSdk` 를 확인하고 26 미만이면 `java.text.SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }` 로 바꾼다.)

- [ ] **Step 5: 테스트 통과 + PromptStoreTest**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.chat.PromptBuilderTest" --tests "com.csh.blogwriter.chat.PromptStoreTest" 2>&1 | grep -E "FAILED|BUILD"`. `PromptStoreTest` 가 모든 섹션의 asset 을 읽는다면 새 파일 3개도 읽힌다(Robolectric assets).

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/assets/prompts/a1_advice_role.md app/src/main/assets/prompts/a2_advice_guards.md app/src/main/assets/prompts/a3_advice_output.md app/src/main/java/com/csh/blogwriter/chat/PromptStore.kt app/src/main/java/com/csh/blogwriter/chat/PromptBuilder.kt app/src/test/java/com/csh/blogwriter/chat/PromptBuilderTest.kt
git commit -m "feat(조언): 조언 프롬프트 섹션 3개(역할·판단 규칙·출력 형식) + PromptBuilder 모드별 조립·최근 글 목록 표"
```

---

### Task 4: 응답 스키마·도구·엔진의 모드 분기

**Files:**
- Modify: `app/src/main/java/com/csh/blogwriter/chat/TurnSchemas.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/chat/ChatContext.kt` (`ChatContext.mode/blogPosts`, `TurnListener.onPostRead`)
- Modify: `app/src/main/java/com/csh/blogwriter/chat/DefaultToolExecutor.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/chat/ConversationEngine.kt`
- Test: `app/src/test/java/com/csh/blogwriter/chat/TurnSchemasTest.kt`(신규), `DefaultToolExecutorTest.kt`, `ConversationEngineTest.kt`

**Interfaces:**
- Consumes: `BlogReader`, `PostSummary`, `PostText`(Task 1·2), `PromptBuilder.system(mode)`/`postsSection`(Task 3)
- Produces:
  ```kotlin
  object TurnSchemas {
      fun turnResponseJsonSchema(mode: SessionMode = SessionMode.WRITE): JsonObject   // ADVICE: {say} 만 required
      fun functionDeclarations(mode: SessionMode = SessionMode.WRITE): List<GFunctionDeclaration>  // ADVICE: list_my_posts, read_my_post
  }
  data class ChatContext(..., val mode: SessionMode = SessionMode.WRITE, val blogPosts: List<PostSummary>? = null)
  interface TurnListener { ...; fun onPostRead(logNo: String, title: String) {} }
  class DefaultToolExecutor @Inject constructor(research: ResearchTool, memory: MemoryRepository, settings: SettingsStore, blog: BlogReader)
  ```

- [ ] **Step 1: 실패하는 테스트 — 스키마**

`chat/TurnSchemasTest.kt`:
```kotlin
class TurnSchemasTest {
    @Test fun adviceSchemaRequiresOnlySay() {
        val s = TurnSchemas.turnResponseJsonSchema(SessionMode.ADVICE)
        assertEquals(listOf("say"), s["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("say"), s["properties"]!!.jsonObject.keys)
    }
    @Test fun writeSchemaUnchanged() {
        val s = TurnSchemas.turnResponseJsonSchema()
        assertTrue(s["properties"]!!.jsonObject.keys.containsAll(listOf("say", "plan", "question", "quickReplies", "readyToDraft", "post")))
    }
    @Test fun toolsPerMode() {
        assertEquals(listOf("list_my_posts", "read_my_post"), TurnSchemas.functionDeclarations(SessionMode.ADVICE).map { it.name })
        assertEquals(listOf("web_search", "open_page", "remember"), TurnSchemas.functionDeclarations().map { it.name })
    }
}
```
(`GFunctionDeclaration` 의 이름 필드가 `name` 인지 `llm/GeminiModels.kt` 에서 확인.)

- [ ] **Step 2: TurnSchemas 구현**

`turnResponseJsonSchema(mode)`:
```kotlin
    fun turnResponseJsonSchema(mode: SessionMode = SessionMode.WRITE): JsonObject = when (mode) {
        SessionMode.WRITE -> buildJsonObject { /* 기존 내용 그대로 */ }
        SessionMode.ADVICE -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { put("say", str("조언 본문. 마크다운 없이 줄바꿈만, 800자 안팎")) }
            putJsonArray("required") { add("say") }
        }
    }
```
`functionDeclarations(mode)`: 기존 3개는 `SessionMode.WRITE`, ADVICE 는
```kotlin
        GFunctionDeclaration("list_my_posts", "사용자 블로그의 최근 글 30개(logNo·제목·날짜·댓글·공감·사진 수·요약)를 돌려준다. 시스템 프롬프트의 목록이 없거나 오래됐을 때만 부른다.",
            buildJsonObject { put("type", "object"); putJsonObject("properties") {} }),
        GFunctionDeclaration("read_my_post", "logNo 의 글 본문(문단·인용·표 텍스트, 사진·동영상 개수)을 돌려준다. 조언하기 전에 반드시 읽는다. 한 번에 최대 3편.",
            buildJsonObject { put("type", "object"); putJsonObject("properties") { put("logNo", str("최근 글 목록의 logNo")) }; putJsonArray("required") { add("logNo") } }),
```

- [ ] **Step 3: ChatContext·TurnListener**

`ChatContext` 에 `val mode: SessionMode = SessionMode.WRITE,` 와 `/** 조언 세션이 세션 시작 때 읽어 둔 최근 글 목록. null = 읽지 못함. */ val blogPosts: List<PostSummary>? = null,` 추가. `TurnListener` 에 `/** 조언 도구가 글 본문을 읽었을 때 — 오른쪽 패널을 그 글로 연다. */ fun onPostRead(logNo: String, title: String) {}` 추가(기본 구현이라 기존 구현체는 그대로).

- [ ] **Step 4: 실패하는 테스트 — 도구 실행기**

`DefaultToolExecutorTest` 에 가짜 `BlogReader` 와 `settings.blogId = flowOf("sampleblog")` 를 두고(기존 `settings` 객체의 `blogId` 를 `MutableStateFlow<String?>("sampleblog")` 로 바꾼다):
```kotlin
    private val readCalls = mutableListOf<String>()
    private val blog = object : BlogReader {
        override suspend fun listPosts(blogId: String, count: Int) = listOf(PostSummary("1", "첫 글", 0, 1, 2, "요약", 3))
        override suspend fun readPost(blogId: String, logNo: String) = PostText(logNo, "글 $logNo", listOf("첫 문단", "[사진 2장]"), 2, 0).also { readCalls += logNo }
    }
    // 기존 생성 호출 DefaultToolExecutor(research, memory, settings) → DefaultToolExecutor(research, memory, settings, blog) 로 모두 바꾼다.

    @Test
    fun adviceToolsReturnPostsAndRespectLimits() = runTest {
        val ex = DefaultToolExecutor(research, memory, settings, blog)
        val list = ex.execute("list_my_posts", buildJsonObject {}) {}
        assertEquals("첫 글", list["posts"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("limit", ex.execute("list_my_posts", buildJsonObject {}) {}["error"]!!.jsonPrimitive.content)

        val progress = mutableListOf<String>()
        val post = ex.execute("read_my_post", buildJsonObject { put("logNo", "7") }) { progress += it }
        assertEquals("글 7", post["title"]!!.jsonPrimitive.content)
        assertTrue(post["text"]!!.jsonPrimitive.content.contains("첫 문단"))
        assertEquals(2, post["imageCount"]!!.jsonPrimitive.int)
        assertTrue(progress.any { it.contains("읽고 있어요") })
        ex.execute("read_my_post", buildJsonObject { put("logNo", "8") }) {}
        ex.execute("read_my_post", buildJsonObject { put("logNo", "9") }) {}
        assertEquals("limit", ex.execute("read_my_post", buildJsonObject { put("logNo", "10") }) {}["error"]!!.jsonPrimitive.content)
        assertEquals(listOf("7", "8", "9"), readCalls)
    }

    @Test
    fun adviceToolsWithoutLoginReportError() = runTest {
        blogIdFlow.value = null   // settings.blogId 의 MutableStateFlow
        val ex = DefaultToolExecutor(research, memory, settings, blog)
        assertEquals("not_logged_in", ex.execute("read_my_post", buildJsonObject { put("logNo", "7") }) {}["error"]!!.jsonPrimitive.content)
    }
```

- [ ] **Step 5: DefaultToolExecutor 구현**

생성자에 `private val blog: BlogReader` 추가. `limits` 에 `"list_my_posts" to 1, "read_my_post" to 3`. `when (name)` 에:
```kotlin
                "list_my_posts" -> {
                    val blogId = settings.blogIdOnce() ?: return buildJsonObject { put("error", "not_logged_in") }
                    onProgress("최근 글 목록을 읽고 있어요…")
                    val posts = blog.listPosts(blogId) ?: return buildJsonObject { put("error", "글 목록을 읽지 못했어요") }
                    buildJsonObject { put("posts", buildJsonArray { posts.forEach { p -> add(buildJsonObject {
                        put("logNo", p.logNo); put("title", p.title); put("addedAt", p.addedAt); put("comments", p.comments); put("likes", p.likes); put("photoCount", p.photoCount); put("brief", p.brief.take(160))
                    }) } }) }
                }
                "read_my_post" -> {
                    val blogId = settings.blogIdOnce() ?: return buildJsonObject { put("error", "not_logged_in") }
                    val logNo = args["logNo"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (logNo.isEmpty()) buildJsonObject { put("error", "logNo 없음") }
                    else {
                        onProgress("글을 읽고 있어요…")
                        val post = blog.readPost(blogId, logNo)
                        if (post == null) buildJsonObject { put("error", "글을 읽지 못했어요") }
                        else buildJsonObject { put("logNo", post.logNo); put("title", post.title); put("text", post.text()); put("imageCount", post.imageCount); put("videoCount", post.videoCount) }
                    }
                }
```
(`return` 은 `execute` 의 반환 — `counts` 를 이미 올린 뒤라 미로그인 호출도 횟수를 먹는다. 괜찮다.)

- [ ] **Step 6: 실패하는 테스트 — 엔진**

`ConversationEngineTest` 에 조언 턴 테스트(기존 도구 라운드 테스트의 SSE 응답 만드는 헬퍼를 그대로 쓴다 — 파일 안의 `sse(...)`/`functionCallChunk(...)` 류 헬퍼 이름을 확인해 사용):
```kotlin
    @Test
    fun adviceTurnUsesAdviceSchemaToolsAndReportsPostRead() = runTest {
        // 1라운드: read_my_post 호출, 2라운드: {say}
        server.enqueue(functionCallResponse("read_my_post", buildJsonObject { put("logNo", "100000000001") }))
        server.enqueue(sseResponse("""{"say":"잘한 점: …"}"""))
        val reads = mutableListOf<Pair<String, String>>()
        val listener = object : TurnListener {
            override fun onToolStatus(text: String) {}
            override fun onPartialSay(text: String) {}
            override fun onPostRead(logNo: String, title: String) { reads += logNo to title }
        }
        val tools = object : ToolExecutor {
            override suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit) =
                buildJsonObject { put("logNo", "100000000001"); put("title", "원주 카페 늘봄"); put("text", "본문"); put("imageCount", 3); put("videoCount", 0) }
        }
        val engine = engine(toolsFactory = { tools })  // 파일의 기존 엔진 생성 헬퍼에 toolsFactory 인자가 없으면 추가
        val ctx = ChatContext(history = listOf(userText("최근 글 봐 줘")), attachments = emptyList(), style = null, draftTurn = false, currentPost = null,
            mode = SessionMode.ADVICE, blogPosts = listOf(PostSummary("100000000001", "원주 카페 늘봄", 0, 1, 2, "", 3)))

        val result = engine.runTurn(ctx, listener) as TurnResult.Success
        assertEquals("잘한 점: …", result.response.say)
        assertEquals(listOf("100000000001" to "원주 카페 늘봄"), reads)

        val first = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val system = first["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(system.contains("[ADVICE_ROLE]")); assertFalse(system.contains("[STRUCTURE]"))
        assertTrue(system.contains("[최근 글 목록]")); assertTrue(system.contains("원주 카페 늘봄"))
        val toolNames = first["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("list_my_posts", "read_my_post"), toolNames)
        val required = first["generationConfig"]!!.jsonObject["responseJsonSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("say"), required)
        assertEquals("high", first["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        // 조언 턴에는 사실 확인·계획 지시가 붙지 않는다.
        val userTexts = first["contents"]!!.jsonArray.flatMap { it.jsonObject["parts"]!!.jsonArray }.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
        assertFalse(userTexts.any { it.contains("plan 을") })
    }
```
(`promptStore` 가짜는 `"[${section.name}] …"` 를 돌려주므로 `[ADVICE_ROLE]` 문자열로 확인한다. `GThinkingConfig` 의 JSON 필드명은 `llm/GeminiModels.kt` 에서 확인.)

- [ ] **Step 7: 엔진 구현**

`ConversationEngine.runTurn`:
- `val system = promptBuilder.system(memItems, ctx.style, policy.targetLength, ctx.draftTurn, ctx.mode).let { if (ctx.mode == SessionMode.ADVICE) it + "\n\n" + promptBuilder.postsSection(ctx.blogPosts) else it }`
- `val thinkingLevel = if (ctx.mode == SessionMode.ADVICE || ctx.draftTurn || ctx.currentPost != null) "high" else "low"` (주석: 조언은 글을 읽고 근거를 대야 해서 초안과 같이 깊게).
- `runWithTools(...)` 에 `mode: SessionMode` 인자 추가 → `tools = listOf(GTool(TurnSchemas.functionDeclarations(mode)))`, `responseJsonSchema = if (useSchema) TurnSchemas.turnResponseJsonSchema(mode) else null`.
- 도구 응답 뒤: `runTool` 결과를 변수에 받고 `if (call.name == "read_my_post") result["title"]?.jsonPrimitive?.content?.let { title -> listener.onPostRead(result["logNo"]?.jsonPrimitive?.content ?: call.args["logNo"]?.jsonPrimitive?.content.orEmpty(), title) }`.
- `buildContents(ctx)`: 맨 앞에 `if (ctx.mode == SessionMode.ADVICE) return adviceContents(ctx)`:
```kotlin
    /** 조언 세션: 사진·계획·초안·사실 확인 지시 없이 말 기록만 넘긴다. */
    private fun adviceContents(ctx: ChatContext): List<GContent> = ctx.history.mapNotNull { m ->
        if (m.role == MessageRole.SYSTEM || m.kind != MessageKind.TEXT) return@mapNotNull null
        val text = runCatching { json.parseToJsonElement(m.payloadJson).jsonObject["text"]!!.jsonPrimitive.content }.getOrDefault(m.payloadJson)
        GContent(if (m.role == MessageRole.USER) "user" else "model", listOf(GPart(text = text)))
    }
```

- [ ] **Step 8: 테스트 통과**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.chat.*" 2>&1 | grep -E "FAILED|BUILD|tests completed"` → BUILD SUCCESSFUL.

- [ ] **Step 9: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/chat/TurnSchemas.kt app/src/main/java/com/csh/blogwriter/chat/ChatContext.kt app/src/main/java/com/csh/blogwriter/chat/DefaultToolExecutor.kt app/src/main/java/com/csh/blogwriter/chat/ConversationEngine.kt app/src/test/java/com/csh/blogwriter/chat/TurnSchemasTest.kt app/src/test/java/com/csh/blogwriter/chat/DefaultToolExecutorTest.kt app/src/test/java/com/csh/blogwriter/chat/ConversationEngineTest.kt
git commit -m "feat(조언): 모드별 응답 스키마·도구(list_my_posts/read_my_post)·엔진 분기, 글 읽음 콜백"
```

---

### Task 5: ChatViewModel — 모드 선택·첫 턴 목록 읽기·글 보기 상태·WRITE 전용 가드

**Files:**
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/ChatUiModels.kt` (`ChatUiState`)
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/csh/blogwriter/ui/chat/ChatViewModelTest.kt`

**Interfaces:**
- Consumes: `BlogReader`(Task 2), `ChatContext.mode/blogPosts`, `TurnListener.onPostRead`(Task 4), `ChatPayloads.blogPosts/postView`(Task 1)
- Produces:
  ```kotlin
  data class ChatUiState(..., val mode: SessionMode = SessionMode.WRITE, val blogId: String? = null, val focusedPost: PostView? = null) {
      val hasPanel: Boolean  // WRITE: panelJobId != null || plan != null ; ADVICE: focusedPost != null
  }
  class ChatViewModel @Inject constructor(..., updateChecker: UpdateChecker, blog: BlogReader) {
      fun setMode(mode: SessionMode)      // 세션이 아직 없을 때만
      companion object { const val ADVICE_NEEDS_LOGIN = "조언은 네이버 로그인 후에 받을 수 있어요"; const val POSTS_FAILED = "글 목록을 읽지 못했어요. 네이버 로그인 상태를 확인해 주세요."; const val READING_POSTS = "최근 글을 읽고 있어요" }
  }
  ```

- [ ] **Step 1: 실패하는 테스트**

`ChatViewModelTest`: `newViewModel` 에 `blog: BlogReader = FakeBlogReader()` 인자를 더하고 생성자 마지막에 넘긴다. `FakeSettingsStore.blogId` 가 `MutableStateFlow<String?>` 인지 확인(`"sampleblog"` 기본값으로 바꾸되 기존 로그인 안내 테스트가 null 을 기대하면 그 테스트에서만 null 로 둔다).
```kotlin
    private class FakeBlogReader(var posts: List<PostSummary>? = listOf(PostSummary("100000000001", "원주 카페 늘봄", 0, 1, 2, "요약", 3))) : BlogReader {
        var listCalls = 0
        override suspend fun listPosts(blogId: String, count: Int): List<PostSummary>? { listCalls++; return posts }
        override suspend fun readPost(blogId: String, logNo: String): PostText? = null
    }

    private fun say(text: String) = TurnResult.Success(TurnResponse(say = text), emptyList(), "m")

    @Test
    fun adviceSessionIsCreatedWithModeAndReadsPostListOnce() = runTest {
        turns += say("어떤 글을 볼까요?"); turns += say("읽어 볼게요")
        val blog = FakeBlogReader()
        val vm = newViewModel(blog = blog)
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        vm.send("최근 글 봐 줘"); advanceUntilIdle()

        val session = chatRepo.sessions.value.single()
        assertEquals(SessionMode.ADVICE, session.mode)
        assertEquals(1, blog.listCalls)
        val kinds = chatRepo.of(session.id).map { it.kind }
        assertEquals(MessageKind.BLOG_POSTS, kinds.first())                       // 목록이 첫 메시지보다 먼저 저장된다
        assertEquals(SessionMode.ADVICE, contexts.last().mode)
        assertEquals("원주 카페 늘봄", contexts.last().blogPosts!!.single().title)
        assertEquals("최근 글 봐 줘", session.title)                             // 조언 세션 제목 = 첫 말

        vm.send("다른 글도"); advanceUntilIdle()
        assertEquals(1, blog.listCalls)                                        // 둘째 턴은 저장된 목록을 쓴다
        assertEquals("원주 카페 늘봄", contexts.last().blogPosts!!.single().title)
    }

    @Test
    fun adviceFirstTurnSurvivesPostListFailure() = runTest {
        turns += say("무슨 글인지 알려 주세요")
        val vm = newViewModel(blog = FakeBlogReader(posts = null))
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        vm.send("칼국수 글 어때?"); advanceUntilIdle()

        val session = chatRepo.sessions.value.single()
        assertTrue(chatRepo.of(session.id).any { it.kind == MessageKind.SYSTEM && ChatPayloads.readText(it.payloadJson) == ChatViewModel.POSTS_FAILED })
        assertNull(contexts.last().blogPosts)
        assertEquals("무슨 글인지 알려 주세요", ChatPayloads.readText(chatRepo.of(session.id).last { it.kind == MessageKind.TEXT }.payloadJson))
    }

    @Test
    fun adviceNeedsLoginToSend() = runTest {
        settings.blogIdFlow.value = null
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        vm.send("봐 줘"); advanceUntilIdle()
        assertTrue(chatRepo.sessions.value.isEmpty())
        assertEquals(ChatViewModel.ADVICE_NEEDS_LOGIN, vm.uiState.value.error)
        assertTrue(contexts.isEmpty())
    }

    @Test
    fun postReadOpensPanelAndIsRestoredOnReopen() = runTest {
        turns += say("읽었어요")
        partials = emptyList()
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        // 엔진이 도구를 돌리다 onPostRead 를 부르는 상황을 흉내 낸다.
        onTurn = { listener -> listener.onPostRead("100000000001", "원주 카페 늘봄") }
        vm.send("늘봄 글 봐 줘"); advanceUntilIdle()

        assertEquals(PostView("100000000001", "원주 카페 늘봄"), vm.uiState.value.focusedPost)
        assertTrue(vm.uiState.value.panelOpen); assertTrue(vm.uiState.value.hasPanel)
        val session = chatRepo.sessions.value.single()
        assertTrue(chatRepo.of(session.id).any { it.kind == MessageKind.POST_VIEW })

        vm.open(null); advanceUntilIdle()
        vm.open(session.id); advanceUntilIdle()
        assertEquals(SessionMode.ADVICE, vm.uiState.value.mode)
        assertEquals("원주 카페 늘봄", vm.uiState.value.focusedPost?.title)
        assertTrue(vm.uiState.value.panelOpen)
    }

    @Test
    fun adviceSessionIgnoresWriteOnlyFeatures() = runTest {
        turns += say("네")
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        vm.send("안녕"); advanceUntilIdle()
        vm.attachPhotos(listOf("content://a")); advanceUntilIdle()
        assertTrue(vm.uiState.value.attachments.isEmpty())
        vm.requestDraft(); advanceUntilIdle()
        assertEquals(1, contexts.size)                                         // 초안 턴이 돌지 않았다
        assertNull(vm.uiState.value.plan); assertNull(vm.uiState.value.draftGate)
        // 모드는 세션이 생긴 뒤 바뀌지 않는다.
        vm.setMode(SessionMode.WRITE)
        assertEquals(SessionMode.ADVICE, vm.uiState.value.mode)
    }

    @Test
    fun writeSessionKeepsPostListUntouched() = runTest {
        turns += planTurn()   // 파일의 기존 계획 턴 헬퍼
        val blog = FakeBlogReader()
        val vm = newViewModel(blog = blog)
        vm.openInitial(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어"); advanceUntilIdle()
        assertEquals(0, blog.listCalls)
        assertEquals(SessionMode.WRITE, contexts.last().mode)
    }
```
`onTurn` 훅: 테스트의 `runner` 에 `var onTurn: ((TurnListener) -> Unit)? = null` 을 두고 `runTurn` 안에서 `onTurn?.invoke(listener)` 를 `gate?.await()` 앞에 부른다. `settings.blogIdFlow` 는 `FakeSettingsStore` 의 `MutableStateFlow<String?>("sampleblog")` 이름에 맞춘다.

- [ ] **Step 2: 상태·뷰모델 구현**

`ChatUiState` 에 `val mode: SessionMode = SessionMode.WRITE`, `val blogId: String? = null`, `val focusedPost: PostView? = null` 추가. `hasPanel`:
```kotlin
    val hasPanel: Boolean get() = when (mode) {
        SessionMode.WRITE -> panelJobId != null || plan != null
        SessionMode.ADVICE -> focusedPost != null
    }
```

`ChatViewModel`:
- 생성자 마지막에 `private val blog: BlogReader`.
- 상수: `ADVICE_NEEDS_LOGIN`, `POSTS_FAILED`, `READING_POSTS`, `ADVICE_TITLE_MAX = 24`.
- `init` 의 blogId 수집: `_uiState.update { it.copy(loggedIn = id != null, blogId = id) }`.
- `open(null)`: `ChatUiState(hasKey, loggedIn, blogId)` (mode 는 WRITE 로 돌아간다). `open(id)`: `mode = session.mode`, `focusedPost = restoreFocusedPost(history)`, `panelOpen/listCollapsed = hasSomethingToShow || focused != null`(조언은 focused 만).
- `fun setMode(mode: SessionMode) { if (_uiState.value.session != null) return; _uiState.update { it.copy(mode = mode) } }`
- `ensureSession()`: `chatRepo.createSession(_uiState.value.mode)`.
- `send(text)`: 조언이면 `if (mode == ADVICE && blogId == null) { error = ADVICE_NEEDS_LOGIN; return }`; `draftTurn = mode == WRITE && readyToDraft && DRAFT_WORDS...`.
- `requestDraft()`, `attachPhotos()`, `startGrouping()`: 첫 줄에 `if (_uiState.value.mode != SessionMode.WRITE) return`.
- `runTurn`: `ensureSession()` 뒤, 키 확인 뒤, 사용자 메시지 저장 **앞**에:
```kotlin
                if (session.mode == SessionMode.ADVICE) ensurePostList(sessionId)
```
```kotlin
    /** 조언 세션의 첫 턴에 최근 글 목록을 한 번 읽어 둔다. 이미 있으면 그대로 쓴다(사용자 메시지 1회당 목록 1회 원칙은 도구 쪽 한도가 지킨다). */
    private suspend fun ensurePostList(sessionId: String) {
        val history = chatRepo.messagesOnce(sessionId)
        if (history.any { it.kind == MessageKind.BLOG_POSTS || (it.kind == MessageKind.SYSTEM && ChatPayloads.readText(it.payloadJson) == POSTS_FAILED) }) return
        val blogId = _uiState.value.blogId ?: return
        _uiState.update { it.copy(toolStatus = READING_POSTS) }
        val posts = blog.listPosts(blogId)
        if (posts == null) system(sessionId, POSTS_FAILED)
        else chatRepo.appendMessage(sessionId, MessageRole.SYSTEM, MessageKind.BLOG_POSTS, ChatPayloads.blogPosts(posts))
        _uiState.update { it.copy(toolStatus = null) }
    }
```
- `context(session, draftTurn)`: `val all = chatRepo.messagesOnce(session.id)`; `history = all.filterNot { SYSTEM 조건 }`; `ChatContext(..., mode = session.mode, blogPosts = all.lastOrNull { it.kind == MessageKind.BLOG_POSTS }?.let { ChatPayloads.readBlogPosts(it.payloadJson) })`. 조언 세션이면 `attachments = emptyList()`, `photoGroups = emptyList()`, `currentPlan = null`, `currentPost = null`.
- `listener.onPostRead(logNo, title)`: 
```kotlin
        override fun onPostRead(logNo: String, title: String) {
            val sessionId = _uiState.value.session?.id ?: return
            val view = PostView(logNo, title)
            _uiState.update { it.copy(focusedPost = view, panelOpen = true, listCollapsed = true) }
            viewModelScope.launch { if (isCurrent(sessionId)) chatRepo.appendMessage(sessionId, MessageRole.SYSTEM, MessageKind.POST_VIEW, ChatPayloads.postView(view)) }
        }
```
- `onSuccess`: 맨 앞에
```kotlin
        if (session.mode == SessionMode.ADVICE) {
            chatRepo.appendMessage(sessionId, MessageRole.ASSISTANT, MessageKind.TEXT, ChatPayloads.text(response.say))
            _uiState.update { it.copy(streamingSay = null) }
            if (session.title == null) {
                val first = chatRepo.messagesOnce(sessionId).firstOrNull { it.role == MessageRole.USER && it.kind == MessageKind.TEXT }
                first?.let { updateSession(session.copy(title = ChatPayloads.readText(it.payloadJson).take(ADVICE_TITLE_MAX))) }
            }
            return
        }
```
- `restoreFocusedPost(history) = history.lastOrNull { it.kind == MessageKind.POST_VIEW }?.let { ChatPayloads.readPostView(it.payloadJson) }`.
- `togglePanel/openPanel` 은 `hasPanel` 을 쓰므로 그대로.

- [ ] **Step 3: 테스트 통과**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.ui.chat.ChatViewModelTest" 2>&1 | grep -E "FAILED|BUILD|tests completed"` → BUILD SUCCESSFUL (기존 테스트 전부 포함).

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/ui/chat/ChatUiModels.kt app/src/main/java/com/csh/blogwriter/ui/chat/ChatViewModel.kt app/src/test/java/com/csh/blogwriter/ui/chat/ChatViewModelTest.kt
git commit -m "feat(조언): ChatViewModel 모드 선택·첫 턴 글 목록 읽기·글 보기 패널 상태·WRITE 전용 기능 가드"
```

---

### Task 6: 화면 — 컴포저 모드 칩, 히어로, 세션 목록, 오른쪽 글 보기 패널

**Files:**
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/components/Composer.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/components/SessionListPane.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/components/PublishedPostPanel.kt`

**Interfaces:**
- Consumes: `ChatUiState.mode/blogId/focusedPost`, `ChatViewModel.setMode`, `postUrl(blogId, logNo)`
- Produces: `Composer(..., mode: SessionMode, onModeChange: ((SessionMode) -> Unit)?)`, `PublishedPostPanel(url, title = "발행한 글", modifier)`

- [ ] **Step 1: Composer — 입력창 안 하단의 모드 칩**

`Composer` 에 인자 `mode: SessionMode = SessionMode.WRITE`, `onModeChange: ((SessionMode) -> Unit)? = null`(null 이면 눌리지 않는 라벨). `OutlinedTextField` 를 아래 컨테이너로 감싼다(텍스트 필드의 컨테이너·테두리는 투명으로 바꾸고, 테두리는 바깥 Column 이 그린다):
```kotlin
        var focused by remember { mutableStateOf(false) }
        Column(
            Modifier.weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(c.surfaceWeak)
                .border(1.dp, if (focused) c.fillBrand else c.surfaceWeak, RoundedCornerShape(24.dp)),
        ) {
            OutlinedTextField(
                value = text, onValueChange = onTextChange, enabled = enabled,
                modifier = Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = if (hero) HeroMinHeight else AppSpacing.ctaHeight)
                    .onFocusChanged { focused = it.isFocused; onFocusChanged(it.isFocused) },
                textStyle = AppTheme.typography.body1.copy(color = c.textPrimary),
                placeholder = { Text(placeholder, style = AppTheme.typography.body1, color = c.textTertiary) },
                shape = RoundedCornerShape(24.dp),
                maxLines = if (hero) 8 else 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent, cursorColor = c.fillBrand,
                ),
            )
            // 입력창 안 하단: 글쓰기/조언 모드. 세션이 생긴 뒤에는 라벨만 남는다.
            Row(Modifier.fillMaxWidth().padding(start = AppSpacing.md, end = AppSpacing.md, bottom = AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                ModeChip(mode, onModeChange)
            }
        }
```
```kotlin
private fun SessionMode.label() = when (this) { SessionMode.WRITE -> "✍️ 글쓰기"; SessionMode.ADVICE -> "💬 조언" }

@Composable
private fun ModeChip(mode: SessionMode, onModeChange: ((SessionMode) -> Unit)?) {
    val c = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(AppSpacing.radiusControl))
                .background(if (onModeChange != null) c.fillBrandWeak else Color.Transparent)
                .then(if (onModeChange != null) Modifier.clickable { expanded = true } else Modifier)
                .padding(horizontal = AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(mode.label(), style = AppTheme.typography.body2, color = if (onModeChange != null) c.fillBrand else c.textTertiary)
            if (onModeChange != null) Icon(Icons.Rounded.ArrowDropDown, contentDescription = "모드 고르기", tint = c.fillBrand)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SessionMode.entries.forEach { m ->
                DropdownMenuItem(text = { Text(m.label(), style = AppTheme.typography.body1) }, onClick = { expanded = false; onModeChange?.invoke(m) }, modifier = Modifier.heightIn(min = AppSpacing.touchTarget))
            }
        }
    }
}
```
(import: `androidx.compose.foundation.border`, `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.Box/Column/heightIn`, `androidx.compose.material3.DropdownMenu/DropdownMenuItem`, `androidx.compose.material.icons.rounded.ArrowDropDown`, `androidx.compose.ui.graphics.Color`.) 사진 버튼은 `canAttach` 가 false 면 기존처럼 비활성이 아니라 **숨긴다**: `if (canAttach) { IconButton(...); Spacer }`. 단, 글쓰기 모드에서 초안 뒤(`panelJobId != null`)에도 숨겨지면 안내 문구와 어긋나므로 인자를 둘로 나눈다 — `showAttach: Boolean = true`(조언이면 false), `canAttach`(기존 의미).

- [ ] **Step 2: ChatScreen 연결**

`ChatPane` 의 `composer` 람다:
```kotlin
            Composer(
                text = draft, onTextChange = { draft = it },
                onSend = { justSent = true; viewModel.send(draft); draft = "" },
                onAttach = viewModel::attachPhotos,
                enabled = ui.hasKey && !ui.thinking,
                placeholder = when {
                    ui.thinking -> if (ui.mode == SessionMode.ADVICE) "글을 읽고 있어요" else "글을 구상하고 있어요"
                    ui.mode == SessionMode.ADVICE -> "어떤 글을 봐 드릴까요? 예: 최근 글 봐 줘"
                    else -> "오늘 있었던 일을 들려주세요"
                },
                showAttach = ui.mode == SessionMode.WRITE,
                canAttach = ui.panelJobId == null,
                hero = hero,
                onFocusChanged = onComposerFocusChanged,
                mode = ui.mode,
                onModeChange = if (ui.session == null) viewModel::setMode else null,
            )
```
- 히어로 제목: `if (ui.mode == SessionMode.ADVICE) "블로그를 함께 살펴볼까요?" else "오늘은 어떤 이야기를 올릴까요?"`.
- `AttachmentTray(ui, viewModel)` 두 호출, 초안 버튼 블록, `NO_PHOTO_AFTER_DRAFT` 문구: 모두 `ui.mode == SessionMode.WRITE` 조건으로 감싼다(초안 버튼 조건 맨 앞에 `ui.mode == SessionMode.WRITE &&`).
- `LoginNudge(onLogin)` → `LoginNudge(onLogin, text = if (ui.mode == SessionMode.ADVICE) ChatViewModel.ADVICE_NEEDS_LOGIN else "네이버에 로그인되어 있지 않아요")` — `LoginNudge` 의 기존 문구를 확인해 기본 인자로 보존.
- 상단 바 토글 아이콘 설명: `val what = when { ui.mode == SessionMode.ADVICE -> "글"; ui.panelJobId != null -> "초안"; else -> "계획" }`.
- 오른쪽 패널(`ChatScreen` 본체): 
```kotlin
    val advicePostUrl = ui.focusedPost?.let { p -> ui.blogId?.let { id -> postUrl(id, p.logNo) } }?.takeIf { ui.mode == SessionMode.ADVICE }
    val showEditor = ui.mode == SessionMode.WRITE && panelMounted && ui.panelJobId != null
    val publishedUrl = ui.session?.takeIf { it.mode == SessionMode.WRITE && it.status == SessionStatus.PUBLISHED }?.publishedUrl
    val planMarkdown = if (ui.mode == SessionMode.WRITE && !showEditor && publishedUrl == null) ui.plan else null
    val panelMountedNow = showEditor || planMarkdown != null || publishedUrl != null || advicePostUrl != null
```
넓은/좁은 두 자리의 내용 분기 맨 앞에 `if (advicePostUrl != null) PublishedPostPanel(advicePostUrl, title = ui.focusedPost?.title ?: "글 보기") else if (publishedUrl != null) ...`. `LaunchedEffect(url)` 이 있어 다른 글을 읽으면 같은 WebView 가 새 주소를 연다.

- [ ] **Step 3: PublishedPostPanel 제목 인자**

`fun PublishedPostPanel(url: String, title: String = "발행한 글", modifier: Modifier = Modifier)` — 헤더 `Text("발행한 글", …)` 을 `Text(title, …, maxLines = 1, overflow = TextOverflow.Ellipsis)` 로.

- [ ] **Step 4: 세션 목록**

`SessionRow`: 제목 `Text` 앞에 `if (session.mode == SessionMode.ADVICE) { Text("조언", style = AppTheme.typography.caption, color = c.fillBrand, modifier = Modifier.clip(RoundedCornerShape(AppSpacing.radiusControl)).background(c.fillBrandWeak).padding(horizontal = AppSpacing.sm, vertical = 2.dp)); Spacer(Modifier.width(AppSpacing.xs)) }` — 제목 줄을 `Row(verticalAlignment = CenterVertically)` 로 감싼다. 둘째 줄: `"${relativeTime(session.updatedAt)} · ${if (session.mode == SessionMode.ADVICE) "조언" else statusLabel(session.status)}"`.

- [ ] **Step 5: 빌드**

Run: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug 2>&1 | grep -vE "WARNING|Daemon|honour" | tail -8` → BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/ui/chat/components/Composer.kt app/src/main/java/com/csh/blogwriter/ui/chat/ChatScreen.kt app/src/main/java/com/csh/blogwriter/ui/chat/components/SessionListPane.kt app/src/main/java/com/csh/blogwriter/ui/chat/components/PublishedPostPanel.kt
git commit -m "feat(조언): 입력창 안 모드 칩(글쓰기/조언), 조언 히어로·세션 표시, 오른쪽 글 보기 패널"
```

---

### Task 7: 프롬프트 화면 — 글쓰기/조언 그룹, 수정됨 `*`

**Files:**
- Modify: `app/src/main/java/com/csh/blogwriter/ui/admin/PromptsScreen.kt`

- [ ] **Step 1: 그룹 헤더 + `*`**

`LazyColumn` 을 그룹별로:
```kotlin
        LazyColumn {
            PromptGroup.entries.forEach { group ->
                val inGroup = sections.filter { it.section.group == group }
                if (inGroup.isEmpty()) return@forEach
                item(key = "header-${group.name}") {
                    Text(group.title, style = AppTheme.typography.title2, color = AppTheme.colors.textPrimary, modifier = Modifier.padding(bottom = AppSpacing.md))
                }
                items(inGroup, key = { it.section }) { state -> PromptCard(...); Spacer(Modifier.height(AppSpacing.lg)) }
                item(key = "gap-${group.name}") { Spacer(Modifier.height(AppSpacing.xl)) }
            }
        }
```
`PromptCard` 제목: `Text(if (state.overridden) "${state.section.title} *" else state.section.title, …)`. 안내 문구 아래 한 줄 추가: `"제목에 * 가 붙은 섹션은 직접 고친 것이라, 앱을 업데이트해도 새 기본값이 적용되지 않아요. 새 기본값을 쓰려면 되돌려 주세요."`.

- [ ] **Step 2: 빌드·커밋**

Run: `./gradlew.bat :app:assembleDebug 2>&1 | grep -vE "WARNING|Daemon|honour" | tail -4`
```bash
git add app/src/main/java/com/csh/blogwriter/ui/admin/PromptsScreen.kt
git commit -m "feat(프롬프트 화면): 글쓰기/조언 그룹으로 나누고 수정된 섹션 제목에 * 표시"
```

---

### Task 8: 통합 검증·설치·실기기 점검 (오케스트레이터)

- [ ] 전체 테스트 + assembleDebug 녹색.
- [ ] Tab35(emulator-5556)에 설치(`installDebug`), 확인: 새 세션 컴포저의 모드 칩 → 조언 → 히어로 제목 변경·사진 버튼 없음 → 로그인돼 있으면 "최근 글 봐 줘" → "최근 글 N개를 읽었어요" 줄 → 모델이 `read_my_post` → 오른쪽에 글이 뜨고 조언이 인용을 포함하는지. 로그인 안 돼 있으면 로그인 안내 문구. 글쓰기 세션은 이전과 동일(칩 라벨 "✍️ 글쓰기", 사진 버튼 있음).
- [ ] 프롬프트 화면: 두 그룹, 수정 후 `*`.
- [ ] `git push origin main`. 문서: `docs/superpowers/specs/2026-08-29-advice-mode-design.md` 는 구현과 어긋난 곳(도구 한도가 실행기에 있음 등)만 한 줄씩 맞춘다.
