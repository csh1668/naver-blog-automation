package com.csh.blogwriter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.csh.blogwriter.ui.admin.ApiKeysScreen
import com.csh.blogwriter.ui.admin.FailureLogScreen
import com.csh.blogwriter.ui.admin.ModelsScreen
import com.csh.blogwriter.ui.admin.PromptsScreen
import com.csh.blogwriter.ui.admin.SettingsScreen
import com.csh.blogwriter.ui.chat.ChatScreen
import com.csh.blogwriter.ui.fallback.FallbackScreen
import com.csh.blogwriter.ui.login.LoginScreen
import com.csh.blogwriter.ui.memory.MemoryScreen
import com.csh.blogwriter.ui.publish.PublishScreen

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    // 앱을 켜면 바로 채팅이다 (사용자 결정 2026-08-29). 다른 화면은 그 위에 쌓였다가 여기로 돌아온다.
    NavHost(navController = nav, startDestination = Routes.Chat()) {
        composable<Routes.Login> { entry ->
            val returnTo = entry.toRoute<Routes.Login>().returnTo
            LoginScreen(
                onBack = { nav.popBackStack() },
                onDone = {
                    nav.popBackStack()
                    if (returnTo?.startsWith("publish:") == true) nav.navigate(Routes.Publish(returnTo.removePrefix("publish:")))
                },
            )
        }
        composable<Routes.Chat> { entry ->
            ChatScreen(
                sessionId = entry.toRoute<Routes.Chat>().sessionId,
                onOpenMemory = { nav.navigate(Routes.Memory) },
                onAdmin = { nav.navigate(Routes.Admin) },
                onSessionExpired = { id -> nav.navigate(Routes.Login("publish:$id")) { popUpTo<Routes.Chat>() } },
                onFailed = { id -> nav.navigate(Routes.Fallback(id)) { popUpTo<Routes.Chat>() } },
            )
        }
        composable<Routes.Publish> {
            PublishScreen(
                onDone = { nav.popBackStack<Routes.Chat>(inclusive = false) },
                onSessionExpired = { id -> nav.navigate(Routes.Login("publish:$id")) { popUpTo<Routes.Chat>() } },
                onFailed = { id -> nav.navigate(Routes.Fallback(id)) { popUpTo<Routes.Chat>() } },
                onLeave = { nav.popBackStack<Routes.Chat>(inclusive = false) },
            )
        }
        composable<Routes.Fallback> { entry ->
            val jobId = entry.toRoute<Routes.Fallback>().jobId
            FallbackScreen(
                onRetry = { nav.navigate(Routes.Publish(jobId)) { popUpTo<Routes.Chat>() } },
                onHome = { nav.popBackStack<Routes.Chat>(inclusive = false) },
            )
        }
        composable<Routes.FailureLogs> { FailureLogScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Admin> {
            SettingsScreen(
                onApiKeys = { nav.navigate(Routes.ApiKeys) },
                onModels = { nav.navigate(Routes.Models) },
                onPrompts = { nav.navigate(Routes.Prompts) },
                onMemory = { nav.navigate(Routes.Memory) },
                onFailureLogs = { nav.navigate(Routes.FailureLogs) },
                onLoggedOut = { nav.popBackStack<Routes.Chat>(inclusive = false) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Routes.ApiKeys> { ApiKeysScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Models> { ModelsScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Prompts> { PromptsScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Memory> { MemoryScreen(onBack = { nav.popBackStack() }) }
    }
}
