package com.teachmint.sharex.share.host

import com.teachmint.sharex.ui.theme.*
import com.teachmint.sharex.ui.theme.AppDimens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.teachmint.sharex.filetransfer.openFileWithExternalApp
import com.teachmint.sharex.share.navigation.HostNavigation
import teachmintsharex.composeapp.generated.resources.*
import com.teachmint.sharex.share.shared.*
import com.teachmint.sharex.ui.theme.AppDimens.dp1
import com.teachmint.sharex.uiComponents.BlockedScreenCastingToast
import com.teachmint.sharex.uiComponents.NoNetworkComposable
import kotlin.math.roundToInt

@Composable
fun HostHomeScreen(controller: HostController) {
    HostNavigation(controller)
}

@Composable
fun HostActiveSharingScreen(
    activeShares: List<ActiveShare>,
    connectedClients: List<ClientInfo>,
    clientCastingPolicies: Map<String, ClientCastingPolicy>,
    connectionSettings: HostConnectionSettings,
    deviceName: String,
    networkName: String,
    ipAddress: String,
    isNetworkConnected: Boolean,
    connectedCount: Int,
    pin: String,
    activeByomClientIds: Set<String> = emptySet(),
    onStopSharingForClient: (String) -> Unit = {},
    onClientCastingPolicyChange: (String, ClientCastingPolicy) -> Unit = { _, _ -> },
    onDeviceNameChange: (String) -> Unit = {},
    onConnectionSettingsChange: (HostConnectionSettings) -> Unit = {},
    onToggleClientAudioMuted: (String, Boolean) -> Unit = { _, _ -> },
    onTransferredFilesClick: () -> Unit = {},
    onByomToggle: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var showScreenCastingManagement by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showTransferredFilesScreen by remember { mutableStateOf(false) }
    var fileOpenToastMessage by remember { mutableStateOf<String?>(null) }
    var showTopToolbar by remember { mutableStateOf(false) }
    var topToolbarShowRequestId by remember { mutableStateOf(0) }
    val topToolbarHeight = AppDimens.dp52
    val topToolbarPopupGap = AppDimens.dp5
    val sharingClientIds = activeShares.map { it.clientId }.toSet()
    val canPinAnyShare = sharingClientIds.size > 1
    val pinnedClientId = if (canPinAnyShare) {
        clientCastingPolicies.entries
            .firstOrNull { it.value.isScreenExclusiveEnabled && it.key in sharingClientIds }
            ?.key
    } else {
        null
    }
    val connectionCode = remember(connectionSettings.remoteSignalingUrl, pin, ipAddress, isNetworkConnected) {
        resolveDisplayedConnectionPin(pin = pin)
    }
    LaunchedEffect(connectedCount) {
        if (connectedCount <= 0) {
            showScreenCastingManagement = false
        }
    }
    LaunchedEffect(showTopToolbar, topToolbarShowRequestId, showScreenCastingManagement) {
        if (showTopToolbar && !showScreenCastingManagement) {
            delay(5000)
            showTopToolbar = false
        }
    }
    SystemBackHandler(enabled = showSettingsScreen || showScreenCastingManagement || showTransferredFilesScreen) {
        when {
            showTransferredFilesScreen -> showTransferredFilesScreen = false
            showSettingsScreen -> showSettingsScreen = false
            showScreenCastingManagement -> showScreenCastingManagement = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ScreenSharingBackground)
    ) {
        MultiClientGridView(
            activeShares = activeShares,
            clientCastingPolicies = clientCastingPolicies,
            pinnedClientId = pinnedClientId,
            showClientInfo = showTopToolbar,
            onToggleClientAudioMuted = onToggleClientAudioMuted,
            onTogglePin = { clientId, pinned ->
                val currentPolicy = clientCastingPolicies[clientId] ?: ClientCastingPolicy()
                onClientCastingPolicyChange(clientId, currentPolicy.copy(isScreenExclusiveEnabled = pinned))
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (!showSettingsScreen && !showScreenCastingManagement) {
                        showTopToolbar = true
                        topToolbarShowRequestId += 1
                    }
                },
        )

        if (showTopToolbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.dp24)
                    .zIndex(3f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                HostTopToolbar(
                    deviceName = deviceName,
                    networkName = networkName,
                    connectionCode = connectionCode,
                    isNetworkConnected = isNetworkConnected,
                    connectedCount = connectedCount,
                    onConnectedDevicesClick = {
                        if (connectedCount <= 0) {
                            showScreenCastingManagement = false
                            return@HostTopToolbar
                        }
                        val shouldShowManagement = !showScreenCastingManagement
                        showScreenCastingManagement = shouldShowManagement
                        if (shouldShowManagement) {
                            showSettingsScreen = false
                        }
                    },
                    onTransferredFilesClick = {
                        onTransferredFilesClick()
                        showTransferredFilesScreen = true
                        showScreenCastingManagement = false
                    },
                    onSettingsClick = {
                        showSettingsScreen = true
                        showScreenCastingManagement = false
                    },
                    backgroundAlpha = 0.82f,
                    modifier = Modifier
                        .fillMaxWidth(),
                )
            }
        }

        if (showScreenCastingManagement && connectedCount > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        showScreenCastingManagement = false
                    },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppDimens.dp24)
                    .zIndex(5f),
                contentAlignment = Alignment.TopEnd,
            ) {
                HostScreenCastingManagementCard(
                    devices = mapConnectedClientsToManagementItems(connectedClients),
                    actionStates = clientCastingPolicies,
                    sharingClientIds = sharingClientIds,
                    activeByomClientIds = activeByomClientIds,
                    onActionStateChange = onClientCastingPolicyChange,
                    onByomToggle = onByomToggle,
                    onCloseClick = { showScreenCastingManagement = false },
                    onRemoveDeviceClick = onStopSharingForClient,
                    modifier = Modifier
                        .padding(top = topToolbarHeight + topToolbarPopupGap)
                        .widthIn(min = AppDimens.dp280, max = AppDimens.dp525),
                )
            }
        }

        if (showSettingsScreen) {
            HostConnectionSettingsScreen(
                deviceName = deviceName,
                connectionSettings = connectionSettings,
                onDeviceNameUpdate = onDeviceNameChange,
                onConnectionSettingsChange = onConnectionSettingsChange,
                onBackClick = { showSettingsScreen = false },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(6f),
            )
        }

        if (showTransferredFilesScreen) {
            TransferredFilesScreen(
                onBackClick = { showTransferredFilesScreen = false },
                onFileClick = { file ->
                    val opened = openFileWithExternalApp(file.absolutePath, file.mimeType)
                    if (!opened) {
                        fileOpenToastMessage = "No supported app found"
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(7f),
            )
        }

        fileOpenToastMessage?.let { message ->
            LaunchedEffect(message) {
                delay(3000)
                fileOpenToastMessage = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = AppDimens.dp16, bottom = AppDimens.dp16)
                    .zIndex(8f),
                contentAlignment = AbsoluteAlignment.BottomRight,
            ) {
                BlockedScreenCastingToast(message = message)
            }
        }
    }
}

