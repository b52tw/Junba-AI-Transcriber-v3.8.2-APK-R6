package tw.junba.transcriber

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.File
import kotlin.system.measureTimeMillis

interface WhisperBatchCallback {
    fun isStopRequested(): Boolean
    /** Return false when the caller wants the job to stop. */
    fun waitWhilePaused(): Boolean
    fun onPlanReady(totalChunks: Int, totalAudioMs: Long, chunked: Boolean)
    fun onChunkCompleted(index: Int, total: Int, combinedText: String, chunkElapsedMs: Long)
}

/**
 * R6 background-friendly Whisper runner.
 *
 * The model is loaded once. Long PCM16 WAV input is processed in 5-minute
 * chunks so pause/stop can happen at safe boundaries and real progress/ETA can
 * be calculated. When chunking is unavailable, it safely falls back to the
 * original one-shot behavior.
 */
object WhisperBatchEngine {
    @JvmStatic
    fun transcribeBlocking(
        context: Context,
        modelPath: String,
        sourcePath: String,
        language: String,
        chunkMinutes: Int,
        callback: WhisperBatchCallback,
    ): String {
        val prepared = AudioPreprocessor.preparePcmForChunking(context, sourcePath)
        var chunks: List<WhisperAudioChunk> = emptyList()
        try {
            chunks = if (prepared.file.name.lowercase().endsWith(".wav")) {
                WavChunker.splitIfPossible(context, prepared.file, chunkMinutes)
            } else {
                listOf(WhisperAudioChunk(prepared.file, 0L, false, -1L))
            }
            val knownTotal = chunks.filter { it.durationMs > 0 }.sumOf { it.durationMs }
            callback.onPlanReady(chunks.size.coerceAtLeast(1), knownTotal, chunks.size > 1)

            val model = Whisper.loadModel(context, modelPath)
            try {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val cfg = if (language.isBlank()) WhisperConfig(threads = threads)
                else WhisperConfig(language = language, threads = threads)
                val merged = StringBuilder()

                for (idx in chunks.indices) {
                    val chunk = chunks[idx]
                    if (callback.isStopRequested()) break
                    if (!callback.waitWhilePaused() || callback.isStopRequested()) break
                    var chunkText = ""
                    val elapsed = measureTimeMillis {
                        val r = Whisper.transcribe(model, chunk.file.absolutePath, cfg)
                        chunkText = if (r.segments.isNotEmpty()) {
                            buildString {
                                r.segments.forEach { s ->
                                    append('[')
                                    append(formatMs(s.startMs + chunk.offsetMs))
                                    append('–')
                                    append(formatMs(s.endMs + chunk.offsetMs))
                                    append("] ")
                                    append(s.text.trim())
                                    append('\n')
                                }
                            }.trim()
                        } else {
                            // Fallback when the AAR returns only plain text.
                            val t = r.text.trim()
                            if (t.isEmpty()) "" else "[${formatMs(chunk.offsetMs)}] $t"
                        }
                    }
                    if (chunkText.isNotBlank()) {
                        if (merged.isNotEmpty()) merged.append('\n')
                        merged.append(chunkText)
                    }
                    callback.onChunkCompleted(idx + 1, chunks.size, merged.toString(), elapsed)
                    if (callback.isStopRequested()) break
                }
                return merged.toString().trim()
            } finally {
                Whisper.releaseModel(model)
            }
        } finally {
            try { WavChunker.cleanup(chunks) } catch (_: Throwable) {}
            if (prepared.temporary) try { prepared.file.delete() } catch (_: Throwable) {}
        }
    }

    private fun formatMs(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
