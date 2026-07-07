package com.teachmint.sharex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.teachmint.sharex.share.host.HostHomeScreen
import com.teachmint.sharex.share.subscription.SubscriptionRequiredScreen
import com.teachmint.sharex.share.shared.AppRole
import com.teachmint.sharex.share.shared.rememberAppControllers
import com.teachmint.sharex.ui.theme.AppTheme

@Composable
@Preview
fun App() {
    AppTheme {
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .fillMaxSize(),
        ) {
            val controllers = rememberAppControllers()
            when (controllers.role) {
                AppRole.Host -> HostHomeScreen(controllers.hostController!!)
                AppRole.SubscriptionRequired -> SubscriptionRequiredScreen()
            }
        }
    }
}
