// SPFN Mobile — the state client's case table, cell by cell (state-client-design.md §3, §4).
//
// Every test is named for its cell, and Tests/SPFNStateTests/SPFNStateClientTests.swift
// carries the same names with the `test_` prefix; tools/validate/validate.sh compares the
// two lists. A cell marked ○ in §4 runs its row of Contracts/fixtures/state/cases.json, which
// both suites read; the others — time, turns, closing — are written here, step by step, against
// the same decision core.

package xyz.superfunction.spfn.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.client.SpfnEventStreamState
import xyz.superfunction.spfn.core.SpfnCanonicalValue

class SpfnStateClientTest
{
    @Test
    fun o1_noEntry_frameApplies()
    {
        StateCases.run("O-1");
    }

    @Test
    fun o2_newerFrame_replacesAndNotifies()
    {
        StateCases.run("O-2");
    }

    @Test
    fun o3_newerSameValue_advancesStampOnly()
    {
        StateCases.run("O-3");
    }

    @Test
    fun o4_olderFrame_isDropped()
    {
        StateCases.run("O-4");
    }

    @Test
    fun o5_equalAtSameValue_changesNothing()
    {
        StateCases.run("O-5");
    }

    @Test
    fun o6_equalAtOtherValue_applies()
    {
        StateCases.run("O-6");
    }

    @Test
    fun o7_dataChange_notifies()
    {
        StateCases.run("O-7");
    }

    @Test
    fun o8_olderThanAdvancedStamp_isDropped()
    {
        StateCases.run("O-8");
    }

    @Test
    fun o9_olderSnapshot_keepsNewerState_appliesOthers()
    {
        StateCases.run("O-9");
    }

    @Test
    fun o10_writeAnswer_thenOwnFrame_notifiesOnce()
    {
        StateCases.run("O-10");
    }

    @Test
    fun o11_frameForUnsubscribedKey_isDropped()
    {
        StateCases.run("O-11");
    }

    @Test
    fun o12_atComparedAsUtf8_acrossBoundaries()
    {
        StateCases.run("O-12");
    }

    @Test
    fun o13_objectKeyOrder_isNotAChange()
    {
        StateCases.run("O-13");
    }

    @Test
    fun o14_arrayOrder_isAChange()
    {
        StateCases.run("O-14");
    }

    @Test
    fun o15_unreadableWriteAnswer_appliesNothing()
    {
        val run = StateCoreRun(initial = initial(mapOf("consent" to "A")));
        run.subscribe("s1", "consent");
        run.open(1);
        run.forget();
        run.write("c1", "consent");
        run.answer("write", Result.success(answer(T2)));
        assertTrue("an unreadable 2xx refuses the call", run.result("c1")?.isFailure == true);
        assertEquals("nothing is applied", "ready($T1, \"A\")", describe(run.cells[CONSENT]));
        assertEquals(0, run.deliveries["s1"].orEmpty().size);
        run.frame("consent", T2, text("B"));
        assertEquals("the write's own frame brings the value (O-2)", listOf("ready($T2, \"B\")"), run.deliveries["s1"]?.map { describe(it) });
    }

    @Test
    fun o16_failedSnapshot_changesNothing()
    {
        val run = StateCoreRun(initial = initial(mapOf("consent" to "A")));
        run.subscribe("s1", "consent");
        run.open(1);
        run.forget();
        run.snapshot("c1", query = null);
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        assertTrue("a rejected fetch rejects", run.result("c1")?.exceptionOrNull() is StateTestNetworkError);
        assertEquals("ready($T1, \"A\")", describe(run.cells[CONSENT]));
        assertEquals(0, run.deliveries["s1"].orEmpty().size);
        assertTrue("a call the app made rejects; onError is not told", run.errors.isEmpty());
    }

    @Test
    fun k1_firstKeyedSubscribe_setsQuery()
    {
        StateCases.run("K-1");
    }

    @Test
    fun k2_sameKeyTwice_keepsQuery()
    {
        StateCases.run("K-2");
    }

    @Test
    fun k3_conflictingKey_latestCarried_shadowReadOnce()
    {
        StateCases.run("K-3");
    }

    @Test
    fun k4_frameForShadowedKey_appliesUnderOrdering()
    {
        StateCases.run("K-4");
    }

    @Test
    fun k5_lastSubscriberOfKey_leaves_requeries()
    {
        StateCases.run("K-5");
    }

