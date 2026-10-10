package ir.amirab.downloader.downloaditem.hls

import io.lindstrom.m3u8.model.KeyMethod
import io.lindstrom.m3u8.model.MasterPlaylist
import io.lindstrom.m3u8.model.MediaPlaylist
import io.lindstrom.m3u8.model.MediaType
import io.lindstrom.m3u8.model.SegmentKey
import io.lindstrom.m3u8.parser.MasterPlaylistParser
import io.lindstrom.m3u8.parser.MediaPlaylistParser
import io.lindstrom.m3u8.parser.ParsingMode
import ir.amirab.downloader.part.MediaSegment
import ir.amirab.downloader.utils.FileNameUtil
import ir.amirab.util.HttpUrlUtils
import ir.amirab.downloader.exception.DownloadValidationException

/** How a stream is protected. Only DRM is refused; there are no workarounds for it. */
enum class HLSEncryption {
    None,

    /** whole-segment AES-128-CBC, key from the playlist's URI: decrypted by WebDM */
    Aes128,

    /** SAMPLE-AES with a plain (identity) key: needs ffmpeg */
    SampleAes,

    /** Widevine / PlayReady / FairPlay key formats */
    Drm,
}

enum class HLSSegmentFormat { Ts, Fmp4 }

data class HLSVariantOption(
    val url: String,
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
    val audioGroup: String?,
) {
    val label: String
        get() = when {
            height != null -> "${height}p"
            else -> "${bandwidth / 1000} kbps"
        }
}

/** A media playlist turned into WebDM parts. */
data class ResolvedTrack(
    val playlistUrl: String,
    val playlist: MediaPlaylist,
    val parts: List<MediaSegment>,
)

data class ResolvedHLSStream(
    val video: ResolvedTrack,
    // separate audio rendition (EXT-X-MEDIA TYPE=AUDIO with a URI)
    val audio: ResolvedTrack?,
    // master playlist variants (empty for a plain media playlist), best first
    val variants: List<HLSVariantOption>,
    val selectedVariant: HLSVariantOption?,
    val encryption: HLSEncryption,
    val format: HLSSegmentFormat,
) {
    val isLive: Boolean get() = video.playlist.ongoing()
    val duration: Double get() = video.playlist.mediaSegments().sumOf { it.duration() }
    val mediaSegmentCount: Int get() = video.parts.count { !it.isInit }
    val allParts: List<MediaSegment> get() = video.parts + audio?.parts.orEmpty()

    /** the file extension of the result */
    val outputExtension: String
        get() = if (format == HLSSegmentFormat.Fmp4 || audio != null) "mp4" else "ts"
}

/** These stop the download (no retries): retrying can't help. */
class DrmProtectedStreamException : DownloadValidationException("DRM-protected video, can't download") {
    override fun isCritical() = true
}

class UnsupportedStreamException(message: String) : DownloadValidationException(message) {
    override fun isCritical() = true
}

object HLSStreamResolver {
    private val DRM_KEY_FORMATS = listOf(
        "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", // Widevine
        "com.widevine",
        "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95", // PlayReady
        "com.microsoft.playready",
        "com.apple.streamingkeydelivery", // FairPlay
        "urn:uuid:94ce86fb-07ff-4f43-adb8-93d2fa968ca2", // FairPlay
    )
    private val FMP4_EXTENSIONS = setOf("m4s", "mp4", "m4a", "m4v", "cmfv", "cmfa", "fmp4")

    fun isMasterPlaylist(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String): MasterPlaylist = MasterPlaylistParser(ParsingMode.LENIENT).readPlaylist(text)
    fun parseMedia(text: String): MediaPlaylist = MediaPlaylistParser(ParsingMode.LENIENT).readPlaylist(text)

    fun variantsOf(master: MasterPlaylist, masterUrl: String): List<HLSVariantOption> {
        return master.variants()
            .map {
                HLSVariantOption(
                    url = resolve(masterUrl, it.uri()),
                    bandwidth = it.bandwidth(),
                    width = it.resolution().orElse(null)?.width(),
                    height = it.resolution().orElse(null)?.height(),
                    audioGroup = it.audio().orElse(null),
                )
            }
            .distinctBy { it.url }
            .sortedWith(compareByDescending<HLSVariantOption> { it.height ?: 0 }.thenByDescending { it.bandwidth })
    }

