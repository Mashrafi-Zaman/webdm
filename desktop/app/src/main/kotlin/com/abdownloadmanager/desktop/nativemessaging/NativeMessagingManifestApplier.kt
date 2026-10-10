package com.abdownloadmanager.desktop.nativemessaging

import com.abdownloadmanager.desktop.utils.AppInfo
import com.abdownloadmanager.desktop.utils.AppProperties
import com.abdownloadmanager.desktop.utils.isAppInstalled
import ir.amirab.util.createParentDirectories
import ir.amirab.util.deleteIfExists
import ir.amirab.util.desktop.WindowsRegistry
import ir.amirab.util.platform.Platform
import ir.amirab.util.writeText
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toOkioPath
import org.koin.core.component.KoinComponent
import kotlin.io.path.Path

abstract class NativeMessagingManifestApplier(val json: Json) : KoinComponent {
    protected inline fun <reified T : Any> serialize(data: T): String {
        return json.encodeToString(data)
    }

    protected inline fun <reified T : Any> deserialize(string: String): T {
        return json.decodeFromString(string)
    }

    abstract fun updateManifests(manifests: NativeMessagingManifests)
    abstract fun removeManifests()

    protected fun writeManifestContent(
        path: Path,
        manifestContent: String
    ) {
        path.createParentDirectories()
        path.writeText(manifestContent)
    }

    companion object {
        fun getForCurrentPlatform(json: Json): NativeMessagingManifestApplier {
            if (!AppInfo.isAppInstalled()) {
                return NoOpNativeMessagingApplier(json)
            }
            return when (AppInfo.platform) {
                Platform.Desktop.Linux -> LinuxNativeMessagingManifestApplier(json)
                Platform.Desktop.MacOS -> MacosNativeMessagingManifestApplier(json)
                Platform.Desktop.Windows -> WindowsNativeMessagingManifestApplier(json)
                Platform.Android -> error("there is no native messaging for android so this code should never used in android")
            }
        }
    }
}

class WindowsNativeMessagingManifestApplier(json: Json) : NativeMessagingManifestApplier(json) {
    private val baseNativeMessagingDir get() = AppInfo.definedPaths.configDir / "native_messaging"
    private val chromeManifestFile get() = baseNativeMessagingDir / "chrome" / "${AppInfo.packageName}.json"

    // Chromium browsers read their own HKCU key; Opera (and most other Chromium forks) read Chrome's
    private val chromiumRegistryPaths
        get() = listOf(
            "Google\\Chrome",
            "Microsoft\\Edge",
            "BraveSoftware\\Brave-Browser",
        ).map { "HKCU\\SOFTWARE\\$it\\NativeMessagingHosts\\${AppInfo.packageName}" }

    // left behind by older versions that also supported Firefox
    private val legacyFirefoxManifestFile get() = baseNativeMessagingDir / "firefox" / "${AppInfo.packageName}.json"
    private val legacyFirefoxRegistryPath get() = "HKCU\\SOFTWARE\\Mozilla\\NativeMessagingHosts\\${AppInfo.packageName}"

    override fun updateManifests(
        manifests: NativeMessagingManifests
    ) {
        removeLegacyFirefoxManifest()
        writeManifestContent(chromeManifestFile, serialize(manifests.chromeNativeMessagingManifest))
        chromiumRegistryPaths.forEach { path ->
            WindowsRegistry.setValueInRegistry(
                path = path,
                key = null,
                value = chromeManifestFile.toString()
            )
        }
    }

    override fun removeManifests() {
        removeLegacyFirefoxManifest()
        chromeManifestFile.deleteIfExists()
        chromiumRegistryPaths.forEach { path ->
            WindowsRegistry.removePathInRegistry(path = path)
        }
    }

    private fun removeLegacyFirefoxManifest() {
        legacyFirefoxManifestFile.deleteIfExists()
        WindowsRegistry.removePathInRegistry(path = legacyFirefoxRegistryPath)
    }
}

class MacosNativeMessagingManifestApplier(
    json: Json
) : NativeMessagingManifestApplier(json) {

    private val nativeMessagingFileName = "${AppInfo.packageName}.json"

    private fun createNativeMessagingPath(appSupportDir: String) =
        Path(
            AppProperties.userDir,
            "Library/Application Support",
            appSupportDir,
            "NativeMessagingHosts",
            nativeMessagingFileName
        ).toOkioPath()


    private val chromiumBasedNativeMessagingPaths = listOf(
        createNativeMessagingPath("Google/Chrome"),
        createNativeMessagingPath("Chromium"),
    )

    override fun updateManifests(manifests: NativeMessagingManifests) {
        val chromiumManifest = serialize(manifests.chromeNativeMessagingManifest)
        chromiumBasedNativeMessagingPaths.forEach { path ->
            writeManifestContent(path, chromiumManifest)
        }
    }

    override fun removeManifests() {
        chromiumBasedNativeMessagingPaths.forEach { path ->
            path.deleteIfExists()
        }
    }
}

class LinuxNativeMessagingManifestApplier(
    json: Json
) : NativeMessagingManifestApplier(json) {

    private val nativeMessagingFileName = "${AppInfo.packageName}.json"



    private fun createChromiumNativeMessagingPath(browserConfigDir: String) = Path(
        AppProperties.userDir,
        ".config",
        browserConfigDir,
        "NativeMessagingHosts",
        nativeMessagingFileName
    ).toOkioPath()

    private val chromiumBasedNativeMessagingPaths
        get() = listOf(
            createChromiumNativeMessagingPath("google-chrome"),
            createChromiumNativeMessagingPath("chromium"),
            createChromiumNativeMessagingPath("BraveSoftware/Brave-Browser"),
            createChromiumNativeMessagingPath("microsoft-edge"),
            createChromiumNativeMessagingPath("vivaldi"),
            createChromiumNativeMessagingPath("opera")
        )

    override fun updateManifests(manifests: NativeMessagingManifests) {
        val chromiumManifest = serialize(manifests.chromeNativeMessagingManifest)
        chromiumBasedNativeMessagingPaths.forEach { path ->
            writeManifestContent(path, chromiumManifest)
        }
    }

    override fun removeManifests() {
        chromiumBasedNativeMessagingPaths.forEach { path ->
            path.deleteIfExists()
        }
    }
}

class NoOpNativeMessagingApplier(
    json: Json
) : NativeMessagingManifestApplier(
    json,
) {
    override fun updateManifests(manifests: NativeMessagingManifests) {
        //no-op
    }

    override fun removeManifests() {
        //no-op
    }
}
