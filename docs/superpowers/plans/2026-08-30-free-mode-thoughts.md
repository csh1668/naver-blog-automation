# 자유 모드 + 생각 표시 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 채팅에 범용 비서 "자유 모드"(기억 반영·제안, 사진·검색 가능)를 추가하고, 세 모드 모두에서 모델의 생각 요약을 연하게 보여 주다가 답이 오면 접는다.

**Architecture:** 조언 모드와 같은 모드 seam(`SessionMode` → 프롬프트 섹션·스키마·도구·contents 분기)에 `FREE`를 더한다. 생각 표시는 Gemini `includeThoughts`로 오는 `thought: true` 파트를 엔진이 답 텍스트와 분리해 `TurnListener.onPartialThought`로 흘리고, 최종 생각을 어시스턴트 메시지 payload(`thought`)에 저장, 화면은 `ThoughtBlock`으로 그린다. DB 변경 없음.

**Tech Stack:** Kotlin, Jetpack Compose(Material3), Hilt, Room(v3 그대로), kotlinx-serialization, OkHttp, JUnit4 + Robolectric + MockWebServer + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-08-30-free-mode-thoughts-design.md`

## Global Constraints

- 패키지 `com.csh.blogwriter`, 소스 `app/src/main/java/com/csh/blogwriter/`, 테스트 `app/src/test/java/com/csh/blogwriter/`.
- 빌드·테스트: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug 2>&1 | grep -vE "WARNING|Daemon|honour" | tail -8` (timeout 600 s). **`installDebug` 금지**(오케스트레이터가 한다).
- 기능 허용 조건은 allow-list로 쓴다: 사진 첨부·사진판 `mode in setOf(WRITE, FREE)`; 묶기·초안·계획·발행·품질 게이트 `mode == WRITE`; 글 보기 패널·글 목록·로그인 필수 `mode == ADVICE`; 로그인 안내 `mode in setOf(WRITE, ADVICE)`. `!= ADVICE` 같은 부정형 금지.
- thinkingLevel: WRITE는 기존(계획 low / 초안·수정 high), ADVICE·FREE는 `high`. 설정 화면 없음. 모든 요청에 `includeThoughts: true`.
- 생각 요약은 영어 그대로, 라벨 없음. 답 파트가 처음 오면 강제로 접힘. 모델 히스토리에는 생각을 싣지 않는다.
- 문구 "~해요"체, 기술 용어 없음, 터치 56dp(`AppSpacing.touchTarget`). 칩 라벨 `✍️ 글쓰기` / `💬 조언` / `🤖 자유`. 히어로 "무엇이든 물어보세요", 플레이스홀더 "궁금한 것을 물어보세요", 생각 중 "생각하고 있어요".
- 기존 코드 스타일(긴 한 줄, 한국어 "왜" 주석). 무관한 리팩터링 금지. 커밋 메시지 한국어 + 트레일러 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. 자기 파일만 `git add`.
- 저장소에 실제 블로그 id·글·API 키를 넣지 않는다.

---

### Task 1: Gemini 생각 파트 분리 + 엔진 `onPartialThought` + `includeThoughts`

**Files:**
- Modify: `app/src/main/java/com/csh/blogwriter/llm/GeminiModels.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/chat/ChatContext.kt` (`TurnListener`, `TurnResult.Success`)
- Modify: `app/src/main/java/com/csh/blogwriter/chat/ConversationEngine.kt` (`runWithTools`)
- Test: `app/src/test/java/com/csh/blogwriter/llm/GeminiModelsTest.kt`(신규), `app/src/test/java/com/csh/blogwriter/chat/ConversationEngineTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data class GPart(..., val thoughtSignature: String? = null, /** includeThoughts 로 오는 생각 요약 파트. */ val thought: Boolean? = null)
  @Serializable data class GThinkingConfig(val thinkingLevel: String, val includeThoughts: Boolean = true)
  // GResponse
  val text: String?         // thought 파트 제외
  val thoughtText: String?  // thought 파트만
  interface TurnListener { ...; /** 지금까지의 생각 요약 전체(교체). 새 시도(attempt)마다 "" 로 초기화. */ fun onPartialThought(text: String) {} }
  data class TurnResult.Success(val response: TurnResponse, val repairs: List<String>, val usedModel: String, val thought: String? = null)
  ```

- [ ] **Step 1: 실패 테스트 — 모델 파싱**

`llm/GeminiModelsTest.kt`:
```kotlin
package com.csh.blogwriter.llm

class GeminiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun thoughtPartsAreSeparatedFromText() {
        val r = json.decodeFromString(GResponse.serializer(), """{"candidates":[{"content":{"role":"model","parts":[{"text":"**Plan** thinking","thought":true},{"text":"{\"say\":\"안녕\"}"}]}}]}""")
        assertEquals("{\"say\":\"안녕\"}", r.text)
        assertEquals("**Plan** thinking", r.thoughtText)
    }

    @Test fun noThoughtGivesNullThoughtText() {
        val r = json.decodeFromString(GResponse.serializer(), """{"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]}}]}""")
        assertEquals("hi", r.text); assertNull(r.thoughtText)
    }

    @Test fun thinkingConfigSerializesIncludeThoughts() {
        assertEquals("""{"thinkingLevel":"high","includeThoughts":true}""", Json.encodeToString(GThinkingConfig.serializer(), GThinkingConfig("high")))
    }
}
```

- [ ] **Step 2: 모델 구현**

