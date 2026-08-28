package com.csh.blogwriter.chat

import kotlinx.serialization.json.JsonObject

interface ToolExecutor {
    /** 도구를 실행하고 결과 JSON을 돌려준다. 진행 문구는 onProgress 로 UI 에 전달. 절대 throw 하지 않는다(오류도 JSON 으로). */
    suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit): JsonObject
}
