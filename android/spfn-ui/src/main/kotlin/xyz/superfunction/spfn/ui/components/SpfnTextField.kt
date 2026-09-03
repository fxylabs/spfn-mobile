// SPFN Mobile — one field, and the whole of what a keyboard does on this platform.
//
// Counterpart of Sources/SPFNUI/Components/SpfnTextField.swift. The prefix is the one
// `SpfnText` carries and for the same reason, which that file's header states.
//
// ---------------------------------------------------------------------------
// The keyboard contract lives here and in Screen, and nowhere else
// ---------------------------------------------------------------------------
//
// Seven clauses, split between two components and no third:
//
//   Screen  — the body gets out of the keyboard's way, and a tap outside the field puts the
//             keyboard away.
//   here    — the KIND decides the keyboard raised, the ime action says what the return key
//             does, `autofocus` decides whether the field takes focus when the screen
//             appears, and typing clears the error under the field.
//
// The last one is `onValueChange` and it is deliberately the VIEW's call rather than the
// model's. A model that cleared its own error inside a text setter would clear it for a
// screen that has since been popped, which is the R9/P24 family: the model exposes the act
// and guards it with the same on-show test every answer passes through, and the view is what
// decides that editing is when to make it.
//
// ---------------------------------------------------------------------------
// A BasicTextField draws nothing, and that was a real defect
// ---------------------------------------------------------------------------
//
// `BasicTextField` has no decoration at all: no border, no background, no label, no
// placeholder. The generated Android views drew exactly that until now — a bare caret on
// white — while the iOS half drew SwiftUI's bordered `TextField`, so the same screen was
// legible on one platform and invisible on the other. `decorationBox` is where that is
// fixed, and it is the reason this component exists rather than a modifier chain at the call
// site.
//
// ---------------------------------------------------------------------------
// `code` is the strict kind and the reason the enum exists
// ---------------------------------------------------------------------------
//
// A user code is machine-issued ASCII. Left as ordinary text a soft keyboard capitalises its
// first letter, offers a correction for what looks like a word, and can substitute for the
// hyphen in `ABCD-1234` — and the request then carries a code the server never issued, with
// no failure anywhere except a refusal the person cannot explain. So `Code` asks for an ASCII
// keyboard, capitalises EVERY character rather than the first, and turns autocorrect off.

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/**
 * A labelled field with a border, a hint, and its refusal underneath it.
 *
 * @param label what the field is, drawn above it.
 * @param id the test tag, `<screen>.<input>`. Not optional: a field a runner cannot find is
 *   a cell nobody can write.
 * @param value the text the screen holds.
 * @param onValueChange every edit. The generated view spends it on writing the text back and
 *   on clearing the error; nothing here assumes that is all it is for.
 * @param hint the placeholder inside the field.
 * @param kind what is expected, which decides the keyboard. See [FieldKind].
 * @param error the refusal to draw under the field, or null.
 * @param enabled false makes it uneditable and dims it.
 * @param submitOnReturn whether the return key performs this screen's action. It decides the
 *   key's LABEL as well as its effect, so the key never says `go` and do nothing.
 * @param autofocus whether the field takes focus when the screen appears.
 * @param onSubmit what the return key does when [submitOnReturn].
 */
@JvmSynthetic
@Composable
public fun SpfnTextField(
    label: String,
    id: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    kind: FieldKind = FieldKind.Text,
    error: String? = null,
    enabled: Boolean = true,
    submitOnReturn: Boolean = false,
    autofocus: Boolean = false,
    onSubmit: () -> Unit = {}
)
{
    val palette = spfnPalette();
    val focus = LocalFocusManager.current;
    val requester = remember { FocusRequester() };
    val shape = RoundedCornerShape(SpfnTokens.radiusSmall);

    // Asked for once per appearance. A screen that is not on show is not composed at all, so
    // there is no popped route for this to steal the keyboard back onto (P24).
    LaunchedEffect(autofocus, enabled)
    {
        if (autofocus && enabled)
        {
            requester.requestFocus();
        }
    }

    Column(modifier = modifier.fillMaxWidth())
    {
        SpfnText(text = label, role = TextRole.Caption, secondary = true);
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Metrics.TOUCH_TARGET)
                .testTag(id)
                .focusRequester(requester),
            enabled = enabled,
            singleLine = true,
            textStyle = styleOf(if (kind == FieldKind.Code) TextRole.Mono else TextRole.Body)
                .copy(color = if (enabled) palette.text else palette.textSecondary),
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType(kind),
                capitalization = capitalization(kind),
                autoCorrectEnabled = kind == FieldKind.Text,
                imeAction = if (submitOnReturn) ImeAction.Go else ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onGo = { onSubmit() },
                onDone = { focus.clearFocus() }
            ),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Metrics.TOUCH_TARGET)
                        .background(color = palette.surface, shape = shape)
                        .border(
                            width = Metrics.BORDER_WIDTH,
                            color = if (error == null) palette.textSecondary else palette.error,
                            shape = shape
                        )
                        .padding(horizontal = SpfnTokens.space3),
                    contentAlignment = Alignment.CenterStart
                )
                {
                    if (value.isEmpty() && hint.isNotEmpty())
                    {
                        SpfnText(text = hint, role = TextRole.Body, secondary = true);
                    }
                    inner();
                }
            }
        );
        if (error != null)
        {
            StatusText(
                kind = StatusKind.Error,
                text = error,
                id = "$id.error",
                modifier = Modifier.padding(top = SpfnTokens.space1)
            );
        }
    }
}

/**
 * An ASCII keyboard for a code, because a machine-issued code has no characters outside ASCII
 * and an emoji keyboard on one is an invitation to send something unsendable.
 */
private fun keyboardType(kind: FieldKind): KeyboardType = when (kind)
{
    FieldKind.Code -> KeyboardType.Ascii
    FieldKind.Text -> KeyboardType.Text
    FieldKind.Email -> KeyboardType.Email
    FieldKind.Number -> KeyboardType.Number
}

/** Every character for a code, none for anything a server matches exactly. */
private fun capitalization(kind: FieldKind): KeyboardCapitalization = when (kind)
{
    FieldKind.Code -> KeyboardCapitalization.Characters
    FieldKind.Text -> KeyboardCapitalization.Sentences
    FieldKind.Email, FieldKind.Number -> KeyboardCapitalization.None
}
