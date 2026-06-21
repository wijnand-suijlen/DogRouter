package app.dogrouter.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EuroSymbol
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
    Billing(route = "billing", label = "Billing", icon = Icons.Filled.EuroSymbol),
    Settings(route = "settings", label = "Settings", icon = Icons.Filled.Settings),
}
