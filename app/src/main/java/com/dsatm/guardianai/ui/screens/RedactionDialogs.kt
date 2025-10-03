package com.dsatm.guardianai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.*

// Data class to hold the state for the Redaction Pop-up
data class RedactionOptions(
    val imageCount: Int = 0,
    val audioCount: Int = 0,
    val isImageSelected: Boolean = true,
    val isAudioSelected: Boolean = true
)

// Helper function to analyze a folder's contents for redaction count
fun analyzeFolderForRedaction(folder: File): RedactionOptions {
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
    val PrimaryBlue = Color(0xFF0288D1)

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
