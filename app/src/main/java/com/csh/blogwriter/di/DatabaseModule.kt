package com.csh.blogwriter.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.csh.blogwriter.data.db.AppDatabase
import com.csh.blogwriter.data.db.FailureLogDao
import com.csh.blogwriter.data.db.PendingJobDao
import com.csh.blogwriter.data.db.PublishHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "blogwriter.db").build()

    @Provides
    fun providePublishHistoryDao(db: AppDatabase): PublishHistoryDao = db.publishHistoryDao()

    @Provides
    fun provideFailureLogDao(db: AppDatabase): FailureLogDao = db.failureLogDao()

    @Provides
    fun providePendingJobDao(db: AppDatabase): PendingJobDao = db.pendingJobDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
}
