package tw.junba.transcriber

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * R6 PCM16 WAV splitter. Splits long WAV files into resumable chunks without
 * transcoding. If the source is not a supported PCM16 WAV the caller can
 * simply fall back to one-shot whisper-android decoding.
 */
data class WhisperAudioChunk(
    val file: File,
    val offsetMs: Long,
    val temporary: Boolean,
    val durationMs: Long,
)

object WavChunker {
    private data class WavInfo(
        val channels: Int,
        val sampleRate: Int,
        val byteRate: Long,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long,
    )

    @JvmStatic
    fun splitIfPossible(context: Context, input: File, chunkMinutes: Int): List<WhisperAudioChunk> {
        if (!input.isFile || !input.name.lowercase().endsWith(".wav")) {
            return listOf(WhisperAudioChunk(input, 0L, false, -1L))
        }
        val info = try { readInfo(input) } catch (_: Throwable) { null }
            ?: return listOf(WhisperAudioChunk(input, 0L, false, -1L))
        if (info.bitsPerSample != 16 || info.byteRate <= 0 || info.blockAlign <= 0) {
            return listOf(WhisperAudioChunk(input, 0L, false, -1L))
        }
        val totalMs = info.dataSize * 1000L / info.byteRate
        val targetMs = chunkMinutes.coerceAtLeast(1) * 60_000L
        if (totalMs <= targetMs + 1000L) {
            return listOf(WhisperAudioChunk(input, 0L, false, totalMs))
        }

        val dir = File(context.cacheDir, "r6_whisper_chunks_${System.nanoTime()}")
        if (!dir.mkdirs()) error("無法建立 Whisper 暫存切段資料夾")
        val bytesPerChunkRaw = info.byteRate * chunkMinutes.coerceAtLeast(1) * 60L
        val bytesPerChunk = (bytesPerChunkRaw / info.blockAlign) * info.blockAlign
        val result = mutableListOf<WhisperAudioChunk>()

        RandomAccessFile(input, "r").use { src ->
            var copied = 0L
            var idx = 0
            while (copied < info.dataSize) {
                val remaining = info.dataSize - copied
                val thisBytes = min(remaining, bytesPerChunk)
                val out = File(dir, "chunk_${idx.toString().padStart(3, '0')}.wav")
                FileOutputStream(out).use { os ->
                    os.write(buildHeader(info.sampleRate, info.channels, thisBytes))
                    src.seek(info.dataOffset + copied)
                    val buffer = ByteArray(256 * 1024)
                    var left = thisBytes
                    while (left > 0) {
                        val n = src.read(buffer, 0, min(buffer.size.toLong(), left).toInt())
                        if (n <= 0) break
                        os.write(buffer, 0, n)
                        left -= n
                    }
                    if (left != 0L) error("Whisper WAV 切段讀取不完整")
                }
                val offsetMs = copied * 1000L / info.byteRate
                val durMs = thisBytes * 1000L / info.byteRate
                result += WhisperAudioChunk(out, offsetMs, true, durMs)
                copied += thisBytes
                idx++
            }
        }
        return result
    }

    @JvmStatic
    fun cleanup(chunks: List<WhisperAudioChunk>) {
        var parent: File? = null
        chunks.forEach { c ->
            if (c.temporary) {
                parent = c.file.parentFile
                try { c.file.delete() } catch (_: Throwable) {}
            }
        }
        try { parent?.delete() } catch (_: Throwable) {}
    }

    private fun readInfo(file: File): WavInfo? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return null
            val riff = ByteArray(4); raf.readFully(riff)
            readU32LE(raf)
            val wave = ByteArray(4); raf.readFully(wave)
            if (String(riff, Charsets.US_ASCII) != "RIFF" || String(wave, Charsets.US_ASCII) != "WAVE") return null

            var audioFormat = -1
            var channels = -1
            var sampleRate = -1
            var byteRate = -1L
            var blockAlign = -1
            var bits = -1
            var dataOffset = -1L
            var dataSize = -1L

            while (raf.filePointer + 8 <= raf.length()) {
                val idb = ByteArray(4); raf.readFully(idb)
                val id = String(idb, Charsets.US_ASCII)
                val size = readU32LE(raf)
                val payload = raf.filePointer
                if (id == "fmt " && size >= 16) {
                    audioFormat = readU16LE(raf)
                    channels = readU16LE(raf)
                    sampleRate = readU32LE(raf).toInt()
                    byteRate = readU32LE(raf)
                    blockAlign = readU16LE(raf)
                    bits = readU16LE(raf)
                } else if (id == "data") {
                    dataOffset = payload
                    dataSize = min(size, raf.length() - payload)
                    break
                }
                val next = payload + size + (size and 1L)
                if (next <= payload || next > raf.length()) break
                raf.seek(next)
            }
            if (audioFormat != 1 || channels <= 0 || sampleRate <= 0 || dataOffset < 0 || dataSize <= 0) return null
            val actualByteRate = if (byteRate > 0) byteRate else sampleRate.toLong() * channels * (bits / 8)
            val actualBlock = if (blockAlign > 0) blockAlign else channels * (bits / 8)
            return WavInfo(channels, sampleRate, actualByteRate, actualBlock, bits, dataOffset, dataSize)
        }
    }

    private fun readU16LE(raf: RandomAccessFile): Int {
        val a = raf.read(); val b = raf.read()
        if (a < 0 || b < 0) error("WAV header truncated")
        return a or (b shl 8)
    }

    private fun readU32LE(raf: RandomAccessFile): Long {
        val a = raf.read(); val b = raf.read(); val c = raf.read(); val d = raf.read()
        if (a < 0 || b < 0 || c < 0 || d < 0) error("WAV header truncated")
        return (a.toLong() or (b.toLong() shl 8) or (c.toLong() shl 16) or (d.toLong() shl 24)) and 0xffffffffL
    }

    private fun buildHeader(sampleRate: Int, channels: Int, pcmBytes: Long): ByteArray {
        val byteRate = sampleRate.toLong() * channels * 2L
        val blockAlign = channels * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt((pcmBytes + 36L).coerceAtMost(0xffffffffL).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(1)
        h.putShort(channels.toShort())
        h.putInt(sampleRate)
        h.putInt(byteRate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        h.putShort(blockAlign.toShort())
        h.putShort(16)
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(pcmBytes.coerceAtMost(0xffffffffL).toInt())
        return h.array()
    }
}
