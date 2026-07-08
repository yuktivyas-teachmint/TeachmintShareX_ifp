package com.teachmint.sharex.uiComponents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.teachmint.sharex.R
import com.teachmint.sharex.ui.theme.AppDimens
import com.teachmint.sharex.ui.theme.AppSurface
import com.teachmint.sharex.ui.theme.ConnectingDialogTitleColor
import com.teachmint.sharex.ui.theme.PrimaryAccent
import com.teachmint.sharex.ui.theme.RenameDialogDivider
import com.teachmint.sharex.ui.theme.TextPrimary
import com.teachmint.sharex.ui.theme.TextSecondary

@Composable
fun RemoteControlConsentDialog(
    onConsentGranted: () -> Unit,
    onConsentDenied: () -> Unit,
) {
    Dialog(
        onDismissRequest = onConsentDenied,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Card(
            modifier = Modifier
                .padding(AppDimens.dp16)
                .widthIn(min = AppDimens.dp280, max = AppDimens.dp420)
                .wrapContentHeight(),
            colors = CardDefaults.cardColors(containerColor = AppSurface),
            shape = RoundedCornerShape(AppDimens.dp16),
            elevation = CardDefaults.cardElevation(AppDimens.dp8),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppDimens.dp28, vertical = AppDimens.dp28),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(AppDimens.dp64)
                            .background(PrimaryAccent.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TouchApp,
                            contentDescription = null,
                            tint = PrimaryAccent,
                            modifier = Modifier.size(AppDimens.dp32),
                        )
                    }

                    Spacer(modifier = Modifier.height(AppDimens.dp16))

                    Text(
                        text = stringResource(R.string.enable_remote_control),
                        color = ConnectingDialogTitleColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(AppDimens.dp10))

                    Text(
                        text = stringResource(R.string.remote_control_consent_body1),
                        color = TextPrimary.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(AppDimens.dp8))

                    Text(
                        text = stringResource(R.string.remote_control_consent_body2),
                        color = TextSecondary.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider(color = RenameDialogDivider)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp12),
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.dp8, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onConsentDenied,
                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                    ) {
                        Text(
                            text = stringResource(R.string.disagree),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Button(
                        onClick = onConsentGranted,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryAccent,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(AppDimens.dp8),
                    ) {
                        Text(
                            text = stringResource(R.string.i_agree),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
