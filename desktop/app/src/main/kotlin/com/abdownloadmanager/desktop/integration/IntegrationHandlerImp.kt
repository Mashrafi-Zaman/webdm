package com.abdownloadmanager.desktop.integration

import com.abdownloadmanager.desktop.AppComponent
import com.abdownloadmanager.desktop.repository.AppRepository
import com.abdownloadmanager.integration.IntegrationHandler
import com.abdownloadmanager.integration.model.*
import com.abdownloadmanager.shared.downloaderinui.BasicDownloadItem
import com.abdownloadmanager.shared.downloaderinui.DownloaderInUiRegistry
import com.abdownloadmanager.shared.pages.adddownload.AddDownloadCredentialsInUiProps
import com.abdownloadmanager.shared.pages.adddownload.ImportOptions
import com.abdownloadmanager.shared.pages.adddownload.SilentImportOptions
import com.abdownloadmanager.shared.util.DownloadSystem
import ir.amirab.downloader.NewDownloadItemProps
import ir.amirab.downloader.downloaditem.EmptyContext
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.hls.HLSMediaTools
import ir.amirab.downloader.downloaditem.hls.HLSPlaylistBuilder
import com.abdownloadmanager.desktop.utils.AppInfo
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import ir.amirab.downloader.queue.QueueManager
import ir.amirab.downloader.utils.OnDuplicateStrategy
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class IntegrationHandlerImp : IntegrationHandler, KoinComponent {
    val appComponent by inject<AppComponent>()
    val downloadSystem by inject<DownloadSystem>()
    val queueManager by inject<QueueManager>()
    val appSettings by inject<AppRepository>()
    private val downloaderInUiRegistry by inject<DownloaderInUiRegistry>()
    private val mediaTools by inject<HLSMediaTools>()
    private val ytDlp by inject<com.abdownloadmanager.desktop.tools.YtDlpManager>()

    override suspend fun addDownloadByGui(
        request: AddDownloadsFromIntegration
    ) {
        val list = request.items
        val options = request.options
        appComponent.externalCredentialComingIntoApp(
            list.map {
                convertToDownloadSystemCredentials(it)
            },
            options = ImportOptions(
                silentImport = if (options.silentAdd) {
                    SilentImportOptions(
                        silentDownload = options.silentStart
                    )
                } else null
            )
        )
    }

    override fun capabilities(): ApiCapabilities {
        return ApiCapabilities(
            app = AppInfo.displayName,
            version = AppInfo.version.toString(),
            jobTypes = ApiCapabilities.JOB_TYPES.filter { it != "page" || mediaTools.siteExtractor() != null },
            features = ApiCapabilities.baseFeatures() + mapOf(
                // separate audio tracks, SAMPLE-AES and .ts -> .mp4 need it
                "ffmpeg" to (mediaTools.ffmpegPath() != null),
                // video pages (YouTube, Facebook ...) read with yt-dlp
                "ytdlp" to (mediaTools.siteExtractor() != null),
                // the extension may send a page's cookies (Settings > Tools > "Use browser cookies")
                "page.cookies" to ytDlp.useBrowserCookiesSetting.value,
            ),
        )
    }

    override fun listQueues(): List<ApiQueueModel> {
        return queueManager.getAll().map { downloadQueue ->
            val queueModel = downloadQueue.getQueueModel()
            ApiQueueModel(id = queueModel.id, name = queueModel.name)
        }
    }

    override suspend fun addDownload(task: NewDownloadTask): Long {
        val addDownloaderInUiProps = convertToDownloadSystemCredentials(task.downloadSource)
        val downloaderInUi = downloaderInUiRegistry.getDownloaderOf(
            addDownloaderInUiProps.credentials
        ) ?: error("Downloader for ${addDownloaderInUiProps.credentials::class.qualifiedName} not found")
        val downloadItem = downloaderInUi.createBareDownloadItem(
            addDownloaderInUiProps.credentials,
            basicDownloadItem = BasicDownloadItem(
                folder = task.folder ?: appSettings.saveLocation.value,
                name = task.name ?: addDownloaderInUiProps.extraConfig.suggestedName
                ?: task.downloadSource.link.substringAfterLast("/"),
            ),
        )
        val id = downloadSystem.addDownload(
            newDownload = NewDownloadItemProps(
                downloadItem = downloadItem,
                onDuplicateStrategy = OnDuplicateStrategy.default(),
                extraConfig = null,
                context = EmptyContext,
            ),
            queueId = task.queueId,
            categoryId = task.categoryId,
        )
        val queueId = task.queueId
        // either start the queue or manually start the download
        // both of them at the same time is not good idea
        if (task.startQueue && queueId != null) {
            val queue = queueManager.getQueue(queueId)
            queue.start()
        } else {
            if (task.startDownload) {
                downloadSystem.userManualResume(id)
            }
        }
        return id
    }

    companion object {
        private fun convertToDownloadSystemCredentials(it: IDownloadCredentialsFromIntegration): AddDownloadCredentialsInUiProps {
            val credentials = when (it) {
                is HttpDownloadCredentialsFromIntegration -> {
                    HttpDownloadCredentials(
                        link = it.link,
                        headers = it.headers,
                        downloadPage = it.downloadPage,
                    )
                }

                is HLSDownloadCredentialsFromIntegration -> {
                    HLSDownloadCredentials(
                        link = it.link,
                        headers = it.headers,
                        downloadPage = it.downloadPage,
                    )
                }

                is SegmentsDownloadCredentialsFromIntegration -> {
                    // a stream rebuilt from its numbered segments: downloaded like an HLS stream whose playlist
                    // we build here (init + ordered segments), into one .mp4
                    HLSDownloadCredentials(
                        link = it.init ?: it.segments.firstOrNull() ?: it.link,
                        headers = it.headers,
                        downloadPage = it.downloadPage,
                        embeddedPlaylist = HLSPlaylistBuilder.build(it.init, it.segments, it.duration),
                        embeddedAudioPlaylist = it.audioSegments?.takeIf { list -> list.isNotEmpty() }?.let { audio ->
                            HLSPlaylistBuilder.build(it.audioInit, audio, it.duration)
                        },
                        displayQuality = it.quality,
                        estimatedSize = it.estimatedSize,
                    )
                }

                is PageDownloadCredentialsFromIntegration -> {
                    // a video page: yt-dlp lists its formats, WebDM downloads the chosen quality like an HLS stream
                    HLSDownloadCredentials(
                        link = it.link,
                        headers = it.headers,
                        downloadPage = it.downloadPage ?: it.link,
                        sitePage = true,
                        siteCookies = it.cookies?.takeIf { list -> list.isNotEmpty() }?.let { list ->
                            "# Netscape HTTP Cookie File\n" + list.joinToString("\n") { c -> c.toNetscapeLine() } + "\n"
                        },
                    )
                }

                is AvDownloadCredentialsFromIntegration -> {
                    // separate picture + sound files: each one downloaded in byte-range parts, merged with ffmpeg
                    HLSDownloadCredentials(
                        link = it.video,
                        headers = it.headers,
                        downloadPage = it.downloadPage,
                        embeddedPlaylist = HLSPlaylistBuilder.buildForWholeFile(it.video, it.videoSize, it.duration),
                        embeddedAudioPlaylist = it.audio?.let { audio ->
                            HLSPlaylistBuilder.buildForWholeFile(audio, it.audioSize, it.duration)
                        },
                        displayQuality = it.quality,
                        estimatedSize = it.videoSize?.let { v -> v + (it.audioSize ?: 0) },
                    )
                }
            }
            return AddDownloadCredentialsInUiProps(
                credentials = credentials,
                extraConfig = AddDownloadCredentialsInUiProps.Configs(
                    suggestedName = it.suggestedName,
                )
            )
        }
    }
}
