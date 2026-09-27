// The Kotlin half: the tab bar the spec declares, as one `TabHost` and one root per tab.
//
// A tab's root is not a screen of any flow — it is what the tab's `NavigationHost` stands ON
// (docs/architecture/tab-host-design.md §3-3) — so there is no model for it and nothing for a
// person to draw: it is generated whole, out of what the spec's `tabs` entry says. What it
// holds is what the case table reads and presses (§3-7): the readouts `tab=`, `stack=` and
// `scrollToTop=`, one control per flow the tab opens (`<root>.<flow>`), a field for the
// keyboard rows (`<root>.note`), and rows enough to scroll, which follow the tab's
// `TabScrollToTop` the way an app's own list does (decision Q-C). Under it stand the hosts of
// the tab's flows: a pushed flow appends to the tab's own stack, and a modal or a sheet
// registers with the tab host and is drawn over the bar.
//
// What the app draws above the readouts — the example's receipt control — is a slot, because
// no spec states it.
//
// Its twin is `SwiftTabsEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

internal class KotlinTabsEmitter(target: Target) : KotlinNames(target)
{
    internal fun tabs(spec: Spec, inputs: Inputs): String = buildString {
        val flows = spec.tabs.flatMap { spec.flowsOf(it) };
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg");
        appendLine();
        appendLine("import androidx.compose.foundation.layout.Arrangement");
        appendLine("import androidx.compose.foundation.layout.Box");
        appendLine("import androidx.compose.foundation.layout.PaddingValues");
        appendLine("import androidx.compose.foundation.layout.fillMaxSize");
        appendLine("import androidx.compose.foundation.lazy.LazyColumn");
        appendLine("import androidx.compose.foundation.lazy.rememberLazyListState");
        appendLine("import androidx.compose.runtime.Composable");
        appendLine("import androidx.compose.runtime.LaunchedEffect");
        appendLine("import androidx.compose.runtime.collectAsState");
        appendLine("import androidx.compose.runtime.getValue");
        appendLine("import androidx.compose.runtime.mutableStateOf");
        appendLine("import androidx.compose.runtime.saveable.rememberSaveable");
        appendLine("import androidx.compose.runtime.setValue");
        appendLine("import androidx.compose.ui.Modifier");
        appendLine("import androidx.compose.ui.graphics.Color");
        appendLine("import androidx.compose.ui.graphics.SolidColor");
        appendLine("import androidx.compose.ui.graphics.vector.ImageVector");
        appendLine("import androidx.compose.ui.graphics.vector.PathData");
        appendLine("import androidx.compose.ui.graphics.vector.rememberVectorPainter");
        appendLine("import androidx.compose.ui.unit.dp");
        flows.sortedBy { it.name }.forEach { appendLine("import $pkg.flows.${type(it.name, "FlowHost")}") };
        flows.sortedBy { it.name }.forEach { appendLine("import $pkg.flows.${route(it)}") };
        appendLine("import xyz.superfunction.spfn.ui.TabHost");
        appendLine("import xyz.superfunction.spfn.ui.TabItem");
        appendLine("import xyz.superfunction.spfn.ui.TabScrollToTop");
        appendLine("import xyz.superfunction.spfn.ui.components.PrimaryButton");
        appendLine("import xyz.superfunction.spfn.ui.components.Screen");
        appendLine("import xyz.superfunction.spfn.ui.components.SpfnText");
        appendLine("import xyz.superfunction.spfn.ui.components.SpfnTextField");
        appendLine("import xyz.superfunction.spfn.ui.components.TextRole");
        appendLine("import xyz.superfunction.spfn.ui.tokens.SpfnTokens");
        appendLine();
        append(host(spec));
        spec.tabs.forEach { tab ->
            appendLine();
            append(root(spec, tab));
        };
        appendLine();
        append(mark());
    }

    private fun host(spec: Spec): String = buildString {
        appendLine("/**");
        appendLine(" * The spec's tab bar: ${spec.tabs.joinToString(", ") { "`${it.id}`" }}, the first the start tab.");
        appendLine(" *");
        appendLine(" * The app's top level when it is drawn — there is no `NavigationHost` above it, because");
        appendLine(" * each tab is one. [header] is what the app draws at the top of every tab's root.");
        appendLine(" */");
        appendLine("@Composable");
        appendLine("fun AppTabs(container: AppContainer, header: @Composable () -> Unit)");
        appendLine("{");
        appendLine("    val mark = rememberVectorPainter(TAB_MARK);");
        appendLine("    TabHost(");
        appendLine("        state = container.tabs,");
        appendLine("        tabs = listOf(");
        spec.tabs.forEachIndexed { index, tab ->
            val comma = if (index == spec.tabs.size - 1) "" else ",";
            appendLine("            TabItem(id = ${quoted(tab.id)}, title = ${quoted(tab.title)}, icon = mark) { ${type(tab.root, "Root")}(container, header); }$comma");
        };
        appendLine("        )");
        appendLine("    );");
        appendLine("}");
    }

