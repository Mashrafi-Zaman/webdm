package ir.amirab.downloader.downloaditem.hls

import kotlin.math.roundToInt

/**
 * A video page resolved with the site extractor (yt-dlp): its formats become the quality list (video + the best
 * audio, or a file that has both; "Audio only"), and the chosen formats become a stream WebDM downloads itself:
 * whole files in byte-range parts (like {type:"av"} jobs), HLS formats through their playlists. Merging is ffmpeg's.
 */
object HLSSiteResolver {
    private val SUPPORTED_PROTOCOLS = setOf("https", "http", "m3u8_native", "m3u8", "http_dash_segments")
    private const val KEY_PREFIX = "site:"

    data class SiteOption(
        val key: String,
        val video: SiteFormat?,
        val audio: SiteFormat?,
        val label: String,
        val height: Int?,
        val width: Int?,
        val bitrate: Long,
    )

    private fun isDirect(f: SiteFormat) = f.protocol == "https" || f.protocol == "http"

    // MP4-family codecs play everywhere; VP9/Opus also mux into .mp4 but fewer players handle them
    private fun videoCodecRank(f: SiteFormat): Int {
        val c = f.vcodec?.lowercase().orEmpty()
        return when {
            c.startsWith("avc") || c.startsWith("h264") -> 3
            c.startsWith("av01") -> 2
            c.startsWith("vp9") || c.startsWith("vp09") -> 1
            else -> 0
        }
    }

    private fun audioRank(f: SiteFormat): Int {
        val c = f.acodec?.lowercase().orEmpty()
        return if (c.startsWith("mp4a") || c.startsWith("aac") || f.ext == "m4a") 1 else 0
    }

    fun options(media: SiteMedia): List<SiteOption> {
        val usable = media.formats.filter {
            it.protocol in SUPPORTED_PROTOCOLS && !it.hasDrm && (it.url.startsWith("https://") || it.url.startsWith("http://"))
                    && (it.protocol != "http_dash_segments" || it.fragments.isNotEmpty())
        }
        if (usable.isEmpty()) {
            if (media.formats.isNotEmpty() && media.formats.all { it.hasDrm }) throw DrmProtectedStreamException()
            throw UnsupportedStreamException("No downloadable format was found on this page")
        }
        val audios = usable.filter { it.hasAudio && !it.hasVideo }
        val bestAudio = audios.maxWithOrNull(
            compareBy<SiteFormat>({ audioRank(it) }, { if (isDirect(it)) 1 else 0 }, { it.tbr ?: 0.0 })
        )
        val progressive = usable.filter { it.isProgressive }
        val videoOnly = usable.filter { it.hasVideo && !it.hasAudio && !it.isProgressive }
        val candidates = videoOnly.map { it to bestAudio } + progressive.map { it to null }
        val perHeight = candidates
            .groupBy { (video, _) -> video.height ?: 0 }
            .mapValues { (_, list) ->
                list.maxWith(
                    compareBy<Pair<SiteFormat, SiteFormat?>>(
                        // a quality with sound first, then compatible codecs, direct files, frame rate, bitrate
                        { (v, a) -> if (a != null || v.isProgressive) 1 else 0 },
                        { (v, _) -> videoCodecRank(v) },
                        { (v, _) -> if (isDirect(v)) 1 else 0 },
                        { (v, _) -> v.fps ?: 0.0 },
                        { (v, _) -> v.tbr ?: 0.0 },
                    )
                )
            }
        val videoOptions = perHeight.entries
            .sortedByDescending { it.key }
            .map { (height, pair) ->
                val (video, audio) = pair
                val fps = video.fps?.roundToInt()?.takeIf { it > 30 }
                val label = buildString {
                    append(if (height > 0) "${height}p" else "Video")
                    if (fps != null) append(fps)
                    if (audio == null && !video.isProgressive) append(" (no sound)")
                }
                SiteOption(
                    key = "$KEY_PREFIX${video.id}+${audio?.id.orEmpty()}",
                    video = video,
                    audio = audio,
                    label = label,
                    height = height.takeIf { it > 0 },
                    width = video.width,
                    bitrate = (((video.tbr ?: 0.0) + (audio?.tbr ?: 0.0)) * 1000).toLong(),
                )
            }
        val audioOption = bestAudio?.let {
            SiteOption("$KEY_PREFIX+${it.id}", null, it, "Audio only", null, null, ((it.tbr ?: 0.0) * 1000).toLong())
        }
        return videoOptions + listOfNotNull(audioOption)
    }

