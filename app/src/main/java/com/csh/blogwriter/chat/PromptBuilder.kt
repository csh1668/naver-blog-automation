package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import javax.inject.Inject

class PromptBuilder @Inject constructor(private val store: PromptStore) {
    companion object { const val MEMORY_CAP = 40 }

    suspend fun system(memory: List<MemoryItem>, style: String?, targetLength: IntRange, draftTurn: Boolean): String {
        // STYLE 항목은 {{style}} 로 이미 들어간다 — 여기서 또 넣으면 같은 문장이 프롬프트에 두 번 실린다.
        val memoryLines = memory.filterNot { it.kind == MemoryKind.STYLE }
            .take(MEMORY_CAP).joinToString("\n") { "- ${it.kind.name}: ${it.text}" }.ifEmpty { "(없음)" }
        val sections = buildList {
            add(store.text(PromptSection.ROLE))
            add(store.text(PromptSection.AUDIENCE))
            add(store.text(PromptSection.STYLE).replace("{{style}}", style ?: "(아직 없음)"))
            add(store.text(PromptSection.MEMORY).replace("{{memory}}", memoryLines))
            add(store.text(PromptSection.STRUCTURE).replace("{{minLen}}", targetLength.first.toString()).replace("{{maxLen}}", targetLength.last.toString()))
            add(store.text(PromptSection.CONVERSATION))
            add(store.text(PromptSection.OUTPUT))
            if (draftTurn) add(store.text(PromptSection.SELFCHECK))
        }
        return sections.joinToString("\n\n")
    }
}
