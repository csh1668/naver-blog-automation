package com.csh.blogwriter.session

/** `https://blog.naver.com/MyBlog.naver` 가 리다이렉트된 `https://blog.naver.com/{blogId}` 에서 blogId 를 뽑는다. */
object BlogIdResolver {
    private val pattern = Regex("^https://blog\\.naver\\.com/([A-Za-z0-9_-]+)/?(?:\\?.*)?$")
    fun fromUrl(url: String): String? = pattern.matchEntire(url)?.groupValues?.get(1)
}
