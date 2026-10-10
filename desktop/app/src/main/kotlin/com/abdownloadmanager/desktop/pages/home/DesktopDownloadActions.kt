package com.abdownloadmanager.desktop.pages.home

import com.abdownloadmanager.shared.pagemanager.EditDownloadDialogManager
import com.abdownloadmanager.shared.pagemanager.FileChecksumDialogManager
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.pagemanager.DownloadDialogManager
import com.abdownloadmanager.shared.pages.home.AbstractDownloadActions
import com.abdownloadmanager.shared.util.DownloadSystem
import com.abdownloadmanager.shared.util.category.CategoryManager
import com.abdownloadmanager.shared.util.ui.icon.MyIcons
import ir.amirab.downloader.monitor.IDownloadItemState
import ir.amirab.downloader.queue.QueueManager
import ir.amirab.util.compose.action.MenuItem
import ir.amirab.util.compose.action.buildMenu
import ir.amirab.util.compose.action.simpleAction
import ir.amirab.util.compose.asStringSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DesktopDownloadActions(
    scope: CoroutineScope,
    downloadSystem: DownloadSystem,
    downloadDialogManager: DownloadDialogManager,
    editDownloadDialogManager: EditDownloadDialogManager,
    fileChecksumDialogManager: FileChecksumDialogManager,
    selections: StateFlow<List<IDownloadItemState>>,
    queueManager: QueueManager,
    categoryManager: CategoryManager,
    openFile: (Long) -> Unit,
    requestDelete: (List<Long>) -> Unit,
    mainItem: StateFlow<Long?>,
    private val openFolder: (Long) -> Unit,
) : AbstractDownloadActions(
    scope = scope,
    downloadSystem = downloadSystem,
    downloadDialogManager = downloadDialogManager,
    editDownloadDialogManager = editDownloadDialogManager,
    fileChecksumDialogManager = fileChecksumDialogManager,
    selections = selections,
    queueManager = queueManager,
    categoryManager = categoryManager,
    openFile = openFile,
    requestDelete = requestDelete,
    mainItem = mainItem,
) {
    val openFolderAction = simpleAction(
        title = Res.string.open_folder.asStringSource(),
        icon = MyIcons.folderOpen,
        onActionPerformed = {
            scope.launch {
                val d = defaultItem.value ?: return@launch
                openFolder(d.id)
            }
        }
    )

    // a live recording interrupted by closing the app: "Resume" continues it, this writes what was recorded
    private val hasInterruptedLiveRecording = MutableStateFlow(false)

    init {
        scope.launch {
            selections.collect { selected ->
                hasInterruptedLiveRecording.value = selected.any { downloadSystem.isInterruptedLiveRecording(it.id) }
            }
        }
    }

    val finishLiveRecordingAction = simpleAction(
        title = "Finish live recording (save what was recorded)".asStringSource(),
        icon = MyIcons.check,
        checkEnable = hasInterruptedLiveRecording,
        onActionPerformed = {
            scope.launch {
                selections.value.forEach {
                    downloadSystem.finishInterruptedLiveRecording(it.id)
                }
                hasInterruptedLiveRecording.value = false
            }
        }
    )

    val menu: List<MenuItem> = buildMenu {
        +openFileAction
        +openFolderAction
        +(resumeAction)
        +pauseAction
        +finishLiveRecordingAction
        separator()
        +(deleteAction)
        +(reDownloadAction)
        separator()
        +moveToQueueItems
        +moveToCategoryAction
        separator()
        subMenu(Res.string.copy.asStringSource(), MyIcons.copy) {
            +(copyDownloadLinkAction)
            +(copyDownloadCredentialsAsJsonAction)
            +(copyDownloadCredentialsAsCurlAction)
        }
        +editDownloadAction
        +fileChecksumAction
        +(openDownloadDialogAction)
    }
}
