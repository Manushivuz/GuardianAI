package com.dsatm.guardianai.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a record linking a publicly available redacted file's hash
 * to its original, encrypted file name stored internally.
 */
@Entity(tableName = "redacted_mapping")
data class RedactedFileEntity(
    // The secure lookup key: SHA-256 hash of the redacted file content.
    @PrimaryKey val redactedFileHash: String,

    // The unique name of the original file as stored in app's internal storage (context.filesDir)
    val encryptedFileName: String,

    // The last known absolute path (for context/logging, not for security lookup)
    val redactedFilePath: String,

    // Timestamp for reference/cleanup
    val timestamp: Long = System.currentTimeMillis()
)