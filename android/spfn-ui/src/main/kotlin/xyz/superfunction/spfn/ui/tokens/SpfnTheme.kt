// SPFN Mobile — the look an app gives the components, as one value it can inject.
//
// Counterpart of Sources/SPFNUI/Tokens/SPFNTheme.swift. The two files carry the SAME KEYS,
// type by type, and section 15 of tools/validate/validate.sh compares them: a theme key one
// platform has and the other does not is an app that can be themed on one platform only.
//
// [SpfnTokens] is where the DEFAULT comes from and nothing else. Every component reads
// [LocalSpfnTheme], and a composition nobody themed reads [SpfnTheme.Default], which is the
// tokens re-spelled — so an app that injects nothing draws exactly what it drew before the
// theme existed. Section 15 also refuses a component that reads [SpfnTokens] directly,
// because that component is one an app cannot theme.
//
// What is NOT here is as deliberate as what is. The touch minimum, the header height and the
// icon size are the platform's (`components/Metrics.kt`); a sheet's detents are geometry the
// flow decides (`SheetGeometry`); the transitions are one set every navigator is handed
// (`FlowTransitions`). None of those is a look, and a theme that could shrink a button under
// 48dp would be a theme that could bring back P21.
//
// Neither is the status bar's foreground nor a modal cover's fill. Both belong to the host
// app's WINDOW theme — `android:windowLightStatusBar` and `android:colorBackground` — which
// the example app's `themes.xml` says why it declares rather than sets in code; an app that
// injects a dark background declares the window to match.

package xyz.superfunction.spfn.ui.tokens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.superfunction.spfn.ui.components.ControlRole
import xyz.superfunction.spfn.ui.components.Metrics
import xyz.superfunction.spfn.ui.components.TextRole

/**
 * The four type roles a `SpfnText` can be set in.
 *
 * A [TextStyle] each, which is a font and, where a theme states one, its line height.
 */
public data class SpfnTypography(
    /** A screen's title. */
    public val title: TextStyle,

    /** Everything a person reads. */
    public val body: TextStyle,

    /** A hint, a caption, an error line. */
    public val caption: TextStyle,

    /** Anything whose characters have to line up: a code, a readout. */
    public val mono: TextStyle
)
{
    /** The style a role is set in. Written once so no component picks a font of its own. */
    internal fun styleOf(role: TextRole): TextStyle = when (role)
    {
        TextRole.Title -> title
        TextRole.Body -> body
        TextRole.Caption -> caption
        TextRole.Mono -> mono
    };
}

/** The six gaps a layout is built out of, smallest first. */
public data class SpfnSpacing(
    /** The tightest gap: between a label and the thing it labels. */
    public val space1: Dp,

    /** Between two lines of one idea. */
    public val space2: Dp,

    /** Between a field and its error. */
    public val space3: Dp,

    /** The standard gutter, and the gap between two controls. */
    public val space4: Dp,

    /** Between two groups on one screen. */
    public val space5: Dp,

    /** Between a screen's header and what it introduces. */
    public val space6: Dp
)

/** The two corner radii. */
public data class SpfnRadius(
    /** A field, a button — anything a finger lands on. */
    public val small: Dp,

    /** A sheet, a card — anything a screen sits inside. */
    public val large: Dp
)

/**
 * What one kind of button is drawn in, in one appearance.
 *
 * [disabledBorder] is one more than the container and the content: an outlined control that
 * greys out greys its outline too, and a theme that could not say what to would leave the
 * outline at full strength around a label that had gone quiet.
 */
public data class SpfnButtonColors(
    /** What the control stands on. */
    public val container: Color,

    /** The label and the spinner. */
    public val content: Color,

    /** The outline, drawn only when the appearance's `borderWidth` is above zero. */
    public val border: Color,

    /** What the control stands on while a finger is down on it. */
    public val pressedContainer: Color,

    /** What a disabled or busy control stands on. */
    public val disabledContainer: Color,

    /** A disabled or busy control's label and spinner. */
    public val disabledContent: Color,

    /** A disabled or busy control's outline. */
    public val disabledBorder: Color
)
{
    /** The fill for a control in this state. */
    internal fun fill(live: Boolean, pressed: Boolean): Color = when
    {
        !live -> disabledContainer
        pressed -> pressedContainer
        else -> container
    };

    /** The label's colour for a control in this state. */
    internal fun label(live: Boolean): Color = if (live) content else disabledContent;

    /** The outline's colour for a control in this state. */
    internal fun outline(live: Boolean): Color = if (live) border else disabledBorder;
}

