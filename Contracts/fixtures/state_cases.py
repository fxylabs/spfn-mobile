"""The state client's case table, written by hand from the @spfn/core state README.

Read by derive-expected-values.py, which renders it to state/cases.json. Unlike the other
fixtures in this directory nothing here is computed: every expected value is the README's
sentence for that row, transcribed (docs/IMPLEMENTATION-PITFALLS.md P10 — expected values
are never taken from an implementation). The sentence is carried in each case as `readme`.

Both suites run each case against the state client's decision core (SPFNStateCore,
SpfnStateCore) through the same small interpreter:

- `described`: the states the client is built with (default consent, aiAccounts, runtime).
- `initial`: the snapshot the client is built with, or absent.
- `maxRetries`: the configuration's, default 0.
- `before`: steps run first; whatever they produce is not checked.
- `arrival` (or `arrivals`, alternative orders each run from scratch): the steps under test.
- `after`: what the arrival must have produced. `deliveries`, `queries`, `requests`,
  `errors`, `finished` and `reconnects` are always checked — an absent key means "none".
  `entries`, `connection`, `connections`, `calls`, `timers`, `dropped` and
  `connectionWatch` are checked when present.

The W-cells (the signed-in boundary, state-client-design.md §2-14) do not come from the
README: the web client never outlives a sign-in. Their expected values are written by hand
from issue #104 and the decisions D1 … D6 of §2-14, and each carries that source as `source`
instead of `readme`. They use two steps the README's cells never need: `subject`, the key
lifecycle's signed-in client id (`clientID`, null for none), and `opened` with the `clientID`
its connection was made for. A case that never gives `subject` runs as before it existed.

After every step the interpreter runs the flush the step asked for, so each step is its own
turn. The cells whose point is time, turns or cancellation are platform tests instead
(state-client-design.md §4, "픽스처" column).
"""

T0 = "2026-10-17T09:00:00.000Z"
T1 = "2026-10-17T09:00:00.001Z"
T2 = "2026-10-17T09:00:00.002Z"
T3 = "2026-10-17T09:00:00.003Z"
WA = {"workspaceId": "w-a"}
WB = {"workspaceId": "w-b"}

MANIFEST = {
    "aiAccounts": {"key": ["workspaceId"], "write": False},
    "consent": {"key": [], "write": True},
    "runtime": {"key": ["workspaceId"], "write": True},
}
ALL = ["state.aiAccounts.changed", "state.consent.changed", "state.runtime.changed"]


def event(state):
    return "state.%s.changed" % state


def ready(at, value, data=None):
    entry = {"status": "ready", "at": at, "value": value}
    if data is not None:
        entry["data"] = data
    return entry


def revoked(at=None, value=None):
    return {"status": "revoked"} if at is None else {"status": "revoked", "at": at, "value": value}


def initial(states=None, query=None, at=T1, manifest=None):
    return {"snapshot": {"at": at, "states": states or {}, "manifest": manifest or MANIFEST}, "query": query or {}}


def subscribe(sub, state, key=None):
    return {"op": "subscribe", "sub": sub, "state": state, "key": key or {}}


def opened(epoch, query=None, initial=ALL, denied=(), client=None):
    step = {"op": "opened", "epoch": epoch, "query": query or {}, "initial": initial, "denied": list(denied)}
    if client is not None:
        step["clientID"] = client
    return step


def frame(state, at, value, key=None, data=None):
    step = {"op": "frame", "state": state, "at": at, "value": value}
    if key is not None:
        step["key"] = key
    if data is not None:
        step["data"] = data
    return step


def stream(state):
    return {"op": "stream", "state": state}


def answer(request, value):
    return {"op": "answer", "request": request, "with": value}


def fail(request):
    return {"op": "answer", "request": request, "fail": "network"}


def snapshot(states, at=T2):
    return {"at": at, "states": states}


def read(at, value):
    return {"at": at, "value": value}


def keyed(entry, key):
    return dict(entry, key=key)


def opened_consent(epoch=1):
    """Initial A@T1, consent subscribed as s1, connection `epoch` open serving everything."""
    return [subscribe("s1", "consent"), stream({"open": epoch}), opened(epoch)]


CONSENT_A = initial({"consent": {"value": "A"}})

CA = "client-a"
CB = "client-b"


def subject(client):
    return {"op": "subject", "clientID": client}


def signed_in_consent(client=CA, epoch=1):
    """Signed in as `client`, consent subscribed as s1, connection `epoch` opened for it."""
    return [subject(client), subscribe("s1", "consent"), stream({"open": epoch}), opened(epoch, client=client)]


REFUSED = {"rejected": "signedInChanged"}
ISSUE_104 = "issue #104; state-client-design.md §2-14"

