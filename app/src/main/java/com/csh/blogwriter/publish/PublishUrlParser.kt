package com.csh.blogwriter.publish

import java.net.URI
import java.net.URLDecoder

data class PublishedPost(val blogId: String, val logNo: String) {
    val url: String get() = "https://blog.naver.com/$blogId/$logNo"
}

object PublishUrlParser {
    fun isLoginPage(url: String): Boolean = url.startsWith("https://nid.naver.com/")

    fun parsePublished(url: String): PublishedPost? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host != "blog.naver.com") return null
        if (uri.path != "/PostView.naver") return parsePermalink(uri.path)
        val query = queryMap(uri.rawQuery ?: return null)
        if (query["isAfterWrite"] != "true") return null
        val blogId = query["blogId"]?.takeIf { it.isNotBlank() } ?: return null
        val logNo = query["logNo"]?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return null
        return PublishedPost(blogId, logNo)
    }

    /**
     * 네이버 글쓰기 화면인가. `/{blogId}/{logNo}` 는 플랫폼의 모든 글이 가지는 주소라서,
     * 발행 판정은 "글쓰기 화면에서 나온 이동인가"로 한 번 더 좁혀야 한다.
     */
    fun isWritePage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.host != "blog.naver.com") return false
        if (uri.path == "/PostWriteForm.naver") return true
        val segments = (uri.path ?: "").trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.size != 1) return false
        return queryMap(uri.rawQuery ?: return false)["Redirect"] == "Write"
    }

    /** 발행 직후 에디터 페이지는 상단 프레임을 다시 읽지 않고 pushState 로 `/{blogId}/{logNo}` 만 남긴다. */
    private fun parsePermalink(path: String?): PublishedPost? {
        val segments = (path ?: return null).trim('/').split('/')
        if (segments.size != 2) return null
        val (blogId, logNo) = segments
        if (blogId.isBlank() || logNo.isEmpty() || !logNo.all(Char::isDigit)) return null
        return PublishedPost(blogId, logNo)
    }

    private fun queryMap(rawQuery: String): Map<String, String> =
        rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
}
