package com.csh.blogwriter.publish

import com.csh.blogwriter.domain.model.Align
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.FontSize
import com.csh.blogwriter.domain.model.ListType
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentModelConverterTest {
    private var counter = 0
    private val converter = DocumentModelConverter(idGenerator = { "SE-${++counter}" })
    private val image = UploadedImage(
        ref = "img_001", url = "/MjAy/abc.PNG/img_001.jpg", fileName = "img_001.jpg",
        width = 1600, height = 1200, fileSize = 12345, domain = "https://blogfiles.pstatic.net",
    )

    private fun convert(content: PostContent, images: Map<String, UploadedImage> = mapOf("img_001" to image)) =
        converter.convert(content, images, documentId = "DOC1", version = "2.10.2")

    private fun components(doc: JsonObject) = doc["document"]!!.jsonObject["components"]!!.jsonArray

    @Test
    fun envelopeAndTitle() {
        val doc = convert(PostContent("제목", emptyList()), emptyMap())
        val document = doc["document"]!!.jsonObject
        assertEquals("2.10.2", document["version"]!!.jsonPrimitive.content)
        assertEquals("DOC1", document["id"]!!.jsonPrimitive.content)
        assertEquals("", doc["documentId"]!!.jsonPrimitive.content)
        val title = components(doc)[0].jsonObject
        assertEquals("documentTitle", title["@ctype"]!!.jsonPrimitive.content)
        val node = title["title"]!!.jsonArray[0].jsonObject["nodes"]!!.jsonArray[0].jsonObject
        assertEquals("제목", node["value"]!!.jsonPrimitive.content)
        assertEquals("textNode", node["@ctype"]!!.jsonPrimitive.content)
    }

    @Test
    fun paragraphRunStylesAndParagraphStyle() {
        val content = PostContent("t", listOf(
            Block.Paragraph(listOf(Run("굵게", bold = true, color = "#ff0010", background = "#ffd300", size = FontSize.TITLE), Run("보통")),
                align = Align.CENTER, list = ListType.BULLET),
        ))
        val text = components(convert(content))[1].jsonObject
        assertEquals("text", text["@ctype"]!!.jsonPrimitive.content)
        val paragraph = text["value"]!!.jsonArray[0].jsonObject
        val pStyle = paragraph["style"]!!.jsonObject
        assertEquals("paragraphStyle", pStyle["@ctype"]!!.jsonPrimitive.content)
        assertEquals("center", pStyle["align"]!!.jsonPrimitive.content)
        assertEquals("bullet", pStyle["list"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1.7, pStyle["lineHeight"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val bold = paragraph["nodes"]!!.jsonArray[0].jsonObject["style"]!!.jsonObject
        assertTrue(bold["bold"]!!.jsonPrimitive.boolean)
        assertEquals("#ff0010", bold["fontColor"]!!.jsonPrimitive.content)
        assertEquals("#ffd300", bold["backgroundColor"]!!.jsonPrimitive.content)
        assertEquals("fs28", bold["fontSizeCode"]!!.jsonPrimitive.content)
        assertEquals("nanumsquare", bold["fontFamily"]!!.jsonPrimitive.content)
        val plain = paragraph["nodes"]!!.jsonArray[1].jsonObject["style"]!!.jsonObject
        assertNull(plain["bold"]); assertNull(plain["fontColor"])
        assertEquals("fs19", plain["fontSizeCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun consecutiveParagraphsShareOneTextComponent() {
        val content = PostContent("t", listOf(
            Block.Paragraph(listOf(Run("a"))), Block.Paragraph(listOf(Run("b"))),
            Block.Image("img_001"),
            Block.Paragraph(listOf(Run("c"))),
        ))
        val comps = components(convert(content))
        assertEquals(listOf("documentTitle", "text", "image", "text"), comps.map { it.jsonObject["@ctype"]!!.jsonPrimitive.content })
        assertEquals(2, comps[1].jsonObject["value"]!!.jsonArray.size)
        assertEquals(4, DocumentModelConverter.expectedComponentCount(content))
    }

    @Test
    fun imageMappingUsesUploadUrlAsPath() {
        val img = components(convert(PostContent("t", listOf(Block.Image("img_001")))))[1].jsonObject
        assertEquals("image", img["@ctype"]!!.jsonPrimitive.content)
        assertEquals("/MjAy/abc.PNG/img_001.jpg", img["path"]!!.jsonPrimitive.content)
        assertEquals("https://blogfiles.pstatic.net/MjAy/abc.PNG/img_001.jpg?type=w1", img["src"]!!.jsonPrimitive.content)
        assertEquals("https://blogfiles.pstatic.net", img["domain"]!!.jsonPrimitive.content)
        assertEquals(693, img["width"]!!.jsonPrimitive.int)
        assertEquals(520, img["height"]!!.jsonPrimitive.int)
        assertEquals(1600, img["originalWidth"]!!.jsonPrimitive.int)
        assertEquals(1200, img["originalHeight"]!!.jsonPrimitive.int)
        assertEquals(12345, img["fileSize"]!!.jsonPrimitive.int)
        assertEquals("img_001.jpg", img["fileName"]!!.jsonPrimitive.content)
        assertTrue(img["represent"]!!.jsonPrimitive.boolean)
        assertTrue(img["internalResource"]!!.jsonPrimitive.boolean)
        assertEquals("fit", img["contentMode"]!!.jsonPrimitive.content)
        assertEquals("local", img["origin"]!!.jsonObject["srcFrom"]!!.jsonPrimitive.content)
    }

    @Test
    fun onlyFirstImageIsRepresentative() {
        val second = image.copy(ref = "img_002", url = "/x/img_002.jpg", fileName = "img_002.jpg")
        val comps = components(convert(PostContent("t", listOf(Block.Image("img_001"), Block.Image("img_002"))),
            mapOf("img_001" to image, "img_002" to second)))
        assertTrue(comps[1].jsonObject["represent"]!!.jsonPrimitive.boolean)
        assertFalse(comps[2].jsonObject["represent"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun quoteWithAndWithoutSource() {
        val comps = components(convert(PostContent("t", listOf(Block.Quote("인용", "출처"), Block.Quote("없음")))))
        val withSource = comps[1].jsonObject
        assertEquals("quotation", withSource["@ctype"]!!.jsonPrimitive.content)
        assertEquals("인용", withSource["value"]!!.jsonArray[0].jsonObject["nodes"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("출처", withSource["source"]!!.jsonArray[0].jsonObject["nodes"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content)
        assertNull(comps[2].jsonObject["source"])
    }

    @Test
    fun missingUploadedImageThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            convert(PostContent("t", listOf(Block.Image("img_009"))))
        }
    }

    @Test
    fun uploadedImageParsesEditorResponse() {
        val response = Json.parseToJsonElement("""{"url":"/a/b.PNG/x.jpg","path":"/a/b.PNG","fileName":"x.jpg","width":800,"height":600,"fileSize":21096,"domain":"https://blogfiles.pstatic.net"}""").jsonObject
        val parsed = UploadedImage.fromResponse("img_001", response)
        assertEquals(UploadedImage("img_001", "/a/b.PNG/x.jpg", "x.jpg", 800, 600, 21096, "https://blogfiles.pstatic.net"), parsed)
    }
}
