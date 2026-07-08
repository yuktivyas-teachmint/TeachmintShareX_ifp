package com.teachmint.sharex.uiComponents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.teachmint.sharex.ui.theme.AppDimens
import com.teachmint.sharex.ui.theme.AppSurface
import com.teachmint.sharex.ui.theme.ConnectingDialogTitleColor
import com.teachmint.sharex.ui.theme.ErrorColor

@Composable
fun ConnectingDialog(
    visible: Boolean,
    onStopConnecting: () -> Unit,
    onDismissRequest: () -> Unit = {},
    title: String = "Connecting....",
    stopText: String = "Stop Connecting",
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .padding(AppDimens.dp26)
                    .widthIn(min = AppDimens.dp220, max = AppDimens.dp360)
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = AppSurface),
                shape = RoundedCornerShape(AppDimens.dp4),
                elevation = CardDefaults.cardElevation(AppDimens.dp1)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppDimens.dp220)
                        .padding(horizontal = AppDimens.dp24, vertical = AppDimens.dp20),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ConnectingWirelessAnimation(
                            modifier = Modifier.size(AppDimens.dp88),
                        )
                        Spacer(modifier = Modifier.height(AppDimens.dp12))
                        Text(
                            text = title,
                            color = ConnectingDialogTitleColor,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(AppDimens.dp12))
                        TextButton(
                            onClick = onStopConnecting,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = ErrorColor,
                            ),
                        ) {
                            Text(
                                text = stopText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

