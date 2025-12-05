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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.os.Build
import android.os.Environment
import android.widget.Toast.LENGTH_LONG
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background

private const val TAG = "FileListingContent"
private val PrimaryBlue = Color(0xFF1976D2)
private val LightGray = Color(0xFFE0E0E0)
private val DarkGray = Color(0xFF37474F)

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun FileListingContent(
      context: Context,
  decodedStartPath: String,
  currentPath: String,
  onPathChange: (String) -> Unit,
  onFolderLongPress: (File) -> Unit,
  redactionProgress: Pair<Int, Int>?, // Kept for API compatibility, but unused
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
    // NOTE: This launcher is deprecated here, as the parent 'PermissionCheckAndGate' now handles the initial settings redirection.
    // It is kept simple for the case where the user navigates back.
      val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
      ) {
            isManageStorageGranted = Environment.isExternalStorageManager()
            if (isManageStorageGranted) {
              coroutineScope.launch {
                loadFiles(context, currentPath, true)
              }
            } else {
              Toast.makeText(context, "Full storage access is required.", LENGTH_LONG).show()
            }
          }


      // Initial state check and load trigger
      LaunchedEffect(currentPath, isManageStorageGranted) {
            if (isManageStorageGranted) {
              loadFiles(context, currentPath, true)
            }
          }

      // --- New Function to Handle Settings Redirect ---
      val goToManageAccessSettings: () -> Unit = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
              try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                  data = Uri.fromParts("package", context.packageName, null)
                }
                manageStorageLauncher.launch(intent)
              } catch (e: Exception) {
                manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
              }
            }
          }


      Column(modifier = Modifier.fillMaxSize()) {

            // Current Path Display (Breadcrumb) - Styled to look cleaner
            Text(
              text = currentPath,
              modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 12.dp),
              color = DarkGray.copy(alpha = 0.7f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Divider(color = LightGray, thickness = 1.dp)

            // File Listing Content
            Box(modifier = Modifier.fillMaxSize()) {

              if (!isManageStorageGranted) {
                // *** Show the Grant All Files Access UI (should only happen if gated failed) ***
                Column(
                  modifier = Modifier.fillMaxSize().align(Alignment.Center).padding(32.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center
                ) {
                  Icon(Icons.Default.Warning, contentDescription = "Access Required", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                  Spacer(Modifier.height(16.dp))
                  Text("Full file access needed to browse storage.", color = DarkGray, textAlign = TextAlign.Center)
                  Spacer(Modifier.height(16.dp))
                  Button(onClick = goToManageAccessSettings) {
                    Text("Grant Access")
                  }
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
                    Divider(modifier = Modifier.padding(start = 64.dp), color = LightGray)
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
            file.name.endsWith(".mp3", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) -> Icons.Default.Audiotrack
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
        // Use Card/Surface for a visual depth around the icon
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = iconTint.copy(alpha = 0.1f)),
            modifier = Modifier.size(40.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = file.name,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
        color = DarkGray
              )
              Text(
                text = "$sizeString | $dateString",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
              )
            }
        if (file.isDirectory) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Navigate", tint = Color.LightGray)
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