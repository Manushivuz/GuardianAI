package com.dsatm.guardianai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityManager(private val context: Context) {

    // --- KEY ALIASES ---
    // Single key alias used for ALL crypto operations.
    private val AUTH_KEY_ALIAS = "app_auth_key"

    private val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val TRANSFORMATION = "AES/GCM/NoPadding"
    val IV_SIZE = 12
    private val TAG = "SecurityManager"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Retrieves or creates the single key used for ALL encryption/decryption.
     * **NOTE: The key is created WITHOUT mandatory user authentication for background I/O.**
     */
    fun getOrCreateAuthKey(): SecretKey {
        return try {
            val existingKey = keyStore.getEntry(AUTH_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            // FALSE: No authentication required for background use
            existingKey?.secretKey ?: createKey(AUTH_KEY_ALIAS, false)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }


    private fun createKey(alias: String, requiresAuth: Boolean): SecretKey {
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // *** CRITICAL FIX: Removing mandatory user authentication for the key itself ***
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }

    fun isBiometricReady(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows a biometric prompt for simple authentication without a cryptographic object.
     */
    fun authenticateForAppAccess(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (errorMessage: CharSequence) -> Unit
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("App Access")
            .setSubtitle("Authenticate to open the app")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use Password")
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onFailure(errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onFailure("Authentication failed. Please try again.")
                }
            }
        )
        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Shows a biometric prompt to authenticate the user for a cryptographic operation.
     * The Cipher is passed to the biometric prompt only to maintain the UX, but the key is not locked.
     */
    fun showBiometricPrompt(
        activity: FragmentActivity,
        data: ByteArray,
        mode: Int,
        iv: ByteArray?,
        onSuccess: (resultData: ByteArray, newIv: ByteArray?) -> Unit,
        onFailure: (errorMessage: CharSequence) -> Unit
    ) {
        // Capture 'mode' locally to ensure stability for the inner class
        val finalMode = mode

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Authentication")
            .setSubtitle("Authenticate to access secure data")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onFailure(errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val cryptoObject = result.cryptoObject
                    val cipher = cryptoObject?.cipher

                    if (cipher != null) {
                        try {
                            // --- DEBUG LOGS START ---
                            Log.d(TAG, "Biometric success. Attempting cipher.doFinal()")
//                            Log.d(TAG, "Cipher mode: ${cipher.mode}")
                            Log.d(TAG, "Input data size: ${data.size} bytes")
                            // --- DEBUG LOGS END ---

                            val resultData = cipher.doFinal(data)

                            Log.d(TAG, "Cipher.doFinal succeeded. Output size: ${resultData.size} bytes")

                            val newIv = if (finalMode == Cipher.ENCRYPT_MODE) cipher.iv else iv
                            onSuccess(resultData, newIv)
                        } catch (e: Exception) {
                            Log.e(TAG, "Crypto operation FAILED after biometric success.", e)
                            onFailure("Error performing crypto operation: ${e.message ?: "null"}")
                        }
                    } else {
                        onFailure("CryptoObject or Cipher is null")
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onFailure("Authentication failed. Please try again.")
                }
            }
        )

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            try {
                if (mode == Cipher.ENCRYPT_MODE) {
                    init(Cipher.ENCRYPT_MODE, getOrCreateAuthKey())
                } else {
                    if (iv == null || iv.size != IV_SIZE) {
                        throw InvalidAlgorithmParameterException("Invalid IV provided for decryption.")
                    }
                    init(Cipher.DECRYPT_MODE, getOrCreateAuthKey(), GCMParameterSpec(128, iv))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
