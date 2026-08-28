package com.csh.blogwriter.ui.navigation

import kotlinx.serialization.Serializable

object Routes {
    @Serializable data object Home
    /** returnTo: 로그인 후 돌아갈 곳. null 이면 Home. 값은 "publish:{jobId}" 또는 "compose". */
    @Serializable data class Login(val returnTo: String? = null)
    /** sessionId 가 null 이면 새 대화를 시작한다. */
    @Serializable data class Chat(val sessionId: String? = null)
    @Serializable data class Publish(val jobId: String)
    @Serializable data class Fallback(val jobId: String)
    @Serializable data object History
    @Serializable data object FailureLogs
    @Serializable data object Admin
    @Serializable data object ApiKeys
    @Serializable data object Models
    @Serializable data object Prompts
    @Serializable data object Memory
}
