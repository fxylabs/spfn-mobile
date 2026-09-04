#if canImport(SwiftUI)
// SPFN Mobile — one read's four states, drawn.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/LoadableView.kt.
//
// `Loadable` has four members and this has four branches, which is the whole design: a screen
// that switched on the state itself would grow three of them and forget the fourth, and
// `empty` is the one it would forget — no operation in the pinned contract can produce it
// today (examples/ui-spec/SCHEMA.md, "1단계 rule: every response is an object"), so a screen
// written by hand would never see it in a run and would ship without it.
//
// Three of the four have defaults and the fourth does not. What "ready" looks like is the
// screen's own business; what "loading", "nothing" and "it failed" look like is the SDK's,
// and a screen that had to write them would write them differently every time. The words
// come from ``SPFNStrings`` and the failure is classified into a KEY rather than drawn from
// the envelope, because the envelope's `message` is text a server chose (decision C7).

import SPFNCore
import SwiftUI

/// Draws whichever of a read's four states it is in.
public struct LoadableView<Value: Sendable, Ready: View>: View
{
    private let state: Loadable<Value>
    private let retryIdentifier: String
    private let onRetry: (() -> Void)?
    private let message: (SPFNErrorEnvelope) -> String
    private let ready: (Value) -> Ready

    /// - Parameters:
    ///   - state: the read's state.
    ///   - retryIdentifier: the accessibility id of the control the error slot draws.
    ///   - onRetry: what that control does. Nil draws no control, which is the honest shape
    ///     for a screen whose spec declares no re-read.
    ///   - message: how an envelope becomes a sentence. The default is the least this SDK
    ///     can honestly say; the generated scaffold passes its own `ScreenFailure.message`,
    ///     which classifies the CODE against the pinned contract and looks the answer up in
    ///     ``SPFNStrings``. The closure is here rather than a classifier inside this file
    ///     because the codes are the contract's and this module does not read the contract.
    ///   - ready: the only slot a caller has to write.
    public init(
        _ state: Loadable<Value>,
        retryIdentifier: String = "",
        onRetry: (() -> Void)? = nil,
        message: @escaping (SPFNErrorEnvelope) -> String = { _ in SPFNStrings.errorUnexpected },
        @ViewBuilder ready: @escaping (Value) -> Ready
    )
    {
        self.state = state
        self.retryIdentifier = retryIdentifier
        self.onRetry = onRetry
        self.message = message
        self.ready = ready
    }

    public var body: some View
    {
        switch state
        {
        case .loading:
            loading
        case .ready(let value):
            ready(value)
        case .empty:
            AnyView(SpfnText(SPFNStrings.stateEmpty, secondary: true))
        case .error(let envelope):
            failure(envelope)
        }
    }

    private var loading: AnyView
    {
        AnyView(
            HStack(spacing: SPFNTokens.space2)
            {
                ProgressView()
                    .controlSize(.small)
                SpfnText(SPFNStrings.stateLoading, secondary: true)
            }
        )
    }

    /// The failure, as the sentence the caller's classifier chose for the envelope's CODE.
    ///
    /// The server's own `message` is never drawn — see this file's header and decision C7.
    private func failure(_ envelope: SPFNErrorEnvelope) -> AnyView
    {
        AnyView(
            VStack(alignment: .leading, spacing: SPFNTokens.space3)
            {
                StatusText(kind: .error, text: message(envelope))
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
        )
    }
}
#endif
