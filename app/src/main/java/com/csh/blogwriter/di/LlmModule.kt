package com.csh.blogwriter.di

import com.csh.blogwriter.llm.AndroidKeystoreCipher
import com.csh.blogwriter.llm.SecretCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides @Singleton fun secretCipher(): SecretCipher = AndroidKeystoreCipher()
}
