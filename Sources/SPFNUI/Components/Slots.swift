#if canImport(SwiftUI)
// SPFN Mobile — the two lines every state-shaped view draws the same way.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/Slots.kt.
//
// ``LoadableView`` draws "in flight" and "it failed" for a READ; ``PagedView``'s footer
// draws the same two for the page AFTER the first one. They are the same two lines and the
// second view was one copy-paste away from being a second, slightly different, answer to
// "what does loading look like" — which is exactly what section 15 of validate.sh compares
// the two platforms for, one level up.
//
// So they live here once and both views call them. Internal rather than public on purpose:
// they take no accessibility identifier of their own, they are not something a screen picks
// from a menu, and the component parity set is the list of things a screen CAN pick
// (validate.sh section 15 reads `public struct`, which is what keeps `RoleButton` out of
// that list for the same reason).
//
// Guarded whole, first line to last (docs/IMPLEMENTATION-PITFALLS.md P20).

import SwiftUI

/// Something is in flight: a spinner and the SDK's own word for it.
struct LoadingLine: View
{
    var body: some View
    {
        HStack(spacing: SPFNTokens.space2)
        {
            ProgressView()
                .controlSize(.small)
            SpfnText(SPFNStrings.stateLoading, secondary: true)
        }
    }
}

/// Something failed: the sentence the caller's classifier chose, and the control that runs
/// it again.
///
/// The sentence arrives already chosen. The server's own `message` is never drawn — see
/// ``LoadableView`` and decision C7 — and this view is one level below the classifier that
/// makes that true, so it takes text and not an envelope.
struct FailureLine: View
{
    let text: String
    let retryIdentifier: String
    let onRetry: (() -> Void)?

    var body: some View
    {
        VStack(alignment: .leading, spacing: SPFNTokens.space3)
        {
            StatusText(kind: .error, text: text)
            if let onRetry = onRetry
            {
                SecondaryButton(
                    title: SPFNStrings.actionRetry,
                    identifier: retryIdentifier,
                    onTap: onRetry
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
#endif
