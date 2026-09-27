// SPFN Mobile — screen-level listening.
//
// `LaunchedEffect` around `SpfnEventStream.listen`, finding the stream in
// LocalSpfnEventStream. The listener attaches when the effect starts and detaches when it
// leaves the composition or its key changes; neither touches the connection.
//
// A form with a condition takes a `key` as well, and there is no form with a condition
// and without one. The condition is fixed when the listener attaches, and a
// `LaunchedEffect` whose key did not change does not restart — so a condition capturing a
// workspace id would keep filtering for the first workspace after the screen moved to
// another, silently (§8 H-11). Passing what the condition captures as `key` is what
// re-attaches it (L-15).
//
// Sources/SPFNEvents/SPFNEventStreamAttachment.swift declares the SwiftUI `onSPFNEvent`.

package xyz.superfunction.spfn.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import xyz.superfunction.spfn.client.SpfnEventPayload
import xyz.superfunction.spfn.client.SpfnEventSignal
import xyz.superfunction.spfn.core.SpfnCanonicalValue

/** Every signal of [payload]'s event, for as long as this composable is composed. */
@Composable
fun <E> SpfnEventEffect(payload: SpfnEventPayload<E>, onSignal: suspend (SpfnEventSignal<E>) -> Unit)
{
    SpfnEventEffect(payload.eventName, payload::decode, onSignal);
}

/** The frames of [payload]'s event that pass [where]; [key] is whatever [where] captures. */
@Composable
fun <E> SpfnEventEffect(payload: SpfnEventPayload<E>, key: Any?, where: (E) -> Boolean, onSignal: suspend (SpfnEventSignal<E>) -> Unit)
{
    SpfnEventEffect(payload.eventName, payload::decode, key, where, onSignal);
}

/** Every signal of the event [name], decoded by [decode]. */
@Composable
fun <E> SpfnEventEffect(name: String, decode: (SpfnCanonicalValue) -> E, onSignal: suspend (SpfnEventSignal<E>) -> Unit)
{
    val stream = LocalSpfnEventStream.current;
    val handler by rememberUpdatedState(onSignal);
    LaunchedEffect(stream, name)
    {
        stream.listen(name, decode).collect { handler(it) };
    };
}

/** The frames of the event [name] that pass [where]; [key] is whatever [where] captures. */
@Composable
fun <E> SpfnEventEffect(
    name: String,
    decode: (SpfnCanonicalValue) -> E,
    key: Any?,
    where: (E) -> Boolean,
    onSignal: suspend (SpfnEventSignal<E>) -> Unit
)
{
    val stream = LocalSpfnEventStream.current;
    val handler by rememberUpdatedState(onSignal);
    LaunchedEffect(stream, name, key)
    {
        stream.listen(name, decode, where).collect { handler(it) };
    };
}