    @Test
    fun k6_sameTurnSubscriptions_setQueryOnce()
    {
        val run = StateCoreRun(initial = initial(emptyMap()));
        run.subscribe("s1", "consent", settle = false);
        run.subscribe("s2", "aiAccounts", WA, settle = false);
        run.subscribe("s3", "runtime", WA, settle = false);
        assertEquals("nothing before the turn ends", emptyList<SpfnStateKey>(), run.queries);
        run.settle();
        assertEquals("one setQuery for the turn", listOf(WA), run.queries);
    }

    @Test
    fun k7_sameTurnUnsubscribeResubscribe_noReopen()
    {
        val run = StateCoreRun(initial = initial(emptyMap()));
        run.subscribe("s1", "runtime", WA);
        assertEquals(listOf(WA), run.queries);
        run.forget();
        run.unsubscribe("s1", settle = false);
        run.subscribe("s2", "runtime", WA, settle = false);
        run.settle();
        assertEquals("the query is the same at the end of the turn", emptyList<SpfnStateKey>(), run.queries);
        assertEquals(0, run.reconnects);
    }

    @Test
    fun k8_wrongKeyFields_refusedNamingBothSets()
    {
        StateCases.run("K-8");
    }

    @Test
    fun k9_unknownId_refused()
    {
        StateCases.run("K-9");
    }

    @Test
    fun k10_wrongKeyBeforeManifest_waitsThenRefused()
    {
        StateCases.run("K-10");
    }

    @Test
    fun k11_keylessEntry_ignoresQueryKeys()
    {
        StateCases.run("K-11");
    }

    @Test
    fun k12_seenKey_resubscribe_showsCachedReady()
    {
        StateCases.run("K-12");
    }

    @Test
    fun k13_keyedEntryWithoutQueryKey_isSkipped()
    {
        StateCases.run("K-13");
    }

    @Test
    fun r1_subscriptionRevoked_marksCarriedKey()
    {
        StateCases.run("R-1");
    }

    @Test
    fun r2_subscriptionRevoked_leavesShadowedKey()
    {
        StateCases.run("R-2");
    }

    @Test
    fun r3_deniedAtConnect_marksRevoked()
    {
        StateCases.run("R-3");
    }

    @Test
    fun r4_deniedAtConnect_leavesShadowedKey()
    {
        StateCases.run("R-4");
    }

    @Test
    fun r5_snapshotOmission_marksRevoked()
    {
        StateCases.run("R-5");
    }

    @Test
    fun r6_snapshotOmission_keyNotInQuery_notMarked()
    {
        StateCases.run("R-6");
    }

    @Test
    fun r7_revokeBeforeValue_isRevokedAlone()
    {
        StateCases.run("R-7");
    }

    @Test
    fun r8_sameConnectionFrame_suppliesValue_staysRevoked()
    {
        StateCases.run("R-8");
    }

    @Test
    fun r9_olderFrameOnRevoked_isDropped_markKept()
    {
        StateCases.run("R-9");
    }

    @Test
    fun r10_granted_clearsAndReadsOnce_eitherOrder()
    {
        StateCases.run("R-10");
    }

    @Test
    fun r11_newerConnectionFrame_clearsMark()
    {
        StateCases.run("R-11");
    }

    @Test
    fun r12_newerConnectionSnapshot_clearsMark()
    {
        StateCases.run("R-12");
    }

    @Test
    fun r13_newerConnectionShadowRead_clearsMark()
    {
        StateCases.run("R-13");
    }

    @Test
    fun r14_olderShadowAnswer_dropsValue_liftsMark()
    {
        StateCases.run("R-14");
    }

    @Test
    fun r15_snapshotAcrossGrant_omissionNotMarked()
    {
        StateCases.run("R-15");
    }

    @Test
    fun r16_snapshotOfOlderConnection_omissionNotMarked()
    {
        StateCases.run("R-16");
    }

    @Test
    fun c1_startValue_isReconnectingZero()
    {
        StateCases.run("C-1");
    }

    @Test
    fun c2_nothingSubscribed_isReconnectingZero()
    {
        StateCases.run("C-2");
    }

    @Test
    fun c3_streamOpen_isConnected()
    {
        StateCases.run("C-3");
    }

    @Test
    fun c4_streamRetrying_isReconnectingN()
    {
        StateCases.run("C-4");
    }

    @Test
    fun c5_streamConnecting_keepsStatus()
    {
        StateCases.run("C-5");
    }

