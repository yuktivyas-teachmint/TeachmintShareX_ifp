package com.teachmint.sharex.uiComponents

import com.teachmint.sharex.ui.theme.*
import com.teachmint.sharex.ui.theme.AppDimens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import teachmintsharex.composeapp.generated.resources.Res
import teachmintsharex.composeapp.generated.resources.no_network_subtitle
import teachmintsharex.composeapp.generated.resources.no_network_title
import teachmintsharex.composeapp.generated.resources.satellite_icon_no_padding

@Composable
fun NoNetworkComposable(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    iconSize: Dp = if (compact) AppDimens.dp50 else AppDimens.dp70,
    titleColor: Color = TextPrimary,
    subtitleColor: Color = TextSecondary,
) {
    val title = stringResource(Res.string.no_network_title)
    val subtitle = stringResource(Res.string.no_network_subtitle)

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            modifier = Modifier.size(iconSize),
            painter = painterResource(Res.drawable.satellite_icon_no_padding),
            contentDescription = null,
        )
        Spacer(modifier = Modifier.height(AppDimens.dp26))
        Text(
            text = title,
            color = titleColor,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AppDimens.dp14))
        Text(
            text = subtitle,
            color = subtitleColor,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
