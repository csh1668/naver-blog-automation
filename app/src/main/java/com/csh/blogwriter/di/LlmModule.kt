package com.csh.blogwriter.di

import com.csh.blogwriter.llm.AndroidKeystoreCipher
import com.csh.blogwriter.llm.GeminiClient
import com.csh.blogwriter.llm.GeminiHttp
import com.csh.blogwriter.llm.SecretCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides @Singleton fun secretCipher(): SecretCipher = AndroidKeystoreCipher()

    @Provides @Singleton fun geminiClient(http: OkHttpClient): GeminiClient = GeminiClient(GeminiHttp.configure(http))
}
