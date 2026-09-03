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
                    navController.navigate(Screen.Terminal.createRoute(hostId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Terminal.route,
            arguments = listOf(
                navArgument("hostId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val hostId = backStackEntry.arguments?.getString("hostId") ?: ""
            TerminalScreen(
                hostId = hostId,
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
    data object Terminal : Screen("terminal/{hostId}") {
        fun createRoute(hostId: String) = "terminal/$hostId"
    }
    data object Settings : Screen("settings")
}
