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

    private fun quoted(url: String) = url.replace("\"", "%22")
}
