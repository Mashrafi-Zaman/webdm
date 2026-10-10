package com.abdownloadmanager.shared.downloaderinui.hls

import com.abdownloadmanager.shared.downloaderinui.CredentialAndItemMapper
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.hls.HLSDownloadItem

class HlsItemToCredentialMapper : CredentialAndItemMapper<
        HLSDownloadCredentials, HLSDownloadItem> {
    override fun itemToCredentials(item: HLSDownloadItem): HLSDownloadCredentials {
        return HLSDownloadCredentials(
            link = item.link,
            downloadPage = item.downloadPage,
            headers = item.headers,
            username = item.username,
            password = item.password,
            userAgent = item.userAgent,
            // keep what isn't a plain playlist URL (rebuilt / av streams, site pages), or editing would lose it
            selectedVariantUrl = item.selectedVariantUrl,
            embeddedPlaylist = item.embeddedPlaylist,
            embeddedAudioPlaylist = item.embeddedAudioPlaylist,
            displayQuality = item.displayQuality,
            estimatedSize = item.estimatedSize,
            sitePage = item.sitePage,
            siteCookies = item.siteCookies,
        )
    }

    override fun appliedCredentialsToItem(
        item: HLSDownloadItem,
        credentials: HLSDownloadCredentials
    ): HLSDownloadItem {
        return item.copy().withCredentials(credentials)
    }

    override fun itemWithEditedName(
        item: HLSDownloadItem,
        name: String
    ): HLSDownloadItem {
        return item.copy(name = name)
    }

    override fun credentialsWithEditedLink(
        credentials: HLSDownloadCredentials,
        link: String
    ): HLSDownloadCredentials {
        return credentials.copy(link = link)
    }

}
