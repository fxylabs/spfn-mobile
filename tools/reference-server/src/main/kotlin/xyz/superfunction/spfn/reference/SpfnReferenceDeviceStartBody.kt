// SPFN Mobile — one `auth.device.start` request body, printed.
//
// The harness runner plays the WAITING DEVICE for cells d1–d3: it parks a key with the
// reference server, hands the user code to a flow, and afterwards polls with the device
// code to see what the phone's approval actually did on the server. Parking a key needs a
// body, and a body cannot be a constant.
//
// The server checks the key rather than storing it (`SpfnReferenceServer.deviceStart`):
// `fingerprint` must be the SHA-256 of the base64-decoded `publicKey`, and the poll that
// follows an approval registers those same bytes through `SpfnReferenceState.enrollKey`,
// which parses them as an SPKI EC key and refuses anything else. So the body carries a
// real P-256 key, generated fresh on every invocation — fresh also because `enrollKey`
// refuses a keyId it already holds, and three cells in one run would otherwise collide.
//
// The output is the request body on standard output and nothing else, so a shell can
// capture it. It is printed rather than sent because sending is `curl`'s job in
// tools/harness/run-harness.sh, which needs the response in the same shell.
//
// Nothing here is a secret. The private half of the pair is generated, used for nothing
// and dropped when this process exits: the runner never proves anything with this key —
// the poll that collects an approval is unproven by definition, since obtaining a key is
// what the whole flow is for.

package xyz.superfunction.spfn.reference

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.generated.SpfnKeyAlgorithm
import xyz.superfunction.spfn.generated.SpfnKeyPlatform
import xyz.superfunction.spfn.generated.SpfnStartDeviceAuthRequest

fun main(args: Array<String>)
{
    val deviceName = args.firstOrNull() ?: "SPFN harness runner";

    val generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(ECGenParameterSpec("secp256r1"));
    val spkiDer = generator.generateKeyPair().public.encoded;
    val fingerprint = SpfnDigest.sha256Hex(spkiDer);

    val body = SpfnStartDeviceAuthRequest(
        publicKey = Base64.getEncoder().encodeToString(spkiDer),
        // Derived from the fingerprint, so two bodies with different keys can never share
        // a keyId and one body always names its own key.
        keyId = "key-harness-${fingerprint.take(16)}",
        fingerprint = fingerprint,
        algorithm = SpfnKeyAlgorithm.ES256,
        deviceName = deviceName,
        platform = SpfnKeyPlatform.ANDROID
    );
    println(SpfnCanonicalJson.encodeToString(body.canonicalValue()));
}
