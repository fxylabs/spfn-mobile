// SPFN Mobile — what a theme resolves to, injected and not.
//
// Counterpart of Tests/SPFNUITests/ThemeTests.swift, case for case (T1–T4 of the theming
// brief; T5 is the validator's, in tools/validate/probe-ui-vocabulary-rules.sh).
//
// This repository has no Compose UI test infrastructure, so what a component DRAWS is not
// observable here. What is observable is every lookup a component makes on its way to
// drawing — `appearanceFor`, `colors`, `fill`, `label`, `outline`, `styleOf`, `palette` —
// which are the functions `RoleButton`, `SpfnText` and the rest call and nothing else. The
// expectations for the default are written out from what the components drew BEFORE the
// theme existed (the old `foreground`/`background` in `components/Buttons.kt`), not read off
// `SpfnTheme.Default`, which would agree with itself by construction (P10).
//
// T4 runs a real composition over Compose's own runtime with an applier that builds nothing:
// nesting is the one claim that is about composition rather than about a value.

package xyz.superfunction.spfn.ui.tokens

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import xyz.superfunction.spfn.ui.components.ControlRole
import xyz.superfunction.spfn.ui.components.TextRole

class SpfnThemeTest
{
    private val default = SpfnTheme.Default;
    private val light = SpfnTokens.light;

    // --- T1: no injection draws what the constants drew ---------------------------------

    @Test
    fun `T1 the default palettes are the token palettes`()
    {
        assertEquals(SpfnTokens.light, default.palette(dark = false));
        assertEquals(SpfnTokens.dark, default.palette(dark = true));
    }

    @Test
    fun `T1 the default type roles are the token fonts`()
    {
        assertEquals(SpfnTokens.title, default.typography.styleOf(TextRole.Title));
        assertEquals(SpfnTokens.body, default.typography.styleOf(TextRole.Body));
        assertEquals(SpfnTokens.caption, default.typography.styleOf(TextRole.Caption));
        assertEquals(SpfnTokens.mono, default.typography.styleOf(TextRole.Mono));
    }

    @Test
    fun `T1 the default spacing and radii are the token sizes`()
    {
        assertEquals(
            listOf(4.dp, 8.dp, 12.dp, 16.dp, 24.dp, 32.dp),
            with(default.spacing) { listOf(space1, space2, space3, space4, space5, space6) }
        );
        assertEquals(SpfnTokens.radiusSmall, default.radius.small);
        assertEquals(SpfnTokens.radiusLarge, default.radius.large);
    }

    @Test
    fun `T1 a default primary is the accent under the background colour, and greys out onto the surface`()
    {
        val colors = default.buttons.appearanceFor(ControlRole.Primary).colors(dark = false);
        assertEquals(light.accent, colors.fill(live = true, pressed = false));
        assertEquals(light.accent, colors.fill(live = true, pressed = true));
        assertEquals(light.background, colors.label(live = true));
        assertEquals(light.surface, colors.fill(live = false, pressed = false));
        assertEquals(light.textSecondary, colors.label(live = false));
    }

    @Test
    fun `T1 a default destructive is the error colour where a primary is the accent`()
    {
        val colors = default.buttons.appearanceFor(ControlRole.Destructive).colors(dark = false);
        assertEquals(light.error, colors.fill(live = true, pressed = false));
        assertEquals(light.background, colors.label(live = true));
        assertEquals(light.surface, colors.fill(live = false, pressed = false));
        assertEquals(light.textSecondary, colors.label(live = false));
    }

    @Test
    fun `T1 a default secondary is the surface, outlined one hairline in the text colour`()
    {
        val appearance = default.buttons.appearanceFor(ControlRole.Secondary);
        val colors = appearance.colors(dark = false);
        assertEquals(1.dp, appearance.borderWidth);
        assertEquals(light.surface, colors.fill(live = true, pressed = false));
        assertEquals(light.surface, colors.fill(live = false, pressed = false));
        assertEquals(light.text, colors.label(live = true));
        assertEquals(light.text, colors.outline(live = true));
        assertEquals(light.textSecondary, colors.label(live = false));
        assertEquals(light.textSecondary, colors.outline(live = false));
    }

