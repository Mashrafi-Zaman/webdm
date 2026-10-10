package com.abdownloadmanager.integration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
sealed interface IDownloadCredentialsFromIntegration {
    val link: String
    val downloadPage: String?

    val suggestedName: String?
}

@SerialName("http")
@Serializable
data class HttpDownloadCredentialsFromIntegration(
    override val link: String,
    val headers: Map<String, String>? = null,
    override val downloadPage: String? = null,
    override val suggestedName: String? = null,
) : IDownloadCredentialsFromIntegration

@SerialName("hls")
@Serializable
data class HLSDownloadCredentialsFromIntegration(
    override val link: String,
    val headers: Map<String, String>? = null,
    override val downloadPage: String? = null,
    override val suggestedName: String? = null,
) : IDownloadCredentialsFromIntegration

/**
 * A stream the browser extension rebuilt from its numbered segments because the page exposes no playlist:
 * init + ordered segments (+ a separate audio track). Downloaded into ONE file. See REST-API.yml.
 */
@SerialName("segments")
@Serializable
data class SegmentsDownloadCredentialsFromIntegration(
    override val link: String,
    val headers: Map<String, String>? = null,
    override val downloadPage: String? = null,
    override val suggestedName: String? = null,
    val init: String? = null,
    val segments: List<String> = emptyList(),
    val audioInit: String? = null,
    val audioSegments: List<String>? = null,
    // seconds; used for the dialog and segment durations
    val duration: Double? = null,
    // bytes (estimate)
    val estimatedSize: Long? = null,
    // e.g. "1080p"
    val quality: String? = null,
) : IDownloadCredentialsFromIntegration

/**
 * A video whose picture and sound are two separate whole files (DASH-style, e.g. what a player fetches in byte
 * ranges without a visible manifest). Both are downloaded (in byte-range parts when their sizes are known) and
 * merged with ffmpeg into ONE .mp4. See REST-API.yml.
 */
@SerialName("av")
@Serializable
data class AvDownloadCredentialsFromIntegration(
    override val link: String,
    val headers: Map<String, String>? = null,
    override val downloadPage: String? = null,
    override val suggestedName: String? = null,
    val video: String,
    val audio: String? = null,
    // full sizes in bytes; known sizes let the app download in parallel byte-range parts
    val videoSize: Long? = null,
    val audioSize: Long? = null,
    // seconds
    val duration: Double? = null,
    // e.g. "720p"
    val quality: String? = null,
) : IDownloadCredentialsFromIntegration

@Serializable
data class AddDownloadOptionsFromIntegration(
    val silentAdd: Boolean = false,
    val silentStart: Boolean = false,
)

@Serializable
data class AddDownloadsFromIntegration(
    val items: List<IDownloadCredentialsFromIntegration>,
    val options: AddDownloadOptionsFromIntegration = AddDownloadOptionsFromIntegration()
) {
    companion object {
        fun createFromRequest(json: Json, jsonData: String): AddDownloadsFromIntegration {
            return try {
                json.decodeFromString<AddDownloadsFromIntegration>(jsonData)
            } catch (_: SerializationException) {
                // TODO Remove this after a while
                AddDownloadsFromIntegration(
                    items = json.decodeFromString<List<HttpDownloadCredentialsFromIntegration>>(jsonData),
                    AddDownloadOptionsFromIntegration(
                        silentAdd = false,
                        silentStart = false,
                    )
                )
            }
        }
    }
}
