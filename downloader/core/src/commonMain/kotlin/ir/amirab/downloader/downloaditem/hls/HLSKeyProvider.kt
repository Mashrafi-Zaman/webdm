package ir.amirab.downloader.downloaditem.hls

import ir.amirab.downloader.connection.HttpDownloaderClient
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import ir.amirab.downloader.part.MediaSegment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128 keys of a stream: fetched once per key URI (key rotation = several URIs) with the stream's
 * headers, cookies and user agent.
 */
class HLSKeyProvider(
    private val client: HttpDownloaderClient,
    private val credentials: () -> IHLSCredentials,
) {
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun keyFor(uri: String): ByteArray {
        keys[uri]?.let { return it }
        return locks.getOrPut(uri) { Mutex() }.withLock {
            keys[uri] ?: fetch(uri).also { keys[uri] = it }
        }
    }

    private suspend fun fetch(uri: String): ByteArray {
        val c = credentials()
        val connection = client.connect(
            HttpDownloadCredentials(
                link = uri,
                headers = c.headers,
                username = c.username,
                password = c.password,
                userAgent = c.userAgent,
                downloadPage = c.downloadPage,
            ),
            null, null,
        )
        val bytes = connection.use {
            if (!it.responseInfo.isSuccessFul) {
                throw IOException("can't fetch the stream key (HTTP ${it.responseInfo.statusCode})")
            }
            it.source.buffer().readByteArray()
        }
        if (bytes.size != 16) {
            throw IOException("the stream key is ${bytes.size} bytes, expected 16 (AES-128)")
        }
        return bytes
    }

    /** AES-128-CBC with PKCS7 padding (Java calls it PKCS5Padding). */
    suspend fun cipherFor(part: MediaSegment): Cipher? {
        if (part.keyMethod != "AES-128") return null
        val uri = part.keyUri ?: throw IOException("AES-128 segment without key URI")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyFor(uri), "AES"), IvParameterSpec(ivFor(part)))
        return cipher
    }

    companion object {
        /** the playlist IV, or the media sequence number as a 16-byte big-endian number */
        fun ivFor(part: MediaSegment): ByteArray {
            val explicit = part.keyIv
            if (explicit != null) {
                val hex = explicit.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
                return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }
            val iv = ByteArray(16)
            var sequence = part.mediaSequence
            for (i in 15 downTo 8) {
                iv[i] = (sequence and 0xff).toByte()
                sequence = sequence ushr 8
            }
            return iv
        }
    }
}
