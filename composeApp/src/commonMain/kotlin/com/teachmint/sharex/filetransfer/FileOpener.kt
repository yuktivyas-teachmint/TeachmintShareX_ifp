package com.teachmint.sharex.filetransfer

/**
 * Opens a file with an external application that supports the given MIME type.
 * Returns false if no suitable app is found on the device.
 */
expect fun openFileWithExternalApp(filePath: String, mimeType: String): Boolean
