package com.teachmint.sharex.filetransfer

import android.os.Environment
import java.io.File

private const val SHAREX_FOLDER_NAME = "ShareX"

fun getShareXDirectoryPath(): String {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val shareXDir = File(downloadsDir, SHAREX_FOLDER_NAME)
    if (!shareXDir.exists()) {
        shareXDir.mkdirs()
    }
    return shareXDir.absolutePath
}

fun writeFileBytes(path: String, bytes: ByteArray) {
    File(path).writeBytes(bytes)
}

fun getTransferredFiles(): List<TransferredFileInfo> {
    val dirPath = getShareXDirectoryPath()
    val dir = File(dirPath)
    if (!dir.exists() || !dir.isDirectory) return emptyList()

    return dir.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(".") }
        ?.map { file ->
            TransferredFileInfo(
                name = file.name,
                size = file.length(),
                extension = file.extension,
                lastModifiedMs = file.lastModified(),
                absolutePath = file.absolutePath,
            )
        }
        ?.sortedByDescending { it.lastModifiedMs }
        ?: emptyList()
}
