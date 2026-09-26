package com.fourj.iptv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * A text input sized and styled for a television.
 *
 * Written on [BasicTextField] rather than a Material text field on purpose: `androidx.tv:tv-material`
 * 1.0.0 ships no `TextField` of its own, and the phone-oriented Material 3 one is laid out for a
 * soft keyboard rather than a remote. This gives explicit control over the size and the focused
 * border, which is the only focus affordance a person across the room can actually see.
 *
 * **Up and down are intercepted to move focus, not to move the text cursor.** A single-line
 * [BasicTextField] consumes D-pad up and down itself, which traps focus inside the field: on a real
 * remote you could never reach the next field or the submit button, and the form became unusable.
 *
 * **Right is left alone by default, since that still feels like "edit my text"** - except on a field
 * that declares [movesFocusRightAtEnd]. Fields laid out side by side need it: with left and right
 * both meaning "edit", a viewer who arrows into the first of two adjacent fields has no way at all
 * to reach the second, and the form can only be completed with a pointer. On such a field, right at
 * the end of the text moves on; right anywhere else still moves the caret, so editing is intact.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    movesFocusRightAtEnd: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    // Held as a TextFieldValue internally so the selection can be read: a String in/out API cannot
    // express one, and the caret position is the only thing that decides whether right means "next
    // character" or "next field".
    var fieldValue by remember(value) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionRight ->
                            if (movesFocusRightAtEnd && fieldValue.selection.end >= fieldValue.text.length) {
                                FocusDirection.Right
                            } else {
                                return@onPreviewKeyEvent false
                            }
                        else -> return@onPreviewKeyEvent false
                    }
                    // Only consume the event if focus actually moved, so a parent scroll
                    // container still gets a chance to handle it at the ends of a list.
                    focusManager.moveFocus(direction)
                },
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = { updated ->
                    fieldValue = updated
                    onValueChange(updated.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                enabled = enabled,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
            )
        }
    }
}

