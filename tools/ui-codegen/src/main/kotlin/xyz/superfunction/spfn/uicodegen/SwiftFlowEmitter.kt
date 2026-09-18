// The Swift half: one flow's route type, its presentation and its host.
//
// The route enum is what a flow can push, the entry value is how the flow is presented, and
// the host is the one place a route becomes a screen. Two flows' routes never meet on one
// stack — the spec refuses a push across flows (refusal 3) — so each flow gets a type of its
// own here rather than a shared one with a runtime check in it.
//
// Its twin is `KotlinFlowEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

internal class SwiftFlowEmitter(target: Target) : SwiftNames(target)
{
    internal fun flow(spec: Spec, flow: FlowDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val screens = spec.screensOf(flow).sortedBy { it.name };
        appendLine("#if canImport(SwiftUI)");
        appendLine(header(inputs));
        appendLine("//");
        appendLine("// Guarded whole, first line of code to last, the way every SwiftUI file in this");
        appendLine("// repository is: SwiftUI is Apple's and the validator holds the guard to the file.");
        appendLine();
        appendLine("import SPFNUI");
        appendLine("import SwiftUI");
        appendLine();
        append(routeType(flow, screens, bundle));
        appendLine();
        append(flowFactory(flow));
        appendLine();
        append(flowHost(flow, screens, bundle));
        appendLine("#endif");
    }

    private fun routeType(flow: FlowDefinition, screens: List<ScreenDefinition>, bundle: Bundle): String =
        buildString {
            appendLine("/// Where the `${flow.name}` flow can stand.");
            appendLine("///");
            appendLine("/// A screen that reads carries what its read needs; a screen that reads nothing");
            appendLine("/// carries nothing. `Hashable` is synthesised either way — every payload here is a");
            appendLine("/// required string or integer, and both are `Hashable` — which is what");
            appendLine("/// `NavigationStack(path:)` identifies a stack entry by.");
            appendLine("public enum ${route(flow)}: FlowRoute");
            appendLine("{");
            screens.forEach { screen ->
                val parameters = RouteParameters.of(screen, bundle);
                if (parameters.isEmpty())
                {
                    appendLine("    case ${routeCase(screen)}");
                }
                else
                {
                    val payload = parameters.joinToString(", ") { "${it.name}: ${swiftType(it.type)}" };
                    appendLine("    case ${routeCase(screen)}($payload)");
                }
            };
            appendLine("}");
        }

    /**
     * The `FlowEntry` value, which is a name for two of the three and a call for the third.
     *
     * A sheet stands at a height and the other two do not, which is why `FlowEntry` carries a
     * payload on one case. The spec says the same thing the same way: a `sheet` entry carries
     * `sheet.detent` and nothing else may.
     */
    private fun entryValue(flow: FlowDefinition): String = when (flow.entry)
    {
        "sheet" -> ".sheet(detent: .${requireNotNull(flow.detent)})"
        else -> ".${flow.entry}"
    }

    private fun flowFactory(flow: FlowDefinition): String = buildString {
        appendLine("/// How this flow is presented, and therefore what a back on its last route means.");
        appendLine("public let ${flow.name}Entry: FlowEntry = ${entryValue(flow)}");
        appendLine();
        appendLine("/// A factory, so the flow opens on the screen the spec named as its start.");
        appendLine("@MainActor");
        appendLine("public func ${type(flow.name, "Flow")}() -> Flow<${route(flow)}>");
        appendLine("{");
        appendLine("    Flow(initial: [.${flow.start}])");
        appendLine("}");
    }

    private fun flowHost(flow: FlowDefinition, screens: List<ScreenDefinition>, bundle: Bundle): String =
        buildString {
            appendLine("/// Renders the `${flow.name}` flow: one route, one model, one view.");
            appendLine("///");
            appendLine("/// A screen with a source loads it here, once per route: a screen loads its own read");
            appendLine("/// however it appeared, which is what makes a deep entry — `open(at:)` onto a whole");
            appendLine("/// stack — behave exactly like a push.");
            appendLine("@MainActor");
            appendLine("public struct ${type(flow.name, "FlowHost")}: View");
            appendLine("{");
            appendLine("    private let container: AppContainer");
            appendLine();
            appendLine("    public init(container: AppContainer)");
            appendLine("    {");
            appendLine("        self.container = container");
            appendLine("    }");
            appendLine();
            appendLine("    public var body: some View");
            appendLine("    {");
            appendLine("        FlowHost(flow: container.${flow.name}Flow, entry: ${flow.name}Entry)");
            appendLine("        { route in");
            appendLine("            switch route");
            appendLine("            {");
            screens.forEach { screen -> append(hostBranch(screen, bundle)) };
            appendLine("            }");
            appendLine("        }");
            appendLine("    }");
            appendLine("}");
        }

    private fun hostBranch(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val parameters = RouteParameters.of(screen, bundle);
        if (parameters.isEmpty())
        {
            appendLine("            case .${routeCase(screen)}:");
            appendLine("                ${type(screen.name, "View")}(model: container.${screen.name}Model())");
            return@buildString;
        }
        val bindings = parameters.joinToString(", ") { "let ${it.name}" };
        val arguments = parameters.joinToString(", ") { "${it.name}: ${it.name}" };
        appendLine("            case .${routeCase(screen)}($bindings):");
        appendLine("                ${type(screen.name, "View")}(model: container.${screen.name}Model($arguments))");
    }
}
