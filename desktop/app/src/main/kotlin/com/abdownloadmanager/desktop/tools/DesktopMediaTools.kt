package com.abdownloadmanager.desktop.tools

import ir.amirab.downloader.downloaditem.hls.HLSMediaTools
import ir.amirab.downloader.downloaditem.hls.SiteMediaExtractor
import java.io.File

/** The external tools the downloader uses: ffmpeg (merging, remuxing) and yt-dlp (video pages). */
class DesktopMediaTools(
    private val ffmpeg: FfmpegManager,
    private val ytDlp: YtDlpManager,
) : HLSMediaTools {
    override fun ffmpegPath(): File? = ffmpeg.ffmpegPath()
    override fun convertTsToMp4(): Boolean = ffmpeg.convertTsToMp4()
    override fun siteExtractor(): SiteMediaExtractor? = ytDlp.takeIf { it.path() != null }
}
