package com.kisshkitty.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kisshkitty.ui.screens.connection.ConnectionScreen
import com.kisshkitty.ui.screens.hosts.HostListScreen
import com.kisshkitty.ui.screens.settings.SettingsScreen
import com.kisshkitty.ui.screens.terminal.TerminalScreen

@Composable
fun KisshNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.HostList.route
    ) {
        composable(Screen.HostList.route) {
            HostListScreen(
                onConnectClick = { hostId ->
                    navController.navigate(Screen.Connection.createRoute(hostId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Connection.route,
            arguments = listOf(
                navArgument("hostId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val hostId = backStackEntry.arguments?.getString("hostId") ?: ""
            ConnectionScreen(
                hostId = hostId,
                onConnected = { sessionId ->
                    navController.navigate(Screen.Terminal.createRoute(sessionId))
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.Terminal.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            TerminalScreen(
                sessionId = sessionId,
                onDisconnect = {
                    navController.popBackStack(Screen.HostList.route, false)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

sealed class Screen(val route: String) {
    data object HostList : Screen("hosts")
    data object Connection : Screen("connection/{hostId}") {
        fun createRoute(hostId: String) = "connection/$hostId"
    }
    data object Terminal : Screen("terminal/{sessionId}") {
        fun createRoute(sessionId: String) = "terminal/$sessionId"
    }
    data object Settings : Screen("settings")
}
