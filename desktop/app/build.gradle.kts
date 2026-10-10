import buildlogic.CiUtils
import buildlogic.versioning.*
import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import ir.amirab.util.platform.Arch
import ir.amirab.util.platform.Platform
import ir.amirab.util.platform.isWindows
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    id(MyPlugins.kotlin)
    id(MyPlugins.composeDesktop)
    id(Plugins.Kotlin.serialization)
    id(Plugins.ksp)
    id(Plugins.aboutLibraries)
    id(Plugins.kotlinRpc)
    id("ir.amirab.installer-plugin")
//    id(MyPlugins.proguardDesktop)
}


dependencies {
    implementation(libs.decompose)
    implementation(libs.decompose.jbCompose)

    implementation(libs.koin.core)

    implementation(libs.kotlin.serialization.json)

    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.coroutines.swing)

    implementation(libs.kotlin.datetime)

    implementation(libs.compose.reorderable)

    implementation(libs.arrow.core)
    implementation(libs.arrow.optics)
    ksp(libs.arrow.opticKsp)

    implementation(libs.androidx.datastore)

    implementation(libs.aboutLibraries.core)
    implementation(libs.markdownRenderer.core)
    implementation(libs.filekit.dialogs.compose) {
        exclude(group = "net.java.dev.jna")
    }
    implementation(libs.proxyVole) {
        exclude(group = "net.java.dev.jna")
    }
    implementation(libs.jna.core)
    implementation(libs.jna.platform)

    implementation(libs.kotlin.reflect) // used by kotlin-rpc
    implementation(libs.kotlinx.rpc.client)
    implementation(libs.kotlinx.rpc.client.ktor)
    implementation(libs.kotlinx.rpc.server)
    implementation(libs.kotlinx.rpc.server.ktor)
    implementation(libs.kotlinx.rpc.serializationJson)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.server.cio)

    // cli
    implementation(libs.clikit)

    implementation(project(":downloader:core"))
    implementation(project(":downloader:monitor"))

    implementation(project(":integration:server"))
    implementation(project(":desktop:shared"))
    implementation(project(":desktop:app-utils"))

    implementation(libs.composeNativeTray)
    implementation(libs.nucleus.aot)

    implementation(project(":shared:app"))
    implementation(project(":shared:utils"))
    implementation(project(":shared:updater"))
    implementation(project(":desktop:mac_utils"))
    implementation(project(":desktop:slf4j-impl"))
}

aboutLibraries {
    export {
        prettyPrint = true
    }
    library {
        mergePlatformArtifacts = true
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.SIMPLE
    }
}

val isAOTEnabled = false

tasks.processResources {
    from(tasks.named("exportLibraryDefinitions"))
}

val cliBinaryName = "${getAppName()}Cli"
val nativeMessagingHostBinaryName = "${getAppName()}NativeMessagingHost"

val desktopPackageName = "com.abdownloadmanager.desktop"
nucleus {
    application {
//            val getProguardConfigurationsTask = tasks.getProguardConfigurations.get()
        buildTypes.release.proguard {
            isEnabled.set(false)
//                obfuscate.set(false)
//                optimize.set(true)
//                configurationFiles.from(
//                    project.fileTree("proguard"),
//                    getProguardConfigurationsTask.outputs.files.asFileTree.filter {
//                        !it.name.contains("r8")
//                    },
//                )
        }

        // Define the main class for the application.
        mainClass = "$desktopPackageName.AppKt"
        additionalLaunchers {
            create(cliBinaryName) {
                mainClass = "$desktopPackageName.cli.CliAppKt"
                winConsole = true
                jvmArgs(*defaultJvmArgs())
            }
            create(nativeMessagingHostBinaryName) {
                mainClass = "$desktopPackageName.nativemessaging.host.NativeMessagingHostKt"
                winConsole = true
                jvmArgs(
                    *defaultJvmArgs(),
                    // we don't want to write anything other than browser's stdio!
                    "-Xlog:disable"
                )
            }
        }
        nativeDistributions {
            cleanupNativeLibs = true
            aotCache {
                enabled = isAOTEnabled
            }
            modules(
                "java.instrument",
                "jdk.unsupported",
                "jdk.accessibility",
            )
            packageVersion = getAppVersionStringForPackaging()
            packageName = getAppName()
            vendor = "WebDM"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            val menuGroupName = getPrettifiedAppName()
            licenseFile.set(rootProject.file("LICENSE"))
            // WebDM targets Windows x64 only; Linux/macOS packaging is not configured.
            windows {
                upgradeUuid = providers.gradleProperty("INSTALLER.WINDOWS.UPGRADE_UUID").orNull
                iconFile = project.file("icons/icon.ico")
                console = false
                dirChooser = true
                shortcut = true
                menuGroup = menuGroupName
                menu = true
            }
        }
    }
}

