package com.csh.blogwriter.ui.chat

import com.csh.blogwriter.chat.AttachedPhoto
import com.csh.blogwriter.chat.Plan
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.ChatSession
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.PostContentJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ChatUiState(
    val session: ChatSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val attachments: List<AttachedPhoto> = emptyList(),
    val thinking: Boolean = false,
    val streamingSay: String? = null,
    val toolStatus: String? = null,
    val error: String? = null,
    val quickReplies: List<String> = emptyList(),
    val panelJobId: String? = null,
    val panelOpen: Boolean = false,
    val listCollapsed: Boolean = false,
    val hasKey: Boolean = true,
    /** [attachments] 중 여기부터가 "아직 안 보낸" 사진 — 입력창 위 사진판에 보여 주고 보내면 비운다. */
    val trayFrom: Int = 0,
) {
    /** 입력창 위 사진판에 걸어 둘 사진들. */
    val tray: List<AttachedPhoto> get() = attachments.drop(trayFrom.coerceIn(0, attachments.size))
}

/** 사진 첨부 메시지의 내용. */
data class PhotosPayload(val count: Int, val refs: List<String>, val uris: List<String>)

/**
 * 메시지 payloadJson 의 인코딩. 대화 기록을 프롬프트로 옮기는
 * `ConversationEngine.buildContents` 가 읽는 모양과 같아야 한다.
 */
object ChatPayloads {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val strings = ListSerializer(String.serializer())

    fun text(value: String): String = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("text", value) })

    fun readText(payload: String): String =
        runCatching { json.parseToJsonElement(payload).jsonObject["text"]!!.jsonPrimitive.content }.getOrDefault(payload)

    fun photos(photos: List<AttachedPhoto>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("count", photos.size)
            put("refs", json.encodeToJsonElement(strings, photos.map { it.ref }))
            put("uris", json.encodeToJsonElement(strings, photos.map { it.uri }))
        },
    )

    fun readPhotos(payload: String): PhotosPayload? = runCatching {
        val obj = json.parseToJsonElement(payload).jsonObject
        val refs = json.decodeFromJsonElement(strings, obj["refs"]!!)
        val uris = json.decodeFromJsonElement(strings, obj["uris"]!!)
        PhotosPayload(obj["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: refs.size, refs, uris)
    }.getOrNull()

    fun plan(plan: Plan): String = json.encodeToString(Plan.serializer(), plan)
    fun readPlan(payload: String): Plan? = runCatching { json.decodeFromString(Plan.serializer(), payload) }.getOrNull()

    fun post(content: PostContent): String = PostContentJson.encode(content)
    fun readPost(payload: String): PostContent? = runCatching { PostContentJson.decode(payload) }.getOrNull()
}
