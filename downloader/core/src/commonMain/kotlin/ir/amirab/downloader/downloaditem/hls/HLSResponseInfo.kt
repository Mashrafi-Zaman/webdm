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
        get() = httpResponseInfo?.fileName
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
        /** Fetch (or read the embedded) playlist(s) and resolve the stream, with the item's headers/cookies/UA. */
        suspend fun resolve(client: HttpDownloaderClient, credentials: IHLSCredentials): HLSResponseInfo {
            var firstResponse: HttpResponseInfo? = null
            val stream = HLSStreamResolver.resolve(credentials) { url ->
                val (response, text) = fetchPlaylist(client, credentials, url)
                if (firstResponse == null) firstResponse = response
                text
            }
            return HLSResponseInfo(firstResponse, stream)
        }

        suspend fun fetchPlaylist(
            client: HttpDownloaderClient,
            credentials: IHLSCredentials,
            url: String,
        ): Pair<HttpResponseInfo, String> {
            val request = HttpDownloadCredentials(
                link = url,
                headers = credentials.headers,
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
