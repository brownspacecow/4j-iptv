package com.fourj.iptv.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
 * **Focusing the field does not open the keyboard. Pressing OK on it does.**
 *
 * That is a change of behavior, and worth spelling out because the platform default is the other
 * way round: a focused `BasicTextField` summons the IME by itself, so merely arrowing onto a field
 * threw up a keyboard over half the screen. On a television that is the wrong default, because
 * arrowing is how you *look* at something. Every other control in this app changes what is selected
 * the instant focus lands on it, and none of them hide half the screen when you do.
 *
 * The mechanism is [BasicTextField]'s `readOnly`, not a call to hide the keyboard. A readOnly field
 * still takes focus and still draws a cursor, but the IME never appears; hiding it after the fact
 * would be racing a system that puts it up a frame later. `readOnly` is released on OK and
 * `SoftwareKeyboardController.show()` is called in the same breath, so the keyboard appears on the
 * press that asked for it rather than on the one before.
 *
 * **The rule is the same in every field in the app.** The login screen's three fields behave
 * identically, deliberately: a viewer who learns that OK opens the keyboard learns it once, and a
 * field that behaved differently depending on which screen it was on would be worse than either
 * choice made consistently.
 *
 * **Up and down move focus, not the text cursor.** A single-line [BasicTextField] consumes D-pad
 * up and down itself, which traps focus inside the field: on a real remote you could never reach the
 * next field or the submit button, and the form became unusable.
 *
 * **Right is left alone once the keyboard is up, since that still feels like "edit my text"** -
 * except on a field that declares [movesFocusRightAtEnd]. Fields laid out side by side need it: with
 * left and right both meaning "edit", a viewer who arrows into the first of two adjacent fields has
 * no way at all to reach the second, and the form can only be completed with a pointer. On such a
 * field, right at the end of the text moves on; right anywhere else still moves the caret. Before
 * the keyboard is up it always moves on, because there is no caret to move - the field is readOnly,
 * so the selection it is tracking is stale and cannot be trusted to say where the caret is.
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
    /**
     * Optional handle on the field, so a screen can put the caret in it on arrival.
     *
     * A search box that opens without focus is a search box you have to arrow into first, and on a
     * television that is the difference between being able to type straight away and having to
     * discover that typing does nothing. Note that arrival is not the same as intent: this puts the
     * *focus* here, and the keyboard still waits for OK.
     */
    focusRequester: FocusRequester? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }

    /**
     * Whether the viewer has asked to type.
     *
     * Distinct from [focused] and the whole point of the composable: focused is "the border is lit",
     * armed is "the keyboard is up and the text can be changed".
     */
    var armed by remember { mutableStateOf(false) }
    val editing = focused && armed

    // Held as a TextFieldValue internally so the selection can be read: a String in/out API cannot
    // express one, and the caret position is the only thing that decides whether right means "next
    // character" or "next field".
    var fieldValue by remember(value) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }

    // Leaving the field disarms it. Otherwise arrowing away and back would silently bring the
    // keyboard up again over a screen the viewer had already dismissed it from.
    LaunchedEffect(focused) {
        if (!focused) {
            armed = false
            keyboard?.hide()
        }
    }

    /**
     * Whether the keyboard is actually on screen.
     *
     * Watched rather than assumed, because a visible IME swallows Back itself: the system dismisses
     * the keyboard and the app's own back handler never runs. Relying on that handler alone left the
     * field believing it was still being edited, with no keyboard up and no hint explaining why -
     * which is the state this is here to prevent.
     */
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible

    /**
     * Whether the keyboard has been up since this field was armed.
     *
     * The guard that stops the watcher undoing itself. `SoftwareKeyboardController.show()` is
     * asynchronous, so on the frame OK is pressed the IME is not visible yet; without this flag the
     * watcher would read that as "the keyboard just went away" and disarm instantly.
     */
    var imeWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasUp = true
        } else if (imeWasUp) {
            imeWasUp = false
            armed = false
        }
    }

    // Back closes the keyboard and stays put, the way it does in every television app with a text
    // field. This is a safety net rather than the mechanism: normally the IME has already taken
    // Back by the time it reaches here, and the watcher above has done the work. It matters on the
    // devices and IMEs where the keyboard does not take Back, where without it the viewer would be
    // taken out of the screen part-way through a word.
    BackHandler(enabled = editing) {
        armed = false
        keyboard?.hide()
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            // The affordance, on the label's own line so nothing moves when it appears. A viewer
            // who arrows onto a field and finds that typing does it nothing has no way to work out
            // that OK is what they want.
            if (focused && !editing) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "OK to type",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
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

                    // OK opens the keyboard. Consumed only when it actually opened one, so that
                    // once the keyboard is up the same key reaches the IME and can move on to the
                    // next field or submit.
                    // Keyed on whether the keyboard is on screen rather than on `editing`, so that a
                    // field left believing it is armed with no keyboard - the state the IME watcher
                    // above exists to clean up - still responds to OK instead of swallowing the
                    // press. When the keyboard *is* up the key falls through to it, so ImeAction
                    // Next and Done still work.
                    val isOk = event.key == Key.Enter ||
                        event.key == Key.NumPadEnter ||
                        event.key == Key.DirectionCenter
                    if (isOk && !imeVisible) {
                        armed = true
                        keyboard?.show()
                        return@onPreviewKeyEvent true
                    }

                    // Before the keyboard is up the tracked selection is stale - readOnly means
                    // onValueChange never fires - so "is the caret at the end" is unanswerable and
                    // right always means "next control".
                    val atEnd = !editing || fieldValue.selection.end >= fieldValue.text.length
                    val direction = when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionRight ->
                            if (movesFocusRightAtEnd && atEnd) FocusDirection.Right else {
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
                    .then(
                        focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                    )
                    .onFocusChanged { focused = it.isFocused },
                enabled = enabled,
                readOnly = !editing,
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
