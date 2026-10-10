package com.abdownloadmanager.shared.downloaderinui.hls

import com.abdownloadmanager.shared.downloaderinui.DownloadSize
import com.abdownloadmanager.shared.downloaderinui.LinkChecker
import com.abdownloadmanager.shared.util.FilenameFixer
import ir.amirab.downloader.connection.HttpDownloaderClient
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.hls.HLSMediaTools
import ir.amirab.downloader.downloaditem.hls.HLSResponseInfo
import ir.amirab.downloader.downloaditem.hls.HLSVariantOption
import ir.amirab.util.HttpUrlUtils
import ir.amirab.util.flow.mapStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HLSLinkChecker(
    credentials: HLSDownloadCredentials,
    private val client: HttpDownloaderClient,
    private val mediaTools: HLSMediaTools = HLSMediaTools.None,
) : LinkChecker<HLSDownloadCredentials, HLSResponseInfo, DownloadSize.Duration>(
    initialCredentials = credentials
) {
    private val _suggestedName: MutableStateFlow<String?> = MutableStateFlow(null)

    // (this used to expose a separate, always-null flow, so the computed name never reached the dialog)
    override val suggestedName: StateFlow<String?> = _suggestedName.asStateFlow()
    private val _duration: MutableStateFlow<Double?> = MutableStateFlow(null)
    val duration: StateFlow<Double?> = _duration.asStateFlow()
    override val downloadSize: StateFlow<DownloadSize.Duration?> = _duration.mapStateFlow {
        it?.let(DownloadSize::Duration)
    }

    // master playlists: the qualities to choose from (best first); empty for a single media playlist
    private val _variants = MutableStateFlow<List<HLSVariantOption>>(emptyList())
    val variants: StateFlow<List<HLSVariantOption>> = _variants.asStateFlow()

    /**
     * A name chosen by whoever added the download (e.g. the browser extension: "<page title>.mp4"). It's kept;
     * only its extension is adjusted. Without it the name comes from the server / link. (For streams rebuilt from
     * segments the link is the init segment, so a link-based name would be "init-....mp4".)
     */
    var preferredName: String? = null

    override fun infoUpdated(responseInfo: HLSResponseInfo?) {
        _duration.value = responseInfo?.duration
        if (responseInfo == null) {
            // a check started (or failed): keep the current name
            return
        }
        val baseName = preferredName?.takeIf { it.isNotBlank() }
            ?: responseInfo.name
            ?: HttpUrlUtils.extractNameFromLink(credentials.value.link)
        _suggestedName.value = baseName
            ?.let(FilenameFixer::fix)
            ?.let { withExtension(it, outputExtensionFor(responseInfo)) }
        _variants.value = responseInfo.stream.variants
    }

    /** .mp4 for fMP4 / separate audio, .ts otherwise (or .mp4 when "Convert .ts to .mp4" is on) */
    private fun outputExtensionFor(responseInfo: HLSResponseInfo?): String {
        val extension = responseInfo?.stream?.outputExtension ?: "ts"
        return if (extension == "ts" && mediaTools.convertTsToMp4()) "mp4" else extension
    }

    override suspend fun actualCheck(credentials: HLSDownloadCredentials): HLSResponseInfo {
        // video pages (YouTube, Facebook ...) are described by yt-dlp
        return HLSResponseInfo.resolve(client, credentials, mediaTools.siteExtractor())
    }

    companion object {
        private val PLAYLIST_EXTENSIONS = setOf("m3u8", "m3u", "ts", "mp4", "m4s", "m4a", "webm", "mp3")

        fun withExtension(name: String, extension: String): String {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0 && name.substring(dot + 1).lowercase() in PLAYLIST_EXTENSIONS) name.substring(0, dot) else name
            return "$base.$extension"
        }
    }
}
