// SPFN Mobile — which seeding a cell runs under.
//
// Counterpart of
// examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/Fixtures.kt.
//
// One `switch` over cell ids, and the only thing that installs a fake service. A launch
// that names no cell reaches `nil` here, and `ExampleApp` builds the live container
// instead — so the fixture code is inert in a build that was not asked for one rather than
// being switched off by a flag inside it.
//
// The mapping is not free-hand: `examples/ui-spec/generated/device-approval.cases.json`
// records a fixture name per cell. The Compose half has a unit test comparing the two;
// this half has none yet, which is stated rather than hidden — the Swift app has no test
// target, and adding one for a mapping the Kotlin twin already checks would be a second
// copy of the same check on a platform this repository cannot run it on.

import Foundation

/// One seeded run: what the reads answer, how long they take, and where the flow opens.
struct Fixture: Sendable
{
    /// The seeding's own name, as `device-approval.cases.json` records it.
    let name: String

    /// What each successive read does; the last entry repeats.
    let answers: [Answer]

    /// What each successive write does; the last entry repeats.
    let writeAnswers: [Answer]

    /// How long every call waits before answering. Zero for every fixture but `slow`.
    let pauseNanoseconds: UInt64

    /// The stack the flow is opened at, or `nil` to leave it on the screen the spec named
    /// as its start. Cell u14 is the one that names a whole stack.
    let openAt: [ApproveDeviceRoute]?

    /// A fresh service for one run.
    func service() -> FakeDeviceApprovalService
    {
        let waitFor = pauseNanoseconds
        return FakeDeviceApprovalService(lookupAnswers: answers, writeAnswers: writeAnswers)
        {
            guard waitFor > 0
            else
            {
                return
            }
            try? await Task.sleep(nanoseconds: waitFor)
        }
    }
}

enum Fixtures
{
    /// The code every flow types, and therefore the code a deep entry arrives holding.
    static let userCode = "ABCD-1234"

    /// How long a `slow` call waits, so a person can watch an in-flight state.
    private static let pauseNanoseconds: UInt64 = 1_500_000_000

    /// The fixture a cell runs under, or `nil` when the launch named no cell this app
    /// knows. `nil` is the fail-closed answer and it is not an error: a person who
    /// launches this app from the home screen passes no argument at all.
    static func forCell(_ cell: String) -> Fixture?
    {
        switch cell
        {
        case "u1", "u2", "u5", "u6", "u7", "u7b", "u8", "u9":
            return ready()
        case "u3", "u11":
            return slow()
        case "u8c", "u9c":
            return writeRefused()
        case "u4":
            return refused()
        case "u10", "u10b", "u13":
            return sourceRefused()
        case "u12":
            return sourceRefusedOnce()
        case "u14":
            return deepReady()
        default:
            return nil
        }
    }

    /// Every read and every write answers.
    static func ready() -> Fixture
    {
        Fixture(name: "ready", answers: [.ok], writeAnswers: [.ok], pauseNanoseconds: 0, openAt: nil)
    }

    /// Every call waits before answering, so an in-flight state can be observed.
    static func slow() -> Fixture
    {
        Fixture(
            name: "slow",
            answers: [.ok],
            writeAnswers: [.ok],
            pauseNanoseconds: pauseNanoseconds,
            openAt: nil
        )
    }

    /// Every read answers and every write refuses.
    ///
    /// It is what makes the late-answer rule observable: a write that succeeded after its
    /// flow closed changes nothing whether the guard is there or not, because closing a
    /// closed flow is a no-op. A write that failed would write an error into a screen
    /// nobody is looking at.
    static func writeRefused() -> Fixture
    {
        Fixture(name: "writeRefused", answers: [.ok], writeAnswers: [.refuse], pauseNanoseconds: 0, openAt: nil)
    }

    /// Every read refuses, so the entry screen's own call never gets as far as pushing.
    static func refused() -> Fixture
    {
        Fixture(name: "refused", answers: [.refuse], writeAnswers: [.ok], pauseNanoseconds: 0, openAt: nil)
    }

    /// The first read answers and every later one refuses: a detail screen standing in error.
    static func sourceRefused() -> Fixture
    {
        Fixture(
            name: "sourceRefused",
            answers: [.ok, .refuse],
            writeAnswers: [.ok],
            pauseNanoseconds: 0,
            openAt: nil
        )
    }

    /// The first answers, the second refuses, the third answers: a retry that recovers.
    static func sourceRefusedOnce() -> Fixture
    {
        Fixture(
            name: "sourceRefusedOnce",
            answers: [.ok, .refuse, .ok],
            writeAnswers: [.ok],
            pauseNanoseconds: 0,
            openAt: nil
        )
    }

    /// Every read answers, and the flow is opened at a whole stack rather than pushed onto.
    static func deepReady() -> Fixture
    {
        Fixture(
            name: "deepReady",
            answers: [.ok],
            writeAnswers: [.ok],
            pauseNanoseconds: 0,
            openAt: [.enterCode, .reviewDevice(userCode: userCode)]
        )
    }
}
