package com.teachmint.sharex.uiComponents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.teachmint.sharex.ui.theme.AppBackground
import com.teachmint.sharex.ui.theme.AppBorder
import com.teachmint.sharex.ui.theme.AppDimens
import com.teachmint.sharex.ui.theme.AppSurface
import com.teachmint.sharex.ui.theme.PrimaryAccent
import com.teachmint.sharex.ui.theme.TextPrimary
import com.teachmint.sharex.ui.theme.TextSecondary
import org.jetbrains.compose.resources.painterResource
import teachmintsharex.composeapp.generated.resources.Res
import teachmintsharex.composeapp.generated.resources.ic_close
import teachmintsharex.composeapp.generated.resources.reverse_screen_icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReverseScreenConfirmationBottomSheet(
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Are you sure?",
    message: String = "Screen casting will stop if you activate reverse screen mode.",
    confirmButtonText: String = "Yes",
    cancelButtonText: String = "Cancel",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = AppSurface,
        contentColor = TextPrimary,
        scrimColor = AppBackground.copy(alpha = 0.70f),
        shape = RoundedCornerShape(
            topStart = AppDimens.dp12,
            topEnd = AppDimens.dp12,
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = AppDimens.dp525)
                .padding(bottom = AppDimens.dp12),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppDimens.dp24,
                        top = AppDimens.dp24,
                        end = AppDimens.dp16,
                        bottom = AppDimens.dp20,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.size(AppDimens.dp40),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close),
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(AppDimens.dp24),
                    )
                }
            }

            HorizontalDivider(
                thickness = AppDimens.dp1,
                color = AppBorder,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AppDimens.dp24,
                        vertical = AppDimens.dp32,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.reverse_screen_icon),
                    contentDescription = "Reverse screen",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(AppDimens.dp100),
                )

                Spacer(modifier = Modifier.height(AppDimens.dp20))

                Text(
                    text = message,
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(AppDimens.dp20))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.dp12),
                ) {
                    OutlinedButton(
                        onClick = onCancelClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(AppDimens.dp56),
                        shape = RoundedCornerShape(AppDimens.dp12),
                        border = BorderStroke(AppDimens.dp1, AppBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = TextSecondary,
                        ),
                    ) {
                        Text(
                            text = cancelButtonText,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    Button(
                        onClick = onConfirmClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(AppDimens.dp56),
                        shape = RoundedCornerShape(AppDimens.dp12),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryAccent,
                            contentColor = TextPrimary,
                        ),
                    ) {
                        Text(
                            text = confirmButtonText,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
