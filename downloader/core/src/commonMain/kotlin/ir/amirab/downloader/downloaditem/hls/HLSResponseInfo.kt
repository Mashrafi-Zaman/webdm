package ir.amirab.downloader.downloaditem.hls

import io.lindstrom.m3u8.model.MediaPlaylist
import ir.amirab.downloader.connection.HttpDownloaderClient
import ir.amirab.downloader.connection.IResponseInfo
import ir.amirab.downloader.connection.response.HttpResponseInfo
import ir.amirab.downloader.connection.response.expectSuccess
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import okio.buffer
import java.io.IOException

/** A checked HLS stream: what the add-download dialog shows and what the download job downloads. */
data class HLSResponseInfo(
    // response of the first playlist request; null for embedded playlists (streams rebuilt by the extension)
    val httpResponseInfo: HttpResponseInfo?,
    val stream: ResolvedHLSStream,
) : IResponseInfo {
    val hlsManifest: MediaPlaylist get() = stream.video.playlist

    val name: String?
        get() = stream.title ?: httpResponseInfo?.fileName
    val duration = stream.duration.takeIf { it > 0 }

    override val isSuccessFul: Boolean
        get() = httpResponseInfo?.isSuccessFul ?: true
    override val requiresAuth: Boolean
        get() = httpResponseInfo?.requireBasicAuth ?: false
    override val requireBasicAuth: Boolean
        get() = httpResponseInfo?.requireBasicAuth ?: false
    override val isWebPage: Boolean
        get() = httpResponseInfo?.isWebPage ?: false
    override val resumeSupport: Boolean
        get() = true
    override val unsuccessFullException: Throwable?
        get() = httpResponseInfo?.unsuccessFullException

    companion object {
        /**
         * Fetch (or read the embedded) playlist(s) and resolve the stream, with the item's headers/cookies/UA.
         * Video pages ([IHLSCredentials.sitePage]) are described by [siteExtractor] (yt-dlp) first.
         */
        suspend fun resolve(
            client: HttpDownloaderClient,
            credentials: IHLSCredentials,
            siteExtractor: SiteMediaExtractor? = null,
        ): HLSResponseInfo {
            if (credentials.sitePage) {
                val extractor = siteExtractor
                    ?: throw SiteNotSupportedException("Downloading from video sites needs yt-dlp (Settings > Tools)")
                val stream = HLSSiteResolver.resolve(
                    credentials = credentials,
                    extractor = extractor,
                    fetch = { url, extra -> fetchPlaylist(client, credentials, url, extra).second },
                    probeSize = { url, extra -> probeSize(client, credentials, url, extra) },
                )
                return HLSResponseInfo(null, stream)
            }
            var firstResponse: HttpResponseInfo? = null
            val stream = HLSStreamResolver.resolve(credentials) { url ->
                val (response, text) = fetchPlaylist(client, credentials, url)
                if (firstResponse == null) firstResponse = response
                text
            }
            return HLSResponseInfo(firstResponse, stream)
        }

        /** full size of a file if its server answers Range requests (206), else null */
        private suspend fun probeSize(
            client: HttpDownloaderClient,
            credentials: IHLSCredentials,
            url: String,
            extraHeaders: Map<String, String>,
        ): Long? = runCatching {
            val request = HttpDownloadCredentials(
                link = url,
                headers = credentials.headers.orEmpty().filterKeys { !it.equals("Range", true) } + extraHeaders,
                username = credentials.username,
                password = credentials.password,
                downloadPage = credentials.downloadPage,
                userAgent = credentials.userAgent,
            )
            client.connect(request, 0, 0).use { connection ->
                connection.responseInfo.takeIf { it.statusCode == 206 }?.totalLength?.takeIf { it > 0 }
            }
        }.getOrNull()

        suspend fun fetchPlaylist(
            client: HttpDownloaderClient,
            credentials: IHLSCredentials,
            url: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): Pair<HttpResponseInfo, String> {
            val request = HttpDownloadCredentials(
                link = url,
                headers = credentials.headers.orEmpty() + extraHeaders,
                username = credentials.username,
                password = credentials.password,
                downloadPage = credentials.downloadPage,
                userAgent = credentials.userAgent,
            )
            return client.connect(request, null, null).use { connection ->
                expectPlaylistResponse(connection.responseInfo)
                connection.responseInfo to connection.source.buffer().use { it.readUtf8() }
            }
        }

        private val HLS_CONTENT_TYPES = listOf(
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
        )

        /**
         * if no hls content-type received we check this
         */
        private const val MAXIMUM_ALLOWED_SIZE = 2 * 1024 * 1024 // 2MiB

        private fun expectPlaylistResponse(responseInfo: HttpResponseInfo) {
            responseInfo.expectSuccess()
            val contentType = responseInfo.responseHeaders["content-type"]
            var error: String? = null
            if (contentType == null) {
                error = "no content type is provided"
            } else {
                // "application/vnd.apple.mpegurl; charset=utf-8" starts with a known type (not the other way round)
                val isHLSContentType = HLS_CONTENT_TYPES.any { contentType.trim().startsWith(it, ignoreCase = true) }
                if (!isHLSContentType) {
                    error = "content type is not hls compatible: $contentType"
                }
            }
            if (error != null) {
                val contentLength = responseInfo.contentLength
                if (contentLength == null) {
                    error += ", and content length is unknown"
                } else {
                    if (contentLength > MAXIMUM_ALLOWED_SIZE) {
                        error += ", and returned content length is too big for hls playlist"
                    } else {
                        error = null
                    }
                }
            }
            if (error != null) {
                throw BadHLSResponseException(error)
            }
        }
    }
}

class BadHLSResponseException(
    message: String, cause: Throwable? = null
) : IOException(message, cause)
