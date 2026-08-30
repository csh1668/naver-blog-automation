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
}
