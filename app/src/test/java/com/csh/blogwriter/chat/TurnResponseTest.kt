package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.Block
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnResponseTest {
    @Test
    fun decodesFullTurnWithPlan() {
        val text = """{"say":"이렇게 써 볼까요?","plan":{"titleCandidates":["a","b","c"],"outline":[{"heading":"도입","summary":"왜 갔는지","photoRefs":["img_001"]}],"tone":"따뜻한 존댓말"},"question":"제목은요?","quickReplies":["1번","더 짧게"],"readyToDraft":false,"post":null}"""
        val t = TurnResponseJson.decode(text)
        assertEquals(3, t.plan!!.titleCandidates.size)
        assertEquals(listOf("img_001"), t.plan!!.outline[0].photoRefs)
        assertNull(t.post)
    }

    @Test
    fun decodesDraftTurnWithPostAndToleratesFencesAndMissingFields() {
        val text = "```json\n{\"say\":\"초안이에요\",\"readyToDraft\":true,\"post\":{\"title\":\"제목\",\"blocks\":[{\"type\":\"paragraph\",\"runs\":[{\"text\":\"본문\"}]},{\"type\":\"image\",\"ref\":\"img_001\"}]}}\n```"
        val t = TurnResponseJson.decode(text)
        assertEquals("제목", t.post!!.title)
        assertTrue(t.post!!.blocks[1] is Block.Image)
        assertEquals(emptyList<String>(), t.quickReplies)
    }

    @Test
    fun schemaDescribesTopLevelFieldsAndPost() {
        val schema = TurnSchemas.turnResponseJsonSchema()
        val props = schema["properties"]!!.jsonObject
        assertEquals(setOf("say", "plan", "question", "quickReplies", "readyToDraft", "post"), props.keys)
        assertEquals("say", schema["required"]!!.jsonArray[0].jsonPrimitive.content)
        val blocks = props["post"]!!.jsonObject["anyOf"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject["blocks"]!!.jsonObject
        assertEquals("array", blocks["type"]!!.jsonPrimitive.content)
        assertEquals(3, TurnSchemas.functionDeclarations().size)
        assertEquals(listOf("web_search", "open_page", "remember"), TurnSchemas.functionDeclarations().map { it.name })
    }
}