    @Test
    fun c6_streamOffline_isReconnectingN()
    {
        StateCases.run("C-6");
    }

    @Test
    fun c7_streamIdle_isReconnectingZero()
    {
        StateCases.run("C-7");
    }

    @Test
    fun c8_streamClosed_isFailed()
    {
        StateCases.run("C-8");
    }

    @Test
    fun c9_firstSnapshotFails_retriesEveryDelay()
    {
        val run = StateCoreRun();
        run.subscribe("s1", "consent");
        for (attempt in 1..3)
        {
            run.answer("snapshot", Result.failure(StateTestNetworkError()));
            assertEquals("each failure goes to onError", attempt, run.errors.size);
            assertNotNull("read again after retryDelay", run.timer);
            assertEquals(SpfnStateConnection.Reconnecting(0), run.connection);
            run.fireTimer();
        }
        assertEquals("the first read and three retries", 4, run.requests.size);
        assertEquals("reconnecting 0 throughout", emptyList<SpfnStateConnection>(), run.connections);
    }

    @Test
    fun c10_maxRetriesSpent_isFailed_noTimer()
    {
        val run = StateCoreRun(maxRetries = 2);
        run.subscribe("s1", "consent");
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        run.fireTimer();
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        run.fireTimer();
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        assertEquals("the first read and maxRetries retries failed", SpfnStateConnection.Failed, run.connection);
        assertNull("no retry is set", run.timer);
        assertEquals(2, run.timersStarted);
        assertEquals(3, run.requests.size);
    }

    @Test
    fun c11_retryAfterSnapshotFailed_readsNow_countReset()
    {
        val run = StateCoreRun(maxRetries = 1);
        run.subscribe("s1", "consent");
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        run.fireTimer();
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        assertEquals(SpfnStateConnection.Failed, run.connection);
        run.forget();
        run.feed(SpfnStateInput.Retry);
        assertEquals("the first snapshot, now", listOf("snapshot"), run.requests.map { StateCoreRun.name(it) });
        assertEquals(listOf<SpfnStateConnection>(SpfnStateConnection.Reconnecting(0)), run.connections);
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        assertEquals("the count was reset: one failure is not maxRetries spent", SpfnStateConnection.Reconnecting(0), run.connection);
        assertNotNull(run.timer);
    }

    @Test
    fun c12_retryWhileSnapshotInFlight_isNoOp()
    {
        val run = StateCoreRun();
        run.subscribe("s1", "consent");
        run.forget();
        run.feed(SpfnStateInput.Retry);
        assertEquals("one in flight is not doubled", 0, run.requests.size);
        assertEquals(emptyList<SpfnStateConnection>(), run.connections);
    }

    @Test
    fun c13_retryWithManifest_reconnectsNow()
    {
        StateCases.run("C-13");
    }

    @Test
    fun c14_retryWhenConnectedOrUnsubscribed_isNoOp()
    {
        StateCases.run("C-14");
    }

    @Test
    fun c15_keyReopenWhileConnected_staysConnected()
    {
        StateCases.run("C-15");
    }

    @Test
    fun c16_subscribeWhileFailed_reconnectsSameQuery()
    {
        StateCases.run("C-16");
    }

    @Test
    fun c17_unsubscribeLeavingOthers_staysFailed()
    {
        StateCases.run("C-17");
    }

    @Test
    fun c18_lastUnsubscribe_resetsStatusQueryAndCount()
    {
        StateCases.run("C-18");
    }

    @Test
    fun c19_closeDuringFirstSnapshot_discardsAndRejects()
    {
        val run = StateCoreRun();
        run.subscribe("s1", "consent");
        run.get("c1", "consent");
        assertEquals("the get waits on the same first snapshot", 1, run.requests.size);
        run.forget();
        run.feed(SpfnStateInput.Close);
        assertEquals("the waiting get rejects: the client was closed", SpfnStateClientError.Closed, run.result("c1")?.exceptionOrNull());
        assertEquals(listOf("s1"), run.finished);
        assertEquals(SpfnStateConnection.Reconnecting(0), run.connection);
        assertEquals(listOf("detach"), run.wire);
        run.answer("snapshot", Result.success(snapshot(mapOf("consent" to "A"))));
        assertNull("the answer is discarded", run.cells[CONSENT]);
        run.forget();
        run.subscribe("s2", "consent");
        assertEquals("a subscribe after close starts again", listOf("attach"), run.wire);
        assertEquals(listOf("snapshot"), run.requests.map { StateCoreRun.name(it) });
    }

