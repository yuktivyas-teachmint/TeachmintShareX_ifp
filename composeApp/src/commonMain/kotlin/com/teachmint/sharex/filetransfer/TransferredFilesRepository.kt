package com.teachmint.sharex.filetransfer

expect fun getTransferredFiles(): List<TransferredFileInfo>

expect fun getShareXDirectoryPath(): String

expect fun writeFileBytes(path: String, bytes: ByteArray)
