package com.abdownloadmanager.desktop.pages.settings

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.abdownloadmanager.desktop.pages.settings.SettingSection.*
import com.abdownloadmanager.desktop.repository.AppRepository
import com.abdownloadmanager.desktop.storage.AppSettingsStorage
import com.abdownloadmanager.desktop.storage.PageStatesStorage
import com.abdownloadmanager.desktop.utils.renderapi.CustomRenderApi
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.pagemanager.PerHostSettingsPageManager
import com.abdownloadmanager.shared.settings.BaseSettingsComponent
import com.abdownloadmanager.shared.settings.CommonSettings
import com.abdownloadmanager.shared.storage.impl.DNSStorage
import com.abdownloadmanager.shared.ui.configurable.ConfigurableGroup
import com.abdownloadmanager.shared.ui.configurable.item.BooleanConfigurable
import com.abdownloadmanager.shared.ui.configurable.item.NavigatableConfigurable
import com.abdownloadmanager.shared.ui.configurable.item.StringConfigurable
import com.abdownloadmanager.desktop.tools.FfmpegManager
import ir.amirab.util.flow.createMutableStateFlowFromStateFlow
import com.abdownloadmanager.shared.ui.theme.ThemeManager
import com.abdownloadmanager.shared.util.proxy.ProxyManager
import com.abdownloadmanager.shared.util.ui.icon.MyIcons
import com.arkivanov.decompose.ComponentContext
import ir.amirab.util.compose.IconSource
import ir.amirab.util.compose.StringSource
import ir.amirab.util.compose.asStringSource
import ir.amirab.util.compose.localizationmanager.LanguageManager
import ir.amirab.util.flow.mapStateFlow
import ir.amirab.util.flow.mapTwoWayStateFlow
import kotlinx.coroutines.flow.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

sealed class SettingSection(
    val icon: IconSource,
    val name: StringSource,
) {
    data object Appearance :
        SettingSection(MyIcons.appearance, Res.string.appearance.asStringSource())

    //    TODO ADD Network section (proxy , etc..)
    //    data object Network : SettingSections(MyIcons.network, "Network")
    data object DownloadEngine :
        SettingSection(MyIcons.downloadEngine, Res.string.download_engine.asStringSource())

    data object BrowserIntegration :
        SettingSection(MyIcons.network, Res.string.browser_integration.asStringSource())

    // external tools (ffmpeg) used for some video downloads
    data object Tools :
        SettingSection(MyIcons.settings, "Tools".asStringSource())
}

interface SettingSectionGetter {
    operator fun get(key: SettingSection): List<ConfigurableGroup>
}

