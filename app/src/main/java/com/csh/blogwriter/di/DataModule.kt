package com.csh.blogwriter.di

import com.csh.blogwriter.chat.AssetPromptStore
import com.csh.blogwriter.chat.PromptStore
import com.csh.blogwriter.data.prefs.DataStoreSettingsStore
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.*
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.DataStoreApiKeyStore
import com.csh.blogwriter.publish.DocumentModelConverter
import com.csh.blogwriter.publish.ImagePreparer
import com.csh.blogwriter.ui.publish.ImagePreparing
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds @Singleton abstract fun historyRepository(impl: RoomHistoryRepository): HistoryRepository
    @Binds @Singleton abstract fun failureLogRepository(impl: RoomFailureLogRepository): FailureLogRepository
    @Binds @Singleton abstract fun pendingJobRepository(impl: RoomPendingJobRepository): PendingJobRepository
    @Binds @Singleton abstract fun chatRepository(impl: RoomChatRepository): ChatRepository
    @Binds @Singleton abstract fun memoryRepository(impl: RoomMemoryRepository): MemoryRepository
    @Binds @Singleton abstract fun settingsStore(impl: DataStoreSettingsStore): SettingsStore
    @Binds @Singleton abstract fun apiKeyStore(impl: DataStoreApiKeyStore): ApiKeyStore
    @Binds @Singleton abstract fun promptStore(impl: AssetPromptStore): PromptStore
    @Binds abstract fun imagePreparing(impl: ImagePreparer): ImagePreparing

    companion object {
        @Provides fun documentModelConverter(): DocumentModelConverter = DocumentModelConverter()
    }
}