/**
 * One kind of button: its colours per appearance and the shape they fill.
 *
 * The minimum height is not here and cannot be: it is the platform's touch target
 * (`Metrics.TOUCH_TARGET`, P21), and a control a theme could make smaller than a finger is a
 * control a device runner taps the neighbour of.
 */
public data class SpfnButtonAppearance(
    /** The colours in a light appearance. */
    public val light: SpfnButtonColors,

    /** The colours in a dark appearance. */
    public val dark: SpfnButtonColors,

    /** How thick the outline is drawn. Zero draws none. */
    public val borderWidth: Dp,

    /** The corner radius of the fill and the outline. */
    public val cornerRadius: Dp
)
{
    /** The colours for a dark appearance when [dark] is true, a light one otherwise. */
    public fun colors(dark: Boolean): SpfnButtonColors = if (dark) this.dark else light;
}

/** One appearance per button kind a screen spec can declare. */
public data class SpfnButtons(
    /** `PrimaryButton`. */
    public val primary: SpfnButtonAppearance,

    /** `SecondaryButton`. */
    public val secondary: SpfnButtonAppearance,

    /** `DestructiveButton`. */
    public val destructive: SpfnButtonAppearance,

    /** `TextButton`. */
    public val text: SpfnButtonAppearance
)
{
    /** The appearance a control of [role] is drawn in. */
    internal fun appearanceFor(role: ControlRole): SpfnButtonAppearance = when (role)
    {
        ControlRole.Primary -> primary
        ControlRole.Secondary -> secondary
        ControlRole.Destructive -> destructive
        ControlRole.Text -> text
    };
}

/** Everything the components draw with, as one value an app injects with [SpfnTheme]. */
public data class SpfnTheme(
    /** The palette a light appearance reads. */
    public val light: SpfnPalette,

    /** The palette a dark appearance reads. */
    public val dark: SpfnPalette,

    /** The four type roles. */
    public val typography: SpfnTypography,

    /** The six gaps. */
    public val spacing: SpfnSpacing,

    /** The two corner radii. */
    public val radius: SpfnRadius,

    /** One appearance per button kind. */
    public val buttons: SpfnButtons
)
{
    /**
     * The palette for a dark appearance when [dark] is true, a light one otherwise.
     *
     * Not a key: it is HOW a palette is chosen, and the two platforms choose by different
     * mechanisms — `isSystemInDarkTheme` here, the `colorScheme` environment value there.
     */
    public fun palette(dark: Boolean): SpfnPalette = if (dark) this.dark else light;

    public companion object
    {
        /** What every component draws with when nobody injected a theme: [SpfnTokens], key for key. */
        public val Default: SpfnTheme = SpfnTheme(
            light = SpfnTokens.light,
            dark = SpfnTokens.dark,
            typography = SpfnTypography(
                title = SpfnTokens.title,
                body = SpfnTokens.body,
                caption = SpfnTokens.caption,
                mono = SpfnTokens.mono
            ),
            spacing = SpfnSpacing(
                space1 = SpfnTokens.space1,
                space2 = SpfnTokens.space2,
                space3 = SpfnTokens.space3,
                space4 = SpfnTokens.space4,
                space5 = SpfnTokens.space5,
                space6 = SpfnTokens.space6
            ),
            radius = SpfnRadius(small = SpfnTokens.radiusSmall, large = SpfnTokens.radiusLarge),
            buttons = DefaultButtons.buttons
        );
    }
}

/**
 * The four button appearances the components drew before there was a theme, derived from the
 * default palettes so that the two cannot drift.
 */
