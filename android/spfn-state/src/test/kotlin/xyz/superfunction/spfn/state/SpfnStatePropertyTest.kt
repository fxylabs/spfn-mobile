// SPFN Mobile — the store's two invariants over many random walks (state-client-design.md §4).
//
// Fixed seed, 1 000 arrivals. Each test also asserts the walk REACHED what it claims to
// cover — equal `at`s and older arrivals; a revoked mark and its lift — so a generator that
// never produced the case cannot pass vacuously (docs/IMPLEMENTATION-PITFALLS.md P46).
// Tests/SPFNStateTests/SPFNStatePropertyTests.swift is the counterpart: the same generator,
// the same seeds, the same walk.

package xyz.superfunction.spfn.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalValue

class SpfnStatePropertyTest
{
    @Test
    fun ordering_neverRegresses()
    {
        val random = Seeded(20_261_017uL);
        val store = SpfnStateStore();
        var equal = 0;
        var older = 0;
        repeat(1_000)
        {
            val before = store.stamp(SLOT);
            val arrival = arrival(random);
            equal += if (before == arrival.at) 1 else 0;
            older += if (before != null && SpfnStateOrder.isOlder(arrival.at, before)) 1 else 0;
            if (random.next(2) == 0)
            {
                store.apply(SLOT, arrival);
            }
            else
            {
                store.serve(SLOT, arrival, random.next(4));
            }
            val after = store.stamp(SLOT)!!;
            assertFalse("the stamp never goes back", before != null && SpfnStateOrder.isOlder(after, before));
            assertFalse("an entry is never newer than the stamp", SpfnStateOrder.isOlder(after, store.cells.getValue(SLOT).frame!!.at));
        };
        assertTrue("the walk reached an equal at", equal > 0);
        assertTrue("the walk reached an older arrival", older > 0);
    }

    @Test
    fun revokedLift_goesByConnection()
    {
        val random = Seeded(1_017uL);
        val store = SpfnStateStore();
        var revoked = 0;
        var lifted = 0;
        repeat(1_000)
        {
            val mark = store.mark(SLOT);
            val generation = random.next(5);
            var granted = false;
            when (random.next(4))
            {
                0 -> store.revoke(SLOT, generation)
                1 ->
                {
                    store.allow(SLOT);
                    granted = true;
                }
                2 -> store.serve(SLOT, arrival(random), generation)
                else -> store.apply(SLOT, arrival(random))
            };
            revoked += if (store.mark(SLOT) != null) 1 else 0;
            if (mark != null && store.mark(SLOT) == null)
            {
                lifted += 1;
                assertTrue("a mark is lifted by a grant or a newer connection only", granted || generation > mark);
            }
        };
        assertTrue("the walk reached revoked", revoked > 0);
        assertTrue("and lifted it", lifted > 0);
    }

    /** A linear congruential generator: the same walk on every run and both platforms. */
    private class Seeded(private var state: ULong)
    {
        fun next(bound: Int): Int
        {
            state = state * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL;
            return ((state shr 33) % bound.toULong()).toInt();
        }
    }

    private companion object
    {
        val SLOT = SpfnStateSlot("consent", emptyMap());
        val ATS = (0 until 6).map { "2026-10-17T09:00:00.00${it}Z" };

        fun arrival(random: Seeded): SpfnStateArrival
        {
            val value = SpfnCanonicalValue.Integer(random.next(3).toLong());
            return SpfnStateArrival(ATS[random.next(ATS.size)], value, null, value);
        }
    }
}
