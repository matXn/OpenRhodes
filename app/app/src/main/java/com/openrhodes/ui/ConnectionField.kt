package com.openrhodes.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation

@Composable
internal fun ConnectionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    TextField(
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = GroupColor, unfocusedContainerColor = GroupColor,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        value = value, onValueChange = onValueChange, modifier = modifier, label = label, enabled = enabled,
        singleLine = singleLine, keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation, trailingIcon = trailingIcon,
    )
}
