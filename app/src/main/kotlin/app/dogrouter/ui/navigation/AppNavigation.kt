package app.dogrouter.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import app.dogrouter.ui.dogs.DogEditScreen
import app.dogrouter.ui.dogs.DogListScreen
import app.dogrouter.ui.history.HistoryScreen
import app.dogrouter.ui.settings.SettingsScreen
import app.dogrouter.ui.today.TodayScreen
import app.dogrouter.ui.week.WeekScreen

object DogsRoutes {
    const val GRAPH = "dogs"
    const val LIST = "dogs/list"
    const val NEW = "dogs/new"
    const val EDIT = "dogs/edit/{dogId}"
    fun edit(dogId: String) = "dogs/edit/$dogId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TabDestination.entries.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTab(tab) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TabDestination.Today.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TabDestination.Today.route) { TodayScreen() }
            composable(TabDestination.Week.route) { WeekScreen() }
            composable(TabDestination.History.route) { HistoryScreen() }
            composable(TabDestination.Settings.route) { SettingsScreen() }

            navigation(startDestination = DogsRoutes.LIST, route = DogsRoutes.GRAPH) {
                composable(DogsRoutes.LIST) {
                    DogListScreen(
                        onAddClick = { navController.navigate(DogsRoutes.NEW) },
                        onDogClick = { dogId -> navController.navigate(DogsRoutes.edit(dogId)) },
                    )
                }
                composable(DogsRoutes.NEW) {
                    DogEditScreen(
                        dogId = null,
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(DogsRoutes.EDIT) { backStackEntry ->
                    val dogId = backStackEntry.arguments?.getString("dogId")
                    DogEditScreen(
                        dogId = dogId,
                        onDone = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

private fun NavHostController.navigateToTab(tab: TabDestination) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
