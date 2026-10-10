package com.abdownloadmanager.shared.util.downloaderror.definederrors

import com.abdownloadmanager.shared.util.downloaderror.DownloadErrorMapper
import com.abdownloadmanager.shared.util.downloaderror.DownloadErrorMapper.Companion.createErrorReason
import com.abdownloadmanager.shared.util.downloaderror.DownloadErrorReason
import ir.amirab.downloader.downloaditem.hls.DrmProtectedStreamException
import ir.amirab.downloader.downloaditem.hls.FfmpegMissingException
import ir.amirab.downloader.downloaditem.hls.UnsupportedStreamException

/** Video stream errors (missing ffmpeg, DRM, unsupported encryption) with a message that says what to do. */
object MediaStreamErrorMapper : DownloadErrorMapper {
    override fun accept(throwable: Throwable): Boolean {
        return throwable is FfmpegMissingException
                || throwable is DrmProtectedStreamException
                || throwable is UnsupportedStreamException
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
