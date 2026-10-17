// SPFN Mobile — the observation adapters' cells that need no device (state-client-design §3-6, §4).
//
// `rememberServerState` and `rememberConnectionStatus` are a few lines over
// SpfnServerStateDriver and SpfnConnectionStatusDriver, which are plain Kotlin; these run here
// against a scripted state source (StateAdapterDoubles.kt). The `LaunchedEffect` cancellation of
// A-5 is a device cell. Sources/../Tests/SPFNEventsTests/SPFNStateAdapterTests.swift carries
// the same names.

package xyz.superfunction.spfn.events

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.client.SpfnEventStream
import xyz.superfunction.spfn.client.SpfnEventStreamConfiguration
import xyz.superfunction.spfn.state.SpfnStateClient
import xyz.superfunction.spfn.state.SpfnStateClientConfiguration
import xyz.superfunction.spfn.state.SpfnStateConnection
import xyz.superfunction.spfn.state.SpfnStateEntry

@OptIn(ExperimentalCoroutinesApi::class)
class SpfnStateAdapterTest
{
    @Test
    fun a1_cachedEntry_firstRenderIsReady() = runTest(UnconfinedTestDispatcher())
    {
        val source = ScriptedStateSource();
        source.entries["consent"] = ready("A");
        val changes = mutableListOf<SpfnStateEntry<String, Unit>>();
        val driver = SpfnServerStateDriver<String, Unit> { changes += it };
        driver.update(source, consent, emptyMap());
        assertEquals("the first frame draws the cached entry, not Loading", ready("A"), driver.entry);
        val job = launch { driver.collect() };
        source.emit("consent", ready("A"));
        advanceUntilIdle();
        assertEquals("the subscription's first element, the same entry, changes nothing", listOf(ready("A")), changes);
        job.cancel();
    }

    @Test
    fun a2_unseenKey_isLoadingUntilEntry() = runTest(UnconfinedTestDispatcher())
    {
        val source = ScriptedStateSource();
        val driver = SpfnServerStateDriver<String, Unit>();
        driver.update(source, runtime, mapOf("workspaceId" to "w-a"));
        assertEquals(SpfnStateEntry.Loading, driver.entry);
        val job = launch { driver.collect() };
        source.emit("runtime", SpfnStateEntry.Loading);
        source.emit("runtime", ready("R"));
        advanceUntilIdle();
        assertEquals(ready("R"), driver.entry);
        job.cancel();
    }

    @Test
    fun a3_equalKeyNewInstance_doesNotResubscribe()
    {
        val source = ScriptedStateSource();
        val driver = SpfnServerStateDriver<String, Unit>();
        val first = mapOf("workspaceId" to "w-a");
        val second = hashMapOf("workspaceId" to "w-" + "a");
        assertNotSame(first, second);
        driver.update(source, runtime, first);
        driver.update(source, runtime, second);
        assertEquals("one current read: the second update was the same binding", 1, source.currentReads);
    }

    @Test
    fun a4_keyChange_resubscribesCachedFirst() = runTest(UnconfinedTestDispatcher())
    {
        val source = ScriptedStateSource();
        source.entries["runtime w-b"] = ready("B");
        val driver = SpfnServerStateDriver<String, Unit>();
        driver.update(source, runtime, mapOf("workspaceId" to "w-a"));
        val old = launch { driver.collect() };
        driver.update(source, runtime, mapOf("workspaceId" to "w-b"));
        assertEquals("the new key's cached entry, at once", ready("B"), driver.entry);
        assertTrue("the old key's collection ended", old.isCancelled);
        val new = launch { driver.collect() };
        advanceUntilIdle();
        assertEquals(2, source.subscribed);
        assertEquals(1, source.ended);
        new.cancel();
    }

    @Test
    fun a5_leaving_endsSubscription() = runTest(UnconfinedTestDispatcher())
    {
        val source = ScriptedStateSource();
        val driver = SpfnServerStateDriver<String, Unit>();
        driver.update(source, consent, emptyMap());
        launch { driver.collect() };
        assertEquals(1, source.subscribed);
        driver.end();
        advanceUntilIdle();
        assertEquals(1, source.ended);
    }

    @Test
    fun a6_entryChanges_onMain()
    {
        val main = StandardTestDispatcher();
        Dispatchers.setMain(main);
        try
        {
            runTest(main)
            {
                val source = ScriptedStateSource();
                val testThread = Thread.currentThread();
                val threads = mutableListOf<Thread>();
                val driver = SpfnServerStateDriver<String, Unit> { threads += Thread.currentThread() };
                driver.update(source, consent, emptyMap());
                val job = launch(Dispatchers.Main) { driver.collect() };
                advanceUntilIdle();
                val emitter = Thread { source.emitBlocking("consent", ready("A")) };
                emitter.start();
                emitter.join();
                advanceUntilIdle();
                assertEquals(ready("A"), driver.entry);
                assertEquals("the change ran on the Main dispatcher's thread, not the emitter's", listOf(testThread), threads);
                job.cancel();
            };
        }
        finally
        {
            Dispatchers.resetMain();
        }
    }

    @Test
    fun a7_connectionStatus_currentFirst() = runTest(UnconfinedTestDispatcher())
    {
        val source = ScriptedStateSource();
        val changes = mutableListOf<SpfnStateConnection>();
        val driver = SpfnConnectionStatusDriver { changes += it };
        driver.update(source);
        assertEquals("the first frame draws connection()", SpfnStateConnection.Reconnecting(0), driver.connection);
        val job = launch { driver.collect() };
        source.connection.value = SpfnStateConnection.Connected;
        source.connection.value = SpfnStateConnection.Connected;
        advanceUntilIdle();
        assertEquals("one change; the same status is not drawn again", listOf<SpfnStateConnection>(SpfnStateConnection.Connected), changes);
        job.cancel();
    }

    /** A-8: reading a state without a host's state client is refused, not left loading. */
    @Test
    fun a8_withoutAttachment_isProgrammerError()
    {
        val thrown = assertThrows(IllegalStateException::class.java) { missingStateClient() };
        assertTrue(thrown.message.orEmpty().contains("SpfnEventStreamHost"));
    }

    @Test
    fun a9_stateClientOfOtherStream_isProgrammerError()
    {
        val scope = TestScope();
        val lifecycle = signedOutLifecycle();
        val configuration = SpfnEventStreamConfiguration(events = listOf("state.consent.changed"));
        val mine = SpfnEventStream(keyLifecycle = lifecycle, configuration = configuration, scope = scope);
        val other = SpfnEventStream(keyLifecycle = lifecycle, configuration = configuration, scope = scope);
        val client = SpfnStateClient(
            SpfnStateClientConfiguration(stream = mine, keyLifecycle = lifecycle, states = listOf(consent), snapshot = { unsentCall<Unit>() }),
            scope
        );
        spfnRequireStateClientOf(mine, client);
        val refused = assertThrows(IllegalArgumentException::class.java) { spfnRequireStateClientOf(other, client) };
        assertTrue(refused.message.orEmpty().contains("another stream"));
        assertFalse("a stream has one state client", runCatching { SpfnStateClient(client.configuration, scope) }.isSuccess);
        client.close();
    }
}
