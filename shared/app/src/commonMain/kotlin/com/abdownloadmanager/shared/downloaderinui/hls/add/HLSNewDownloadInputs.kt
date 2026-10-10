package com.abdownloadmanager.shared.downloaderinui.hls.add

import com.abdownloadmanager.resources.Res
import androidx.compose.runtime.mutableStateListOf
import com.abdownloadmanager.shared.ui.configurable.item.EnumConfigurable
import com.abdownloadmanager.shared.util.convertPositiveBytesToHumanReadable
import ir.amirab.downloader.downloaditem.hls.HLSEncryption
import ir.amirab.downloader.downloaditem.hls.HLSVariantOption
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.abdownloadmanager.shared.downloaderinui.DownloadSize
import com.abdownloadmanager.shared.downloaderinui.add.NewDownloadInputs
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import com.abdownloadmanager.shared.downloaderinui.hls.HLSLinkChecker
import ir.amirab.downloader.downloaditem.hls.HLSResponseInfo
import com.abdownloadmanager.shared.downloaderinui.http.applyToHttpDownload
import com.abdownloadmanager.shared.ui.configurable.item.FileChecksumConfigurable
import com.abdownloadmanager.shared.ui.configurable.item.IntConfigurable
import com.abdownloadmanager.shared.ui.configurable.item.ToggleableSpeedLimitConfigurable
import com.abdownloadmanager.shared.ui.configurable.item.StringConfigurable
import com.abdownloadmanager.shared.util.SizeAndSpeedUnitProvider
import com.abdownloadmanager.shared.util.ThreadCountLimitation
import com.abdownloadmanager.shared.util.FileChecksum
import com.abdownloadmanager.shared.util.convertPositiveSpeedToHumanReadable
import com.abdownloadmanager.shared.util.perhostsettings.PerHostSettingsItem
import ir.amirab.downloader.downloaditem.DownloadJobExtraConfig
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.downloader.downloaditem.hls.HLSDownloadItem
import ir.amirab.downloader.downloaditem.hls.HLSDownloadJobExtraConfig
import ir.amirab.util.compose.StringSource
import ir.amirab.util.compose.asStringSource
import ir.amirab.util.compose.asStringSourceWithARgs
import ir.amirab.util.flow.combineStateFlows
import ir.amirab.util.flow.createMutableStateFlowFromStateFlow
import ir.amirab.util.flow.mapStateFlow
import ir.amirab.util.flow.mapTwoWayStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class HLSNewDownloadInputs(
    private val downloadUiChecker: HLSNewDownloadUIChecker,
    private val sizeAndSpeedUnitProvider: SizeAndSpeedUnitProvider,
    private val scope: CoroutineScope,
) : NewDownloadInputs<
        HLSDownloadItem,
        HLSDownloadCredentials,
        HLSResponseInfo,
        DownloadSize.Duration,
        HLSLinkChecker,
        >(
    newDownloadUiChecker = downloadUiChecker
) {

    //extra settings
    private var threadCount = MutableStateFlow(null as Int?)
    private var speedLimit = MutableStateFlow(0L)
    private var fileChecksum = MutableStateFlow(null as FileChecksum?)
    override val downloadItem: StateFlow<HLSDownloadItem> = combineStateFlows(
        this.credentials,
        this.folder,
        this.name,
        this.downloadSize,
        this.speedLimit,
        this.threadCount,
        this.fileChecksum,
    ) { credentials,
        folder,
        name,
        duration,
        speedLimit,
        threadCount,
        fileChecksum
        ->
        HLSDownloadItem(
            id = -1,
            folder = folder,
            name = name,
            link = credentials.link,
            dateAdded = openedTime,
            startTime = null,
            completeTime = null,
            status = DownloadStatus.Added,
            preferredConnectionCount = threadCount,
            speedLimit = speedLimit,
            fileChecksum = fileChecksum?.toString(),
            duration = duration?.duration,
        ).withCredentials(credentials)
    }
    override val downloadJobConfig: StateFlow<DownloadJobExtraConfig?> = downloadUiChecker.responseInfo.mapStateFlow {
        it?.let {
            HLSDownloadJobExtraConfig(
                stream = it.stream
            )
        }
    }

    // ---------- quality (master playlists) ----------
    // a snapshot list so the dropdown updates when the qualities are known (after the playlist is fetched)
    private val qualityOptions = mutableStateListOf<HLSVariantOption>()
    // placeholder while there is no list (the dropdown is hidden then)
    private val noQuality = HLSVariantOption(url = "", bandwidth = 0, width = null, height = null, audioGroup = null)
    private val selectedQuality = MutableStateFlow(noQuality)
    private val hasQualities = downloadUiChecker.variants.mapStateFlow { it.isNotEmpty() }

    init {
        downloadUiChecker.variants.onEach { variants ->
            if (variants.map { it.url } == qualityOptions.map { it.url }) return@onEach
            qualityOptions.clear()
            qualityOptions.addAll(variants)
            // default: the chosen one (if still offered), else the highest
            val chosen = variants.firstOrNull { it.url == credentials.value.selectedVariantUrl } ?: variants.firstOrNull()
            selectedQuality.value = chosen ?: noQuality
        }.launchIn(scope)
        selectedQuality.onEach { quality ->
            if (quality.url.isNotEmpty() && quality.url != credentials.value.selectedVariantUrl) {
                setCredentials(credentials.value.copy(selectedVariantUrl = quality.url))
            }
        }.launchIn(scope)
    }

    private val qualityConfigurable = EnumConfigurable(
        title = "Quality".asStringSource(),
        description = "Video quality to download (master playlist)".asStringSource(),
        backedBy = selectedQuality,
        describe = { qualityLabel(it).asStringSource() },
        possibleValues = qualityOptions,
        valueToString = { listOf(qualityLabel(it)) },
        visible = hasQualities,
    )

    private fun qualityLabel(q: HLSVariantOption): String {
        if (q.url.isEmpty()) return ""
        val resolution = if (q.width != null && q.height != null) " (${q.width}x${q.height})" else ""
        return "${q.label}$resolution - ${q.bandwidth / 1000} kbps"
    }

    override fun applyHostSettingsToExtraConfig(extraConfig: PerHostSettingsItem) {
        extraConfig.applyToHttpDownload(
            setUsername = { setCredentials(credentials.value.copy(username = it)) },
            setPassword = { setCredentials(credentials.value.copy(password = it)) },
            setUserAgent = { setCredentials(credentials.value.copy(userAgent = it)) },
            setThreadCount = { threadCount.value = it },
            setSpeedLimit = { speedLimit.value = it }
        )
    }

    override val configurableList = listOf(
        qualityConfigurable,
        ToggleableSpeedLimitConfigurable(
            Res.string.download_item_settings_speed_limit.asStringSource(),
            Res.string.download_item_settings_speed_limit_description.asStringSource(),
            backedBy = speedLimit,
            describe = {
                if (it == 0L) Res.string.unlimited.asStringSource()
                else convertPositiveSpeedToHumanReadable(
                    it, sizeAndSpeedUnitProvider.speedUnit.value
                ).asStringSource()
            }
        ),
        FileChecksumConfigurable(
            Res.string.download_item_settings_file_checksum.asStringSource(),
            Res.string.download_item_settings_file_checksum_description.asStringSource(),
            backedBy = fileChecksum,
            describe = { "".asStringSource() }
        ),
        IntConfigurable(
            Res.string.settings_download_thread_count.asStringSource(),
            Res.string.settings_download_thread_count_description.asStringSource(),
            backedBy = threadCount.mapTwoWayStateFlow(
                map = {
                    it ?: 0
                },
                unMap = {
                    it.takeIf { it >= 1 }
                }
            ),
            range = 0..ThreadCountLimitation.MAX_ALLOWED_THREAD_COUNT,
            describe = {
                if (it == 0) Res.string.use_global_settings.asStringSource()
                else Res.string.download_item_settings_thread_count_describe
                    .asStringSourceWithARgs(
                        Res.string.download_item_settings_thread_count_describe_createArgs(
                            count = it.toString()
                        )
                    )
            }
        ),
        StringConfigurable(
            Res.string.username.asStringSource(),
            Res.string.download_item_settings_username_description.asStringSource(),
            backedBy = createMutableStateFlowFromStateFlow(
                flow = credentials.mapStateFlow {
                    it.username.orEmpty()
                },
                updater = {
                    setCredentials(credentials.value.copy(username = it.takeIf { it.isNotBlank() }))
                }, scope
            ),
            describe = {
                "".asStringSource()
            }
        ),
        StringConfigurable(
            Res.string.password.asStringSource(),
            Res.string.download_item_settings_password_description.asStringSource(),
            backedBy = createMutableStateFlowFromStateFlow(
                flow = credentials.mapStateFlow {
                    it.password.orEmpty()
                },
                updater = {
                    setCredentials(credentials.value.copy(password = it.takeIf { it.isNotBlank() }))
                }, scope
            ),
            describe = {
                "".asStringSource()
            }
        ),
        StringConfigurable(
            Res.string.download_item_settings_user_agent.asStringSource(),
            Res.string.download_item_settings_user_agent_description.asStringSource(),
            backedBy = credentials.mapTwoWayStateFlow(
                map = {
                    it.userAgent.orEmpty()
                },
                unMap = {
                    copy(userAgent = it.takeIf { it.isNotEmpty() })
                }
            ),
            describe = {
                "".asStringSource()
            }
        ),
        StringConfigurable(
            Res.string.download_item_settings_download_page.asStringSource(),
            Res.string.download_item_settings_download_page_description.asStringSource(),
            backedBy = credentials.mapTwoWayStateFlow(
                map = {
                    it.downloadPage.orEmpty()
                },
                unMap = {
                    copy(downloadPage = it.takeIf { it.isNotEmpty() })
                }
            ),
            describe = {
                "".asStringSource()
            }
        )
    )

    /** "10:00 - 1080p - 120 segments - ~11.4 MB" (plus notes like AES-128, separate audio, live) */
    override fun downloadSizeToStringSource(downloadSize: DownloadSize.Duration): StringSource {
        val stream = downloadUiChecker.responseInfo.value?.stream
        val c = credentials.value
        val parts = mutableListOf(formatDuration(downloadSize.duration))
        (stream?.selectedVariant?.label ?: c.displayQuality)?.let { parts += it }
        // a video site's files are byte-range parts of whole files: their count means nothing to the user
        if (!c.sitePage) stream?.let { parts += "${it.mediaSegmentCount} segments" }
        val exactSize = stream?.knownSize
        (exactSize ?: c.estimatedSize)?.takeIf { it > 0 }?.let { size ->
            convertPositiveBytesToHumanReadable(size, sizeAndSpeedUnitProvider.sizeUnit.value)
                ?.let { parts += if (exactSize != null) it else "~$it" }
        }
        if (stream?.audio != null) parts += if (c.sitePage) "video + audio" else "separate audio"
        if (stream?.encryption == HLSEncryption.Aes128) parts += "AES-128"
        if (stream?.isLive == true) parts += "live (records until you stop it)"
        return parts.joinToString(" - ").asStringSource()
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
