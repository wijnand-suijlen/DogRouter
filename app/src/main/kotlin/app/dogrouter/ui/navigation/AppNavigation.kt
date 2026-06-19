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
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.ui.addresspicker.AddressPickerScreen
import app.dogrouter.ui.common.LegMapScreen
import app.dogrouter.ui.dogs.DogEditScreen
import app.dogrouter.ui.dogs.DogListScreen
import app.dogrouter.ui.followplan.FollowPlanScreen
import app.dogrouter.ui.history.HistoryScreen
import app.dogrouter.ui.settings.SettingsScreen
import app.dogrouter.ui.today.TodayScreen
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import java.time.LocalDate

object DogsRoutes {
    const val GRAPH = "dogs"
    const val LIST = "dogs/list"
    const val NEW = "dogs/new"
    const val EDIT = "dogs/edit/{dogId}"
    fun edit(dogId: String) = "dogs/edit/$dogId"
}

object FollowPlanRoutes {
    const val ARG_DATE = "date"
    const val ROUTE = "follow-plan/{$ARG_DATE}"

    /** [date] is formatted as ISO-8601 (e.g. 2026-06-19). */
    fun route(date: LocalDate): String = "follow-plan/$date"
}

object LegMapRoutes {
    const val ARG_FROM_LAT = "fromLat"
    const val ARG_FROM_LON = "fromLon"
    const val ARG_TO_LAT = "toLat"
    const val ARG_TO_LON = "toLon"
    const val ROUTE =
        "leg-map?$ARG_FROM_LAT={$ARG_FROM_LAT}&$ARG_FROM_LON={$ARG_FROM_LON}" +
            "&$ARG_TO_LAT={$ARG_TO_LAT}&$ARG_TO_LON={$ARG_TO_LON}"

    fun route(from: GeoPoint, to: GeoPoint): String =
        "leg-map?$ARG_FROM_LAT=${from.latitude}&$ARG_FROM_LON=${from.longitude}" +
            "&$ARG_TO_LAT=${to.latitude}&$ARG_TO_LON=${to.longitude}"
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

/** Routes that take over the whole screen and hide the bottom navigation. */
private val FULL_SCREEN_ROUTES = setOf(FollowPlanRoutes.ROUTE, LegMapRoutes.ROUTE)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStack?.destination
    val json: Json = koinInject()

    // Full-screen destinations (e.g. Follow plan) hide the bottom bar so they
    // own the whole screen.
    val showBottomBar = currentDestination?.route !in FULL_SCREEN_ROUTES

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
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
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TabDestination.Today.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TabDestination.Today.route) {
                TodayScreen(
                    onStartTrip = { date -> navController.navigate(FollowPlanRoutes.route(date)) },
                    onOpenLegMap = { from, to -> navController.navigate(LegMapRoutes.route(from, to)) },
                )
            }
            composable(
                route = FollowPlanRoutes.ROUTE,
                arguments = listOf(navArgument(FollowPlanRoutes.ARG_DATE) { type = NavType.StringType }),
            ) { entry ->
                val date = LocalDate.parse(entry.arguments?.getString(FollowPlanRoutes.ARG_DATE))
                FollowPlanScreen(
                    date = date,
                    onExit = { navController.popBackStack() },
                    onOpenLegMap = { from, to -> navController.navigate(LegMapRoutes.route(from, to)) },
                )
            }
            composable(
                route = LegMapRoutes.ROUTE,
                arguments = listOf(
                    navArgument(LegMapRoutes.ARG_FROM_LAT) { type = NavType.StringType },
                    navArgument(LegMapRoutes.ARG_FROM_LON) { type = NavType.StringType },
                    navArgument(LegMapRoutes.ARG_TO_LAT) { type = NavType.StringType },
                    navArgument(LegMapRoutes.ARG_TO_LON) { type = NavType.StringType },
                ),
            ) { entry ->
                val args = entry.arguments
                val from = GeoPoint(
                    args?.getString(LegMapRoutes.ARG_FROM_LAT)?.toDouble() ?: DEFAULT_LAT,
                    args?.getString(LegMapRoutes.ARG_FROM_LON)?.toDouble() ?: DEFAULT_LON,
                )
                val to = GeoPoint(
                    args?.getString(LegMapRoutes.ARG_TO_LAT)?.toDouble() ?: DEFAULT_LAT,
                    args?.getString(LegMapRoutes.ARG_TO_LON)?.toDouble() ?: DEFAULT_LON,
                )
                LegMapScreen(from = from, to = to, onExit = { navController.popBackStack() })
            }
            composable(TabDestination.History.route) { HistoryScreen() }
            composable(TabDestination.Settings.route) { entry ->
                val picked = entry.consumePickedAddress(json)
                SettingsScreen(
                    pickedAddress = picked,
                    onPickHomeOnMap = { lat, lon ->
                        navController.navigate(AddressPickerRoutes.navigate(lat, lon))
                    },
                )
            }

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
