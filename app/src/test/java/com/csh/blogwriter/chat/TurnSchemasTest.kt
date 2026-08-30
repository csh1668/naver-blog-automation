package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.SessionMode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    @Test fun freeSchemaAndTools() {
        val s = TurnSchemas.turnResponseJsonSchema(SessionMode.FREE)
        assertEquals(setOf("say"), s["properties"]!!.jsonObject.keys)
        assertEquals(listOf("web_search", "open_page", "remember"), TurnSchemas.functionDeclarations(SessionMode.FREE).map { it.name })
    }
    @Test fun sayDescriptionDiffersByModeAndAdviceIsUnchanged() {
        val advice = TurnSchemas.turnResponseJsonSchema(SessionMode.ADVICE)
        val free = TurnSchemas.turnResponseJsonSchema(SessionMode.FREE)
        assertEquals("조언 본문. 마크다운 없이 줄바꿈만, 800자 안팎", advice["properties"]!!.jsonObject["say"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertTrue(free["properties"]!!.jsonObject["say"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("마크다운"))
    }
    @Test fun rememberDescriptionAsksFirstOnlyInFreeMode() {
        val free = TurnSchemas.functionDeclarations(SessionMode.FREE).single { it.name == "remember" }.description
        val write = TurnSchemas.functionDeclarations().single { it.name == "remember" }.description
        assertTrue(free.contains("동의한 뒤"))
        assertTrue(!write.contains("동의한 뒤"))
    }
}
