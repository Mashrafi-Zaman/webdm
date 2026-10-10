package ir.amirab.downloader.downloaditem.hls

import ir.amirab.downloader.exception.DownloadValidationException

/**
 * A video page (YouTube, Facebook, Instagram ...) as an external site extractor (yt-dlp) describes it: the
 * downloadable formats with their direct URLs. WebDM then downloads the chosen formats itself (byte-range parts,
 * merge with ffmpeg), see [HLSSiteResolver].
 */
data class SiteMedia(
    val title: String?,
    val duration: Double?,
    val isLive: Boolean,
    val formats: List<SiteFormat>,
)

data class SiteFormat(
    val id: String,
    val url: String,
    // yt-dlp protocol: "https", "http", "m3u8_native", "m3u8", ...
    val protocol: String,
    val ext: String?,
    // null / "none" = no such track
    val vcodec: String?,
    val acodec: String?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    // total bitrate in kbit/s
    val tbr: Double?,
    val filesize: Long?,
    // headers the URL must be requested with (e.g. the User-Agent the extractor used)
    val headers: Map<String, String>,
    val hasDrm: Boolean,
    // DASH manifests ("http_dash_segments"): the segment URLs in order; an initialization segment (no duration) first
    val fragments: List<SiteFragment> = emptyList(),
) {
    val hasVideo: Boolean get() = !vcodec.isNullOrBlank() && vcodec != "none"
    val hasAudio: Boolean get() = !acodec.isNullOrBlank() && acodec != "none"

    /** some sites don't name the codecs of muxed files: then it's a progressive file with both tracks */
    val isProgressive: Boolean get() = (hasVideo && hasAudio) || (vcodec == null && acodec == null && height != null)
}

data class SiteFragment(val url: String, val duration: Double?)

interface SiteMediaExtractor {
    /**
     * Describe a video page. [cookies] is a Netscape cookies.txt text for sites that need a login (only when the
     * user allowed it). Throws [SiteExtractionException] subclasses with a clear message.
     */
    suspend fun extract(pageUrl: String, headers: Map<String, String>?, cookies: String?): SiteMedia

    /** can it handle this page URL at all (a site it supports)? */
    fun supportsUrl(url: String): Boolean
}

/** Errors from the site extractor; they stop the download (retrying won't help). */
open class SiteExtractionException(message: String) : DownloadValidationException(message) {
    override fun isCritical() = true
}

class SiteLoginRequiredException(message: String) : SiteExtractionException(message)

/** the extractor is probably outdated for this site (it is being updated: resume later) */
class SiteExtractorOutdatedException(message: String) : SiteExtractionException(message)

class SiteNotSupportedException(message: String) : SiteExtractionException(message)
