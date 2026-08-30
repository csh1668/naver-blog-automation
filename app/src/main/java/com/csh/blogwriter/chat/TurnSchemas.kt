package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.SessionMode
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
            add(buildJsonObject { put("type", "object"); putJsonObject("properties") {
                put("type", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("table") } })
                put("rows", buildJsonObject { put("type", "array"); put("description", "2열 표. 각 행은 [항목명, 값]"); put("items", arrayOf(str())) })
            }; putJsonArray("required") { add("type"); add("rows") } })
            add(buildJsonObject { put("type", "object"); putJsonObject("properties") {
                put("type", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("imageGroup") } })
                put("refs", buildJsonObject { put("type", "array"); put("description", "묶을 사진 ref 2~4개(각 사진은 글 전체에서 한 번만)"); put("items", str()) })
                put("layout", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("COLLAGE"); add("SLIDE") } })
            }; putJsonArray("required") { add("type"); add("refs") } })
        }
    }
    val postContent: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { put("title", str()); put("blocks", arrayOf(block)) }
        putJsonArray("required") { add("title"); add("blocks") }
    }

    private fun sayOnly(desc: String) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { put("say", str(desc)) }
        putJsonArray("required") { add("say") }
    }

    fun turnResponseJsonSchema(mode: SessionMode = SessionMode.WRITE): JsonObject = when (mode) {
        SessionMode.WRITE -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                put("say", str("말풍선 본문, 2~4문장"))
                put("plan", nullable(str("글 계획 전문(마크다운). 고칠 때도 전체를 다시 낸다")))
                put("question", nullable(str("한 턴에 질문 하나")))
                put("quickReplies", arrayOf(str())); put("readyToDraft", buildJsonObject { put("type", "boolean") }); put("post", nullable(postContent))
            }
            putJsonArray("required") { add("say"); add("quickReplies"); add("readyToDraft") }
        }
        SessionMode.ADVICE -> sayOnly("조언 본문. 마크다운 없이 줄바꿈만, 800자 안팎")
        SessionMode.FREE -> sayOnly("답 본문. 제목·목록·굵게 같은 간단한 마크다운을 써도 된다")
    }

    private val writeTools = listOf(
        GFunctionDeclaration("web_search", "네이버(실패 시 구글)에서 검색해 결과 목록(results)과 결과 페이지 요약(pageSummary: 플레이스 카드의 영업시간·주소·전화·가격 등)을 돌려준다. 영업시간·주소·가격·행사 날짜처럼 사실 확인이 필요할 때 쓴다. pageSummary 에 답이 있으면 open_page 는 필요 없다.",
            buildJsonObject { put("type", "object"); putJsonObject("properties") { put("query", str("검색어")) }; putJsonArray("required") { add("query") } }),
        GFunctionDeclaration("open_page", "웹 페이지를 열어 본문 텍스트(최대 4000자)를 돌려준다. web_search 결과의 url만 연다.",
            buildJsonObject { put("type", "object"); putJsonObject("properties") { put("url", str("http(s) 주소")) }; putJsonArray("required") { add("url") } }),
        rememberTool("사용자의 취향·습관·자주 쓰는 표현·사실을 저장한다. 저장 후 say에 '기억해 둘게요: …' 로 알린다."),
    )

    // 자유 모드 프롬프트는 "기억할까요?" 를 먼저 묻게 돼 있다 — 도구 설명도 그 순서에 맞춘다.
    private val freeTools = writeTools.filterNot { it.name == "remember" } +
        rememberTool("사용자의 취향·습관·자주 쓰는 표현·사실을 저장한다. 사용자가 기억해 달라고 했거나 '기억할까요?' 제안에 동의한 뒤에만 부른다. 저장 후 say에 '기억해 둘게요: …' 로 알린다.")

    private fun rememberTool(description: String) = GFunctionDeclaration("remember", description,
        buildJsonObject { put("type", "object"); putJsonObject("properties") {
            put("kind", buildJsonObject { put("type", "string"); putJsonArray("enum") { add("STYLE"); add("PREFERENCE"); add("FACT"); add("EXPRESSION") } }); put("text", str("한 문장"))
        }; putJsonArray("required") { add("kind"); add("text") } })

    fun functionDeclarations(mode: SessionMode = SessionMode.WRITE): List<GFunctionDeclaration> = when (mode) {
        SessionMode.WRITE -> writeTools
        SessionMode.FREE -> freeTools
        SessionMode.ADVICE -> listOf(
            GFunctionDeclaration("list_my_posts", "사용자 블로그의 최근 글 30개(logNo·제목·날짜·댓글·공감·사진 수·요약)를 돌려준다. 시스템 프롬프트의 목록이 없거나 오래됐을 때만 부른다.",
                buildJsonObject { put("type", "object"); putJsonObject("properties") {} }),
            GFunctionDeclaration("read_my_post", "logNo 의 글 본문(문단·인용·표 텍스트, 사진·동영상 개수)을 돌려준다. 조언하기 전에 반드시 읽는다. 한 번에 최대 3편.",
                buildJsonObject { put("type", "object"); putJsonObject("properties") { put("logNo", str("최근 글 목록의 logNo")) }; putJsonArray("required") { add("logNo") } }),
        )
    }
}
