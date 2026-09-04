// SPFN Mobile — a line that says how something went.
//
// Counterpart of Sources/SPFNUI/Components/StatusText.swift.
//
// What it may say is the whole point of it existing as a component rather than as a
// `SpfnText` with a colour. A screen's failure is classified into a key and the key is looked
// up in `SpfnStrings`; the server's own `message` never reaches here, because a server can
// put anything in that field including something it echoed back from the request
// (decision C7, and spfn-core's own header on `SpfnErrorEnvelope`).
//
// The component cannot enforce that on its own — it takes a `String` like anything else —
// but it is the one place the rule is written next to the drawing, and section 14 of
// tools/validate/validate.sh is what refuses a generated view that reaches for `.message`.

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/**
 * One line of status: a refusal, or something worth knowing.
 *
 * @param kind error or info, which decides the colour and nothing else.
 * @param text a sentence from `SpfnStrings`, never one a server sent.
 * @param id a test tag, when a runner has to read this line. Empty draws none.
 */
@JvmSynthetic
@Composable
public fun StatusText(kind: StatusKind, text: String, id: String = "", modifier: Modifier = Modifier)
{
    val palette = spfnPalette();
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = (if (id.isEmpty()) modifier else modifier.testTag(id)).fillMaxWidth(),
        style = SpfnTokens.caption.copy(
            color = if (kind == StatusKind.Error) palette.error else palette.textSecondary
        )
    );
}