installerPlugin {
    dependsOn("createReleaseDistributable")
    outputFolder.set(layout.buildDirectory.dir("custom-installer"))
    windows {
        appName = getAppName()
        appDisplayName = getPrettifiedAppName()
        appVersion = getAppVersionStringForPackaging(TargetFormat.Exe)
        appDisplayVersion = getAppVersionString()
        appDataDirName = getAppDataDirName()
        inputDir = project.file("build/compose/binaries/main-release/app/${getAppName()}")
        outputFileName = getAppName()
        licenceFile = rootProject.file("LICENSE")
        iconFile = project.file("icons/icon.ico")
        nsisTemplate = project.file("resources/installer/nsis-script-template.nsi")
        extraParams = mapOf(
            "native_messaging_host_binary_name" to nativeMessagingHostBinaryName,
            "cli_binary_name" to cliBinaryName,
            "app_publisher" to "WebDM",
            "app_version_with_build" to "${getAppVersionStringForPackaging(TargetFormat.Exe)}.0",
            "source_code_url" to "https://github.com/Mashrafi-Zaman/webdm",
            "project_website" to "https://github.com/Mashrafi-Zaman/webdm",
            "copyright" to "© 2024-present AB Download Manager App; © 2026-present WebDM contributors",
            "header_image_file" to project.file("resources/installer/webdm-header-image.bmp"),
            "sidebar_image_file" to project.file("resources/installer/webdm-sidebar-image.bmp")
        )
    }
}

/**
 * Temporary workaround. needs to be improved by the plugin.
 * I want to add default jvm-args used by the default launcher.
 * however when I add a single item to the jvmArgs, all default values are gone!
 * so I have to manually add these default values to each additional launcher
 */
fun defaultJvmArgs(): Array<String> {
    fun appDir(vararg pathParts: String): String {
        /** For windows we need to pass '\\' to jpackage file, each '\' need to be escaped.
        Otherwise '$APPDIR\resources' is passed to jpackage,
        and '\r' is treated as a special character at run time.
         */
        val separator = if (Platform.isWindows()) "\\\\" else "/"
        return listOf($$"$APPDIR", *pathParts).joinToString(separator) { it }
    }

    return buildList {
        if (isAOTEnabled) {
            add("-XX:AOTCache=${appDir("app.aot")}")
        }
//        add("-Djpackage.app-version=1.0.0")
        add("-Dcompose.application.resources.dir=${appDir("resources")}")
        add("-Dcompose.application.configure.swing.globals=true")
        add("--enable-native-access=ALL-UNNAMED")
        add("-Dnucleus.executable.type=dev")
        add("-Dnucleus.app.id=${getAppName()}")
        add("-Dskiko.library.path=${appDir()}")
    }.toTypedArray()
}

fun Task.dependsOnAOT() {
    if (isAOTEnabled) {
        dependsOn("generateReleaseAotCache")
    }
}

