package com.dsatm.image_redaction.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.dsatm.image_redaction.util.ImageRedactionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ImageRedactionScreen() {
    val context = LocalContext.current
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var processing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var processedFiles by remember { mutableStateOf(listOf<File>()) }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        folderUri = uri
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Log.d("ImageRedactionScreen", "Selected folder URI: $uri")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "GuardianAi: Batch Sensitive Data Redaction",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { folderPickerLauncher.launch(null) },
            enabled = !processing
        ) { Text(if (folderUri == null) "Select Folder" else "Change Folder") }

        folderUri?.let { uri ->
            Button(
                onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        processing = true
                        progressText = "Scanning images..."
                        Log.d("ImageRedactionScreen", "Starting image scan.")
                        val imgDocs = ImageRedactionUtils.getAllImagesInFolder(context, uri)
                        val outputDir = File(context.getExternalFilesDir("images"), "redacted")
                        outputDir.mkdirs()
                        val results = mutableListOf<File>()
                        for ((i, inputDoc) in imgDocs.withIndex()) {
                            progressText = "Processing image ${i + 1} of ${imgDocs.size}"
                            val redacted = ImageRedactionUtils.redactSensitiveInImage(context, inputDoc, outputDir)
                            if (redacted != null) results.add(redacted)
                        }
                        processedFiles = results
                        progressText = "Completed: ${results.size} redacted images saved to ${outputDir.absolutePath}"
                        processing = false
                        Log.d("ImageRedactionScreen", "Processing completed.")
                    }
                },
                enabled = folderUri != null && !processing
            ) {
                Text("Scan & Redact All Images")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(progressText, style = MaterialTheme.typography.bodyMedium)

        if (processedFiles.isNotEmpty()) {
            Text(
                text = "Redacted Images:",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(processedFiles) { file ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "File: ${file.name}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            AsyncImage(
                                model = Uri.fromFile(file),
                                contentDescription = "Redacted Image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getAllImagesInFolder(context: Context, treeUri: Uri) =
    ImageRedactionUtils.getAllImagesInFolder(context, treeUri)

private suspend fun redactSensitiveInImage(context: Context, inputDoc: DocumentFile, outputDir: File) =
    ImageRedactionUtils.redactSensitiveInImage(context, inputDoc, outputDir)