#if canImport(SwiftUI)
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
//
// Guarded whole, first line of code to last, the way every SwiftUI file in this
// repository is: SwiftUI is Apple's and the validator holds the guard to the file.

import SPFNUI
import SwiftUI

/// The spec's tab bar: `home`, `account`, the first the start tab.
///
/// The app's top level when it is drawn — there is no `NavigationHost` above it, because
/// each tab is one. `header` is what the app draws at the top of every tab's root.
@MainActor
public struct AppTabs<Header: View>: View
{
    private let container: AppContainer
    private let header: () -> Header

    public init(container: AppContainer, @ViewBuilder header: @escaping () -> Header)
    {
        self.container = container
        self.header = header
    }

    public var body: some View
    {
        TabHost(
            state: container.tabs,
            tabs: [
                TabItem(id: "home", title: "Home", icon: Image(systemName: "circle.fill"))
                {
                    HomeListRoot(container: container, header: header)
                },
                TabItem(id: "account", title: "Account", icon: Image(systemName: "circle.fill"))
                {
                    AccountHomeRoot(container: container, header: header)
                }
            ]
        )
    }
}

/// The `home` tab's root: its readouts, one control per flow it opens, and those flows' hosts.
@MainActor
private struct HomeListRoot<Header: View>: View
{
    let container: AppContainer
    let header: () -> Header

    @Environment(\.tabScrollToTop) private var scrollToTop
    @State private var note = ""

    var body: some View
    {
        ZStack
        {
            ScrollViewReader
            { proxy in
                Screen(title: "Home", scroll: true)
                {
                    VStack(alignment: .leading, spacing: SPFNTokens.space4)
                    {
                        header()
                        SpfnText("tab=home", role: .mono)
                        SpfnText("stack=" + String(stack), role: .mono)
                        SpfnText("scrollToTop=" + String(scrollToTop.count), role: .mono)
                        SpfnTextField(label: "note", identifier: "homeList.note", text: $note)
                        PrimaryButton(
                            title: "itemDetail",
                            identifier: "homeList.itemDetail",
                            onTap: { container.itemDetailFlow.push(.item) }
                        )
                        ForEach(1 ... 30, id: \.self)
                        { row in
                            SpfnText("row " + String(row))
                        }
                    }
                    .padding(SPFNTokens.space4)
                    .id("top")
                }
                .onChange(of: scrollToTop)
                {
                    withAnimation
                    {
                        proxy.scrollTo("top", anchor: .top)
                    }
                }
            }
            ItemDetailFlowHost(container: container)
        }
    }

    /// Every flow of this tab's depth added up, which is the tab's stack.
    private var stack: Int
    {
        container.itemDetailFlow.stack.count
    }
}

/// The `account` tab's root: its readouts, one control per flow it opens, and those flows' hosts.
@MainActor
private struct AccountHomeRoot<Header: View>: View
{
    let container: AppContainer
    let header: () -> Header

    @Environment(\.tabScrollToTop) private var scrollToTop
    @State private var note = ""

    var body: some View
    {
        ZStack
        {
            ScrollViewReader
            { proxy in
                Screen(title: "Account", scroll: true)
                {
                    VStack(alignment: .leading, spacing: SPFNTokens.space4)
                    {
                        header()
                        SpfnText("tab=account", role: .mono)
                        SpfnText("stack=" + String(stack), role: .mono)
                        SpfnText("scrollToTop=" + String(scrollToTop.count), role: .mono)
                        SpfnTextField(label: "note", identifier: "accountHome.note", text: $note)
                        PrimaryButton(
                            title: "profile",
                            identifier: "accountHome.profile",
                            onTap: { container.profileFlow.push(.profileSummary) }
                        )
                        PrimaryButton(
                            title: "editProfile",
                            identifier: "accountHome.editProfile",
                            onTap: { container.editProfileFlow.push(.editName) }
                        )
                        PrimaryButton(
                            title: "accountSheet",
                            identifier: "accountHome.accountSheet",
                            onTap: { container.accountSheetFlow.push(.accountNote) }
                        )
                        ForEach(1 ... 30, id: \.self)
                        { row in
                            SpfnText("row " + String(row))
                        }
                    }
                    .padding(SPFNTokens.space4)
                    .id("top")
                }
                .onChange(of: scrollToTop)
                {
                    withAnimation
                    {
                        proxy.scrollTo("top", anchor: .top)
                    }
                }
            }
            ProfileFlowHost(container: container)
            EditProfileFlowHost(container: container)
            AccountSheetFlowHost(container: container)
        }
    }

    /// Every flow of this tab's depth added up, which is the tab's stack.
    private var stack: Int
    {
        container.profileFlow.stack.count + container.editProfileFlow.stack.count + container.accountSheetFlow.stack.count
    }
}
#endif
