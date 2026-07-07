package com.teachmint.sharex.share.shared

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform-specific QR code generation
 */
expect class QRCodeGenerator() {
    /**
     * Generate QR code as ImageBitmap
     * @param data The data to encode in QR code
     * @param size Size of QR code in pixels
     * @return ImageBitmap of the QR code
     */
    fun generateQRCode(data: String, size: Int = 512): ImageBitmap?
}
