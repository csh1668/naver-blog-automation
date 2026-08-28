package com.csh.blogwriter.research

/**
 * 호스트의 "등록 가능 도메인"만 남긴다(마지막 두 라벨, `co`/`or`/`ne`/`go`/`ac` 같은 2단계 코드면 세 라벨).
 * 정확한 공개 접미사 목록은 아니지만 리다이렉트 판별(www→consent, search→m.search 등)엔 충분하다.
 */
internal fun registrableDomain(host: String): String {
    val labels = host.lowercase().split(".")
    if (labels.size <= 2) return labels.joinToString(".")
    val secondLast = labels[labels.size - 2]
    val take = if (secondLast in setOf("co", "or", "ne", "go", "ac")) 3 else 2
    return labels.takeLast(take).joinToString(".")
}