    @Test
    fun c20_leavingOffline_rereadsFirstSnapshotNow()
    {
        val run = StateCoreRun();
        run.subscribe("s1", "consent");
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        assertNotNull(run.timer);
        run.stream(SpfnEventStreamState.Offline(1));
        run.forget();
        run.stream(SpfnEventStreamState.Connecting(1));
        assertEquals("the first snapshot, now", listOf("snapshot"), run.requests.map { StateCoreRun.name(it) });
        assertNull("its timer is cleared", run.timer);
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        assertEquals(SpfnStateConnection.Reconnecting(0), run.connection);
    }

    @Test
    fun c21_leavingOfflineWithManifest_doesNothing()
    {
        StateCases.run("C-21");
    }

    @Test
    fun c22_subscribeConnection_yieldsCurrentFirst()
    {
        StateCases.run("C-22");
    }

    @Test
    fun c23_sameStatus_isNotRepeated()
    {
        StateCases.run("C-23");
    }

    @Test
    fun c24_unavailableStateEvent_reportedOnce_rereadEachOpen()
    {
        StateCases.run("C-24");
    }

    @Test
    fun c25_overflow_rereadsSnapshotAndShadows()
    {
        StateCases.run("C-25");
    }

    @Test
    fun c26_openedAllServed_noSnapshot()
    {
        StateCases.run("C-26");
    }

    @Test
    fun c27_openedUnserved_readsSnapshotOnce()
    {
        StateCases.run("C-27");
    }

    @Test
    fun c28_openedWithoutInitial_readsSnapshot()
    {
        StateCases.run("C-28");
    }

    @Test
    fun i1_initial_answersAtOnce()
    {
        StateCases.run("I-1");
    }

    @Test
    fun i2_initialOmission_isLoadingNotRevoked()
    {
        StateCases.run("I-2");
    }

    @Test
    fun i3_initialKeyedWithoutQueryKey_isSkipped()
    {
        StateCases.run("I-3");
    }

    @Test
    fun i4_initial_firstSubscribe_readsNoSnapshot()
    {
        StateCases.run("I-4");
    }

    @Test
    fun i5_initial_thenFirstFrame_ordersByAt()
    {
        StateCases.run("I-5");
    }

    @Test
    fun i6_initialStateOutsideManifest_isSkipped()
    {
        StateCases.run("I-6");
    }

    @Test
    fun i7_initial_refusedSubscribe_reportedAtOnce()
    {
        StateCases.run("I-7");
    }

    @Test
    fun i8_noInitial_firstSubscribe_readsSnapshotFirst()
    {
        StateCases.run("I-8");
    }

    @Test
    fun i9_subscribeDuringFirstSnapshot_notReadTwice()
    {
        val run = StateCoreRun();
        run.subscribe("s1", "consent");
        run.subscribe("s2", "aiAccounts", WA);
        assertEquals("served by the read in flight, not a second", 1, run.requests.size);
        run.answer("snapshot", Result.success(snapshot(mapOf("consent" to "A"))));
        assertEquals(listOf("loading", "ready($T1, \"A\")"), run.deliveries["s1"]?.map { describe(it) });
        assertEquals(listOf("loading"), run.deliveries["s2"]?.map { describe(it) });
        assertEquals(listOf(WA), run.queries);
    }

    @Test
    fun i10_getBringsManifest_servesWaitingSubscriptions()
    {
        val run = StateCoreRun();
        run.subscribe("s1", "consent");
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        assertNotNull("the subscription waits on the retry", run.timer);
        run.forget();
        run.get("c1", "consent");
        assertEquals("the get reads the first snapshot", listOf("snapshot"), run.requests.map { StateCoreRun.name(it) });
        run.answer("snapshot", Result.success(snapshot(mapOf("consent" to "A"))));
        assertEquals("the waiting subscription is served", listOf("ready($T1, \"A\")"), run.deliveries["s1"]?.map { describe(it) });
        assertNull(run.timer);
        assertEquals("then the get's own read", listOf("snapshot", "read"), run.requests.map { StateCoreRun.name(it) });
        run.answer("read", Result.success(answer(T2, "B")));
        assertNotNull(run.result("c1")?.getOrThrow());
    }

