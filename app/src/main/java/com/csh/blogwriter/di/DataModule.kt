package com.csh.blogwriter.di

import com.csh.blogwriter.data.prefs.DataStoreSettingsStore
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds @Singleton abstract fun historyRepository(impl: RoomHistoryRepository): HistoryRepository
    @Binds @Singleton abstract fun failureLogRepository(impl: RoomFailureLogRepository): FailureLogRepository
    @Binds @Singleton abstract fun pendingJobRepository(impl: RoomPendingJobRepository): PendingJobRepository
    @Binds @Singleton abstract fun settingsStore(impl: DataStoreSettingsStore): SettingsStore
}
