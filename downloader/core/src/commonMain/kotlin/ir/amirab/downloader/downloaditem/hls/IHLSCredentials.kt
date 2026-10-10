package ir.amirab.downloader.downloaditem.hls

import ir.amirab.downloader.downloaditem.http.IHttpBasedDownloadCredentials

interface IHLSCredentials : IHttpBasedDownloadCredentials {
    /** master playlists: URL of the chosen variant (null = highest quality) */
    val selectedVariantUrl: String?

    /**
     * Media playlist text to use instead of fetching [link]; used for streams the browser extension rebuilt from
     * numbered segments ({type:"segments"}), which have no playlist of their own.
     */
    val embeddedPlaylist: String?

    /** like [embeddedPlaylist], for a separate audio track */
    val embeddedAudioPlaylist: String?

    /** shown in the add-download dialog, e.g. "1080p" */
    val displayQuality: String?

    /** estimated total size in bytes (rebuilt streams), shown in the add-download dialog */
    val estimatedSize: Long?

    /** [link] is a video page for the site extractor (yt-dlp: YouTube, Facebook ...), not a playlist */
    val sitePage: Boolean get() = false

    /** cookies.txt text for the site extractor, when the user allowed it; never saved */
    val siteCookies: String? get() = null
}
