// SPFN Mobile — the state client's case table, cell by cell (state-client-design.md §3, §4).
//
// Every test is named for its cell, and android/spfn-state/src/test/.../SpfnStateClientTest.kt
// carries the same names without the `test_` prefix; tools/validate/validate.sh compares the
// two lists. A cell marked ○ in §4 runs its row of Contracts/fixtures/state/cases.json, which
// both suites read; the others — time, turns, closing — are written here, step by step, against
// the same decision core.

import XCTest
@testable import SPFNState
import SPFNClient
import SPFNCore

final class SPFNStateClientTests: XCTestCase
{
    static let t1 = "2026-10-17T09:00:00.001Z"
    static let t2 = "2026-10-17T09:00:00.002Z"
    static let wa: SPFNStateKey = ["workspaceId": "w-a"]
    static let consent = SPFNStateSlot(id: "consent", key: [:])

    static func snapshot(_ states: [String: String], at: String = t1) -> SPFNCanonicalValue
    {
        .object([
            "at": .string(at),
            "states": .object(states.mapValues { .object(["value": .string($0)]) }),
            "manifest": StateCases.table["manifest"]!,
        ])
    }

    static func initial(_ states: [String: String]) -> SPFNStateInitial
    {
        SPFNStateInitial(snapshot: try! SPFNStatesSnapshot(canonical: snapshot(states)))
    }

