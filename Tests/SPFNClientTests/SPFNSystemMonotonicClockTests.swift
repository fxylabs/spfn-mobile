// SPFN Mobile — the monotonic source the proof clock derives time from.
//
// Sleep itself is not reproducible in a unit test: no process can suspend the device it
// runs on. What is reproducible is the property that makes the sleep-inclusive clock the
// right one — that this implementation reads the platform's sleep-inclusive source and
// not the uptime source beside it — so the last case pins the source by comparing units
// and origin against it. The sleep half is a human procedure in tools/harness/README.md.

import Foundation
import XCTest
@testable import SPFNClient

#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

final class SPFNSystemMonotonicClockTests: XCTestCase
{
    func testTwoReadsNeverGoBackwards() throws
    {
        let clock = SPFNSystemMonotonicClock()

        let first = clock.nowNanos()
        let second = clock.nowNanos()

        XCTAssertGreaterThanOrEqual(second, first)
    }

    func testElapsedTimeTracksARealWait() throws
    {
        let clock = SPFNSystemMonotonicClock()

        let before = clock.nowNanos()
        usleep(20_000)
        let elapsedMillis = (clock.nowNanos() - before) / 1_000_000

        XCTAssertGreaterThanOrEqual(elapsedMillis, 15, "a 20ms wait must show as elapsed time")
    }

    /// Same unit and same origin as the platform's sleep-inclusive clock, which is the
    /// only way to tell it apart from the uptime clock without suspending the device.
    func testTheSourceIsThePlatformSleepInclusiveClock() throws
    {
        let clock = SPFNSystemMonotonicClock()

        #if canImport(Darwin)
        let reference = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)
        #else
        var now = timespec()
        clock_gettime(CLOCK_BOOTTIME, &now)
        let reference = UInt64(now.tv_sec) * 1_000_000_000 + UInt64(now.tv_nsec)
        #endif

        let difference = Int64(bitPattern: clock.nowNanos() &- reference)

        XCTAssertLessThan(abs(difference), 50_000_000, "a different clock would differ by its own origin")
    }
}
