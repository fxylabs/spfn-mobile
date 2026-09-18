// The Kotlin half: one view skeleton per screen a flow has not taken back.
//
// The view is the one file a flow can write for itself. A flow whose `views` are `authored`
// has its screens written by hand from its contract document, and this emitter then neither
// writes them nor lets `Main` delete them (`authoredViews`).
//
// What a generated view holds is the toolkit and nothing below it: a header, the body, one
// control per action, one field per typed input and — for a target that asked for them — the
// two readouts a runner reads.
//
// Its twin is `SwiftViewEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

internal class KotlinViewEmitter(target: Target) : KotlinNames(target)
{
    /**
     * One screen's view: a `Screen` frame, and SPFNUI components inside it.
     *
     * Nothing here draws a control of its own any more. A field is a `SpfnTextField`, a
     * control is the button its `role` names, a refusal is a `StatusText` and a read's four
     * states are a `LoadableView` — so the touch minimum, the keyboard contract and the
     * palette are the SDK's, written once and checked once, rather than re-emitted into every
     * generated view where a fix would have to be made in the generator and shipped.
     *
     * Selectors are unchanged and deliberately so: a control is still found by the test tag
     * `<screen>.<action>` and a readout by its text. `tools/harness/flows/d1-approve.yaml`
     * and its two siblings drive these screens against a live server by exactly those
     * strings, and a component swap that moved them would be a device regression nothing on
     * this host could see. The tags reach Maestro as resource ids because the app's root
     * turns test tags into them.
     */
    internal fun view(screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val typed = screen.actions.flatMap { RouteParameters.inputs(screen, it, bundle) }.distinctBy { it.name };
        val controls = screen.actions.filter { it != screen.reread };
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg.views");
        appendLine();
        viewImports(screen, typed, controls).forEach { appendLine("import $it") };
        appendLine();
        appendLine("/** The `${screen.name}` screen, drawn out of spfn-ui's components. */");
        appendLine("@Composable");
        appendLine("fun ${type(screen.name, "Screen")}(model: ${type(screen.name, "Model")})");
        appendLine("{");
        // Every local is emitted only where something below reads it: this module compiles
        // with `allWarningsAsErrors`, and an unused local is a warning, so a screen shape
        // that needed none of them would fail the build rather than emit a dead line.
        val usesState = screen.isLoadable || readouts || typed.isNotEmpty() ||
            controls.any { it.call != null };
        if (usesState)
        {
            appendLine("    val state = model.state.collectAsState().value;");
        }
        if (readouts)
        {
            appendLine("    val stack = model.stack.collectAsState().value;");
        }
        if (screen.isLoadable && controls.any { it.call != null })
        {
            appendLine("    val writing = model.writing.collectAsState().value;");
        }
        if (screen.actions.any { it.call != null })
        {
            appendLine("    val scope = rememberCoroutineScope();");
        }
        typed.forEach { input ->
            appendLine("    var ${input.name} by remember { mutableStateOf(\"\") };");
        };
        if (screen.source != null)
        {
            appendLine();
            appendLine("    // A screen loads its own read once, however it appeared: pushed onto the stack,");
            appendLine("    // or already on it because the flow was opened at a whole stack at once.");
            appendLine("    LaunchedEffect(model) { model.load() };");
        }
        appendLine();
        appendLine("    Screen(title = ${quoted(screen.title)}${trailingArgument(screen)}, scroll = ${screen.scroll})");
        appendLine("    {");
        // `spacedBy` and not a padding on each child, because the Swift emitter's own body
        // is `VStack(alignment: .leading, spacing: SPFNTokens.space4)` and the two halves of
        // one screen are supposed to be the same screen. Without it every paragraph, readout
        // and control on an Android screen touched the one above it while the iOS shot of the
        // same cell had a step of air between them.
        appendLine(
            "        Column(" +
                "modifier = Modifier.fillMaxWidth().padding(SpfnTokens.space4), " +
                "verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4))"
        );
        appendLine("        {");
        // The readouts come FIRST, and that is a rule about reach rather than about layout.
        // A body long enough to need scrolling puts everything under it below the fold, and a
        // runner that could not read `stack=` until it had scrolled could not tell an app
        // that had not started from a screen it had not reached yet.
        if (readouts)
        {
            appendLine("            SpfnText(text = \"state=\" + stateName(state), role = TextRole.Mono);");
            appendLine("            SpfnText(text = \"stack=\" + stack.size, role = TextRole.Mono);");
        }
        if (screen.isLoadable)
        {
            append(loadableSlot(screen));
        }
        // The static body, one component per paragraph. The words are the generator's, out of
        // `BodyText`, because a spec carrying its own prose is one nobody can read the
        // structure out of; the spec named the key.
        screen.body.forEach { paragraph ->
            appendLine("            SpfnText(text = ${quoted(paragraph)});");
        };
        typed.forEach { input -> append(field(screen, input, bundle)) };
        if (!screen.isLoadable && typed.isNotEmpty())
        {
            append(statusLine(screen));
        }
        controls.forEach { action -> append(control(screen, action, bundle)) };
        appendLine("        }");
        appendLine("    }");
        appendLine("}");
        if (readouts)
        {
            appendLine();
            append(stateName(screen));
        }
    }

