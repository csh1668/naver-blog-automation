package com.csh.blogwriter.chat

/**
 * 발행이 끝난 직후 대화에서 배울 것을 챙기는 자리. 구현은 `MemoryExtractor`.
 */
interface PublishedHook {
    suspend fun onPublished(sessionId: String, url: String)
}