    /**
     * [fetch] downloads a playlist and [probeSize] returns a file's full size if its server answers Range requests,
     * both with the given extra headers.
     */
    suspend fun resolve(
        credentials: IHLSCredentials,
        extractor: SiteMediaExtractor,
        fetch: suspend (url: String, headers: Map<String, String>) -> String,
        probeSize: suspend (url: String, headers: Map<String, String>) -> Long?,
    ): ResolvedHLSStream {
        val media = extractor.extract(credentials.link, credentials.headers, credentials.siteCookies)
        val options = options(media)
        val chosen = options.firstOrNull { it.key == credentials.selectedVariantUrl } ?: options.first()
        val main = chosen.video ?: chosen.audio!!
        val separateAudio = chosen.audio?.takeIf { chosen.video != null }
        val headers = main.headers + separateAudio?.headers.orEmpty()
        val video = track(main, 0, media.duration, fetch, probeSize)
        val audio = separateAudio?.let { track(it, 1, media.duration, fetch, probeSize) }
        val encryption = listOfNotNull(
            HLSStreamResolver.encryptionOf(video.playlist), audio?.let { HLSStreamResolver.encryptionOf(it.playlist) },
        ).maxBy { it.ordinal }
        if (encryption == HLSEncryption.Drm) throw DrmProtectedStreamException()
        val variants = options.map {
            HLSVariantOption(url = it.key, bandwidth = it.bitrate, width = it.width, height = it.height, audioGroup = null, name = it.label)
        }
        return ResolvedHLSStream(
            video = video,
            audio = audio,
            variants = variants,
            selectedVariant = variants.firstOrNull { it.url == chosen.key },
            encryption = encryption,
            format = if (isDirect(main)) HLSSegmentFormat.Fmp4 else HLSStreamResolver.formatOf(video.playlist, video.playlistUrl),
            requestHeaders = headers,
            title = media.title,
            extensionOverride = if (chosen.video == null) (main.ext?.takeIf { it == "m4a" || it == "mp3" || it == "webm" } ?: "m4a") else "mp4",
        )
    }

    private suspend fun track(
        format: SiteFormat,
        track: Int,
        duration: Double?,
        fetch: suspend (url: String, headers: Map<String, String>) -> String,
        probeSize: suspend (url: String, headers: Map<String, String>) -> Long?,
    ): ResolvedTrack {
        val text = if (format.protocol == "http_dash_segments") {
            // a DASH representation: init + numbered segments, like a stream rebuilt by the extension
            val init = format.fragments.first().takeIf { it.duration == null }?.url
            val segments = format.fragments.filter { it.url != init }.map { it.url }
            HLSPlaylistBuilder.build(init, segments, duration ?: format.fragments.sumOf { it.duration ?: 0.0 })
        } else if (isDirect(format)) {
            // a whole file: parallel byte-range parts when the server answers Range requests, else one piece
            val size = probeSize(format.url, format.headers)
            HLSPlaylistBuilder.buildForWholeFile(format.url, size, duration)
        } else {
            val first = fetch(format.url, format.headers)
            if (HLSStreamResolver.isMasterPlaylist(first)) {
                val variant = HLSStreamResolver.variantsOf(HLSStreamResolver.parseMaster(first), format.url).first()
                return mediaTrack(variant.url, fetch(variant.url, format.headers), track)
            }
            first
        }
        return mediaTrack(format.url, text, track)
    }

    private fun mediaTrack(url: String, text: String, track: Int): ResolvedTrack {
        val playlist = HLSStreamResolver.parseMedia(text)
        if (playlist.mediaSegments().isEmpty()) throw UnsupportedStreamException("the video has no segments")
        return ResolvedTrack(url, playlist, HLSStreamResolver.toParts(playlist, url, track))
    }
}
