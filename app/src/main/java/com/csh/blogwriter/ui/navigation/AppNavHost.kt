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
import com.csh.blogwriter.ui.history.HistoryScreen
import com.csh.blogwriter.ui.home.HomeScreen
import com.csh.blogwriter.ui.login.LoginScreen
import com.csh.blogwriter.ui.memory.MemoryScreen
import com.csh.blogwriter.ui.publish.PublishScreen

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.Home) {
        composable<Routes.Home> {
            HomeScreen(
                onNewPost = { nav.navigate(Routes.Chat()) },
                onLogin = { returnTo -> nav.navigate(Routes.Login(returnTo)) },
                onOpenSession = { sessionId -> nav.navigate(Routes.Chat(sessionId)) },
                onResumePending = { jobId -> nav.navigate(Routes.Publish(jobId)) },
                onHistory = { nav.navigate(Routes.History) },
                onAdmin = { nav.navigate(Routes.Admin) },
            )
        }
        composable<Routes.Login> { entry ->
            val returnTo = entry.toRoute<Routes.Login>().returnTo
            LoginScreen(
                onBack = { nav.popBackStack() },
                onDone = {
                    nav.popBackStack()
                    when {
                        returnTo == "compose" -> nav.navigate(Routes.Chat())
                        returnTo?.startsWith("publish:") == true -> nav.navigate(Routes.Publish(returnTo.removePrefix("publish:")))
                    }
                },
            )
        }
        composable<Routes.Chat> { entry ->
            ChatScreen(
                sessionId = entry.toRoute<Routes.Chat>().sessionId,
                onBack = { nav.popBackStack() },
                onOpenMemory = { nav.navigate(Routes.Memory) },
                onSessionExpired = { id -> nav.navigate(Routes.Login("publish:$id")) { popUpTo(Routes.Home) } },
                onFailed = { id -> nav.navigate(Routes.Fallback(id)) { popUpTo(Routes.Home) } },
            )
        }
        composable<Routes.Publish> {
            PublishScreen(
                onDone = { nav.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } } },
                onSessionExpired = { id -> nav.navigate(Routes.Login("publish:$id")) { popUpTo(Routes.Home) } },
                onFailed = { id -> nav.navigate(Routes.Fallback(id)) { popUpTo(Routes.Home) } },
                onLeave = { nav.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } } },
            )
        }
        composable<Routes.Fallback> { entry ->
            val jobId = entry.toRoute<Routes.Fallback>().jobId
            FallbackScreen(
                onRetry = { nav.navigate(Routes.Publish(jobId)) { popUpTo(Routes.Home) } },
                onHome = { nav.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } } },
            )
        }
        composable<Routes.History> { HistoryScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.FailureLogs> { FailureLogScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Admin> {
            SettingsScreen(
                onApiKeys = { nav.navigate(Routes.ApiKeys) },
                onModels = { nav.navigate(Routes.Models) },
                onPrompts = { nav.navigate(Routes.Prompts) },
                onMemory = { nav.navigate(Routes.Memory) },
                onFailureLogs = { nav.navigate(Routes.FailureLogs) },
                onLoggedOut = { nav.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } } },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Routes.ApiKeys> { ApiKeysScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Models> { ModelsScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Prompts> { PromptsScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Memory> { MemoryScreen(onBack = { nav.popBackStack() }) }
    }
}
