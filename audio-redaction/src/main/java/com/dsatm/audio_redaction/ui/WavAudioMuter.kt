// File: com.dsatm.audio_redaction.ui/WavAudioMuter.kt (FINAL INTEGRITY FIX)

package com.dsatm.audio_redaction.ui

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.max

private const val TAG = "WavAudioMuter"

class WavAudioMuter {

    fun processAudio(
        inputWav: File,
        outputWav: File,
        muteRangesMs: List<Pair<Long, Long>>
    ): Boolean {
        if (!inputWav.exists()) {
            Log.e(TAG, "Input file does not exist: ${inputWav.absolutePath}")
            return false
        }

        var rafIn: RandomAccessFile? = null
        var fosOut: FileOutputStream? = null

        return try {
            val header = parseWavHeader(inputWav) ?: return false

            if (header.bitsPerSample != 16 || header.audioFormat != 1) {
                Log.e(TAG, "Unsupported WAV format: must be PCM16")
                return false
            }

            // --- 1. Calculate parameters and merge ranges ---
            val bytesPerMs = (header.sampleRate * header.numChannels * header.bitsPerSample) / 8.0 / 1000.0
            val ranges = mergeRanges(muteRangesMs)

            // --- 2. Initialize Streams ---
            rafIn = RandomAccessFile(inputWav, "r")
            fosOut = FileOutputStream(outputWav)

            // --- 3. Write Header to Output ---
            // Read and write the entire header chunk (up to dataStart) explicitly.
            rafIn.seek(0)
            val headerBytes = ByteArray(header.dataStart)
            rafIn.readFully(headerBytes)
            fosOut.write(headerBytes)
            Log.d(TAG, "Wrote WAV header (${header.dataStart} bytes) to output file.")

            // --- 4. Stream Data and Mute ---
            rafIn.seek(header.dataStart.toLong()) // Start reading from audio data

            val totalDataBytes = header.dataSize
            var bytesProcessed: Long = 0
            val buffer = ByteArray(4096)

            while (bytesProcessed < totalDataBytes) {
                val toRead = min(buffer.size.toLong(), totalDataBytes - bytesProcessed).toInt()
                val actuallyRead = rafIn.read(buffer, 0, toRead)
                if (actuallyRead <= 0) break

                // Process the buffer to apply mutes
                val outBuffer = buffer.copyOf(actuallyRead)
                val chunkStartByte = bytesProcessed

                for ((msStart, msEnd) in ranges) {
                    val muteStartByte = (msStart * bytesPerMs).toLong()
                    val muteEndByte = (msEnd * bytesPerMs).toLong()

                    val overlapStart = max(chunkStartByte, muteStartByte)
                    val overlapEnd = min(chunkStartByte + actuallyRead, muteEndByte)

                    if (overlapStart < overlapEnd) {
                        // Calculate indices relative to the current buffer
                        val zeroFrom = (overlapStart - chunkStartByte).toInt()
                        val zeroToExcl = (overlapEnd - chunkStartByte).toInt()

                        // CRITICAL: Ensure 16-bit alignment for zeroing
                        val alignedZeroFrom = if (zeroFrom % 2 != 0) zeroFrom + 1 else zeroFrom
                        val alignedZeroToExcl = if (zeroToExcl % 2 != 0) zeroToExcl - 1 else zeroToExcl

                        if (alignedZeroFrom < alignedZeroToExcl) {
                            for (i in alignedZeroFrom until alignedZeroToExcl) {
                                outBuffer[i] = 0
                            }
                        }
                    }
                }

                fosOut.write(outBuffer)
                bytesProcessed += actuallyRead
            }

            // --- 5. Success ---
            Log.d(TAG, "Successfully processed ${bytesProcessed} data bytes.")
            true

        } catch (e: Exception) {
            Log.e(TAG, "processAudio failed due to stream error or data corruption.", e)
            false
        } finally {
            // --- 6. Cleanup ---
            try { fosOut?.close() } catch (_: Exception) {}
            try { rafIn?.close() } catch (_: Exception) {}
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
        val audioFormat: Int,
        val numChannels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataStart: Int,
        val dataSize: Int
    )

    /**
     * Parse WAV header to find 'data' chunk offset, sampleRate, channels etc.
     */
    private fun parseWavHeader(file: File): WavHeader? {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4)
            if (raf.read(riff) != 4 || String(riff) != "RIFF") return null

            raf.skipBytes(4)

            val wave = ByteArray(4)
            if (raf.read(wave) != 4 || String(wave) != "WAVE") return null

            var fmtFound = false
            var dataFound = false

            var audioFormat = 1
            var channels = 1
            var sampleRate = 16000
            var bits = 16
            var dataPos = -1
            var dataSize = -1

            while (raf.filePointer < raf.length()) {
                val id = ByteArray(4)
                if (raf.read(id) != 4) break
                val chunkId = String(id)

                val size = readIntLE(raf)
                val chunkStart = raf.filePointer

                when (chunkId) {
                    "fmt " -> {
                        audioFormat = readShortLE(raf)
                        channels = readShortLE(raf)
                        sampleRate = readIntLE(raf)
                        raf.skipBytes(6)
                        bits = readShortLE(raf)
                        fmtFound = true
                    }

                    "data" -> {
                        dataPos = raf.filePointer.toInt()
                        dataSize = size
                        dataFound = true
                    }
                }

                // Skip to the next chunk boundary
                raf.seek(chunkStart + size + (size % 2))
                if (fmtFound && dataFound) break
            }

            return if (fmtFound && dataFound)
                WavHeader(audioFormat, channels, sampleRate, bits, dataPos, dataSize)
            else null
        }
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        val b4 = raf.read()
        return (b1 and 0xFF) or
                ((b2 and 0xFF) shl 8) or
                ((b3 and 0xFF) shl 16) or
                ((b4 and 0xFF) shl 24)
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        return (b1 and 0xFF) or
                ((b2 and 0xFF) shl 8)
    }
}