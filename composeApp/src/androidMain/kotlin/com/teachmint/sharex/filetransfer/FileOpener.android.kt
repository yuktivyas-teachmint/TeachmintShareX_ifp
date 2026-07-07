package com.teachmint.sharex.filetransfer

import android.content.Intent
import androidx.core.content.FileProvider
import com.teachmint.sharex.share.shared.AndroidContextHolder
import java.io.File

actual fun openFileWithExternalApp(filePath: String, mimeType: String): Boolean {
    val context = AndroidContextHolder.applicationContext ?: return false
    val file = File(filePath)
    if (!file.exists()) return false

    return try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Open with").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    } catch (e: Exception) {
        println("FILE_OPENER: Error opening file: ${e.message}")
        false
    }
}
