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
    fun decodesFullTurnWithPlanMarkdown() {
        val text = """{"say":"오른쪽에 계획을 정리했어요.","plan":"# 원주 한우 후기\n다른 제목: A / B\n\n## 글 구성\n1. 도입 — 왜 갔는지 (사진 img_001)","question":"어디를 고칠까요?","quickReplies":["더 짧게","사진 순서 바꿔 줘"],"readyToDraft":true,"post":null}"""
        val t = TurnResponseJson.decode(text)
        val plan = t.plan!!
        assertTrue(plan.startsWith("# 원주 한우 후기"))
        assertTrue(plan.contains("1. 도입 — 왜 갔는지 (사진 img_001)"))
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
        // 계획은 마크다운 문자열 하나다 (예전의 제목 후보·개요 객체가 아니다).
        val plan = props["plan"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals("string", plan[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("null", plan[1].jsonObject["type"]!!.jsonPrimitive.content)
        val blocks = props["post"]!!.jsonObject["anyOf"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject["blocks"]!!.jsonObject
        assertEquals("array", blocks["type"]!!.jsonPrimitive.content)
        assertEquals(3, TurnSchemas.functionDeclarations().size)
        assertEquals(listOf("web_search", "open_page", "remember"), TurnSchemas.functionDeclarations().map { it.name })
    }
}
