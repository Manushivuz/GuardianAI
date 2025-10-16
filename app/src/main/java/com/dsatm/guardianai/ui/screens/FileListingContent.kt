package com.dsatm.guardianai.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.os.Build // NEW IMPORT for API checks
import android.os.Environment // NEW IMPORT for environment check


private const val TAG = "FileListingContent"
private val PrimaryBlue = Color(0xFF0288D1)

@Composable
fun FileListingContent(
    context: Context,
    decodedStartPath: String,
    currentPath: String,
    onPathChange: (String) -> Unit,
    onFolderLongPress: (File) -> Unit,
    redactionProgress: Pair<Int, Int>?,
    filesInCurrentDir: List<File>,
    onFilesUpdate: (List<File>) -> Unit,
    isLoading: Boolean,
    onLoadingChange: (Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val activity = context as? FragmentActivity

    // CRITICAL: Check for MANAGE_EXTERNAL_STORAGE availability
    var isManageStorageGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
    }

    // Function to load files (runs only if access is granted)
    val loadFiles: suspend (Context, String, Boolean) -> Unit = { ctx, path, isInitialLoad ->
        onLoadingChange(true)
        withContext(Dispatchers.IO) {
            // Note: This block now runs ONLY if isManageStorageGranted is true.
            val rootDir = File(path)
            if (rootDir.exists() && rootDir.isDirectory) {
                val newFiles = rootDir.listFiles()?.toList()
                    ?.filter { it.canRead() }
                    ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                    ?: emptyList()

                if (newFiles.isNotEmpty() || !isInitialLoad) {
                    onFilesUpdate(newFiles)
                }
            } else {
                onFilesUpdate(emptyList())
                Log.e(TAG, "Invalid path or directory not found: $path")
            }
        }
        onLoadingChange(false)
    }

    // --- MANAGE_EXTERNAL_STORAGE Request Launcher ---
    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // After returning from settings, check the status again
        isManageStorageGranted = Environment.isExternalStorageManager()
        if (isManageStorageGranted) {
            coroutineScope.launch {
                loadFiles(context, currentPath, true)
            }
        } else {
            Toast.makeText(context, "Full storage access is required.", Toast.LENGTH_LONG).show()
        }
    }


    // Initial state check and load trigger
    LaunchedEffect(currentPath, isManageStorageGranted) {
        if (isManageStorageGranted) {
            loadFiles(context, currentPath, true)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Only try to load files if access is granted or if API is older than R.
            // For API >= R, we rely entirely on Environment.isExternalStorageManager() check.
        } else {
            // For older APIs (pre-R), we fall back to the deprecated READ_EXTERNAL_STORAGE check
            // which your code handles, but which we will simplify out of necessity here:
            // Force the request if we are older than R and don't have permission.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                // For now, we trust the button click below handles this for older APIs.
            }
        }
    }

    // --- New Function to Handle Settings Redirect ---
    val goToManageAccessSettings: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (R) and above: Launch the dedicated 'All files access' screen
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                // Fallback for devices that don't implement the specific intent correctly
                manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }


    Column(modifier = Modifier.fillMaxSize()) {

        // --- Redaction Progress Indicator (Remains the same) ---
        redactionProgress?.let { (processed, total) ->
            val progress = processed.toFloat() / total
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = PrimaryBlue
            )
            Text(
                text = "Redacting: $processed of $total files completed.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = PrimaryBlue
            )
        }
        // --- End Progress Indicator ---

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

            if (!isManageStorageGranted) {
                // *** FIX: Show the Grant All Files Access UI ***
                Column(
                    modifier = Modifier.fillMaxSize().align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Full external storage access is required for file exploration.",
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))

                    // Button directs to the special settings screen
                    Button(onClick = goToManageAccessSettings) {
                        Text("Grant All Files Access")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Please toggle the 'Allow access to manage all files' switch.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            else if (isLoading) {
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
                                    onPathChange(file.absolutePath)
                                } else {
                                    Toast.makeText(context, "Clicked file: ${file.name}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onFileLongPress = {
                                if (file.isDirectory) {
                                    onFolderLongPress(file)
                                } else {
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
}

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