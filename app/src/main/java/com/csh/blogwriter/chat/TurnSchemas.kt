package com.csh.blogwriter.chat

import com.csh.blogwriter.llm.GFunctionDeclaration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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