    @Test
    fun i11_writeOnReadOnlyState_refusedBeforeRequest()
    {
        StateCases.run("I-11");
    }

    @Test
    fun i12_failedFirstSnapshot_rejectsCallingGet()
    {
        val run = StateCoreRun();
        run.get("c1", "consent");
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        assertTrue("the get rejects", run.result("c1")?.isFailure == true);
        assertTrue("it went to the call that needed it, not onError", run.errors.isEmpty());
        assertNull("nothing waits", run.timer);
        run.subscribe("s1", "consent");
        run.get("c2", "consent");
        run.answer("snapshot", Result.failure(StateTestNetworkError()));
        assertTrue("the get rejects", run.result("c2")?.isFailure == true);
        assertEquals("the waiting subscription follows C-9", 1, run.errors.size);
        assertNotNull(run.timer);
    }

    // ---- W: the signed-in boundary (§2-14, issue #104) ---------------------

    @Test
    fun w1_firstSubject_isBaseline_clearsNothing()
    {
        StateCases.run("W-1");
    }

    @Test
    fun w2_signedOut_subscriptionIsLoading()
    {
        StateCases.run("W-2");
    }

    @Test
    fun w3_signedOutWhileRevoked_markIsGone()
    {
        StateCases.run("W-3");
    }

    @Test
    fun w4_signedInChanged_subscribeIsLoading()
    {
        StateCases.run("W-4");
    }

    @Test
    fun w5_directSwitch_subscriptionIsLoading()
    {
        StateCases.run("W-5");
    }

    @Test
    fun w6_frameBeforeNewConnection_isDropped()
    {
        StateCases.run("W-6");
    }

    @Test
    fun w7_openedForPreviousAccount_isDropped()
    {
        StateCases.run("W-7");
    }

    @Test
    fun w8_openedForNewAccount_acceptsItsFrames()
    {
        StateCases.run("W-8");
    }

    @Test
    fun w9_snapshotOfPreviousAccount_isNotApplied()
    {
        StateCases.run("W-9");
    }

    @Test
    fun w10_shadowReadOfPreviousAccount_isNotApplied()
    {
        StateCases.run("W-10");
    }

    @Test
    fun w11_getInFlight_refusedSignedInChanged()
    {
        StateCases.run("W-11");
    }

    @Test
    fun w12_writeInFlight_refusedSignedInChanged()
    {
        StateCases.run("W-12");
    }

    @Test
    fun w13_getWaitingOnManifest_refusedAtOnce()
    {
        StateCases.run("W-13");
    }

    @Test
    fun w14_snapshotCallInFlight_refusedSignedInChanged()
    {
        StateCases.run("W-14");
    }

    @Test
    fun w15_newAccountsOlderAt_isApplied()
    {
        StateCases.run("W-15");
    }

    @Test
    fun w16_grantOrRevokeBeforeNewConnection_isDropped()
    {
        StateCases.run("W-16");
    }

    @Test
    fun w17_sameSubjectAgain_changesNothing()
    {
        StateCases.run("W-17");
    }

    @Test
    fun w18_closedClient_stillClears()
    {
        StateCases.run("W-18");
    }

    @Test
    fun w19_getAfterChange_usesKeptManifest()
    {
        StateCases.run("W-19");
    }

    private companion object
    {
        const val T1 = "2026-10-17T09:00:00.001Z";
        const val T2 = "2026-10-17T09:00:00.002Z";
        val WA: SpfnStateKey = mapOf("workspaceId" to "w-a");
        val CONSENT = SpfnStateSlot("consent", emptyMap());

        fun snapshot(states: Map<String, String>, at: String = T1): SpfnCanonicalValue =
            SpfnCanonicalValue.Obj(
                mapOf(
                    "at" to text(at),
                    "states" to SpfnCanonicalValue.Obj(states.mapValues { SpfnCanonicalValue.Obj(mapOf("value" to text(it.value))) }),
                    "manifest" to StateCases.table.getValue("manifest")
                )
            );

        fun initial(states: Map<String, String>): SpfnStateInitial = SpfnStateInitial(SpfnStatesSnapshot.decode(snapshot(states)));

        /** A read answer; without a value it is the 2xx no decoder accepts. */
        fun answer(at: String, value: String? = null): SpfnCanonicalValue =
            SpfnCanonicalValue.Obj(if (value == null) mapOf("at" to text(at)) else mapOf("at" to text(at), "value" to text(value)));
    }
}
