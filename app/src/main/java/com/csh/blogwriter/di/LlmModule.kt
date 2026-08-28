package com.csh.blogwriter.di

import com.csh.blogwriter.chat.CachedPhotoAttachments
import com.csh.blogwriter.chat.ConversationEngine
import com.csh.blogwriter.chat.DefaultToolExecutor
import com.csh.blogwriter.chat.NoOpPublishedHook
import com.csh.blogwriter.chat.PhotoAttachments
import com.csh.blogwriter.chat.PromptBuilder
import com.csh.blogwriter.chat.PublishedHook
import com.csh.blogwriter.chat.ToolExecutor
import com.csh.blogwriter.chat.TurnRunner
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.llm.AndroidKeystoreCipher
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GeminiClient
import com.csh.blogwriter.llm.GeminiHttp
import com.csh.blogwriter.llm.KeyRotator
import com.csh.blogwriter.llm.SecretCipher
import com.csh.blogwriter.research.ResearchTool
import com.csh.blogwriter.research.WebResearchTool
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {
    @Binds @Singleton abstract fun researchTool(impl: WebResearchTool): ResearchTool

    /** 도구 호출 횟수 제한이 턴 단위이므로 언스코프 바인딩 — 주입될 때마다 새 인스턴스. */
    @Binds abstract fun toolExecutor(impl: DefaultToolExecutor): ToolExecutor

    @Binds @Singleton abstract fun turnRunner(impl: ConversationEngine): TurnRunner

    @Binds @Singleton abstract fun photoAttachments(impl: CachedPhotoAttachments): PhotoAttachments

    /** 발행 뒤 메모리 추출(SP2 Task 12)이 여기에 붙는다. */
    @Binds @Singleton abstract fun publishedHook(impl: NoOpPublishedHook): PublishedHook

    companion object {
        @Provides @Singleton fun secretCipher(): SecretCipher = AndroidKeystoreCipher()

        @Provides @Singleton fun geminiClient(http: OkHttpClient): GeminiClient = GeminiClient(GeminiHttp.configure(http))

        @Provides @Singleton fun conversationEngine(
            client: GeminiClient,
            keyStore: ApiKeyStore,
            promptBuilder: PromptBuilder,
            memory: MemoryRepository,
            settings: SettingsStore,
            tools: Provider<ToolExecutor>,
        ): ConversationEngine = ConversationEngine(
            client, keyStore,
            { keyIds, models -> KeyRotator(keyIds, models) { System.currentTimeMillis() } },
            { settings.modelPolicyOnce() }, promptBuilder, memory, { tools.get() },
        )
    }
}
