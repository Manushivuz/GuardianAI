package com.dsatm.guardianai.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.util.*

// Data class to hold the state for the Redaction Pop-up (UPDATED)
data class RedactionOptions(
      val imageCount: Int = 0,
      val audioCount: Int = 0,
    val totalFilesToRedact: Int = 0, // NEW: Sum of imageCount + audioCount
      val isImageSelected: Boolean = true,
      val isAudioSelected: Boolean = true
)

// Helper function to analyze a folder's contents for redaction count
fun analyzeFolderForRedaction(folder: File): RedactionOptions {
      var imageCount = 0
      var audioCount = 0
      folder.walkTopDown().forEach { file -> // FIX: Use walkTopDown for comprehensive count
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
    val total = imageCount + audioCount

      return RedactionOptions(imageCount, audioCount, totalFilesToRedact = total)
}

// Custom Redaction Dialog Composable
@Composable
fun RedactionDialog(
      folder: File,
  options: RedactionOptions,
  onDismiss: () -> Unit,
  onRedactNow: (isImageChecked: Boolean, isAudioChecked: Boolean) -> Unit
) {
    val PrimaryBlue = Color(0xFF1976D2)
      var isImageChecked by remember { mutableStateOf(options.isImageSelected) }
      var isAudioChecked by remember { mutableStateOf(options.isAudioSelected) }
     

      AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Redact Folder Contents", fontWeight = FontWeight.SemiBold) },
        text = {
              Column {
                Text(
                  text = "Confirm redaction for ${options.totalFilesToRedact} file(s) inside: ${folder.name}",
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

// --- NEW REDACTION PROGRESS DIALOG ---

@Composable
fun RedactionProgressDialog(progress: RedactionProgress, folderName: String) {
    val PrimaryBlue = Color(0xFF1976D2)
    val progressValue = if (progress.total > 0) progress.processed.toFloat() / progress.total.toFloat() else 0f
    val animatedProgress by animateFloatAsState(targetValue = progressValue, label = "RedactionProgressAnimation")

    val progressText = "${progress.processed} of ${progress.total} files completed"
    val percentageText = "${(animatedProgress * 100).toInt()}%"

    AlertDialog(
        onDismissRequest = { /* Prevent dismissal during redaction */ },
        title = { Text("Redacting Files in $folderName", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {

                // Circular Progress Bar
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
                    CircularProgressIndicator(
                        progress = animatedProgress,
                        modifier = Modifier.size(100.dp),
                        strokeWidth = 8.dp,
                        color = PrimaryBlue,
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        text = percentageText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Status Text
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Please keep the app open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            // No confirm button needed
        },
        dismissButton = {
            // Not dismissible while running
        }
    )
}


// --- NEW PERMISSION GATE COMPOSABLE (For FileExplorerScreen) ---

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun PermissionCheckAndGate(activity: FragmentActivity, isModelReady: Boolean, onReady: () -> Unit) {
    val context = LocalContext.current

    // --- State Management ---
    var isManageStorageGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
    }
    var isKeyboardGranted by remember {
        mutableStateOf(isInputMethodEnabled(context))
    }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // --- Launchers ---
    val manageStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isManageStorageGranted = Environment.isExternalStorageManager()
        // Re-check keyboard after granting file access
        if (isManageStorageGranted) {
            isKeyboardGranted = isInputMethodEnabled(context)
        }
    }

    // Check Keyboard status every time the component recomposes (or returns from settings)
    LaunchedEffect(isManageStorageGranted) {
        isKeyboardGranted = isInputMethodEnabled(context)
    }

    // Gate control: If models are ready AND both permissions are true, proceed.
    LaunchedEffect(isModelReady, isManageStorageGranted, isKeyboardGranted) {
        if (isModelReady && isManageStorageGranted && isKeyboardGranted) {
            onReady() // Signals the parent Composable to proceed
        } else if (isModelReady) {
            showPermissionDialog = true
        }
    }

    // --- Permission Check Logic ---
    val goToSettings: (ImageVector, String) -> Unit = { icon, action ->
        when (action) {
            "STORAGE" -> {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                } else {
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                }
                manageStorageLauncher.launch(intent)
            }
            "KEYBOARD" -> {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                context.startActivity(intent)
            }
        }
    }

    if (showPermissionDialog && isModelReady) {
        val missingPermissionIcon: ImageVector
        val missingPermissionText: String
        val missingPermissionAction: String

        if (!isManageStorageGranted) {
            missingPermissionIcon = Icons.Default.Folder
            missingPermissionText = "Full File Access (Required for Redaction)"
            missingPermissionAction = "STORAGE"
        } else if (!isKeyboardGranted) {
            missingPermissionIcon = Icons.Default.Keyboard
            missingPermissionText = "Keyboard Grant (Required for Clipboard)"
            missingPermissionAction = "KEYBOARD"
        } else {
            showPermissionDialog = false
            return
        }

        AlertDialog(
            onDismissRequest = { activity.finish() }, // Cannot proceed without permissions
            title = { Text("Required Permissions", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("To use Guardian AI's full functionality, you must grant the following permission:")
                    Spacer(Modifier.height(12.dp))
                    PermissionItem(
                        icon = missingPermissionIcon,
                        text = missingPermissionText,
                        granted = false,
                        onClick = { goToSettings(missingPermissionIcon, missingPermissionAction) }
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Note: Access is required for all primary functions. The app will close if access is not granted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Red
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { goToSettings(missingPermissionIcon, missingPermissionAction) }
                ) {
                    Text("Grant Access / Go to Settings")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { activity.finish() }) {
                    Text("Exit App")
                }
            }
        )
    }
}

// Helper to check if GuardianAI is an enabled input method
private fun isInputMethodEnabled(context: Context): Boolean {
    val enabledInputMethods = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
    // Checks if the package name is in the list of enabled input methods
    return enabledInputMethods?.contains(context.packageName) == true
}

@Composable
private fun PermissionItem(icon: ImageVector, text: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (granted) Color(0xFF00C853) else Color.Red)
        Spacer(Modifier.width(12.dp))
        Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (granted) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Granted", tint = Color(0xFF00C853))
        } else {
            Icon(Icons.Default.Warning, contentDescription = "Missing", tint = Color.Red)
        }
    }
}