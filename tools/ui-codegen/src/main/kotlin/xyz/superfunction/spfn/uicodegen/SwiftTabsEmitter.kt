// The Swift half: the tab bar the spec declares, as one `TabHost` and one root per tab.
//
// Its twin is `KotlinTabsEmitter.kt`, whose header says what a generated tab root is and why
// it is generated whole. The one difference is where the scroll-to-top lands: SwiftUI has no
// list state to hand a root, so the root's content carries an id and a `ScrollViewReader`
// scrolls to it when the tab's `TabScrollToTop` moves.

package xyz.superfunction.spfn.uicodegen

internal class SwiftTabsEmitter(target: Target) : SwiftNames(target)
{
    internal fun tabs(spec: Spec, inputs: Inputs): String = buildString {
        appendLine("#if canImport(SwiftUI)");
        appendLine(header(inputs));
        appendLine("//");
        appendLine("// Guarded whole, first line of code to last, the way every SwiftUI file in this");
        appendLine("// repository is: SwiftUI is Apple's and the validator holds the guard to the file.");
        appendLine();
        appendLine("import SPFNUI");
        appendLine("import SwiftUI");
        appendLine();
        append(host(spec));
        spec.tabs.forEach { tab ->
            appendLine();
            append(root(spec, tab));
        };
        appendLine("#endif");
    }

    private fun host(spec: Spec): String = buildString {
        appendLine("/// The spec's tab bar: ${spec.tabs.joinToString(", ") { "`${it.id}`" }}, the first the start tab.");
        appendLine("///");
        appendLine("/// The app's top level when it is drawn — there is no `NavigationHost` above it, because");
        appendLine("/// each tab is one. `header` is what the app draws at the top of every tab's root.");
        appendLine("@MainActor");
        appendLine("public struct AppTabs<Header: View>: View");
        appendLine("{");
        appendLine("    private let container: AppContainer");
        appendLine("    private let header: () -> Header");
        appendLine();
        appendLine("    public init(container: AppContainer, @ViewBuilder header: @escaping () -> Header)");
        appendLine("    {");
        appendLine("        self.container = container");
        appendLine("        self.header = header");
        appendLine("    }");
        appendLine();
        appendLine("    public var body: some View");
        appendLine("    {");
        appendLine("        TabHost(");
        appendLine("            state: container.tabs,");
        appendLine("            tabs: [");
        spec.tabs.forEachIndexed { index, tab ->
            val comma = if (index == spec.tabs.size - 1) "" else ",";
            appendLine("                TabItem(id: ${quoted(tab.id)}, title: ${quoted(tab.title)}, icon: Image(systemName: \"circle.fill\"))");
            appendLine("                {");
            appendLine("                    ${type(tab.root, "Root")}(container: container, header: header)");
            appendLine("                }$comma");
        };
        appendLine("            ]");
        appendLine("        )");
        appendLine("    }");
        appendLine("}");
    }

    private fun root(spec: Spec, tab: TabDefinition): String = buildString {
        val flows = spec.flowsOf(tab);
        appendLine("/// The `${tab.id}` tab's root: its readouts, one control per flow it opens, and those flows' hosts.");
        appendLine("@MainActor");
        appendLine("private struct ${type(tab.root, "Root")}<Header: View>: View");
        appendLine("{");
        appendLine("    let container: AppContainer");
        appendLine("    let header: () -> Header");
        appendLine();
        appendLine("    @Environment(\\.tabScrollToTop) private var scrollToTop");
        appendLine("    @State private var note = \"\"");
        appendLine();
        appendLine("    var body: some View");
        appendLine("    {");
        appendLine("        ZStack");
        appendLine("        {");
        appendLine("            ScrollViewReader");
        appendLine("            { proxy in");
        appendLine("                Screen(title: ${quoted(tab.title)}, scroll: true)");
        appendLine("                {");
        appendLine("                    VStack(alignment: .leading, spacing: SPFNTokens.space4)");
        appendLine("                    {");
        appendLine("                        header()");
        appendLine("                        SpfnText(${quoted("tab=${tab.id}")}, role: .mono)");
        appendLine("                        SpfnText(\"stack=\" + String(stack), role: .mono)");
        appendLine("                        SpfnText(\"scrollToTop=\" + String(scrollToTop.count), role: .mono)");
        appendLine("                        SpfnTextField(label: \"note\", identifier: ${quoted("${tab.root}.note")}, text: \$note)");
        flows.forEach { flow ->
            appendLine("                        PrimaryButton(");
            appendLine("                            title: ${quoted(flow.name)},");
            appendLine("                            identifier: ${quoted("${tab.root}.${flow.name}")},");
            appendLine("                            onTap: { container.${flow.name}Flow.push(.${routeCase(spec.screenNamed(flow.start))}) }");
            appendLine("                        )");
        };
        appendLine("                        ForEach(1 ... ${KotlinTabsEmitter.ROWS}, id: \\.self)");
        appendLine("                        { row in");
        appendLine("                            SpfnText(\"row \" + String(row))");
        appendLine("                        }");
        appendLine("                    }");
        appendLine("                    .padding(SPFNTokens.space4)");
        appendLine("                    .id(\"top\")");
        appendLine("                }");
        appendLine("                .onChange(of: scrollToTop)");
        appendLine("                {");
        appendLine("                    withAnimation");
        appendLine("                    {");
        appendLine("                        proxy.scrollTo(\"top\", anchor: .top)");
        appendLine("                    }");
        appendLine("                }");
        appendLine("            }");
        flows.forEach { appendLine("            ${type(it.name, "FlowHost")}(container: container)") };
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// Every flow of this tab's depth added up, which is the tab's stack.");
        appendLine("    private var stack: Int");
        appendLine("    {");
        appendLine("        " + flows.joinToString(" + ") { "container.${it.name}Flow.stack.count" });
        appendLine("    }");
        appendLine("}");
    }
}