class DesktopSettingsComponent(
    ctx: ComponentContext,
    val perHostSettingsPageManager: PerHostSettingsPageManager,
) : BaseSettingsComponent(ctx),
    KoinComponent {
    private val appSettings by inject<AppSettingsStorage>()
    private val pageStorage by inject<PageStatesStorage>()
    private val appRepository by inject<AppRepository>()
    private val proxyManager by inject<ProxyManager>()
    private val dNSStorage by inject<DNSStorage>()
    private val themeManager by inject<ThemeManager>()
    private val languageManager by inject<LanguageManager>()
    private val fontManager by inject<FontManager>()
    private val customRenderApi by inject<CustomRenderApi>()
    private val ffmpegManager by inject<FfmpegManager>()
    private val allConfigs = object : SettingSectionGetter {
        override operator fun get(key: SettingSection): List<ConfigurableGroup> {
            return when (key) {
                Appearance -> listOf(
                    ConfigurableGroup(
                        mainConfigurable = CommonSettings.themeConfig(themeManager, scope),
                        nestedVisible = themeManager.currentThemeInfo.mapStateFlow {
                            it.id == ThemeManager.systemThemeInfo.id
                        },
                        nestedConfigurable = listOfNotNull(
                            CommonSettings.defaultDarkThemeConfig(themeManager, scope),
                            CommonSettings.defaultLightThemeConfig(themeManager, scope),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.languageConfig(languageManager, scope),
                            DesktopSettings.fontConfig(fontManager, scope),
                            CommonSettings.uiScaleConfig(appSettings),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOfNotNull(
                            DesktopSettings.useNativeMenuBarConfig(appSettings),
                            DesktopSettings.mergeTopBarWithTitleBarConfig(appSettings),
                            CommonSettings.showIconLabels(appSettings),
                            CommonSettings.useRelativeDateTime(appSettings),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.autoStartConfig(appSettings),
                            DesktopSettings.useSystemTray(appSettings),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.sizeUnit(appRepository, scope),
                            CommonSettings.speedUnit(appRepository, scope),
                            CommonSettings.useAverageSpeedConfig(appRepository),
                        )
                    ),
                    ConfigurableGroup(
                        mainConfigurable = CommonSettings.playSoundNotification(appSettings),
                        nestedVisible = appSettings.notificationSound,
                        nestedConfigurable = listOf(
                            CommonSettings.generalNotificationSound(appSettings),
                            CommonSettings.errorNotificationSound(appSettings),
                            CommonSettings.successNotificationSound(appSettings),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.autoShowDownloadProgressWindow(appSettings),
                            CommonSettings.showDownloadFinishWindow(appSettings),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            DesktopSettings.renderApi(customRenderApi),
                        )
                    )
                )

//                Network -> listOf()
                BrowserIntegration -> listOf(
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.browserIntegrationEnabled(appRepository),
                            CommonSettings.browserIntegrationPort(appRepository),
                            CommonSettings.apiAuthEnabled(appRepository),
                            CommonSettings.apiAuthKey(appRepository),
                        )
                    )
                )

                Tools -> listOf(
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            StringConfigurable(
                                title = "ffmpeg".asStringSource(),
                                description = ("Needed for videos with a separate audio track, SAMPLE-AES encrypted " +
                                        "streams and converting .ts to .mp4. WebDM only copies streams with it " +
                                        "(no re-encoding).").asStringSource(),
                                backedBy = createMutableStateFlowFromStateFlow(
                                    flow = ffmpegManager.status,
                                    updater = {},
                                    scope = scope,
                                ),
                                describe = { it.asStringSource() },
                                enabled = MutableStateFlow(false),
                            ),
                            NavigatableConfigurable(
                                title = "Download or update ffmpeg (LGPL)".asStringSource(),
                                description = ("Downloads the LGPL Windows build from github.com/BtbN/FFmpeg-Builds, " +
                                        "checks it against the release's SHA-256 checksums and installs it with its " +
                                        "license into WebDM's tools folder.").asStringSource(),
                                onRequestNavigate = { ffmpegManager.downloadOrUpdate() },
                            ),
                            NavigatableConfigurable(
                                title = "Show ffmpeg license".asStringSource(),
                                description = "Opens the license that came with the installed ffmpeg build (LGPL).".asStringSource(),
                                onRequestNavigate = {
                                    runCatching { java.awt.Desktop.getDesktop().open(ffmpegManager.licenseFile) }
                                },
                            ),
                            BooleanConfigurable(
                                title = "Convert .ts downloads to .mp4".asStringSource(),
                                description = ("Save MPEG-TS video streams as .mp4 (stream copy, no re-encoding). " +
                                        "Needs ffmpeg.").asStringSource(),
                                backedBy = ffmpegManager.convertTsToMp4Setting,
                                describe = {
                                    if (it) Res.string.enabled.asStringSource() else Res.string.disabled.asStringSource()
                                },
                            ),
                        )
                    )
                )

                DownloadEngine -> listOf(
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.defaultDownloadFolderConfig(appSettings),
                            CommonSettings.useCategoryByDefault(appSettings),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.useSpeedLimitConfig(appRepository),
                            CommonSettings.speedLimitConfig(appRepository),
                            CommonSettings.threadCountConfig(appRepository),
                            CommonSettings.maxConcurrentDownloads(appRepository),
                            CommonSettings.maxDownloadRetryCount(appRepository),
                            CommonSettings.dynamicPartDownloadConfig(appRepository),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.perHostSettings(perHostSettingsPageManager),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.proxyConfig(proxyManager),
                            CommonSettings.dnsConfig(dNSStorage),
                            CommonSettings.userAgent(appSettings),
                            CommonSettings.ignoreSSLCertificates(appSettings),
                            CommonSettings.useServerLastModified(appRepository),
                        )
                    ),
                    ConfigurableGroup(
                        nestedConfigurable = listOf(
                            CommonSettings.trackDeletedFilesOnDisk(appRepository),
                            CommonSettings.appendExtensionToIncompleteDownloads(appRepository),
                            CommonSettings.deletePartialFileOnDownloadCancellation(appSettings),
                            CommonSettings.useSparseFileAllocation(appRepository),
                        )
                    ),
                )
            }
        }
    }

    fun toFront() {
        sendEffect(Effects.BringToFront)
    }

    val settingsPageStateToPersist = MutableStateFlow(pageStorage.settingsPageStorage.value)
    private val _windowSize = settingsPageStateToPersist.mapTwoWayStateFlow(
        map = {
            it.windowSize.let { (x, y) ->
                DpSize(x.dp, y.dp)
            }
        },
        unMap = {
            copy(
                windowSize = it.width.value to it.height.value
            )
        }
    )
    val windowSize = _windowSize.asStateFlow()
    fun setWindowSize(dpSize: DpSize) {
        _windowSize.value = dpSize
    }

    init {
        settingsPageStateToPersist
            .debounce(500.milliseconds)
            .onEach { newValue ->
                pageStorage.settingsPageStorage.update { newValue }
            }.launchIn(scope)
    }

    var pages = listOf(
        Appearance,
//        Network,
        DownloadEngine,
        BrowserIntegration,
        Tools,
    )
    private val _currentPage: MutableStateFlow<SettingSection> = MutableStateFlow(Appearance)
    val currentPage: StateFlow<SettingSection> = _currentPage.asStateFlow()
    fun setCurrentPage(section: SettingSection) {
        _currentPage.value = section
        _configurables.value = allConfigs[section]
    }

    private val _configurables = MutableStateFlow(
        allConfigs[currentPage.value]
    )
    override val configurables = _configurables.asStateFlow()

    sealed interface Effects : BaseSettingsComponent.Effects.Platform {
        data object BringToFront : Effects
    }
}
