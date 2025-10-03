package com.dsatm.guardianai

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dsatm.guardianai.security.SecurityManager
import com.dsatm.guardianai.ui.Screen
import com.dsatm.guardianai.ui.screens.FileExplorerScreen
import com.dsatm.guardianai.ui.screens.HomeScreen
import com.dsatm.guardianai.ui.screens.SearchScreen // NEW IMPORT
import com.dsatm.guardianai.ui.theme.GuardianAITheme
import com.dsatm.guardianai.security.SecurityUtils

/**
 * Main application entry point handling initial Biometric authentication.
 */
class MainActivity : FragmentActivity() {

    private lateinit var securityManager: SecurityManager
    // State to track if authentication has succeeded
    private var isAuthenticated by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecurityUtils.destroyMasterKey(this)
        securityManager = SecurityManager(this)

        if (securityManager.isBiometricReady()) {
            performBiometricAuth()
        } else {
            // Fallback: If biometrics are not available, grant access by default.
            isAuthenticated = true
            Toast.makeText(this, "Biometrics not set up. Access granted.", Toast.LENGTH_SHORT).show()
        }

        setContent {
            GuardianAITheme {
                if (isAuthenticated) {
                    // Show the main content once authenticated
                    MainScreen()
                } else {
                    // Show the splash screen during authentication
                    SplashScreen()
                }
            }
        }
    }

    private fun performBiometricAuth() {
        securityManager.authenticateForAppAccess(
            this,
            onSuccess = {
                // Authentication succeeded, grant access
                isAuthenticated = true
                Toast.makeText(this, "Authentication successful!", Toast.LENGTH_SHORT).show()
            },
            onFailure = { errorMessage ->
                // Authentication failed, show a message and close the app
                isAuthenticated = false
                Toast.makeText(this, "Authentication failed: $errorMessage", Toast.LENGTH_LONG).show()
                finish() // Close the activity to prevent unauthorized access
            }
        )
    }
}

/**
 * Main application screen launched after successful authentication, now hosting the NavHost.
 */
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val activity = LocalContext.current as FragmentActivity

    Scaffold { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route, // Start at the Home screen
            modifier = Modifier.padding(innerPadding)
        ) {
            // 1. Home Screen (Dashboard/Main Menu)
            composable(Screen.Home.route) {
                HomeScreen(navController = navController)
            }

            // 2. File Explorer Screen (Accepts a path argument)
            composable(
                route = Screen.Explorer.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { backStackEntry ->
                val encodedPath = backStackEntry.arguments?.getString("path") ?: ""
                FileExplorerScreen(
                    navController = navController,
                    activity = activity,
                    encodedPath = encodedPath
                )
            }

            // 3. Search Screen
            composable(Screen.Search.route) {
                SearchScreen(navController = navController)
            }

            // 4. Placeholder for Settings
            composable(Screen.Settings.route) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Settings Screen", style = MaterialTheme.typography.headlineLarge)
                }
            }

            // 5. Placeholder for Tools
            composable(Screen.Tools.route) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tools Screen", style = MaterialTheme.typography.headlineLarge)
                }
            }

            // 6. Placeholder for Redacted Files
            composable(Screen.Redacted.route) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Redacted Files Screen", style = MaterialTheme.typography.headlineLarge)
                }
            }
        }
    }
}

// A simple splash screen to show while authenticating
@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        CircularProgressIndicator()
        Text(text = "Authenticating...", modifier = Modifier.padding(top = 80.dp))
    }
}