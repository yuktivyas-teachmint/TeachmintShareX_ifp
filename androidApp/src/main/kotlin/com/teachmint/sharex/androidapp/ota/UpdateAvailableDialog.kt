package com.teachmint.sharex.androidapp.ota

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teachmint.ota.Ota
import com.teachmint.ota.model.UpdateState
import com.teachmint.sharex.remoteconfig.RemoteConfigManager
import kotlinx.coroutines.launch

/**
 * Top-level overlay mounted above the shared App() content in MainActivity.
 * Observes [Ota.state] and shows the update prompt when an update is available,
 * installing, or errored. Ported from chakra's UpdateAvailableDialog.
 */
@Composable
fun OtaUpdateOverlay(maxWidth: Dp = 360.dp) {
    if (!RemoteConfigManager.config.enableOtaUpdates) return

    val otaState by Ota.state.collectAsStateWithLifecycle()
    var updateDismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val hasUpdate = otaState is UpdateState.Available ||
        otaState is UpdateState.Installing ||
        otaState is UpdateState.Error

    if (!updateDismissed && hasUpdate) {
        UpdateAvailableDialog(
            onInstall = { scope.launch { Ota.install() } },
            onDismiss = { updateDismissed = true },
            maxWidth = maxWidth,
        )
    }
}

/**
 * Uses a Box with scrim instead of a Compose Dialog so it can render in any
 * window context without an Activity token, and so a force update can present
 * a non-dismissable scrim.
 */
@Composable
fun UpdateAvailableDialog(
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    maxWidth: Dp = 360.dp,
) {
    val otaState by Ota.state.collectAsStateWithLifecycle()

    when (val state = otaState) {
        is UpdateState.Available -> {
            val isForce = state.updateInfo.isForceUpdate
            UpdateScrim(dismissable = !isForce, onDismiss = onDismiss, maxWidth = maxWidth) {
                UpdateDialogCard(
                    title = if (isForce) "Update Required" else "Update Available",
                    description = "A new version (v${state.updateInfo.versionName}) is ready to install.",
                    primaryButtonText = "Update Now",
                    onPrimaryClick = onInstall,
                    cancelButtonText = if (isForce) null else "Later",
                    onCancelClick = onDismiss,
                )
            }
        }

        is UpdateState.Installing -> {
            UpdateScrim(dismissable = false, onDismiss = {}, maxWidth = maxWidth) {
                UpdateDialogCard(
                    title = "Installing Update",
                    description = "Please wait while the update is being installed…",
                    showProgress = true,
                )
            }
        }

        is UpdateState.Error -> {
            val dismissError = {
                Ota.resetState()
                onDismiss()
            }
            UpdateScrim(dismissable = true, onDismiss = dismissError, maxWidth = maxWidth) {
                UpdateDialogCard(
                    title = "Update Failed",
                    description = state.message,
                    primaryButtonText = if (state.retryable) "Retry" else "OK",
                    onPrimaryClick = if (state.retryable) onInstall else dismissError,
                    cancelButtonText = "Cancel",
                    onCancelClick = dismissError,
                )
            }
        }

        else -> {
            // Idle, Checking, Downloading, UpToDate, InstalledSuccess — no dialog
        }
    }
}

@Composable
private fun UpdateScrim(
    dismissable: Boolean,
    onDismiss: () -> Unit,
    maxWidth: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { if (dismissable) onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .padding(horizontal = 12.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { /* swallow clicks inside the card */ },
        ) {
            content()
        }
    }
}

@Composable
private fun UpdateDialogCard(
    title: String,
    description: String,
    primaryButtonText: String? = null,
    onPrimaryClick: () -> Unit = {},
    cancelButtonText: String? = null,
    onCancelClick: () -> Unit = {},
    showProgress: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (showProgress) {
                Spacer(modifier = Modifier.height(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }

            if (primaryButtonText != null || cancelButtonText != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cancelButtonText != null) {
                        TextButton(onClick = onCancelClick) {
                            Text(cancelButtonText)
                        }
                    }
                    if (primaryButtonText != null) {
                        Button(onClick = onPrimaryClick) {
                            Text(primaryButtonText)
                        }
                    }
                }
            }
        }
    }
}
