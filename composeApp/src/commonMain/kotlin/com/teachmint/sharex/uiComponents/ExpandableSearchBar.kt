package com.teachmint.sharex.uiComponents

import com.teachmint.sharex.ui.theme.*
import com.teachmint.sharex.ui.theme.AppDimens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import teachmintsharex.composeapp.generated.resources.Res
import teachmintsharex.composeapp.generated.resources.ic_close
import teachmintsharex.composeapp.generated.resources.ic_search_line
import teachmintsharex.composeapp.generated.resources.search_icon

@Composable
fun ExpandableSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search device name",
    cancelText: String = "Cancel",
    clearOnCancel: Boolean = true
) {
    val noRippleInteraction = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 220)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        if (isExpanded) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(AppDimens.dp48)
                    .background(AppSurface, RoundedCornerShape(AppDimens.dp12))
                    .padding(horizontal = AppDimens.dp12),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_search_line),
                    contentDescription = "Search",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(AppDimens.dp20)
                )
                Spacer(modifier = Modifier.width(AppDimens.dp8))

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    cursorBrush = SolidColor(TextPrimary),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                )

                if (query.isNotEmpty()) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close),
                        contentDescription = "Clear search",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(AppDimens.dp18)
                            .clickable(
                                interactionSource = noRippleInteraction,
                                indication = null
                            ) { onQueryChange("") }
                    )
                }
            }

            Spacer(modifier = Modifier.width(AppDimens.dp12))

            Text(
                text = cancelText,
                color = PrimaryAccent,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(
                    interactionSource = noRippleInteraction,
                    indication = null
                ) {
                    if (clearOnCancel) {
                        onQueryChange("")
                    }
                    onExpandedChange(false)
                }
            )
        } else {
            Icon(
                painter = painterResource(Res.drawable.search_icon),
                contentDescription = "Open search",
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(AppDimens.dp32)
                    .clickable(
                        interactionSource = noRippleInteraction,
                        indication = null
                    ) {
                        onExpandedChange(true)
                    }
            )
        }
    }
}
