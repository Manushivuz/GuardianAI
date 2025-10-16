package com.dsatm.guardianai.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.documentfile.provider.DocumentFile
import com.dsatm.guardianai.RedactionProcessor
import com.dsatm.guardianai.security.EncryptedFileService
import com.dsatm.guardianai.security.FileManagementService
import com.dsatm.guardianai.security.SecurityManager
import com.dsatm.image_redaction.ImageRedactionManager
import com.dsatm.guardianai.ui.Screen
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

// --- CRITICAL IMPORTS FOR COROUTINES AND UNITS ---
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.unit.dp
// --- END CRITICAL IMPORTS ---

// --- AUDIO IMPORTS ---
import com.dsatm.audio_redaction.AudioRedactionExecutor
import com.dsatm.audio_redaction.ui.AudioRedactionManager // Vosk Manager
import com.dsatm.audio_redaction.ui.WavAudioMuter // WAV Processor
import com.dsatm.ner.BertNerOnnxManager // NER Model
// --- END AUDIO IMPORTS ---

private const val TAG = "FileExplorerScreen"
private val PrimaryBlue = Color(0xFF0288D1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    modifier: Modifier = Modifier,
    activity: FragmentActivity,
    navController: NavController,
    encodedPath: String
) {
    val context: Context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- 1. Service Initialization ---
    // Core Services
    val securityManager = remember { SecurityManager(context) }
    val encryptedFileService = remember { EncryptedFileService(context, securityManager) }
    val fileManagementService = remember { FileManagementService(context, encryptedFileService) }

    // Feature Modules
    val imageRedactionManager = remember { ImageRedactionManager(context) }

    // *** AUDIO REDACTION SETUP ***
    val voskManager = remember { AudioRedactionManager(context) } // Vosk model loading/state
    val nerManager = remember { BertNerOnnxManager(context) } // NER Model
    val audioMuter = remember { WavAudioMuter() } // Audio processor

    val audioRedactionExecutor = remember {
        AudioRedactionExecutor(context, voskManager, nerManager, audioMuter)
    }
    // *** END AUDIO REDACTION SETUP ***

    // Redaction Processor now takes all necessary executors/managers
    val redactionProcessor = remember {
        // Updated constructor with the new audio component
        RedactionProcessor(context, fileManagementService, imageRedactionManager, audioRedactionExecutor)
    }

    // --- 2. State Management & Model Ready Check ---
    val decodedStartPath = remember { URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.toString()) }
    var currentPath by remember { mutableStateOf(decodedStartPath) }
    var filesInCurrentDir by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var showRedactDialog by remember { mutableStateOf(false) }
    var selectedFolder by remember { mutableStateOf<File?>(null) }
    var redactionOptions by remember { mutableStateOf(RedactionOptions()) }
    var redactionProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    var writableFolderUri by remember { mutableStateOf<Uri?>(null) }
    var isNerModelReady by remember { mutableStateOf(false) } // NEW STATE

    // --- CRITICAL: Initialize NER Model and Vosk Model on load ---
    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            // Initialize NER Model
            try {
                nerManager.initialize()
                Log.d(TAG, "BertNerOnnxManager initialized.")

                // Await Vosk Model (from AudioRedactionManager)
                while (!voskManager.isModelLoaded()) {
                    kotlinx.coroutines.delay(100)
                }
                Log.d(TAG, "Vosk model loaded.")

                isNerModelReady = true
            } catch (e: Exception) {
                Log.e(TAG, "FATAL: Failed to initialize NER or Vosk models.", e)
            }
        }
    }
    // --- END MODEL INITIALIZATION ---


    // --- 3. Redaction Logic using Writable URI ---
    val startRedactionWithUri: suspend (Uri) -> Unit = safFlow@{ folderUri ->
        val folderToRedact = DocumentFile.fromTreeUri(context, folderUri)

        if (folderToRedact == null || !folderToRedact.isDirectory) {
            Toast.makeText(context, "Selected URI is invalid.", Toast.LENGTH_SHORT).show()
            selectedFolder = null
            writableFolderUri = null
            return@safFlow
        }

        val isImageChecked = redactionOptions.isImageSelected
        val isAudioChecked = redactionOptions.isAudioSelected

        redactionProgress = Pair(0, 1) // Start progress

        // Run processor with the original File path for traversal, and the writable URI for I/O
        redactionProcessor.startFolderRedaction(
            rootFolder = File(selectedFolder!!.absolutePath),
            folderUri = folderUri, // Pass the Writable URI
            isImageRedactionEnabled = isImageChecked,
            isAudioRedactionEnabled = isAudioChecked,
            onProgress = { processed, total ->
                redactionProgress = Pair(processed, total)
            }
        )

        // Finalize
        redactionProgress = null
        Toast.makeText(context,
            "Redaction of ${selectedFolder!!.name} complete! Files overwritten.",
            Toast.LENGTH_LONG).show()

        // Reset state and reload the current directory
        selectedFolder = null
        writableFolderUri = null
        currentPath = currentPath // Trigger reload
    }


    // --- 4. SAF Picker Launcher ---
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistent permission for this URI
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            writableFolderUri = uri

            // Immediately start the redaction flow with the granted URI
            coroutineScope.launch {
                startRedactionWithUri(uri)
            }
        } else {
            Toast.makeText(context, "Folder access denied.", Toast.LENGTH_SHORT).show()
        }
    }


    // --- 5. Redaction Flow Initiation ---
    val startRedaction: (isImageChecked: Boolean, isAudioChecked: Boolean) -> Unit = redactInit@{ isImageChecked, isAudioChecked ->

        if (redactionOptions.imageCount == 0 && redactionOptions.audioCount == 0) {
            Toast.makeText(context, "No files selected to redact.", Toast.LENGTH_SHORT).show()
            return@redactInit // <-- USE EXPLICIT LABEL
        }

        showRedactDialog = false // Close the dialog immediately

        // CRITICAL SAF LOGIC: Ask for permission to write to this folder first.
        folderPickerLauncher.launch(null)
    }


    // --- 6. Navigation and Path Handlers (Remain the same) ---
    fun navigateUp() {
        val parent = File(currentPath).parentFile
        val rootParent = File(decodedStartPath).parentFile

        if (parent != null && parent.absolutePath != rootParent?.absolutePath) {
            currentPath = parent.absolutePath
        } else {
            navController.popBackStack()
        }
    }

    fun handlePathChange(newPath: String) {
        currentPath = newPath
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = File(currentPath).name, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = ::navigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Search.route) }) {
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

            // --- ADD CHECK FOR MODEL INITIALIZATION ---
            if (!isNerModelReady) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                    Text("Initializing AI Models...", modifier = Modifier.padding(top = 60.dp))
                }
            } else {
                // FileListingContent runs ONLY when models are ready
                FileListingContent(
                    context = context,
                    decodedStartPath = decodedStartPath,
                    currentPath = currentPath,
                    onPathChange = ::handlePathChange,
                    onFolderLongPress = { file ->
                        selectedFolder = file
                        redactionOptions = analyzeFolderForRedaction(file) // Utility from RedactionDialogs.kt
                        showRedactDialog = true
                    },
                    redactionProgress = redactionProgress,
                    filesInCurrentDir = filesInCurrentDir,
                    onFilesUpdate = { filesInCurrentDir = it },
                    isLoading = isLoading,
                    onLoadingChange = { isLoading = it }
                )
            }
        }

        // Redaction Confirmation Dialog
        if (showRedactDialog && selectedFolder != null) {
            RedactionDialog(
                folder = selectedFolder!!,
                options = redactionOptions,
                onDismiss = { showRedactDialog = false },
                onRedactNow = startRedaction // Hooked up to the SAF initiation flow
            )
        }
    }
}