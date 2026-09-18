// SPFN Mobile — the operation descriptor shared by hand-written and generated code.
//
// Counterpart of Sources/SPFNCore/SPFNOperation.swift. This type is hand-written and
// stable; the per-operation values that fill it in are generated into
// xyz.superfunction.spfn.generated from the pinned bundle.

package xyz.superfunction.spfn.core

/** One contract operation, as the pinned bundle describes it. */
data class SpfnOperation(
    val id: String,
    val method: String,
    val path: String,
    val authProfile: String,
    val requiresSession: Boolean,
    /**
     * Whether the contract declares a response body for this operation.
     *
     * `false` is the contract's own bodyless shape, stated by `restOperations.responseBody`
     * from 0.10.0 on: "An operation that declares no responseType answers 204 with an empty
     * body and there is nothing to decode". [SpfnClient] reads this rather than the
     * operation id, so a later bodyless operation needs no change to the execute path.
     *
     * No default value. Every descriptor states it, because a defaulted `true` would make
     * the bodyless case something a hand-built descriptor forgets rather than something it
     * declares — and a Kotlin default is no default at all to a Java caller, which has to
     * pass every argument anyway.
     */
    val declaresResponse: Boolean
)

/**
 * What an operation that declares no response body answers with.
 *
 * An object rather than [Unit]: `Unit` is what a Kotlin function returns when it returns
 * nothing, so a caller could not tell "the server accepted it and said nothing" — which is
 * a result — from a call that reports nothing at all. A Java caller sees
 * `SpfnNoResponse.INSTANCE`, a named type from this contract, rather than `kotlin.Unit`.
 *
 * Swift's counterpart is the `SPFNNoResponse` struct, for the same reason on that side.
 */
object SpfnNoResponse
