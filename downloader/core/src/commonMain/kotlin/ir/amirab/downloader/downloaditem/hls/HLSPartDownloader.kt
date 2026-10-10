package ir.amirab.downloader.downloaditem.hls

import ir.amirab.downloader.connection.Connection
import ir.amirab.downloader.connection.HttpDownloaderClient
import ir.amirab.downloader.connection.response.HttpResponseInfo
import ir.amirab.downloader.destination.DestWriter
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import ir.amirab.downloader.part.MediaSegment
import ir.amirab.downloader.part.PartDownloader
import ir.amirab.util.HttpUrlUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import ir.amirab.downloader.utils.speedlimiter.SpeedLimiter
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.cipherSource
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class HLSPartDownloader(
    part: MediaSegment,
    getDestWriter: () -> DestWriter,
    private val baseURL: String,
    private val client: HttpDownloaderClient,
    private val speedLimiters: List<SpeedLimiter>,
    // the item's headers, cookies, user agent and login: sent with every segment request
    private val credentials: () -> IHLSCredentials,
    private val keyProvider: HLSKeyProvider,
) : PartDownloader<
        MediaSegment
        >(
    part = part,
    getDestWriter = getDestWriter,
) {
    override fun howMuchCanRead(maxAllowed: Long): Long {
        return maxAllowed
    }

    // raw (still encrypted) bytes received and expected, to detect connections that end early
    @Volatile
    private var rawBytesRead = 0L

    @Volatile
    private var expectedRawLength: Long? = null

    override suspend fun connectAndVerify(): Connection<HttpResponseInfo> {
        val fullLink = part.link
            .takeIf { HttpUrlUtils.isValidUrl(it) }
            ?.let(HttpUrlUtils::createURL)
            ?: HttpUrlUtils.createURL(baseURL).resolve(part.link)
        requireNotNull(fullLink) {
            "link is incorrect! ${part.link}"
        }
        val c = credentials()
        val start = part.rangeStart
        val end = part.rangeLength?.let { (start ?: 0L) + it - 1 }
        // the cipher (and the key download) before the segment request, so a key error doesn't waste a connection
        val cipher = keyProvider.cipherFor(part)
        val connect = client.connect(
            HttpDownloadCredentials(
                link = fullLink.toString(),
                headers = c.headers,
                username = c.username,
                password = c.password,
                downloadPage = c.downloadPage,
                userAgent = c.userAgent,
            ),
            start, end,
        )
        if (stop || !currentCoroutineContext().isActive) {
            connect.close()
            throw CancellationException()
        }
        if (!connect.responseInfo.isSuccessFul) {
            connect.close()
            throw IOException("segment request failed: HTTP ${connect.responseInfo.statusCode}")
        }
        rawBytesRead = 0
        expectedRawLength = connect.responseInfo.contentLength?.takeIf { it > 0 }
        // progress is measured in written (decrypted) bytes; close enough to the content length for the UI
        part.length = expectedRawLength
        val counting: Source = object : ForwardingSource(connect.source) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0) rawBytesRead += read
                return read
            }
        }
        val limited = speedLimiters.fold(counting) { acc, thr -> thr.source(acc) }
        return connect.copy(
            source = if (cipher != null) limited.cipherSource(cipher) else limited
        )
    }

    override fun onCanceled(e: Throwable) {
        if (!part.isCompleted) {
            // we should restart failed parts
            part.resetCurrent()
        }
        super.onCanceled(e)
    }

    override fun onFinish() {
        val expected = expectedRawLength
        if (expected != null && rawBytesRead < expected) {
            // the server closed the connection early: don't accept a truncated segment
            onCanceled(IOException("segment ended early ($rawBytesRead of $expected bytes)"))
            return
        }
        part.isCompleted = true
        super.onFinish()
    }
}
