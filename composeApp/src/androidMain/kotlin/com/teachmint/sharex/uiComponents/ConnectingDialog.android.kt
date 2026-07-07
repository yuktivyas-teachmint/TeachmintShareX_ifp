package com.teachmint.sharex.uiComponents

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import org.jetbrains.compose.resources.ExperimentalResourceApi
import teachmintsharex.composeapp.generated.resources.Res

@OptIn(ExperimentalResourceApi::class)
@Composable
internal actual fun ConnectingWirelessAnimation(modifier: Modifier) {
    LottieFileAnimation(
        filePath = "files/wireless_connecting.json",
        modifier = modifier,
    )
}

@OptIn(ExperimentalResourceApi::class)
@Composable
internal actual fun SmartTvAnimation(modifier: Modifier) {
    LottieFileAnimation(
        filePath = "files/smart_tv.json",
        modifier = modifier,
    )
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun LottieFileAnimation(
    filePath: String,
    modifier: Modifier,
) {
    val composition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes(filePath).decodeToString(),
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

