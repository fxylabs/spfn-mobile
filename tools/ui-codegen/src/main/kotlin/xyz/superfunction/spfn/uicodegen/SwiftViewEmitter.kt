// The Swift half: one view skeleton per screen a flow has not taken back.
//
// The view is the one file a flow can write for itself. A flow whose `views` are `authored`
// has its screens written by hand from its contract document, and this emitter then neither
// writes them nor lets `Main` delete them (`authoredViews`).
//
// What a generated view holds is the toolkit and nothing below it: a header, the body, one
// control per action, one field per typed input and — for a target that asked for them — the
// two readouts a runner reads.
//
// Its twin is `KotlinViewEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

internal class SwiftViewEmitter(target: Target) : SwiftNames(target)
{
    /**
     * One screen's view: a `Screen` frame, and SPFNUI components inside it.
     *
     * Nothing here draws a control of its own any more. A field is a `SpfnTextField`, a
     * control is the button its `role` names, a refusal is a `StatusText` and a read's four
     * states are a `LoadableView` — so the touch minimum, the keyboard contract and the
     * palette are the SDK's, written once and checked once, rather than re-emitted into
     * every generated view where a fix would have to be made in the generator and shipped.
     *
     * Selectors are unchanged and deliberately so: a control is still found by the id
     * `<screen>.<action>` and a readout by its text. `tools/harness/flows/d1-approve.yaml`
     * and its two siblings drive these screens against a live server by exactly those
     * strings, and a component swap that moved them would be a device regression nothing on
     * this host could see.
     */
    internal fun view(screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val typed = screen.actions.flatMap { RouteParameters.inputs(screen, it, bundle) }.distinctBy { it.name };
        val controls = screen.actions.filter { it != screen.reread };
        appendLine("#if canImport(SwiftUI)");
        appendLine(header(inputs));
        appendLine("//");
        appendLine("// Every element here exists because a runner has to reach it or read it: one control");
        appendLine("// per action, one field per typed input" + if (readouts) ", and the two readouts." else ".");
        if (readouts)
        {
            appendLine("// The readouts stand FIRST so a body long enough to scroll cannot put them out of");
            appendLine("// reach: a runner reads them before it has done anything at all.");
        }
        appendLine("// What a VALUE looks like is the human's, outside `Generated/` — the ready slot below is");
        appendLine("// deliberately empty. Selectors follow the harness's rule: a control by the id");
        appendLine("// `<screen>.<action>`, a readout by its text (tools/harness/ios/Sources/HarnessView.swift).");
        appendLine();
        appendLine("import SPFNCore");
        appendLine("import SPFNUI");
        appendLine("import SwiftUI");
        appendLine();
        appendLine("/// The `${screen.name}` screen, drawn out of SPFNUI's components.");
        appendLine("@MainActor");
        appendLine("public struct ${type(screen.name, "View")}: View");
        appendLine("{");
        appendLine("    @State private var model: ${type(screen.name, "Model")}");
        typed.forEach { appendLine("    @State private var ${it.name}: String = \"\"") };
        appendLine();
        appendLine("    public init(model: ${type(screen.name, "Model")})");
        appendLine("    {");
        appendLine("        _model = State(initialValue: model)");
        appendLine("    }");
        appendLine();
        appendLine("    public var body: some View");
        appendLine("    {");
        appendLine("        Screen(title: ${quoted(screen.title)}${trailingArgument(screen)}, scroll: ${screen.scroll})");
        appendLine("        {");
        appendLine("            VStack(alignment: .leading, spacing: SPFNTokens.space4)");
        appendLine("            {");
        // The readouts come FIRST, and that is a rule about reach rather than about layout.
        // A body long enough to need scrolling puts everything under it below the fold, and a
        // runner that could not read `stack=` until it had scrolled could not tell an app
        // that had not started from a screen it had not reached yet.
        if (readouts)
        {
            appendLine("                readouts");
        }
        if (screen.isLoadable)
        {
            append(loadableSlot(screen));
        }
        // The static body, one component per paragraph. The words are the generator's, out
        // of `BodyText`, because a spec carrying its own prose is one nobody can read the
        // structure out of; the spec named the key.
        screen.body.forEach { paragraph -> appendLine("                SpfnText(${quoted(paragraph)})") };
        typed.forEach { input -> append(field(screen, input, bundle)) };
        if (!screen.isLoadable && typed.isNotEmpty())
        {
            appendLine("                status");
        }
        controls.forEach { action -> append(control(screen, action, bundle)) };
        appendLine("            }");
        appendLine("            .padding(SPFNTokens.space4)");
        if (screen.source != null)
        {
            appendLine("            // A screen loads its own read once, however it appeared: pushed onto the");
            appendLine("            // stack, or already on it because the flow was opened at a whole stack.");
            appendLine("            .task");
            appendLine("            {");
            appendLine("                await model.load()");
            appendLine("            }");
        }
        appendLine("        }");
        appendLine("    }");
        if (!screen.isLoadable && typed.isNotEmpty())
        {
            appendLine();
            append(failureAccessors(screen));
        }
        if (readouts)
        {
            appendLine();
            append(readoutSlot());
        }
        appendLine("}");
        if (readouts)
        {
            appendLine();
            append(stateName(screen));
        }
        appendLine("#endif");
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
        if (screen.suppressesClose) ", trailing: AnyView(EmptyView())" else ""

    /**
     * The read's four states, and the retry control inside the error one.
     *
     * The re-read action is drawn HERE and nowhere else. Emitted as a control of its own as
     * well, it would put two nodes under `<screen>.<retry>` and a runner asked for that id
     * would refuse to pick between them.
     */
    private fun loadableSlot(screen: ScreenDefinition): String = buildString {
        val retry = screen.reread;
        appendLine("                LoadableView(");
        appendLine("                    model.state,");
        if (retry != null)
        {
            appendLine("                    retryIdentifier: \"${screen.name}.${retry.name}\",");
            appendLine("                    onRetry: { Task { await model.${retry.name}() } },");
        }
        appendLine("                    message: ScreenFailure.message");
        appendLine("                )");
        appendLine("                { _ in");
        appendLine("                    // What a value looks like is the human's, outside `Generated/`.");
        appendLine("                    EmptyView()");
        appendLine("                }");
    }

    /** The screen's own refusal, where it is not one a field carries. */
    private fun failureAccessors(screen: ScreenDefinition): String = buildString {
        appendLine("    /// The envelope this screen is carrying, or nil.");
        appendLine("    private var failure: SPFNErrorEnvelope?");
        appendLine("    {");
        appendLine("        if case .error(let envelope) = model.state");
        appendLine("        {");
        appendLine("            return envelope");
        appendLine("        }");
        appendLine("        return nil");
        appendLine("    }");
        appendLine();
        appendLine("    /// A refusal that is the SCREEN's rather than one field's.");
        appendLine("    ///");
        appendLine("    /// A field's own refusal is drawn under the field by `SpfnTextField`, so drawing it");
        appendLine("    /// here as well would say the same thing twice in two places.");
        appendLine("    @ViewBuilder");
        appendLine("    private var status: some View");
        appendLine("    {");
        appendLine("        if let failure = failure, !ScreenFailure.isFieldRefusal(failure)");
        appendLine("        {");
        appendLine("            StatusText(");
        appendLine("                kind: .error,");
        appendLine("                text: ScreenFailure.message(failure),");
        appendLine("                identifier: \"${screen.name}.status\"");
        appendLine("            )");
        appendLine("        }");
        appendLine("    }");
    }

    /**
     * The two readouts, drawn only for a target that asked for them.
     *
     * They are test equipment: the one thing both runners can read and neither can guess, and
     * two lines of diagnostics on a screen a person is meant to use. `--runner-readouts`
     * decides, per consumer, and both consumers that ship today set it (decision C6).
     */
    private fun readoutSlot(): String = buildString {
        appendLine("    /// What a runner reads this screen's state and its flow's depth as.");
        appendLine("    @ViewBuilder");
        appendLine("    private var readouts: some View");
        appendLine("    {");
        appendLine("        SpfnText(\"state=\" + stateName(model.state), role: .mono)");
        appendLine("        SpfnText(\"stack=\" + String(model.stack.count), role: .mono)");
        appendLine("    }");
    }

    /**
     * One typed input, decorated by whatever `screens.<s>.inputs.<i>` said.
     *
     * `onSubmit` and the submitting action are the same call written twice, which is the
     * whole of `submitOnReturn`: the return key does what the button does, so a person who
     * finishes typing does not have to reach for the control.
     */
    private fun field(screen: ScreenDefinition, input: RouteParameters.Parameter, bundle: Bundle): String =
        buildString {
            val declared = screen.inputNamed(input.name);
            val submitting = screen.actions.firstOrNull { action ->
                RouteParameters.inputs(screen, action, bundle).any { it.name == input.name }
            };
            appendLine("                SpfnTextField(");
            appendLine("                    label: ${quoted(declared.label)},");
            appendLine("                    kind: .${declared.kind},");
            appendLine("                    identifier: \"${screen.name}.${input.name}\",");
            appendLine("                    text: \$${input.name},");
            appendLine("                    error: ScreenFailure.fieldMessage(failure, field: \"${input.name}\"),");
            appendLine("                    submitOnReturn: ${declared.submitOnReturn && submitting != null},");
            appendLine("                    autofocus: ${declared.autofocus},");
            if (declared.submitOnReturn && submitting != null)
            {
                appendLine("                    onSubmit: { ${invocation(screen, submitting, bundle)} },");
            }
            appendLine("                    onChange: { _ in model.clearError() }");
            appendLine("                )");
        }

    /**
     * One control, as the button its role names.
     *
     * `busy` is what the model already knows and the screen used to hide: a write in flight
     * disables the control that started it and spins on it, which is the same rule R2 states
     * for the model, drawn.
     */
    private fun control(screen: ScreenDefinition, action: ActionDefinition, bundle: Bundle): String = buildString {
        val id = "${screen.name}.${action.name}";
        appendLine("                ${button(action.role)}(");
        appendLine("                    title: \"${action.name}\",");
        appendLine("                    identifier: \"$id\",");
        if (action.call != null)
        {
            appendLine("                    busy: ${busyExpression(screen)},");
        }
        appendLine("                    onTap: { ${invocation(screen, action, bundle)} }");
        appendLine("                )");
    }

    /** Whether a write of this screen's is in flight, in the shape the model publishes it. */
    private fun busyExpression(screen: ScreenDefinition): String =
        if (screen.isLoadable) "model.writing" else "model.state == .busy"

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
        val arguments = RouteParameters.inputs(screen, action, bundle)
            .joinToString(", ") { "${it.name}: ${it.name}" };
        if (action.call == null)
        {
            return "model.${action.name}()";
        }
        return "Task { await model.${action.name}($arguments) }";
    }

    private fun stateName(screen: ScreenDefinition): String = buildString {
        val stateType = if (screen.isLoadable) "Loadable<Value>" else "Busy";
        appendLine("/// The one word a runner reads this screen's state as.");
        if (screen.isLoadable)
        {
            appendLine("private func stateName<Value: Sendable>(_ state: $stateType) -> String");
        }
        else
        {
            appendLine("private func stateName(_ state: $stateType) -> String");
        }
        appendLine("{");
        appendLine("    switch state");
        appendLine("    {");
        if (screen.isLoadable)
        {
            appendLine("    case .loading: return \"loading\"");
            appendLine("    case .ready: return \"ready\"");
            appendLine("    case .empty: return \"empty\"");
            appendLine("    case .error: return \"error\"");
        }
        else
        {
            appendLine("    case .idle: return \"idle\"");
            appendLine("    case .busy: return \"busy\"");
            appendLine("    case .error: return \"error\"");
        }
        appendLine("    }");
        appendLine("}");
    }
}
