package com.dsatm.guardianai.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dsatm.guardianai.ui.Screen
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.fragment.app.FragmentActivity // NEW IMPORT

// Custom colors matching ES File Explorer aesthetic
private val PrimaryBlue = Color(0xFF0288D1)
private val DarkGray = Color(0xFF424242)
private val LightBackground = Color(0xFFF5F5F5)

// The segments for the top bar
sealed class HomeTab(val label: String) {
    object Local : HomeTab("Local")
    object Redacted : HomeTab("Redacted")
    object Tools : HomeTab("Tools")
}

data class StorageItem(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val path: String,
    val route: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as FragmentActivity // Cast context to activity for RedactedFilesScreen
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // State for the custom segmented tab bar
    var selectedTab by remember { mutableStateOf<HomeTab>(HomeTab.Local) }

    // 1. Storage Item Definitions (main categories)
    val storageItems = listOf(
        StorageItem(
            title = "Internal Storage",
            icon = Icons.Default.SdCard,
            color = PrimaryBlue,
            path = Environment.getExternalStorageDirectory().absolutePath,
            route = Screen.Explorer.route
        ),
        StorageItem(
            title = "Downloads",
            icon = Icons.Default.Download,
            color = Color(0xFF00C853),
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
            route = Screen.Explorer.route
        )
    )

    // 2. Library Items (Images, Music, etc.)
    val libraryItems = listOf(
        StorageItem(title = "Images", icon = Icons.Default.Image, color = Color(0xFFFDD835), path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath, route = Screen.Explorer.route),
        StorageItem(title = "Music", icon = Icons.Default.MusicNote, color = Color(0xFF673AB7), path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath, route = Screen.Explorer.route),
        StorageItem(title = "Documents", icon = Icons.Default.Description, color = Color(0xFFD32F2F), path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath, route = Screen.Explorer.route),
        StorageItem(title = "Video", icon = Icons.Default.Videocam, color = Color(0xFFE65100), path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath, route = Screen.Explorer.route)
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // Side Menu (Drawer) - Closely mimicking the ES design
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PrimaryBlue)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Icon(Icons.Default.AccountCircle, contentDescription = "User", tint = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Guest User", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }

                // Static Menu Items
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("Storage") },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    selected = selectedTab == HomeTab.Local,
                    onClick = { coroutineScope.launch { drawerState.close(); selectedTab = HomeTab.Local } }
                )
                NavigationDrawerItem(
                    label = { Text("Redacted Files (Secure)") },
                    icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    selected = selectedTab == HomeTab.Redacted,
                    // CHANGE 1: Change to setting the tab state, NOT navigating away
                    onClick = { coroutineScope.launch { drawerState.close(); selectedTab = HomeTab.Redacted } }
                )
                Divider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    selected = false,
                    onClick = { coroutineScope.launch { drawerState.close(); navController.navigate(Screen.Settings.route) } }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column(modifier = Modifier.background(PrimaryBlue)) {
                    // Custom Search/Actions Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }

                        // Search Bar area - functional navigation
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .clickable { navController.navigate(Screen.Search.route) }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Search", color = Color.Gray, fontSize = 14.sp)
                        }

                        // User/Net Disk Icon (Placeholder)
                        IconButton(onClick = { Toast.makeText(context, "User profile clicked", Toast.LENGTH_SHORT).show() }) {
                            Icon(Icons.Default.Person, contentDescription = "User", tint = Color.White)
                        }
                    }

                    // Custom Segmented Tab Bar (Local, Redacted, Tools)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        // CHANGE 2: Change click handlers to set the tab state
                        CustomTabButton(HomeTab.Local, selectedTab == HomeTab.Local) { selectedTab = HomeTab.Local }
                        CustomTabButton(HomeTab.Redacted, selectedTab == HomeTab.Redacted) { selectedTab = HomeTab.Redacted }
                        CustomTabButton(HomeTab.Tools, selectedTab == HomeTab.Tools) { navController.navigate(Screen.Tools.route) }
                    }
                }
            }
        ) { paddingValues ->
            // Main Content Area based on selectedTab

            // Container for Local/Redacted content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(LightBackground)
            ) {
                when (selectedTab) {
                    // Case 1: Local Tab (Shows file categories in a LazyColumn)
                    is HomeTab.Local -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        ) {
                            // --- Storage Analysis Section ---
                            item {
                                Text(
                                    text = "Storage",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                    color = DarkGray
                                )

                                // Internal/Downloads Cards
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    storageItems.forEach { item ->
                                        StorageCard(item = item) {
                                            val encodedPath = URLEncoder.encode(item.path, StandardCharsets.UTF_8.toString())
                                            navController.navigate(Screen.Explorer.createRoute(encodedPath))
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(20.dp))
                                Divider()
                            }

                            // --- Library Section ---
                            item {
                                Text(
                                    text = "Library",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    color = DarkGray
                                )

                                // Grid/Wrap Layout for Library Items
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        libraryItems.take(2).forEach { item ->
                                            LibraryCard(item = item, modifier = Modifier.weight(1f)) {
                                                val encodedPath = URLEncoder.encode(item.path, StandardCharsets.UTF_8.toString())
                                                navController.navigate(Screen.Explorer.createRoute(encodedPath))
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        libraryItems.takeLast(2).forEach { item ->
                                            LibraryCard(item = item, modifier = Modifier.weight(1f)) {
                                                val encodedPath = URLEncoder.encode(item.path, StandardCharsets.UTF_8.toString())
                                                navController.navigate(Screen.Explorer.createRoute(encodedPath))
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(20.dp))
                            }
                        }
                    }

                    // Case 2: Redacted Tab (Shows the secure files screen)
                    is HomeTab.Redacted -> {
                        // CHANGE 3: Display the RedactedFilesScreen directly here.
                        RedactedFilesScreen(activity = activity)
                    }

                    // Case 3: Tools Tab (Only navigation left in the header button)
                    is HomeTab.Tools -> {
                        // Should not be reachable via the segmented bar, but included for completeness.
                        Text("Tools Content Area", modifier = Modifier.align(Alignment.Center).padding(16.dp))
                    }
                }
            }
        }
    }
}

