package com.dsatm.guardianai.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.dsatm.guardianai.security.EncryptedFileService
import com.dsatm.guardianai.security.FileManagementService
import com.dsatm.guardianai.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// Tag for logging messages in Logcat
private const val TAG = "CryptoDemoScreen"

@Composable
fun CryptoDemoScreen(activity: FragmentActivity) {
    val context: Context = LocalContext.current
    val securityManager = remember { SecurityManager(context) }
    // Initialize EncryptedFileService using the MasterKey from SecurityManager
    val encryptedFileService = remember { EncryptedFileService(context, securityManager.masterKey) }
    // Initialize the new FileManagementService, passing the EncryptedFileService
    val fileManagementService = remember { FileManagementService(context, encryptedFileService) }

    // State for managing UI text and status updates
    var statusText by remember { mutableStateOf("Ready to process files.") }
    var encryptedFile by remember { mutableStateOf<File?>(null) }
    var originalFileName by remember { mutableStateOf<String?>(null) }

    // State for data that needs permission check before saving
    var decryptedDataForSave by remember { mutableStateOf<ByteArray?>(null) }
    var decryptedFileNameForSave by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    // Launcher for requesting storage permissions (used before saving decrypted data to public storage)
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "Storage permission granted. Proceeding with file save.")
            decryptedDataForSave?.let { data ->
                decryptedFileNameForSave?.let { name ->
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            // Use the core service to save the file
                            fileManagementService.saveRedactedFile(name, data)
                            statusText = "File saved to Downloads as '$name'."
                            Log.d(TAG, "Successfully saved decrypted file to external storage.")
                        } catch (e: Exception) {
                            statusText = "Failed to save decrypted file: ${e.message}"
                            Log.e(TAG, "Failed to save file", e)
                        } finally {
                            // Clear state variables after the operation is complete
                            decryptedDataForSave = null
                            decryptedFileNameForSave = null
                        }
                    }
                }
            }
        } else {
            statusText = "Permission denied. Cannot save file to public storage."
            Log.w(TAG, "Storage permission denied by user.")
        }
    }

    // File picker launcher for encryption
    val encryptFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            Log.d(TAG, "File picker result received for encryption. Uri: $uri")
            if (uri != null) {
                coroutineScope.launch {
                    statusText = "Processing file... Please wait."
                    withContext(Dispatchers.IO) {
                        try {
                            val name = getFileName(context, uri)
                            val nameForEncryptedFile = "$name.enc" // Simplified name for internal file

                            Log.d(TAG, "Selected file: $name. Encrypted file name will be: $nameForEncryptedFile")

                            // Use the new FileManagementService to handle reading and encrypting in one step.
                            // The returned 'originalData' would be passed to your redaction modules later.
                            val originalData = fileManagementService.processAndEncryptOriginalFile(
                                sourceUri = uri,
                                originalFileName = nameForEncryptedFile
                            )

                            // *** IMPORTANT: This is where you would call your Redaction Modules ***
                            // val redactedData = imageRedactionModule.redact(originalData)
                            // fileManagementService.saveRedactedFile("$name.redacted", redactedData)

                            // For this demo, we skip redaction and just complete the encryption process.

                            // Store the File object and original name for later decryption
                            // Note: You must retrieve the File object from the internal storage path later,
                            // but for this simple demo, we'll construct the File path explicitly.
                            val savedInternalFile = File(context.filesDir, nameForEncryptedFile)
                            encryptedFile = savedInternalFile
                            originalFileName = name // The original, unencrypted file name

                            statusText = "File '$name' encrypted and saved to app's private storage."
                            Log.d(TAG, "Encryption complete. Encrypted file stored at: ${encryptedFile?.absolutePath}")

                        } catch (e: IOException) {
                            statusText = "File processing failed: ${e.message}"
                            Log.e(TAG, "File processing failed due to IO Exception", e)
                        } catch (e: Exception) {
                            statusText = "File processing failed: ${e.message}"
                            Log.e(TAG, "File processing failed", e)
                        }
                    }
                }
            } else {
                statusText = "No file selected for encryption."
                Log.d(TAG, "User canceled file selection for encryption.")
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = statusText, modifier = Modifier.padding(bottom = 16.dp))

        Button(
            onClick = { encryptFileLauncher.launch("*/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "1. Select File (Encrypt & Save Original)")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                encryptedFile?.let { file ->
                    Log.d(TAG, "Decrypt button clicked. Encrypted file path: ${file.absolutePath}")
                    coroutineScope.launch {
                        statusText = "Decrypting file... Please wait."
                        withContext(Dispatchers.IO) {
                            try {
                                val decryptedFileName = originalFileName ?: "decrypted_file"

                                // Use the FileManagementService to decrypt the file
                                val decryptedData = fileManagementService.decryptAndAccessOriginalFile(file)

                                // Store the data and file name in state for the permission launcher callback
                                decryptedDataForSave = decryptedData
                                decryptedFileNameForSave = "DECRYPTED_$decryptedFileName" // Prepend to avoid overwriting

                                // Check for permissions before saving
                                // WRITE_EXTERNAL_STORAGE is required on older APIs (pre-Q) to write to Downloads
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                    Log.d(TAG, "Permissions already granted or not needed. Saving file directly.")

                                    // Use the core service to save the decrypted data to external storage
                                    fileManagementService.saveRedactedFile(decryptedFileNameForSave!!, decryptedDataForSave!!)

                                    statusText = "File '${file.name}' decrypted and saved to Downloads as '${decryptedFileNameForSave}'."
                                    decryptedDataForSave = null
                                    decryptedFileNameForSave = null
                                } else {
                                    Log.d(TAG, "Permissions needed. Requesting now.")
                                    statusText = "Permission needed to save file. Please grant permission."
                                    // Request permissions via the launcher
                                    withContext(Dispatchers.Main) {
                                        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }
                                }
                            } catch (e: Exception) {
                                statusText = "Decryption failed: ${e.message}"
                                Log.e(TAG, "Decryption failed", e)
                            }
                        }
                    }
                } ?: run {
                    statusText = "No encrypted file to decrypt. Please encrypt one first."
                    Log.d(TAG, "Decryption attempted but no encrypted file was found.")
                }
            },
            enabled = encryptedFile != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "2. Decrypt & Save Original to Downloads")
        }
    }
}

/**
 * A utility function to get the file name from a Uri. (Kept for resolving original file name)
 */
private fun getFileName(context: Context, uri: Uri): String {
    Log.d(TAG, "Attempting to get file name from Uri: $uri")
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    result = it.getString(nameIndex)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path?.split('/')?.last()
    }
    val finalName = result ?: "unnamed_file"
    Log.d(TAG, "Resolved file name: $finalName")
    return finalName
}
