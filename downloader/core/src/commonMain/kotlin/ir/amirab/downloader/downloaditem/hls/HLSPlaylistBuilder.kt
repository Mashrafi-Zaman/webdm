package ir.amirab.downloader.downloaditem.hls

import java.util.Locale
import kotlin.math.ceil

/**
 * Builds a media playlist for a stream that has none: the browser extension found its numbered segments and
 * rebuilt the list ({type:"segments"}). WebDM then downloads it like any other HLS stream.
 */
object HLSPlaylistBuilder {
    const val MAX_SEGMENTS = 100_000

    fun build(init: String?, segments: List<String>, totalDuration: Double?): String {
        require(segments.isNotEmpty()) { "no segments" }
        require(segments.size <= MAX_SEGMENTS) { "too many segments (${segments.size})" }
        (listOfNotNull(init) + segments).forEach {
            require(it.startsWith("http://", true) || it.startsWith("https://", true)) { "not an http(s) URL: $it" }
        }
        val segmentDuration = totalDuration?.takeIf { it > 0 }?.div(segments.size) ?: 1.0
        val target = ceil(segmentDuration.coerceAtLeast(1.0)).toInt()
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:$target\n")
            append("#EXT-X-MEDIA-SEQUENCE:1\n")
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            if (init != null) {
                append("#EXT-X-MAP:URI=\"${quoted(init)}\"\n")
            }
            for (segment in segments) {
                append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", segmentDuration)).append(",\n")
                append(segment).append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }
    }

    const val RANGE_PART_SIZE = 4L * 1024 * 1024

    /**
     * A playlist for ONE whole file: with a known size it's split into byte ranges (EXT-X-BYTERANGE) so the parts
     * download in parallel and resume; without a size it's a single segment.
     */
    fun buildForWholeFile(url: String, size: Long?, totalDuration: Double?): String {
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "not an http(s) URL: $url" }
        if (size == null || size <= 0) {
            return build(null, listOf(url), totalDuration)
        }
        val count = ((size + RANGE_PART_SIZE - 1) / RANGE_PART_SIZE).toInt()
        require(count <= MAX_SEGMENTS) { "file too large ($size bytes)" }
        val segmentDuration = totalDuration?.takeIf { it > 0 }?.div(count) ?: 1.0
        val target = ceil(segmentDuration.coerceAtLeast(1.0)).toInt()
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:$target\n")
            append("#EXT-X-MEDIA-SEQUENCE:1\n")
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            var offset = 0L
            while (offset < size) {
                val length = minOf(RANGE_PART_SIZE, size - offset)
                append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", segmentDuration)).append(",\n")
                append("#EXT-X-BYTERANGE:").append(length).append('@').append(offset).append('\n')
                append(url).append('\n')
                offset += length
            }
            append("#EXT-X-ENDLIST\n")
        }
    }

    private fun quoted(url: String) = url.replace("\"", "%22")
}
