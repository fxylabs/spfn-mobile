// SPFN Mobile — the sheet's arithmetic, against vectors written by hand.
//
// Counterpart of
// android/spfn-ui/src/test/kotlin/xyz/superfunction/spfn/ui/SheetGeometryTest.kt, vector for
// vector. The numbers below were computed from the rule and typed out, not printed from this
// implementation (docs/IMPLEMENTATION-PITFALLS.md P10): a vector read off the code under
// test agrees with it by construction and says nothing about whether the rule is the
// approved one.
//
// A container of 1000 makes every expectation readable — full is 920, half is 500, the fit
// fallback is 320 — and the same 1000 is used on the other platform, so a disagreement
// between the two halves shows up as one failing line rather than as a rounding argument.

import XCTest
@testable import SPFNUI

private let epsilon = 1e-9

final class SheetGeometryTests: XCTestCase
{
    func testFullStandsAtTheFullFractionOfTheContainer()
    {
        XCTAssertEqual(SheetGeometry.height(for: .full, container: 1000, content: 0), 920, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.height(for: .full, container: 1000, content: 100), 920, accuracy: epsilon)
    }

    func testHalfStandsAtHalfTheContainer()
    {
        XCTAssertEqual(SheetGeometry.height(for: .half, container: 1000, content: 0), 500, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.height(for: .half, container: 1000, content: 900), 500, accuracy: epsilon)
    }

    func testFitTakesTheHeightItsContentMeasured()
    {
        XCTAssertEqual(SheetGeometry.height(for: .fit, container: 1000, content: 300), 300, accuracy: epsilon)
    }

    func testFitIsClampedToFullWhenItsContentIsTallerThanASheetGoes()
    {
        XCTAssertEqual(SheetGeometry.height(for: .fit, container: 1000, content: 990), 920, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.height(for: .fit, container: 1000, content: 5000), 920, accuracy: epsilon)
    }

    func testFitFallsBackWhenNothingHasBeenMeasured()
    {
        XCTAssertEqual(SheetGeometry.height(for: .fit, container: 1000, content: 0), 320, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.height(for: .fit, container: 1000, content: -1), 320, accuracy: epsilon)
    }

    // ---- fitHeight ---------------------------------------------------------
    //
    // The measured half of `fit`, and the one both platforms call with a header. A screen's
    // header does not scroll, so it is not in the measurement and is added back; 56 is what
    // `Metrics.headerHeight` is on both sides, which is why it is the number here.

    func testFitHeightAddsTheHeaderToTheContentItWasGiven()
    {
        XCTAssertEqual(SheetGeometry.fitHeight(content: 300, header: 56, max: 920), 356, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.fitHeight(content: 300, header: 0, max: 920), 300, accuracy: epsilon)
    }

    func testFitHeightIsClampedToTheCeilingItWasGiven()
    {
        XCTAssertEqual(SheetGeometry.fitHeight(content: 900, header: 56, max: 920), 920, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.fitHeight(content: 5000, header: 56, max: 920), 920, accuracy: epsilon)
    }

    /// A caller that knows no container passes an infinity, which is iOS: SwiftUI clamps a
    /// height detent to the sheet's own maximum, so this side names no second ceiling.
    func testFitHeightWithNoCeilingIsTheContentAndTheHeader()
    {
        XCTAssertEqual(SheetGeometry.fitHeight(content: 300, header: 56, max: .infinity), 356, accuracy: epsilon)
    }

    func testFitHeightAnswersZeroWhenNothingHasBeenMeasured()
    {
        XCTAssertEqual(SheetGeometry.fitHeight(content: 0, header: 56, max: 920), 0, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.fitHeight(content: -1, header: 56, max: 920), 0, accuracy: epsilon)
    }

    /// A negative header adds nothing rather than subtracting: a sheet is not made shorter
    /// than its own content by a chrome measurement that came back wrong.
    func testFitHeightIgnoresANegativeHeader()
    {
        XCTAssertEqual(SheetGeometry.fitHeight(content: 300, header: -50, max: 920), 300, accuracy: epsilon)
    }

    func testFitHeightWithNoRoomAnswersNoHeight()
    {
        XCTAssertEqual(SheetGeometry.fitHeight(content: 300, header: 56, max: 0), 0, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.fitHeight(content: 300, header: 56, max: -10), 0, accuracy: epsilon)
    }

    func testAContainerWithNoRoomGivesEveryDetentNoHeight()
    {
        XCTAssertEqual(SheetGeometry.height(for: .full, container: 0, content: 500), 0, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.height(for: .half, container: -10, content: 500), 0, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.height(for: .fit, container: 0, content: 500), 0, accuracy: epsilon)
    }

    func testADragDismissesAtTheThresholdAndNotBeforeIt()
    {
        XCTAssertFalse(SheetGeometry.closes(offset: 0, height: 400))
        XCTAssertFalse(SheetGeometry.closes(offset: 199, height: 400))
        XCTAssertTrue(SheetGeometry.closes(offset: 200, height: 400))
        XCTAssertTrue(SheetGeometry.closes(offset: 400, height: 400))
    }

    func testASheetDraggedUpNeverDismisses()
    {
        XCTAssertFalse(SheetGeometry.closes(offset: -50, height: 400))
    }

    func testASheetWithNoHeightCannotBeDismissedByADrag()
    {
        XCTAssertFalse(SheetGeometry.closes(offset: 100, height: 0))
        XCTAssertFalse(SheetGeometry.closes(offset: 100, height: -1))
    }

    func testTheScrimFadesWithTheDrag()
    {
        XCTAssertEqual(SheetGeometry.scrim(offset: 0, height: 400), 1, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.scrim(offset: 200, height: 400), 0.5, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.scrim(offset: 400, height: 400), 0, accuracy: epsilon)
    }

    func testTheScrimIsClampedAtBothEnds()
    {
        XCTAssertEqual(SheetGeometry.scrim(offset: 500, height: 400), 0, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.scrim(offset: -50, height: 400), 1, accuracy: epsilon)
        XCTAssertEqual(SheetGeometry.scrim(offset: 10, height: 0), 0, accuracy: epsilon)
    }
}