    /**
     * The header's trailing slot, emitted only where the spec suppresses the flow's own.
     *
     * `Flow.wayOut` gives a back to every route above the root and a close to the root of a
     * flow presented over something, so almost every screen wants the default. An empty slot
     * passed everywhere would erase every way out in the app; it is passed exactly where a
     * root that would have had a close said `header.close: false`.
     */
    private fun trailingArgument(screen: ScreenDefinition): String =
        if (screen.suppressesClose) ", trailing = {}" else ""

    /**
     * The read's four states, and the retry control inside the error one.
     *
     * The re-read action is drawn HERE and nowhere else. Emitted as a control of its own as
     * well, it would put two nodes under `<screen>.<retry>` and a runner asked for that id
     * would refuse to pick between them.
     */
    private fun loadableSlot(screen: ScreenDefinition): String = buildString {
        val retry = screen.reread;
        appendLine("            LoadableView(");
        appendLine("                state = state,");
        if (retry != null)
        {
            appendLine("                retryId = \"${screen.name}.${retry.name}\",");
            appendLine("                onRetry = { scope.launch { model.${retry.name}() } },");
        }
        appendLine("                message = ScreenFailure::message");
        appendLine("            )");
        appendLine("            {");
        appendLine("                // What a value looks like is the human's, outside `generated/`.");
        appendLine("            }");
    }

    /**
     * A refusal that is the SCREEN's rather than one field's.
     *
     * A field's own refusal is drawn under the field by `SpfnTextField`, so drawing it here as
     * well would say the same thing twice in two places.
     */
    private fun statusLine(screen: ScreenDefinition): String = buildString {
        appendLine("            val failure = (state as? Busy.Error)?.error;");
        appendLine("            if (failure != null && !ScreenFailure.isFieldRefusal(failure))");
        appendLine("            {");
        appendLine("                StatusText(");
        appendLine("                    kind = StatusKind.Error,");
        appendLine("                    text = ScreenFailure.message(failure),");
        appendLine("                    id = \"${screen.name}.status\"");
        appendLine("                );");
        appendLine("            }");
    }

    private fun viewImports(
        screen: ScreenDefinition,
        typed: List<RouteParameters.Parameter>,
        controls: List<ActionDefinition>
    ): List<String>
    {
        val imports = mutableListOf(
            "androidx.compose.foundation.layout.Arrangement",
            "androidx.compose.foundation.layout.Column",
            "androidx.compose.foundation.layout.fillMaxWidth",
            "androidx.compose.foundation.layout.padding",
            "androidx.compose.runtime.Composable",
            "androidx.compose.runtime.collectAsState",
            "androidx.compose.ui.Modifier",
            "$pkg.screens.${type(screen.name, "Model")}",
            "xyz.superfunction.spfn.ui.components.Screen",
            "xyz.superfunction.spfn.ui.tokens.SpfnTokens"
        );
        // Named only where something reads it. A showcase screen calls nothing and collects
        // nothing, so it has no failure to classify — and this module compiles with
        // `allWarningsAsErrors`, where an unused import is a build failure rather than lint.
        if (screen.isLoadable || typed.isNotEmpty())
        {
            imports += "$pkg.screens.ScreenFailure";
        }
        if (screen.isLoadable && readouts)
        {
            imports += "xyz.superfunction.spfn.ui.Loadable";
        }
        if (!screen.isLoadable && (readouts || typed.isNotEmpty() || controls.any { it.call != null }))
        {
            imports += "xyz.superfunction.spfn.ui.Busy";
        }
        if (readouts)
        {
            imports += "xyz.superfunction.spfn.ui.components.TextRole";
        }
        if (readouts || screen.body.isNotEmpty())
        {
            imports += "xyz.superfunction.spfn.ui.components.SpfnText";
        }
        if (screen.actions.any { it.call != null })
        {
            imports += "androidx.compose.runtime.rememberCoroutineScope";
            imports += "kotlinx.coroutines.launch";
        }
        if (screen.source != null)
        {
            imports += "androidx.compose.runtime.LaunchedEffect";
            imports += "xyz.superfunction.spfn.ui.components.LoadableView";
        }
        if (typed.isNotEmpty())
        {
            imports += "androidx.compose.runtime.getValue";
            imports += "androidx.compose.runtime.mutableStateOf";
            imports += "androidx.compose.runtime.remember";
            imports += "androidx.compose.runtime.setValue";
            imports += "xyz.superfunction.spfn.ui.components.FieldKind";
            imports += "xyz.superfunction.spfn.ui.components.SpfnTextField";
        }
        if (!screen.isLoadable && typed.isNotEmpty())
        {
            imports += "xyz.superfunction.spfn.ui.components.StatusKind";
            imports += "xyz.superfunction.spfn.ui.components.StatusText";
        }
        controls.forEach { imports += "xyz.superfunction.spfn.ui.components.${button(it.role)}" };
        return imports.distinct().sorted();
    }

