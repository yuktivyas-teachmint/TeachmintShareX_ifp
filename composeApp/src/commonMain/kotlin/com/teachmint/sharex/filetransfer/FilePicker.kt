package com.teachmint.sharex.filetransfer

import androidx.compose.runtime.Composable

@Composable
expect fun rememberFilePickerLauncher(
    onFilePicked: (PickedFileInfo) -> Unit,
    onError: (String) -> Unit = {},
): () -> Unit
