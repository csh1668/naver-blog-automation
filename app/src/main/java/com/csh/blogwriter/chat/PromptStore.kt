package com.csh.blogwriter.chat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

enum class PromptGroup(val title: String) { WRITE("글쓰기"), ADVICE("조언"), FREE("자유") }

enum class PromptSection(val file: String, val title: String, val group: PromptGroup = PromptGroup.WRITE) {
    ROLE("01_role.md", "역할"), AUDIENCE("02_audience.md", "독자"), STYLE("03_style.md", "글 스타일"), MEMORY("04_memory.md", "기억"),
    STRUCTURE("05_structure.md", "글 구조 규칙"), CONVERSATION("06_conversation.md", "대화 규칙"), OUTPUT("07_output.md", "출력 형식"), SELFCHECK("08_selfcheck.md", "제출 전 점검"),
    ADVICE_ROLE("a1_advice_role.md", "조언·역할", PromptGroup.ADVICE), ADVICE_GUARDS("a2_advice_guards.md", "조언·판단 규칙", PromptGroup.ADVICE), ADVICE_OUTPUT("a3_advice_output.md", "조언·출력 형식", PromptGroup.ADVICE),
    FREE_ROLE("f1_free_role.md", "자유·역할", PromptGroup.FREE), FREE_MEMORY("f2_free_memory.md", "자유·기억 제안", PromptGroup.FREE),
}

interface PromptStore {
    suspend fun text(section: PromptSection): String
    fun observe(section: PromptSection): Flow<String>
    suspend fun override(section: PromptSection, text: String?)
    suspend fun isOverridden(section: PromptSection): Boolean
}

class AssetPromptStore @Inject constructor(@ApplicationContext private val context: Context, private val dataStore: DataStore<Preferences>) : PromptStore {
    private fun key(s: PromptSection) = stringPreferencesKey("prompt_override_${s.name}")
    private val defaults = HashMap<PromptSection, String>()
    private fun default(s: PromptSection) = defaults.getOrPut(s) { context.assets.open("prompts/${s.file}").bufferedReader().readText().trim() }

    override suspend fun text(section: PromptSection) = dataStore.data.first()[key(section)] ?: default(section)
    override fun observe(section: PromptSection) = dataStore.data.map { it[key(section)] ?: default(section) }
    override suspend fun override(section: PromptSection, text: String?) { dataStore.edit { if (text.isNullOrBlank()) it.remove(key(section)) else it[key(section)] = text.trim() } }
    override suspend fun isOverridden(section: PromptSection) = dataStore.data.first().contains(key(section))
}
