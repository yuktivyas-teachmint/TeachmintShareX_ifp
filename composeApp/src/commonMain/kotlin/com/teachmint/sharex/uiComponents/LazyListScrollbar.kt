package com.teachmint.sharex.uiComponents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.teachmint.sharex.ui.theme.AppDimens
import com.teachmint.sharex.ui.theme.AppSurface
import com.teachmint.sharex.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun LazyListScrollbar(
    listState: LazyListState,
    totalItemCount: Int,
    modifier: Modifier = Modifier,
    itemSpacing: Dp = AppDimens.dp0,
    minThumbHeight: Dp = AppDimens.dp24,
    trackColor: Color = AppSurface,
    thumbColor: Color = TextSecondary,
) {
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    val viewportHeight =
        (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toFloat()
    val density = LocalDensity.current
    val minThumbHeightPx = with(density) { minThumbHeight.toPx() }
    val itemSpacingPx = with(density) { itemSpacing.toPx() }

    Box(modifier = modifier) {
        if (visibleItems.isEmpty() || viewportHeight <= 0f || totalItemCount <= 0) return@Box

        val averageItemHeightPx =
            (visibleItems.sumOf { it.size }.toFloat() / visibleItems.size).coerceAtLeast(1f)
        val contentHeightPx =
            (averageItemHeightPx * totalItemCount) +
                (itemSpacingPx * (totalItemCount - 1).coerceAtLeast(0))

        if (contentHeightPx <= viewportHeight) return@Box

        val itemSlotHeightPx = averageItemHeightPx + itemSpacingPx
        val maxScrollablePx = (contentHeightPx - viewportHeight).coerceAtLeast(1f)
        val currentScrollPx =
            (listState.firstVisibleItemIndex * itemSlotHeightPx) + listState.firstVisibleItemScrollOffset
        val thumbHeightPx =
            (viewportHeight * (viewportHeight / contentHeightPx)).coerceIn(
                minThumbHeightPx,
                viewportHeight,
            )
        val thumbTravelPx = (viewportHeight - thumbHeightPx).coerceAtLeast(0f)
        val thumbOffsetPx = ((currentScrollPx / maxScrollablePx).coerceIn(0f, 1f)) * thumbTravelPx

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(trackColor, RoundedCornerShape(percent = 50)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { thumbHeightPx.toDp() })
                .background(thumbColor, RoundedCornerShape(percent = 50)),
        )
    }
}
