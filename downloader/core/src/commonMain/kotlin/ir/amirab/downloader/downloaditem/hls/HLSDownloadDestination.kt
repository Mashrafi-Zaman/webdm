package ir.amirab.downloader.downloaditem.hls

import ir.amirab.downloader.destination.SegmentedDownloadDestination
import ir.amirab.downloader.part.DownloadPart
import ir.amirab.downloader.part.MediaSegment
import java.io.File

/**
 * Writes an HLS / rebuilt stream into ONE file:
 * - one track: parts (init + segments) concatenated in order (.ts, or .mp4 for fMP4); optionally remuxed
 *   from .ts to .mp4 with ffmpeg (stream copy)
 * - separate audio track: video and audio concatenated into two temp files, then muxed with ffmpeg
 * The downloaded parts are deleted afterwards.
 */
class HLSDownloadDestination(
    tempDirectory: File,
    getAllParts: () -> List<DownloadPart>,
    outputFile: File,
    private val mediaTools: HLSMediaTools,
) : SegmentedDownloadDestination(
    tempDirectory = tempDirectory,
    getFileName = { it.getID().toString() },
    getAllParts = getAllParts,
    appendMode = false,
    outputFile = outputFile,
) {
    private val allParts = getAllParts

    // never two assemblies at once (they would rewrite the same temp files under each other)
    @Synchronized
    override fun assembleOutput(onProgressUpdate: (Int?) -> Unit) {
        val parts = allParts().filterIsInstance<MediaSegment>().sortedBy { it.getID() }
        val video = parts.filter { it.track == 0 }
        val audio = parts.filter { it.track == 1 }
        val fileOf = { part: MediaSegment -> tempDirectory.resolve(part.getID().toString()) }
        if (audio.isEmpty()) {
            val remux = needsTsToMp4Remux(video)
            if (!remux) {
                assemble(video.map(fileOf), outputFile, onProgressUpdate)
                return
            }
            val ffmpeg = mediaTools.ffmpegPath() ?: throw FfmpegMissingException("Converting .ts to .mp4")
            val joined = tempDirectory.resolve("video.ts")
            try {
                assemble(video.map(fileOf), joined) { onProgressUpdate(it * 9 / 10) }
                prepareDestinationFolder(outputFile)
                Ffmpeg.remuxToMp4(ffmpeg, joined, outputFile)
                onProgressUpdate(100)
            } finally {
                joined.delete()
            }
            return
        }
        val ffmpeg = mediaTools.ffmpegPath() ?: throw FfmpegMissingException("A video with a separate audio track")
        val videoFile = tempDirectory.resolve("video.${extensionOf(video)}")
        val audioFile = tempDirectory.resolve("audio.${extensionOf(audio)}")
        try {
            assemble(video.map(fileOf), videoFile) { onProgressUpdate(it * 6 / 10) }
            assemble(audio.map(fileOf), audioFile) { onProgressUpdate(60 + it * 3 / 10) }
            prepareDestinationFolder(outputFile)
            Ffmpeg.mux(ffmpeg, videoFile, audioFile, outputFile)
            onProgressUpdate(100)
        } finally {
            videoFile.delete()
            audioFile.delete()
        }
    }

    private fun isFmp4(parts: List<MediaSegment>): Boolean {
        return parts.any { it.isInit } || parts.firstOrNull()?.link?.substringBefore('?')?.substringAfterLast('.')
            ?.lowercase() in setOf("m4s", "mp4", "m4a", "m4v", "cmfv", "cmfa", "fmp4")
    }

    private fun extensionOf(parts: List<MediaSegment>) = if (isFmp4(parts)) "mp4" else "ts"

    private fun needsTsToMp4Remux(video: List<MediaSegment>): Boolean {
        return !isFmp4(video) && outputFile.extension.equals("mp4", ignoreCase = true)
    }

    companion object {
        fun prepareDestinationFolder(file: File) {
            file.parentFile?.mkdirs()
        }
    }
}