    /** URL of the audio rendition a variant uses, if the audio is a separate playlist */
    fun audioRenditionUrl(master: MasterPlaylist, masterUrl: String, variant: HLSVariantOption): String? {
        val group = variant.audioGroup ?: return null
        val renditions = master.alternativeRenditions()
            .filter { it.type() == MediaType.AUDIO && it.groupId() == group && it.uri().isPresent }
        val chosen = renditions.firstOrNull { it.defaultRendition().orElse(false) } ?: renditions.firstOrNull()
        return chosen?.uri()?.orElse(null)?.let { resolve(masterUrl, it) }
    }

    fun classifyKeys(keys: List<SegmentKey>): HLSEncryption {
        var result = HLSEncryption.None
        for (key in keys) {
            val method = key.method()
            if (method == KeyMethod.NONE) continue
            val format = key.keyFormat().orElse(null)?.lowercase()
            val value = when {
                format != null && DRM_KEY_FORMATS.any { format.startsWith(it) } -> HLSEncryption.Drm
                method == KeyMethod.AES_128 -> HLSEncryption.Aes128
                method == KeyMethod.SAMPLE_AES && (format == null || format == "identity") -> HLSEncryption.SampleAes
                // SAMPLE-AES-CTR (CENC) and other key formats are DRM in practice
                else -> HLSEncryption.Drm
            }
            if (value.ordinal > result.ordinal) result = value
        }
        return result
    }

    fun encryptionOf(playlist: MediaPlaylist): HLSEncryption {
        return classifyKeys(playlist.mediaSegments().flatMap { it.segmentKeys() })
    }

    fun formatOf(playlist: MediaPlaylist, playlistUrl: String): HLSSegmentFormat {
        val segments = playlist.mediaSegments()
        if (segments.any { it.segmentMap().isPresent }) return HLSSegmentFormat.Fmp4
        val extension = segments.firstOrNull()?.uri()
            ?.let { resolve(playlistUrl, it) }
            ?.let(HttpUrlUtils::extractNameFromLink)
            ?.let(FileNameUtil::getExtensionOrNull)
            ?.lowercase()
        return if (extension != null && extension in FMP4_EXTENSIONS) HLSSegmentFormat.Fmp4 else HLSSegmentFormat.Ts
    }

    /** Turn a media playlist into parts (init segments, byte ranges and keys resolved). */
    fun toParts(playlist: MediaPlaylist, playlistUrl: String, track: Int): List<MediaSegment> {
        val parts = ArrayList<MediaSegment>()
        var lastMapKey: String? = null
        // EXT-X-BYTERANGE without offset continues where the previous range of the same URI ended
        val nextOffsetByUri = HashMap<String, Long>()
        var currentKey: SegmentKey? = null
        playlist.mediaSegments().forEachIndexed { index, segment ->
            val sequence = playlist.mediaSequence() + index
            val url = resolve(playlistUrl, segment.uri())
            segment.segmentMap().orElse(null)?.let { map ->
                val mapUrl = resolve(playlistUrl, map.uri())
                val range = map.byteRange().orElse(null)
                val mapKey = "$mapUrl|${range?.offset()?.orElse(0L)}|${range?.length()}"
                if (mapKey != lastMapKey) {
                    lastMapKey = mapKey
                    parts.add(
                        MediaSegment(
                            segmentIndex = MediaSegment.idFor(track, sequence, isInit = true),
                            link = mapUrl,
                            duration = 0.0,
                            sequence = sequence,
                            track = track,
                            isInit = true,
                            rangeStart = range?.let { it.offset().orElse(0L) },
                            rangeLength = range?.length(),
                        )
                    )
                }
            }
            // a key applies to all following segments until the next EXT-X-KEY
            segment.segmentKeys().firstOrNull()?.let { currentKey = it }
            val key = currentKey?.takeIf { it.method() == KeyMethod.AES_128 }
            var rangeStart: Long? = null
            var rangeLength: Long? = null
            segment.byteRange().orElse(null)?.let { range ->
                rangeStart = range.offset().orElse(null) ?: nextOffsetByUri[url] ?: 0L
                rangeLength = range.length()
                nextOffsetByUri[url] = rangeStart!! + range.length()
            }
            parts.add(
                MediaSegment(
                    segmentIndex = MediaSegment.idFor(track, sequence, isInit = false),
                    link = url,
                    duration = segment.duration(),
                    sequence = sequence,
                    track = track,
                    rangeStart = rangeStart,
                    rangeLength = rangeLength,
                    keyMethod = key?.let { "AES-128" },
                    keyUri = key?.uri()?.orElse(null)?.let { resolve(playlistUrl, it) },
                    keyIv = key?.iv()?.orElse(null),
                    discontinuity = segment.discontinuity(),
                )
            )
        }
        return parts
    }

