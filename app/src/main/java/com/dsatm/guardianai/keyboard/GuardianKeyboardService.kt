package com.dsatm.guardianai.keyboard

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GuardianKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private val TAG = "GuardianKeyboardService"

    private lateinit var keyboardView: KeyboardView
    private lateinit var keyboard: Keyboard
    private var caps = false
    private lateinit var nerManager: com.dsatm.ner.BertNerOnnxManager
    private lateinit var clipboardManager: ClipboardManager
    private var isModelInitialized: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Keyboard Service Created")

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        nerManager = com.dsatm.ner.BertNerOnnxManager(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                nerManager.initialize()
                isModelInitialized = true
                Log.d(TAG, "NER Model initialized successfully")
            } catch (e: Exception) {
                isModelInitialized = false
                Log.e(TAG, "NER Model initialization failed", e)
            }
        }
    }

    override fun onCreateInputView(): View {
        // TEMPORARY FIX: Use resource ID directly instead of R.xml.keyboard_layout
        // The resource ID for XML files is typically 0x7f0f0000 + your file number
        // We'll use a try-catch approach
        return try {
            // Method 1: Try to create keyboard with resource name
            keyboard = Keyboard(this, resources.getIdentifier("keyboard_layout", "xml", packageName))
            keyboardView = KeyboardView(this, null)
            keyboardView.keyboard = keyboard
            keyboardView.setOnKeyboardActionListener(this)
            keyboardView.isPreviewEnabled = false
            keyboardView
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create keyboard view: ${e.message}")
            // Fallback: Create a simple empty view
            KeyboardView(this, null).apply {
                keyboard = Keyboard(this@GuardianKeyboardService, 0) // Empty keyboard
                setOnKeyboardActionListener(this@GuardianKeyboardService)
            }
        }
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray) {
        val inputConnection: InputConnection = currentInputConnection ?: return

        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                inputConnection.deleteSurroundingText(1, 0)
            }
            Keyboard.KEYCODE_SHIFT -> {
                caps = !caps
                keyboard.isShifted = caps
                keyboardView.invalidateAllKeys()
            }
            Keyboard.KEYCODE_DONE -> {
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            }
            -10 -> { // Custom code for PASTE & MASK
                pasteAndMaskPII(inputConnection)
            }
            else -> {
                var code = primaryCode.toChar()
                if (Character.isLetter(code) && caps) {
                    code = Character.toUpperCase(code)
                }
                inputConnection.commitText(code.toString(), 1)
            }
        }
    }

    private fun pasteAndMaskPII(inputConnection: InputConnection) {
        try {
            val clip = clipboardManager.primaryClip ?: return
            if (clip.itemCount == 0) return

            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isBlank()) return

            if (!isModelInitialized) {
                Log.w(TAG, "Model not ready - pasting raw text")
                inputConnection.commitText(text, 1)
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val entities = nerManager.detectPii(text)
                    val maskedText = nerManager.postProcessor.redactText(text, entities)

                    launch(Dispatchers.Main) {
                        inputConnection.commitText(maskedText, 1)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Masking failed", e)
                    launch(Dispatchers.Main) {
                        inputConnection.commitText(text, 1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Paste failed", e)
        }
    }

    // Required override methods
    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence) {}
    override fun swipeDown() {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeUp() {}

    override fun onDestroy() {
        super.onDestroy()
        if (::nerManager.isInitialized) {
            nerManager.close()
        }
    }
}