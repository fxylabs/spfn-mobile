// SPFN Mobile — the arithmetic a sheet is made of, with no toolkit under it.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/SheetGeometry.kt,
// function for function and vector for vector. A sheet is the one presentation whose
// behaviour is a number rather than a rule about a list: how tall it stands, when a drag has
// gone far enough to dismiss it, and how far the scrim has faded on the way. Pulling those
// three out of the view is what lets them be tested on Linux at all — a drag is a gesture,
// but the decision a drag ends in is arithmetic, and arithmetic does not need a device.
//
// The two platforms do not draw sheets the same way and are not meant to: iOS hands the
// heights to `presentationDetents` and the system draws the sheet, Android draws its own.
// What they share is these numbers, so a `half` sheet is the same half on both.
//
// The unit is `Double` here and `Float` on the Kotlin side, which is the one place the two
// halves are spelled differently on purpose: every length SwiftUI hands out is a `CGFloat`
// and every length Compose hands out is a `Float`, and a module that converted at this
// boundary would be converting on every frame of every drag to make a comment true. The
// vectors both suites are written against are the same numbers either way.

/// Where a sheet's heights and thresholds come from.
///
/// A caseless enum rather than a struct: there is no instance of this to make, and a type
/// nobody can construct cannot be constructed by accident.
public enum SheetGeometry
{
    /// How tall a sheet stands, in the same unit `container` and `content` are given in.
    ///
    /// ``SheetDetent/fit`` is the only one that reads `content`, and it resolves through
    /// ``fitHeight(content:header:max:)`` clamped to ``SheetDetent/full``, so that a screen
    /// with more content than the sheet has room for becomes a full sheet rather than a
    /// sheet taller than the window. There is one piece of fit arithmetic and both call
    /// sites go through it; the header term is zero here because a caller with a container
    /// to measure against has already laid its header out inside the sheet.
    ///
    /// Content of zero or less means nothing has been measured yet and it falls back to
    /// ``fitFallbackFraction`` rather than to a sheet of no height.
    ///
    /// A container of zero or less has no room for a sheet at all, and every detent gives
    /// zero: a host that has not been measured yet draws nothing rather than something wrong.
    public static func height(for detent: SheetDetent, container: Double, content: Double) -> Double
    {
        guard container > 0
        else
        {
            return 0
        }
        let full = container * fullFraction
        switch detent
        {
        case .full:
            return full
        case .half:
            return container * halfFraction
        case .fit:
            return content <= 0 ? container * fitFallbackFraction : fitHeight(content: content, header: 0, max: full)
        }
    }

    /// How tall a ``SheetDetent/fit`` sheet stands once its content has been measured.
    ///
    /// `content` is the height of the CONTENT and never of the scroll view around it. A
    /// scroll view inside a sheet is as tall as the sheet, so a detent resolved from one
    /// feeds its own answer back in and never settles; the stack inside it has a natural
    /// height that does not move when the sheet does, and that is what is measured.
    ///
    /// `header` is what stands ABOVE the content and is therefore not in the measurement —
    /// a screen's header does not scroll — so its height is added back here. A negative
    /// header adds nothing rather than subtracting.
    ///
    /// `max` is the tallest this sheet may stand. Android names it, because it lays the
    /// sheet out itself and knows the container; iOS passes `.infinity`, because SwiftUI
    /// resolves the detent and clamps a height detent to the sheet's own maximum, and a
    /// ceiling invented here would be a second, smaller one.
    ///
    /// Zero means nothing has been measured yet, which is the caller's signal to stand at
    /// ``fitFallbackFraction`` rather than at no height at all.
    public static func fitHeight(content: Double, header: Double, max ceiling: Double) -> Double
    {
        guard content > 0, ceiling > 0
        else
        {
            return 0
        }
        return min(content + (header > 0 ? header : 0), ceiling)
    }

    /// Whether releasing a sheet dragged `offset` down from its resting position dismisses it.
    ///
    /// The threshold is a fraction of the sheet's own `height` rather than a fixed distance,
    /// so a short sheet is not harder to throw away than a tall one. An offset at or above
    /// the threshold dismisses; a negative offset is a sheet dragged UP past where it rests,
    /// which never dismisses. A sheet of no height cannot be dragged, so it never dismisses
    /// either — which is what stops an unmeasured host from closing a flow nobody touched.
    public static func closes(offset: Double, height: Double) -> Bool
    {
        guard height > 0
        else
        {
            return false
        }
        return offset >= height * dismissFraction
    }

    /// How opaque the scrim behind a sheet is at `offset`, from 1 at rest to 0 when the sheet
    /// has been dragged its whole `height` away.
    ///
    /// The scrim fades with the drag rather than with the dismissal, which is what makes a
    /// drag that is released short of the threshold read as a drag that did nothing.
    public static func scrim(offset: Double, height: Double) -> Double
    {
        guard height > 0
        else
        {
            return 0
        }
        return min(1, max(0, 1 - offset / height))
    }

    /// The tallest a sheet stands, as a fraction of the space it was given.
    public static let fullFraction: Double = 0.92

    /// What ``SheetDetent/half`` means, as a fraction of the space the sheet was given.
    public static let halfFraction: Double = 0.5

    /// What a ``SheetDetent/fit`` sheet stands at while its content is unmeasured.
    public static let fitFallbackFraction: Double = 0.32

    /// How much of its own height a sheet is dragged down before releasing dismisses it.
    public static let dismissFraction: Double = 0.5
}