    @Test
    fun `T1 a default text button is the accent's words on nothing`()
    {
        val appearance = default.buttons.appearanceFor(ControlRole.Text);
        val colors = appearance.colors(dark = false);
        assertEquals(0.dp, appearance.borderWidth);
        assertEquals(Color.Transparent, colors.fill(live = true, pressed = false));
        assertEquals(light.accent, colors.label(live = true));
        assertEquals(light.textSecondary, colors.label(live = false));
    }

    @Test
    fun `T1 every default button keeps the small radius and only the secondary has an outline`()
    {
        ControlRole.entries.forEach { role ->
            val appearance = default.buttons.appearanceFor(role);
            assertEquals("$role radius", SpfnTokens.radiusSmall, appearance.cornerRadius);
            assertEquals(
                "$role outline",
                if (role == ControlRole.Secondary) 1.dp else 0.dp,
                appearance.borderWidth
            );
        };
    }

    // --- T2: a distinct primary container reaches the primary and nothing else ------------

    @Test
    fun `T2 an injected primary container is the primary's fill and not the secondary's`()
    {
        val red = Color(0xFFFF0000);
        val themed = withPrimaryContainer(red);
        assertEquals(red, themed.buttons.appearanceFor(ControlRole.Primary).colors(dark = false).fill(true, false));
        assertEquals(
            light.surface,
            themed.buttons.appearanceFor(ControlRole.Secondary).colors(dark = false).fill(true, false)
        );
    }

    // --- T3: a dark appearance reads the dark half --------------------------------------

    @Test
    fun `T3 a dark appearance reads the injected dark palette and a light one the light palette`()
    {
        val night = light.copy(background = Color(0xFF000000), text = Color(0xFFFFFFFF));
        val themed = default.copy(dark = night);
        assertEquals(night, themed.palette(dark = true));
        assertEquals(light, themed.palette(dark = false));
    }

    @Test
    fun `T3 a dark appearance reads a button's dark colours`()
    {
        val primary = default.buttons.primary;
        val nightColors = primary.dark.copy(container = Color(0xFF123456));
        val themed = primary.copy(dark = nightColors);
        assertEquals(Color(0xFF123456), themed.colors(dark = true).fill(live = true, pressed = false));
        assertEquals(light.accent, themed.colors(dark = false).fill(live = true, pressed = false));
    }

    // --- T4: the nearest injection wins -------------------------------------------------

    @Test
    fun `T4 an inner theme wins inside it and the outer one stands outside it`()
    {
        val outer = withPrimaryContainer(Color(0xFF00FF00));
        val inner = withPrimaryContainer(Color(0xFF0000FF));
        val seen = mutableMapOf<String, SpfnTheme>();
        compose {
            seen["unthemed"] = LocalSpfnTheme.current;
            SpfnTheme(outer)
            {
                seen["before"] = LocalSpfnTheme.current;
                SpfnTheme(inner)
                {
                    seen["inside"] = LocalSpfnTheme.current;
                }
                seen["after"] = LocalSpfnTheme.current;
            }
        };
        assertEquals(SpfnTheme.Default, seen["unthemed"]);
        assertEquals(outer, seen["before"]);
        assertEquals(inner, seen["inside"]);
        assertEquals(outer, seen["after"]);
        assertNotEquals(outer, inner);
    }

    private fun withPrimaryContainer(colour: Color): SpfnTheme
    {
        val primary = default.buttons.primary;
        return default.copy(
            buttons = default.buttons.copy(
                primary = primary.copy(light = primary.light.copy(container = colour, pressedContainer = colour))
            )
        );
    }

    /** One initial composition of [content], over an applier that builds nothing. */
    private fun compose(content: @Composable () -> Unit)
    {
        val composition = Composition(NothingApplier(), Recomposer(EmptyCoroutineContext));
        composition.setContent(content);
        composition.dispose();
    }

    private class NothingApplier : AbstractApplier<Unit>(Unit)
    {
        override fun insertTopDown(index: Int, instance: Unit) = Unit;

        override fun insertBottomUp(index: Int, instance: Unit) = Unit;

        override fun remove(index: Int, count: Int) = Unit;

        override fun move(from: Int, to: Int, count: Int) = Unit;

        override fun onClear() = Unit;
    }
}
