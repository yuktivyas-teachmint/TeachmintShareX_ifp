package com.teachmint.sharex.uiComponents

import com.teachmint.sharex.ui.theme.*
import com.teachmint.sharex.ui.theme.AppDimens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teachmint.sharex.share.shared.ClientDeviceType
import com.teachmint.sharex.share.shared.PendingShareRequest
import org.jetbrains.compose.resources.painterResource
import teachmintsharex.composeapp.generated.resources.Res
import teachmintsharex.composeapp.generated.resources.check_icon
import teachmintsharex.composeapp.generated.resources.ic_close
import teachmintsharex.composeapp.generated.resources.laptop_icon
import teachmintsharex.composeapp.generated.resources.mobile_icon

private const val MAX_VISIBLE_REQUESTS = 3
private val REQUEST_ROW_HEIGHT = AppDimens.dp70
private val REQUEST_DIVIDER_SPACE = AppDimens.dp8
private val REQUEST_DIVIDER_THICKNESS = AppDimens.dp1
private val MAX_REQUEST_LIST_HEIGHT =
    (REQUEST_ROW_HEIGHT * MAX_VISIBLE_REQUESTS) +
            ((REQUEST_DIVIDER_SPACE + REQUEST_DIVIDER_THICKNESS) * (MAX_VISIBLE_REQUESTS - 1))

@Composable
fun ScreenShareRequestPopup(
    requests: List<PendingShareRequest>,
    onAcceptRequest: (String) -> Unit,
    onRejectRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (requests.isEmpty()) return
    val visibleRequestCount = requests.size.coerceAtMost(MAX_VISIBLE_REQUESTS)
    val requestListHeight =
        (REQUEST_ROW_HEIGHT * visibleRequestCount) +
                ((REQUEST_DIVIDER_SPACE + REQUEST_DIVIDER_THICKNESS) * (visibleRequestCount - 1))
    val showScrollableList = requests.size > MAX_VISIBLE_REQUESTS
    val listState = rememberLazyListState()

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val popupWidth = if (maxWidth >= AppDimens.dp280) {
            maxWidth.coerceAtMost(AppDimens.dp525)
        } else {
            maxWidth
        }

        Card(
            modifier = Modifier
                .width(popupWidth)
                .wrapContentHeight(),
            shape = RoundedCornerShape(AppDimens.dp12),
            colors = CardDefaults.cardColors(containerColor = AppSurface),
            border = BorderStroke(AppDimens.dp1, AppBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.dp24, vertical = AppDimens.dp20),
                verticalArrangement = Arrangement.spacedBy(AppDimens.dp8),
            ) {
                Text(
                    text = "Device Requests",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )

                if (showScrollableList) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MAX_REQUEST_LIST_HEIGHT),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = AppDimens.dp12),
                        ) {
                            itemsIndexed(
                                requests,
                                key = { _, request -> request.clientId }) { index, request ->
                                if (index > 0) {
                                    RequestDivider()
                                }
                                ScreenShareRequestRow(
                                    request = request,
                                    onAcceptRequest = onAcceptRequest,
                                    onRejectRequest = onRejectRequest,
                                )
                            }
                        }

                        LazyListScrollbar(
                            listState = listState,
                            totalItemCount = requests.size,
                            itemSpacing = REQUEST_DIVIDER_SPACE + REQUEST_DIVIDER_THICKNESS,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(AppDimens.dp4)
                                .height(MAX_REQUEST_LIST_HEIGHT),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(requestListHeight),
                    ) {
                        requests.forEachIndexed { index, request ->
                            if (index > 0) {
                                RequestDivider()
                            }
                            ScreenShareRequestRow(
                                request = request,
                                onAcceptRequest = onAcceptRequest,
                                onRejectRequest = onRejectRequest,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestDivider() {
    Spacer(modifier = Modifier.height(REQUEST_DIVIDER_SPACE))
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(REQUEST_DIVIDER_THICKNESS)
            .background(AppSurface),
    )
}

@Composable
private fun ScreenShareRequestRow(
    request: PendingShareRequest,
    onAcceptRequest: (String) -> Unit,
    onRejectRequest: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(REQUEST_ROW_HEIGHT),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = AppDimens.dp12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isMobile = request.deviceType == ClientDeviceType.Mobile
            val iconBackground = if (isMobile) AppBorder else AppSurface


            Icon(
                painter = painterResource(
                    if (isMobile) Res.drawable.mobile_icon else Res.drawable.laptop_icon,
                ),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(AppDimens.dp50),
            )


            Spacer(modifier = Modifier.width(AppDimens.dp16))

            Column {
                Text(
                    text = request.clientName,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppDimens.dp2))
                Text(
                    text = "Requesting",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(AppDimens.dp12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onRejectRequest(request.clientId) },
                modifier = Modifier.size(AppDimens.dp40),
                shape = RoundedCornerShape(AppDimens.dp8),
                border = BorderStroke(AppDimens.dp1, TextSecondary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                contentPadding = PaddingValues(AppDimens.dp0),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_close),
                    contentDescription = "Reject request",
                    tint = TextSecondary,
                    modifier = Modifier.size(AppDimens.dp20),
                )
            }

            Button(
                onClick = { onAcceptRequest(request.clientId) },
                modifier = Modifier.size(AppDimens.dp40),
                shape = RoundedCornerShape(AppDimens.dp8),
                colors = ButtonDefaults.buttonColors(containerColor = SuccessColor),
                contentPadding = PaddingValues(AppDimens.dp0),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.check_icon),
                    contentDescription = "Accept request",
                    tint = TextPrimary,
                    modifier = Modifier.size(AppDimens.dp20),
                )
            }
        }
    }
}
