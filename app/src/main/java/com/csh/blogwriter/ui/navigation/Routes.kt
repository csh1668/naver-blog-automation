package com.csh.blogwriter.ui.navigation

import kotlinx.serialization.Serializable

object Routes {
    /** returnTo: 로그인 후 돌아갈 곳. null 이면 채팅으로 돌아간다. 값은 "publish:{jobId}". */
    @Serializable data class Login(val returnTo: String? = null)
    /** 시작 화면. sessionId 가 null 이면 아직 만들지 않은 "새 글" 로 연다. */
    @Serializable data class Chat(val sessionId: String? = null)
    @Serializable data class Publish(val jobId: String)
    @Serializable data class Fallback(val jobId: String)
    @Serializable data object FailureLogs
    @Serializable data object Admin
    @Serializable data object ApiKeys
    @Serializable data object Models
    @Serializable data object Prompts
    @Serializable data object Memory
}
