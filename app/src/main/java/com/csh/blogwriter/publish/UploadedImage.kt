package com.csh.blogwriter.publish

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** 에디터 이미지 업로드 응답 (spike/findings.md §4). path 로는 반드시 url(파일명 포함)을 쓴다. */
data class UploadedImage(
    val ref: String,
    val url: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val fileSize: Long,
    val domain: String,
) {
    companion object {
        fun fromResponse(ref: String, response: JsonObject): UploadedImage = UploadedImage(
            ref = ref,
            url = response["url"]!!.jsonPrimitive.content,
            fileName = response["fileName"]?.jsonPrimitive?.content ?: response["url"]!!.jsonPrimitive.content.substringAfterLast('/'),
            width = response["width"]!!.jsonPrimitive.int,
            height = response["height"]!!.jsonPrimitive.int,
            fileSize = response["fileSize"]?.jsonPrimitive?.content?.toLong() ?: 0L,
            domain = response["domain"]?.jsonPrimitive?.content ?: "https://blogfiles.pstatic.net",
        )
    }
}
