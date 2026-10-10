package ir.amirab.downloader.downloaditem.hls

import ir.amirab.downloader.DownloadManager
import ir.amirab.downloader.connection.HttpDownloaderClient
import ir.amirab.downloader.destination.DownloadDestination
import ir.amirab.downloader.downloaditem.DownloadJob
import ir.amirab.downloader.downloaditem.DownloadJobExtraConfig
import ir.amirab.downloader.downloaditem.DownloadJobStatus
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.downloader.downloaditem.IDownloadItem
import ir.amirab.downloader.exception.DownloadValidationException
import ir.amirab.downloader.exception.TooManyErrorException
import ir.amirab.downloader.part.*
import kotlinx.coroutines.currentCoroutineContext
import ir.amirab.downloader.utils.*
import ir.amirab.downloader.utils.speedlimiter.SpeedLimiter
import ir.amirab.util.tryLocked
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds


/**
 * alive object that responsible for download a file
 */

class HLSDownloadJob(
    override val downloadItem: HLSDownloadItem,
    downloadManager: DownloadManager,
    val client: HttpDownloaderClient,
    private val mediaTools: HLSMediaTools = HLSMediaTools.None,
) : DownloadJob(
    downloadManager = downloadManager,
) {
    val listDb by downloadManager::dlListDb
    val partListDb by downloadManager::partListDb
    private val parts: MutableList<MediaSegment> = mutableListOf()

    private lateinit var destination: HLSDownloadDestination

    // AES-128 keys, fetched with the item's headers/cookies
    private val keyProvider = HLSKeyProvider(client) { downloadItem }

    // live stream (no EXT-X-ENDLIST): keep polling the playlist and record until the user stops
    @Volatile
    var isLiveRecording = false
        private set

    // the last parts can complete at the same moment: finish (assemble the file) only once per session
    private val finishRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun finishOnce() {
        if (finishRequested.compareAndSet(false, true)) {
            onDownloadFinished()
        }
    }

    // number of media segments (without init segments), for the progress window
    fun getSegmentProgress(): Pair<Int, Int> {
        val media = getParts().filter { !it.isInit }
        return media.count { it.isCompleted } to media.size
    }

    override fun getDestination(): DownloadDestination {
        return destination
    }

    var serverLastModified: Long? = null
        private set

    override suspend fun actualBoot() {
        initializeDestination()
        loadPartState()
        applySpeedLimit()
        downloadedSizeBeforeRetry = getDownloadedSize()
    }

    override fun initializeDestination() {
        val outFile = downloadManager.calculateOutputFile(downloadItem)
        destination = HLSDownloadDestination(
            outputFile = outFile,
            getAllParts = {
                getParts()
            },
            tempDirectory = downloadManager.downloadDataFolder.resolve(id.toString()),
            mediaTools = mediaTools,
        )
    }

    private fun setParts(list: List<MediaSegment>) {
        this.parts.clear()
        list.forEach {
            if (it.isCompleted) {
                it.statusFlow.update { PartDownloadStatus.Completed }
            }
        }
        this.parts.addAll(list)
    }

    val itemSaveLock = Mutex()
    val partLock = Mutex()
    private suspend fun loadPartState() {
        val mediaSegments = partLock.withLock {
            partListDb.getParts(id)
        } as? MediaSegments
        setParts(mediaSegments?.list.orEmpty())
    }


    override suspend fun reset() {
        pause()
        clearPartDownloaderList()
        setParts(emptyList())
        downloadItem.contentLength = IDownloadItem.LENGTH_UNKNOWN
        downloadItem.status = DownloadStatus.Added
        downloadItem.startTime = null
        downloadItem.completeTime = null
        downloadItem.duration = null
        isLiveRecording = false
        downloadItem.liveRecording = false
        downloadedSizeBeforeRetry = 0 // nothing
        saveState()
        downloadManager.onDownloadItemChange(downloadItem)
    }


    override suspend fun resume() {
        if (isDownloadActive.value) {
            return
        }
        _isDownloadActive.update { true }
        resumeWithNewScope(
            newActiveScope = createAndInitializeDownloadScope(),
            isInFirstResume = true
        )
    }

    fun createAndInitializeDownloadScope(): CoroutineScope {
        val newActiveScope = newScopeBasedOn(scope)
            .also {
                activeDownloadScope = it
            }
        return newActiveScope
    }

    private suspend fun resumeWithNewScope(
        newActiveScope: CoroutineScope,
        isInFirstResume: Boolean,
    ) {

//        println(parts.filter { !it.isCompleted })
        return newActiveScope.launch {
            //boot download item from storage!
            boot()
            finishRequested.set(false)
            // if download item is booted and parts is not empty it means that we resumed that file in some point
            // but we should check if all parts are already downloaded to finish the job before hitting the server unnecessarily!
            if (parts.isNotEmpty() && parts.all { it.isCompleted } && !isLiveRecording) {
                finishOnce()
                return@launch
            }
            onDownloadResuming()
            try {
                val stream = fetchDownloadInfoAndValidate()
                if (stream?.encryption == HLSEncryption.SampleAes) {
                    // SAMPLE-AES: WebDM doesn't decrypt it itself; ffmpeg does (stream copy)
                    downloadWithFfmpeg(stream)
                    return@launch
                }
                createPartDownloaderList()
//                println("part downloaders created")
                beginDownloadParts()
                startAutoSaver()
                downloadItem.status = DownloadStatus.Downloading
                if (downloadItem.startTime == null) {
                    downloadItem.startTime = System.currentTimeMillis()
                }
                saveState()
                onDownloadResumed()
                if (isLiveRecording) {
                    startLivePlaylistPolling(stream?.video?.playlist?.targetDuration() ?: 6)
                }
            } catch (e: Exception) {
                e.printStackIfNOtUsual()
                val shouldStop = when {
                    ExceptionUtils.isNormalCancellation(e) -> true
                    e is DownloadValidationException -> e.isCritical()
                    else -> false
                }
                if (shouldStop) {
                    // this function called from activeDownloadScope
                    // so we change the scope here to prevent cancel this suspend function
                    scope.launch {
                        pause(e)
                    }
                } else {
                    downloadFailedRetryOrPause(
                        e = e,
                        isInFirstResume = isInFirstResume,
                    )
                }
            }
        }.join()
    }


    override fun getDownloadedSize(): Long {
        return getParts().sumOf {
            it.howMuchProceed()
        }
    }


    fun onPreferredConnectionCountChanged() {
        activeDownloadScope?.launch {
            beginDownloadParts()
        }
    }

    override suspend fun changeConfig(
        updater: (IDownloadItem) -> Unit,
        extraConfig: DownloadJobExtraConfig?
    ): IDownloadItem {
        boot()
        val previousItem = downloadItem.copy()
        val newItem = previousItem.copy().apply(updater)
        val previousDestination = downloadManager.calculateOutputFile(previousItem)
        val newDestination = downloadManager.calculateOutputFile(newItem)
        val shouldUpdateDestination = previousDestination != newDestination
        if (shouldUpdateDestination) {
            if (isDownloadActive.value) {
                pause()
            }
            destination.moveOutput(newDestination)
        }
        // if there is no error update the actual download item
        downloadItem.applyFrom(newItem)
        if (shouldUpdateDestination) {
            // destination should be closed for now!
            initializeDestination()
        }
        if (previousItem.preferredConnectionCount != downloadItem.preferredConnectionCount) {
            onPreferredConnectionCountChanged()
        }
        if (previousItem.link != downloadItem.link) {
            onLinkChanged()
        }
        applySpeedLimit()
        extraConfig?.let {
            extraConfigsReceived(extraConfig)
        }
        saveDownloadItem()
        return downloadItem
    }

    private fun applySpeedLimit() {
        speedLimiter.bytesPerSecond(bytesPerSecond = downloadItem.speedLimit)
    }

    fun onLinkChanged() {
        scope.launch {
            if (activeDownloadScope?.isActive == true) {
                pause()
                resume()
            }

        }
    }

    fun getRequestedThreadCount(): Int {
        return downloadItem.preferredConnectionCount
            ?: downloadManager.settings.defaultThreadCount
    }


    private val partLoopLock = Mutex()

    //    private val c = AtomicInteger(0)
    private fun beginDownloadParts() {
        if (partLoopLock.isLocked) {
            return
        }
        activeDownloadScope?.launch {
            if (!partLoopLock.tryLock()) {
                return@launch
            }
//            c.incrementAndGet()
            try {
                val activeCount = getPartDownloaderList()
                    .count { it.active }
                val howMuchCreate = getRequestedThreadCount() - activeCount
                if (howMuchCreate > 0) {
                    val mutableInactivePartDownloaderList = getPartDownloaderList()
                        .filter { !it.active && !it.part.isCompleted }
                        .sortedBy { it.part.getID() }
                        .toMutableList()
//                    println(mutableInactivePartDownloaderList)

                    fun getPartDownloader(): HLSPartDownloader? {
                        val inactivePart =
                            runCatching { mutableInactivePartDownloaderList.removeAt(0) }.getOrNull()
                        if (inactivePart != null) return inactivePart
                        return null
                    }
                    for (i in 1..howMuchCreate) {
                        val partDownloader = getPartDownloader()
                        if (partDownloader == null) {
//                            println("part downloader is null")
                            break
                        }
                        if (partDownloader.part.isCompleted) {
//                            println("it seems part is downloaded!")
                            continue
                        }
//                        println("got new part downloader ${partDownloader.part}")
                        partDownloader.start()
                    }
                }
                if (howMuchCreate < 0) {
                    // as we restart the parts each time we don't pause the active ones
//                    partDownloaderList.values
//                        .toList()
//                        .filter { it.active }
//                        .sortedByDescending { it.part.getID() }
//                        .take(-howMuchCreate)
//                        .onEach {
//                            it.stop()
//                        }.onEach {
//                            it.join()
//                            it.awaitIdle()
//                        }
                }
            } catch (e: Exception) {
                throw e
            } finally {
//                println("C:" + c)
                partLoopLock.unlock()
//                c.decrementAndGet()
            }
        }
    }

    private fun onPartHaveToManyError(throwable: Throwable) {
        var paused = false
        if (throwable is DownloadValidationException) {
            if (throwable.isCritical()) {
                //stop the whole job! as we have big problem here
                paused = true
                scope.launch {
                    pause(throwable)
                }
            }
        }
        val allHaveError = partDownloaderList.values
            .filter { it.active }
            .all {
                it.injured()
            }
        if (allHaveError && !paused) {
//            println("all have error!")
            downloadFailedRetryOrPause(
                e = throwable,
                isInFirstResume = false,
            )
        }
    }

    // for this download job only, it has higher priority than download manager settings
    var _maxAllowedRetries: Int? = null
    fun getMaxAllowedRetries(): Int {
        return _maxAllowedRetries ?: downloadManager.settings.maxDownloadRetryCount
    }

    var failedDownloadTries = 0
    val delayForEachRetry = 3_000L
    private var downloadedSizeBeforeRetry = 0L

    private var retryJob: Job? = null

    private val retryLock = Mutex()

    // I have to improve this function to not allow accessing it concurrently
    private fun downloadFailedRetryOrPause(
        e: Throwable,
        isInFirstResume: Boolean,
    ) {
        //moving to the main scope and request to cancel activeDownload scope!
        scope.launch {
            if (isInFirstResume && failedDownloadTries == 0 && shouldRetryIfInitialFailed()) {
                if (ExceptionUtils.isNetworkError(e) || ExceptionUtils.isResponseError(e)) {
                    pause(e)
                    return@launch
                }
            }
            // can't proceed
            if (e is DownloadValidationException && e.isCritical()) {
                pause(e)
                return@launch
            }
            val downloadedSize = getDownloadedSize()
            if (downloadedSize > downloadedSizeBeforeRetry) {
                // download had progress! so we reset it
                failedDownloadTries = 0
            } else {
                failedDownloadTries++
            }
            downloadedSizeBeforeRetry = downloadedSize

            // we always have one try (the initial resume action), after that others are retries!
            val retriedCount = (failedDownloadTries - 1).coerceAtLeast(0)
            if (retriedCount < getMaxAllowedRetries()) {
                retry(isInFirstResume)
            } else {
                pause(TooManyErrorException(e))
            }
        }
    }

    fun retry(isInFirstResume: Boolean) {
        scope.launch {
            val newScopeResult = retryLock.tryLocked {
                val job = async {
                    saveState()
                    cancelDownloadScope()
                    stopAllParts()
                    _status.update { DownloadJobStatus.Retrying(delayForEachRetry) }
                    delay(delayForEachRetry.milliseconds)
                    createAndInitializeDownloadScope()
                }
                retryJob = job
                job.await()
            }
            newScopeResult.getOrNull()?.let {
                resumeWithNewScope(it, isInFirstResume)
            }
        }
    }

    fun shouldRetryIfInitialFailed(): Boolean {
        return true
    }


    private fun onPartStatusChanged(
        partDownloader: HLSPartDownloader,
        partStatus: PartDownloadStatus,
    ) {
        when (partStatus) {
            is PartDownloadStatus.Canceled -> {
                destination.onPartCancelled(partDownloader.part)
            }

            PartDownloadStatus.Completed -> {
                destination.onPartCancelled(partDownloader.part)
                if (getParts().all { it.isCompleted } && !isLiveRecording) {
                    finishOnce()
                } else {
                    scope.launch {
                        beginDownloadParts()
                    }
                }
            }

            PartDownloadStatus.ReceivingData -> {}
            PartDownloadStatus.Connecting -> {}
            PartDownloadStatus.IDLE -> {}
        }
    }


    @Synchronized
    private fun createPartDownloaderList() {
        synchronized(partDownloaderList) {
            //        thisLogger().info("create part downloaders")
            parts.forEach {
                getOrCreatePartDownloader(it)
            }
//            println("created N parts = " + partDownloaderList.values.size)
        }
    }

    private fun clearPartDownloaderList() {
//        thisLogger().info("create part downloaders")
        parts.forEach {
            destroyPartDownloader(it)
        }
    }

    private val speedLimiter = SpeedLimiter()

    private val partDownloaderList = ConcurrentHashMap<Long, HLSPartDownloader>()
    private val listenerJobs: MutableMap<Long, Job> = ConcurrentHashMap<Long, Job>()
    private fun getPartDownloaderList(): List<HLSPartDownloader> {
        synchronized(partDownloaderList) {
            return partDownloaderList.map { it.value }
        }
    }

    // headers the resolved stream's URLs need (site pages: the User-Agent etc. yt-dlp used); refreshed on resolve
    @Volatile
    private var streamHeaders: Map<String, String> = emptyMap()

    // the last resolved stream (live recordings of video pages refresh its playlists, not the whole page)
    @Volatile
    private var lastResolvedStream: ResolvedHLSStream? = null

    private fun partCredentials(): IHLSCredentials {
        val extra = streamHeaders
        if (extra.isEmpty()) return downloadItem
        return downloadItem.copy(headers = downloadItem.headers.orEmpty() + extra)
    }

    private fun getOrCreatePartDownloader(part: MediaSegment): HLSPartDownloader {
        synchronized(partDownloaderList) {
            return partDownloaderList.getOrPut(part.getID()) {
                HLSPartDownloader(
                    baseURL = downloadItem.link,
                    part = part,
                    getDestWriter = {
                        destination.getWriterFor(part)
                    },
                    client = client,
                    speedLimiters = listOf(
                        downloadManager.speedLimiter,
                        speedLimiter,
                    ),
                    credentials = { partCredentials() },
                    keyProvider = keyProvider,
                ).also { partDownloader: HLSPartDownloader ->
                    partDownloader.onTooManyErrors = {
                        onPartHaveToManyError(it)
                    }
                    //we should close that scope after we don't need it anymore!
                    listenerJobs[part.getID()] = partDownloader.statusFlow.onEach { status ->
                        //TODO probably bug here
                        onPartStatusChanged(partDownloader, status)
                    }.launchIn(scope)
                }
            }
        }
    }

    private fun destroyPartDownloader(part: MediaSegment) {
        listenerJobs.remove(part.getID())?.cancel()
        partDownloaderList.remove(part.getID())
    }

    // the stream checked by the add-download dialog (saves fetching the playlists twice)
    private var pendingStreamFromDialog: ResolvedHLSStream? = null

    private suspend fun fetchDownloadInfoAndValidate(): ResolvedHLSStream? {
        var stream: ResolvedHLSStream? = null
        if (parts.isEmpty() || isLiveRecording || downloadItem.liveRecording || needsStreamInfo()) {
            // site pages are resolved on every resume (their file URLs expire); the dialog's result is fresh
            stream = pendingStreamFromDialog?.takeIf { parts.isEmpty() || downloadItem.sitePage }
                ?: HLSResponseInfo.resolve(client, downloadItem, mediaTools.siteExtractor()).stream
            pendingStreamFromDialog = null
            validateStream(stream)
            streamHeaders = stream.requestHeaders
            lastResolvedStream = stream
            isLiveRecording = stream.isLive
            updateParts(stream)
            downloadItem.liveRecording = stream.isLive
        } else {
            // the parts came from the add-download dialog (or an earlier run): still check what the stream needs
            // (ffmpeg, no DRM) before downloading anything, not only when the file is put together at the end
            pendingStreamFromDialog?.let { validateStream(it) } ?: validatePartRequirements()
            pendingStreamFromDialog = null
        }
        // at this point we have all the parts we need either by hitting remote or they are already in destination
        // before proceed we should check the downloaded parts and remove the "isCompleted" flag if they are missing
        parts
            .filter { it.isCompleted }
            .forEach {
                // redownload invalid parts
                if (!destination.isDownloadPartValid(it)) {
                    it.isCompleted = false
                }
            }

        saveState()
        return stream
    }

    // a stored download without the info we need to decide how to download it (e.g. older data)
    private fun needsStreamInfo(): Boolean = downloadItem.duration == null || downloadItem.sitePage

    private fun validateStream(stream: ResolvedHLSStream) {
        when (stream.encryption) {
            HLSEncryption.Drm -> throw DrmProtectedStreamException()
            HLSEncryption.SampleAes -> if (mediaTools.ffmpegPath() == null) {
                throw UnsupportedStreamException("SAMPLE-AES encrypted video is not supported without ffmpeg (Settings > Tools)")
            }

            else -> {}
        }
        val outputIsMp4 = destination.outputFile.extension.equals("mp4", ignoreCase = true)
        if (stream.audio != null && mediaTools.ffmpegPath() == null) {
            throw FfmpegMissingException("A video with a separate audio track")
        }
        if (stream.format == HLSSegmentFormat.Ts && stream.audio == null && outputIsMp4 && mediaTools.ffmpegPath() == null) {
            throw FfmpegMissingException("Saving an MPEG-TS stream as .mp4")
        }
    }

    /** What the known parts need when the stream itself isn't fetched again (see [validateStream]). */
    private fun validatePartRequirements() {
        if (getParts().any { it.track == 1 } && mediaTools.ffmpegPath() == null) {
            throw FfmpegMissingException("A video with a separate audio track")
        }
    }

    /**
     * New playlist data: keep the completion state of parts we already have (same id = same track, sequence and
     * kind) and add new ones (live streams). If a segment changed its duration the stream changed: start over.
     */
    fun updateParts(stream: ResolvedHLSStream) {
        val newParts = stream.allParts
        downloadItem.duration = stream.duration
        val currentParts = parts.toList()
        if (currentParts.isEmpty()) {
            setParts(newParts)
            return
        }
        val oldPartsMap = currentParts.associateBy { it.getID() }
        val merged = ArrayList<MediaSegment>(maxOf(newParts.size, currentParts.size))
        for (newPart in newParts) {
            val oldPart = oldPartsMap[newPart.getID()]
            if (oldPart != null && !oldPart.isInit && (oldPart.duration != newPart.duration
                        || oldPart.rangeStart != newPart.rangeStart || oldPart.rangeLength != newPart.rangeLength)) {
                // inconsistencies found! reset the parts to the new one
                setParts(newParts)
                return
            }
            merged.add(
                if (oldPart != null) newPart.copy(isCompleted = oldPart.isCompleted, length = oldPart.length) else newPart
            )
        }
        if (stream.isLive || downloadItem.liveRecording) {
            // a live playlist only lists recent segments: keep the recorded ones that already left it (also when
            // an interrupted recording is continued after the stream ended)
            val newIds = newParts.map { it.getID() }.toHashSet()
            merged.addAll(currentParts.filter { it.getID() !in newIds && it.isCompleted })
        }
        setParts(merged.sortedBy { it.getID() })
    }

    private fun startLivePlaylistPolling(targetDurationSeconds: Int) {
        activeDownloadScope?.launch {
            val interval = (targetDurationSeconds * 1000L).coerceIn(2_000L, 10_000L)
            while (isActive && isLiveRecording) {
                delay(interval)
                val stream = try {
                    val previous = lastResolvedStream
                    if (downloadItem.sitePage && previous != null) refreshLivePlaylists(previous)
                    else HLSResponseInfo.resolve(client, downloadItem, mediaTools.siteExtractor()).stream
                } catch (e: Exception) {
                    if (ExceptionUtils.isNormalCancellation(e)) throw e
                    continue // a missed refresh is fine; try again
                }
                lastResolvedStream = stream
                partLock.withLock { updateParts(stream) }
                createPartDownloaderList()
                beginDownloadParts()
                if (!stream.isLive) {
                    // the stream ended (EXT-X-ENDLIST appeared)
                    isLiveRecording = false
                    if (getParts().all { it.isCompleted }) finishOnce()
                }
                saveState()
            }
        }
    }

    /** A video page's live stream: re-read its playlists (running yt-dlp on the page every few seconds is too slow). */
    private suspend fun refreshLivePlaylists(previous: ResolvedHLSStream): ResolvedHLSStream {
        suspend fun refresh(track: ResolvedTrack, index: Int): ResolvedTrack {
            val text = HLSResponseInfo.fetchPlaylist(client, downloadItem, track.playlistUrl, streamHeaders).second
            val playlist = HLSStreamResolver.parseMedia(text)
            return ResolvedTrack(track.playlistUrl, playlist, HLSStreamResolver.toParts(playlist, track.playlistUrl, index))
        }
        return previous.copy(video = refresh(previous.video, 0), audio = previous.audio?.let { refresh(it, 1) })
    }

    /** "Stop" on a live recording: keep what was recorded and write the file. */
    private suspend fun finishLiveRecording() {
        isLiveRecording = false
        downloadItem.liveRecording = false
        clearPartDownloaderList()
        setParts(getParts().filter { it.isCompleted })
        saveState()
        finishOnce()
    }

    /** Is this a live recording that was interrupted (app closed, error) and can continue or be finished? */
    fun isInterruptedLiveRecording(): Boolean {
        return downloadItem.liveRecording && !isDownloadActive.value && downloadItem.status != DownloadStatus.Completed
    }

    /** Finish an interrupted live recording: write what was recorded instead of continuing it. */
    suspend fun finishInterruptedLiveRecording(): Boolean {
        boot()
        if (!isInterruptedLiveRecording() || getParts().none { it.isCompleted && !it.isInit }) {
            return false
        }
        // init segments are kept: the recorded fMP4 segments need them
        val keep = getParts().filter { it.isCompleted || it.isInit }
        setParts(keep)
        if (keep.any { !it.isCompleted }) {
            return false
        }
        finishRequested.set(false)
        finishLiveRecording()
        return true
    }

    private suspend fun downloadWithFfmpeg(stream: ResolvedHLSStream) {
        val ffmpeg = mediaTools.ffmpegPath()
            ?: throw UnsupportedStreamException("SAMPLE-AES encrypted video is not supported without ffmpeg")
        downloadItem.status = DownloadStatus.Downloading
        if (downloadItem.startTime == null) downloadItem.startTime = System.currentTimeMillis()
        saveState()
        onDownloadResumed()
        val output = destination.outputFile
        output.parentFile?.mkdirs()
        val inputs = buildList {
            addAll(Ffmpeg.headerArgs(downloadItem.headers, downloadItem.userAgent))
            addAll(listOf("-i", stream.video.playlistUrl))
            stream.audio?.let {
                addAll(Ffmpeg.headerArgs(downloadItem.headers, downloadItem.userAgent))
                addAll(listOf("-i", it.playlistUrl))
            }
        }
        val maps = if (stream.audio != null) listOf("-map", "0:v:0?", "-map", "1:a:0") else listOf("-map", "0")
        val total = stream.duration.takeIf { it > 0 }
        val scopeJob = currentCoroutineContext().job
        withContext(Dispatchers.IO) {
            Ffmpeg.run(
                ffmpeg,
                inputs + maps + listOf("-c", "copy", output.absolutePath),
                onProgress = { seconds ->
                    total?.let {
                        _status.value = DownloadJobStatus.PreparingFile((seconds * 100 / it).toInt().coerceIn(0, 99))
                    }
                },
                isCancelled = { !scopeJob.isActive },
            )
        }
        // ffmpeg wrote the whole file: nothing left to download or assemble
        setParts(getParts().map { it.copy(isCompleted = true) })
        finishOnce()
    }

    suspend fun cancelDownloadScope() {
        activeDownloadScope?.coroutineContext?.job?.cancelAndJoin()
        activeDownloadScope = null
    }

    suspend fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    suspend fun stopAllParts() {
        withContext(Dispatchers.IO) {
            partDownloaderList.values.onEach {
                it.stop()
            }.onEach {
                it.join()
                it.awaitIdle()
            }
        }
    }

    override suspend fun pause(throwable: Throwable) {
        boot()
        failedDownloadTries = 0
        cancelRetry()
        cancelDownloadScope()
        stopAllParts()
        if (isLiveRecording && !downloadManager.isStoppingAll
            && ExceptionUtils.isNormalCancellation(throwable) && getParts().any { it.isCompleted }) {
            // user pressed stop on a live recording: write what was recorded (not when the app exits: then it's
            // kept as an interrupted recording, to continue or finish on the next start)
            finishLiveRecording()
            return
        }
        onDownloadCanceled(throwable)
    }

    override fun onDownloadFinishedBeforeSave() {
        downloadItem.liveRecording = false
        downloadItem.contentLength = destination.outputFile.length()
    }

    private var lastSavedDownloadItem: HLSDownloadItem? = null
    private var lastSavedParts: List<MediaSegment>? = null

    private suspend fun saveDownloadItem() {
        itemSaveLock.withLock {
            val copy = downloadItem.copy()
            if (lastSavedDownloadItem != downloadItem) {
                listDb.update(downloadItem)
                lastSavedDownloadItem = copy
            }
        }
    }

    private suspend fun saveParts() {
        partLock.withLock {
            val copy = getParts().map { it.copy() }
            if (lastSavedParts != copy) {
                destination.flush()
                partListDb.setParts(id, MediaSegments(copy))
                lastSavedParts = copy
            }
        }
    }

    override suspend fun saveState() {
        saveDownloadItem()
        saveParts()
    }

    fun getParts(): List<MediaSegment> {
        //Make a copy because of CMException
        return parts.toList()
    }

    override fun reloadSettings() {
        onPreferredConnectionCountChanged()
    }

    override suspend fun extraConfigsReceived(config: DownloadJobExtraConfig) {
        if (config !is HLSDownloadJobExtraConfig) return
        config.stream?.let {
            pendingStreamFromDialog = it
            streamHeaders = it.requestHeaders
            lastResolvedStream = it
            if (parts.isEmpty()) {
                isLiveRecording = it.isLive
                downloadItem.liveRecording = it.isLive
                updateParts(it)
                saveParts()
            }
        }
    }
}