    /// A read answer; without a value it is the 2xx no decoder accepts.
    static func answer(at: String, _ value: String? = nil) -> SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = ["at": .string(at)]
        members["value"] = value.map { .string($0) }
        return .object(members)
    }

    func test_o1_noEntry_frameApplies() throws
    {
        try StateCases.run("O-1")
    }

    func test_o2_newerFrame_replacesAndNotifies() throws
    {
        try StateCases.run("O-2")
    }

    func test_o3_newerSameValue_advancesStampOnly() throws
    {
        try StateCases.run("O-3")
    }

    func test_o4_olderFrame_isDropped() throws
    {
        try StateCases.run("O-4")
    }

    func test_o5_equalAtSameValue_changesNothing() throws
    {
        try StateCases.run("O-5")
    }

    func test_o6_equalAtOtherValue_applies() throws
    {
        try StateCases.run("O-6")
    }

    func test_o7_dataChange_notifies() throws
    {
        try StateCases.run("O-7")
    }

    func test_o8_olderThanAdvancedStamp_isDropped() throws
    {
        try StateCases.run("O-8")
    }

    func test_o9_olderSnapshot_keepsNewerState_appliesOthers() throws
    {
        try StateCases.run("O-9")
    }

    func test_o10_writeAnswer_thenOwnFrame_notifiesOnce() throws
    {
        try StateCases.run("O-10")
    }

    func test_o11_frameForUnsubscribedKey_isDropped() throws
    {
        try StateCases.run("O-11")
    }

    func test_o12_atComparedAsUtf8_acrossBoundaries() throws
    {
        try StateCases.run("O-12")
    }

    func test_o13_objectKeyOrder_isNotAChange() throws
    {
        try StateCases.run("O-13")
    }

    func test_o14_arrayOrder_isAChange() throws
    {
        try StateCases.run("O-14")
    }

    func test_o15_unreadableWriteAnswer_appliesNothing() throws
    {
        let run = StateCoreRun(initial: Self.initial(["consent": "A"]))
        run.subscribe("s1", "consent")
        run.open(1)
        run.forget()
        run.write("c1", "consent")
        run.answer("write", .success(Self.answer(at: Self.t2)))
        guard case .failure? = run.result("c1")
        else
        {
            return XCTFail("an unreadable 2xx refuses the call")
        }
        XCTAssertEqual(describe(run.cells[Self.consent]), "ready(\(Self.t1), \"A\")", "nothing is applied")
        XCTAssertEqual(run.deliveries["s1"]?.count ?? 0, 0)
        run.frame("consent", at: Self.t2, .text("B"))
        XCTAssertEqual(run.deliveries["s1"]?.map(describe), ["ready(\(Self.t2), \"B\")"], "the write's own frame brings the value (O-2)")
    }

    func test_o16_failedSnapshot_changesNothing() throws
    {
        let run = StateCoreRun(initial: Self.initial(["consent": "A"]))
        run.subscribe("s1", "consent")
        run.open(1)
        run.forget()
        run.snapshot("c1", query: nil)
        run.answer("snapshot", .failure(StateTestNetworkError()))
        guard case .failure(let error)? = run.result("c1")
        else
        {
            return XCTFail("a rejected fetch rejects")
        }
        XCTAssertTrue(error is StateTestNetworkError)
        XCTAssertEqual(describe(run.cells[Self.consent]), "ready(\(Self.t1), \"A\")")
        XCTAssertEqual(run.deliveries["s1"]?.count ?? 0, 0)
        XCTAssertTrue(run.errors.isEmpty, "a call the app made rejects; onError is not told")
    }

    func test_k1_firstKeyedSubscribe_setsQuery() throws
    {
        try StateCases.run("K-1")
    }

    func test_k2_sameKeyTwice_keepsQuery() throws
    {
        try StateCases.run("K-2")
    }

    func test_k3_conflictingKey_latestCarried_shadowReadOnce() throws
    {
        try StateCases.run("K-3")
    }

    func test_k4_frameForShadowedKey_appliesUnderOrdering() throws
    {
        try StateCases.run("K-4")
    }

    func test_k5_lastSubscriberOfKey_leaves_requeries() throws
    {
        try StateCases.run("K-5")
    }

    func test_k6_sameTurnSubscriptions_setQueryOnce() throws
    {
        let run = StateCoreRun(initial: Self.initial([:]))
        run.subscribe("s1", "consent", settle: false)
        run.subscribe("s2", "aiAccounts", Self.wa, settle: false)
        run.subscribe("s3", "runtime", Self.wa, settle: false)
        XCTAssertEqual(run.queries, [], "nothing before the turn ends")
        run.settle()
        XCTAssertEqual(run.queries, [Self.wa], "one setQuery for the turn")
    }

    func test_k7_sameTurnUnsubscribeResubscribe_noReopen() throws
    {
        let run = StateCoreRun(initial: Self.initial([:]))
        run.subscribe("s1", "runtime", Self.wa)
        XCTAssertEqual(run.queries, [Self.wa])
        run.forget()
        run.unsubscribe("s1", settle: false)
        run.subscribe("s2", "runtime", Self.wa, settle: false)
        run.settle()
        XCTAssertEqual(run.queries, [], "the query is the same at the end of the turn")
        XCTAssertEqual(run.reconnects, 0)
    }

    func test_k8_wrongKeyFields_refusedNamingBothSets() throws
    {
        try StateCases.run("K-8")
    }

    func test_k9_unknownId_refused() throws
    {
        try StateCases.run("K-9")
    }

    func test_k10_wrongKeyBeforeManifest_waitsThenRefused() throws
    {
        try StateCases.run("K-10")
    }

    func test_k11_keylessEntry_ignoresQueryKeys() throws
    {
        try StateCases.run("K-11")
    }

    func test_k12_seenKey_resubscribe_showsCachedReady() throws
    {
        try StateCases.run("K-12")
    }

    func test_k13_keyedEntryWithoutQueryKey_isSkipped() throws
    {
        try StateCases.run("K-13")
    }

    func test_r1_subscriptionRevoked_marksCarriedKey() throws
    {
        try StateCases.run("R-1")
    }

    func test_r2_subscriptionRevoked_leavesShadowedKey() throws
    {
        try StateCases.run("R-2")
    }

    func test_r3_deniedAtConnect_marksRevoked() throws
    {
        try StateCases.run("R-3")
    }

    func test_r4_deniedAtConnect_leavesShadowedKey() throws
    {
        try StateCases.run("R-4")
    }

    func test_r5_snapshotOmission_marksRevoked() throws
    {
        try StateCases.run("R-5")
    }

    func test_r6_snapshotOmission_keyNotInQuery_notMarked() throws
    {
        try StateCases.run("R-6")
    }

    func test_r7_revokeBeforeValue_isRevokedAlone() throws
    {
        try StateCases.run("R-7")
    }

    func test_r8_sameConnectionFrame_suppliesValue_staysRevoked() throws
    {
        try StateCases.run("R-8")
    }

    func test_r9_olderFrameOnRevoked_isDropped_markKept() throws
    {
        try StateCases.run("R-9")
    }

    func test_r10_granted_clearsAndReadsOnce_eitherOrder() throws
    {
        try StateCases.run("R-10")
    }

    func test_r11_newerConnectionFrame_clearsMark() throws
    {
        try StateCases.run("R-11")
    }

    func test_r12_newerConnectionSnapshot_clearsMark() throws
    {
        try StateCases.run("R-12")
    }

    func test_r13_newerConnectionShadowRead_clearsMark() throws
    {
        try StateCases.run("R-13")
    }

    func test_r14_olderShadowAnswer_dropsValue_liftsMark() throws
    {
        try StateCases.run("R-14")
    }

    func test_r15_snapshotAcrossGrant_omissionNotMarked() throws
    {
        try StateCases.run("R-15")
    }

    func test_r16_snapshotOfOlderConnection_omissionNotMarked() throws
    {
        try StateCases.run("R-16")
    }

    func test_c1_startValue_isReconnectingZero() throws
    {
        try StateCases.run("C-1")
    }

    func test_c2_nothingSubscribed_isReconnectingZero() throws
    {
        try StateCases.run("C-2")
    }

    func test_c3_streamOpen_isConnected() throws
    {
        try StateCases.run("C-3")
    }

    func test_c4_streamRetrying_isReconnectingN() throws
    {
        try StateCases.run("C-4")
    }

    func test_c5_streamConnecting_keepsStatus() throws
    {
        try StateCases.run("C-5")
    }

    func test_c6_streamOffline_isReconnectingN() throws
    {
        try StateCases.run("C-6")
    }

    func test_c7_streamIdle_isReconnectingZero() throws
    {
        try StateCases.run("C-7")
    }

    func test_c8_streamClosed_isFailed() throws
    {
        try StateCases.run("C-8")
    }

    func test_c9_firstSnapshotFails_retriesEveryDelay() throws
    {
        let run = StateCoreRun()
        run.subscribe("s1", "consent")
        for attempt in 1 ... 3
        {
            run.answer("snapshot", .failure(StateTestNetworkError()))
            XCTAssertEqual(run.errors.count, attempt, "each failure goes to onError")
            XCTAssertNotNil(run.timer, "read again after retryDelay")
            XCTAssertEqual(run.connection, .reconnecting(attempt: 0))
            run.fireTimer()
        }
        XCTAssertEqual(run.requests.count, 4, "the first read and three retries")
        XCTAssertEqual(run.connections, [], "reconnecting 0 throughout")
    }

    func test_c10_maxRetriesSpent_isFailed_noTimer() throws
    {
        let run = StateCoreRun(maxRetries: 2)
        run.subscribe("s1", "consent")
        run.answer("snapshot", .failure(StateTestNetworkError()))
        run.fireTimer()
        run.answer("snapshot", .failure(StateTestNetworkError()))
        run.fireTimer()
        run.answer("snapshot", .failure(StateTestNetworkError()))
        XCTAssertEqual(run.connection, .failed, "the first read and maxRetries retries failed")
        XCTAssertNil(run.timer, "no retry is set")
        XCTAssertEqual(run.timersStarted, 2)
        XCTAssertEqual(run.requests.count, 3)
    }

    func test_c11_retryAfterSnapshotFailed_readsNow_countReset() throws
    {
        let run = StateCoreRun(maxRetries: 1)
        run.subscribe("s1", "consent")
        run.answer("snapshot", .failure(StateTestNetworkError()))
        run.fireTimer()
        run.answer("snapshot", .failure(StateTestNetworkError()))
        XCTAssertEqual(run.connection, .failed)
        run.forget()
        run.feed(.retry)
        XCTAssertEqual(run.requests.map(StateCoreRun.name(of:)), ["snapshot"], "the first snapshot, now")
        XCTAssertEqual(run.connections, [.reconnecting(attempt: 0)])
        run.answer("snapshot", .failure(StateTestNetworkError()))
        XCTAssertEqual(run.connection, .reconnecting(attempt: 0), "the count was reset: one failure is not maxRetries spent")
        XCTAssertNotNil(run.timer)
    }

    func test_c12_retryWhileSnapshotInFlight_isNoOp() throws
    {
        let run = StateCoreRun()
        run.subscribe("s1", "consent")
        run.forget()
        run.feed(.retry)
        XCTAssertEqual(run.requests.count, 0, "one in flight is not doubled")
        XCTAssertEqual(run.connections, [])
    }

    func test_c13_retryWithManifest_reconnectsNow() throws
    {
        try StateCases.run("C-13")
    }

    func test_c14_retryWhenConnectedOrUnsubscribed_isNoOp() throws
    {
        try StateCases.run("C-14")
    }

    func test_c15_keyReopenWhileConnected_staysConnected() throws
    {
        try StateCases.run("C-15")
    }

    func test_c16_subscribeWhileFailed_reconnectsSameQuery() throws
    {
        try StateCases.run("C-16")
    }

    func test_c17_unsubscribeLeavingOthers_staysFailed() throws
    {
        try StateCases.run("C-17")
    }

    func test_c18_lastUnsubscribe_resetsStatusQueryAndCount() throws
    {
        try StateCases.run("C-18")
    }

    func test_c19_closeDuringFirstSnapshot_discardsAndRejects() throws
    {
        let run = StateCoreRun()
        run.subscribe("s1", "consent")
        run.get("c1", "consent")
        XCTAssertEqual(run.requests.count, 1, "the get waits on the same first snapshot")
        run.forget()
        run.feed(.close)
        guard case .failure(SPFNStateClientError.closed)? = run.result("c1")
        else
        {
            return XCTFail("the waiting get rejects: the client was closed")
        }
        XCTAssertEqual(run.finished, ["s1"])
        XCTAssertEqual(run.connection, .reconnecting(attempt: 0))
        XCTAssertEqual(run.wire, ["detach"])
        run.answer("snapshot", .success(Self.snapshot(["consent": "A"])))
        XCTAssertNil(run.cells[Self.consent], "the answer is discarded")
        run.forget()
        run.subscribe("s2", "consent")
        XCTAssertEqual(run.wire, ["attach"], "a subscribe after close starts again")
        XCTAssertEqual(run.requests.map(StateCoreRun.name(of:)), ["snapshot"])
    }

    func test_c20_leavingOffline_rereadsFirstSnapshotNow() throws
    {
        let run = StateCoreRun()
        run.subscribe("s1", "consent")
        run.answer("snapshot", .failure(StateTestNetworkError()))
        XCTAssertNotNil(run.timer)
        run.stream(.offline(attempt: 1))
        run.forget()
        run.stream(.connecting(attempt: 1))
        XCTAssertEqual(run.requests.map(StateCoreRun.name(of:)), ["snapshot"], "the first snapshot, now")
        XCTAssertNil(run.timer, "its timer is cleared")
        run.answer("snapshot", .failure(StateTestNetworkError()))
        XCTAssertEqual(run.connection, .reconnecting(attempt: 0))
    }

    func test_c21_leavingOfflineWithManifest_doesNothing() throws
    {
        try StateCases.run("C-21")
    }

    func test_c22_subscribeConnection_yieldsCurrentFirst() throws
    {
        try StateCases.run("C-22")
    }

    func test_c23_sameStatus_isNotRepeated() throws
    {
        try StateCases.run("C-23")
    }

    func test_c24_unavailableStateEvent_reportedOnce_rereadEachOpen() throws
    {
        try StateCases.run("C-24")
    }

    func test_c25_overflow_rereadsSnapshotAndShadows() throws
    {
        try StateCases.run("C-25")
    }

    func test_c26_openedAllServed_noSnapshot() throws
    {
        try StateCases.run("C-26")
    }

    func test_c27_openedUnserved_readsSnapshotOnce() throws
    {
        try StateCases.run("C-27")
    }

    func test_c28_openedWithoutInitial_readsSnapshot() throws
    {
        try StateCases.run("C-28")
    }

    func test_i1_initial_answersAtOnce() throws
    {
        try StateCases.run("I-1")
    }

    func test_i2_initialOmission_isLoadingNotRevoked() throws
    {
        try StateCases.run("I-2")
    }

    func test_i3_initialKeyedWithoutQueryKey_isSkipped() throws
    {
        try StateCases.run("I-3")
    }

    func test_i4_initial_firstSubscribe_readsNoSnapshot() throws
    {
        try StateCases.run("I-4")
    }

    func test_i5_initial_thenFirstFrame_ordersByAt() throws
    {
        try StateCases.run("I-5")
    }

    func test_i6_initialStateOutsideManifest_isSkipped() throws
    {
        try StateCases.run("I-6")
    }

    func test_i7_initial_refusedSubscribe_reportedAtOnce() throws
    {
        try StateCases.run("I-7")
    }

    func test_i8_noInitial_firstSubscribe_readsSnapshotFirst() throws
    {
        try StateCases.run("I-8")
    }

    func test_i9_subscribeDuringFirstSnapshot_notReadTwice() throws
    {
        let run = StateCoreRun()
        run.subscribe("s1", "consent")
        run.subscribe("s2", "aiAccounts", Self.wa)
        XCTAssertEqual(run.requests.count, 1, "served by the read in flight, not a second")
        run.answer("snapshot", .success(Self.snapshot(["consent": "A"])))
        XCTAssertEqual(run.deliveries["s1"]?.map(describe), ["loading", "ready(\(Self.t1), \"A\")"])
        XCTAssertEqual(run.deliveries["s2"]?.map(describe), ["loading"])
        XCTAssertEqual(run.queries, [Self.wa])
    }

    func test_i10_getBringsManifest_servesWaitingSubscriptions() throws
    {
        let run = StateCoreRun()
        run.subscribe("s1", "consent")
        run.answer("snapshot", .failure(StateTestNetworkError()))
        XCTAssertNotNil(run.timer, "the subscription waits on the retry")
        run.forget()
        run.get("c1", "consent")
        XCTAssertEqual(run.requests.map(StateCoreRun.name(of:)), ["snapshot"], "the get reads the first snapshot")
        run.answer("snapshot", .success(Self.snapshot(["consent": "A"])))
        XCTAssertEqual(run.deliveries["s1"]?.map(describe), ["ready(\(Self.t1), \"A\")"], "the waiting subscription is served")
        XCTAssertNil(run.timer)
        XCTAssertEqual(run.requests.map(StateCoreRun.name(of:)), ["snapshot", "read"], "then the get's own read")
        run.answer("read", .success(Self.answer(at: Self.t2, "B")))
        XCTAssertNotNil(try run.result("c1")?.get())
    }

    func test_i11_writeOnReadOnlyState_refusedBeforeRequest() throws
    {
        try StateCases.run("I-11")
    }

    func test_i12_failedFirstSnapshot_rejectsCallingGet() throws
    {
        let run = StateCoreRun()
        run.get("c1", "consent")
        run.answer("snapshot", .failure(StateTestNetworkError()))
        guard case .failure? = run.result("c1")
        else
        {
            return XCTFail("the get rejects")
        }
        XCTAssertTrue(run.errors.isEmpty, "it went to the call that needed it, not onError")
        XCTAssertNil(run.timer, "nothing waits")
        run.subscribe("s1", "consent")
        run.get("c2", "consent")
        run.answer("snapshot", .failure(StateTestNetworkError()))
        guard case .failure? = run.result("c2")
        else
        {
            return XCTFail("the get rejects")
        }
        XCTAssertEqual(run.errors.count, 1, "the waiting subscription follows C-9")
        XCTAssertNotNil(run.timer)
    }

    // MARK: - W: the signed-in boundary (§2-14, issue #104)

    func test_w1_firstSubject_isBaseline_clearsNothing() throws
    {
        try StateCases.run("W-1")
    }

    func test_w2_signedOut_subscriptionIsLoading() throws
    {
        try StateCases.run("W-2")
    }

    func test_w3_signedOutWhileRevoked_markIsGone() throws
    {
        try StateCases.run("W-3")
    }

    func test_w4_signedInChanged_subscribeIsLoading() throws
    {
        try StateCases.run("W-4")
    }

    func test_w5_directSwitch_subscriptionIsLoading() throws
    {
        try StateCases.run("W-5")
    }

    func test_w6_frameBeforeNewConnection_isDropped() throws
    {
        try StateCases.run("W-6")
    }

    func test_w7_openedForPreviousAccount_isDropped() throws
    {
        try StateCases.run("W-7")
    }

    func test_w8_openedForNewAccount_acceptsItsFrames() throws
    {
        try StateCases.run("W-8")
    }

    func test_w9_snapshotOfPreviousAccount_isNotApplied() throws
    {
        try StateCases.run("W-9")
    }

    func test_w10_shadowReadOfPreviousAccount_isNotApplied() throws
    {
        try StateCases.run("W-10")
    }

    func test_w11_getInFlight_refusedSignedInChanged() throws
    {
        try StateCases.run("W-11")
    }

    func test_w12_writeInFlight_refusedSignedInChanged() throws
    {
        try StateCases.run("W-12")
    }

    func test_w13_getWaitingOnManifest_refusedAtOnce() throws
    {
        try StateCases.run("W-13")
    }

    func test_w14_snapshotCallInFlight_refusedSignedInChanged() throws
    {
        try StateCases.run("W-14")
    }

    func test_w15_newAccountsOlderAt_isApplied() throws
    {
        try StateCases.run("W-15")
    }

    func test_w16_grantOrRevokeBeforeNewConnection_isDropped() throws
    {
        try StateCases.run("W-16")
    }

    func test_w17_sameSubjectAgain_changesNothing() throws
    {
        try StateCases.run("W-17")
    }

    func test_w18_closedClient_stillClears() throws
    {
        try StateCases.run("W-18")
    }

    func test_w19_getAfterChange_usesKeptManifest() throws
    {
        try StateCases.run("W-19")
    }
}