@Composable
private fun HostTopToolbar(
    deviceName: String,
    networkName: String,
    connectionCode: String,
    isNetworkConnected: Boolean,
    connectedCount: Int,
    onConnectedDevicesClick: () -> Unit = {},
    onTransferredFilesClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    backgroundAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = AppBackground.copy(alpha = backgroundAlpha),
        shape = RoundedCornerShape(AppDimens.dp16),
        border = BorderStroke(AppDimens.dp1, AppSurface.copy(alpha = backgroundAlpha)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppDimens.dp18, vertical = AppDimens.dp10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = deviceName.ifBlank { stringResource(Res.string.device_name) },
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.width(AppDimens.dp20))
                Icon(
                    painter = painterResource(Res.drawable.wifi_icon),
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(AppDimens.dp16),
                )
                Spacer(modifier = Modifier.width(AppDimens.dp8))
                Text(
                    text = if (isNetworkConnected) {
                        stringResource(Res.string.network_info, networkName, connectionCode)
                    } else {
                        stringResource(Res.string.network_error)
                    },
                    color = if (isNetworkConnected) TextSecondary else ErrorColor,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppDimens.dp10),
            ) {
                val toolbarActionHeight = AppDimens.dp32
                val hasConnectedDevices = connectedCount > 0
                val devicesCardBorderColor = if (hasConnectedDevices) Color(0xFF1C8CD1) else Color(0xFF303030)
                val devicesCardContentColor = if (hasConnectedDevices) TextPrimary else Color(0xFF8C8C8C)
                val devicesCardContainerColor = if (hasConnectedDevices) CardButtonBackground else Color.Transparent

                Card(
                    modifier = Modifier
                        .height(toolbarActionHeight)
                        .clickable(onClick = onConnectedDevicesClick),
                    colors = CardDefaults.cardColors(devicesCardContainerColor),
                    shape = RoundedCornerShape(AppDimens.dp8),
                    border = BorderStroke(width = dp1, color = devicesCardBorderColor),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = AppDimens.dp14),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.connected_device_icon),
                            contentDescription = null,
                            tint = devicesCardContentColor,
                            modifier = Modifier.size(AppDimens.dp14),
                        )
                        Spacer(modifier = Modifier.width(AppDimens.dp8))
                        Text(
                            text = connectedCount.toString(),
                            color = devicesCardContentColor,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

//                Surface(
//                    color = AppBorder,
//                    shape = RoundedCornerShape(AppDimens.dp10),
//                ) {
//                    Row(
//                        modifier = Modifier.padding(horizontal = AppDimens.dp14, vertical = AppDimens.dp8),
//                        verticalAlignment = Alignment.CenterVertically,
//                    ) {
//                        Icon(
//                            painter = painterResource(Res.drawable.ic_lock),
//                            contentDescription = null,
//                            tint = TextPrimary,
//                            modifier = Modifier.size(AppDimens.dp14),
//                        )
//                        Spacer(modifier = Modifier.width(AppDimens.dp8))
//                        Text(
//                            text = stringResource(Res.string.open),
//                            color = TextPrimary,
//                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
//                        )
//                    }
//                }

                Icon(
                    painter = painterResource(Res.drawable.file_transfer_icon),
                    contentDescription = "Transferred Files",
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(toolbarActionHeight)
                        .clickable(onClick = onTransferredFilesClick),
                )

                Icon(
                    painter = painterResource(Res.drawable.settings_icon),
                    contentDescription = "Settings",
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(toolbarActionHeight)
                        .clickable(onClick = onSettingsClick),
                )
            }
        }
    }
}

private fun resolveDisplayedConnectionPin(pin: String): String {
    return pin.takeIf { it.isNotBlank() } ?: "------"
}

