package com.csh.blogwriter.chat

import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.research.ResearchTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/** 턴마다 새로 만든다(횟수 제한은 턴 단위). */
class DefaultToolExecutor @Inject constructor(
    private val research: ResearchTool,
    private val memory: MemoryRepository,
    private val settings: SettingsStore,
) : ToolExecutor {
    private val counts = HashMap<String, Int>()
    private val limits = mapOf("web_search" to 2, "open_page" to 2, "remember" to 2)

    /** 이번 턴 web_search 결과로 나온 url만 open_page 로 열 수 있다. */
    private val allowedUrls = mutableSetOf<String>()

    override suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit): JsonObject {
        if ((name == "web_search" || name == "open_page") && !settings.researchEnabled.first()) {
            return buildJsonObject { put("error", "disabled") }
        }
        val used = counts.getOrDefault(name, 0)
        val limit = limits[name] ?: return buildJsonObject { put("error", "unknown tool") }
        if (used >= limit) return buildJsonObject { put("error", "limit") }
        counts[name] = used + 1
        return try {
            when (name) {
                "web_search" -> {
                    val q = args["query"]?.jsonPrimitive?.content.orEmpty()
                    onProgress("네이버에서 '$q' 정보를 찾고 있어요…")
                    val hits = research.search(q)
                    allowedUrls += hits.map { normalizeUrl(it.url) }
                    buildJsonObject { put("results", buildJsonArray { hits.forEach { h -> add(buildJsonObject { put("title", h.title); put("url", h.url); put("snippet", h.snippet) }) } }) }
                }
                "open_page" -> {
                    val url = args["url"]?.jsonPrimitive?.content.orEmpty()
                    if (normalizeUrl(url) !in allowedUrls) {
                        buildJsonObject { put("error", "not_allowed") }
                    } else {
                        onProgress("'${runCatching { java.net.URI(url).host }.getOrNull() ?: url}' 페이지를 읽고 있어요…")
                        val page = research.openPage(url)
                        if (page == null) buildJsonObject { put("error", "페이지를 열 수 없음") } else buildJsonObject { put("title", page.title); put("text", page.text) }
                    }
                }
                "remember" -> {
                    onProgress("기억해 둘게요…")
                    val kind = runCatching { MemoryKind.valueOf(args["kind"]!!.jsonPrimitive.content) }.getOrDefault(MemoryKind.PREFERENCE)
                    val item = memory.add(kind, args["text"]?.jsonPrimitive?.content.orEmpty(), "chat")
                    buildJsonObject { put("saved", true); put("id", item.id) }
                }
                else -> buildJsonObject { put("error", "unknown tool") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { buildJsonObject { put("error", e.message ?: "실패") } }
    }
}

/** 스킴/호스트는 소문자로, fragment 는 버리고, path 끝의 슬래시 하나는 지운다. query 는 그대로 둔다. */
internal fun normalizeUrl(u: String): String {
    val noFragment = u.substringBefore("#")
    val uri = runCatching { java.net.URI(noFragment) }.getOrNull() ?: return noFragment
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase().orEmpty()
    val port = if (uri.port != -1) ":${uri.port}" else ""
    val path = uri.rawPath.orEmpty().let { if (it.endsWith("/")) it.dropLast(1) else it }
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "$scheme://$host$port$path$query"
}
