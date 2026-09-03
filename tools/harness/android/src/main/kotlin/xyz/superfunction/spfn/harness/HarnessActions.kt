package xyz.superfunction.spfn.harness

/**
 * One tappable thing on the screen: how a flow finds it, what a person reads, and what it
 * runs.
 *
 * The tag is the string a Maestro `id:` selector matches. With
 * `testTagsAsResourceId = true` set on the root, a Compose test tag IS the Android
 * resource id a runner sees, so these are the twenty-one names
 * `src/main/res/values/ids.xml` used to declare — the same strings, now attached where the
 * control is written instead of in a file it had to be looked up from.
 *
 * The `btn_` prefix is not decoration and it is preserved for the reason it was chosen: a
 * flow's selector is a REGEX, so an id that is a substring of another matches both. Plain
 * `revoke` would also match `note_revoked`, and `btn_enroll` is deliberately not a
 * substring of `btn_case_first_enroll`.
 *
 * [title] is what a person reads on the phone, lowercase and uncapitalised, which is the
 * same word tools/harness/flows/ and the README name.
 */
class HarnessAction(val tag: String, val title: String, val run: () -> Unit);

/**
 * Everything the screen can do, handed to it by the Activity that owns the model.
 *
 * The composables take this rather than the model itself. What a tap has to do here is
 * more than call a method — it sets the busy flag synchronously, moves the work to the IO
 * dispatcher, restores the flag in a `finally` and announces the result — and all of that
 * belongs to the Activity that owns the coroutine scope it runs in. A screen that reached
 * for the model directly would have to own a second copy of those rules.
 */
class HarnessActions(
    /** Choosing a case is instant work, so it never shows `busy=busy`. */
    val selectCase: (HarnessSocialCase) -> Unit,

    /** The one button that opens a real provider sheet. */
    val socialSignIn: HarnessAction,

    /** Signing THIS device in with a code somebody approves elsewhere. */
    val deviceSignIn: HarnessAction,

    /** The code a person typed on this device to approve another one. */
    val setApproverCode: (String) -> Unit,

    /** The approver's three operations, in the order the screen shows them. */
    val approver: List<HarnessAction>,

    /** The ten buttons the Maestro flows tap, in the order the flows expect to find them. */
    val lifecycle: List<HarnessAction>
);
