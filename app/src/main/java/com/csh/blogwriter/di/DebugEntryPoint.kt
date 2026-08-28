package com.csh.blogwriter.di

import com.csh.blogwriter.research.ResearchTool
import com.csh.blogwriter.session.NaverSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 디버그 빌드의 브로드캐스트 수신기가 Hilt 주입 없이 세션·검색 도구를 꺼내 쓰기 위한 통로. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugEntryPoint {
    fun session(): NaverSession
    fun research(): ResearchTool
}
