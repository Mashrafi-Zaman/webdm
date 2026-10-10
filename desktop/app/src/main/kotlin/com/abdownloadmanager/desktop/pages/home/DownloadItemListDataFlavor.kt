package com.abdownloadmanager.desktop.pages.home

import ir.amirab.downloader.monitor.CompletedDownloadItemState
import ir.amirab.downloader.monitor.IDownloadItemState
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

val DownloadItemListDataFlavor = DataFlavor(
    IDownloadItemState::class.java,
    "Download Item"
)

class DownloadItemTransferable(
    val items: List<IDownloadItemState>,
) : Transferable {
    // dragged to Explorer / the desktop: only finished downloads whose file exists are copied
    private val finishedFiles: List<File> by lazy {
        items.filterIsInstance<CompletedDownloadItemState>()
            .map { File(it.folder, it.name) }
            .filter { it.isFile }
    }

    private val flavors: Array<DataFlavor>
        get() = if (finishedFiles.isNotEmpty()) arrayOf(DataFlavor.javaFileListFlavor, DownloadItemListDataFlavor)
        else arrayOf(DownloadItemListDataFlavor)

    override fun getTransferDataFlavors(): Array<DataFlavor> {
        return flavors
    }

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean {
        return flavor in flavors
    }

    override fun getTransferData(flavor: DataFlavor?): Any {
        return when (flavor) {
            DataFlavor.javaFileListFlavor -> finishedFiles.takeIf { it.isNotEmpty() }
                ?: throw UnsupportedFlavorException(flavor)

            DownloadItemListDataFlavor -> items
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
