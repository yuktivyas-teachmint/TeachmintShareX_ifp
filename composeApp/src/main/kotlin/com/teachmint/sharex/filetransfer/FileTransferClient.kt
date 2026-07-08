package com.teachmint.sharex.filetransfer

import com.teachmint.sharex.share.shared.FILE_UPLOAD_ENDPOINT_PATH
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess

class FileTransferClient {

    /**
     * Dedicated lightweight HTTP client for file uploads.
     * Intentionally NOT using [createHttpClient] to avoid sharing OkHttp connection
     * pools / dispatcher with the WebSocket signaling client. Closing this client
     * after upload must not tear down the signaling connection.
     */
    private val client = HttpClient()

    suspend fun uploadFile(
        hostAddress: String,
        hostPort: Int,
        fileInfo: PickedFileInfo,
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> {
        return try {
            val url = "http://$hostAddress:$hostPort$FILE_UPLOAD_ENDPOINT_PATH"
            val encodedName = fileInfo.name.encodeURLParameter()

            onProgress(0.1f) // started

            val response = client.post(url) {
                header("X-File-Name", encodedName)
                contentType(ContentType.Application.OctetStream)
                setBody(fileInfo.bytes)
            }

            onProgress(1.0f) // done

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val body = response.bodyAsText()
                Result.failure(Exception("Upload failed (${response.status}): $body"))
            }
        } catch (e: Exception) {
            println("FILE_TRANSFER_CLIENT: Upload error: ${e.message}")
            Result.failure(e)
        }
    }
}
