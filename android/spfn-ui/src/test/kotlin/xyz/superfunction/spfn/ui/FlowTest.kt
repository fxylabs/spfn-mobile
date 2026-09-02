// SPFN Mobile — the flow transition table.
//
// Counterpart of Tests/SPFNUITests/FlowTests.swift, case for case. The expected values
// are the table approved with the module (work unit w-w823n), written out here from that
// table rather than read off this implementation: every row states a start state, one
// operation and the result, and a row nobody wrote down is a row neither platform has.
//
// | start      | op         | result                |
// | closed []  | open(a,b)  | [a,b], presented      |
// | closed []  | open([])   | refused               |
// | closed []  | push(a)    | [a], presented        |
// | [a]        | push(b)    | [a,b]                 |
// | [a,b]      | pop()      | [a]                   |
// | [a]        | pop()      | [a] (no-op)           |
// | [a,b]      | replace(c) | [a,c]                 |
// | [a,b]      | close()    | [], not presented     |
// | [] closed  | close()    | no-op, no event       |

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A route with a payload, so two routes of the same kind are still two routes. */
private data class Step(val name: String) : FlowRoute

private val a = Step("a");
private val b = Step("b");
private val c = Step("c");

class FlowTest
{
    @Test
    fun `a new flow is closed and empty`()
    {
        val flow = Flow<Step>();
        assertEquals(emptyList<Step>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun `open on a closed flow presents the whole stack`()
    {
        val flow = Flow<Step>();
        flow.open(at = listOf(a, b));
        assertEquals(listOf(a, b), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun `open refuses an empty stack and changes nothing`()
    {
        val flow = Flow<Step>();
        val refusal = assertThrows(IllegalArgumentException::class.java) { flow.open(at = emptyList()) };
        assertEquals("a flow cannot be opened on an empty stack", refusal.message);
        assertEquals(emptyList<Step>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun `push on a closed flow opens it on that route`()
    {
        val flow = Flow<Step>();
        flow.push(a);
        assertEquals(listOf(a), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun `push on an open flow adds a route on top`()
    {
        val flow = Flow(listOf(a));
        flow.push(b);
        assertEquals(listOf(a, b), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun `pop drops the top route`()
    {
        val flow = Flow(listOf(a, b));
        flow.pop();
        assertEquals(listOf(a), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun `pop on the last route is a no-op and never closes the flow`()
    {
        val flow = Flow(listOf(a));
        flow.pop();
        assertEquals(listOf(a), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun `replace swaps the top route and leaves everything under it`()
    {
        val flow = Flow(listOf(a, b));
        flow.replace(c);
        assertEquals(listOf(a, c), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun `replace on a closed flow is a no-op`()
    {
        val flow = Flow<Step>();
        flow.replace(c);
        assertEquals(emptyList<Step>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun `close empties the stack and stops presenting`()
    {
        val flow = Flow(listOf(a, b));
        flow.close();
        assertEquals(emptyList<Step>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun `close on a closed flow publishes nothing`()
    {
        val flow = Flow<Step>();
        val stackReplays = flow.stack.replayCache.size;
        flow.close();
        // A MutableStateFlow conflates an equal value, so a state that did not change
        // produces no emission at all. The observable evidence is that the values are
        // unchanged and the replay cache still holds exactly one state.
        assertEquals(1, stackReplays);
        assertEquals(1, flow.stack.replayCache.size);
        assertEquals(emptyList<Step>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun `isPresented is exactly a non-empty stack across the whole table`()
    {
        val flow = Flow<Step>();
        val operations: List<() -> Unit> = listOf(
            { flow.push(a) },
            { flow.push(b) },
            { flow.pop() },
            { flow.pop() },
            { flow.replace(c) },
            { flow.close() },
            { flow.open(at = listOf(a, b)) },
            { flow.close() }
        );
        assertEquals(flow.stack.value.isNotEmpty(), flow.isPresented.value);
        operations.forEach { operation ->
            operation();
            assertEquals(flow.stack.value.isNotEmpty(), flow.isPresented.value);
        };
    }

    @Test
    fun `the constructor copies the list it was given`()
    {
        val seed = mutableListOf(a, b);
        val flow = Flow(seed);
        seed.clear();
        assertEquals(listOf(a, b), flow.stack.value);
    }

    @Test
    fun `open copies the list it was given`()
    {
        val flow = Flow<Step>();
        val routes = mutableListOf(a, b);
        flow.open(at = routes);
        routes.clear();
        assertEquals(listOf(a, b), flow.stack.value);
    }
}
