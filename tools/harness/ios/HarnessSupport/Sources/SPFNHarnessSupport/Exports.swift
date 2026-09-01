// SPFN Mobile — the two re-exports that make this module worth importing.
//
// The harness app imports this module and gets exactly the two adapter modules, built
// with the traits this package's manifest enabled. It could have imported them by name
// instead — Xcode puts every package module in one build products directory, so the
// import would have resolved — but then the app's dependency list and the modules it can
// actually see would be two different things, and only the second one would be true.

@_exported import SPFNSocialApple
@_exported import SPFNSocialGoogle
