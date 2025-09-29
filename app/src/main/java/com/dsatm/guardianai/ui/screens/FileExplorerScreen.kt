package com.dsatm.guardianai.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

// Tag for logging messages
private const val TAG = "FileExplorerScreen"
// Custom color for an ES File Explorer-like theme
private val PrimaryBlue = Color(0xFF007AFF)

// Data class to hold the state for the Redaction Pop-up
data class RedactionOptions(
    val imageCount: Int = 0,
    val audioCount: Int = 0,
    val isImageSelected: Boolean = true,
    val isAudioSelected: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileExplorerScreen(
    modifier: Modifier = Modifier,
    activity: FragmentActivity,
    navController: NavController,
    encodedPath: String
) {
    val context: Context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Decode the initial path from the navigation argument
    val decodedStartPath = remember {
        URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.toString())
    }

    // State for file system navigation
    var currentPath by remember { mutableStateOf(decodedStartPath) }
    var filesInCurrentDir by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // State for Redaction Dialog
    var showRedactDialog by remember { mutableStateOf(false) }
    var selectedFolder by remember { mutableStateOf<File?>(null) }
    var redactionOptions by remember { mutableStateOf(RedactionOptions()) }

    // Function to load files
    val loadFiles: suspend (Context, String, Boolean) -> Unit = { ctx, path, isInitialLoad ->
        isLoading = true
        withContext(Dispatchers.IO) {
            val rootDir = File(path)
            if (rootDir.exists() && rootDir.isDirectory) {
                val newFiles = rootDir.listFiles()?.toList()
                    ?.filter { it.canRead() }
                    // Folders first, then sort by name alphabetically
                    ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                    ?: emptyList()

                if (newFiles.isNotEmpty() || !isInitialLoad) {
                    filesInCurrentDir = newFiles
                }
            } else {
                filesInCurrentDir = emptyList()
                Log.e(TAG, "Invalid path or directory not found: $path")
                if (!isInitialLoad) {
                    navController.popBackStack()
                }
            }
        }
        isLoading = false
    }

    // 1. Storage Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "Storage permission granted. Loading files.")
            coroutineScope.launch { loadFiles(context, currentPath, filesInCurrentDir.isNotEmpty()) }
        } else {
            Toast.makeText(context, "Storage permission is required to view files.", Toast.LENGTH_LONG).show()
        }
    }


    // Initial file load and path change listener
    LaunchedEffect(currentPath) {
        // Check for permission before loading files from external storage
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Requesting READ_EXTERNAL_STORAGE permission.")
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            loadFiles(context, currentPath, true)
        }
    }

    // Function to handle navigation up (back button)
    fun navigateUp() {
        val parent = File(currentPath).parentFile
        val rootParent = File(decodedStartPath).parentFile

        if (parent != null && parent.absolutePath != rootParent?.absolutePath) {
            currentPath = parent.absolutePath
        } else {
            // Pop back to the HomeScreen when navigating up from the initial path
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = File(currentPath).name, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    // Back button behavior: navigates up or pops back to Home
                    IconButton(onClick = ::navigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* Search logic */ }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { /* More options logic */ }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryBlue,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)) {

            // Current Path Display (Breadcrumb)
            Text(
                text = currentPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Divider()

            // File Listing Content
            Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (filesInCurrentDir.isEmpty()) {
                    Text("No files or folders found here.", modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filesInCurrentDir, key = { it.absolutePath }) { file ->
                            FileItem(
                                file = file,
                                onFileClick = {
                                    if (file.isDirectory) {
                                        currentPath = file.absolutePath
                                    } else {
                                        // TODO: Implement file open/view logic here
                                        Toast.makeText(context, "Clicked file: ${file.name}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onFileLongPress = {
                                    if (file.isDirectory) {
                                        selectedFolder = file
                                        redactionOptions = analyzeFolderForRedaction(file)
                                        showRedactDialog = true
                                    } else {
                                        // Show context menu for files (e.g., Decrypt, Redact)
                                        Toast.makeText(context, "Long-pressed file: ${file.name}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            Divider(modifier = Modifier.padding(start = 64.dp))
                        }
                    }
                }
            }
        }

        // Redaction Confirmation Dialog
        if (showRedactDialog && selectedFolder != null) {
            RedactionDialog(
                folder = selectedFolder!!,
                options = redactionOptions,
                onDismiss = { showRedactDialog = false },
                onRedactNow = { isImageChecked, isAudioChecked ->
                    // Logic for initiating redaction will be handled here
                    Toast.makeText(context,
                        "Redaction Started on ${selectedFolder!!.name}. Image: $isImageChecked, Audio: $isAudioChecked",
                        Toast.LENGTH_LONG).show()
                    showRedactDialog = false
                }
            )
        }
    }
}

// Helper function to analyze a folder's contents for redaction count
private fun analyzeFolderForRedaction(folder: File): RedactionOptions {
    var imageCount = 0
    var audioCount = 0
    folder.listFiles()?.forEach { file ->
        val nameLower = file.name.lowercase(Locale.ROOT)
        if (file.isFile) {
            when {
                // Common image extensions
                nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg") || nameLower.endsWith(".png") || nameLower.endsWith(".webp") -> imageCount++
                // Common audio extensions
                nameLower.endsWith(".mp3") || nameLower.endsWith(".wav") || nameLower.endsWith(".ogg") || nameLower.endsWith(".m4a") -> audioCount++
            }
        }
    }
    return RedactionOptions(imageCount, audioCount)
}

// Composable for a single file/folder item in the list
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItem(file: File, onFileClick: (File) -> Unit, onFileLongPress: (File) -> Unit) {
    val icon = when {
        file.isDirectory -> Icons.Default.Folder
        file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".png", ignoreCase = true) -> Icons.Default.Image
        file.name.endsWith(".mp3", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) -> Icons.Default.Mic
        file.name.endsWith(".txt", ignoreCase = true) -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }

    val iconTint = if (file.isDirectory) PrimaryBlue else Color.Gray
    val dateString = remember(file.lastModified()) {
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    }
    val sizeString = remember(file.length(), file.isDirectory) {
        if (file.isDirectory) {
            val count = file.listFiles()?.size ?: 0
            if (count > 0) "$count Items" else "Empty"
        } else {
            formatFileSize(file.length())
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onFileClick(file) },
                onLongClick = { onFileLongPress(file) }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$sizeString | $dateString",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

// Utility for formatting file size
private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// Custom Redaction Dialog Composable
@Composable
fun RedactionDialog(
    folder: File,
    options: RedactionOptions,
    onDismiss: () -> Unit,
    onRedactNow: (isImageChecked: Boolean, isAudioChecked: Boolean) -> Unit
) {
    var isImageChecked by remember { mutableStateOf(options.isImageSelected) }
    var isAudioChecked by remember { mutableStateOf(options.isAudioSelected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Redact Folder Contents") },
        text = {
            Column {
                Text(
                    text = "Confirm redaction for files inside: ${folder.name}",
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Image Checkbox
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = isImageChecked,
                        onCheckedChange = { isImageChecked = it },
                        enabled = options.imageCount > 0
                    )
                    Text(text = "Images (${options.imageCount} found)", modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Image, contentDescription = "Image", tint = Color.Gray)
                }

                // Audio Checkbox
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = isAudioChecked,
                        onCheckedChange = { isAudioChecked = it },
                        enabled = options.audioCount > 0
                    )
                    Text(text = "Audio (${options.audioCount} found)", modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Mic, contentDescription = "Audio", tint = Color.Gray)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onRedactNow(isImageChecked, isAudioChecked) },
                enabled = (isImageChecked && options.imageCount > 0) || (isAudioChecked && options.audioCount > 0)
            ) {
                Text("Redact Now")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}