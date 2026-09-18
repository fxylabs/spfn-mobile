// The Kotlin half: one flow's route type, its presentation and its host.
//
// The route enum is what a flow can push, the entry value is how the flow is presented, and
// the host is the one place a route becomes a screen. Two flows' routes never meet on one
// stack — the spec refuses a push across flows (refusal 3) — so each flow gets a type of its
// own here rather than a shared one with a runtime check in it.
//
// Its twin is `SwiftFlowEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

internal class KotlinFlowEmitter(target: Target) : KotlinNames(target)
{
    internal fun flow(spec: Spec, flow: FlowDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val screens = spec.screensOf(flow);
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg.flows");
        appendLine();
        appendLine("import androidx.compose.runtime.Composable");
        appendLine("import androidx.compose.runtime.remember");
        appendLine("import $pkg.AppContainer");
        screens.sortedBy { it.name }.forEach {
            appendLine("import $pkg.views.${type(it.name, "Screen")}");
        };
        appendLine("import xyz.superfunction.spfn.ui.Flow");
        appendLine("import xyz.superfunction.spfn.ui.FlowEntry");
        appendLine("import xyz.superfunction.spfn.ui.FlowHost");
        appendLine("import xyz.superfunction.spfn.ui.FlowRoute");
        if (flow.entry == "sheet")
        {
            appendLine("import xyz.superfunction.spfn.ui.SheetDetent");
        }
        appendLine();
        append(routeType(flow, screens, bundle));
        appendLine();
        append(flowFactory(flow));
        appendLine();
        append(flowHost(flow, screens, bundle));
    }

    /**
     * The `FlowEntry` value, which is a name for two of the three and a call for the third.
     *
     * A sheet stands at a height and the other two do not, which is why `FlowEntry` is a
     * sealed interface rather than an enum. The spec says the same thing the same way: a
     * `sheet` entry carries `sheet.detent` and nothing else may.
     */
    private fun entryValue(flow: FlowDefinition): String = when (flow.entry)
    {
        "sheet" -> "FlowEntry.Sheet(SheetDetent.${UiNames.pascal(requireNotNull(flow.detent))})"
        else -> "FlowEntry.${UiNames.pascal(flow.entry)}"
    }

    private fun routeType(flow: FlowDefinition, screens: List<ScreenDefinition>, bundle: Bundle): String =
        buildString {
            appendLine("/**");
            appendLine(" * Where the `${flow.name}` flow can stand.");
            appendLine(" *");
            appendLine(" * A screen that reads carries what its read needs; a screen that reads nothing");
            appendLine(" * carries nothing and is a `data object`, so two entries for it are the same entry.");
            appendLine(" */");
            appendLine("sealed interface ${route(flow)} : FlowRoute");
            appendLine("{");
            screens.sortedBy { it.name }.forEachIndexed { index, screen ->
                if (index > 0)
                {
                    appendLine();
                }
                val parameters = RouteParameters.of(screen, bundle);
                if (parameters.isEmpty())
                {
                    appendLine("    data object ${routeCase(screen)} : ${route(flow)}");
                }
                else
                {
                    val fields = parameters.joinToString(", ") { "val ${it.name}: ${kotlinType(it.type)}" };
                    appendLine("    data class ${routeCase(screen)}($fields) : ${route(flow)}");
                }
            };
            appendLine("}");
        }

    private fun flowFactory(flow: FlowDefinition): String = buildString {
        appendLine("/** How this flow is presented, and therefore what a back on its last route means. */");
        appendLine("val ${UiNames.pascal(flow.name)}Entry: FlowEntry = ${entryValue(flow)};");
        appendLine();
        appendLine("/** A closed-over factory, so the flow opens on the screen the spec named as its start. */");
        appendLine("@Suppress(\"FunctionName\")");
        appendLine("fun ${type(flow.name, "Flow")}(): Flow<${route(flow)}> =");
        appendLine("    Flow(listOf(${route(flow)}.${UiNames.pascal(flow.start)}))");
    }

    /**
     * Route to model to view, and nothing else.
     *
     * A screen with a source loads it here, once per route, keyed on the route: a screen
     * loads its own read however it appeared, which is what makes a deep entry —
     * `open(at:)` onto a whole stack — behave exactly like a push.
     */
    private fun flowHost(flow: FlowDefinition, screens: List<ScreenDefinition>, bundle: Bundle): String =
        buildString {
            appendLine("/** Renders the `${flow.name}` flow: one route, one model, one view. */");
            appendLine("@Composable");
            appendLine("fun ${type(flow.name, "FlowHost")}(container: AppContainer)");
            appendLine("{");
            appendLine("    FlowHost(container.${flow.name}Flow, ${UiNames.pascal(flow.name)}Entry) { route ->");
            appendLine("        when (route)");
            appendLine("        {");
            screens.sortedBy { it.name }.forEach { screen -> append(hostBranch(flow, screen, bundle)) };
            appendLine("        }");
            appendLine("    }");
            appendLine("}");
        }

    private fun hostBranch(flow: FlowDefinition, screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val parameters = RouteParameters.of(screen, bundle);
        val arguments = parameters.joinToString(", ") { "route.${it.name}" };
        appendLine("            is ${route(flow)}.${routeCase(screen)} ->");
        appendLine("            {");
        appendLine("                val model = remember(route) { container.${screen.name}Model($arguments) };");
        appendLine("                ${type(screen.name, "Screen")}(model);");
        appendLine("            }");
    }
}