    fun resolve(base: String, uri: String): String {
        if (HttpUrlUtils.isValidUrl(uri)) return uri
        return HttpUrlUtils.createURL(base).resolve(uri)?.toString()
            ?: throw UnsupportedStreamException("invalid segment URL: $uri")
    }

    /**
     * Resolve a stream: [fetch] downloads a playlist (with the item's headers/cookies/user agent).
     * Embedded playlists (streams rebuilt by the extension) are used as they are.
     */
    suspend fun resolve(
        credentials: IHLSCredentials,
        fetch: suspend (url: String) -> String,
    ): ResolvedHLSStream {
        var variants = emptyList<HLSVariantOption>()
        var selected: HLSVariantOption? = null
        var audioUrl: String? = null
        val videoUrl: String
        val videoText: String
        val embedded = credentials.embeddedPlaylist
        if (embedded != null) {
            videoUrl = credentials.link
            videoText = embedded
        } else {
            val firstText = fetch(credentials.link)
            if (isMasterPlaylist(firstText)) {
                val master = parseMaster(firstText)
                if (classifyKeys(master.sessionKeys()) == HLSEncryption.Drm) throw DrmProtectedStreamException()
                variants = variantsOf(master, credentials.link)
                if (variants.isEmpty()) throw UnsupportedStreamException("master playlist has no variants")
                selected = variants.firstOrNull { it.url == credentials.selectedVariantUrl } ?: variants.first()
                audioUrl = audioRenditionUrl(master, credentials.link, selected)
                videoUrl = selected.url
                videoText = fetch(videoUrl)
            } else {
                videoUrl = credentials.link
                videoText = firstText
            }
        }
        val videoPlaylist = parsePlaylistOrThrow(videoText)
        val audioTrack = when {
            credentials.embeddedAudioPlaylist != null -> credentials.embeddedAudioPlaylist!!.let {
                val playlist = parsePlaylistOrThrow(it)
                ResolvedTrack(credentials.link, playlist, toParts(playlist, credentials.link, track = 1))
            }

            audioUrl != null -> fetch(audioUrl).let {
                val playlist = parsePlaylistOrThrow(it)
                ResolvedTrack(audioUrl, playlist, toParts(playlist, audioUrl, track = 1))
            }

            else -> null
        }
        val encryption = listOfNotNull(encryptionOf(videoPlaylist), audioTrack?.let { encryptionOf(it.playlist) })
            .maxBy { it.ordinal }
        if (encryption == HLSEncryption.Drm) throw DrmProtectedStreamException()
        return ResolvedHLSStream(
            video = ResolvedTrack(videoUrl, videoPlaylist, toParts(videoPlaylist, videoUrl, track = 0)),
            audio = audioTrack,
            variants = variants,
            selectedVariant = selected,
            encryption = encryption,
            format = formatOf(videoPlaylist, videoUrl),
        )
    }

    private fun parsePlaylistOrThrow(text: String): MediaPlaylist {
        val playlist = try {
            parseMedia(text)
        } catch (e: Exception) {
            throw BadHLSResponseException("can't parse HLS playlist", e)
        }
        if (playlist.mediaSegments().isEmpty()) {
            throw UnsupportedStreamException("playlist has no segments")
        }
        return playlist
    }
}
