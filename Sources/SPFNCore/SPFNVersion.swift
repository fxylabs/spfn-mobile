// SPFN Mobile — Step 1 scaffold. No behaviour, no release promise.

/// The single mobile release-train version.
///
/// `SPFNVersion.current` must equal the repository `VERSION` file byte-for-byte
/// (trimmed). `tools/validate/validate.sh` and `SPFNCoreTests` both enforce this.
public enum SPFNVersion
{
    /// Release-train start version per decision D9 (2026-08-01). Pre-release:
    /// nothing has been tagged or published, and 1.0.0 waits on Step 5 evidence.
    public static let current: String = "0.1.0-alpha.3"
}
