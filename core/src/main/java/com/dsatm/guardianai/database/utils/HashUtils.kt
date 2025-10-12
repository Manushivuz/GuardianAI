package com.dsatm.guardianai.database.utils

import java.security.MessageDigest

object HashUtils {

    /**
     * Generates a SHA-256 hash of a ByteArray.
     * This hash serves as the secure, unique identifier for the redacted file content.
     */
    fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)

        // Convert the byte array hash to a hexadecimal string
        return hash.fold("") { str, it -> str + "%02x".format(it) }
    }
}