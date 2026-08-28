package com.csh.blogwriter.ui.navigation

import kotlinx.serialization.Serializable

object Routes {
    @Serializable data object Home
    /** returnTo: 로그인 후 돌아갈 곳. null 이면 Home. 값은 "publish:{jobId}" 또는 "compose". */
    @Serializable data class Login(val returnTo: String? = null)
    @Serializable data object TestCompose
    @Serializable data class Publish(val jobId: String)
    @Serializable data class Fallback(val jobId: String)
    @Serializable data object History
    @Serializable data object FailureLogs
}