CASES = [
    # ---- O: ordering --------------------------------------------------------------------
    {"id": "O-1", "name": "o1_noEntry_frameApplies",
     "readme": "Ordering: no current entry, or `at` newer than the stamp | the ordering stamp advances; applied and notified only when `status`, `value` or `data` differ",
     "initial": initial(), "before": opened_consent(),
     "arrival": [frame("consent", T1, "A")],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}], "deliveries": {"s1": [ready(T1, "A")]}}},
    {"id": "O-2", "name": "o2_newerFrame_replacesAndNotifies",
     "readme": "Ordering: no current entry, or `at` newer than the stamp | the ordering stamp advances; applied and notified only when `status`, `value` or `data` differ",
     "initial": CONSENT_A, "before": opened_consent(),
     "arrival": [frame("consent", T2, "B")],
     "after": {"entries": [{"state": "consent", "entry": ready(T2, "B")}], "deliveries": {"s1": [ready(T2, "B")]}}},
    {"id": "O-3", "name": "o3_newerSameValue_advancesStampOnly",
     "readme": "Ordering: A listener hears it only when `status`, `value` or `data` changed. The `at` of `current()` is the `at` of the read that last changed the value",
     "initial": CONSENT_A, "before": opened_consent(),
     "arrival": [frame("consent", T2, "A")],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}]}},
    {"id": "O-4", "name": "o4_olderFrame_isDropped",
     "readme": "Ordering: `at` older than the stamp | dropped",
     "initial": initial({"consent": {"value": "B"}}, at=T2), "before": opened_consent(),
     "arrival": [frame("consent", T1, "C")],
     "after": {"entries": [{"state": "consent", "entry": ready(T2, "B")}]}},
    {"id": "O-5", "name": "o5_equalAtSameValue_changesNothing",
     "readme": "Ordering: `at` equal to the stamp | one read stamps one `at`, so this is the same value: applied and notified only when `value` or `data` differ",
     "initial": CONSENT_A, "before": opened_consent(),
     "arrival": [frame("consent", T1, "A")],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}]}},
    {"id": "O-6", "name": "o6_equalAtOtherValue_applies",
     "readme": "Ordering: `at` equal to the stamp | one read stamps one `at`, so this is the same value: applied and notified only when `value` or `data` differ",
     "initial": CONSENT_A, "before": opened_consent(),
     "arrival": [frame("consent", T1, "B")],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "B")}], "deliveries": {"s1": [ready(T1, "B")]}}},
    {"id": "O-7", "name": "o7_dataChange_notifies",
     "readme": "Ordering: applied and notified only when `status`, `value` or `data` differ",
     "initial": initial({"consent": {"value": "A", "data": {"version": "X"}}}), "before": opened_consent(),
     "arrival": [frame("consent", T2, "A", data={"version": "Y"})],
     "after": {"entries": [{"state": "consent", "entry": ready(T2, "A", {"version": "Y"})}],
               "deliveries": {"s1": [ready(T2, "A", {"version": "Y"})]}}},
    {"id": "O-8", "name": "o8_olderThanAdvancedStamp_isDropped",
     "readme": "Ordering: a later read that repeats it advances only the stamp, so an older arrival after it is still dropped",
     "initial": CONSENT_A, "before": opened_consent() + [frame("consent", T3, "A")],
     "arrival": [frame("consent", T2, "B")],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}]}},
    {"id": "O-9", "name": "o9_olderSnapshot_keepsNewerState_appliesOthers",
     "readme": "Ordering: a snapshot older than one state's stamp | that state keeps its entry; the others take the snapshot",
     "initial": initial({"consent": {"value": "A"}, "aiAccounts": {"value": "P"}}, query=WA),
     "before": [subscribe("s1", "consent"), subscribe("s2", "aiAccounts", WA), stream({"open": 1}), opened(1, WA),
                frame("consent", T3, "B")],
     "arrival": [{"op": "snapshotCall", "call": "c1", "query": WA},
                 answer("snapshot", snapshot({"consent": {"value": "C"}, "aiAccounts": {"value": "Q"}}, at=T2))],
     "after": {"entries": [{"state": "consent", "entry": ready(T3, "B")},
                           {"state": "aiAccounts", "key": WA, "entry": ready(T2, "Q")}],
               "deliveries": {"s2": [ready(T2, "Q")]},
               "requests": [{"snapshot": WA}], "calls": {"c1": "snapshot"}}},
    {"id": "O-10", "name": "o10_writeAnswer_thenOwnFrame_notifiesOnce",
     "readme": "Ordering: a write answer | applied like a frame — its own frame, arriving later with the same `at` and value, changes nothing",
     "initial": CONSENT_A, "before": opened_consent(),
     "arrival": [{"op": "write", "call": "c1", "state": "consent", "input": {"choice": "B"}},
                 answer("write", read(T2, "B")), frame("consent", T2, "B")],
     "after": {"entries": [{"state": "consent", "entry": ready(T2, "B")}], "deliveries": {"s1": [ready(T2, "B")]},
               "requests": [{"write": {"state": "consent", "key": {}}}], "calls": {"c1": {"frame": read(T2, "B")}}}},
    {"id": "O-11", "name": "o11_frameForUnsubscribedKey_isDropped",
     "readme": "Ordering: a frame for a key not subscribed here | dropped (the server already filters by the connect query; this is the second guard)",
     "initial": initial(), "before": [subscribe("s1", "runtime", WA), stream({"open": 1}), opened(1, WA)],
     "arrival": [frame("runtime", T1, "X", key=WB)],
     "after": {"entries": [{"state": "runtime", "key": WB, "entry": "loading"}, {"state": "runtime", "key": WA, "entry": "loading"}]}},
    {"id": "O-12", "name": "o12_atComparedAsUtf8_acrossBoundaries",
     "readme": "At: `at` is `new Date().toISOString()`; Ordering: a larger `at` is a later value",
     "initial": initial({"consent": {"value": "A"}}, at="2026-10-17T09:00:00.999Z"), "before": opened_consent(),
     "arrival": [frame("consent", "2026-10-17T09:00:01.000Z", "B"),
                 frame("consent", "2026-10-17T23:59:59.999Z", "C"),
                 frame("consent", "2026-10-18T00:00:00.000Z", "D"),
                 frame("consent", "2026-12-31T23:59:59.999Z", "E"),
                 frame("consent", "2027-01-01T00:00:00.000Z", "F"),
                 frame("consent", "2026-12-31T23:59:59.998Z", "G")],
     "after": {"entries": [{"state": "consent", "entry": ready("2027-01-01T00:00:00.000Z", "F")}],
               "deliveries": {"s1": [ready("2026-10-17T09:00:01.000Z", "B"), ready("2026-10-17T23:59:59.999Z", "C"),
                                     ready("2026-10-18T00:00:00.000Z", "D"), ready("2026-12-31T23:59:59.999Z", "E"),
                                     ready("2027-01-01T00:00:00.000Z", "F")]}}},
    {"id": "O-13", "name": "o13_objectKeyOrder_isNotAChange",
     "readme": "Ordering: applied and notified only when `status`, `value` or `data` differ (deep-equal)",
     "initial": initial({"consent": {"value": {"a": 1, "b": 2}}}), "before": opened_consent(),
     "arrival": [frame("consent", T2, {"b": 2, "a": 1})],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, {"a": 1, "b": 2})}]}},
    {"id": "O-14", "name": "o14_arrayOrder_isAChange",
     "readme": "Ordering: applied and notified only when `status`, `value` or `data` differ (deep-equal)",
     "initial": initial({"consent": {"value": [1, 2]}}), "before": opened_consent(),
     "arrival": [frame("consent", T2, [2, 1])],
     "after": {"entries": [{"state": "consent", "entry": ready(T2, [2, 1])}], "deliveries": {"s1": [ready(T2, [2, 1])]}}},

    # ---- K: keys ------------------------------------------------------------------------
    {"id": "K-1", "name": "k1_firstKeyedSubscribe_setsQuery",
     "readme": "The stream: the connect query is the union of the subscribed keys' fields … A new key value … closes the stream and opens it with the new query",
     "initial": initial(), "before": [stream({"open": 1}), opened(1)],
     "arrival": [subscribe("s1", "runtime", WA)],
     "after": {"queries": [WA], "deliveries": {"s1": ["loading"]}}},
    {"id": "K-2", "name": "k2_sameKeyTwice_keepsQuery",
     "readme": "The stream: the connect query is the union of the subscribed keys' fields",
     "initial": initial({"runtime": {"value": "R"}}, query=WA), "before": [subscribe("s1", "runtime", WA)],
     "arrival": [subscribe("s2", "runtime", WA)],
     "after": {"deliveries": {"s2": [ready(T1, "R")]}}},
    {"id": "K-3", "name": "k3_conflictingKey_latestCarried_shadowReadOnce",
     "readme": "The stream: the most recently subscribed key is carried. A key the stream does not carry is read once (`get`) when the stream opens",
     "initial": initial(), "before": [subscribe("s1", "runtime", WA), stream({"open": 1}), opened(1, WA)],
     "arrival": [subscribe("s2", "aiAccounts", WB), stream({"connecting": 1}), stream({"open": 2}), opened(2, WB)],
     "after": {"queries": [WB], "requests": [{"read": {"state": "runtime", "key": WA}}], "deliveries": {"s2": ["loading"]}}},
    {"id": "K-4", "name": "k4_frameForShadowedKey_appliesUnderOrdering",
     "readme": "Ordering: a frame for a key not subscribed here | dropped (a subscribed key's frame is applied); The stream: receives no frames until it is carried again",
     "initial": initial(),
     "before": [subscribe("s1", "runtime", WA), stream({"open": 1}), opened(1, WA),
                subscribe("s2", "aiAccounts", WB), stream({"open": 2}), opened(2, WB)],
     "arrival": [frame("runtime", T2, "X", key=WA)],
     "after": {"entries": [{"state": "runtime", "key": WA, "entry": ready(T2, "X")}], "deliveries": {"s1": [ready(T2, "X")]}}},
    {"id": "K-5", "name": "k5_lastSubscriberOfKey_leaves_requeries",
     "readme": "The stream: or the last subscriber of one leaving, closes the stream and opens it with the new query",
     "initial": initial(), "before": [subscribe("s1", "runtime", WA), subscribe("s2", "aiAccounts", WB)],
     "arrival": [{"op": "unsubscribe", "sub": "s2"}],
     "after": {"queries": [WA]}},
    {"id": "K-8", "name": "k8_wrongKeyFields_refusedNamingBothSets",
     "readme": "The manifest: a `subscribe` … whose key fields differ from the state's, is refused: the error, naming the state and both sets of fields, goes to `onError`, and the subscription is dropped without reopening the stream",
     "initial": initial(),
     "arrival": [subscribe("s1", "runtime", {"projectId": "p-1"})],
     "after": {"errors": [{"keyFields": {"state": "runtime", "expected": ["workspaceId"], "given": ["projectId"]}}],
               "finished": ["s1"]}},
    {"id": "K-9", "name": "k9_unknownId_refused",
     "readme": "The manifest: a `subscribe` whose id it does not name … is refused",
     "described": ["aiAccounts", "billing", "consent", "runtime"], "initial": initial(),
     "arrival": [subscribe("s1", "billing")],
     "after": {"errors": [{"unknownState": "billing"}], "finished": ["s1"]}},
    {"id": "K-10", "name": "k10_wrongKeyBeforeManifest_waitsThenRefused",
     "readme": "The manifest: Until then a `subscribe` waits (it never throws)… Once the manifest is known, a `subscribe` … is refused",
     "before": [],
     "arrival": [subscribe("s1", "runtime", {"projectId": "p-1"}), answer("snapshot", snapshot({"consent": {"value": "A"}}))],
     "after": {"deliveries": {"s1": ["loading"]}, "requests": [{"snapshot": {"projectId": "p-1"}}],
               "errors": [{"keyFields": {"state": "runtime", "expected": ["workspaceId"], "given": ["projectId"]}}],
               "finished": ["s1"]}},
    {"id": "K-11", "name": "k11_keylessEntry_ignoresQueryKeys",
     "readme": "createStateClient: keyed states under the query's key (a keyless state has no key)",
     "initial": initial(),
     "before": [subscribe("s1", "consent"), subscribe("s2", "runtime", WA), stream({"open": 1}), opened(1, WA, initial=None)],
     "arrival": [answer("snapshot", snapshot({"consent": {"value": "A"}, "runtime": {"value": "R"}}, at=T1))],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}, {"state": "runtime", "key": WA, "entry": ready(T1, "R")}],
               "deliveries": {"s1": [ready(T1, "A")], "s2": [ready(T1, "R")]}}},
    {"id": "K-12", "name": "k12_seenKey_resubscribe_showsCachedReady",
     "readme": "useServerState: a key seen before shows its cached entry as 'ready' until the reopen's frame or snapshot lands (entries are not evicted)",
     "initial": initial({"runtime": {"value": "R"}}, query=WA),
     "arrival": [subscribe("s1", "runtime", WA), stream({"open": 1}), opened(1, WA), frame("runtime", T2, "S", key=WA)],
     "after": {"queries": [WA], "deliveries": {"s1": [ready(T1, "R"), ready(T2, "S")]}}},
    {"id": "K-13", "name": "k13_keyedEntryWithoutQueryKey_isSkipped",
     "readme": "createStateClient (snapshot): Read every state the query names a key for",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1}), opened(1, initial=None)],
     "arrival": [answer("snapshot", snapshot({"consent": {"value": "A"}, "runtime": {"value": "R"}}, at=T1))],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}, {"state": "runtime", "entry": "loading"},
                           {"state": "runtime", "key": WA, "entry": "loading"}],
               "deliveries": {"s1": [ready(T1, "A")]}}},

    # ---- R: revoked ---------------------------------------------------------------------
    {"id": "R-1", "name": "r1_subscriptionRevoked_marksCarriedKey",
     "readme": "'revoked': `subscription.revoked` … the entry of `id` for the key the connection carries … gets `status: 'revoked'`, value kept, listeners notified",
     "initial": initial({"runtime": {"value": "A"}}, query=WA),
     "before": [subscribe("s1", "runtime", WA), stream({"open": 3}), opened(3, WA)],
     "arrival": [{"op": "revoked", "state": "runtime"}],
     "after": {"entries": [{"state": "runtime", "key": WA, "entry": revoked(T1, "A")}], "deliveries": {"s1": [revoked(T1, "A")]}}},
    {"id": "R-2", "name": "r2_subscriptionRevoked_leavesShadowedKey",
     "readme": "'revoked': a shadowed key of the same state is untouched",
     "initial": initial({"runtime": {"value": "A"}}, query=WA),
     "before": [subscribe("s2", "runtime", WB), subscribe("s1", "runtime", WA), stream({"open": 3}), opened(3, WA),
                answer("read", read(T1, "X"))],
     "arrival": [{"op": "revoked", "state": "runtime"}],
     "after": {"entries": [{"state": "runtime", "key": WA, "entry": revoked(T1, "A")},
                           {"state": "runtime", "key": WB, "entry": ready(T1, "X")}],
               "deliveries": {"s1": [revoked(T1, "A")]}}},
    {"id": "R-3", "name": "r3_deniedAtConnect_marksRevoked",
     "readme": "'revoked': `connected.deniedEvents` names a subscribed state — keyless, or keyed on the key the connection carries | 'revoked', value kept",
     "initial": CONSENT_A, "before": [subscribe("s1", "consent")],
     "arrival": [stream({"open": 2}),
                 opened(2, initial=["state.aiAccounts.changed", "state.runtime.changed"], denied=["state.consent.changed"])],
     "after": {"entries": [{"state": "consent", "entry": revoked(T1, "A")}], "deliveries": {"s1": [revoked(T1, "A")]}}},
    {"id": "R-4", "name": "r4_deniedAtConnect_leavesShadowedKey",
     "readme": "'revoked': A shadowed key of the same state is untouched",
     "initial": initial({"runtime": {"value": "A"}}, query=WA),
     "before": [subscribe("s2", "runtime", WB), subscribe("s1", "runtime", WA)],
     "arrival": [stream({"open": 1}),
                 opened(1, WA, initial=["state.aiAccounts.changed", "state.consent.changed"], denied=["state.runtime.changed"])],
     "after": {"entries": [{"state": "runtime", "key": WA, "entry": revoked(T1, "A")}, {"state": "runtime", "key": WB, "entry": "loading"}],
               "deliveries": {"s1": [revoked(T1, "A")]}, "requests": [{"read": {"state": "runtime", "key": WB}}]}},
    {"id": "R-5", "name": "r5_snapshotOmission_marksRevoked",
     "readme": "'revoked': a snapshot that omits a subscribed state — keyless, or keyed with its key in the query | 'revoked', value kept if one exists",
     "initial": CONSENT_A, "before": [subscribe("s1", "consent"), stream({"open": 1}), opened(1, initial=None)],
     "arrival": [answer("snapshot", snapshot({}))],
     "after": {"entries": [{"state": "consent", "entry": revoked(T1, "A")}], "deliveries": {"s1": [revoked(T1, "A")]}}},
    {"id": "R-6", "name": "r6_snapshotOmission_keyNotInQuery_notMarked",
     "readme": "'revoked': A keyed state whose key the query did not carry is not marked",
     "initial": initial(), "before": [subscribe("s1", "runtime", WB)],
     "arrival": [{"op": "snapshotCall", "call": "c1", "query": WA}, answer("snapshot", snapshot({}))],
     "after": {"entries": [{"state": "runtime", "key": WB, "entry": "loading"}], "requests": [{"snapshot": WA}],
               "calls": {"c1": "snapshot"}}},
    {"id": "R-7", "name": "r7_revokeBeforeValue_isRevokedAlone",
     "readme": "'revoked': the mark is kept … apart from the value, so it holds even when no value has arrived yet; createStateClient: `{ status: 'revoked' }` for a state revoked before any value arrived",
     "initial": initial(), "before": opened_consent(),
     "arrival": [{"op": "revoked", "state": "consent"}],
     "after": {"entries": [{"state": "consent", "entry": revoked()}], "deliveries": {"s1": [revoked()]}}},
    {"id": "R-8", "name": "r8_sameConnectionFrame_suppliesValue_staysRevoked",
     "readme": "'revoked': a frame, a read or a snapshot started before the revoke | supplies its value under the ordering rule; the status stays 'revoked'",
     "initial": CONSENT_A, "before": opened_consent(3) + [{"op": "revoked", "state": "consent"}],
     "arrival": [frame("consent", T2, "B")],
     "after": {"entries": [{"state": "consent", "entry": revoked(T2, "B")}], "deliveries": {"s1": [revoked(T2, "B")]}}},
    {"id": "R-9", "name": "r9_olderFrameOnRevoked_isDropped_markKept",
     "readme": "'revoked': supplies its value under the ordering rule; the status stays 'revoked'; Ordering: older than the stamp | dropped",
     "initial": initial({"consent": {"value": "B"}}, at=T2), "before": opened_consent(3) + [{"op": "revoked", "state": "consent"}],
     "arrival": [frame("consent", T1, "C")],
     "after": {"entries": [{"state": "consent", "entry": revoked(T2, "B")}]}},
    {"id": "R-10", "name": "r10_granted_clearsAndReadsOnce_eitherOrder",
     "readme": "'revoked': `subscription.granted` for it | clears the mark for the carried key and reads it once (`get`); the server also sends a frame, before or after the control frame. Both arrive 'ready', and either order ends at the latest value",
     "initial": CONSENT_A, "before": opened_consent(3) + [{"op": "revoked", "state": "consent"}],
     "arrivals": [[{"op": "granted", "state": "consent"}, answer("read", read(T2, "B")), frame("consent", T2, "B")],
                  [{"op": "granted", "state": "consent"}, frame("consent", T2, "B"), answer("read", read(T2, "B"))]],
     "after": {"entries": [{"state": "consent", "entry": ready(T2, "B")}], "deliveries": {"s1": [ready(T2, "B")]},
               "requests": [{"read": {"state": "consent", "key": {}}}]}},
    {"id": "R-11", "name": "r11_newerConnectionFrame_clearsMark",
     "readme": "'revoked': a frame — the initial one included — … of a connection opened after the revoke, holding the state | clears the mark",
     "initial": CONSENT_A, "before": opened_consent(3) + [{"op": "revoked", "state": "consent"}],
     "arrival": [stream({"open": 4}), opened(4), frame("consent", T2, "B")],
     "after": {"entries": [{"state": "consent", "entry": ready(T2, "B")}], "deliveries": {"s1": [ready(T2, "B")]}}},
    {"id": "R-12", "name": "r12_newerConnectionSnapshot_clearsMark",
     "readme": "'revoked': or the snapshot of a connection opened after the revoke",
     "initial": CONSENT_A, "before": opened_consent(3) + [{"op": "revoked", "state": "consent"}],
     "arrival": [stream({"open": 4}), opened(4, initial=None), answer("snapshot", snapshot({"consent": {"value": "B"}}))],
     "after": {"entries": [{"state": "consent", "entry": ready(T2, "B")}], "deliveries": {"s1": [ready(T2, "B")]},
               "requests": [{"snapshot": {}}]}},
    {"id": "R-13", "name": "r13_newerConnectionShadowRead_clearsMark",
     "readme": "'revoked': the read of a shadowed key when a connection opened after the revoke, answered | clears the mark the same way",
     "initial": initial({"runtime": {"value": "A"}}, query=WA),
     "before": [subscribe("s1", "runtime", WA), stream({"open": 3}), opened(3, WA), {"op": "revoked", "state": "runtime"},
                subscribe("s2", "aiAccounts", WB), stream({"open": 4}), opened(4, WB)],
     "arrival": [answer("read", read(T2, "B"))],
     "after": {"entries": [{"state": "runtime", "key": WA, "entry": ready(T2, "B")}], "deliveries": {"s1": [ready(T2, "B")]}}},
    {"id": "R-14", "name": "r14_olderShadowAnswer_dropsValue_liftsMark",
     "readme": "'revoked': an answer of such a read older than the stamp … its value is dropped under the ordering rule, while the mark is still lifted: the lift goes by the connection, not by `at`",
     "initial": initial({"runtime": {"value": "A"}}, query=WA),
     "before": [subscribe("s1", "runtime", WA), stream({"open": 3}), opened(3, WA), {"op": "revoked", "state": "runtime"},
                subscribe("s2", "aiAccounts", WB), stream({"open": 4}), opened(4, WB)],
     "arrival": [{"op": "write", "call": "c1", "state": "runtime", "key": WA, "input": {"choice": "B"}},
                 answer("write", read(T3, "B")), answer("read", read(T2, "C"))],
     "after": {"entries": [{"state": "runtime", "key": WA, "entry": ready(T3, "B")}],
               "deliveries": {"s1": [revoked(T3, "B"), ready(T3, "B")]},
               "requests": [{"write": {"state": "runtime", "key": WA}}], "calls": {"c1": {"frame": read(T3, "B")}}}},
    {"id": "R-15", "name": "r15_snapshotAcrossGrant_omissionNotMarked",
     "readme": "'revoked': Every automatic read is tagged with the connection it was started for, which is how a snapshot in flight across a revoke is told from one read after it",
     "initial": CONSENT_A, "before": [subscribe("s1", "consent"), stream({"open": 3}), opened(3, initial=None)],
     "arrival": [{"op": "granted", "state": "consent"}, answer("snapshot", snapshot({}))],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}], "requests": [{"read": {"state": "consent", "key": {}}}]}},
    {"id": "R-16", "name": "r16_snapshotOfOlderConnection_omissionNotMarked",
     "readme": "'revoked': Every automatic read is tagged with the connection it was started for",
     "initial": CONSENT_A, "before": [subscribe("s1", "consent"), stream({"open": 3}), opened(3, initial=None)],
     "arrival": [stream({"open": 4}), opened(4), answer("snapshot", snapshot({}))],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}]}},

    # ---- C: connection status and opens -------------------------------------------------
    {"id": "C-1", "name": "c1_startValue_isReconnectingZero",
     "readme": "Connection status: Start value `{ status: 'reconnecting', attempt: 0 }`, a client built with `initial` too",
     "initial": CONSENT_A, "arrival": [],
     "after": {"connection": {"reconnecting": 0}}},
    {"id": "C-2", "name": "c2_nothingSubscribed_isReconnectingZero",
     "readme": "Connection status: no stream open — not yet, or nothing subscribed",
     "initial": initial(), "arrival": [stream({"open": 1}), opened(1)],
     "after": {"connection": {"reconnecting": 0}, "connections": []}},
    {"id": "C-3", "name": "c3_streamOpen_isConnected",
     "readme": "Connection status: a stream is open (the SSE `onOpen`)",
     "initial": initial(), "before": [subscribe("s1", "consent")],
     "arrival": [stream({"open": 1})],
     "after": {"connection": "connected", "connections": ["connected"]}},
    {"id": "C-4", "name": "c4_streamRetrying_isReconnectingN",
     "readme": "Connection status: the SSE client retrying a dropped one: `attempt` is its count (`onReconnect`)",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1})],
     "arrival": [stream({"retrying": 1})],
     "after": {"connection": {"reconnecting": 1}, "connections": [{"reconnecting": 1}]}},
    {"id": "C-5", "name": "c5_streamConnecting_keepsStatus",
     "readme": "Connection status: the SSE client retrying a dropped one: `attempt` is its count",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1}), stream({"retrying": 2})],
     "arrival": [stream({"connecting": 2})],
     "after": {"connection": {"reconnecting": 2}, "connections": []}},
    {"id": "C-6", "name": "c6_streamOffline_isReconnectingN",
     "readme": "Connection status: the SSE client retrying a dropped one (offline is a retry, §2-9)",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1})],
     "arrival": [stream({"offline": 1})],
     "after": {"connection": {"reconnecting": 1}, "connections": [{"reconnecting": 1}]}},
    {"id": "C-7", "name": "c7_streamIdle_isReconnectingZero",
     "readme": "Connection status: no stream open — not yet",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1})],
     "arrival": [stream({"idle": "background"})],
     "after": {"connection": {"reconnecting": 0}, "connections": [{"reconnecting": 0}]}},
    {"id": "C-8", "name": "c8_streamClosed_isFailed",
     "readme": "Connection status: the retries are exhausted",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1}), stream({"retrying": 3})],
     "arrival": [stream({"closed": "unauthorized"})],
     "after": {"connection": "failed", "connections": ["failed"]}},
    {"id": "C-13", "name": "c13_retryWithManifest_reconnectsNow",
     "readme": "createStateClient `retry()`: once the manifest is known, reopens the stream now; Connection status: The status is 'reconnecting' 0 until it opens",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1}), stream({"closed": "forbidden"})],
     "arrival": [{"op": "retry"}, stream({"connecting": 1}), stream({"open": 2})],
     "after": {"reconnects": 1, "connection": "connected", "connections": [{"reconnecting": 0}, "connected"]}},
    {"id": "C-14", "name": "c14_retryWhenConnectedOrUnsubscribed_isNoOp",
     "readme": "createStateClient `retry()`: A no-op when 'connected' … and with nothing subscribed",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1})],
     "arrival": [{"op": "retry"}, {"op": "unsubscribe", "sub": "s1"}, {"op": "retry"}],
     "after": {"connection": {"reconnecting": 0}, "connections": [{"reconnecting": 0}]}},
    {"id": "C-15", "name": "c15_keyReopenWhileConnected_staysConnected",
     "readme": "Connection status: a reopen on a key change while connected stays 'connected'",
     "initial": initial(), "before": [subscribe("s1", "runtime", WA), stream({"open": 1}), opened(1, WA)],
     "arrival": [subscribe("s2", "runtime", WB), stream({"connecting": 1}), stream({"open": 2}), opened(2, WB)],
     "after": {"connection": "connected", "connections": [], "queries": [WB],
               "requests": [{"read": {"state": "runtime", "key": WA}}], "deliveries": {"s2": ["loading"]}}},
    {"id": "C-16", "name": "c16_subscribeWhileFailed_reconnectsSameQuery",
     "readme": "Connection status: a `subscribe` restarts when 'failed', reopening a failed stream even when the query is unchanged",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1}), stream({"closed": "forbidden"})],
     "arrival": [subscribe("s2", "consent")],
     "after": {"reconnects": 1, "connection": {"reconnecting": 0}, "connections": [{"reconnecting": 0}],
               "deliveries": {"s2": ["loading"]}}},
    {"id": "C-17", "name": "c17_unsubscribeLeavingOthers_staysFailed",
     "readme": "Connection status: An unsubscribe that leaves subscribers never does: the status stays 'failed'",
     "initial": initial(),
     "before": [subscribe("s1", "consent"), subscribe("s2", "consent"), stream({"open": 1}), stream({"closed": "forbidden"})],
     "arrival": [{"op": "unsubscribe", "sub": "s2"}],
     "after": {"connection": "failed", "connections": []}},
    {"id": "C-18", "name": "c18_lastUnsubscribe_resetsStatusQueryAndCount",
     "readme": "Connection status: The last unsubscribe closes the stream: 'reconnecting' 0, the start value, and the first snapshot's retry count starts over for the next wait",
     "maxRetries": 1,
     "before": [subscribe("s1", "consent"), fail("snapshot")],
     "arrival": [{"op": "unsubscribe", "sub": "s1"}, subscribe("s2", "consent"), fail("snapshot")],
     "after": {"connection": {"reconnecting": 0}, "connections": [], "timers": 1,
               "requests": [{"snapshot": {}}], "errors": ["network"], "deliveries": {"s2": ["loading"]}}},
    {"id": "C-21", "name": "c21_leavingOfflineWithManifest_doesNothing",
     "readme": "Connection status: `retry()` and the browser's `online` event … restart when 'failed' or 'reconnecting' (the stream reopens itself, E-35)",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1}), stream({"offline": 1})],
     "arrival": [stream({"connecting": 1})],
     "after": {"connection": {"reconnecting": 1}, "connections": []}},
    {"id": "C-22", "name": "c22_subscribeConnection_yieldsCurrentFirst",
     "readme": "createStateClient `subscribeConnection`: called with the new status on every change, after `connection()` returns it",
     "initial": initial(),
     "arrival": [{"op": "subscribeConnection", "watch": "k1"}, subscribe("s1", "consent"), stream({"open": 1})],
     "after": {"connectionWatch": {"k1": [{"reconnecting": 0}, "connected"]}, "deliveries": {"s1": ["loading"]}}},
    {"id": "C-23", "name": "c23_sameStatus_isNotRepeated",
     "readme": "createStateClient `connection()`: the same reference until it changes",
     "initial": initial(), "before": [subscribe("s1", "consent"), stream({"open": 1}), stream({"retrying": 1})],
     "arrival": [{"op": "subscribeConnection", "watch": "k1"}, stream({"retrying": 1, "delayMillis": 4000})],
     "after": {"connectionWatch": {"k1": [{"reconnecting": 1}]}, "connections": []}},
    {"id": "C-24", "name": "c24_unavailableStateEvent_reportedOnce_rereadEachOpen",
     "readme": "The stream: The client reads the snapshot only when a state it requested is neither in `initial` nor in `deniedEvents`",
     "described": ["aiAccounts", "billing", "consent", "runtime"], "initial": initial(),
     "before": [subscribe("s1", "consent"), stream({"open": 1})],
     "arrival": [{"op": "unavailable", "names": ["state.billing.changed"]}, opened(1),
                 {"op": "unavailable", "names": ["state.billing.changed"]}, stream({"open": 2}), opened(2)],
     "after": {"errors": [{"unavailable": "state.billing.changed"}], "requests": [{"snapshot": {}}, {"snapshot": {}}]}},
    {"id": "C-25", "name": "c25_overflow_rereadsSnapshotAndShadows",
     "readme": "The stream: A frame sent while no stream was open is lost … The server covers that gap",
     "initial": initial(),
     "before": [subscribe("s1", "runtime", WB), subscribe("s2", "runtime", WA), stream({"open": 3}), opened(3, WA),
                answer("read", read(T1, "X"))],
     "arrival": [{"op": "overflow"}],
     "after": {"requests": [{"snapshot": WA}, {"read": {"state": "runtime", "key": WB}}]}},
    {"id": "C-26", "name": "c26_openedAllServed_noSnapshot",
     "readme": "The stream: The client reads the snapshot only when a state it requested is neither in `initial` nor in `deniedEvents`",
     "initial": initial(),
     "before": [subscribe("s1", "runtime", WB), subscribe("s2", "consent"), subscribe("s3", "runtime", WA), stream({"open": 1})],
     "arrival": [opened(1, WA)],
     "after": {"requests": [{"read": {"state": "runtime", "key": WB}}]}},
    {"id": "C-27", "name": "c27_openedUnserved_readsSnapshotOnce",
     "readme": "The stream: The client reads the snapshot only when a state it requested is neither in `initial` nor in `deniedEvents` (a state whose read threw on the server)",
     "initial": initial(),
     "before": [subscribe("s1", "runtime", WB), subscribe("s2", "consent"), subscribe("s3", "runtime", WA), stream({"open": 1})],
     "arrival": [opened(1, WA, initial=["state.consent.changed", "state.runtime.changed"])],
     "after": {"requests": [{"snapshot": WA}, {"read": {"state": "runtime", "key": WB}}]}},
    {"id": "C-28", "name": "c28_openedWithoutInitial_readsSnapshot",
     "readme": "The stream: — or when `initial` is absent (a server before it)",
     "initial": initial(),
     "before": [subscribe("s1", "runtime", WB), subscribe("s2", "consent"), subscribe("s3", "runtime", WA), stream({"open": 1})],
     "arrival": [opened(1, WA, initial=None)],
     "after": {"requests": [{"snapshot": WA}, {"read": {"state": "runtime", "key": WB}}]}},

    # ---- I: initial and the manifest ----------------------------------------------------
    {"id": "I-1", "name": "i1_initial_answersAtOnce",
     "readme": "createStateClient: The client installs its manifest and applies each state it holds at construction, keyed states under the query's key, so `current()` answers at once",
     "initial": initial({"consent": {"value": "A"}, "aiAccounts": {"value": "X"}}, query=WA), "arrival": [],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}, {"state": "aiAccounts", "key": WA, "entry": ready(T1, "X")}]}},
    {"id": "I-2", "name": "i2_initialOmission_isLoadingNotRevoked",
     "readme": "createStateClient: Nothing is marked 'revoked' from it: an omission in the render's snapshot is not a denial by the stream",
     "initial": initial({"consent": {"value": "A"}}, query=WA),
     "arrival": [subscribe("s1", "runtime", WA)],
     "after": {"entries": [{"state": "runtime", "key": WA, "entry": "loading"}], "deliveries": {"s1": ["loading"]}, "queries": [WA]}},
    {"id": "I-3", "name": "i3_initialKeyedWithoutQueryKey_isSkipped",
     "readme": "createStateClient: keyed states under the query's key",
     "initial": initial({"aiAccounts": {"value": "X"}}), "arrival": [],
     "after": {"entries": [{"state": "aiAccounts", "entry": "loading"}, {"state": "aiAccounts", "key": WA, "entry": "loading"}]}},
    {"id": "I-4", "name": "i4_initial_firstSubscribe_readsNoSnapshot",
     "readme": "createStateClient: the first `subscribe` opens the stream without reading the snapshot first; Connection status: a client built with `initial` too: its entries are ready, its stream is not",
     "initial": initial({"aiAccounts": {"value": "X"}}, query=WA),
     "arrival": [subscribe("s1", "aiAccounts", WA)],
     "after": {"queries": [WA], "connection": {"reconnecting": 0}, "deliveries": {"s1": [ready(T1, "X")]}}},
    {"id": "I-5", "name": "i5_initial_thenFirstFrame_ordersByAt",
     "readme": "useServerState: the stream's initial frames bring anything newer; Ordering (O-3)",
     "initial": CONSENT_A, "before": [subscribe("s1", "consent"), stream({"open": 1})],
     "arrival": [opened(1), frame("consent", T2, "A"), frame("consent", T2, "B")],
     "after": {"entries": [{"state": "consent", "entry": ready(T2, "B")}], "deliveries": {"s1": [ready(T2, "B")]}}},
    {"id": "I-6", "name": "i6_initialStateOutsideManifest_isSkipped",
     "readme": "client.ts applyPresent: A state the manifest does not describe … is skipped",
     "described": ["aiAccounts", "billing", "consent", "runtime"],
     "initial": initial({"billing": {"value": "B"}, "consent": {"value": "A"}}), "arrival": [],
     "after": {"entries": [{"state": "billing", "entry": "loading"}, {"state": "consent", "entry": ready(T1, "A")}]}},
    {"id": "I-7", "name": "i7_initial_refusedSubscribe_reportedAtOnce",
     "readme": "The manifest: Such a client knows it from construction: nothing above waits, a refused `subscribe` goes to `onError` at once",
     "initial": initial(),
     "arrival": [subscribe("s1", "aiAccounts", {"projectId": "p-1"})],
     "after": {"errors": [{"keyFields": {"state": "aiAccounts", "expected": ["workspaceId"], "given": ["projectId"]}}],
               "finished": ["s1"]}},
    {"id": "I-8", "name": "i8_noInitial_firstSubscribe_readsSnapshotFirst",
     "readme": "The manifest: The first `subscribe` — or `get`, `write`, `snapshot` — therefore reads the snapshot first: the client installs its manifest, then applies its states, then opens the stream",
     "arrival": [subscribe("s1", "aiAccounts", WA), answer("snapshot", snapshot({"aiAccounts": {"value": "X"}}, at=T1))],
     "after": {"requests": [{"snapshot": WA}], "queries": [WA], "deliveries": {"s1": ["loading", ready(T1, "X")]},
               "entries": [{"state": "aiAccounts", "key": WA, "entry": ready(T1, "X")}]}},
    {"id": "I-11", "name": "i11_writeOnReadOnlyState_refusedBeforeRequest",
     "readme": "The manifest: A `write` on a state the manifest marks `write: false` rejects naming the state, before any request",
     "initial": initial(manifest=dict(MANIFEST, consent={"key": [], "write": False})),
     "arrival": [{"op": "write", "call": "c1", "state": "consent", "input": {"choice": "B"}}],
     "after": {"calls": {"c1": {"rejected": {"readOnly": "consent"}}}}},

    # ---- W: the signed-in boundary (issue #104, §2-14 — not the README) ---------------
    {"id": "W-1", "name": "w1_firstSubject_isBaseline_clearsNothing",
     "source": ISSUE_104 + " D1: the first signed-in value is the baseline; nothing is cleared",
     "initial": CONSENT_A, "before": [subscribe("s1", "consent")],
     "arrival": [subject(CA)],
     "after": {"entries": [{"state": "consent", "entry": ready(T1, "A")}], "connections": []}},
    {"id": "W-2", "name": "w2_signedOut_subscriptionIsLoading",
     "source": ISSUE_104 + " D1, D2, D6: sign-out clears every cell; open subscriptions get loading; reconnecting 0",
     "initial": CONSENT_A, "before": signed_in_consent(),
     "arrival": [subject(None)],
     "after": {"deliveries": {"s1": ["loading"]}, "entries": [{"state": "consent", "entry": "loading"}],
               "connection": {"reconnecting": 0}, "connections": [{"reconnecting": 0}]}},
    {"id": "W-3", "name": "w3_signedOutWhileRevoked_markIsGone",
     "source": ISSUE_104 + " D2: the revoked mark goes with the value; the next account's read is ready",
     "initial": CONSENT_A, "before": signed_in_consent() + [{"op": "revoked", "state": "consent"}],
     "arrival": [subject(None), subject(CB), stream({"open": 2}), opened(2, client=CB),
                 {"op": "get", "call": "g1", "state": "consent"}, answer("read", read(T2, "B"))],
     "after": {"deliveries": {"s1": ["loading", ready(T2, "B")]}, "entries": [{"state": "consent", "entry": ready(T2, "B")}],
               "requests": [{"read": {"state": "consent", "key": {}}}], "calls": {"g1": {"frame": {"at": T2, "value": "B"}}},
               "connections": [{"reconnecting": 0}, "connected"]}},
    {"id": "W-4", "name": "w4_signedInChanged_subscribeIsLoading",
     "source": ISSUE_104 + " (Steps): after sign-out and another sign-in, subscribing the same state is loading first",
     "initial": CONSENT_A, "before": signed_in_consent() + [subject(None)],
     "arrival": [subject(CB), subscribe("s2", "consent")],
     "after": {"deliveries": {"s2": ["loading"]}, "entries": [{"state": "consent", "entry": "loading"}]}},
    {"id": "W-5", "name": "w5_directSwitch_subscriptionIsLoading",
     "source": ISSUE_104 + " D1: another id without nil between clears too (E-43 never passes idle)",
     "initial": CONSENT_A, "before": signed_in_consent(),
     "arrival": [subject(CB)],
     "after": {"deliveries": {"s1": ["loading"]}, "entries": [{"state": "consent", "entry": "loading"}],
               "connection": {"reconnecting": 0}, "connections": [{"reconnecting": 0}]}},
    {"id": "W-6", "name": "w6_frameBeforeNewConnection_isDropped",
     "source": ISSUE_104 + " D4: frames are dropped until an opened of the new id's connection",
     "initial": CONSENT_A, "before": signed_in_consent() + [subject(CB)],
     "arrival": [frame("consent", T2, "A")],
     "after": {"entries": [{"state": "consent", "entry": "loading"}]}},
    {"id": "W-7", "name": "w7_openedForPreviousAccount_isDropped",
     "source": ISSUE_104 + " D4, D6: an opened whose clientID is the previous account's opens nothing and reads nothing",
     "initial": CONSENT_A, "before": signed_in_consent() + [subject(CB)],
     "arrival": [stream({"open": 2}), opened(2, initial=None, client=CA), frame("consent", T2, "A")],
     "after": {"entries": [{"state": "consent", "entry": "loading"}], "connection": {"reconnecting": 0}, "connections": []}},
    {"id": "W-8", "name": "w8_openedForNewAccount_acceptsItsFrames",
     "source": ISSUE_104 + " D4, D6: the new id's opened is taken as today (C-26 … C-28); then connected",
     "initial": CONSENT_A, "before": signed_in_consent() + [subject(CB)],
     "arrival": [stream({"open": 2}), opened(2, client=CB), frame("consent", T2, "B")],
     "after": {"deliveries": {"s1": [ready(T2, "B")]}, "entries": [{"state": "consent", "entry": ready(T2, "B")}],
               "connection": "connected", "connections": ["connected"]}},
    {"id": "W-9", "name": "w9_snapshotOfPreviousAccount_isNotApplied",
     "source": ISSUE_104 + " D3: an automatic read sent for the previous account applies nothing and reports nothing",
     "initial": CONSENT_A,
     "before": [subject(CA), subscribe("s1", "consent"), stream({"open": 1}), opened(1, initial=None, client=CA)],
     "arrivals": [[subject(CB), answer("snapshot", snapshot({"consent": {"value": "A"}}))],
                  [subject(CB), fail("snapshot")]],
     "after": {"deliveries": {"s1": ["loading"]}, "entries": [{"state": "consent", "entry": "loading"}]}},
    {"id": "W-10", "name": "w10_shadowReadOfPreviousAccount_isNotApplied",
     "source": ISSUE_104 + " D3: a shadow read sent for the previous account applies nothing",
     "initial": initial(),
     "before": [subject(CA), subscribe("s1", "runtime", WB), subscribe("s2", "runtime", WA), stream({"open": 1}),
                opened(1, WA, client=CA)],
     "arrivals": [[subject(CB), answer("read", read(T2, "X"))],
                  [subject(CB), fail("read")]],
     "after": {"entries": [{"state": "runtime", "key": WB, "entry": "loading"}]}},
    {"id": "W-11", "name": "w11_getInFlight_refusedSignedInChanged",
     "source": ISSUE_104 + " D3: an app get in flight is refused signedInChanged; its answer is not applied",
     "initial": CONSENT_A, "before": signed_in_consent() + [{"op": "get", "call": "g1", "state": "consent"}],
     "arrival": [subject(CB), answer("read", read(T2, "A"))],
     "after": {"calls": {"g1": REFUSED}, "deliveries": {"s1": ["loading"]}, "entries": [{"state": "consent", "entry": "loading"}]}},
    {"id": "W-12", "name": "w12_writeInFlight_refusedSignedInChanged",
     "source": ISSUE_104 + " D3: an app write in flight is refused signedInChanged; its answer is not applied",
     "initial": CONSENT_A,
     "before": signed_in_consent() + [{"op": "write", "call": "c1", "state": "consent", "input": {"choice": "B"}}],
     "arrival": [subject(CB), answer("write", read(T2, "B"))],
     "after": {"calls": {"c1": REFUSED}, "deliveries": {"s1": ["loading"]}, "entries": [{"state": "consent", "entry": "loading"}]}},
    {"id": "W-13", "name": "w13_getWaitingOnManifest_refusedAtOnce",
     "source": ISSUE_104 + " D3: a call waiting on the manifest is refused at once and never sent",
     "before": [subject(CA), {"op": "get", "call": "g1", "state": "consent"}],
     "arrival": [subject(CB), answer("snapshot", snapshot({}, at=T1))],
     "after": {"calls": {"g1": REFUSED}}},
    {"id": "W-14", "name": "w14_snapshotCallInFlight_refusedSignedInChanged",
     "source": ISSUE_104 + " D3: an app snapshot() in flight is refused signedInChanged; its answer is not applied",
     "initial": CONSENT_A, "before": signed_in_consent() + [{"op": "snapshotCall", "call": "n1"}],
     "arrival": [subject(CB), answer("snapshot", snapshot({"consent": {"value": "A"}}))],
     "after": {"calls": {"n1": REFUSED}, "deliveries": {"s1": ["loading"]}, "entries": [{"state": "consent", "entry": "loading"}]}},
    {"id": "W-15", "name": "w15_newAccountsOlderAt_isApplied",
     "source": ISSUE_104 + " D2: the ordering stamps go too, so the next account's older `at` is applied",
     "initial": initial({"consent": {"value": "A"}}, at=T3),
     "before": signed_in_consent() + [subject(CB), stream({"open": 2}), opened(2, client=CB)],
     "arrival": [frame("consent", T2, "B")],
     "after": {"deliveries": {"s1": [ready(T2, "B")]}, "entries": [{"state": "consent", "entry": ready(T2, "B")}]}},
    {"id": "W-16", "name": "w16_grantOrRevokeBeforeNewConnection_isDropped",
     "source": ISSUE_104 + " D4: grant and revoke signals are dropped until an opened of the new id's connection",
     "initial": CONSENT_A, "before": signed_in_consent() + [subject(CB)],
     "arrivals": [[{"op": "revoked", "state": "consent"}], [{"op": "granted", "state": "consent"}]],
     "after": {"entries": [{"state": "consent", "entry": "loading"}]}},
    {"id": "W-17", "name": "w17_sameSubjectAgain_changesNothing",
     "source": ISSUE_104 + " D1: a rotation keeps the client id and clears nothing",
     "initial": CONSENT_A, "before": signed_in_consent(),
     "arrival": [subject(CA), frame("consent", T2, "B")],
     "after": {"deliveries": {"s1": [ready(T2, "B")]}, "entries": [{"state": "consent", "entry": ready(T2, "B")}],
               "connections": []}},
    {"id": "W-18", "name": "w18_closedClient_stillClears",
     "source": ISSUE_104 + " D1, D2: a closed client (C-19) keeps its entries, and a change clears them",
     "initial": CONSENT_A, "before": signed_in_consent() + [{"op": "close"}],
     "arrival": [subject(None), subscribe("s2", "consent")],
     "after": {"deliveries": {"s2": ["loading"]}, "entries": [{"state": "consent", "entry": "loading"}]}},
    {"id": "W-19", "name": "w19_getAfterChange_usesKeptManifest",
     "source": ISSUE_104 + " D2: the manifest is server-wide and kept; a get after the change reads no snapshot",
     "initial": CONSENT_A, "before": signed_in_consent() + [subject(CB)],
     "arrival": [{"op": "get", "call": "g1", "state": "consent"}, answer("read", read(T2, "B"))],
     "after": {"requests": [{"read": {"state": "consent", "key": {}}}], "calls": {"g1": {"frame": {"at": T2, "value": "B"}}},
               "deliveries": {"s1": [ready(T2, "B")]}, "entries": [{"state": "consent", "entry": ready(T2, "B")}]}},
]
