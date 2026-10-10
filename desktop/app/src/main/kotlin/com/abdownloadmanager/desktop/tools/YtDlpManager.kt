package com.abdownloadmanager.desktop.tools

import com.abdownloadmanager.desktop.utils.AppInfo
import ir.amirab.downloader.downloaditem.hls.DrmProtectedStreamException
import ir.amirab.downloader.downloaditem.hls.SiteExtractionException
import ir.amirab.downloader.downloaditem.hls.SiteExtractorOutdatedException
import ir.amirab.downloader.downloaditem.hls.SiteFormat
import ir.amirab.downloader.downloaditem.hls.SiteFragment
import ir.amirab.downloader.downloaditem.hls.SiteLoginRequiredException
import ir.amirab.downloader.downloaditem.hls.SiteMedia
import ir.amirab.downloader.downloaditem.hls.SiteMediaExtractor
import ir.amirab.downloader.downloaditem.hls.SiteNotSupportedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * yt-dlp (https://github.com/yt-dlp/yt-dlp, Unlicense) as an external program that describes video pages
 * (YouTube, Facebook, Instagram ...): `yt-dlp -J` lists the formats with their direct URLs, WebDM downloads and
 * merges them itself. Used in this order: a copy installed/updated from Settings > Tools, the one shipped with the
 * installer (app resources, tools/yt-dlp), yt-dlp on PATH.
 */
class YtDlpManager(
    private val scope: CoroutineScope,
) : SiteMediaExtractor {
    private val toolsDir: File = AppInfo.dataDir.resolve("tools")
    private val installDir: File = toolsDir.resolve("yt-dlp")
    private val settingsFile: File = toolsDir.resolve("yt-dlp-settings.json")
    private val cacheDir: File = toolsDir.resolve("yt-dlp-cache")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class Settings(val useBrowserCookies: Boolean = false)

    val installedExe: File get() = installDir.resolve("yt-dlp.exe")
    private val shippedDir: File? = System.getProperty("compose.application.resources.dir")?.let { File(it, "tools/yt-dlp") }
    val shippedExe: File? get() = shippedDir?.resolve("yt-dlp.exe")
    val licenseFile: File
        get() = if (installedExe.isFile || shippedDir == null) installDir.resolve("LICENSE.txt") else shippedDir.resolve("LICENSE.txt")

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    /** let sites where the user is logged in work: the browser extension sends that site's cookies (off by default) */
    val useBrowserCookiesSetting = MutableStateFlow(loadSettings().useBrowserCookies)

    private var updateJob: Job? = null
    private var lastAutoUpdateCheck = 0L

    init {
        useBrowserCookiesSetting.drop(1).onEach { saveSettings(Settings(it)) }.launchIn(scope)
        scope.launch(Dispatchers.IO) { refreshStatus() }
    }

    fun path(): File? {
        if (installedExe.isFile) return installedExe
        shippedExe?.takeIf { it.isFile }?.let { return it }
        return pathExe
    }

    private val pathExe: File? by lazy { findOnPath("yt-dlp.exe") }

    // yt-dlp needs a JavaScript runtime for some YouTube formats; it only looks for deno by default
    private val nodeOnPath: File? by lazy { findOnPath("node.exe") }

    private fun findOnPath(name: String): File? = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .asSequence().map { File(it.trim('"'), name) }.firstOrNull { it.isFile }

    fun version(): String? = path()?.let { exe ->
        runCatching {
            val process = ProcessBuilder(exe.absolutePath, "--version").redirectErrorStream(true).start()
            val line = process.inputStream.bufferedReader().readLine()
            process.waitFor(15, TimeUnit.SECONDS)
            line?.trim()
        }.getOrNull()
    }

    private fun refreshStatus() {
        val exe = path()
        _status.value = if (exe == null) {
            "Not installed"
        } else {
            val where = when (exe) {
                installedExe -> "WebDM tools folder"
                shippedExe -> "included with WebDM"
                else -> exe.absolutePath
            }
            "Installed: ${version() ?: "unknown version"} ($where)"
        }
    }

    // ---------- SiteMediaExtractor ----------

    override fun supportsUrl(url: String): Boolean = isVideoSite(url)

    override suspend fun extract(pageUrl: String, headers: Map<String, String>?, cookies: String?): SiteMedia {
        val exe = path() ?: throw SiteNotSupportedException("yt-dlp is not installed (Settings > Tools)")
        return withContext(Dispatchers.IO) {
            val cookieFile = cookies?.takeIf { useBrowserCookiesSetting.value && it.isNotBlank() }?.let {
                toolsDir.mkdirs()
                File.createTempFile("cookies-", ".txt", toolsDir).apply { writeText(it) }
            }
            try {
                val args = buildList {
                    add(exe.absolutePath)
                    addAll(listOf("-J", "--no-playlist", "--ignore-config", "--no-progress"))
                    addAll(listOf("--cache-dir", cacheDir.absolutePath))
                    headers?.entries?.firstOrNull { it.key.equals("Referer", true) }?.let { addAll(listOf("--referer", it.value)) }
                    cookieFile?.let { addAll(listOf("--cookies", it.absolutePath)) }
                    nodeOnPath?.let { addAll(listOf("--js-runtimes", "node:${it.absolutePath}")) }
                    add("--")
                    add(pageUrl)
                }
                val process = ProcessBuilder(args).start()
                val (out, err) = coroutineScope {
                    val out = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                    val err = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
                    out.await() to err.await()
                }
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw SiteExtractionException("yt-dlp took too long to read this page")
                }
                if (process.exitValue() != 0 || out.isBlank()) {
                    throw classify(err)
                }
                parse(out)
            } finally {
                cookieFile?.delete()
            }
        }
    }

    private fun classify(stderr: String): Exception {
        val line = stderr.lineSequence().lastOrNull { it.startsWith("ERROR:") } ?: stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: "unknown error"
        // "ERROR: [youtube] abc123: Sign in to confirm ..." -> "Sign in to confirm ..."
        val message = line.removePrefix("ERROR:").trim().replace(Regex("^\\[[^]]+]\\s*[^:]*:\\s*"), "").substringBefore(" please report this issue")
        val lower = message.lowercase()
        return when {
            "drm" in lower -> DrmProtectedStreamException()
            listOf("sign in", "log in", "login", "logged", "cookies", "private", "members", "age", "registered users")
                .any { it in lower } -> SiteLoginRequiredException(
                "This video needs a login ($message). Turn on \"Use browser cookies\" in Settings > Tools, make sure " +
                        "you're logged in to the site in your browser, then add it again from the browser."
            )

            "unsupported url" in lower -> SiteNotSupportedException("yt-dlp doesn't support this page")
            listOf("unable to extract", "cannot parse", "failed to extract", "nsig", "signature", "unable to download")
                .any { it in lower } || "please report this issue" in stderr -> {
                autoUpdate()
                SiteExtractorOutdatedException(
                    "yt-dlp couldn't read this page ($message). It may be outdated: WebDM is updating it now, " +
                            "press Resume in a minute. If it still fails, the video may be removed or private."
                )
            }

            else -> SiteExtractionException(message)
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun parse(text: String): SiteMedia {
        var root: JsonObject = json.parseToJsonElement(text).jsonObject
        // a page with several videos: the first one (--no-playlist usually avoids this)
        if (root.str("_type") == "playlist") {
            root = (root["entries"]?.jsonArray?.firstOrNull() as? JsonObject)
                ?: throw SiteExtractionException("this page has no video")
        }
        val formats = root["formats"]?.jsonArray.orEmpty().mapNotNull { element: JsonElement ->
            val f = element as? JsonObject ?: return@mapNotNull null
            val url = f.str("url") ?: return@mapNotNull null
            SiteFormat(
                id = f.str("format_id") ?: return@mapNotNull null,
                url = url,
                protocol = f.str("protocol") ?: "https",
                ext = f.str("ext"),
                vcodec = f.str("vcodec"),
                acodec = f.str("acodec"),
                width = f.long("width")?.toInt(),
                height = f.long("height")?.toInt(),
                fps = f.num("fps"),
                tbr = f.num("tbr"),
                filesize = f.long("filesize"),
                headers = (f["http_headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                hasDrm = f.bool("has_drm") == true,
                fragments = run {
                    val base = f.str("fragment_base_url")
                    (f["fragments"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { fragment ->
                        val o = fragment as? JsonObject ?: return@mapNotNull null
                        val fragmentUrl = o.str("url") ?: o.str("path")?.let { p ->
                            if (p.startsWith("http")) p else base?.let { b -> URI(b).resolve(p).toString() }
                        } ?: return@mapNotNull null
                        SiteFragment(fragmentUrl, o.num("duration"))
                    }
                },
            )
        }
        return SiteMedia(
            title = cleanTitle(root.str("title")),
            duration = root.num("duration"),
            isLive = root.bool("is_live") == true,
            formats = formats,
        )
    }

    /** a file name from a page title: without Facebook's "9.8K views · 341 reactions | " prefix, not overly long */
    private fun cleanTitle(title: String?): String? {
        val original = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var cleaned = original.replace(
            Regex("^[\\d.,]+\\s*[KMB]?\\s+views?\\s*·\\s*[\\d.,]+\\s*[KMB]?\\s+reactions?\\s*\\|\\s*", RegexOption.IGNORE_CASE), "",
        )
        if (cleaned.length > 120) cleaned = cleaned.take(120).substringBeforeLast(' ').trimEnd(' ', '|', '·', '-')
        return cleaned.ifBlank { original }
    }

    // ---------- install / update ----------

    fun downloadOrUpdate() {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch(Dispatchers.IO) {
            try {
                install()
            } catch (e: Exception) {
                _status.value = "Download failed: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    /** after an "outdated" error: update if a newer release exists (at most once an hour) */
    private fun autoUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastAutoUpdateCheck < 3_600_000) return
        lastAutoUpdateCheck = now
        if (updateJob?.isActive == true) return
        updateJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val latest = latestVersion()
                if (latest != null && latest != version()) install()
            }
        }
    }

    private val client by lazy {
        OkHttpClient.Builder().followRedirects(true).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun latestVersion(): String? {
        // .../releases/latest redirects to .../releases/tag/<version>
        val request = Request.Builder().url("https://github.com/yt-dlp/yt-dlp/releases/latest").head().build()
        return client.newCall(request).execute().use { response ->
            URI(response.request.url.toString()).path.substringAfterLast("/tag/", "").takeIf { it.isNotBlank() }
        }
    }

    private fun install() {
        _status.value = "Downloading checksums..."
        val expected = get("$RELEASE/SHA2-256SUMS").use { it.body.string() }.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == "yt-dlp.exe" }
            ?.get(0)?.lowercase()
            ?: throw IOException("yt-dlp.exe is not listed in SHA2-256SUMS")
        val staging = toolsDir.resolve("yt-dlp.new")
        staging.deleteRecursively()
        staging.mkdirs()
        val exe = staging.resolve("yt-dlp.exe")
        val digest = MessageDigest.getInstance("SHA-256")
        get("$RELEASE/yt-dlp.exe").use { response ->
            val total = response.body.contentLength()
            var read = 0L
            var lastPercent = -1
            response.body.byteStream().use { input ->
                exe.outputStream().use { output ->
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
                            _status.value = if (percent >= 0) "Downloading yt-dlp... $percent%" else "Downloading yt-dlp... ${read / 1_048_576} MB"
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            staging.deleteRecursively()
            throw IOException("checksum mismatch, the download was not installed")
        }
        runCatching { get(LICENSE_URL).use { staging.resolve("LICENSE.txt").writeText(it.body.string()) } }
        installDir.deleteRecursively()
        if (!staging.renameTo(installDir)) throw IOException("can't move yt-dlp into ${installDir.absolutePath}")
        refreshStatus()
    }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute().also {
        if (!it.isSuccessful) {
            it.close()
            throw IOException("HTTP ${it.code} for $url")
        }
    }

    private fun loadSettings(): Settings = runCatching { json.decodeFromString<Settings>(settingsFile.readText()) }.getOrDefault(Settings())

    private fun saveSettings(settings: Settings) {
        runCatching {
            toolsDir.mkdirs()
            settingsFile.writeText(json.encodeToString(settings))
        }
    }

    companion object {
        private const val RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
        private const val LICENSE_URL = "https://raw.githubusercontent.com/yt-dlp/yt-dlp/master/LICENSE"

        // sites yt-dlp handles well; their page URLs go to yt-dlp (Add URL, extension "page" jobs)
        private val VIDEO_SITE_HOSTS = listOf(
            "youtube.com", "youtu.be", "facebook.com", "fb.watch", "instagram.com", "x.com", "twitter.com",
            "tiktok.com", "vimeo.com", "dailymotion.com", "reddit.com", "twitch.tv",
        )

        fun isVideoSite(url: String): Boolean = runCatching {
            val host = URI(url).host?.lowercase() ?: return false
            VIDEO_SITE_HOSTS.any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)
    }
}
