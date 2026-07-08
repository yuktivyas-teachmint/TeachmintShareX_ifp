package com.teachmint.sharex.uiComponents

import com.teachmint.sharex.R
import com.teachmint.sharex.ui.theme.AppBorder
import com.teachmint.sharex.ui.theme.AppDimens
import com.teachmint.sharex.ui.theme.AppSurface
import com.teachmint.sharex.ui.theme.PrimaryAccent
import com.teachmint.sharex.ui.theme.TextPrimary
import com.teachmint.sharex.ui.theme.TextSecondary
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource

@Composable
fun CompactSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    containerColor: Color = AppSurface,
    textColor: Color = TextPrimary,
    placeholderColor: Color = TextSecondary,
    cursorColor: Color = PrimaryAccent,
    iconTint: Color = TextSecondary,
    borderStroke: BorderStroke? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
) {
    val shape = RoundedCornerShape(AppDimens.dp28)
    val noRippleInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .background(containerColor, shape)
            .then(
                if (borderStroke != null) Modifier.border(borderStroke, shape)
                else Modifier
            )
            .padding(horizontal = AppDimens.dp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search_line),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(AppDimens.dp16),
        )
        Spacer(Modifier.width(AppDimens.dp8))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle.copy(color = textColor),
            cursorBrush = SolidColor(cursorColor),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            color = placeholderColor,
                            style = textStyle,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(AppDimens.dp8))
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Clear search",
                tint = iconTint,
                modifier = Modifier
                    .size(AppDimens.dp16)
                    .clickable(
                        interactionSource = noRippleInteraction,
                        indication = null,
                    ) { onValueChange("") },
            )
        }
    }
}