// NOTE: All utility composables (CustomTabButton, StorageCard, LibraryCard, RedactedFilesPlaceholder)
// remain UNCHANGED below this point, but RedactedFilesPlaceholder is now unused
// because the RedactedFilesScreen Composable is shown directly.
@Composable
fun CustomTabButton(tab: HomeTab, isSelected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
            containerColor = Color.Transparent
        ),
        border = if (isSelected) BorderStroke(2.dp, Color.White) else null,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = Modifier.width(IntrinsicSize.Min)
    ) {
        Text(tab.label, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StorageCard(item: StorageItem, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = item.color,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                // Placeholder for space
                LinearProgressIndicator(
                    progress = 0.4f, // 40% usage placeholder
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(6.dp),
                    color = item.color,
                    trackColor = item.color.copy(alpha = 0.3f)
                )
                Text(
                    text = "5.2 GB Free / 64 GB Total",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Navigate",
                tint = Color.Gray
            )
        }
    }
}

@Composable
fun LibraryCard(item: StorageItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = item.color,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = DarkGray
            )
        }
    }
}

// RedactedFilesPlaceholder is now redundant since the screen is shown directly,
// but is left here if it's referenced elsewhere.
@Composable
fun RedactedFilesPlaceholder(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Redacted",
            modifier = Modifier.size(64.dp),
            tint = PrimaryBlue
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Your secure, redacted files will appear here.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Use the Local tab to select a file or folder for redaction.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { /* Do nothing, as the tab is for navigation */ }) {
            Text("View Redacted Files")
        }
    }
}
