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
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.unit.dp
// --- AUDIO IMPORTS ---
import com.dsatm.audio_redaction.AudioRedactionExecutor
import com.dsatm.audio_redaction.ui.AudioRedactionManager
import com.dsatm.audio_redaction.ui.WavAudioMuter
import com.dsatm.ner.BertNerOnnxManager
// --- END AUDIO IMPORTS ---

private const val TAG = "FileExplorerScreen"
private val PrimaryBlue = Color(0xFF1976D2) // Consistent Primary Blue

// --- UPDATED REDACTION PROGRESS DATA CLASS ---
data class RedactionProgress(
    val processed: Int,
    val total: Int
)

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
    val securityManager = remember { SecurityManager(context) }
    val encryptedFileService = remember { EncryptedFileService(context, securityManager) }
    val fileManagementService = remember { FileManagementService(context, encryptedFileService) }
    val imageRedactionManager = remember { ImageRedactionManager(context) }

    val voskManager = remember { AudioRedactionManager(context) }
    val nerManager = remember { BertNerOnnxManager(context) }
    val audioMuter = remember { WavAudioMuter() }

    val audioRedactionExecutor = remember {
        AudioRedactionExecutor(context, voskManager, nerManager, audioMuter)
    }

    val redactionProcessor = remember {
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

    var redactionProgressState by remember { mutableStateOf<RedactionProgress?>(null) }
    var isRedactionRunning by remember { mutableStateOf(false) }

    var writableFolderUri by remember { mutableStateOf<Uri?>(null) }
    var isNerModelReady by remember { mutableStateOf(false) }
    var areModelsAndPermissionsReady by remember { mutableStateOf(false) }

    // --- CRITICAL: Initialize NER Model and Vosk Model on load ---
    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                nerManager.initialize()
                while (!voskManager.isModelLoaded()) {
                    kotlinx.coroutines.delay(100)
                }
                isNerModelReady = true
                Log.d(TAG, "All AI Models initialized.")
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

        isRedactionRunning = true
        redactionProgressState = RedactionProgress(0, redactionOptions.totalFilesToRedact)

        // Run processor with the original File path for traversal, and the writable URI for I/O
        redactionProcessor.startFolderRedaction(
            rootFolder = File(selectedFolder!!.absolutePath),
            folderUri = folderUri,
            isImageRedactionEnabled = isImageChecked,
            isAudioRedactionEnabled = isAudioChecked,
            onProgress = { processed, total ->
                redactionProgressState = RedactionProgress(processed, total)
            }
        )

        // Finalize
        isRedactionRunning = false
        redactionProgressState = null

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
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            writableFolderUri = uri

            coroutineScope.launch {
                startRedactionWithUri(uri)
            }
        } else {
            Toast.makeText(context, "Folder access denied.", Toast.LENGTH_SHORT).show()
        }
    }


    // --- 5. Redaction Flow Initiation ---
    val startRedaction: (isImageChecked: Boolean, isAudioChecked: Boolean) -> Unit = redactInit@{ isImageChecked, isAudioChecked ->

        if (redactionOptions.totalFilesToRedact == 0) {
            Toast.makeText(context, "No files selected to redact.", Toast.LENGTH_SHORT).show()
            return@redactInit
        }

        showRedactDialog = false

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

            // --- PERMISSION GATE & MODEL CHECK ---
            PermissionCheckAndGate(
                activity = activity,
                isModelReady = isNerModelReady,
                onReady = { areModelsAndPermissionsReady = true }
            )

            if (!areModelsAndPermissionsReady) {
                // Show a clean loading indicator while permissions are being asked or models load
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Awaiting Permissions & AI Model Initialization...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                // FileListingContent runs ONLY when models and permissions are ready
                FileListingContent(
                    context = context,
                    decodedStartPath = decodedStartPath,
                    currentPath = currentPath,
                    onPathChange = ::handlePathChange,
                    onFolderLongPress = { file ->
                        selectedFolder = file
                        redactionOptions = analyzeFolderForRedaction(file)
                        showRedactDialog = true
                    },
                    // Pass null progress here, as the dialog handles the main progress state
                    redactionProgress = null,
                    filesInCurrentDir = filesInCurrentDir,
                    onFilesUpdate = { filesInCurrentDir = it },
                    isLoading = isLoading,
                    onLoadingChange = { isLoading = it }
                )
            }
        }

        // Redaction Progress Dialog (MODERNIZED)
        if (isRedactionRunning && redactionProgressState != null) {
            RedactionProgressDialog(
                progress = redactionProgressState!!,
                folderName = selectedFolder?.name ?: "Folder"
            )
        }

        // Redaction Confirmation Dialog
        if (showRedactDialog && selectedFolder != null) {
            RedactionDialog(
                folder = selectedFolder!!,
                options = redactionOptions,
                onDismiss = { showRedactDialog = false },
                onRedactNow = startRedaction
            )
        }
    }
}