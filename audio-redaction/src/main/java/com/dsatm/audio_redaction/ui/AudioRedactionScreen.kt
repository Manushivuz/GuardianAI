package com.dsatm.audio_redaction.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsatm.ner.BertNerOnnxManager
import com.dsatm.ner.PiiEntity
import com.dsatm.ner.mapPiiToTimeRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

// --- UI State ---
sealed class AudioRedactionUiState {
    object Initial : AudioRedactionUiState()
    object LoadingModel : AudioRedactionUiState()
    object Ready : AudioRedactionUiState()
    object Transcribing : AudioRedactionUiState()
    object AnalyzingPii : AudioRedactionUiState()
    object MutingAudio : AudioRedactionUiState()
    data class Completed(
        val originalUri: Uri,
        val rawTranscript: String,
        val cleanTranscript: String,
        val entities: List<PiiEntity>,
        val mutedAudioFile: File?
    ) : AudioRedactionUiState()
    data class Error(val message: String) : AudioRedactionUiState()
}

// --- Compose Screen ---
@Composable
fun AudioRedactionScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val audioManager = remember { AudioRedactionManager(context) }
    val nerManager = remember { BertNerOnnxManager(context) }
    val audioMuter = remember { WavAudioMuter() }

    var uiState by remember { mutableStateOf<AudioRedactionUiState>(AudioRedactionUiState.LoadingModel) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    // --- Save file launcher for WAV ---
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri: Uri? ->
        val currentState = uiState
        if (uri != null && currentState is AudioRedactionUiState.Completed && currentState.mutedAudioFile != null) {
            coroutineScope.launch(Dispatchers.IO) {
                saveMutedFile(context, currentState.mutedAudioFile, uri)
            }
        } else {
            Toast.makeText(context, "Save cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Cleanup MediaPlayer ---
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // --- Playback function ---
    fun togglePlayback(audioFile: File) {
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setOnPreparedListener {
                    it.start()
                    isPlaying = true
                }
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    isPlaying = false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("Playback", "Playback failed: ${e.message}")
            Toast.makeText(context, "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- Audio picker launcher ---
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            if (uiState !is AudioRedactionUiState.Ready && uiState !is AudioRedactionUiState.Completed) return@let
            uiState = AudioRedactionUiState.Transcribing

            audioManager.transcribeAudioWithTimestamps(it) { rawTranscript ->
                if (rawTranscript.startsWith("Error") || rawTranscript.startsWith("Vosk model failed")) {
                    uiState = AudioRedactionUiState.Error("Transcription failed: $rawTranscript")
                } else {
                    coroutineScope.launch {
                        runPiiDetectionAndMuting(context, it, rawTranscript, nerManager, audioMuter) { resultState ->
                            uiState = resultState
                        }
                    }
                }
            }
        }
    }

    // --- Initialize models ---
    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                nerManager.initialize()
                while (!audioManager.isModelLoaded()) {
                    kotlinx.coroutines.delay(100)
                }
                uiState = AudioRedactionUiState.Ready
            } catch (e: Exception) {
                uiState = AudioRedactionUiState.Error("Model initialization failed: ${e.message}")
            }
        }
    }

    // --- UI Layout ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val isBusy = uiState is AudioRedactionUiState.Transcribing ||
                uiState is AudioRedactionUiState.AnalyzingPii ||
                uiState is AudioRedactionUiState.MutingAudio

        val isReady = uiState is AudioRedactionUiState.Ready || uiState is AudioRedactionUiState.Completed

        Button(
            onClick = { if (isReady) audioPickerLauncher.launch("audio/*") },
            enabled = isReady && !isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when (uiState) {
                    is AudioRedactionUiState.LoadingModel -> "Loading Models..."
                    is AudioRedactionUiState.Transcribing -> "Transcribing Audio..."
                    is AudioRedactionUiState.AnalyzingPii -> "Analyzing for PII..."
                    is AudioRedactionUiState.MutingAudio -> "Muting Audio..."
                    else -> "Pick Audio File (Start Redaction)"
                }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        when (uiState) {
            is AudioRedactionUiState.Initial, is AudioRedactionUiState.LoadingModel -> {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                Text("Initializing Transcription and NER Models...")
            }
            is AudioRedactionUiState.Ready -> {
                Text("Models ready. Pick an audio file to begin. 🎧", color = MaterialTheme.colorScheme.primary)
            }
            is AudioRedactionUiState.Transcribing, is AudioRedactionUiState.AnalyzingPii, is AudioRedactionUiState.MutingAudio -> {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                Text(
                    when (uiState) {
                        is AudioRedactionUiState.Transcribing -> "Decoding and Transcribing Audio..."
                        is AudioRedactionUiState.AnalyzingPii -> "Running PII Detection..."
                        is AudioRedactionUiState.MutingAudio -> "Processing and Muting Audio Segments..."
                        else -> ""
                    }
                )
            }
            is AudioRedactionUiState.Error -> {
                val errorMsg = (uiState as AudioRedactionUiState.Error).message
                Text("❌ ERROR: $errorMsg", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            is AudioRedactionUiState.Completed -> {
                val result = uiState as AudioRedactionUiState.Completed

                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Identified PII Entities (${result.entities.size}):",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (result.entities.isEmpty()) {
                            Text("✅ No PII found in the transcript.")
                        } else {
                            result.entities.forEach { pii ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "${pii.label}:",
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(100.dp)
                                    )
                                    Text(text = pii.text)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Clean Transcript:", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = highlightPii(result.cleanTranscript, result.entities))

                Spacer(modifier = Modifier.height(24.dp))
                Text("Audio Redaction:", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                if (result.mutedAudioFile != null && result.mutedAudioFile.exists()) {
                    val audioFile = result.mutedAudioFile
                    Text("✅ Audio Muting Complete. File: ${audioFile.name}", color = Color.Green.copy(alpha = 0.8f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { togglePlayback(audioFile) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause"
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (isPlaying) "Pause" else "Play Muted Audio")
                        }

                        Button(
                            onClick = { saveFileLauncher.launch("redacted_audio.wav") },
                            enabled = true
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Download")
                            Spacer(Modifier.width(8.dp))
                            Text("Download")
                        }
                    }
                } else {
                    Text(
                        "⚠️ Audio Muting Skipped or Failed. Check logs for details.",
                        color = Color.Red.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

// --- Pipeline ---
private suspend fun runPiiDetectionAndMuting(
    context: Context,
    originalUri: Uri,
    rawTranscript: String,
    nerManager: BertNerOnnxManager,
    audioMuter: WavAudioMuter,
    onStateUpdate: (AudioRedactionUiState) -> Unit
) {
    onStateUpdate(AudioRedactionUiState.AnalyzingPii)
    withContext(Dispatchers.IO) {
        try {
            val cleanTranscript = rawTranscript.replace(Regex("\\[[\\d.]+-[\\d.]+\\]"), "").trim()
            val entities = nerManager.detectPii(rawTranscript)

            val muteRangesMs = try {
                mapPiiToTimeRanges(rawTranscript, entities)
            } catch (e: Exception) {
                Log.w("AudioRedaction", "Failed to map PII to time ranges: ${e.message}")
                emptyList<Pair<Long, Long>>()
            }

            if (muteRangesMs.isEmpty()) {
                onStateUpdate(AudioRedactionUiState.Completed(originalUri, rawTranscript, cleanTranscript, entities, null))
                return@withContext
            }

            onStateUpdate(AudioRedactionUiState.MutingAudio)

            val inputFile = uriToFile(context, originalUri)
            val outputFile = File(context.cacheDir, "muted_audio_${System.currentTimeMillis()}.wav")

            val success = audioMuter.processAudio(inputFile, outputFile, muteRangesMs)
            val mutedFile = if (success) outputFile else null

            onStateUpdate(AudioRedactionUiState.Completed(originalUri, rawTranscript, cleanTranscript, entities, mutedFile))
        } catch (e: Exception) {
            Log.e("AudioRedactionScreen", "Pipeline failed", e)
            onStateUpdate(AudioRedactionUiState.Error("Redaction failed: ${e.message}"))
        }
    }
}

// --- Helpers ---
private fun uriToFile(context: Context, uri: Uri): File {
    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
    val tempFile = File(context.cacheDir, "temp_input_audio_${System.currentTimeMillis()}.wav")
    val outputStream = FileOutputStream(tempFile)
    inputStream?.copyTo(outputStream)
    outputStream.close()
    inputStream?.close()
    return tempFile
}

@Composable
private fun highlightPii(cleanTranscript: String, entities: List<PiiEntity>): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        append(cleanTranscript)
        entities.forEach { entity ->
            val start = entity.start
            val end = entity.end
            if (start in 0..cleanTranscript.length && end in start..cleanTranscript.length) {
                addStyle(
                    style = SpanStyle(background = Color.Yellow.copy(alpha = 0.4f), fontWeight = FontWeight.Bold),
                    start = start,
                    end = end
                )
            }
        }
    }
}

private fun saveMutedFile(context: Context, sourceFile: File, outputUri: Uri) {
    try {
        context.contentResolver.openOutputStream(outputUri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        }
        Toast.makeText(context, "Audio saved successfully!", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e("AudioRedactionScreen", "File save failed", e)
        Toast.makeText(context, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
