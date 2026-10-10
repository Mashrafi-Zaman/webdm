package com.abdownloadmanager.shared.util.downloaderror.definederrors

import com.abdownloadmanager.shared.util.downloaderror.DownloadErrorMapper
import com.abdownloadmanager.shared.util.downloaderror.DownloadErrorMapper.Companion.createErrorReason
import com.abdownloadmanager.shared.util.downloaderror.DownloadErrorReason
import ir.amirab.downloader.downloaditem.hls.DrmProtectedStreamException
import ir.amirab.downloader.downloaditem.hls.FfmpegMissingException
import ir.amirab.downloader.downloaditem.hls.SiteExtractionException
import ir.amirab.downloader.downloaditem.hls.SiteExtractorOutdatedException
import ir.amirab.downloader.downloaditem.hls.SiteLoginRequiredException
import ir.amirab.downloader.downloaditem.hls.UnsupportedStreamException

/** Video stream errors (missing ffmpeg, DRM, unsupported encryption) with a message that says what to do. */
object MediaStreamErrorMapper : DownloadErrorMapper {
    override fun accept(throwable: Throwable): Boolean {
        return throwable is FfmpegMissingException
                || throwable is DrmProtectedStreamException
                || throwable is UnsupportedStreamException
                || throwable is SiteExtractionException
    }

    override fun getReason(throwable: Throwable): DownloadErrorReason {
        return when (throwable) {
            is FfmpegMissingException -> createErrorReason(
                title = "ffmpeg is needed",
                description = throwable.message.orEmpty(),
                suggestion = "Open Settings > Tools and click \"Download or update ffmpeg (LGPL)\", then press " +
                        "Resume. What was already downloaded is kept.",
                throwable = throwable,
            )

            is SiteLoginRequiredException -> createErrorReason(
                title = "Login required",
                description = throwable.message.orEmpty(),
                suggestion = "Settings > Tools > \"Use browser cookies for video sites\", be logged in to the site in " +
                        "your browser, then add the video again from the browser.",
                throwable = throwable,
            )

            is SiteExtractorOutdatedException -> createErrorReason(
                title = "yt-dlp couldn't read this page",
                description = throwable.message.orEmpty(),
                suggestion = "WebDM updates yt-dlp automatically when a newer one exists; press Resume in a minute. " +
                        "You can also update it in Settings > Tools.",
                throwable = throwable,
            )

            is SiteExtractionException -> createErrorReason(
                title = "Can't read this video page",
                description = throwable.message.orEmpty(),
                suggestion = "Check that the video plays in your browser. Settings > Tools can update yt-dlp.",
                throwable = throwable,
            )

            is DrmProtectedStreamException -> createErrorReason(
                title = "DRM-protected video",
                description = "This video is protected with DRM, so it can't be downloaded.",
                suggestion = "WebDM doesn't remove DRM protection.",
                throwable = throwable,
            )

            else -> createErrorReason(
                title = "Unsupported video",
                description = throwable.message.orEmpty(),
                suggestion = "Check Settings > Tools: some streams need ffmpeg.",
                throwable = throwable,
            )
        }
    }
}
