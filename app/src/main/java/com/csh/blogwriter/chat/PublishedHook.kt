package com.csh.blogwriter.chat

import javax.inject.Inject

/**
 * 발행이 끝난 직후 대화에서 배울 것을 챙기는 자리.
 * 지금은 아무것도 하지 않고, 메모리 추출(SP2 Task 12)이 여기에 붙는다.
 */
interface PublishedHook {
    suspend fun onPublished(sessionId: String, url: String)
}

class NoOpPublishedHook @Inject constructor() : PublishedHook {
    override suspend fun onPublished(sessionId: String, url: String) = Unit
}
