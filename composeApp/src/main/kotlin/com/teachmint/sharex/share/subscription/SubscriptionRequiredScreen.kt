package com.teachmint.sharex.share.subscription

import com.teachmint.sharex.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.teachmint.sharex.ui.theme.AppBackground
import com.teachmint.sharex.ui.theme.TextSecondary
import com.teachmint.sharex.ui.theme.TextPrimary
import com.teachmint.sharex.uiComponents.SmartTvAnimation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable
fun SubscriptionRequiredScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBackground,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0C1830),
                            AppBackground,
                        ),
                        radius = 1200f,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            val isCompact = maxWidth < 700.dp || maxHeight < 620.dp
            val containerHorizontalPadding = if (isCompact) 16.dp else 28.dp
            val contentHorizontalPadding = if (isCompact) 18.dp else 30.dp
            val contentVerticalPadding = if (isCompact) 18.dp else 28.dp
            val animationSize = if (isCompact) 168.dp else 220.dp
            val headingStyle = if (isCompact) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.headlineMedium
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = containerHorizontalPadding, vertical = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF04070F),
                                Color(0xFF020A1D),
                                Color(0xFF04070F),
                            ),
                        ),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (isCompact) 460.dp else 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = contentHorizontalPadding,
                            vertical = contentVerticalPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.sharex_app_icon),
                            contentDescription = "Share X app icon",
                            modifier = Modifier.size(36.dp),
                        )
                        Image(
                            painter = painterResource(R.drawable.share_x_text_icon),
                            contentDescription = "Share X",
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .widthIn(max = 126.dp)
                                .height(26.dp),
                        )
                    }

                    if (!isCompact) {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(18.dp))
                    }

                    SmartTvAnimation(
                        modifier = Modifier.size(animationSize),
                    )

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = stringResource(R.string.app_not_compatible),
                        style = headingStyle,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = TextPrimary,
                        lineHeight = if (isCompact) MaterialTheme.typography.headlineSmall.lineHeight
                        else MaterialTheme.typography.headlineMedium.lineHeight,
                        modifier = Modifier.widthIn(max = if (isCompact) 620.dp else 760.dp),
                    )

                    Text(
                        text = stringResource(R.string.teachmint_owned_only),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = TextSecondary,
                        modifier = Modifier.widthIn(max = if (isCompact) 560.dp else 680.dp),
                    )
                }
            }
        }
    }
}
