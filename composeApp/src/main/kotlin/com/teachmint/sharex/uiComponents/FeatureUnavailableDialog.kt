package com.teachmint.sharex.uiComponents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.teachmint.sharex.R
import com.teachmint.sharex.ui.theme.AppDimens
import com.teachmint.sharex.ui.theme.AppSurface
import com.teachmint.sharex.ui.theme.ConnectingDialogTitleColor
import com.teachmint.sharex.ui.theme.PrimaryAccent

@Composable
fun FeatureUnavailableDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    message: String = "This functionality is temporarily unavailable. Please try again later.",
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Card(
            modifier = Modifier
                .padding(AppDimens.dp26)
                .widthIn(min = AppDimens.dp220, max = AppDimens.dp360)
                .wrapContentHeight(),
            colors = CardDefaults.cardColors(containerColor = AppSurface),
            shape = RoundedCornerShape(AppDimens.dp12),
            elevation = CardDefaults.cardElevation(AppDimens.dp1),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.dp24, vertical = AppDimens.dp20),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.unavailable),
                    color = ConnectingDialogTitleColor,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(AppDimens.dp12))
                Text(
                    text = message,
                    color = ConnectingDialogTitleColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(AppDimens.dp16))
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = PrimaryAccent,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.ok),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
