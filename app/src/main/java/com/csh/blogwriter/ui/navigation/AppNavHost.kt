package com.csh.blogwriter.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.csh.blogwriter.ui.admin.FailureLogScreen
import com.csh.blogwriter.ui.history.HistoryScreen
import com.csh.blogwriter.ui.home.HomeScreen
import com.csh.blogwriter.ui.login.LoginScreen

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.Home) {
        composable<Routes.Home> {
            HomeScreen(
                onNewPost = { nav.navigate(Routes.TestCompose) },
                onLogin = { returnTo -> nav.navigate(Routes.Login(returnTo)) },
                onResumePending = { jobId -> nav.navigate(Routes.Publish(jobId)) },
                onHistory = { nav.navigate(Routes.History) },
                onAdmin = { nav.navigate(Routes.FailureLogs) },
            )
        }
        composable<Routes.Login> { entry ->
            val returnTo = entry.toRoute<Routes.Login>().returnTo
            LoginScreen(
                onBack = { nav.popBackStack() },
                onDone = {
                    nav.popBackStack()
                    when {
                        returnTo == "compose" -> nav.navigate(Routes.TestCompose)
                        returnTo?.startsWith("publish:") == true -> nav.navigate(Routes.Publish(returnTo.removePrefix("publish:")))
                    }
                },
            )
        }
        composable<Routes.TestCompose> { Text("준비 중: 글쓰기") }
        composable<Routes.Publish> { Text("준비 중: 발행 " + it.toRoute<Routes.Publish>().jobId) }
        composable<Routes.Fallback> { Text("준비 중: 폴백 " + it.toRoute<Routes.Fallback>().jobId) }
        composable<Routes.History> { HistoryScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.FailureLogs> { FailureLogScreen(onBack = { nav.popBackStack() }) }
    }
}
