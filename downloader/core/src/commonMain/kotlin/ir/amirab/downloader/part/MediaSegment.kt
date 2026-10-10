package ir.amirab.downloader.part

import ir.amirab.downloader.utils.calcPercent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One piece of an HLS stream (or of a stream rebuilt from segments): a media segment or an init segment
 * (EXT-X-MAP), of the video track or of a separate audio track.
 *
 * [segmentIndex] is the unique, sortable id of the part: sorting by it gives the order in which the parts are
 * written (all video parts, init before its segments, then all audio parts). See [MediaSegment.idFor].
 */
@Serializable
data class MediaSegment(
    val segmentIndex: Long,
    val link: String,
    var duration: Double,
    override var isCompleted: Boolean = false,
    var length: Long? = null,
    @Transient
    override var current: Long = 0,
    // media sequence number (EXT-X-MEDIA-SEQUENCE + index); -1 = old data where segmentIndex was the sequence
    val sequence: Long = -1,
    // 0 = video (or muxed), 1 = separate audio track
    val track: Int = 0,
    // EXT-X-MAP init segment
    val isInit: Boolean = false,
    // EXT-X-BYTERANGE / EXT-X-MAP BYTERANGE (absolute offset and length)
    val rangeStart: Long? = null,
    val rangeLength: Long? = null,
    // EXT-X-KEY: only AES-128 is decrypted here; the key is fetched from keyUri
    val keyMethod: String? = null,
    val keyUri: String? = null,
    // IV as hex ("0x..."); null = use the media sequence number
    val keyIv: String? = null,
    val discontinuity: Boolean = false,
) : DownloadPart {

    override fun howMuchProceed() = current

    override fun resetCurrent() {
        current = 0
    }

    @Transient
    override val statusFlow = MutableStateFlow<PartDownloadStatus>(PartDownloadStatus.IDLE)

    override val percent: Int?
        get() = length?.let {
            calcPercent(howMuchProceed(), it)
        }

    override fun getID(): Long {
        return segmentIndex.toLong()
    }

    /** media sequence number, also for parts saved by older versions */
    val mediaSequence: Long get() = if (sequence >= 0) sequence else segmentIndex

    companion object {
        private const val TRACK_OFFSET = 1_000_000_000_000L

        /** unique id; init segments sort right before the first media segment that uses them */
        fun idFor(track: Int, sequence: Long, isInit: Boolean): Long {
            return track * TRACK_OFFSET + sequence * 2 + (if (isInit) 0 else 1)
        }
    }
}