`GeminiModels.kt`: `GPart`에 `val thought: Boolean? = null`; `GThinkingConfig(val thinkingLevel: String, val includeThoughts: Boolean = true)`; `GResponse`:
```kotlin
    private val parts get() = candidates.firstOrNull()?.content?.parts.orEmpty()
    /** 답 텍스트. 생각 요약(thought) 파트는 뺀다 — JSON 파싱이 생각 문장으로 오염되지 않게. */
    val text: String? get() = parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("").takeIf { it.isNotEmpty() }
    val thoughtText: String? get() = parts.filter { it.thought == true }.mapNotNull { it.text }.joinToString("").takeIf { it.isNotEmpty() }
    val functionCalls: List<GFunctionCall> get() = parts.mapNotNull { it.functionCall }
```

- [ ] **Step 3: 실패 테스트 — 엔진**

`ConversationEngineTest`에 헬퍼와 테스트 추가(기존 `chunk`/`sse`/`textResponse`/엔진 생성 헬퍼 재사용):
```kotlin
    private fun thoughtChunk(text: String) = """{"candidates":[{"content":{"role":"model","parts":[{"text":${quote(text)},"thought":true}]}}]}"""

    @Test
    fun thoughtPartsStreamSeparatelyAndAreReturned() = runTest {
        server.enqueue(sse(thoughtChunk("**Plan** "), thoughtChunk("think"), chunk("""{"say":"이렇게"""), chunk(""" 써 볼까요?","quickReplies":[],"readyToDraft":false}""")))
        val thoughts = mutableListOf<String>(); val says = mutableListOf<String>()
        val listener = object : TurnListener {
            override fun onToolStatus(text: String) {}
            override fun onPartialSay(text: String) { says += text }
            override fun onPartialThought(text: String) { thoughts += text }
        }
        val r = engine().runTurn(ctx(), listener) as TurnResult.Success
        assertEquals("이렇게 써 볼까요?", r.response.say)
        assertEquals("**Plan** think", r.thought)
        assertEquals(listOf("", "**Plan** ", "**Plan** think"), thoughts)   // 시도 시작의 "" 다음 누적
        assertFalse(says.any { it.contains("Plan") })
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"includeThoughts\":true"))
    }

    @Test
    fun thoughtsAccumulateAcrossToolRounds() = runTest {
        server.enqueue(sse(thoughtChunk("first"), callChunk))
        server.enqueue(sse(thoughtChunk("second"), chunk("""{"say":"끝","quickReplies":[],"readyToDraft":false}""")))
        val thoughts = mutableListOf<String>()
        val listener = object : TurnListener { override fun onToolStatus(text: String) {}; override fun onPartialSay(text: String) {}; override fun onPartialThought(text: String) { thoughts += text } }
        val r = engine().runTurn(ctx(), listener) as TurnResult.Success
        assertEquals("first\n\nsecond", r.thought)
        assertEquals("first\n\nsecond", thoughts.last())
    }
```
(`callChunk`는 파일에 이미 있는 `web_search` 호출 청크. 도구 실행기 가짜가 `web_search`를 처리하는 기존 방식을 그대로 쓴다.)

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.csh.blogwriter.chat.ConversationEngineTest" --tests "com.csh.blogwriter.llm.GeminiModelsTest" 2>&1 | grep -E "FAILED|BUILD"` → 컴파일 실패.

- [ ] **Step 4: 엔진 구현**

`ChatContext.kt`: `TurnListener`에 `fun onPartialThought(text: String) {}`; `TurnResult.Success`에 `val thought: String? = null`.

`ConversationEngine.runWithTools`: 루프 밖에 `var thoughtAll = ""`; `listener.onPartialThought("")`를 `runWithTools` 시작(루프 앞)에 한 번. 루프 안 collect:
```kotlin
            var roundThought = ""
            client.generateStream(secret, model, req).collect { chunk ->
                chunk.thoughtText?.let { piece ->
                    roundThought += piece
                    listener.onPartialThought(joinThoughts(thoughtAll, roundThought))
                }
                chunk.text?.let { text += it }
                ...
            }
            if (roundThought.isNotEmpty()) thoughtAll = joinThoughts(thoughtAll, roundThought)
```
```kotlin
    /** 도구 라운드가 이어질 때 생각 요약을 빈 줄로 잇는다. */
    private fun joinThoughts(prev: String, next: String) = if (prev.isEmpty()) next else if (next.isEmpty()) prev else prev + "\n\n" + next
```
성공 반환: `TurnResult.Success(..., model, thought = thoughtAll.ifBlank { null })`. JSON 재시도(온도 0)와 도구 라운드 모두 같은 `runWithTools` 안이라 `thoughtAll`이 이어진다; 키/모델 로테이션은 `runWithTools`를 다시 부르므로 ""부터.

- [ ] **Step 5: 테스트 통과 + 전체 빌드**

