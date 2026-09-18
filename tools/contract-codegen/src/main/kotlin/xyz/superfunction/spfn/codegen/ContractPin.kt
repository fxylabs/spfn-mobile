// SPFN Mobile — the pinned contract, read once for both generators.
//
// Two files answer two different questions and neither restates the other:
//
//   Contracts/upstream.lock.json          which primitives commit was read, and WHERE the
//                                         vendored copy sits in THIS tree
//   Contracts/upstream-provenance.json    WHAT the contract is — name, version, major,
//                                         supportedRange, bundleSha256 — written by the
//                                         exporter and copied here unmodified
//
// Until lockVersion 3 the lock carried a second copy of the contract facts and every
// reader chose one of the two. Two sources for one value can disagree, so the values now
// have one home and the comparison that used to guard them has nothing left to compare.

package xyz.superfunction.spfn.codegen

import java.io.File
import java.security.MessageDigest

class ContractPinFailure(message: String) : RuntimeException(message)

/**
 * Reads the pin, recomputes the bundle digest and refuses to continue when they
 * disagree. This is the gate that makes a generated header meaningful: a client whose
 * header names a digest was demonstrably produced from a file with that digest.
 *
 * Both generators come through here rather than each keeping a reader of its own, so a
 * contract fact cannot be read one way by `:contract-codegen` and another by `:ui-codegen`.
 */
object ContractPin
{
    const val LOCK_PATH: String = "Contracts/upstream.lock.json"
    const val PROVENANCE_PATH: String = "Contracts/upstream-provenance.json"

    fun loadBundle(repoRoot: File): Bundle
    {
        val lock = readObject(repoRoot, LOCK_PATH)
        val status = lock.required("status").text();
        if (status != "RESOLVED_DEV_BUNDLE" && status != "RESOLVED_UPSTREAM")
        {
            throw ContractPinFailure("lock status '$status' is not generatable; nothing is pinned");
        }

        val bundlePath = lock.required("contract").obj().required("bundlePath").text();
        val bundleFile = File(repoRoot, bundlePath);
        if (!bundleFile.isFile)
        {
            throw ContractPinFailure("lock points at $bundlePath, which does not exist");
        }

        val contract = readObject(repoRoot, PROVENANCE_PATH).required("contract").obj();
        val expectedDigest = contract.required("bundleSha256").text();
        val bytes = bundleFile.readBytes();
        val actualDigest = sha256Hex(bytes);
        if (actualDigest != expectedDigest)
        {
            throw ContractPinFailure(
                "bundle digest mismatch for $bundlePath\n" +
                    "  provenance says: $expectedDigest\n" +
                    "  file is:         $actualDigest\n" +
                    "Refusing to generate. Either the bundle was edited without re-pinning, or the " +
                    "lock was pointed at a different file."
            );
        }

        val version = contract.required("version").text();
        return Bundle.read(
            bundleText = String(bytes, Charsets.UTF_8),
            sha256 = actualDigest,
            supportedRange = contract.required("supportedRange").text(),
            contractMajor = contract.required("major").number().toInt(),
            contractMinor = minorOf(version)
        );
    }

    /**
     * The minor, derived rather than read: the evidence records the version and the major
     * and stops there, because a minor written beside a version is a second chance to be
     * wrong about the same number.
     */
    private fun minorOf(version: String): Int
    {
        val parts = version.split(".");
        val minor = if (parts.size >= 2) parts[1].toIntOrNull() else null;
        return minor
            ?: throw ContractPinFailure("contract version '$version' has no minor to derive the 0.x range from");
    }

    private fun readObject(repoRoot: File, relativePath: String): Map<String, JsonValue>
    {
        val file = File(repoRoot, relativePath);
        if (!file.isFile)
        {
            throw ContractPinFailure("missing ${file.path}");
        }
        return Json.parse(file.readText()).obj();
    }

    private fun sha256Hex(bytes: ByteArray): String
    {
        val digits = "0123456789abcdef";
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        val out = StringBuilder(digest.size * 2);
        for (byte in digest)
        {
            val value = byte.toInt() and 0xFF;
            out.append(digits[value shr 4]);
            out.append(digits[value and 0x0F]);
        }
        return out.toString();
    }
}
