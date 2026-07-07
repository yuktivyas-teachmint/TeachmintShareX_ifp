package com.teachmint.sharex.filetransfer

import kotlin.math.round

/** Maximum file size allowed for transfer (100 MB). */
const val MAX_FILE_SIZE_BYTES: Long = 100L * 1024 * 1024

data class PickedFileInfo(
    val name: String,
    val size: Long,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedFileInfo) return false
        return name == other.name && size == other.size && mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

data class TransferredFileInfo(
    val name: String,
    val size: Long,
    val extension: String,
    val lastModifiedMs: Long,
    val absolutePath: String,
) {
    val formattedSize: String
        get() {
            val kb = size / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> "${formatOneDecimal(gb)} GB"
                mb >= 1.0 -> "${formatOneDecimal(mb)} MB"
                kb >= 1.0 -> "${formatOneDecimal(kb)} KB"
                else -> "$size B"
            }
        }

    val mimeType: String
        get() = when (extension.lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            else -> "application/octet-stream"
        }
}

private fun formatOneDecimal(value: Double): String {
    val rounded = round(value * 10).toLong()
    return "${rounded / 10}.${rounded % 10}"
}
