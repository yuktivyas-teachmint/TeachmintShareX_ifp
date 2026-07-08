package com.teachmint.sharex.filetransfer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberFilePickerLauncher(
    onFilePicked: (PickedFileInfo) -> Unit,
    onError: (String) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        try {
            val contentResolver = context.contentResolver
            val cursor = contentResolver.query(uri, null, null, null, null)
            var fileName = "unknown_file"
            var fileSize = 0L

            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = it.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0) fileSize = it.getLong(sizeIndex)
                }
            }

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                val sizeMb = fileSize / (1024 * 1024)
                onError("File is too large (${sizeMb} MB). Maximum allowed size is ${MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB.")
                return@rememberLauncherForActivityResult
            }

            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                onError("Unable to read file \"$fileName\". The file may be inaccessible or corrupted.")
                return@rememberLauncherForActivityResult
            }

            if (fileSize == 0L) fileSize = bytes.size.toLong()

            onFilePicked(
                PickedFileInfo(
                    name = fileName,
                    size = fileSize,
                    mimeType = mimeType,
                    bytes = bytes,
                )
            )
        } catch (e: OutOfMemoryError) {
            println("FILE_PICKER: OutOfMemoryError reading file: ${e.message}")
            onError("File is too large to transfer. Please choose a smaller file.")
        } catch (e: Exception) {
            println("FILE_PICKER: Error reading file: ${e.message}")
            e.printStackTrace()
            onError("Failed to read file: ${e.message ?: "Unknown error"}")
        }
    }

    return remember(launcher) {
        { launcher.launch(arrayOf("*/*")) }
    }
}