// ======= bundled tools
// The LGPL ffmpeg (Windows x64) ships with the app, in its resources (resources/windows/tools/ffmpeg -> at runtime
// <app>/resources/tools/ffmpeg). Downloaded at build time from github.com/BtbN/FFmpeg-Builds and checked against
// that release's checksums.sha256; not kept in git. Users can still update it from Settings > Tools.
val bundledFfmpegZip = "ffmpeg-n8.1-latest-win64-lgpl-8.1.zip"
val bundledFfmpegRelease = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"
val bundledFfmpegDir = project.layout.projectDirectory.dir("resources/windows/tools/ffmpeg")
val downloadBundledFfmpeg = tasks.register("downloadBundledFfmpeg") {
    description = "Downloads the LGPL ffmpeg build that is packaged with the app"
    val outDir = bundledFfmpegDir.asFile
    outputs.dir(outDir)
    onlyIf { !outDir.resolve("ffmpeg.exe").isFile }
    doLast {
        fun open(url: String) = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
        }.getInputStream()
        val expected = open("$bundledFfmpegRelease/checksums.sha256").bufferedReader().use { it.readText() }
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == bundledFfmpegZip }
            ?.get(0)?.lowercase()
            ?: error("$bundledFfmpegZip is not listed in checksums.sha256")
        val zip = layout.buildDirectory.file("tmp/$bundledFfmpegZip").get().asFile
        zip.parentFile.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        open("$bundledFfmpegRelease/$bundledFfmpegZip").use { input ->
            zip.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "checksum mismatch for $bundledFfmpegZip" }
        outDir.deleteRecursively()
        outDir.mkdirs()
        ZipInputStream(zip.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                val wanted = (entry.name.contains("/bin/") && name == "ffmpeg.exe") ||
                        (!entry.isDirectory && name == "LICENSE.txt" && entry.name.count { it == '/' } <= 1)
                if (wanted) outDir.resolve(name).outputStream().use { zin.copyTo(it) }
            }
        }
        check(outDir.resolve("ffmpeg.exe").isFile) { "ffmpeg.exe not found in $bundledFfmpegZip" }
        outDir.resolve("SOURCE.txt").writeText(
            "ffmpeg (LGPL build) from $bundledFfmpegRelease/$bundledFfmpegZip\n" +
                    "sha256 $actual\n" +
                    "Source code: https://ffmpeg.org/download.html and https://github.com/BtbN/FFmpeg-Builds\n"
        )
        zip.delete()
    }
}
// yt-dlp (Unlicense) reads video pages (YouTube, Facebook ...): resources/windows/tools/yt-dlp, checked against the
// release's SHA2-256SUMS. Users can update it from Settings > Tools.
val bundledYtDlpRelease = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
val bundledYtDlpDir = project.layout.projectDirectory.dir("resources/windows/tools/yt-dlp")
val downloadBundledYtDlp = tasks.register("downloadBundledYtDlp") {
    description = "Downloads the yt-dlp.exe that is packaged with the app"
    val outDir = bundledYtDlpDir.asFile
    outputs.dir(outDir)
    onlyIf { !outDir.resolve("yt-dlp.exe").isFile }
    doLast {
        fun open(url: String) = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
        }.getInputStream()
        val expected = open("$bundledYtDlpRelease/SHA2-256SUMS").bufferedReader().use { it.readText() }
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == "yt-dlp.exe" }
            ?.get(0)?.lowercase()
            ?: error("yt-dlp.exe is not listed in SHA2-256SUMS")
        outDir.deleteRecursively()
        outDir.mkdirs()
        val exe = outDir.resolve("yt-dlp.exe")
        val digest = MessageDigest.getInstance("SHA-256")
        open("$bundledYtDlpRelease/yt-dlp.exe").use { input ->
            exe.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            outDir.deleteRecursively()
            error("checksum mismatch for yt-dlp.exe")
        }
        outDir.resolve("LICENSE.txt").writeText(
            open("https://raw.githubusercontent.com/yt-dlp/yt-dlp/master/LICENSE").bufferedReader().use { it.readText() }
        )
        outDir.resolve("SOURCE.txt").writeText(
            "yt-dlp from $bundledYtDlpRelease/yt-dlp.exe\nsha256 $actual\nSource code: https://github.com/yt-dlp/yt-dlp\n"
        )
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(downloadBundledFfmpeg, downloadBundledYtDlp) }
// ======= end of bundled tools

