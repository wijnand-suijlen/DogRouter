package app.dogrouter.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.dogrouter.data.remote.AddressSuggestion
import app.dogrouter.ui.addresspicker.AddressPickerScreen
import app.dogrouter.ui.dogs.DogEditScreen
import app.dogrouter.ui.dogs.DogListScreen
import app.dogrouter.ui.history.HistoryScreen
import app.dogrouter.ui.settings.SettingsScreen
import app.dogrouter.ui.today.TodayScreen
import app.dogrouter.ui.week.WeekScreen
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

object DogsRoutes {
    const val GRAPH = "dogs"
    const val LIST = "dogs/list"
    const val NEW = "dogs/new"
    const val EDIT = "dogs/edit/{dogId}"
    fun edit(dogId: String) = "dogs/edit/$dogId"
}

object AddressPickerRoutes {
    const val BASE = "address-picker"
    const val ROUTE = "$BASE?lat={lat}&lon={lon}"
    fun navigate(lat: Double? = null, lon: Double? = null): String {
        val params = listOfNotNull(
            lat?.let { "lat=$it" },
            lon?.let { "lon=$it" },
        ).joinToString("&")
        return if (params.isEmpty()) BASE else "$BASE?$params"
    }
}

/** Default centre when the picker is opened without an existing dog location. */
private const val DEFAULT_LAT = 48.81
private const val DEFAULT_LON = 2.24

const val PICKED_ADDRESS_KEY = "pickedAddressJson"

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStack?.destination
    val json: Json = koinInject()

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
                composable(DogsRoutes.NEW) { entry ->
                    val picked = entry.consumePickedAddress(json)
                    DogEditScreen(
                        dogId = null,
                        pickedAddress = picked,
                        onDone = { navController.popBackStack() },
                        onPickOnMap = { lat, lon ->
                            navController.navigate(AddressPickerRoutes.navigate(lat, lon))
                        },
                    )
                }
                composable(DogsRoutes.EDIT) { entry ->
                    val dogId = entry.arguments?.getString("dogId")
                    val picked = entry.consumePickedAddress(json)
                    DogEditScreen(
                        dogId = dogId,
                        pickedAddress = picked,
                        onDone = { navController.popBackStack() },
                        onPickOnMap = { lat, lon ->
                            navController.navigate(AddressPickerRoutes.navigate(lat, lon))
                        },
                    )
                }
            }

            composable(
                route = AddressPickerRoutes.ROUTE,
                arguments = listOf(
                    navArgument("lat") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("lon") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val lat = entry.arguments?.getString("lat")?.toDoubleOrNull() ?: DEFAULT_LAT
                val lon = entry.arguments?.getString("lon")?.toDoubleOrNull() ?: DEFAULT_LON
                AddressPickerScreen(
                    initialLatitude = lat,
                    initialLongitude = lon,
                    onPicked = { suggestion ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(PICKED_ADDRESS_KEY, json.encodeToString(suggestion))
                        navController.popBackStack()
                    },
                    onDismiss = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Read any address that the picker stashed in this entry's saved state, then
 * clear the key so a recomposition does not re-apply it. Returns null when no
 * pick is pending.
 */
@Composable
private fun NavBackStackEntry.consumePickedAddress(json: Json): AddressSuggestion? {
    val raw by savedStateHandle
        .getStateFlow<String?>(PICKED_ADDRESS_KEY, null)
        .collectAsStateWithLifecycle()

    val suggestion = raw?.let {
        runCatching { json.decodeFromString<AddressSuggestion>(it) }.getOrNull()
    }

    LaunchedEffect(raw) {
        if (raw != null) savedStateHandle[PICKED_ADDRESS_KEY] = null
    }

    return suggestion
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