    private fun root(spec: Spec, tab: TabDefinition): String = buildString {
        val flows = spec.flowsOf(tab);
        val depth = flows.joinToString(" +\n        ") { "container.${it.name}Flow.stack.collectAsState().value.size" };
        appendLine("/** The `${tab.id}` tab's root: its readouts, one control per flow it opens, and those flows' hosts. */");
        appendLine("@Composable");
        appendLine("private fun ${type(tab.root, "Root")}(container: AppContainer, header: @Composable () -> Unit)");
        appendLine("{");
        appendLine("    val stack = $depth;");
        appendLine("    val scrollToTop = TabScrollToTop.current;");
        appendLine("    val rows = rememberLazyListState();");
        appendLine("    var note by rememberSaveable { mutableStateOf(\"\") };");
        appendLine();
        appendLine("    LaunchedEffect(scrollToTop)");
        appendLine("    {");
        appendLine("        if (scrollToTop.count > 0)");
        appendLine("        {");
        appendLine("            rows.animateScrollToItem(0);");
        appendLine("        }");
        appendLine("    };");
        appendLine();
        appendLine("    Box(modifier = Modifier.fillMaxSize())");
        appendLine("    {");
        appendLine("        Screen(title = ${quoted(tab.title)}, scroll = false)");
        appendLine("        {");
        appendLine("            LazyColumn(");
        appendLine("                state = rows,");
        appendLine("                modifier = Modifier.fillMaxSize(),");
        appendLine("                contentPadding = PaddingValues(SpfnTokens.space4),");
        appendLine("                verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4)");
        appendLine("            )");
        appendLine("            {");
        appendLine("                item { header(); };");
        appendLine("                item { SpfnText(text = ${quoted("tab=${tab.id}")}, role = TextRole.Mono); };");
        appendLine("                item { SpfnText(text = \"stack=\$stack\", role = TextRole.Mono); };");
        appendLine("                item { SpfnText(text = \"scrollToTop=\${scrollToTop.count}\", role = TextRole.Mono); };");
        appendLine("                item { SpfnTextField(label = \"note\", id = ${quoted("${tab.root}.note")}, value = note, onValueChange = { note = it }); };");
        flows.forEach { flow ->
            val start = spec.screenNamed(flow.start);
            // `item {` on one line, as `SideEffect {` is written in the SDK: `item` followed by a
            // newline is a reference to the function, and the block under it a stray lambda.
            appendLine("                item {");
            appendLine("                    PrimaryButton(");
            appendLine("                        title = ${quoted(flow.name)},");
            appendLine("                        id = ${quoted("${tab.root}.${flow.name}")},");
            appendLine("                        onTap = { container.${flow.name}Flow.push(${route(flow)}.${routeCase(start)}) }");
            appendLine("                    );");
            appendLine("                };");
        };
        appendLine("                items(ROWS) { row -> SpfnText(text = \"row \${row + 1}\"); };");
        appendLine("            };");
        appendLine("        };");
        flows.forEach { appendLine("        ${type(it.name, "FlowHost")}(container);") };
        appendLine("    };");
        appendLine("}");
    }

    private fun mark(): String = buildString {
        appendLine("/** How many rows a root draws: enough that a scroll to the top has somewhere to come from. */");
        appendLine("private const val ROWS: Int = $ROWS;");
        appendLine();
        appendLine("/**");
        appendLine(" * The one mark every tab carries, a dot. A spec says nothing about artwork, and the bar's");
        appendLine(" * labels are what tell the tabs apart.");
        appendLine(" */");
        appendLine("private val TAB_MARK: ImageVector = ImageVector.Builder(");
        appendLine("    name = \"tabMark\",");
        appendLine("    defaultWidth = 20.dp,");
        appendLine("    defaultHeight = 20.dp,");
        appendLine("    viewportWidth = 20f,");
        appendLine("    viewportHeight = 20f");
        appendLine(")");
        appendLine("    .addPath(");
        appendLine("        pathData = PathData {");
        appendLine("            moveTo(2f, 10f);");
        appendLine("            arcTo(8f, 8f, 0f, false, true, 18f, 10f);");
        appendLine("            arcTo(8f, 8f, 0f, false, true, 2f, 10f);");
        appendLine("            close();");
        appendLine("        },");
        appendLine("        fill = SolidColor(Color.Black)");
        appendLine("    )");
        appendLine("    .build();");
    }

    internal companion object
    {
        /** How many rows a tab root draws, on both platforms. */
        const val ROWS: Int = 30;
    }
}
