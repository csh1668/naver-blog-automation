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
/** 턴마다 생각 예산을 조절한다 — 초안·수정은 "high", 질문·계획·피드백은 "low". */
@Serializable data class GThinkingConfig(val thinkingLevel: String)
@Serializable data class GGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null,
    val responseJsonSchema: JsonObject? = null,
    val thinkingConfig: GThinkingConfig? = null,
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

@Serializable data class GModelInfo(val name: String, val supportedGenerationMethods: List<String> = emptyList())
@Serializable data class GModelsListResponse(val models: List<GModelInfo> = emptyList(), val nextPageToken: String? = null)