Run: 전체 명령. Expected: BUILD SUCCESSFUL(기존 테스트 전부 포함 — `GThinkingConfig` 직렬화가 바뀌어 `"thinkingConfig":{"thinkingLevel":"high"}` 같은 문자열 단언이 있으면 `includeThoughts` 포함으로 고친다).

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/llm/GeminiModels.kt app/src/main/java/com/csh/blogwriter/chat/ChatContext.kt app/src/main/java/com/csh/blogwriter/chat/ConversationEngine.kt app/src/test/java/com/csh/blogwriter/llm/GeminiModelsTest.kt app/src/test/java/com/csh/blogwriter/chat/ConversationEngineTest.kt
git commit -m "feat(생각): Gemini 생각 요약 파트를 답과 분리해 흘리고(onPartialThought) 턴 결과에 담는다"
```

---

### Task 2: 자유 모드 — SessionMode.FREE, 프롬프트 2개, PromptBuilder·TurnSchemas·엔진 분기

**Files:**
- Modify: `app/src/main/java/com/csh/blogwriter/data/repo/ChatRepository.kt` (`SessionMode`)
- Create: `app/src/main/assets/prompts/f1_free_role.md`, `f2_free_memory.md`
- Modify: `app/src/main/java/com/csh/blogwriter/chat/PromptStore.kt`, `PromptBuilder.kt`, `TurnSchemas.kt`, `ConversationEngine.kt`
- Test: `PromptBuilderTest.kt`, `TurnSchemasTest.kt`, `ConversationEngineTest.kt`

**Interfaces:**
- Consumes: Task 1의 `TurnResult.Success.thought`(무관), 기존 `ChatContext.mode`.
- Produces: `SessionMode.FREE`; `PromptGroup.FREE("자유")`; `PromptSection.FREE_ROLE("f1_free_role.md", "자유·역할", PromptGroup.FREE)`, `FREE_MEMORY("f2_free_memory.md", "자유·기억 제안", PromptGroup.FREE)`; `PromptBuilder.system(mode = FREE)` = FREE_ROLE → MEMORY → FREE_MEMORY; `TurnSchemas.turnResponseJsonSchema(FREE)` = `{say}`; `functionDeclarations(FREE)` = `web_search, open_page, remember`; 엔진: FREE 턴 thinking high, contents = 첨부 + TEXT/PHOTOS 히스토리만.

- [ ] **Step 1: 프롬프트 파일**

`f1_free_role.md`:
```
당신은 사용자의 한국어 비서입니다. 블로그 글쓰기와는 상관없는 일반 대화·질문·계산·정리·번역·요약을 돕습니다.
짧고 정확하게 답합니다. 모르면 모른다고 말하고 추측을 사실처럼 말하지 않습니다. 최신 정보나 사실 확인이 필요하면 web_search 로 찾아보고 출처를 한 줄로 밝힙니다.
사용자는 컴퓨터에 익숙하지 않습니다. 기술 용어는 풀어서 쓰고, 단계가 있으면 번호 목록으로 씁니다. 제목(#)·목록(-, 1.)·굵게(**) 같은 간단한 마크다운은 써도 됩니다.
사진이 붙어 있으면 사진에 보이는 것을 근거로 답합니다.
답은 항상 지정된 JSON 스키마({say})로만 냅니다.
```

`f2_free_memory.md`:
```
[기억 제안]
대화 중에 사용자의 취향·습관·자주 쓰는 표현·가족이나 단골 가게 같은 사실처럼 "다음에 블로그 글을 쓸 때 참고하면 좋을 것"이 나오면, 답의 맨 끝에 한 줄로 제안합니다:
이 내용 기억할까요? — "기억할 문장"
한 답에 제안은 하나까지. 이미 [사용자에 대해 기억하는 것]에 있는 내용이나 이 대화에서 사용자가 거절한 내용은 다시 묻지 않습니다.
사용자가 좋다고 하면 그때 remember 도구로 저장하고 "기억해 둘게요: …" 한 줄로 알립니다. 묻지 않고 저장하지 않습니다.
사용자가 "이거 기억해 줘"라고 직접 말하면 묻지 않고 바로 저장합니다.
```

- [ ] **Step 2: enum·섹션**

`ChatRepository.kt`: `enum class SessionMode { WRITE, ADVICE, FREE }`.
`PromptStore.kt`: `enum class PromptGroup(val title: String) { WRITE("글쓰기"), ADVICE("조언"), FREE("자유") }`; `PromptSection`에 `FREE_ROLE("f1_free_role.md", "자유·역할", PromptGroup.FREE), FREE_MEMORY("f2_free_memory.md", "자유·기억 제안", PromptGroup.FREE)` 추가.

- [ ] **Step 3: 실패 테스트**

`PromptBuilderTest`(`texts`에 `PromptSection.FREE_ROLE to "자유 역할", PromptSection.FREE_MEMORY to "자유 기억"` 추가):
```kotlin
    @Test
    fun freeModeAssemblesRoleMemoryAndSuggestionOnly() = runTest {
        val s = PromptBuilder(store).system(memory = listOf(mem(1)), style = "존댓말", targetLength = 900..1400, draftTurn = false, mode = SessionMode.FREE)
        val idx = listOf("자유 역할", "- PREFERENCE: 항목1", "자유 기억").map { s.indexOf(it) }
        assertTrue(idx.all { it >= 0 }); assertEquals(idx, idx.sorted())
        listOf("역할 문안", "독자 문안", "스타일:", "구조 문안", "대화 문안", "출력 문안", "점검 문안", "조언 역할").forEach { assertFalse(it, s.contains(it)) }
    }
```
`TurnSchemasTest`:
```kotlin
    @Test fun freeSchemaAndTools() {
        val s = TurnSchemas.turnResponseJsonSchema(SessionMode.FREE)
        assertEquals(setOf("say"), s["properties"]!!.jsonObject.keys)
        assertEquals(listOf("web_search", "open_page", "remember"), TurnSchemas.functionDeclarations(SessionMode.FREE).map { it.name })
    }
```
`ConversationEngineTest`:
```kotlin
    @Test
    fun freeTurnSendsPhotosButNoPlanInstructionsAndThinksHigh() = runTest {
        server.enqueue(textResponse("""{"say":"안녕하세요"}"""))
        val ctx = ChatContext(history = listOf(userText("사진에 뭐가 있어?")), attachments = listOf(Attachment("img_001", "AAAA")), style = "존댓말", draftTurn = false, currentPost = null, mode = SessionMode.FREE)
        val r = engine().runTurn(ctx, silentListener) as TurnResult.Success
        assertEquals("안녕하세요", r.response.say)
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val system = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(system.contains("[FREE_ROLE]")); assertFalse(system.contains("[STRUCTURE]")); assertFalse(system.contains("[ADVICE_ROLE]"))
        assertTrue(body.toString().contains("\"inlineData\""))
        val userTexts = body["contents"]!!.jsonArray.flatMap { it.jsonObject["parts"]!!.jsonArray }.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
        assertFalse(userTexts.any { it.contains("plan 을") || it.contains("현재 계획") || it.contains("post 를") })
        assertEquals("high", body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals(listOf("say"), body["generationConfig"]!!.jsonObject["responseJsonSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val tools = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("web_search", "open_page", "remember"), tools)
    }
```
(`userText(...)`/`silentListener`는 파일에 있으면 재사용, 없으면 2~3줄로 만든다.)

- [ ] **Step 4: 구현**

`PromptBuilder.system`의 `when (mode)`에 `SessionMode.FREE -> listOf(store.text(PromptSection.FREE_ROLE), memorySection, store.text(PromptSection.FREE_MEMORY))` (주석: 자유는 블로그 페르소나·스타일을 싣지 않고 기억만 공유한다).

`TurnSchemas`: `sayOnly` 스키마를 private val로 빼서 ADVICE·FREE가 공유; `functionDeclarations(mode)`: `SessionMode.WRITE, SessionMode.FREE -> writeTools`(기존 3개), `SessionMode.ADVICE -> adviceTools`.

`ConversationEngine`:
- thinking: `val thinkingLevel = if (ctx.mode == SessionMode.ADVICE || ctx.mode == SessionMode.FREE || ctx.draftTurn || ctx.currentPost != null) "high" else "low"`.
- `buildContents` 맨 앞: `if (ctx.mode == SessionMode.FREE) return freeContents(ctx)`(ADVICE 분기 옆).
```kotlin
    /** 자유 모드: 첨부 사진 + 말·사진 기록만. 계획·초안·사실 확인 지시는 붙이지 않는다. */
    private fun freeContents(ctx: ChatContext): List<GContent> {
        val out = mutableListOf<GContent>()
        attachmentContent(ctx, withGroups = false)?.let { out += it }
        ctx.history.forEach { m ->
            if (m.role == MessageRole.SYSTEM) return@forEach
            val text = when (m.kind) {
                MessageKind.TEXT -> runCatching { json.parseToJsonElement(m.payloadJson).jsonObject["text"]!!.jsonPrimitive.content }.getOrDefault(m.payloadJson)
                MessageKind.PHOTOS -> "(사진 ${runCatching { json.parseToJsonElement(m.payloadJson).jsonObject["count"]!!.jsonPrimitive.content }.getOrDefault("")}장 첨부)"
                else -> return@forEach
            }
            out += GContent(if (m.role == MessageRole.USER) "user" else "model", listOf(GPart(text = text)))
        }
        return out
    }

    /** 첨부 사진을 첫 user 파트로. [withGroups] 면 사용자 묶음 줄도 붙인다(글쓰기). 첨부가 없으면 null. */
    private fun attachmentContent(ctx: ChatContext, withGroups: Boolean): GContent? {
        if (ctx.attachments.isEmpty()) return null
        val parts = mutableListOf(GPart(text = "첨부 사진 (ref 순서대로):"))
        ctx.attachments.forEach { a -> parts += GPart(text = "ref=${a.ref}"); parts += GPart(inlineData = GInlineData(a.mimeType, a.jpegBase64)) }
        if (withGroups) ctx.photoGroups.forEach { group -> parts += GPart(text = "$USER_GROUP[${group.joinToString(", ")}] — 이 사진들은 imageGroup 블록 하나(COLLAGE)로만 쓰고 따로 떼지 않는다") }
        return GContent("user", parts)
    }
```
기존 `buildContents`의 첨부 블록은 `attachmentContent(ctx, withGroups = true)?.let { out += it }`로 바꾼다(동작 동일).

- [ ] **Step 5: 테스트 통과 + 전체 빌드**

Run: 전체 명령 → BUILD SUCCESSFUL. `when (mode)`가 exhaustive인 곳(PromptBuilder, TurnSchemas, ChatUiState.hasPanel 등)에 FREE 분기가 빠지면 컴파일 에러가 알려 준다 — `hasPanel`은 `SessionMode.FREE -> false`.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/assets/prompts/f1_free_role.md app/src/main/assets/prompts/f2_free_memory.md app/src/main/java/com/csh/blogwriter/data/repo/ChatRepository.kt app/src/main/java/com/csh/blogwriter/chat/PromptStore.kt app/src/main/java/com/csh/blogwriter/chat/PromptBuilder.kt app/src/main/java/com/csh/blogwriter/chat/TurnSchemas.kt app/src/main/java/com/csh/blogwriter/chat/ConversationEngine.kt app/src/main/java/com/csh/blogwriter/ui/chat/ChatUiModels.kt app/src/test/java/com/csh/blogwriter/chat/PromptBuilderTest.kt app/src/test/java/com/csh/blogwriter/chat/TurnSchemasTest.kt app/src/test/java/com/csh/blogwriter/chat/ConversationEngineTest.kt
git commit -m "feat(자유): SessionMode.FREE — 범용 비서 프롬프트 2개, {say} 스키마·검색/기억 도구, 첨부+대화만 싣는 contents, thinking high"
```

---

### Task 3: payload·ChatViewModel — 생각 저장/스트리밍 상태, 자유 세션 규칙

**Files:**
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/ChatUiModels.kt` (`ChatUiState`, `ChatPayloads`)
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/csh/blogwriter/ui/chat/ChatPayloadsTest.kt`, `ChatViewModelTest.kt`

**Interfaces:**
- Consumes: `TurnResult.Success.thought`, `TurnListener.onPartialThought`(Task 1), `SessionMode.FREE`(Task 2).
- Produces:
  ```kotlin
  data class ChatUiState(..., val streamingThought: String? = null, val thoughtCollapsed: Boolean = false)
  object ChatPayloads { fun assistantText(text: String, thought: String?): String; fun readThought(payload: String): String? }
  class ChatViewModel { fun toggleStreamingThought(); companion object { val PHOTO_MODES = setOf(SessionMode.WRITE, SessionMode.FREE) } }
  ```

- [ ] **Step 1: 실패 테스트 — payload**

`ChatPayloadsTest`:
```kotlin
    @Test fun assistantTextCarriesThoughtAndStaysReadable() {
        val p = ChatPayloads.assistantText("답", "생각 요약")
        assertEquals("답", ChatPayloads.readText(p)); assertEquals("생각 요약", ChatPayloads.readThought(p))
        val noThought = ChatPayloads.assistantText("답", null)
        assertEquals("답", ChatPayloads.readText(noThought)); assertNull(ChatPayloads.readThought(noThought))
        assertFalse(noThought.contains("thought"))
        assertNull(ChatPayloads.readThought(ChatPayloads.text("옛 메시지")))
    }
```

- [ ] **Step 2: payload 구현**

`ChatPayloads`:
```kotlin
    /** 어시스턴트 말풍선. 생각 요약이 있으면 함께 담는다(모델 히스토리에는 싣지 않는다). */
    fun assistantText(text: String, thought: String?): String = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("text", text); if (!thought.isNullOrBlank()) put("thought", thought) })
    fun readThought(payload: String): String? = runCatching { json.parseToJsonElement(payload).jsonObject["thought"]?.jsonPrimitive?.content }.getOrNull()
```
`ChatUiState`에 `val streamingThought: String? = null`, `/** 답 파트가 오기 시작하면 true — 스트리밍 중인 생각을 접는다. */ val thoughtCollapsed: Boolean = false`.

- [ ] **Step 3: 실패 테스트 — 뷰모델**

`ChatViewModelTest`(기존 `onTurn` 훅·`FakeSettingsStore.blogIdFlow`·`say()` 헬퍼 재사용; `say`에 thought 인자 추가: `private fun say(text: String, thought: String? = null) = TurnResult.Success(TurnResponse(say = text), emptyList(), "m", thought)`):
```kotlin
    @Test
    fun freeSessionSendsWithoutLoginAllowsPhotosAndTitlesFromFirstMessage() = runTest {
        settings.blogIdFlow.value = null
        turns += say("안녕하세요")
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.FREE)
        vm.attachPhotos(listOf("content://a")); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attachments.size)
        vm.send("이 사진 뭐야?"); advanceUntilIdle()
        val session = chatRepo.sessions.value.single()
        assertEquals(SessionMode.FREE, session.mode); assertEquals("이 사진 뭐야?", session.title)
        assertNull(vm.uiState.value.error)
        val ctx = contexts.last()
        assertEquals(SessionMode.FREE, ctx.mode); assertEquals(1, ctx.attachments.size); assertNull(ctx.style); assertNull(ctx.currentPlan); assertNull(ctx.currentPost)
        assertTrue(ctx.photoGroups.isEmpty())
    }

    @Test
    fun freeSessionRejectsGroupingAndDraft() = runTest {
        turns += say("네")
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.FREE)
        vm.attachPhotos(listOf("content://a", "content://b")); advanceUntilIdle()
        vm.startGrouping(); assertNull(vm.uiState.value.groupPicks)
        vm.send("안녕"); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        assertEquals(1, contexts.size); assertNull(vm.uiState.value.plan); assertFalse(vm.uiState.value.hasPanel)
    }

    @Test
    fun streamingThoughtShowsThenCollapsesWhenAnswerStarts() = runTest {
        turns += say("답", thought = "**Plan** think")
        partials = emptyList()
        val seen = mutableListOf<Pair<String?, Boolean>>()
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.FREE)
        onTurn = { l ->
            l.onPartialThought(""); seen += vm.uiState.value.let { it.streamingThought to it.thoughtCollapsed }
            l.onPartialThought("**Plan**"); seen += vm.uiState.value.let { it.streamingThought to it.thoughtCollapsed }
            l.onPartialSay("답"); seen += vm.uiState.value.let { it.streamingThought to it.thoughtCollapsed }
        }
        vm.send("질문"); advanceUntilIdle()
        assertEquals(listOf(null to false, "**Plan**" to false, "**Plan**" to true), seen)
        // 턴이 끝나면 스트리밍 상태는 비고, 저장된 메시지가 생각을 이어받는다.
        assertNull(vm.uiState.value.streamingThought); assertFalse(vm.uiState.value.thoughtCollapsed)
        val last = chatRepo.of(chatRepo.sessions.value.single().id).last { it.role == MessageRole.ASSISTANT }
        assertEquals("**Plan** think", ChatPayloads.readThought(last.payloadJson)); assertEquals("답", ChatPayloads.readText(last.payloadJson))
    }

    @Test
    fun writeTurnAlsoStoresThought() = runTest {
        turns += TurnResult.Success(TurnResponse(say = "계획이에요", plan = PLAN, readyToDraft = true), emptyList(), "m", thought = "생각")
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어"); advanceUntilIdle()
        val last = chatRepo.of(chatRepo.sessions.value.single().id).last { it.role == MessageRole.ASSISTANT && it.kind == MessageKind.TEXT }
        assertEquals("생각", ChatPayloads.readThought(last.payloadJson))
    }

    @Test
    fun toggleStreamingThoughtFlipsCollapse() = runTest {
        val vm = newViewModel()
        vm.toggleStreamingThought(); assertTrue(vm.uiState.value.thoughtCollapsed)
        vm.toggleStreamingThought(); assertFalse(vm.uiState.value.thoughtCollapsed)
    }
```

- [ ] **Step 4: 뷰모델 구현**

- `companion`: `val PHOTO_MODES = setOf(SessionMode.WRITE, SessionMode.FREE)`.
- `attachPhotos`: 첫 줄 `if (_uiState.value.mode !in PHOTO_MODES) return`. `startGrouping`/`requestDraft`: 기존 `== WRITE` 가드 그대로.
- `send`: ADVICE 로그인 가드 그대로(FREE는 통과). `draftTurn` 계산 그대로(WRITE만).
- `runTurn` 시작 `copy(...)`에 `streamingThought = null, thoughtCollapsed = false`; `finally`의 초기화에도 둘 다.
- `listenerFor(sessionId)`:
```kotlin
        override fun onPartialThought(text: String) { if (isCurrent(sessionId)) _uiState.update { it.copy(streamingThought = text.ifEmpty { null }) } }
        // 답이 오기 시작하면 생각은 접는다 — 한 번 접히면 턴이 끝날 때까지 다시 펴지지 않는다(사용자가 탭하면 예외).
        override fun onPartialSay(text: String) { if (isCurrent(sessionId)) _uiState.update { it.copy(streamingSay = text.ifEmpty { null }, thoughtCollapsed = it.thoughtCollapsed || text.isNotEmpty()) } }
```
- `fun toggleStreamingThought() = _uiState.update { it.copy(thoughtCollapsed = !it.thoughtCollapsed) }`.
- `runTurn`: `is TurnResult.Success -> onSuccess(sessionId, result.response, result.repairs, result.thought)`.
- `onSuccess(sessionId, response, repairs, thought: String?)`: 조언 분기를 `if (session.mode != SessionMode.WRITE)`로 넓혀 ADVICE·FREE 공통(`say`만 저장 + 첫 메시지 제목) — 저장은 `ChatPayloads.assistantText(response.say, thought)`; WRITE 경로의 `appendMessage(... TEXT, ChatPayloads.text(say))`도 `ChatPayloads.assistantText(say, thought)`로.
- `context()`: `val write = session.mode == SessionMode.WRITE; val photos = session.mode in PHOTO_MODES` → `attachments = if (photos) photoAttachments.attachments(...) else emptyList()`, `photoGroups = if (write) ... else emptyList()`, `style = if (write) style else null`, 계획/초안/questionRounds는 `write`일 때만(기존).
- `open(id)`/`hasPanel`: FREE는 패널 없음(`ChatUiState.hasPanel`의 `when`에 `SessionMode.FREE -> false` — Task 2에서 넣었으면 그대로).

- [ ] **Step 5: 테스트 통과 + 전체 빌드**

Run: `--tests "com.csh.blogwriter.ui.chat.*"` → 녹색, 이어서 전체 명령 → BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/ui/chat/ChatUiModels.kt app/src/main/java/com/csh/blogwriter/ui/chat/ChatViewModel.kt app/src/test/java/com/csh/blogwriter/ui/chat/ChatPayloadsTest.kt app/src/test/java/com/csh/blogwriter/ui/chat/ChatViewModelTest.kt
git commit -m "feat(자유·생각): 생각 요약을 메시지에 저장하고 스트리밍 중 답이 오면 접는 상태, 자유 세션 규칙(로그인 불필요·사진 허용·묶기/초안 없음)"
```

---

### Task 4: 화면 — ThoughtBlock, 자유 모드 칩·히어로·사진판·마크다운 말풍선·세션 표시

**Files:**
- Create: `app/src/main/java/com/csh/blogwriter/ui/chat/components/ThoughtBlock.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/components/MessageBubble.kt`, `Composer.kt`, `SessionListPane.kt`
- Modify: `app/src/main/java/com/csh/blogwriter/ui/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `ChatUiState.streamingThought/thoughtCollapsed`, `ChatPayloads.readThought`, `viewModel.toggleStreamingThought()`, `ChatViewModel.PHOTO_MODES`(Task 3), `SessionMode.FREE`(Task 2).

- [ ] **Step 1: ThoughtBlock**

`components/ThoughtBlock.kt`:
```kotlin
package com.csh.blogwriter.ui.chat.components

/** 모델의 생각 요약. 연하고 작게, 접으면 첫 줄만. 탭으로 펴고 접는다(터치 56dp). */
@Composable
fun ThoughtBlock(text: String, expanded: Boolean, onToggle: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = AppSpacing.touchTarget).clickable(onClick = onToggle)
            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text.trim(),
            style = AppTheme.typography.caption, color = c.textTertiary,
            maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
```

- [ ] **Step 2: 마크다운 말풍선**

`MessageBubble(text: String, mine: Boolean, markdown: Boolean = false)`: 안쪽 `Text(...)`를 `if (markdown) MarkdownLite(text) else Text(...)`로. (`MarkdownLite`가 `textPrimary`로 그리는지 확인; 내 말풍선(`mine`)에는 markdown을 쓰지 않는다.)

- [ ] **Step 3: Composer·SessionRow**

`Composer.kt`: `SessionMode.label()`에 `SessionMode.FREE -> "🤖 자유"`.
`SessionListPane.kt` `SessionRow`: 칩 텍스트를 `val modeChip = when (session.mode) { SessionMode.WRITE -> null; SessionMode.ADVICE -> "조언"; SessionMode.FREE -> "자유" }`로 뽑아 `if (modeChip != null)` 칩, 둘째 줄 `modeChip ?: statusLabel(session.status)`.

- [ ] **Step 4: ChatScreen**

- 상수: `private val LOGIN_NUDGE_MODES = setOf(SessionMode.WRITE, SessionMode.ADVICE)`.
- 컴포저: `placeholder` when에 `ui.thinking && ui.mode == SessionMode.FREE -> "생각하고 있어요"`(thinking 분기 안에서 모드별), `ui.mode == SessionMode.FREE -> "궁금한 것을 물어보세요"`; `showAttach = ui.mode in ChatViewModel.PHOTO_MODES`.
- 히어로 제목: `when (ui.mode) { SessionMode.ADVICE -> "블로그를 함께 살펴볼까요?"; SessionMode.FREE -> "무엇이든 물어보세요"; SessionMode.WRITE -> "오늘은 어떤 이야기를 올릴까요?" }`.
- `AttachmentTray` 두 호출: `if (ui.mode in ChatViewModel.PHOTO_MODES)`. `AttachmentTray` 안 `canGroup = ui.mode == SessionMode.WRITE && ui.panelJobId == null && ui.attachments.size >= MIN_GROUP`.
- `LoginNudge` 두 호출: `if (!ui.loggedIn && ui.mode in LOGIN_NUDGE_MODES)`.
- 목록: `itemCount`에 `(if (ui.streamingThought != null) 1 else 0)` 더하고, `ui.streamingSay` item 앞에
```kotlin
                ui.streamingThought?.let { thought -> item(key = "streaming-thought") { ThoughtBlock(thought, expanded = !ui.thoughtCollapsed, onToggle = viewModel::toggleStreamingThought) } }
```
- `MessageItem(message, panelOpen, viewModel)` → `MessageItem(message, ui.mode, panelOpen, viewModel)`; TEXT 분기:
```kotlin
        MessageKind.TEXT -> {
            val mine = message.role == MessageRole.USER
            val thought = if (mine) null else ChatPayloads.readThought(message.payloadJson)
            if (thought != null) {
                var expanded by remember(message.id) { mutableStateOf(false) }
                ThoughtBlock(thought, expanded) { expanded = !expanded }
            }
            MessageBubble(ChatPayloads.readText(message.payloadJson), mine = mine, markdown = !mine && mode == SessionMode.FREE)
        }
```
- 상단 바 토글 아이콘 설명 `when`에 FREE는 해당 없음(`hasPanel`이 false라 안 보임).

- [ ] **Step 5: 빌드**

Run: 전체 명령 → BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/csh/blogwriter/ui/chat/components/ThoughtBlock.kt app/src/main/java/com/csh/blogwriter/ui/chat/components/MessageBubble.kt app/src/main/java/com/csh/blogwriter/ui/chat/components/Composer.kt app/src/main/java/com/csh/blogwriter/ui/chat/components/SessionListPane.kt app/src/main/java/com/csh/blogwriter/ui/chat/ChatScreen.kt
git commit -m "feat(자유·생각): 생각 요약 블록(연하게·탭으로 접기), 자유 모드 칩·히어로·사진판·마크다운 말풍선·세션 표시"
```

---

### Task 5: 설정 화면 "업데이트 확인" 버튼 (수동 확인)

**Files:**
- Modify: `app/src/main/java/com/csh/blogwriter/ui/admin/SettingsScreen.kt` (`SettingsUiState`, `SettingsViewModel`, 화면)
- Test: `app/src/test/java/com/csh/blogwriter/ui/admin/SettingsViewModelTest.kt`(신규)

**Interfaces:**
- Consumes: 기존 `UpdateChecker.checkForUpdate(): UpdateInfo?`(`update/UpdateChecker.kt`, `UpdateInfo(tag, htmlUrl)`), `SettingsStore.setLastUpdateCheckAt/setDismissedUpdateTag`, `BuildConfig.VERSION_NAME`.
- Produces:
  ```kotlin
  sealed interface UpdateCheckState { data object Idle : UpdateCheckState; data object Checking : UpdateCheckState; data class Available(val info: UpdateInfo) : UpdateCheckState; data class UpToDate(val version: String) : UpdateCheckState; data object Failed : UpdateCheckState }
  data class SettingsUiState(..., val updateCheck: UpdateCheckState = UpdateCheckState.Idle)
  class SettingsViewModel(settings, keyStore, naverSession, updateChecker: UpdateChecker) { fun checkForUpdate() }
  ```

- [ ] **Step 1: 실패 테스트**

`ui/admin/SettingsViewModelTest.kt`(`ChatViewModelTest`의 `FakeSettingsStore`/`FakeApiKeyStore`/`FakeUpdateChecker`와 같은 모양의 가짜를 이 파일 안에 최소로 둔다; `NaverSession`은 생성자만 맞추는 가짜 — 클래스면 `mockk` 없이 상속 가능한지 확인하고, 안 되면 `NaverSession` 대신 `() -> Unit` 로그아웃 람다로 바꾸지 말고 인터페이스 추출 없이 실제 인스턴스를 만들 수 있는지 본다. 만들 수 없으면 `SettingsViewModel`의 로그아웃 의존을 `Provider<NaverSession>` 로 늦추지 말고, 테스트에서는 `Robolectric` 로 실제 `NaverSession(context, settings)` 을 만든다 — 파일 상단에 `@RunWith(RobolectricTestRunner::class)`):
```kotlin
    @Test fun checkFindsNewerVersionAndArmsChatBanner() = runTest {
        settings.dismissedTag.value = "v9.9.9"; settings.lastCheckAt.value = 123L
        val vm = newViewModel(checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com")))
        vm.checkForUpdate(); advanceUntilIdle()
        assertEquals(UpdateCheckState.Available(UpdateInfo("v9.9.9", "https://example.com")), vm.uiState.value.updateCheck)
        assertNull(settings.dismissedTag.value)   // 닫아 둔 태그를 풀어 채팅 배너가 다시 뜨게
        assertEquals(0L, settings.lastCheckAt.value)  // 채팅으로 돌아가면 바로 다시 확인
    }
    @Test fun checkReportsUpToDate() = runTest {
        val vm = newViewModel(checker = FakeUpdateChecker(null))
        vm.checkForUpdate(); advanceUntilIdle()
        assertEquals(UpdateCheckState.UpToDate(BuildConfig.VERSION_NAME), vm.uiState.value.updateCheck)
    }
    @Test fun checkFailureIsReported() = runTest {
        val vm = newViewModel(checker = object : UpdateChecker { override suspend fun checkForUpdate(repo: String, currentVersion: String): UpdateInfo? = throw java.io.IOException("offline") })
        vm.checkForUpdate(); advanceUntilIdle()
        assertEquals(UpdateCheckState.Failed, vm.uiState.value.updateCheck)
    }
```
(`GithubUpdateChecker`는 실패를 null로 삼키지만 인터페이스 계약상 throw 도 가능하므로 뷰모델이 잡는다.)

- [ ] **Step 2: 구현**

`SettingsViewModel`: 생성자에 `private val updateChecker: UpdateChecker` 추가(Hilt 바인딩은 `NetworkModule.provideUpdateChecker`에 이미 있음). `uiState`는 기존 `combine(...)`에 `MutableStateFlow<UpdateCheckState>(Idle)`를 하나 더 합친다.
```kotlin
    fun checkForUpdate() = viewModelScope.launch {
        _updateCheck.value = UpdateCheckState.Checking
        val info = try { updateChecker.checkForUpdate() } catch (e: CancellationException) { throw e } catch (e: Exception) { _updateCheck.value = UpdateCheckState.Failed; return@launch }
        if (info == null) { _updateCheck.value = UpdateCheckState.UpToDate(BuildConfig.VERSION_NAME); return@launch }
        // 수동으로 찾은 새 버전은 채팅 배너로도 보이게 — 닫아 둔 태그를 풀고 10분 간격도 무시한다.
        settings.setDismissedUpdateTag(null); settings.setLastUpdateCheckAt(0L)
        _updateCheck.value = UpdateCheckState.Available(info)
    }
```
화면: "실패 로그" 줄 아래에 `ListRow(title = "업데이트 확인", subtitle = "지금 버전 ${BuildConfig.VERSION_NAME}", onClick = viewModel::checkForUpdate, trailingChevron = false)`; 그 아래 상태에 따라:
- `Checking` → `InlineBanner("새 버전이 있는지 확인하고 있어요", BannerKind.Info)`
- `Available(info)` → `InlineBanner("새 버전(${info.tag})이 나왔어요 — 받으러 가기", BannerKind.Success) { context.startActivity(Intent(Intent.ACTION_VIEW, info.htmlUrl.toUri())) }` (채팅 배너와 같은 문구·동작)
- `UpToDate(v)` → `InlineBanner("최신 버전이에요 ($v)", BannerKind.Info)`
- `Failed` → `InlineBanner("확인하지 못했어요. 인터넷 연결을 확인해 주세요.", BannerKind.Warning)`
- `Idle` → 없음. 배너는 `Spacer(AppSpacing.sm)`로 줄과 띄운다.

- [ ] **Step 3: 테스트 통과·빌드·커밋**

Run: 전체 명령 → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/com/csh/blogwriter/ui/admin/SettingsScreen.kt app/src/test/java/com/csh/blogwriter/ui/admin/SettingsViewModelTest.kt
git commit -m "feat(설정): 업데이트 확인 버튼 — 수동 확인 결과(새 버전/최신/실패)를 바로 보여 주고 채팅 배너도 다시 켠다"
```

---

### Task 6: 통합 검증·설치·점검 (오케스트레이터)

- [ ] 전체 테스트 + assembleDebug 녹색. Tab35 설치.
- [ ] 설정 › 업데이트 확인: 최신이면 "최신 버전이에요", 새 버전이면 배너.
- [ ] 자유 세션: 칩 → 🤖 자유 → 히어로 → 질문 전송 → 생각 블록이 연하게 자라다 답이 오면 접힘 → 탭으로 펴짐 → 마크다운 렌더 → 세션 목록 "자유". 사진 첨부 버튼 보임. "이거 기억해 줘" → `remember` 저장(설정 › 기억한 것들).
- [ ] 글쓰기 세션 한 턴: 생각 블록이 뜨고 계획 흐름은 그대로. 재열기 시 저장된 생각이 접힌 채 보임.
- [ ] `git push origin main`; 스펙 어긋난 곳 한 줄 맞춤.
