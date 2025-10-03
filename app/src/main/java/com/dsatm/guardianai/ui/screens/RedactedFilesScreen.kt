package com.dsatm.guardianai.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.dsatm.guardianai.security.EncryptedFileService
import com.dsatm.guardianai.security.FileManagementService
import com.dsatm.guardianai.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher

private val PrimaryBlue = Color(0xFF0288D1)
private const val TAG = "RedactedFilesScreen"

// Data structure to hold the decrypted file preview state
data class DecryptedFileState(
    val file: File,
    val bitmap: Bitmap? = null,
    val isShowing: Boolean = false,
    val decryptionError: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactedFilesScreen(activity: FragmentActivity) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- Service Initialization ---
    val securityManager = remember { SecurityManager(context) }
    // EncryptedFileService requires SecurityManager
    val encryptedFileService = remember { EncryptedFileService(context, securityManager) }
    val fileManagementService = remember { FileManagementService(context, encryptedFileService) }

    // State for the list of encrypted files (originals)
    var encryptedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // State to hold the result of decryption for display
    var decryptedFileState by remember { mutableStateOf<DecryptedFileState?>(null) }


    // Function to load the list of files from internal storage
    val loadEncryptedFiles: suspend () -> Unit = {
        isLoading = true
        withContext(Dispatchers.IO) {
            encryptedFiles = fileManagementService.listEncryptedFiles()
        }
        isLoading = false
    }

    // Load files on startup
    LaunchedEffect(Unit) {
        loadEncryptedFiles()
    }

    // --- Decryption Trigger Function ---
    fun startDecryption(file: File) {
        decryptedFileState = null // Hide previous preview

        // Step 1: Read the encrypted file into bytes (including IV)
        val encryptedDataWithIV: ByteArray = try {
            file.readBytes()
        } catch (e: Exception) {
            Toast.makeText(context, "Error reading encrypted file data.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error reading encrypted file bytes.", e)
            return
        }

        // --- VALIDATION AND EXTRACTION ---
        if (encryptedDataWithIV.size < securityManager.IV_SIZE) {
            Toast.makeText(context, "Error: Secured file is corrupt or too small.", Toast.LENGTH_LONG).show()
            Log.e(TAG, "File too small: Size=${encryptedDataWithIV.size}, Expected IV=${securityManager.IV_SIZE}")
            return
        }

        // Step 2: Extract IV and encrypted payload for BiometricPrompt
        val iv = encryptedDataWithIV.copyOfRange(0, securityManager.IV_SIZE)
        // The rest of the data is the encrypted payload (ciphertext)
        val encryptedPayload = encryptedDataWithIV.copyOfRange(securityManager.IV_SIZE, encryptedDataWithIV.size)

        Log.d(TAG, "Decryption attempt for ${file.name}: Total bytes=${encryptedDataWithIV.size}, Payload bytes=${encryptedPayload.size}, IV bytes=${iv.size}")
        // --- END VALIDATION AND EXTRACTION ---

        // Step 3: Show Biometric Prompt linked to the cryptographic operation
        securityManager.showBiometricPrompt(
            activity,
            data = encryptedPayload, // Pass ONLY the ciphertext
            mode = Cipher.DECRYPT_MODE,
            iv = iv,
            onSuccess = { resultData, _ ->
                // Biometric authentication succeeded, resultData is the decrypted image bytes
                coroutineScope.launch(Dispatchers.Default) {
                    val bitmap = try {
                        BitmapFactory.decodeByteArray(resultData, 0, resultData.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode decrypted bytes to Bitmap.", e)
                        null
                    }

                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            decryptedFileState = DecryptedFileState(file, bitmap, true)
                            Toast.makeText(context, "File unlocked successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Decryption success, but failed to display image.", Toast.LENGTH_LONG).show()
                            decryptedFileState = DecryptedFileState(file, decryptionError = "Image corrupted during encryption/decryption, or invalid format.")
                        }
                    }
                }
            },
            onFailure = { errorMessage ->
                Toast.makeText(context, "Decryption failed: $errorMessage", Toast.LENGTH_LONG).show()
                decryptedFileState = DecryptedFileState(file, decryptionError = errorMessage.toString())
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Redacted Files (Secure)", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryBlue,
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (encryptedFiles.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No original files have been secured yet.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "Original Files Secured in App Storage:",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Divider()
                    }
                    items(encryptedFiles, key = { it.absolutePath }) { file ->
                        EncryptedFileItem(file = file) {
                            startDecryption(file)
                        }
                    }
                }
            }
        }
    }

    // --- Decryption Result Dialog/Modal ---
    decryptedFileState?.let { state ->
        if (state.isShowing && state.bitmap != null) {
            DecryptedImageModal(state.file.name, state.bitmap) {
                decryptedFileState = null
            }
        } else if (state.decryptionError != null) {
            AlertDialog(
                onDismissRequest = { decryptedFileState = null },
                title = { Text("Decryption Failed") },
                text = { Text(state.decryptionError) },
                confirmButton = {
                    Button(onClick = { decryptedFileState = null }) { Text("OK") }
                }
            )
        }
    }
}

@Composable
fun EncryptedFileItem(file: File, onClick: () -> Unit) {
    val dateString = remember(file.lastModified()) {
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Encrypted",
                tint = PrimaryBlue,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Using MaterialTheme primary color for text contrast
                Text(
                    text = file.name,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Secured | $dateString",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Icon(
                imageVector = Icons.Default.Restore,
                contentDescription = "Decrypt",
                tint = PrimaryBlue
            )
        }
    }
}

@Composable
fun DecryptedImageModal(fileName: String, bitmap: Bitmap, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Decrypted Original: $fileName") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Display the decrypted bitmap
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Decrypted Original Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .padding(8.dp)
                )
                Text(
                    "This is the original, unsecured content.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Red
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
