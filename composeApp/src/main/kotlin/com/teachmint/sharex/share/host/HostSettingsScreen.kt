package com.teachmint.sharex.share.host

import com.teachmint.sharex.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import com.teachmint.sharex.appupdate.AppUpdateBridge
import com.teachmint.sharex.appupdate.AppUpdateStatus
import com.teachmint.sharex.appupdate.AppUpdateUiState
import com.teachmint.sharex.language.LocaleManager
import com.teachmint.sharex.language.findActivity
import com.teachmint.sharex.uiComponents.LanguageSelectionDialog
import com.teachmint.sharex.share.shared.APP_VERSION
import com.teachmint.sharex.share.shared.APP_VERSION_CODE
import com.teachmint.sharex.share.shared.HostConnectionSettings
import com.teachmint.sharex.ui.theme.AppBorder
import com.teachmint.sharex.ui.theme.AppDimens
import com.teachmint.sharex.ui.theme.AppSurface
import com.teachmint.sharex.ui.theme.BackButtonBackground
import com.teachmint.sharex.ui.theme.PrimaryAccent
import com.teachmint.sharex.ui.theme.RenameDialogBorder
import com.teachmint.sharex.ui.theme.RenameDialogDivider
import com.teachmint.sharex.ui.theme.RenameDialogTextFieldBorder
import com.teachmint.sharex.ui.theme.TextPrimary
import com.teachmint.sharex.ui.theme.TextSecondary
import androidx.compose.ui.res.painterResource

@Composable
fun HostConnectionSettingsScreen(
    deviceName: String,
    connectionSettings: HostConnectionSettings,
    onDeviceNameUpdate: (String) -> Unit,
    onConnectionSettingsChange: (HostConnectionSettings) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentLanguage = remember { LocaleManager.getAppLanguage(context) }

    Box(
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            AppSurface,
                            AppBorder,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppDimens.dp24, vertical = AppDimens.dp20),
            verticalArrangement = Arrangement.spacedBy(AppDimens.dp16),
        ) {
            Surface(
                shape = RoundedCornerShape(AppDimens.dp12),
                color = BackButtonBackground,
                border = BorderStroke(AppDimens.dp1, AppSurface),
                modifier = Modifier
                    .padding(start = AppDimens.dp157)
                    .clickable(onClick = onBackClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppDimens.dp14, vertical = AppDimens.dp8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.left_arrow_icon),
                        contentDescription = null,
                        modifier = Modifier.size(AppDimens.dp16),
                    )
                    Spacer(modifier = Modifier.width(AppDimens.dp8))
                    Text(
                        text = stringResource(R.string.back),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = AppDimens.dp158),
                colors = CardDefaults.cardColors(containerColor = BackButtonBackground),
                shape = RoundedCornerShape(AppDimens.dp12),
                border = BorderStroke(AppDimens.dp1, AppSurface),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0XFF141414))
                            .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp14),
                    ) {
                        Text(
                            text = stringResource(R.string.setting),
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp14),
                    ) {
                        HostEditableSettingRow(
                            title = stringResource(R.string.device_name),
                            value = deviceName.ifBlank { stringResource(R.string.unknown_device) },
                            onClick = { showRenameDialog = true },
                        )
                        HorizontalDivider(color = AppSurface)
                        HostConnectionToggleRow(
                            title = stringResource(R.string.multiple_device_cast),
                            subtitle = stringResource(R.string.multiple_device_cast_desc),
                            checked = connectionSettings.isMultipleDeviceCastEnabled,
                            onCheckedChange = { enabled ->
                                onConnectionSettingsChange(
                                    connectionSettings.copy(isMultipleDeviceCastEnabled = enabled),
                                )
                            },
                        )
                        HorizontalDivider(color = AppSurface)
                        HostConnectionToggleRow(
                            title = stringResource(R.string.direct_connection),
                            subtitle = stringResource(R.string.direct_connection_desc),
                            checked = connectionSettings.isDirectConnectionEnabled,
                            onCheckedChange = { enabled ->
                                onConnectionSettingsChange(
                                    connectionSettings.copy(isDirectConnectionEnabled = enabled),
                                )
                            },
                        )
                        HorizontalDivider(color = AppSurface)
                        HostEditableSettingRow(
                            title = stringResource(R.string.language),
                            value = currentLanguage.nativeLang,
                            onClick = { showLanguageDialog = true },
                        )
                        val appUpdateState by AppUpdateBridge.state.collectAsState()
                        if (appUpdateState.hasPendingUpdate) {
                            HorizontalDivider(color = AppSurface)
                            HostAppUpdateRow(
                                state = appUpdateState,
                                onUpdateClick = { AppUpdateBridge.requestInstall() },
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.app_version, APP_VERSION, APP_VERSION_CODE),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppDimens.dp2),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.sharex_app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(AppDimens.dp16),
                    )
                    Spacer(modifier = Modifier.width(AppDimens.dp8))
                    Text(
                        text = stringResource(R.string.sharex_exclusive),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameDeviceDialog(
            currentDeviceName = deviceName.ifBlank { stringResource(R.string.unknown_device) },
            onDismiss = { showRenameDialog = false },
            onUpdate = { updatedName ->
                onDeviceNameUpdate(updatedName)
                showRenameDialog = false
            },
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = currentLanguage,
            onDismiss = { showLanguageDialog = false },
            onSave = { language ->
                if (language != currentLanguage) {
                    LocaleManager.setAppLanguage(context, language)
                    // Recreate so every screen re-resolves its strings in the new locale.
                    context.findActivity()?.recreate()
                }
            },
        )
    }
}

