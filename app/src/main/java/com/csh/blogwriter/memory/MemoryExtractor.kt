package com.csh.blogwriter.memory

import com.csh.blogwriter.chat.PublishedHook
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.ChatRepository
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GContent
import com.csh.blogwriter.llm.GGenerationConfig
import com.csh.blogwriter.llm.GPart
import com.csh.blogwriter.llm.GRequest
import com.csh.blogwriter.llm.GSystemInstruction
import com.csh.blogwriter.llm.GeminiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * 발행이 끝난 대화를 통째로 넣어 다음 글에 참고할 기억(0~3개)을 뽑는다.
 * [ChatViewModel.onPublished] 는 자신의 viewModelScope 안에서 이 훅을 부르므로,
 * 화면을 떠나도 추출이 끊기지 않도록 실제 작업은 앱 스코프([scope])에서 실행하고 이 함수는 곧바로 반환한다.
 * 실패는 사용자에게 보이지 않게 조용히 삼킨다.
 */
class MemoryExtractor @Inject constructor(
    private val chatRepo: ChatRepository,
    private val client: GeminiClient,
    private val keyStore: ApiKeyStore,
    private val settings: SettingsStore,
    private val memory: MemoryRepository,
    private val scope: CoroutineScope,
) : PublishedHook {

    companion object {
        private const val INSTRUCTION = "대화 전체를 보고, 다음에 글을 쓸 때 참고하면 좋을 사용자의 취향·습관·자주 쓰는 표현·사실을 0~3개 뽑아 주세요. " +
            "이미 알 법한 당연한 내용이나 이번 글에만 해당하는 세부 사항은 빼 주세요. 각 항목은 한 문장으로, kind 는 " +
            "STYLE(문체 취향)/PREFERENCE(선호)/FACT(사실)/EXPRESSION(자주 쓰는 표현) 중 하나로 골라 주세요. 기억할 게 없으면 빈 배열을 주세요."

        private val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("memories") {
                    put("type", "array")
                    put("maxItems", 3)
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("kind") { put("type", "string"); putJsonArray("enum") { add("STYLE"); add("PREFERENCE"); add("FACT"); add("EXPRESSION") } }
                            putJsonObject("text") { put("type", "string") }
                        }
                        putJsonArray("required") { add("kind"); add("text") }
                    }
                }
            }
            putJsonArray("required") { add("memories") }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun onPublished(sessionId: String, url: String) {
        scope.launch {
            try {
                extract(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 발행 후 부가 작업일 뿐이라 실패해도 사용자에게 알리지 않는다.
            }
        }
    }

    private suspend fun extract(sessionId: String) {
        val key = keyStore.keysOnce().firstOrNull { it.usable } ?: return
        val conversation = buildConversationText(chatRepo.messagesOnce(sessionId))
        if (conversation.isEmpty()) return
        val models = settings.modelPolicyOnce().models
        val model = models.getOrNull(1) ?: models.firstOrNull() ?: return

        val request = GRequest(
            contents = listOf(GContent("user", listOf(GPart(text = conversation)))),
            systemInstruction = GSystemInstruction(listOf(GPart(text = INSTRUCTION))),
            generationConfig = GGenerationConfig(responseMimeType = "application/json", responseJsonSchema = SCHEMA),
        )
        val text = client.generate(key.secret, model, request).text ?: return
        val extracted = parseItems(text).take(3)
        if (extracted.isEmpty()) return

        val existing = memory.activeItems().map { it.text }.toSet()
        val added = extracted.filter { it.second !in existing }.map { (kind, text) -> memory.add(kind, text, "publish") }
        if (added.isEmpty()) return
        val summary = "이런 점을 기억해 둘게요: " + added.joinToString(", ") { it.text }
        chatRepo.appendMessage(sessionId, MessageRole.SYSTEM, MessageKind.SYSTEM, systemPayload(summary))
    }

    /** [com.csh.blogwriter.ui.chat.ChatPayloads.text] 와 같은 인코딩(`{"text": …}`)을 여기서도 그대로 맞춘다. */
    private fun systemPayload(text: String): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject { put("text", text) })

    private fun readText(payload: String): String =
        runCatching { json.parseToJsonElement(payload).jsonObject["text"]!!.jsonPrimitive.content }.getOrDefault(payload)

    private fun buildConversationText(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        messages.forEach { m ->
            if (m.role == MessageRole.SYSTEM || m.kind != MessageKind.TEXT) return@forEach
            val speaker = if (m.role == MessageRole.USER) "사용자" else "어시스턴트"
            sb.append(speaker).append(": ").append(readText(m.payloadJson)).append('\n')
        }
        return sb.toString().trim()
    }

    private fun parseItems(text: String): List<Pair<MemoryKind, String>> = runCatching {
        val items = json.parseToJsonElement(text).jsonObject["memories"]?.jsonArray ?: return emptyList()
        items.mapNotNull { el ->
            val obj = el.jsonObject
            val kind = obj["kind"]?.jsonPrimitive?.content?.let { s -> runCatching { MemoryKind.valueOf(s) }.getOrNull() } ?: return@mapNotNull null
            val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (text.isEmpty()) null else kind to text
        }
    }.getOrDefault(emptyList())
}
