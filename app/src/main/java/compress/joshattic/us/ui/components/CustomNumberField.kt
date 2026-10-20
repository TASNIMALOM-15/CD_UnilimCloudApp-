package compress.joshattic.us.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * A free-form whole-number input. Lets the user type ANY value instead of
 * being limited to the preset chips.
 *
 * - Empty field  -> reports 0 (which the app treats as "Original").
 * - Value above [maxValue] is ignored (not accepted while typing).
 * - The field text re-syncs whenever [value] changes from outside (e.g. a chip tap).
 * - [onCommit] is called with the typed value when the user presses Done or leaves the
 *   field (used to turn a typed value into a permanent button).
 */
@Composable
fun CustomNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    suffix: String,
    maxValue: Int,
    modifier: Modifier = Modifier,
    emptyPlaceholder: String? = null,
    onCommit: (Int) -> Unit = {}
) {
    var text by remember(value) { mutableStateOf(if (value > 0) value.toString() else "") }
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.trimStart('0').take(6)
            if (digits.isEmpty()) {
                text = ""
                onValueChange(0)
            } else {
                val parsed = digits.toIntOrNull() ?: return@OutlinedTextField
                if (parsed <= maxValue) {
                    text = digits
                    onValueChange(parsed)
                }
            }
        },
        label = { Text(label) },
        placeholder = if (emptyPlaceholder != null) { { Text(emptyPlaceholder) } } else null,
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (hadFocus && !focusState.isFocused) {
                    val typed = text.toIntOrNull()
                    if (typed != null && typed > 0) onCommit(typed)
                }
                hadFocus = focusState.isFocused
            }
    )
}
