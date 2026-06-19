package app.dogrouter.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector

enum class TabDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Today(route = "today", label = "Today", icon = Icons.Filled.Today),
    Dogs(route = "dogs", label = "Dogs", icon = Icons.Filled.Pets),
    History(route = "history", label = "History", icon = Icons.Filled.History),
    Settings(route = "settings", label = "Settings", icon = Icons.Filled.Settings),
}
