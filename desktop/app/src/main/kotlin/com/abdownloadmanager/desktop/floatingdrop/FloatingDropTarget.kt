package com.abdownloadmanager.desktop.floatingdrop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import com.abdownloadmanager.desktop.AppComponent
import com.abdownloadmanager.desktop.utils.AppInfo
import com.abdownloadmanager.shared.pages.adddownload.AddDownloadCredentialsInUiProps
import com.abdownloadmanager.shared.pages.adddownload.ImportOptions
import com.abdownloadmanager.shared.util.extractors.linkextractor.DefaultDownloadCredentialsExtractor
import com.abdownloadmanager.shared.util.ui.icon.MyIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Window as AwtWindow
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File

/** On/off and position of the floating drop target, kept in <data dir>/config/floating-drop-target.json. */
class FloatingDropTargetSettings(scope: CoroutineScope) {
    @Serializable
    private data class Model(val enabled: Boolean = false, val x: Int? = null, val y: Int? = null)

    private val file = File(AppInfo.dataDir, "config/floating-drop-target.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val initial = runCatching { json.decodeFromString<Model>(file.readText()) }.getOrDefault(Model())

    val enabled = MutableStateFlow(initial.enabled)
    val position = MutableStateFlow(if (initial.x != null && initial.y != null) initial.x to initial.y else null)

    init {
        combine(enabled, position) { e, p -> Model(e, p?.first, p?.second) }
            .drop(1)
            .debounce(400)
            .onEach { model ->
                runCatching {
                    file.parentFile.mkdirs()
                    file.writeText(json.encodeToString(model))
                }
            }
            .launchIn(scope)
    }
}

private const val SIZE = 64

/** Links (or .txt files of links) dropped on WebDM: one link opens Add Download, several the batch dialog. */
fun importDroppedLinks(appComponent: AppComponent, transferable: Transferable): Boolean {
    val text = runCatching {
        when {
            transferable.isDataFlavorSupported(DataFlavor.stringFlavor) ->
                transferable.getTransferData(DataFlavor.stringFlavor) as String

            transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
                droppedFilesText((transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>())

            else -> null
        }
    }.getOrNull() ?: return false
    val links = DefaultDownloadCredentialsExtractor.extract(text).distinctBy { it.link }
    if (links.isEmpty()) return false
    appComponent.externalCredentialComingIntoApp(
        links.map { AddDownloadCredentialsInUiProps(credentials = it) },
        ImportOptions(),
    )
    return true
}

/** the text of dropped files that can hold links (text files up to 1 MB each), joined */
fun droppedFilesText(files: List<File>): String {
    return files
        .filter { it.isFile && it.length() <= 1024 * 1024 }
        .joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
}

/**
 * Take a drop from another app as a COPY. Compose accepts it with the source's proposed action, which is "move" for
 * files from Explorer: the source would then delete the dropped file (e.g. a .txt of links) after the drop.
 */
fun acceptAsCopy(event: DragAndDropEvent) {
    (event.nativeEvent as? java.awt.dnd.DropTargetDropEvent)?.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
}

private fun canImport(event: DragAndDropEvent): Boolean {
    val t = event.awtTransferable
    return t.isDataFlavorSupported(DataFlavor.stringFlavor) || t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
}

/**
 * A small always-on-top, semi-transparent window with the WebDM logo: drop links from the browser on it.
 * Drag it anywhere (the position is remembered); right-click for Add URL / Pause all / Open WebDM / Hide.
 * Not shown in the taskbar.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FloatingDropTarget(
    appComponent: AppComponent,
    settings: FloatingDropTargetSettings,
    onAddUrl: () -> Unit,
    onPauseAll: () -> Unit,
    onOpenWebDM: () -> Unit,
) {
    val enabled by settings.enabled.collectAsState()
    if (!enabled) return
    Window(
        create = {
            ComposeWindow().apply {
                title = "${AppInfo.displayName} drop target"
                isUndecorated = true
                isTransparent = true
                isAlwaysOnTop = true
                isResizable = false
                // a utility window has no taskbar button; not focusable so it never takes the focus from other apps
                type = AwtWindow.Type.UTILITY
                focusableWindowState = false
                setSize(SIZE, SIZE)
                val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                val saved = settings.position.value
                val onScreen = saved != null && GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any {
                    it.defaultConfiguration.bounds.contains(saved.first + SIZE / 2, saved.second + SIZE / 2)
                }
                if (saved != null && onScreen) setLocation(saved.first, saved.second)
                else setLocation(bounds.x + bounds.width - SIZE - 40, bounds.y + bounds.height - SIZE - 80)
                addComponentListener(object : ComponentAdapter() {
                    override fun componentMoved(e: ComponentEvent) {
                        settings.position.value = x to y
                    }
                })
            }
        },
        dispose = { it.dispose() },
    ) {
        var hovered by remember { mutableStateOf(false) }
        var dragOver by remember { mutableStateOf(false) }
        val density = LocalDensity.current.density
        val active = hovered || dragOver
        Box(
            Modifier
                .fillMaxSize()
                .padding(4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF172A46).copy(alpha = if (active) 0.95f else 0.6f))
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.buttons.isSecondaryPressed) {
                        val position = event.changes.first().position
                        showMenu(
                            window,
                            (position.x / density).toInt(),
                            (position.y / density).toInt(),
                            onAddUrl = onAddUrl,
                            onPauseAll = onPauseAll,
                            onOpenWebDM = onOpenWebDM,
                            onHide = { settings.enabled.value = false },
                        )
                    }
                }
                .dragAndDropTarget(
                    shouldStartDragAndDrop = ::canImport,
                    target = remember {
                        object : DragAndDropTarget {
                            override fun onEntered(event: DragAndDropEvent) {
                                dragOver = true
                            }

                            override fun onExited(event: DragAndDropEvent) {
                                dragOver = false
                            }

                            override fun onEnded(event: DragAndDropEvent) {
                                dragOver = false
                            }

                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                dragOver = false
                                acceptAsCopy(event)
                                return importDroppedLinks(appComponent, event.awtTransferable)
                            }
                        }
                    }
                )
        ) {
            WindowDraggableArea(Modifier.fillMaxSize()) {
                Image(
                    painter = MyIcons.appIcon.rememberPainter(),
                    contentDescription = "Drop links here to download them with ${AppInfo.displayName}",
                    modifier = Modifier.fillMaxSize().padding(8.dp).alpha(if (active) 1f else 0.8f),
                )
            }
        }
    }
}

private fun showMenu(
    window: ComposeWindow,
    x: Int,
    y: Int,
    onAddUrl: () -> Unit,
    onPauseAll: () -> Unit,
    onOpenWebDM: () -> Unit,
    onHide: () -> Unit,
) {
    // a native (heavyweight) menu: it can extend beyond this tiny window
    val menu = PopupMenu()
    fun item(label: String, action: () -> Unit) = menu.add(MenuItem(label).apply { addActionListener { action() } })
    item("Add URL") { onAddUrl() }
    item("Pause all") { onPauseAll() }
    item("Open ${AppInfo.displayName}") { onOpenWebDM() }
    menu.addSeparator()
    item("Hide") { onHide() }
    window.add(menu)
    menu.show(window, x, y)
}
