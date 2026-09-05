// The words a screen with nothing to read shows.
//
// A spec says what a screen IS, and a paragraph of body copy is not that: a screen whose
// spec carried its own prose would be a spec nobody could read the structure out of, and the
// long one below is thirty lines on its own. So the spec names a KEY and the words live here,
// which is the same split `SPFNStrings` makes for the sentence a failed screen shows.
//
// A closed set, checked at generation time (refusal 7's family): a key this table does not
// carry is refused by name rather than emitted as an empty screen. That matters more here
// than for a role or a kind, because the failure mode is quiet — a screen that drew nothing
// looks like a screen somebody had not filled in yet.
//
// One line per paragraph, and no newline inside a value. The emitters write each paragraph
// as its own text component, so nothing here has to be escaped into a multi-line literal on
// two languages with different rules for one.
//
// The words are lorem-family filler on purpose. This table exists so the showcase has
// something to scroll and something to measure a sheet against; prose that said anything
// would have to be maintained as if it did. `lorem.sheet` is the one exception and it
// earns it: a body that has to stay inside a half sheet is a body whose LENGTH is the
// requirement, and a sentence naming that is what stops the next person growing it back.

package xyz.superfunction.spfn.uicodegen

/** The static body a screen names by key, when it has no read to fill it. */
object BodyText
{
    /** The paragraphs behind [key], or null when this table carries no such body. */
    fun paragraphs(key: String): List<String>? = TABLE[key]

    /** Every key this table carries, for a refusal to name what it could have said. */
    fun keys(): List<String> = TABLE.keys.sorted()

    /**
     * Whether a body behind [key] is long enough to put a control below the fold.
     *
     * A property of the TABLE and not of the screen, because it is the words that decide it.
     * The case rules read it to know whether a cell has to scroll before it presses, and a
     * body that grew past the fold would make that cell start scrolling by itself.
     */
    fun scrolls(key: String): Boolean = key in SCROLLING

    /**
     * Short enough to stand inside a sheet that is not full.
     *
     * A half sheet is a viewport with a fold of its own, and the fold is much higher than a
     * screen's. What sits below it is out of the accessibility tree exactly as it is on a
     * full screen (P25), so the control at the foot of a sheet body that overflows is a
     * control no runner can tap — and the runner cannot scroll to it either, because a
     * scroll gesture started at the middle of a half sheet lands on the sheet's grabber and
     * drags the sheet rather than its content.
     *
     * So this is one paragraph and it stays one. It is not a shorter [SHORT] to save room;
     * it is the body of a screen whose presentation has already decided how much there is.
     */
    private val SHEET: List<String> = listOf(
        "This sheet stands at half. What it shows fits without scrolling, so the way out " +
            "is always in reach."
    )

    private val SHORT: List<String> = listOf(
        "This screen reads nothing and writes nothing. It is here so the presentation " +
            "around it can be looked at on its own: the frame, the way out it offers, and " +
            "what the platform does to it when a person swipes.",
        "The control below moves the flow. Nothing on this screen reaches a server."
    )

    /**
     * Long enough that the control under it is off the screen on a phone.
     *
     * That is the whole requirement, and it is why the paragraph count is what it is: the
     * cell that presses that control has to scroll to reach it, and a body that fitted would
     * make the cell pass without exercising anything.
     */
    private val LONG: List<String> = listOf(
        "This screen reads nothing and writes nothing. Its body is long on purpose: the " +
            "control at the foot of it is below the fold on a phone, so reaching it is a " +
            "scroll rather than a tap, and the header above it has to stay where it is " +
            "while that happens.",
        "A header that scrolled away with the body would take the way out of the flow with " +
            "it. That is the thing this screen exists to make visible, and it is a thing " +
            "only a device can hold still — the frame is laid out by the platform, and a " +
            "JVM test of the model would pass whatever the frame did.",
        "The second half of it is the keyboard, which is a different screen's job. Here " +
            "there is no field to focus, so the body scrolls under a header that does not " +
            "and there is nothing else moving to confuse the reading.",
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor " +
            "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis " +
            "nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.",
        "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu " +
            "fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt " +
            "in culpa qui officia deserunt mollit anim id est laborum.",
        "Sed ut perspiciatis unde omnis iste natus error sit voluptatem accusantium " +
            "doloremque laudantium, totam rem aperiam, eaque ipsa quae ab illo inventore " +
            "veritatis et quasi architecto beatae vitae dicta sunt explicabo.",
        "Nemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit, sed " +
            "quia consequuntur magni dolores eos qui ratione voluptatem sequi nesciunt.",
        "Neque porro quisquam est, qui dolorem ipsum quia dolor sit amet, consectetur, " +
            "adipisci velit, sed quia non numquam eius modi tempora incidunt ut labore et " +
            "dolore magnam aliquam quaerat voluptatem."
    )

    private val TABLE: Map<String, List<String>> = mapOf(
        "lorem.sheet" to SHEET,
        "lorem.short" to SHORT,
        "lorem.long" to LONG
    )

    /** The bodies that do not fit on a phone. */
    private val SCROLLING: Set<String> = setOf("lorem.long")
}
