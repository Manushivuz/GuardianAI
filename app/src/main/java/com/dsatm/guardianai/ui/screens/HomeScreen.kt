package com.dsatm.guardianai.ui.screens

import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dsatm.guardianai.ui.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.fragment.app.FragmentActivity
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

// --- THEME COLORS (Modern & Clean) ---
private val PrimaryBlue = Color(0xFF1976D2) // Rich Blue
private val DarkGray = Color(0xFF37474F)    // Dark Slate
private val LightSurface = Color(0xFFF0F2F5) // Off-White Background
private val AccentGreen = Color(0xFF4CAF50) // Accent color for progress

// --- DATA STRUCTURES (Defined Locally for Completeness) ---
sealed class HomeTab(val label: String) {
    object Local : HomeTab("Local Storage")
    object Redacted : HomeTab("Secure Vault")
    object Tools : HomeTab("Utilities")
}

data class StorageItem(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val path: String,
    val route: String
)

data class StorageMetrics(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val usedPercent: Float
)

// --- UTILITY FUNCTIONS ---

private fun getStorageMetrics(path: String): StorageMetrics {
    try {
        val stat = StatFs(path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalBytes = totalBlocks * blockSize
        val availableBytes = availableBlocks * blockSize
        val usedBytes = totalBytes - availableBytes
        val usedPercent = if (totalBytes > 0) usedBytes.toFloat() / totalBytes.toFloat() else 0f

        return StorageMetrics(totalBytes, availableBytes, usedBytes, usedPercent)
    } catch (e: Exception) {
        return StorageMetrics(0L, 0L, 0L, 0f)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}

@Composable
private fun StorageStatusFetcher(path: String, content: @Composable (StorageMetrics) -> Unit) {
    var metrics by remember { mutableStateOf(getStorageMetrics(path)) }

    LaunchedEffect(path) {
        while (true) {
            metrics = getStorageMetrics(path)
            delay(5000) // Update every 5 seconds
        }
    }
    content(metrics)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf<HomeTab>(HomeTab.Local) }

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

    val libraryItems = listOf(
        StorageItem(title = "Images", icon = Icons.Default.Image, color = Color(0xFFFDD835), path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath, route = Screen.Explorer.route),
        StorageItem(title = "Music", icon = Icons.Default.MusicNote, color = Color(0xFF673AB7), path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath, route = Screen.Explorer.route),
        StorageItem(title = "Documents", icon = Icons.Default.Description, color = Color(0xFFD32F2F), path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath, route = Screen.Explorer.route),
        StorageItem(title = "Video", icon = Icons.Default.Videocam, color = Color(0xFFE65100), path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath, route = Screen.Explorer.route)
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                // Drawer Content remains the same
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
                    // Custom Search/Actions Bar (Kept mostly functional)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }

                        // Modern Search Bar Input Area
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { navController.navigate(Screen.Search.route) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Search files...", color = Color.Gray, fontSize = 14.sp)
                            }
                        }

                        IconButton(onClick = { Toast.makeText(context, "User profile clicked", Toast.LENGTH_SHORT).show() }) {
                            Icon(Icons.Default.Person, contentDescription = "User", tint = Color.White)
                        }
                    }

                    // Modern Tab Bar (Underlined indicator style)
                    TabRow(
                        selectedTabIndex = when (selectedTab) { HomeTab.Local -> 0; HomeTab.Redacted -> 1; else -> 0 },
                        containerColor = PrimaryBlue,
                        indicator = { tabPositions ->
                            if (tabPositions.isNotEmpty()) {
                                TabRowDefaults.Indicator(
                                    Modifier.tabIndicatorOffset(tabPositions[when (selectedTab) {
                                        HomeTab.Local -> 0; HomeTab.Redacted -> 1; else -> 0
                                    }]),
                                    color = Color.White,
                                    height = 3.dp
                                )
                            }
                        },
                        divider = {} // Remove default divider
                    ) {
                        // Local Tab
                        Tab(
                            selected = selectedTab == HomeTab.Local,
                            onClick = { selectedTab = HomeTab.Local },
                            text = { Text(HomeTab.Local.label, color = Color.White) }
                        )
                        // Redacted Tab
                        Tab(
                            selected = selectedTab == HomeTab.Redacted,
                            onClick = { selectedTab = HomeTab.Redacted },
                            text = { Text(HomeTab.Redacted.label, color = Color.White) }
                        )
                        // Tools Tab (Navigation only)
                        Tab(
                            selected = selectedTab == HomeTab.Tools,
                            onClick = { navController.navigate(Screen.Tools.route) },
                            text = { Text(HomeTab.Tools.label, color = Color.White) }
                        )
                    }
                }
            }
        ) { paddingValues ->
            // Main Content Area based on selectedTab
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(LightSurface)
            ) {
                when (selectedTab) {
                    is HomeTab.Local -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // --- Storage Analysis Section ---
                            item {
                                Text(
                                    text = "Your Storage",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DarkGray,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                // Internal/Downloads Cards
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    storageItems.forEach { item ->
                                        StorageStatusFetcher(path = item.path) { metrics ->
                                            StorageCardModern(item = item, metrics = metrics) {
                                                val encodedPath = URLEncoder.encode(item.path, StandardCharsets.UTF_8.toString())
                                                navController.navigate(Screen.Explorer.createRoute(encodedPath))
                                            }
                                        }
                                    }
                                }
                            }

                            // --- Library Section ---
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Quick Access",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DarkGray,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                                )

                                // Grid Layout for Library Items
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        libraryItems.take(2).forEach { item ->
                                            LibraryCardModern(item = item, modifier = Modifier.weight(1f)) {
                                                val encodedPath = URLEncoder.encode(item.path, StandardCharsets.UTF_8.toString())
                                                navController.navigate(Screen.Explorer.createRoute(encodedPath))
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        libraryItems.takeLast(2).forEach { item ->
                                            LibraryCardModern(item = item, modifier = Modifier.weight(1f)) {
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

                    is HomeTab.Redacted -> {
                        RedactedFilesScreen(activity = activity)
                    }

                    is HomeTab.Tools -> {
                        Text("Tools Content Area", modifier = Modifier.align(Alignment.Center).padding(16.dp))
                    }
                }
            }
        }
    }
}


@Composable
fun StorageCardModern(item: StorageItem, metrics: StorageMetrics, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = item.color,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = DarkGray
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Real-time Progress Bar (prominent)
            LinearProgressIndicator(
                progress = metrics.usedPercent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .height(10.dp),
                color = AccentGreen, // Use a consistent accent color for progress
                trackColor = Color(0xFFE0E0E0)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Storage Text Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatBytes(metrics.usedBytes)} Used",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkGray
                )
                Text(
                    text = "${formatBytes(metrics.availableBytes)} Free",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
            Text(
                text = "Total: ${formatBytes(metrics.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray
            )
        }
    }
}

@Composable
fun LibraryCardModern(item: StorageItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = modifier
            .height(120.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = item.color,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = DarkGray
            )
        }
    }
}