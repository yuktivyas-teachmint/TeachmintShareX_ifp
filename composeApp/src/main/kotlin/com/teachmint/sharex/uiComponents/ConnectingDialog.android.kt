package com.teachmint.sharex.uiComponents

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter

@Composable
internal fun ConnectingWirelessAnimation(modifier: Modifier = Modifier) {
    LottieFileAnimation(
        assetPath = "files/wireless_connecting.json",
        modifier = modifier,
    )
}

@Composable
internal fun SmartTvAnimation(modifier: Modifier = Modifier) {
    LottieFileAnimation(
        assetPath = "files/smart_tv.json",
        modifier = modifier,
    )
}

@Composable
private fun LottieFileAnimation(
    assetPath: String,
    modifier: Modifier,
) {
    val context = LocalContext.current.applicationContext
    val composition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            context.assets.open(assetPath).use { it.readBytes() }.decodeToString(),
        )
    }

    Image(
        painter = rememberLottiePainter(
            composition = composition,
            iterations = Compottie.IterateForever,
        ),
        contentDescription = null,
        modifier = modifier,
    )
}