val postReleaseDistributable = tasks.register("postReleaseDistributable") {
    dependsOn("createReleaseDistributable")
    description = "Any modification need to be added to the app distributable folder, should be added here"
    dependsOnAOT()
}
installerPlugin.dependsOn(postReleaseDistributable)

// ======= begin of GitHub action stuff
val ciDir = CiUtils.getCiDir(project)

val appPackageNameByComposePlugin
    get() = requireNotNull(nucleus.application.nativeDistributions.packageName) {
        "compose.desktop.application.nativeDistributions.packageName must not be null!"
    }

val distributableAppArchiveDir: Provider<Directory> =
    project.layout.buildDirectory.dir("dist/archives")

fun AbstractArchiveTask.fromAppImagePath() {
    from(tasks.named("createReleaseDistributable"))
    destinationDirectory.set(distributableAppArchiveDir)
}

/**
 * gradle 9 removes file permissions and timestamp by default in archive tasks!. but we want them!
 */
fun AbstractArchiveTask.preserveFileAttributes() {
    // Make file order based on the file system
    isReproducibleFileOrder = false
    // Use file timestamps from the file system
    isPreserveFileTimestamps = true
    // Use permissions from the file system
    useFileSystemPermissions()
}

val createDistributableAppArchiveZip = tasks.register("createDistributableAppArchiveZip", Zip::class) {
    dependsOn(postReleaseDistributable)
    preserveFileAttributes()
    archiveFileName.set("app.zip")
    fromAppImagePath()
}
// WebDM ships Windows x64 only (.exe installer + .zip)
val createDistributableAppArchive = tasks.register("createDistributableAppArchive") {
    dependsOn(createDistributableAppArchiveZip)
}

tasks.register(CiUtils.getCreateBinaryFolderForCiTaskName()) {
    doFirst {
        require(Platform.isWindows() && Arch.getCurrentArch() == Arch.X64) {
            "WebDM release binaries can only be built on Windows x64 (current: ${Platform.getCurrentPlatform()} ${Arch.getCurrentArch()})"
        }
    }
    if (installerPlugin.isThisPlatformSupported()) {
        dependsOn(installerPlugin.createInstallerTask)
        inputs.dir(installerPlugin.outputFolder)
    }
    dependsOn(createDistributableAppArchive)
    inputs.property("appVersion", getAppVersionString())
    inputs.dir(distributableAppArchiveDir)
    outputs.dir(ciDir.binariesDir)
    doLast {
        val output = ciDir.binariesDir.get().asFile
        val packageName = appPackageNameByComposePlugin

        if (installerPlugin.isThisPlatformSupported()) {
            val targets = installerPlugin.getCreatedInstallerTargetFormats()
            for (target in targets) {
                CiUtils.movePackagedAndCreateSignature(
                    appVersion = getAppVersion(),
                    packageName = packageName,
                    target = target,
                    basePackagedAppsDir = installerPlugin.outputFolder.get().asFile,
                    outputDir = output,
                )
            }
            logger.lifecycle("app packages for '${targets.joinToString(", ") { it.name }}' written in $output using the installer plugin")
        }
        val appArchiveDistributableDir = distributableAppArchiveDir.get().asFile
        CiUtils.copyAndHashToDestination(
            distributableAppArchiveDir.get().asFile.resolve(
                CiUtils.getFileOfDistributedArchivedTarget(
                    appArchiveDistributableDir,
                )
            ),
            output,
            CiUtils.getTargetFileName(
                packageName,
                getAppVersion(),
                null, // this is not an installer (it will be automatically converted to current os name
                Arch.getCurrentArch().name
            )
        )
        logger.lifecycle("distributable app archive written in ${output}")
    }
}
// ======= end of GitHub action stuff
