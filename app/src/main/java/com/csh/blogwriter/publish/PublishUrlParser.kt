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
        if (uri.host != "blog.naver.com" || uri.path != "/PostView.naver") return null
        val query = queryMap(uri.rawQuery ?: return null)
        if (query["isAfterWrite"] != "true") return null
        val blogId = query["blogId"]?.takeIf { it.isNotBlank() } ?: return null
        val logNo = query["logNo"]?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return null
        return PublishedPost(blogId, logNo)
    }

    private fun queryMap(rawQuery: String): Map<String, String> =
        rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
}
