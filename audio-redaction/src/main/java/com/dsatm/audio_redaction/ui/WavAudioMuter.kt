package com.dsatm.audio_redaction.ui

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import kotlin.math.max
import kotlin.math.min
import java.nio.ByteBuffer
/**
 * WavAudioMuter
 *
 * - Processes 16-bit PCM WAV files.
 * - Mutes (zeros) only the specified time ranges (milliseconds).
 *
 * processAudio(inputFile, outputFile, muteRangesMs)
 *  - inputFile: WAV file readable by FileInputStream
 *  - outputFile: destination WAV file (will be overwritten)
 *  - muteRangesMs: List of (startMs, endMs) pairs (can be unsorted / overlapping)
 *
 * Returns true on success, false on failure (and logs error).
 */
class WavAudioMuter {

    private val TAG = "WavAudioMuter"
    private val BUFFER_SIZE = 32 * 1024

    suspend fun processAudio(
        inputFile: File,
        outputFile: File,
        muteRangesMs: List<Pair<Long, Long>>
    ): Boolean = withContext(Dispatchers.IO) {
        if (!inputFile.exists()) {
            Log.e(TAG, "Input file does not exist: ${inputFile.absolutePath}")
            return@withContext false
        }

        // Normalize and merge ranges (ms)
        val ranges = mergeRanges(muteRangesMs)

        var input: RandomAccessFile? = null
        var out: FileOutputStream? = null
        try {
            input = RandomAccessFile(inputFile, "r")
            // Parse WAV header to find 'data' chunk offset and ensure 16-bit PCM
            val header = parseWavHeader(input) ?: run {
                Log.e(TAG, "Unsupported or corrupted WAV header.")
                return@withContext false
            }

            if (header.audioFormat != 1) {
                Log.e(TAG, "Unsupported WAV audio format (only PCM=1 supported). Found audioFormat=${header.audioFormat}")
                return@withContext false
            }
            if (header.bitsPerSample != 16) {
                Log.e(TAG, "Unsupported bitsPerSample (only 16-bit supported). Found ${header.bitsPerSample}")
                return@withContext false
            }

            // Prepare streams
            out = FileOutputStream(outputFile)
            // Copy header bytes (everything before dataStart)
            input.seek(0)
            val headerBytes = ByteArray(header.dataStart)
            input.readFully(headerBytes)
            out.write(headerBytes)

            val sampleRate = header.sampleRate
            val channels = header.numChannels
            val bytesPerSample = header.bitsPerSample / 8
            val bytesPerMs = (sampleRate * channels * bytesPerSample) / 1000.0

            // Stream audio data and zero the regions overlapping ranges
            val totalDataBytes = header.dataChunkSize
            var bytesProcessed: Long = 0
            val buf = ByteArray(BUFFER_SIZE)

            input.seek(header.dataStart.toLong())
            while (bytesProcessed < totalDataBytes) {
                val toRead = min(BUFFER_SIZE.toLong(), totalDataBytes - bytesProcessed).toInt()
                val actuallyRead = input.read(buf, 0, toRead)
                if (actuallyRead <= 0) break

                // For the current chunk compute absolute byte range [chunkStartByte, chunkEndByte)
                val chunkStartByte = bytesProcessed
                val chunkEndByte = bytesProcessed + actuallyRead

                // Create a mutable copy (we'll zero bytes in-place)
                val outBuf = buf.copyOf(actuallyRead)

                // For each mute range, compute overlap in bytes and zero
                for ((msStart, msEnd) in ranges) {
                    val muteStartByte = ((msStart.toDouble()) * bytesPerMs).toLong()
                    val muteEndByte = ((msEnd.toDouble()) * bytesPerMs).toLong()

                    val overlapStart = max(chunkStartByte, muteStartByte)
                    val overlapEnd = min(chunkEndByte, muteEndByte)
                    if (overlapStart < overlapEnd) {
                        // zero bytes from (overlapStart - chunkStartByte) .. (overlapEnd - chunkStartByte - 1)
                        val zeroFrom = (overlapStart - chunkStartByte).toInt()
                        val zeroToExcl = (overlapEnd - chunkStartByte).toInt()
                        // Safety clamp
                        val zFrom = zeroFrom.coerceIn(0, outBuf.size)
                        val zTo = zeroToExcl.coerceIn(0, outBuf.size)
                        if (zFrom < zTo) {
                            for (i in zFrom until zTo) outBuf[i] = 0
                        }
                    }
                }

                // Write processed chunk
                out.write(outBuf)
                bytesProcessed += actuallyRead
            }

            out.flush()
            Log.i(TAG, "WAV mute succeeded. Output: ${outputFile.absolutePath}")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "WavAudioMuter failed: ${e.message}", e)
            return@withContext false
        } finally {
            try { out?.close() } catch (_: Throwable) {}
            try { input?.close() } catch (_: Throwable) {}
        }
    }

    // Merge overlapping and sort ranges (ms)
    private fun mergeRanges(ranges: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<Pair<Long, Long>>()
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= curEnd) {
                curEnd = maxOf(curEnd, e)
            } else {
                merged.add(curStart to curEnd)
                curStart = s
                curEnd = e
            }
        }
        merged.add(curStart to curEnd)
        return merged
    }

    // WAV header info container
    private data class WavHeader(
        val dataStart: Int,         // offset in bytes where data begins
        val dataChunkSize: Long,    // bytes in data chunk
        val sampleRate: Int,
        val numChannels: Int,
        val bitsPerSample: Int,
        val audioFormat: Int
    )

    /**
     * Parse WAV header to find 'data' chunk offset, sampleRate, channels etc.
     * Returns null if header is invalid/unrecognized.
     */
    private fun parseWavHeader(r: RandomAccessFile): WavHeader? {
        try {
            r.seek(0)
            val riff = ByteArray(4)
            r.readFully(riff)
            if (!String(riff, Charsets.US_ASCII).equals("RIFF", ignoreCase = true)) return null

            r.skipBytes(4) // file size
            val wave = ByteArray(4)
            r.readFully(wave)
            if (!String(wave, Charsets.US_ASCII).equals("WAVE", ignoreCase = true)) return null

            var fmtFound = false
            var dataFound = false

            var audioFormat = 1
            var numChannels = 1
            var sampleRate = 44100
            var bitsPerSample = 16
            var dataStart = -1
            var dataSize: Long = -1

            // Iterate chunks
            while (true) {
                val chunkIdBytes = ByteArray(4)
                if (r.filePointer + 8 > r.length()) break
                r.readFully(chunkIdBytes)
                val chunkId = String(chunkIdBytes, Charsets.US_ASCII)
                val chunkSize = Integer.reverseBytes(r.readInt()).toLong() and 0xFFFFFFFFL
                val chunkDataPos = r.filePointer

                when (chunkId) {
                    "fmt " -> {
                        fmtFound = true
                        // read fmt chunk (PCM or other)
                        val fmtChunkData = ByteArray(chunkSize.toInt())
                        r.readFully(fmtChunkData)
                        val b = ByteBuffer.wrap(fmtChunkData).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        audioFormat = b.short.toInt() and 0xFFFF
                        numChannels = b.short.toInt() and 0xFFFF
                        sampleRate = b.int
                        b.int // byteRate
                        b.short // blockAlign
                        bitsPerSample = b.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        dataFound = true
                        dataStart = chunkDataPos.toInt()
                        dataSize = chunkSize
                        // Move file pointer past data chunk (we'll handle reading later)
                        r.seek(chunkDataPos + chunkSize)
                    }
                    else -> {
                        // skip unknown chunk
                        r.seek(chunkDataPos + chunkSize)
                    }
                }
            }

            if (!fmtFound || !dataFound) {
                Log.e(TAG, "fmt or data chunk not found in WAV")
                return null
            }

            return WavHeader(
                dataStart = dataStart,
                dataChunkSize = dataSize,
                sampleRate = sampleRate,
                numChannels = numChannels,
                bitsPerSample = bitsPerSample,
                audioFormat = audioFormat
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WAV header: ${e.message}", e)
            return null
        }
    }
}
