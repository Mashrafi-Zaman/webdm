package ir.amirab.downloader.downloaditem.hls

import ir.amirab.downloader.exception.DownloadValidationException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * External tools the HLS downloader may use; provided by the app (Settings > Tools).
 */
interface HLSMediaTools {
    /** ffmpeg executable, or null if it isn't installed */
    fun ffmpegPath(): File?

    /** remux finished MPEG-TS downloads into .mp4 (stream copy) */
    fun convertTsToMp4(): Boolean

    object None : HLSMediaTools {
        override fun ffmpegPath(): File? = null
        override fun convertTsToMp4(): Boolean = false
    }

    /** extractor for video pages (YouTube, Facebook ...): yt-dlp; null if not available */
    fun siteExtractor(): SiteMediaExtractor? = null
}

class FfmpegMissingException(what: String) :
    DownloadValidationException("$what needs ffmpeg. Install it in Settings > Tools, then resume the download.") {
    override fun isCritical() = true
}

/** Runs ffmpeg (stream copy only; nothing is re-encoded). */
object Ffmpeg {
    /** ffmpeg arguments for request headers (each line "Name: value\r\n") */
    fun headerArgs(headers: Map<String, String>?, userAgent: String?): List<String> {
        val args = mutableListOf<String>()
        val lines = headers.orEmpty()
            .filterKeys { !it.equals("user-agent", true) && !it.equals("range", true) }
            .entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        if (lines.isNotEmpty()) {
            args += listOf("-headers", lines)
        }
        val agent = userAgent ?: headers.orEmpty().entries.firstOrNull { it.key.equals("user-agent", true) }?.value
        if (agent != null) {
            args += listOf("-user_agent", agent)
        }
        return args
    }

    /**
     * Run ffmpeg and wait. [onProgress] gets the processed media time in seconds (from -progress).
     * [isCancelled] is polled; the process is killed when it returns true.
     */
    fun run(
        ffmpeg: File,
        args: List<String>,
        onProgress: (Double) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        val command = listOf(ffmpeg.absolutePath, "-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats") + args
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        val errors = StringBuilder()
        val errorReader = Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(errors) {
                        errors.appendLine(line)
                        if (errors.length > 8000) errors.delete(0, errors.length - 8000)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val outReader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                        line.substringAfter("=").toLongOrNull()?.let { onProgress(it / 1_000_000.0) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (isCancelled()) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                throw kotlinx.coroutines.CancellationException("ffmpeg cancelled")
            }
        }
        outReader.join(2000)
        errorReader.join(2000)
        if (process.exitValue() != 0) {
            val tail = synchronized(errors) { errors.toString().trim().lines().takeLast(5).joinToString(" | ") }
            throw IOException("ffmpeg failed (exit ${process.exitValue()}): $tail")
        }
    }

    /** Combine a video file and an audio file into one .mp4 (stream copy). */
    fun mux(ffmpeg: File, video: File, audio: File, output: File) {
        run(
            ffmpeg,
            listOf(
                "-i", video.absolutePath, "-i", audio.absolutePath,
                "-map", "0:v:0?", "-map", "0:a?", "-map", "1:a:0",
                "-c", "copy", "-movflags", "+faststart", output.absolutePath,
            ),
        )
    }

    /** MPEG-TS -> MP4 without re-encoding (fixes AAC headers for mp4). */
    fun remuxToMp4(ffmpeg: File, input: File, output: File) {
        run(
            ffmpeg,
            listOf("-i", input.absolutePath, "-map", "0", "-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", output.absolutePath),
        )
    }
}
