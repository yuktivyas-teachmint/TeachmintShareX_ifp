package com.teachmint.sharex.uiComponents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teachmint.sharex.ui.theme.AppDimens
import com.teachmint.sharex.ui.theme.BlockedCastingToastCardColor
import com.teachmint.sharex.ui.theme.BlockedCastingToastTextColor

@Composable
fun BlockedScreenCastingToast(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(max = 620.dp),
        colors = CardDefaults.cardColors(containerColor = BlockedCastingToastCardColor),
        shape = RoundedCornerShape(AppDimens.dp20),
        border = BorderStroke(AppDimens.dp1, BlockedCastingToastCardColor),
    ) {
        Text(
            text = message,
            color = BlockedCastingToastTextColor,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .widthIn(min = 240.dp)
                .padding(horizontal = AppDimens.dp22, vertical = AppDimens.dp16),
        )
    }
}
