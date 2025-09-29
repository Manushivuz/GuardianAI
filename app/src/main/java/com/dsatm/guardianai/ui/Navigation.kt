package com.dsatm.guardianai.ui

/**
 * Sealed class defining the navigation destinations and their route arguments.
 */
sealed class Screen(val route: String) {
    // The main dashboard where storage options (Internal, Downloads, etc.) are listed.
    object Home : Screen("home_screen")

    // The file explorer view, which requires a starting path.
    object Explorer : Screen("explorer_screen/{path}") {
        fun createRoute(path: String) = "explorer_screen/$path"
    }

    // Placeholder routes for settings and other general options
    object Settings : Screen("settings_screen")
    object Tools : Screen("tools_screen")
    object Redacted : Screen("redacted_screen")
    object Search : Screen("search_screen")
}