private object DefaultButtons
{
    val buttons: SpfnButtons = SpfnButtons(
        primary = filled(SpfnTokens.light.accent, SpfnTokens.dark.accent),
        secondary = outlined(),
        destructive = filled(SpfnTokens.light.error, SpfnTokens.dark.error),
        text = bare()
    );

    /** A solid fill in [light]/[dark], with the background's colour on it. */
    private fun filled(light: Color, dark: Color): SpfnButtonAppearance = SpfnButtonAppearance(
        light = filledColors(light, SpfnTokens.light),
        dark = filledColors(dark, SpfnTokens.dark),
        borderWidth = 0.dp,
        cornerRadius = SpfnTokens.radiusSmall
    );

    private fun filledColors(fill: Color, palette: SpfnPalette): SpfnButtonColors = SpfnButtonColors(
        container = fill,
        content = palette.background,
        border = Color.Transparent,
        pressedContainer = fill,
        disabledContainer = palette.surface,
        disabledContent = palette.textSecondary,
        disabledBorder = Color.Transparent
    );

    /** The raised surface, outlined in the text colour. */
    private fun outlined(): SpfnButtonAppearance = SpfnButtonAppearance(
        light = outlinedColors(SpfnTokens.light),
        dark = outlinedColors(SpfnTokens.dark),
        borderWidth = Metrics.BORDER_WIDTH,
        cornerRadius = SpfnTokens.radiusSmall
    );

    private fun outlinedColors(palette: SpfnPalette): SpfnButtonColors = SpfnButtonColors(
        container = palette.surface,
        content = palette.text,
        border = palette.text,
        pressedContainer = palette.surface,
        disabledContainer = palette.surface,
        disabledContent = palette.textSecondary,
        disabledBorder = palette.textSecondary
    );

    /** No fill and no outline: the accent colour's words. */
    private fun bare(): SpfnButtonAppearance = SpfnButtonAppearance(
        light = bareColors(SpfnTokens.light),
        dark = bareColors(SpfnTokens.dark),
        borderWidth = 0.dp,
        cornerRadius = SpfnTokens.radiusSmall
    );

    private fun bareColors(palette: SpfnPalette): SpfnButtonColors = SpfnButtonColors(
        container = Color.Transparent,
        content = palette.accent,
        border = Color.Transparent,
        pressedContainer = Color.Transparent,
        disabledContainer = Color.Transparent,
        disabledContent = palette.textSecondary,
        disabledBorder = Color.Transparent
    );
}

/**
 * The theme the components under this point draw with.
 *
 * Static rather than dynamic: a theme is set once near the root and changes, if ever, with
 * the whole app, so a change that recomposes everything under it is the honest cost and
 * nothing pays per-read tracking for the rest of the time.
 */
public val LocalSpfnTheme: ProvidableCompositionLocal<SpfnTheme> = staticCompositionLocalOf { SpfnTheme.Default };

/**
 * Draws every SPFN component inside [content] with [theme].
 *
 * A wrapper rather than a parameter on `FlowHost` or `NavigationHost`: a theme is not
 * navigation, a screen composed outside any host draws with it just the same, and the nearest
 * wrapper wins — a `SpfnTheme` inside another one themes its own subtree and leaves the outer
 * theme everywhere else.
 *
 * `@JvmSynthetic` for the reason `Screen` carries it (docs/IMPLEMENTATION-PITFALLS.md P15).
 */
@JvmSynthetic
@Composable
public fun SpfnTheme(theme: SpfnTheme, content: @Composable () -> Unit)
{
    CompositionLocalProvider(LocalSpfnTheme provides theme, content = content);
}

/**
 * The palette of the theme in scope, for the appearance in scope.
 *
 * It stands beside what it selects rather than in `components/Metrics.kt`: `Metrics` is the
 * four sizes the theme does NOT hold. The Swift twin is `SPFNTheme.palette(for:)` over the
 * `colorScheme` environment value.
 */
@Composable
@ReadOnlyComposable
internal fun spfnPalette(): SpfnPalette = LocalSpfnTheme.current.palette(isSystemInDarkTheme());