@Composable
private fun HostEditableSettingRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppDimens.dp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = AppDimens.dp12),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = "Rename device",
            tint = TextSecondary,
            modifier = Modifier
                .padding(start = AppDimens.dp8)
                .size(AppDimens.dp16),
        )
    }
}

@Composable
private fun RenameDeviceDialog(
    currentDeviceName: String,
    onDismiss: () -> Unit,
    onUpdate: (String) -> Unit,
) {
    var updatedDeviceName by remember(currentDeviceName) { mutableStateOf(currentDeviceName) }
    val canUpdate = updatedDeviceName.trim().isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val wideRenameDialogPadding = 438.dp
            val dialogHorizontalPadding = if (maxWidth >= AppDimens.dp403 + (wideRenameDialogPadding * 2)) {
                wideRenameDialogPadding
            } else {
                AppDimens.dp24
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dialogHorizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = AppDimens.dp403),
                    shape = RoundedCornerShape(AppDimens.dp12),
                    color = Color(0xFF121212),
                    border = BorderStroke(AppDimens.dp1, RenameDialogBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppDimens.dp24, vertical = AppDimens.dp20),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.rename),
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Close",
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(AppDimens.dp32)
                                    .clickable(onClick = onDismiss),
                            )
                        }

                        Spacer(modifier = Modifier.height(AppDimens.dp20))
                        HorizontalDivider(color = RenameDialogDivider)

                        Spacer(modifier = Modifier.height(AppDimens.dp20))
                        Text(
                            text = buildAnnotatedString {
                                append(stringResource(R.string.device_name) + " ")
                                withStyle(style = SpanStyle(color = Color(0xFFFF5B5B))) {
                                    append("*")
                                }
                            },
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )

                        Spacer(modifier = Modifier.height(AppDimens.dp12))
                        OutlinedTextField(
                            value = updatedDeviceName,
                            onValueChange = { updatedDeviceName = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(AppDimens.dp12),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RenameDialogTextFieldBorder,
                                unfocusedBorderColor = RenameDialogTextFieldBorder,
                                focusedContainerColor = Color(0xFF232427),
                                unfocusedContainerColor = Color(0xFF232427),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = TextPrimary,
                            ),
                        )

                        Spacer(modifier = Modifier.height(AppDimens.dp20))
                        HorizontalDivider(color = RenameDialogDivider)

                        Spacer(modifier = Modifier.height(AppDimens.dp20))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppDimens.dp16),
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(AppDimens.dp50),
                                border = BorderStroke(AppDimens.dp1, PrimaryAccent),
                                shape = RoundedCornerShape(AppDimens.dp12),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryAccent),
                            ) {
                                Text(
                                    text = stringResource(R.string.cancel),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }

                            Button(
                                onClick = { onUpdate(updatedDeviceName.trim()) },
                                enabled = canUpdate,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(AppDimens.dp50),
                                shape = RoundedCornerShape(AppDimens.dp12),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryAccent,
                                    contentColor = TextPrimary,
                                    disabledContainerColor = Color(0xFF2F3034),
                                    disabledContentColor = Color(0xFF7D7D7D),
                                ),
                            ) {
                                Text(
                                    text = stringResource(R.string.update),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostAppUpdateRow(
    state: AppUpdateUiState,
    onUpdateClick: () -> Unit,
) {
    val failed = state.status == AppUpdateStatus.FAILED
    val subtitle = when (state.status) {
        AppUpdateStatus.DOWNLOADING ->
            stringResource(R.string.app_update_downloading, state.downloadPercent)
        AppUpdateStatus.VERIFYING -> stringResource(R.string.app_update_verifying)
        AppUpdateStatus.INSTALLING -> stringResource(R.string.app_update_installing)
        AppUpdateStatus.FAILED -> stringResource(R.string.app_update_failed_desc)
        else -> stringResource(R.string.app_update_desc)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppDimens.dp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_update_available, state.versionName),
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                color = if (failed) Color(0xFFFF5B5B) else TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.width(AppDimens.dp12))
        if (state.canInstall) {
            Button(
                onClick = onUpdateClick,
                shape = RoundedCornerShape(AppDimens.dp12),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryAccent,
                    contentColor = TextPrimary,
                ),
            ) {
                Text(
                    text = stringResource(if (failed) R.string.retry else R.string.update),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            // Downloading / Verifying / Installing: show a loader so the user
            // waits instead of tapping Update repeatedly.
            CircularProgressIndicator(
                modifier = Modifier.size(AppDimens.dp24),
                strokeWidth = AppDimens.dp2,
                color = PrimaryAccent,
            )
        }
    }
}

@Composable
private fun HostConnectionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppDimens.dp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = PrimaryAccent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = AppBorder,
            ),
        )
    }
}
