package com.csh.blogwriter.blog

/** 최근 글 목록의 한 줄 — `post-list` API 의 항목에서 조언에 필요한 것만. */
data class PostSummary(val logNo: String, val title: String, val addedAt: Long, val comments: Int, val likes: Int, val brief: String, val photoCount: Int)

/** 글 본문. 문단·인용·표는 줄 단위 텍스트로, 사진·동영상은 개수만 센다. */
data class PostText(val logNo: String, val title: String, val lines: List<String>, val imageCount: Int, val videoCount: Int) {
    /** 모델에 넘길 본문. [MAX_CHARS] 를 넘으면 자르고 "(이하 생략)" 을 붙인다. */
    fun text(): String {
        val joined = lines.joinToString("\n")
        return if (joined.length <= MAX_CHARS) joined else joined.take(MAX_CHARS) + "\n(이하 생략)"
    }
    companion object { const val MAX_CHARS = 6_000 }
}

/** 오른쪽 패널과 도구가 함께 쓰는 모바일 글 주소. */
fun postUrl(blogId: String, logNo: String): String = "https://m.blog.naver.com/PostView.naver?blogId=$blogId&logNo=$logNo"
