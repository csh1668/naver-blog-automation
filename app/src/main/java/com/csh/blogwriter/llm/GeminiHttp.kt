package com.csh.blogwriter.llm

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit.SECONDS

/** Gemini 전용 타임아웃(생성/스트리밍은 수 초를 넘길 수 있어 FR-12 의 5초 타임아웃과 별개로 조정). */
object GeminiHttp {
    fun configure(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectTimeout(15, SECONDS)
        .readTimeout(120, SECONDS)
        .writeTimeout(30, SECONDS)
        .callTimeout(180, SECONDS)
        .build()
}
