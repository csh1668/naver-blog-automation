package com.csh.blogwriter.di

import com.csh.blogwriter.chat.ConversationEngine
import com.csh.blogwriter.chat.PromptBuilder
import com.csh.blogwriter.chat.ToolExecutor
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.llm.AndroidKeystoreCipher
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GeminiClient
import com.csh.blogwriter.llm.GeminiHttp
import com.csh.blogwriter.llm.KeyRotator
import com.csh.blogwriter.llm.ModelPolicy
import com.csh.blogwriter.llm.SecretCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import javax.inject.Provider
import javax.inject.Singleton

/** Task 8 에서 DefaultToolExecutor(web_search/open_page/remember)로 교체된다. */
class NoopToolExecutor : ToolExecutor {
    override suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit): JsonObject =
        buildJsonObject { put("error", "unavailable") }
}

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides @Singleton fun secretCipher(): SecretCipher = AndroidKeystoreCipher()

    @Provides @Singleton fun geminiClient(http: OkHttpClient): GeminiClient = GeminiClient(GeminiHttp.configure(http))

    @Provides @Singleton fun toolExecutor(): ToolExecutor = NoopToolExecutor()

    /** ModelPolicy 는 Task 9 에서 SettingsStore 값으로 교체한다. */
    @Provides @Singleton fun conversationEngine(
        client: GeminiClient,
        keyStore: ApiKeyStore,
        promptBuilder: PromptBuilder,
        memory: MemoryRepository,
        tools: Provider<ToolExecutor>,
    ): ConversationEngine = ConversationEngine(
        client, keyStore,
        { keyIds, models -> KeyRotator(keyIds, models) { System.currentTimeMillis() } },
        { ModelPolicy.DEFAULT }, promptBuilder, memory, { tools.get() },
    )
}
