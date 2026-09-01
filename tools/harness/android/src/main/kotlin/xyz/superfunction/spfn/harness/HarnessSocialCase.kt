package xyz.superfunction.spfn.harness

/**
 * The five cases a device sign-in run can be, and the word each one writes into a receipt.
 *
 * The names are the shared spec's, written from the spec by hand. Neither platform derives
 * this list from the other's code, and neither derives it from a receipt a run produced —
 * a table copied out of an implementation asserts only that the implementation is
 * self-consistent (docs/IMPLEMENTATION-PITFALLS.md P10).
 *
 * Two of the five change what the app does; three do not.
 *
 * | case | what the app does differently |
 * | --- | --- |
 * | first-enroll | nothing. The person starts from an unenrolled install |
 * | re-login | nothing. The person wipes first and signs in as the same account |
 * | user-cancel | nothing. The person dismisses the provider sheet |
 * | network-failure | blocks the transport for the duration of the attempt |
 * | server-reject | damages the token after the provider issued it, so the server refuses |
 *
 * The first three are identical code paths, and that is the point: the app cannot tell a
 * first enrolment from a second one, or a dismissal from a sign-in that never started, so
 * the person declares the intent and the receipt records what actually happened under it.
 */
enum class HarnessSocialCase(val wireName: String)
{
    FIRST_ENROLL("first-enroll"),
    RE_LOGIN("re-login"),
    USER_CANCEL("user-cancel"),
    NETWORK_FAILURE("network-failure"),
    SERVER_REJECT("server-reject");

    /** True while the harness must hold the transport shut for this case. */
    val blocksNetwork: Boolean
        get() = this == NETWORK_FAILURE;

    /** True while the harness must hand the server a token it cannot verify. */
    val damagesToken: Boolean
        get() = this == SERVER_REJECT;
}
