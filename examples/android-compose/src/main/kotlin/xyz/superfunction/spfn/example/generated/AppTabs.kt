// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      0fbec833616cfb9717bb9d80a057d4069bae1f04d1c47d44f98d9becf08dce41
// bundleSha256:    8cce6d896e200a18e1312f23ed63ff4fc36b4ff6ea484f576c3de824be3b589e
// contractVersion: 0.13.2
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import xyz.superfunction.spfn.example.generated.flows.AccountSheetFlowHost
import xyz.superfunction.spfn.example.generated.flows.EditProfileFlowHost
import xyz.superfunction.spfn.example.generated.flows.ItemDetailFlowHost
import xyz.superfunction.spfn.example.generated.flows.ProfileFlowHost
import xyz.superfunction.spfn.example.generated.flows.AccountSheetRoute
import xyz.superfunction.spfn.example.generated.flows.EditProfileRoute
import xyz.superfunction.spfn.example.generated.flows.ItemDetailRoute
import xyz.superfunction.spfn.example.generated.flows.ProfileRoute
import xyz.superfunction.spfn.ui.TabHost
import xyz.superfunction.spfn.ui.TabItem
import xyz.superfunction.spfn.ui.TabScrollToTop
import xyz.superfunction.spfn.ui.components.PrimaryButton
import xyz.superfunction.spfn.ui.components.Screen
import xyz.superfunction.spfn.ui.components.SpfnText
import xyz.superfunction.spfn.ui.components.SpfnTextField
import xyz.superfunction.spfn.ui.components.TextRole
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/**
 * The spec's tab bar: `home`, `account`, the first the start tab.
 *
 * The app's top level when it is drawn — there is no `NavigationHost` above it, because
 * each tab is one. [header] is what the app draws at the top of every tab's root.
 */
@Composable
fun AppTabs(container: AppContainer, header: @Composable () -> Unit)
{
    val mark = rememberVectorPainter(TAB_MARK);
    TabHost(
        state = container.tabs,
        tabs = listOf(
            TabItem(id = "home", title = "Home", icon = mark) { HomeListRoot(container, header); },
            TabItem(id = "account", title = "Account", icon = mark) { AccountHomeRoot(container, header); }
        )
    );
}

/** The `home` tab's root: its readouts, one control per flow it opens, and those flows' hosts. */
@Composable
private fun HomeListRoot(container: AppContainer, header: @Composable () -> Unit)
{
    val stack = container.itemDetailFlow.stack.collectAsState().value.size;
    val scrollToTop = TabScrollToTop.current;
    val rows = rememberLazyListState();
    var note by rememberSaveable { mutableStateOf("") };

    LaunchedEffect(scrollToTop)
    {
        if (scrollToTop.count > 0)
        {
            rows.animateScrollToItem(0);
        }
    };

    Box(modifier = Modifier.fillMaxSize())
    {
        Screen(title = "Home", scroll = false)
        {
            LazyColumn(
                state = rows,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(SpfnTokens.space4),
                verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4)
            )
            {
                item { header(); };
                item { SpfnText(text = "tab=home", role = TextRole.Mono); };
                item { SpfnText(text = "stack=$stack", role = TextRole.Mono); };
                item { SpfnText(text = "scrollToTop=${scrollToTop.count}", role = TextRole.Mono); };
                item { SpfnTextField(label = "note", id = "homeList.note", value = note, onValueChange = { note = it }); };
                item {
                    PrimaryButton(
                        title = "itemDetail",
                        id = "homeList.itemDetail",
                        onTap = { container.itemDetailFlow.push(ItemDetailRoute.Item) }
                    );
                };
                items(ROWS) { row -> SpfnText(text = "row ${row + 1}"); };
            };
        };
        ItemDetailFlowHost(container);
    };
}

/** The `account` tab's root: its readouts, one control per flow it opens, and those flows' hosts. */
@Composable
private fun AccountHomeRoot(container: AppContainer, header: @Composable () -> Unit)
{
    val stack = container.profileFlow.stack.collectAsState().value.size +
        container.editProfileFlow.stack.collectAsState().value.size +
        container.accountSheetFlow.stack.collectAsState().value.size;
    val scrollToTop = TabScrollToTop.current;
    val rows = rememberLazyListState();
    var note by rememberSaveable { mutableStateOf("") };

    LaunchedEffect(scrollToTop)
    {
        if (scrollToTop.count > 0)
        {
            rows.animateScrollToItem(0);
        }
    };

    Box(modifier = Modifier.fillMaxSize())
    {
        Screen(title = "Account", scroll = false)
        {
            LazyColumn(
                state = rows,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(SpfnTokens.space4),
                verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4)
            )
            {
                item { header(); };
                item { SpfnText(text = "tab=account", role = TextRole.Mono); };
                item { SpfnText(text = "stack=$stack", role = TextRole.Mono); };
                item { SpfnText(text = "scrollToTop=${scrollToTop.count}", role = TextRole.Mono); };
                item { SpfnTextField(label = "note", id = "accountHome.note", value = note, onValueChange = { note = it }); };
                item {
                    PrimaryButton(
                        title = "profile",
                        id = "accountHome.profile",
                        onTap = { container.profileFlow.push(ProfileRoute.ProfileSummary) }
                    );
                };
                item {
                    PrimaryButton(
                        title = "editProfile",
                        id = "accountHome.editProfile",
                        onTap = { container.editProfileFlow.push(EditProfileRoute.EditName) }
                    );
                };
                item {
                    PrimaryButton(
                        title = "accountSheet",
                        id = "accountHome.accountSheet",
                        onTap = { container.accountSheetFlow.push(AccountSheetRoute.AccountNote) }
                    );
                };
                items(ROWS) { row -> SpfnText(text = "row ${row + 1}"); };
            };
        };
        ProfileFlowHost(container);
        EditProfileFlowHost(container);
        AccountSheetFlowHost(container);
    };
}

/** How many rows a root draws: enough that a scroll to the top has somewhere to come from. */
private const val ROWS: Int = 30;

/**
 * The one mark every tab carries, a dot. A spec says nothing about artwork, and the bar's
 * labels are what tell the tabs apart.
 */
private val TAB_MARK: ImageVector = ImageVector.Builder(
    name = "tabMark",
    defaultWidth = 20.dp,
    defaultHeight = 20.dp,
    viewportWidth = 20f,
    viewportHeight = 20f
)
    .addPath(
        pathData = PathData {
            moveTo(2f, 10f);
            arcTo(8f, 8f, 0f, false, true, 18f, 10f);
            arcTo(8f, 8f, 0f, false, true, 2f, 10f);
            close();
        },
        fill = SolidColor(Color.Black)
    )
    .build();
