package ir.amirab.downloader.downloaditem.hls

import ir.amirab.downloader.downloaditem.DownloadJobExtraConfig

data class HLSDownloadJobExtraConfig(
    // the stream as checked by the add-download dialog (saves fetching the playlists again)
    val stream: ResolvedHLSStream? = null
) : DownloadJobExtraConfig