    /**
     * One typed input, decorated by whatever `screens.<s>.inputs.<i>` said.
     *
     * `onSubmit` and the submitting action are the same call written twice, which is the whole
     * of `submitOnReturn`: the return key does what the button does, so a person who finishes
     * typing does not have to reach for the control.
     */
    private fun field(screen: ScreenDefinition, input: RouteParameters.Parameter, bundle: Bundle): String =
        buildString {
            val declared = screen.inputNamed(input.name);
            val submitting = screen.actions.firstOrNull { action ->
                RouteParameters.inputs(screen, action, bundle).any { it.name == input.name }
            };
            appendLine("            SpfnTextField(");
            appendLine("                label = ${quoted(declared.label)},");
            appendLine("                id = \"${screen.name}.${input.name}\",");
            appendLine("                value = ${input.name},");
            appendLine("                onValueChange = { edited -> ${input.name} = edited; model.clearError(); },");
            appendLine("                kind = FieldKind.${UiNames.pascal(declared.kind)},");
            appendLine("                error = ScreenFailure.fieldMessage(");
            appendLine("                    (state as? Busy.Error)?.error,");
            appendLine("                    \"${input.name}\"");
            appendLine("                ),");
            appendLine("                submitOnReturn = ${declared.submitOnReturn && submitting != null},");
            appendLine("                autofocus = ${declared.autofocus}" + if (declared.submitOnReturn && submitting != null) "," else "");
            if (declared.submitOnReturn && submitting != null)
            {
                appendLine("                onSubmit = { ${invocation(screen, submitting, bundle)} }");
            }
            appendLine("            );");
        }

    /**
     * One control, as the button its role names.
     *
     * `busy` is what the model already knows and the screen used to hide: a write in flight
     * disables the control that started it and spins on it, which is the same rule R2 states
     * for the model, drawn.
     */
    private fun control(screen: ScreenDefinition, action: ActionDefinition, bundle: Bundle): String = buildString {
        appendLine("            ${button(action.role)}(");
        appendLine("                title = \"${action.name}\",");
        appendLine("                id = \"${screen.name}.${action.name}\",");
        if (action.call != null)
        {
            appendLine("                busy = ${busyExpression(screen)},");
        }
        appendLine("                onTap = { ${invocation(screen, action, bundle)} }");
        appendLine("            );");
    }

    /** Whether a write of this screen's is in flight, in the shape the model publishes it. */
    private fun busyExpression(screen: ScreenDefinition): String =
        if (screen.isLoadable) "writing" else "state is Busy.Busy"

    /** The component a spec role names. */
    private fun button(role: String): String = when (role)
    {
        "primary" -> "PrimaryButton"
        "destructive" -> "DestructiveButton"
        "text" -> "TextButton"
        else -> "SecondaryButton"
    }

    /** Calling one action from a control or a return key, suspending or not. */
    private fun invocation(screen: ScreenDefinition, action: ActionDefinition, bundle: Bundle): String
    {
        val arguments = RouteParameters.inputs(screen, action, bundle).joinToString(", ") { it.name };
        if (action.call == null)
        {
            return "model.${action.name}()";
        }
        return "scope.launch { model.${action.name}($arguments) }";
    }

    /** The state readout's vocabulary, which is the state type's own member names. */
    private fun stateName(screen: ScreenDefinition): String = buildString {
        val stateType = if (screen.isLoadable) "Loadable<*>" else "Busy";
        appendLine("/** The one word a runner reads this screen's state as. */");
        appendLine("private fun stateName(state: $stateType): String = when (state)");
        appendLine("{");
        if (screen.isLoadable)
        {
            appendLine("    is Loadable.Loading -> \"loading\"");
            appendLine("    is Loadable.Ready -> \"ready\"");
            appendLine("    is Loadable.Empty -> \"empty\"");
            appendLine("    is Loadable.Error -> \"error\"");
        }
        else
        {
            appendLine("    is Busy.Idle -> \"idle\"");
            appendLine("    is Busy.Busy -> \"busy\"");
            appendLine("    is Busy.Error -> \"error\"");
        }
        appendLine("}");
    }
}
