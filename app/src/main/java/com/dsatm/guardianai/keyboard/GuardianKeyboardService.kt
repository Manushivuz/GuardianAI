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
import com.dsatm.ner.TextBertNerOnnxManager
import android.view.inputmethod.EditorInfo
import android.view.ContextThemeWrapper
import android.content.res.Resources // NEW IMPORT

class GuardianKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private val TAG = "GuardianKeyboardService"

    private lateinit var keyboardView: KeyboardView
    private var qwertyKeyboard: Keyboard? = null
    private var symbolsKeyboard: Keyboard? = null
    private var currentKeyboard: Keyboard? = null

    private var caps = false
    private lateinit var textNerManager: TextBertNerOnnxManager
    private lateinit var clipboardManager: ClipboardManager
    private var isModelInitialized: Boolean = false

    private val CODE_SWITCH_TO_SYMBOLS = -2
    private val CODE_SWITCH_TO_QWERTY = -3
    private val CODE_PASTE_MASK = -10

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Keyboard Service Created")

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        textNerManager = TextBertNerOnnxManager(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                textNerManager.initialize()
                isModelInitialized = true
                Log.d(TAG, "Text NER Model initialized successfully")
            } catch (e: Exception) {
                isModelInitialized = false
                Log.e(TAG, "Text NER Model initialization failed", e)
            }
        }
    }

    override fun onCreateInputView(): View {
        // --- CRITICAL FIX START: Explicitly applying theme context for correct styling ---

        // 1. Get the ID of the custom Keyboard Service Theme (defined in styles.xml)
        val themeId = resources.getIdentifier("Theme_GuardianKeyboardService", "style", packageName)

        // 2. Create a themed context wrapper using our custom theme ID
        val styledContext = if (themeId != 0) {
            ContextThemeWrapper(this, themeId)
        } else {
            // Fallback to default context if resource lookup fails
            this
        }

        // --- CRITICAL FIX END ---

        return try {
            // Load key layouts using the base service context, but instantiate the view with the styled context.
            // This is the common pattern to get styling inheritance working.
            if (qwertyKeyboard == null) {
                val qwertyId = resources.getIdentifier("keyboard_layout", "xml", packageName)
                qwertyKeyboard = Keyboard(this, qwertyId)
            }
            if (symbolsKeyboard == null) {
                val symbolsId = resources.getIdentifier("keyboard_symbols", "xml", packageName)
                symbolsKeyboard = Keyboard(this, symbolsId)
            }

            // Instantiate KeyboardView using the styled context
            keyboardView = KeyboardView(styledContext, null).apply {
                keyboard = qwertyKeyboard
                currentKeyboard = qwertyKeyboard
                setOnKeyboardActionListener(this@GuardianKeyboardService)
                isPreviewEnabled = false
            }
            keyboardView
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Failed to create keyboard view or load XMLs.", e)
            // Fallback
            KeyboardView(this, null).apply {
                keyboard = Keyboard(this@GuardianKeyboardService, 0)
                setOnKeyboardActionListener(this@GuardianKeyboardService)
            }
        }
    }

    private fun switchKeyboard(newKeyboard: Keyboard) {
        currentKeyboard = newKeyboard
        keyboardView.keyboard = newKeyboard
        caps = false
        newKeyboard.isShifted = false
        keyboardView.invalidateAllKeys()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (currentKeyboard == null || currentKeyboard != qwertyKeyboard) {
            switchKeyboard(qwertyKeyboard ?: return)
        }
        caps = false
        qwertyKeyboard?.isShifted = false
        keyboardView.invalidateAllKeys()
    }


    override fun onKey(primaryCode: Int, keyCodes: IntArray) {
        val inputConnection: InputConnection = currentInputConnection ?: return

        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                inputConnection.deleteSurroundingText(1, 0)
            }
            Keyboard.KEYCODE_SHIFT -> {
                caps = !caps
                currentKeyboard?.isShifted = caps
                keyboardView.invalidateAllKeys()
            }
            Keyboard.KEYCODE_DONE -> {
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            }
            CODE_PASTE_MASK -> {
                pasteAndMaskPII(inputConnection)
            }
            CODE_SWITCH_TO_SYMBOLS -> {
                switchKeyboard(symbolsKeyboard ?: return)
            }
            CODE_SWITCH_TO_QWERTY -> {
                switchKeyboard(qwertyKeyboard ?: return)
            }
            else -> {
                var code = primaryCode.toChar()
                if (Character.isLetter(code) && caps) {
                    code = Character.toUpperCase(code)
                }
                inputConnection.commitText(code.toString(), 1)

                if (caps && currentKeyboard?.isShifted == true) {
                    caps = false
                    currentKeyboard?.isShifted = false
                    keyboardView.invalidateAllKeys()
                }
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
                    val entities = textNerManager.detectPiiForText(text)
                    val maskedText = textNerManager.textPostProcessor.applyClipboardRedaction(text, entities)

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
        if (::textNerManager.isInitialized) {
            textNerManager.close()
        }
    }
}