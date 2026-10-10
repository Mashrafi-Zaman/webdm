package com.abdownloadmanager.desktop.tools

import com.abdownloadmanager.desktop.utils.AppInfo
import ir.amirab.downloader.downloaditem.hls.HLSMediaTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * ffmpeg for HLS downloads (separate audio tracks, SAMPLE-AES, .ts -> .mp4; always stream copy).
 * "Download or update" installs the LGPL Windows build from github.com/BtbN/FFmpeg-Builds (checked against the
 * release's checksums.sha256) into WebDM's tools folder, with its license. An ffmpeg on PATH is used otherwise.
 */
class FfmpegManager(
    private val scope: CoroutineScope,
) : HLSMediaTools {
    private val toolsDir: File = AppInfo.dataDir.resolve("tools")
    private val ffmpegDir: File = toolsDir.resolve("ffmpeg")
    private val settingsFile: File = toolsDir.resolve("tools-settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class ToolsSettings(val convertTsToMp4: Boolean = false)

    val bundledFfmpeg: File get() = ffmpegDir.resolve("ffmpeg.exe")
    val licenseFile: File get() = ffmpegDir.resolve("LICENSE.txt")

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    val convertTsToMp4Setting = MutableStateFlow(loadSettings().convertTsToMp4)

    private var downloadJob: Job? = null

    init {
        convertTsToMp4Setting.drop(1).onEach { saveSettings(ToolsSettings(it)) }.launchIn(scope)
        scope.launch(Dispatchers.IO) { refreshStatus() }
    }

    override fun ffmpegPath(): File? {
        if (bundledFfmpeg.isFile) return bundledFfmpeg
        return pathFfmpeg
    }

    override fun convertTsToMp4(): Boolean = convertTsToMp4Setting.value && ffmpegPath() != null

    // an ffmpeg.exe found on PATH (looked up once)
    private val pathFfmpeg: File? by lazy {
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .asSequence()
            .map { File(it.trim('"'), "ffmpeg.exe") }
            .firstOrNull { it.isFile }
    }

    private fun refreshStatus() {
        val ffmpeg = ffmpegPath()
        _status.value = if (ffmpeg == null) {
            "Not installed"
        } else {
            val version = versionOf(ffmpeg) ?: "unknown version"
            val where = if (ffmpeg == bundledFfmpeg) "WebDM tools folder" else ffmpeg.absolutePath
            "Installed: $version ($where)"
        }
    }

    private fun versionOf(ffmpeg: File): String? = runCatching {
        val process = ProcessBuilder(ffmpeg.absolutePath, "-hide_banner", "-version").redirectErrorStream(true).start()
        val firstLine = process.inputStream.bufferedReader().readLine()
        process.waitFor(10, TimeUnit.SECONDS)
        firstLine?.substringBefore(" Copyright")?.trim()
    }.getOrNull()

    fun downloadOrUpdate() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                install()
            } catch (e: Exception) {
                _status.value = "Download failed: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun install() {
        _status.value = "Downloading checksums..."
        val checksums = get(CHECKSUMS_URL).use { it.body.string() }
        val expected = checksums.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == ZIP_NAME }
            ?.get(0)?.lowercase()
            ?: throw IOException("$ZIP_NAME is not listed in checksums.sha256")

        toolsDir.mkdirs()
        val zip = toolsDir.resolve("$ZIP_NAME.part")
        val digest = MessageDigest.getInstance("SHA-256")
        get(ZIP_URL).use { response ->
            val total = response.body.contentLength()
            var read = 0L
            var lastPercent = -1
            response.body.byteStream().use { input ->
                zip.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        val percent = if (total > 0) (read * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _status.value = if (percent >= 0) "Downloading ffmpeg... $percent%" else "Downloading ffmpeg... ${read / 1_048_576} MB"
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            zip.delete()
            throw IOException("checksum mismatch, the download was not installed")
        }

        _status.value = "Installing..."
        val staging = toolsDir.resolve("ffmpeg.new")
        staging.deleteRecursively()
        staging.mkdirs()
        ZipInputStream(zip.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                // ffmpeg.exe and ffprobe.exe from bin/, and the license from the build's root
                val wanted = (entry.name.contains("/bin/") && name in setOf("ffmpeg.exe", "ffprobe.exe")) ||
                        (!entry.isDirectory && name == "LICENSE.txt" && entry.name.count { it == '/' } <= 1)
                if (wanted) {
                    staging.resolve(name).outputStream().use { zin.copyTo(it) }
                }
            }
        }
        zip.delete()
        if (!staging.resolve("ffmpeg.exe").isFile) {
            staging.deleteRecursively()
            throw IOException("ffmpeg.exe not found in the download")
        }
        ffmpegDir.deleteRecursively()
        if (!staging.renameTo(ffmpegDir)) {
            throw IOException("can't move ffmpeg into ${ffmpegDir.absolutePath}")
        }
        refreshStatus()
    }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute().also {
        if (!it.isSuccessful) {
            it.close()
            throw IOException("HTTP ${it.code} for $url")
        }
    }

    private fun loadSettings(): ToolsSettings = runCatching {
        json.decodeFromString<ToolsSettings>(settingsFile.readText())
    }.getOrDefault(ToolsSettings())

    private fun saveSettings(settings: ToolsSettings) {
        runCatching {
            toolsDir.mkdirs()
            settingsFile.writeText(json.encodeToString(settings))
        }
    }

    companion object {
        // LGPL build (no GPL-only components)
        const val ZIP_NAME = "ffmpeg-master-latest-win64-lgpl.zip"
        private const val RELEASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"
        const val ZIP_URL = "$RELEASE/$ZIP_NAME"
        const val CHECKSUMS_URL = "$RELEASE/checksums.sha256"
    }
}
