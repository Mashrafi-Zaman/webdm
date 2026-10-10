package com.abdownloadmanager.integration.model

import kotlinx.serialization.Serializable

/**
 * GET /capabilities (and the "capabilities" native-messaging action): what this app can download, so the browser
 * extension never sends a job the app doesn't understand (older apps answer 404 / unknown action).
 */
@Serializable
data class ApiCapabilities(
    val app: String,
    val version: String,
    // /add item types this app accepts
    val jobTypes: List<String>,
    // e.g. "hls.aes128", "hls.fmp4", "hls.byteRange", "hls.live", "ffmpeg" (true = available now)
    val features: Map<String, Boolean>,
) {
    companion object {
        // "page": a video page for yt-dlp (shipped with the app; HTTP /capabilities drops it if yt-dlp is missing)
        val JOB_TYPES = listOf("http", "hls", "segments", "av", "page")

        /** features that don't depend on installed tools */
        fun baseFeatures(): Map<String, Boolean> = mapOf(
            "hls.masterPlaylists" to true,
            "hls.aes128" to true,
            "hls.fmp4" to true,
            "hls.byteRange" to true,
            "hls.discontinuity" to true,
            "hls.live" to true,
        )
    }
}