@Composable
private fun HostScreenCastingManagementCard(
    devices: List<ScreenCastingDeviceItem>,
    actionStates: Map<String, ClientCastingPolicy>,
    sharingClientIds: Set<String>,
    activeByomClientIds: Set<String> = emptySet(),
    onActionStateChange: (String, ClientCastingPolicy) -> Unit,
    onByomToggle: (String, Boolean) -> Unit = { _, _ -> },
    onCloseClick: () -> Unit = {},
    onRemoveDeviceClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val normalizedDevices = devices.map { device ->
        device.copy(clientName = device.clientName.ifBlank { "Unknown Device" })
    }
    val deviceNameColumnWeight = 1.5f
    val managementRowSpacing = AppDimens.dp4
    val managementRowStartPadding = AppDimens.dp16
    val managementRowEndPadding = AppDimens.dp5

    Card(
        modifier = modifier.shadow(
            elevation = AppDimens.dp1,
            shape = RoundedCornerShape(AppDimens.dp16),
            ambientColor = ScreenCastingManagementCardShadow,
            spotColor = ScreenCastingManagementCardShadow,
        ),
        colors = CardDefaults.cardColors(containerColor = ScreenCastingManagementBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = AppDimens.dp0),
        shape = RoundedCornerShape(AppDimens.dp16),
        border = BorderStroke(AppDimens.dp1, ScreenCastingManagementStroke),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(AppDimens.dp28),
                    color = AppBorder.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(AppDimens.dp16),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(Res.drawable.connected_device_icon),
                            contentDescription = null,
                            tint = PrimaryAccent,
                            modifier = Modifier.size(AppDimens.dp14),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(AppDimens.dp14))
                Text(
                    text = "Connected Device",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(Res.drawable.ic_close),
                    contentDescription = "Close device list",
                    tint = TextSecondary,
                    modifier = Modifier
                        .size(AppDimens.dp20)
                        .clickable(onClick = onCloseClick),
                )
            }

            HorizontalDivider(color = ScreenCastingManagementStroke)

            if (normalizedDevices.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp14),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "No device connected yet.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val deviceNameHeaderStartInset = AppDimens.dp24 + AppDimens.dp12
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = managementRowStartPadding,
                            top = AppDimens.dp8,
                            end = managementRowEndPadding,
                            bottom = AppDimens.dp8,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Device name",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(deviceNameColumnWeight)
                            .padding(start = deviceNameHeaderStartInset),
                    )
                    Spacer(modifier = Modifier.width(managementRowSpacing))
                    Text(
                        text = "Cast",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.95f),
                    )
                    Spacer(modifier = Modifier.width(managementRowSpacing))
                    Text(
                        text = "Reverse Cast",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(managementRowSpacing))
                    Text(
                        text = "Control",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.8f),
                    )
                    Spacer(modifier = Modifier.width(managementRowSpacing))
                    Text(
                        text = "BYOM",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.8f),
                    )
                    Spacer(modifier = Modifier.width(managementRowSpacing))
                    Text(
                        text = "Remove",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.8f),
                    )
                }

                HorizontalDivider(color = ScreenCastingManagementStroke)

                normalizedDevices.forEachIndexed { index, device ->
                    val actionState = actionStates[device.clientId] ?: ClientCastingPolicy()
                    val isNonWebRtcClient = isAirPlayClientId(device.clientId) || isMiracastClientId(device.clientId)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = managementRowStartPadding,
                                top = AppDimens.dp8,
                                end = managementRowEndPadding,
                                bottom = AppDimens.dp8,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(deviceNameColumnWeight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (device.deviceType == ClientDeviceType.Mobile) {
                                        Res.drawable.mobile_icon
                                    } else {
                                        Res.drawable.laptop_icon
                                    },
                                ),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(AppDimens.dp24),
                            )
                            Spacer(modifier = Modifier.width(AppDimens.dp12))
                            AutoScrollingDeviceNameText(
                                text = device.clientName,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.width(managementRowSpacing))

                        ScreenCastingSwitchAction(
                            isEnabled = actionState.isScreenCastEnabled,
                            onCheckedChange = { isEnabled ->
                                onActionStateChange(
                                    device.clientId,
                                    actionState.copy(
                                        isScreenCastEnabled = isEnabled,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(0.95f),
                        )
                        Spacer(modifier = Modifier.width(managementRowSpacing))

                        if (isNonWebRtcClient) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "-",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            ScreenCastingSwitchAction(
                                isEnabled = actionState.isReverseCastEnabled,
                                onCheckedChange = { isEnabled ->
                                    onActionStateChange(
                                        device.clientId,
                                        actionState.copy(
                                            isReverseCastEnabled = isEnabled,
                                        ),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.width(managementRowSpacing))

                        if (isNonWebRtcClient) {
                            Box(
                                modifier = Modifier.weight(0.8f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "-",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier.weight(0.8f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.TouchApp,
                                    contentDescription = if (actionState.isRemoteControlEnabled) {
                                        "Disable remote control"
                                    } else {
                                        "Enable remote control"
                                    },
                                    tint = if (actionState.isRemoteControlEnabled) PrimaryAccent else TextSecondary,
                                    modifier = Modifier
                                        .size(AppDimens.dp28)
                                        .clickable {
                                            onActionStateChange(
                                                device.clientId,
                                                actionState.copy(
                                                    isRemoteControlEnabled = !actionState.isRemoteControlEnabled,
                                                ),
                                            )
                                        },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(managementRowSpacing))

                        if (isNonWebRtcClient) {
                            Box(
                                modifier = Modifier.weight(0.8f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "-",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            val isByomActive = device.clientId in activeByomClientIds
                            ScreenCastingSwitchAction(
                                isEnabled = isByomActive,
                                onCheckedChange = { enabled -> onByomToggle(device.clientId, enabled) },
                                modifier = Modifier.weight(0.8f),
                            )
                        }
                        Spacer(modifier = Modifier.width(managementRowSpacing))

                        Box(
                            modifier = Modifier.weight(0.8f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.remove_icon),
                                contentDescription = "delete",
                                tint = Color.Unspecified,
                                modifier = Modifier
                                    .size(AppDimens.dp28)
                                    .clickable {
                                        onRemoveDeviceClick(device.clientId)
                                    },
                            )
                        }
                    }
                    if (index < normalizedDevices.lastIndex) {
                        HorizontalDivider(color = ScreenCastingManagementStroke)
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoScrollingDeviceNameText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val marqueeGap = AppDimens.dp24
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val availableWidthPx = with(density) { maxWidth.roundToPx() }
        val measuredTextWidthPx = remember(text, style, density) {
            textMeasurer.measure(
                text = AnnotatedString(text),
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            ).size.width
        }
        val hasValidWidth = availableWidthPx > 0
        val shouldScroll = hasValidWidth && measuredTextWidthPx > availableWidthPx

        if (shouldScroll) {
            val marqueeGapPx = with(density) { marqueeGap.roundToPx() }
            val scrollDistancePx = measuredTextWidthPx + marqueeGapPx
            val durationMillis = remember(scrollDistancePx) {
                (scrollDistancePx * 24).coerceIn(3000, 16000)
            }
            val infiniteTransition = rememberInfiniteTransition(label = "device-name-marquee")
            val scrollOffsetPx by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = scrollDistancePx.toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = durationMillis,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "device-name-marquee-offset",
            )

            Row(
                modifier = Modifier
                    .offset { IntOffset(-scrollOffsetPx.roundToInt(), 0) }
                    .wrapContentWidth(
                        align = Alignment.Start,
                        unbounded = true,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = text,
                    color = color,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(modifier = Modifier.width(marqueeGap))
                Text(
                    text = text,
                    color = color,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Text(
                text = text,
                color = color,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScreenCastingSwitchAction(
    isEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = isEnabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.6f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = PrimaryAccent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = AppBorder,
            ),
        )
    }
}

@Composable
private fun ScreenCastingToggleActionButton(
    icon: DrawableResource,
    isEnabled: Boolean,
    isInteractionEnabled: Boolean = true,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconTint = if (isEnabled) {
        PrimaryAccent
    } else {
        TextSecondary.copy(alpha = if (isInteractionEnabled) 1f else 0.45f)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier
                .size(AppDimens.dp28)
                .alpha(if (isInteractionEnabled) 1f else 0.65f)
                .clickable(enabled = isInteractionEnabled, onClick = onClick),
        )
    }


}

private data class ScreenCastingDeviceItem(
    val clientId: String,
    val clientName: String,
    val deviceType: ClientDeviceType,
)

private fun mapConnectedClientsToManagementItems(
    connectedClients: List<ClientInfo>,
): List<ScreenCastingDeviceItem> {
    return connectedClients
        .distinctBy { it.clientId }
        .map { client ->
            ScreenCastingDeviceItem(
                clientId = client.clientId,
                clientName = client.name.ifBlank { "Unknown Device" },
                deviceType = detectClientDeviceType(client.name),
            )
        }
}

private fun detectClientDeviceType(clientName: String): ClientDeviceType {
    val normalizedName = clientName.lowercase()
    return when {
        normalizedName.contains("android") ||
            normalizedName.contains("iphone") ||
            normalizedName.contains("ios") ||
            normalizedName.contains("ipad") ||
            normalizedName.contains("phone") ||
            normalizedName.contains("mobile") -> ClientDeviceType.Mobile
        else -> ClientDeviceType.Laptop
    }
}

@Composable
fun MultiClientGridView(
    activeShares: List<ActiveShare>,
    clientCastingPolicies: Map<String, ClientCastingPolicy> = emptyMap(),
    pinnedClientId: String? = null,
    showClientInfo: Boolean = false,
    onToggleClientAudioMuted: (String, Boolean) -> Unit = { _, _ -> },
    onTogglePin: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // Defensive de-duplication by client ID before rendering.
    val uniqueShares = activeShares.distinctBy { it.clientId }
    val pinnedShare = pinnedClientId?.let { targetId ->
        uniqueShares.firstOrNull { it.clientId == targetId }
    }
    val shouldShowPinnedLayout = uniqueShares.size > 1 && pinnedShare != null
    val visibleShares = if (shouldShowPinnedLayout) {
        // Pinned layout supports up to 4 total tiles:
        // 1 large pinned tile + up to 3 compact side tiles.
        listOf(pinnedShare) + uniqueShares
            .asSequence()
            .filterNot { it.clientId == pinnedShare.clientId }
            .take(3)
            .toList()
    } else {
        uniqueShares.take(4)
    }
    val visibleShareCount = visibleShares.size
    val gridCardSpacing = if (visibleShareCount > 1) AppDimens.dp5 else AppDimens.dp12
    val canPinShares = uniqueShares.size > 1
    println("GRID_VIEW: Composing with shareCount=${uniqueShares.size}, pinnedClientId=$pinnedClientId")
    println("GRID_VIEW: Share IDs: ${uniqueShares.map { it.clientId }}")
    if (uniqueShares.size > 4) {
        println("GRID_VIEW: More than 4 shares detected. Showing first 4 only.")
    }

    when {
        shouldShowPinnedLayout -> {
            val nonPinnedShares = visibleShares.drop(1)
            Row(
                modifier = modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(gridCardSpacing),
            ) {
                ClientScreenTile(
                    share = visibleShares.first(),
                    isAudioEnabled = clientCastingPolicies[visibleShares.first().clientId]?.isAudioEnabled ?: false,
                    isPinned = visibleShares.first().clientId == pinnedClientId,
                    isPinInteractionEnabled = canPinShares,
                    showClientInfo = showClientInfo,
                    onToggleClientAudioMuted = onToggleClientAudioMuted,
                    onTogglePin = onTogglePin,
                    modifier = Modifier
                        .weight(3.6f)
                        .fillMaxHeight(),
                )

                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(gridCardSpacing),
                ) {
                    val sideShareCount = nonPinnedShares.size.coerceAtMost(3)
                    val emptySlots = (3 - sideShareCount).coerceAtLeast(0)
                    val topBottomSpacerWeight = emptySlots / 2f

                    if (topBottomSpacerWeight > 0f) {
                        Spacer(
                            modifier = Modifier
                                .weight(topBottomSpacerWeight)
                                .fillMaxWidth(),
                        )
                    }

                    nonPinnedShares.take(3).forEach { sideShare ->
                        key("pinned-side-${sideShare.clientId}") {
                            ClientScreenTile(
                                share = sideShare,
                                isAudioEnabled = clientCastingPolicies[sideShare.clientId]?.isAudioEnabled ?: false,
                                isPinned = sideShare.clientId == pinnedClientId,
                                isPinInteractionEnabled = canPinShares,
                                showClientInfo = showClientInfo,
                                onToggleClientAudioMuted = onToggleClientAudioMuted,
                                onTogglePin = onTogglePin,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        }
                    }

                    if (topBottomSpacerWeight > 0f) {
                        Spacer(
                            modifier = Modifier
                                .weight(topBottomSpacerWeight)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        }

        else -> when (visibleShareCount) {
        0 -> EmptyGridTile(
            modifier = modifier.fillMaxSize(),
        )

        1 -> ClientScreenTile(
            share = visibleShares.first(),
            isAudioEnabled = clientCastingPolicies[visibleShares.first().clientId]?.isAudioEnabled ?: false,
            isPinned = false,
            isPinInteractionEnabled = false,
            showClientInfo = showClientInfo,
            onToggleClientAudioMuted = onToggleClientAudioMuted,
            onTogglePin = onTogglePin,
            modifier = modifier.fillMaxSize(),
        )

        2, 3 -> Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gridCardSpacing),
        ) {
            visibleShares.forEach { share ->
                key("single-row-${share.clientId}") {
                    ClientScreenTile(
                        share = share,
                        isAudioEnabled = clientCastingPolicies[share.clientId]?.isAudioEnabled ?: false,
                        isPinned = share.clientId == pinnedClientId,
                        isPinInteractionEnabled = canPinShares,
                        showClientInfo = showClientInfo,
                        onToggleClientAudioMuted = onToggleClientAudioMuted,
                        onTogglePin = onTogglePin,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        else -> Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gridCardSpacing),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gridCardSpacing),
            ) {
                visibleShares.take(2).forEach { share ->
                    key("top-row-${share.clientId}") {
                        ClientScreenTile(
                            share = share,
                            isAudioEnabled = clientCastingPolicies[share.clientId]?.isAudioEnabled ?: false,
                            isPinned = share.clientId == pinnedClientId,
                            isPinInteractionEnabled = canPinShares,
                            showClientInfo = showClientInfo,
                            onToggleClientAudioMuted = onToggleClientAudioMuted,
                            onTogglePin = onTogglePin,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gridCardSpacing),
            ) {
                visibleShares.drop(2).take(2).forEach { share ->
                    key("bottom-row-${share.clientId}") {
                        ClientScreenTile(
                            share = share,
                            isAudioEnabled = clientCastingPolicies[share.clientId]?.isAudioEnabled ?: false,
                            isPinned = share.clientId == pinnedClientId,
                            isPinInteractionEnabled = canPinShares,
                            showClientInfo = showClientInfo,
                            onToggleClientAudioMuted = onToggleClientAudioMuted,
                            onTogglePin = onTogglePin,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun ClientScreenTile(
    share: ActiveShare,
    isAudioEnabled: Boolean = false,
    isPinned: Boolean = false,
    isPinInteractionEnabled: Boolean = false,
    showClientInfo: Boolean = false,
    onToggleClientAudioMuted: (String, Boolean) -> Unit = { _, _ -> },
    onTogglePin: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    println("CLIENT_TILE: Composing tile for client ${share.clientId}, track=${share.videoTrack != null}")
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(AppDimens.dp5),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        border = BorderStroke(AppDimens.dp1, AppBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    share.isMiracastShare() -> {
                        MiracastTileRenderer(
                            share = share,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    share.isAirPlayShare() -> {
                        AirPlayTileRenderer(
                            share = share,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    share.videoTrack != null -> {
                        println(
                            "CLIENT_TILE: Creating VideoRenderer for client " +
                                "${share.clientId}, rotation=${share.displayRotation}°"
                        )
                        val rotationDegrees = share.displayRotation.toFloat()
                        if (rotationDegrees == 0f || rotationDegrees == 180f) {
                            VideoRenderer(
                                track = share.videoTrack,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .rotate(rotationDegrees),
                                debugLabel = "host-client:${share.clientId}",
                            )
                        } else {
                            // Client is in landscape but capturing into a portrait
                            // VirtualDisplay buffer, so the letterboxed content needs to
                            // be rotated back upright. Rotation around center means the
                            // inner renderer must have swapped width/height so the
                            // rotated rectangle still fills the tile.
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val tileWidth = maxWidth
                                val tileHeight = maxHeight
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(width = tileHeight, height = tileWidth)
                                        .rotate(-rotationDegrees),
                                ) {
                                    VideoRenderer(
                                        track = share.videoTrack,
                                        modifier = Modifier.fillMaxSize(),
                                        debugLabel = "host-client:${share.clientId}",
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Image(
                                painter = painterResource(Res.drawable.satellite_icon),
                                contentDescription = null,
                                modifier = Modifier.size(AppDimens.dp52),
                            )
                            Spacer(modifier = Modifier.height(AppDimens.dp14))
                            Text(
                                text = "Reconnecting....",
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }
            }

            if (showClientInfo) {
                val shareDeviceType = detectClientDeviceType(share.clientName)
                val infoChipHeight = AppDimens.dp36
                val infoChipShape = RoundedCornerShape(AppDimens.dp8)
                val infoChipBackground = AppSurface.copy(alpha = 0.72f)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = AppDimens.dp10, vertical = AppDimens.dp10),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = infoChipBackground,
                        shape = infoChipShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(infoChipHeight),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = AppDimens.dp12),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (shareDeviceType == ClientDeviceType.Mobile) {
                                        Res.drawable.mobile_icon
                                    } else {
                                        Res.drawable.laptop_icon
                                    },
                                ),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(AppDimens.dp20),
                            )
                            Spacer(modifier = Modifier.width(AppDimens.dp8))
                            AutoScrollingDeviceNameText(
                                text = share.clientName.ifBlank { "Unknown Device" },
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(AppDimens.dp8))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AppDimens.dp8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isPinInteractionEnabled) {
                            Surface(
                                color = infoChipBackground,
                                shape = infoChipShape,
                                modifier = Modifier.size(infoChipHeight),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(infoChipShape)
                                        .clickable { onTogglePin(share.clientId, !isPinned) }
                                        .padding(AppDimens.dp8),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.pin_icon),
                                        contentDescription = if (isPinned) {
                                            "Unpin ${share.clientName}"
                                        } else {
                                            "Pin ${share.clientName}"
                                        },
                                        tint = if (isPinned) PrimaryAccent else TextSecondary,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        Surface(
                            color = infoChipBackground,
                            shape = infoChipShape,
                            modifier = Modifier.size(infoChipHeight),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(infoChipShape)
                                    .clickable {
                                        onToggleClientAudioMuted(share.clientId, isAudioEnabled)
                                    }
                                    .padding(AppDimens.dp8),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (isAudioEnabled) {
                                            Res.drawable.speaker_icon
                                        } else {
                                            Res.drawable.speaker_icon_off
                                        },
                                    ),
                                    contentDescription = if (isAudioEnabled) {
                                        "Mute ${share.clientName}"
                                    } else {
                                        "Unmute ${share.clientName}"
                                    },
                                    tint = Color.Unspecified,
                                    modifier = Modifier.fillMaxSize(),
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
private fun TileControlButton(
    icon: DrawableResource,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit = {},
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(AppDimens.dp34),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Color.Unspecified,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.35f),
        )
    }
}

@Composable
fun EmptyGridTile(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(AppDimens.dp14),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        border = BorderStroke(AppDimens.dp1, AppBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(Res.drawable.satellite_icon),
                    contentDescription = null,
                    modifier = Modifier.size(AppDimens.dp52),
                )
                Spacer(modifier = Modifier.height(AppDimens.dp14))
                Text(
                    text = "Waiting for device...",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun HostAutoManualCarousel(
    networkName: String,
    connectionCode: String,
    modifier: Modifier = Modifier,
    autoScrollIntervalMs: Long = 10000L,
    includeQrCard: Boolean = false,
    isCompactLayout: Boolean = false,
    isNetworkConnected: Boolean = true,
    qrCodeBitmap: ImageBitmap? = null,
    lastError: String? = null,
) {
    val cards = remember(includeQrCard) {
        buildList {
            add("card1")
            add("card2")
            if (includeQrCard) add("card3")
        }
    }
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { cards.size }
    )
    val contentPadding = if (isCompactLayout) AppDimens.dp10 else AppDimens.dp14
    val indicatorSize = if (isCompactLayout) AppDimens.dp10 else AppDimens.dp12
    val indicatorSpacing = if (isCompactLayout) AppDimens.dp10 else AppDimens.dp12
    val bottomSpacer = if (isCompactLayout) AppDimens.dp4 else AppDimens.dp6

    LaunchedEffect(cards.size) {
        if (cards.isNotEmpty() && pagerState.currentPage >= cards.size) {
            pagerState.scrollToPage(cards.lastIndex)
        }
    }

    LaunchedEffect(pagerState, autoScrollIntervalMs) {
        if (cards.size <= 1) return@LaunchedEffect
        while (true) {
            delay(autoScrollIntervalMs)
            if (!pagerState.isScrollInProgress) {
                val nextPage = (pagerState.currentPage + 1) % cards.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(color = AppBackground, shape = RoundedCornerShape(AppDimens.dp12)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(
                    width = AppDimens.dp1,
                    color = AppBackground,
                    shape = RoundedCornerShape(AppDimens.dp12)
                )
                .padding(contentPadding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF141414),
                                    Color(0xFF051E32),
                                )
                            ),
                            shape = RoundedCornerShape(AppDimens.dp12)
                        ),
                ) {
                    when (cards[page]) {
                        "card1" -> HostMobilePcInstructionCard(
                            networkName = networkName,
                            isNetworkConnected = isNetworkConnected,
                            compact = isCompactLayout,
                        )
                        "card2" -> HostBrowserInstructionCard(
                            networkName = networkName,
                            connectionCode = connectionCode,
                            isNetworkConnected = isNetworkConnected,
                            compact = isCompactLayout,
                        )
                        "card3" -> HostQrConnectionContent(
                            isNetworkConnected = isNetworkConnected,
                            connectionCode = connectionCode,
                            lastError = lastError,
                            qrCodeBitmap = qrCodeBitmap,
                            compact = isCompactLayout,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp10),
                        )
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(indicatorSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(cards.size) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .size(indicatorSize)
                        .background(
                            color = if (isSelected) TextPrimary else AppBorder,
                            shape = CircleShape
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(bottomSpacer))
    }
}

@Composable
private fun HostMobilePcInstructionCard(
    networkName: String,
    isNetworkConnected: Boolean,
    compact: Boolean = false,
) {
    val iconSize = if (compact) AppDimens.dp64 else AppDimens.dp80
    val appIconSize = if (compact) 54.dp else AppDimens.dp62
    val compactScrollState = rememberScrollState()
    val instructionRows: @Composable ColumnScope.() -> Unit = {
        HostInstructionStepRow(
            icon = {
                HostQrInstructionIcon(
                    icon = Res.drawable.share_x_app_download_qr_image,
                    size = iconSize,
                )
            },
            showConnector = true,
            compact = compact,
        ) {
            Text(
                text = "Scan QR code with mobile camera or visit :",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "https://www.teachmint.com/apps/sharex",
                color = PrimaryAccent,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Download Share X App based on your system type.",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HostInstructionStepRow(
            icon = { HostInstructionVectorIcon(Res.drawable.wifi_icon_big, size = iconSize) },
            showConnector = true,
            compact = compact,
        ) {
            Text(
                text = "Connect to Wifi Network:",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            HostInstructionNetworkChip(
                networkName = networkName,
                isNetworkConnected = isNetworkConnected,
            )
        }

        HostInstructionStepRow(
            icon = {
                HostInstructionVectorIcon(
                    icon = Res.drawable.sharex_app_icon,
                    size = appIconSize,
                )
            },
            showConnector = false,
            compact = compact,
        ) {
            Text(
                text = "Open the Share X App on your system",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Start screen sharing",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (compact) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(compactScrollState),
        ) {
            Text(
                text = "Mobile/PC",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppDimens.dp12),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = AppDimens.dp12, vertical = AppDimens.dp12),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = instructionRows,
            )
            Spacer(modifier = Modifier.height(AppDimens.dp8))
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = "Mobile/PC",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppDimens.dp20),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp20),
                    content = instructionRows,
                )
            }
        }
    }
}

@Composable
private fun HostInstructionStepRow(
    icon: @Composable () -> Unit,
    showConnector: Boolean,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val connectorColor = TextPrimary.copy(alpha = 0.72f)
    val iconColumnWidth = if (compact) 92.dp else AppDimens.dp109
    val iconBottomY = if (compact) AppDimens.dp64 else AppDimens.dp80
    val overlapIntoIcon = if (compact) AppDimens.dp18 else AppDimens.dp24
    val connectorSpacer = if (compact) AppDimens.dp38 else AppDimens.dp56
    val rowGap = if (compact) AppDimens.dp4 else AppDimens.dp6
    val textSpacing = if (compact) AppDimens.dp12 else AppDimens.dp16

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .width(iconColumnWidth)
                .drawBehind {
                    if (showConnector) {
                        val strokeWidth = AppDimens.dp4.toPx()
                        val startY = (iconBottomY.toPx() - overlapIntoIcon.toPx()).coerceAtLeast(0f)
                        drawLine(
                            color = connectorColor,
                            start = Offset(x = size.width / 2f, y = startY),
                            end = Offset(x = size.width / 2f, y = size.height),
                            strokeWidth = strokeWidth,
                        )
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            icon()
            if (showConnector) {
                Spacer(modifier = Modifier.height(connectorSpacer))
            }
        }
        Spacer(modifier = Modifier.width(textSpacing))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(rowGap),
            content = content,
        )
    }
}

@Composable
private fun HostBrowserInstructionCard(
    networkName: String,
    connectionCode: String,
    isNetworkConnected: Boolean,
    compact: Boolean = false,
) {
    val iconSize = if (compact) AppDimens.dp64 else AppDimens.dp80
    val appIconSize = if (compact) 54.dp else AppDimens.dp62
    val compactScrollState = rememberScrollState()
    val instructionRows: @Composable ColumnScope.() -> Unit = {
        HostInstructionStepRow(
            icon = { HostInstructionVectorIcon(Res.drawable.wifi_icon_big, size = iconSize) },
            showConnector = true,
            compact = compact,
        ) {
            Text(
                text = "Connect to Wifi Network:",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            HostInstructionNetworkChip(
                networkName = networkName,
                isNetworkConnected = isNetworkConnected,
            )
        }

        HostInstructionStepRow(
            icon = {
                HostQrInstructionIcon(
                    icon = Res.drawable.sharex_web_browser_qr_image,
                    size = iconSize,
                )
            },
            showConnector = true,
            compact = compact,
        ) {
            Text(
                text = "Scan QR code with mobile camera or visit :",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "https://sharex.teachmint.com/",
                color = PrimaryAccent,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HostInstructionStepRow(
            icon = {
                HostInstructionVectorIcon(
                    icon = Res.drawable.sharex_app_icon,
                    size = appIconSize,
                )
            },
            showConnector = false,
            compact = compact,
        ) {
            Text(
                text = "Enter the Pin below to cast",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            HostInstructionCodeChip(connectionCode)
        }
    }

    if (compact) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(compactScrollState),
        ) {
            Text(
                text = "Browser",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppDimens.dp12),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = AppDimens.dp12, vertical = AppDimens.dp12),
                content = instructionRows,
            )
            Spacer(modifier = Modifier.height(AppDimens.dp8))
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = "Browser",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppDimens.dp20),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp20),
                    content = instructionRows,
                )
            }
        }
    }
}

@Composable
private fun HostInstructionVectorIcon(
    icon: DrawableResource,
    size: Dp = AppDimens.dp80,
) {
    Image(
        painter = painterResource(icon),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun HostSatelliteInstructionIcon() {
    Surface(
        modifier = Modifier.size(AppDimens.dp80),
        color = Color(0xFF0C1015),
        shape = RoundedCornerShape(AppDimens.dp14),
        border = BorderStroke(AppDimens.dp1, AppBorder.copy(alpha = 0.75f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(Res.drawable.satellite_icon),
                contentDescription = null,
                modifier = Modifier.size(AppDimens.dp56),
            )
        }
    }
}

@Composable
private fun HostQrInstructionIcon(
    icon: DrawableResource,
    size: Dp = AppDimens.dp80,
) {
    Image(
        painter = painterResource(icon),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun HostInstructionNetworkChip(
    networkName: String,
    isNetworkConnected: Boolean,
) {
    val label = if (isNetworkConnected) {
        networkName.takeIf { it.isNotBlank() } ?: "DEMO"
    } else {
        stringResource(Res.string.network_error)
    }
    val containerColor = if (isNetworkConnected) Color(0x333B0F66) else ErrorColor.copy(alpha = 0.16f)
    val borderColor = if (isNetworkConnected) Color(0xFF6A2FBA) else ErrorColor
    val textColor = if (isNetworkConnected) Color(0xFFBB7EFF) else ErrorColor
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(AppDimens.dp14),
        border = BorderStroke(AppDimens.dp1, borderColor),
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = AppDimens.dp8, vertical = AppDimens.dp4),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HostInstructionCodeChip(code: String) {
    Surface(
        color = Color(0x333B0F66),
        shape = RoundedCornerShape(AppDimens.dp14),
        border = BorderStroke(AppDimens.dp1, Color(0xFF6A2FBA)),
    ) {
        Text(
            text = code,
            color = Color(0xFFBB7EFF),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = AppDimens.dp10, vertical = AppDimens.dp4),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HostQrConnectionContent(
    isNetworkConnected: Boolean,
    connectionCode: String,
    lastError: String?,
    qrCodeBitmap: ImageBitmap?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(lastError) {
        lastError
            ?.takeIf { it.isNotBlank() }
            ?.let { println("HOST_UI: hidden QR connection error: $it") }
    }

    if (!isNetworkConnected) {
        NoNetworkComposable(
            modifier = modifier.fillMaxSize(),
            compact = true,
            iconSize = AppDimens.dp50,
            titleColor = TextPrimary,
            subtitleColor = ErrorColor,
        )
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val scrollState = rememberScrollState()
        val qrSize = if (compact) {
            (maxWidth * 0.52f).coerceIn(128.dp, 180.dp)
        } else {
            AppDimens.dp200
        }
        val satelliteIconSize = if (compact) AppDimens.dp64 else AppDimens.dp80
        val headingStyle = if (compact) {
            MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        } else {
            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        }
        val codeStyle = if (compact) {
            MaterialTheme.typography.headlineMedium
        } else {
            MaterialTheme.typography.headlineLarge
        }
        val contentModifier = if (compact) {
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(vertical = AppDimens.dp10)
        } else {
            Modifier.fillMaxSize()
        }
        val columnArrangement = if (compact) Arrangement.Top else Arrangement.Center
        val iconToHeadingSpacer = if (compact) AppDimens.dp10 else AppDimens.dp14
        val headingToSubheadingSpacer = if (compact) AppDimens.dp6 else AppDimens.dp6
        val subheadingToQrSpacer = if (compact) AppDimens.dp12 else AppDimens.dp20
        val qrToPinSpacer = if (compact) AppDimens.dp10 else AppDimens.dp15
        val pinToCodeSpacer = if (compact) AppDimens.dp10 else AppDimens.dp12

        Column(
            modifier = contentModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = columnArrangement,
        ) {
            Image(
                modifier = Modifier.size(satelliteIconSize),
                painter = painterResource(Res.drawable.satellite_icon),
                contentDescription = null,
            )

            Spacer(modifier = Modifier.height(iconToHeadingSpacer))

            Text(
                text = stringResource(Res.string.ready_to_receive),
                color = TextPrimary,
                style = headingStyle,
            )

            Spacer(modifier = Modifier.height(headingToSubheadingSpacer))

            Text(
                text = stringResource(Res.string.scan_the_qr_code),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(subheadingToQrSpacer))

            Surface(
                modifier = Modifier.size(qrSize),
                color = TextPrimary,
                shape = RoundedCornerShape(AppDimens.dp8),
            ) {
                if (qrCodeBitmap != null) {
                    Image(
                        bitmap = qrCodeBitmap,
                        contentDescription = "QR Code for connection",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AppDimens.dp8)
                            .clip(RoundedCornerShape(AppDimens.dp8))
                    )
                } else {
                    Icon(
                        painter = painterResource(Res.drawable.ic_qr_code),
                        contentDescription = "QR Code",
                        tint = AppBackground,
                        modifier = Modifier.padding(AppDimens.dp8)
                    )
                }
            }

            Text(
                text = stringResource(Res.string.enter_code_instruction),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = qrToPinSpacer),
            )

            Spacer(modifier = Modifier.height(pinToCodeSpacer))

            Text(
                text = connectionCode,
                color = TextSecondary,
                style = codeStyle,
            )

//            if (!lastError.isNullOrBlank()) {
//                Spacer(modifier = Modifier.height(AppDimens.dp10))
//                Text(
//                    text = lastError,
//                    color = ErrorColor,
//                    style = MaterialTheme.typography.bodySmall,
//                    textAlign = TextAlign.Center,
//                )
//            }

            if (compact) {
                Spacer(modifier = Modifier.height(AppDimens.dp10))
            }
        }
    }
}

@Composable
fun HostWaitingScreen(
    deviceName: String,
    networkName: String,
    ipAddress: String,
    isNetworkConnected: Boolean,
    isInMultiWindowMode: Boolean,
    connectedClients: List<ClientInfo>,
    clientCastingPolicies: Map<String, ClientCastingPolicy>,
    connectionSettings: HostConnectionSettings,
    connectedCount: Int,
    pin: String,
    lastError: String? = null,
    activeByomClientIds: Set<String> = emptySet(),
    onStopSharingForClient: (String) -> Unit = {},
    onClientCastingPolicyChange: (String, ClientCastingPolicy) -> Unit = { _, _ -> },
    onDeviceNameChange: (String) -> Unit = {},
    onConnectionSettingsChange: (HostConnectionSettings) -> Unit = {},
    onTransferredFilesClick: () -> Unit = {},
    onByomToggle: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val connectionCode = remember(connectionSettings.remoteSignalingUrl, pin, ipAddress, isNetworkConnected) {
        resolveDisplayedConnectionPin(pin = pin)
    }
    val topToolbarHeight = AppDimens.dp52
    val topToolbarPopupGap = AppDimens.dp5

    var showScreenCastingManagement by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showTransferredFilesScreen by remember { mutableStateOf(false) }
    var fileOpenToastMessage by remember { mutableStateOf<String?>(null) }
    val qrCodeBitmap = remember(ipAddress, deviceName, isNetworkConnected) {
        if (!isNetworkConnected) {
            null
        } else {
            val connectionData = QRConnectionData(
                hostIp = ipAddress,
                port = SIGNALING_PORT,
                hostName = deviceName
            )
            val jsonData = connectionData.toJson()
            println("HOST: Generated QR code with data: $jsonData")
            println("HOST: IP=$ipAddress, Port=$SIGNALING_PORT, Name=$deviceName")
            QRCodeGenerator().generateQRCode(
                data = jsonData,
                size = 512
            )
        }
    }

    LaunchedEffect(connectedCount) {
        if (connectedCount <= 0) {
            showScreenCastingManagement = false
        }
    }
    SystemBackHandler(enabled = showSettingsScreen || showScreenCastingManagement || showTransferredFilesScreen) {
        when {
            showTransferredFilesScreen -> showTransferredFilesScreen = false
            showSettingsScreen -> showSettingsScreen = false
            showScreenCastingManagement -> showScreenCastingManagement = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        AppSurface,
                        AppBorder
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = AppDimens.dp15,
                    start = AppDimens.dp19,
                    end = AppDimens.dp19,
                    bottom = AppDimens.dp10,
                )
        ) {
            HostTopToolbar(
                deviceName = deviceName,
                networkName = networkName,
                connectionCode = connectionCode,
                isNetworkConnected = isNetworkConnected,
                connectedCount = connectedCount,
                onConnectedDevicesClick = {
                    if (connectedCount <= 0) {
                        showScreenCastingManagement = false
                        return@HostTopToolbar
                    }
                    val shouldShowManagement = !showScreenCastingManagement
                    showScreenCastingManagement = shouldShowManagement
                    if (shouldShowManagement) {
                        showSettingsScreen = false
                    }
                },
                onTransferredFilesClick = {
                    onTransferredFilesClick()
                    showTransferredFilesScreen = true
                    showScreenCastingManagement = false
                },
                onSettingsClick = {
                    showSettingsScreen = true
                    showScreenCastingManagement = false
                },
            )

            Spacer(modifier = Modifier.height(AppDimens.dp12))
            // Main Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isInMultiWindowMode) {
                    HostAutoManualCarousel(
                        networkName = networkName,
                        connectionCode = connectionCode,
                        includeQrCard = true,
                        isCompactLayout = true,
                        isNetworkConnected = isNetworkConnected,
                        qrCodeBitmap = qrCodeBitmap,
                        lastError = lastError,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(AppDimens.dp14),
                        verticalAlignment = Alignment.Top,
                    ) {
                        HostAutoManualCarousel(
                            networkName = networkName,
                            connectionCode = connectionCode,
                            includeQrCard = false,
                            isCompactLayout = false,
                            isNetworkConnected = isNetworkConnected,
                            modifier = Modifier
                                .weight(0.4f)
                                .fillMaxHeight(),
                        )
                        Column(
                            modifier = Modifier
                                .weight(0.6f)
                                .fillMaxHeight()
                                .background(
                                    color = AppBackground,
                                    shape = RoundedCornerShape(AppDimens.dp12)
                                )
                                .border(
                                    width = AppDimens.dp1,
                                    color = AppSurface,
                                    shape = RoundedCornerShape(AppDimens.dp12)
                                )
                                .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp14),
                        ) {
                            HostQrConnectionContent(
                                isNetworkConnected = isNetworkConnected,
                                connectionCode = connectionCode,
                                lastError = lastError,
                                qrCodeBitmap = qrCodeBitmap,
                                compact = false,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

        }

        if (showScreenCastingManagement && connectedCount > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        showScreenCastingManagement = false
                    },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = AppDimens.dp15, start = AppDimens.dp19, end = AppDimens.dp19)
                    .zIndex(3f),
                contentAlignment = Alignment.TopEnd,
            ) {
                HostScreenCastingManagementCard(
                    devices = mapConnectedClientsToManagementItems(connectedClients),
                    actionStates = clientCastingPolicies,
                    sharingClientIds = emptySet(),
                    activeByomClientIds = activeByomClientIds,
                    onActionStateChange = onClientCastingPolicyChange,
                    onByomToggle = onByomToggle,
                    onCloseClick = { showScreenCastingManagement = false },
                    onRemoveDeviceClick = onStopSharingForClient,
                    modifier = Modifier
                        .padding(top = topToolbarHeight + topToolbarPopupGap)
                        .widthIn(min = AppDimens.dp280, max = AppDimens.dp525),
                )
            }
        }

        if (showSettingsScreen) {
            HostConnectionSettingsScreen(
                deviceName = deviceName,
                connectionSettings = connectionSettings,
                onDeviceNameUpdate = onDeviceNameChange,
                onConnectionSettingsChange = onConnectionSettingsChange,
                onBackClick = { showSettingsScreen = false },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f),
            )
        }

        if (showTransferredFilesScreen) {
            TransferredFilesScreen(
                onBackClick = { showTransferredFilesScreen = false },
                onFileClick = { file ->
                    val opened = openFileWithExternalApp(file.absolutePath, file.mimeType)
                    if (!opened) {
                        fileOpenToastMessage = "No supported app found"
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(5f),
            )
        }

        fileOpenToastMessage?.let { message ->
            LaunchedEffect(message) {
                delay(3000)
                fileOpenToastMessage = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = AppDimens.dp16, bottom = AppDimens.dp16)
                    .zIndex(6f),
                contentAlignment = AbsoluteAlignment.BottomRight,
            ) {
                BlockedScreenCastingToast(message = message)
            }
        }
    }
}
