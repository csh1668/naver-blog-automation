package com.csh.blogwriter.chat

import com.csh.blogwriter.blog.PostSummary
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.SessionMode
import javax.inject.Inject

class PromptBuilder @Inject constructor(private val store: PromptStore) {
    companion object {
        const val MEMORY_CAP = 40
        const val NO_POSTS = "[최근 글 목록]\n(목록 없음 — 글 목록을 읽지 못했습니다. 사용자가 글을 지목하면 read_my_post 로 읽고, 목록이 필요하면 list_my_posts 를 부릅니다.)"
        private val DATE = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.of("Asia/Seoul"))
    }

    suspend fun system(memory: List<MemoryItem>, style: String?, targetLength: IntRange, draftTurn: Boolean, mode: SessionMode = SessionMode.WRITE): String {
        // STYLE 항목은 {{style}} 로 이미 들어간다 — 여기서 또 넣으면 같은 문장이 프롬프트에 두 번 실린다.
        val memoryLines = memory.filterNot { it.kind == MemoryKind.STYLE }
            .take(MEMORY_CAP).joinToString("\n") { "- ${it.kind.name}: ${it.text}" }.ifEmpty { "(없음)" }
        val styleSection = store.text(PromptSection.STYLE).replace("{{style}}", style ?: "(아직 없음)")
        val memorySection = store.text(PromptSection.MEMORY).replace("{{memory}}", memoryLines)
        val sections = when (mode) {
            SessionMode.WRITE -> buildList {
                add(store.text(PromptSection.ROLE)); add(store.text(PromptSection.AUDIENCE)); add(styleSection); add(memorySection)
                add(store.text(PromptSection.STRUCTURE)); add(store.text(PromptSection.CONVERSATION)); add(store.text(PromptSection.OUTPUT))
                if (draftTurn) add(store.text(PromptSection.SELFCHECK))
            }
            // 조언은 글쓰기 규칙(구조·대화·출력·점검)을 싣지 않는다 — 스타일·기억만 공유해 "출발점"을 알게 한다.
            SessionMode.ADVICE -> listOf(store.text(PromptSection.ADVICE_ROLE), styleSection, memorySection, store.text(PromptSection.ADVICE_GUARDS), store.text(PromptSection.ADVICE_OUTPUT))
        }
        // 길이 자리표시자는 어느 섹션에 있든(구조·자기점검·관리자가 편집한 곳) 모두 채운다.
        return sections.joinToString("\n\n")
            .replace("{{minLen}}", targetLength.first.toString())
            .replace("{{maxLen}}", targetLength.last.toString())
    }

    /** 조언 시스템 프롬프트 끝에 붙는 [최근 글 목록] 표. null 이면 "(목록 없음 …)" 안내. */
    fun postsSection(posts: List<PostSummary>?): String {
        if (posts == null) return NO_POSTS
        val rows = posts.joinToString("\n") { p ->
            "${p.logNo} | ${p.title} | ${DATE.format(java.time.Instant.ofEpochMilli(p.addedAt))} | 댓글 ${p.comments} | 공감 ${p.likes} | 사진 ${p.photoCount}" +
                (p.brief.takeIf { it.isNotBlank() }?.let { "\n    요약: ${it.take(160)}" } ?: "")
        }
        return "[최근 글 목록] (logNo | 제목 | 날짜 | 댓글 | 공감 | 사진 수) — 본문은 read_my_post(logNo) 로 읽는다\n$rows"
    }
}
