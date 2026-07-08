package com.teachmint.sharex.uiComponents

import com.teachmint.sharex.R
import com.teachmint.sharex.ui.theme.*
import com.teachmint.sharex.ui.theme.AppDimens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable
fun noreceiverfoundComposable(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.no_host_found_icon),
            contentDescription = null,
            tint = Color.Unspecified,
        )
        Spacer(modifier = Modifier.height(AppDimens.dp20))
        Text(
            text = stringResource(R.string.no_receiver_found),
            color = TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(AppDimens.dp8))
        Text(
            text = stringResource(R.string.no_receiver_found_desc),
            color = TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
