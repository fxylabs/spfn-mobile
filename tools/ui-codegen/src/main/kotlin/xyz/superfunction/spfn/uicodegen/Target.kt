// Which app the scaffold is being written FOR.
//
// One spec, more than one consumer. The generator used to hold the example apps' three
// output roots, their Kotlin package and their bundle id as constants, which made "the
// example apps" a property of the generator rather than of the invocation — and a second
// consumer could only be added by editing the generator every time.
//
// So the consumer is an argument. Everything that used to be a constant is a field here,
// and nothing under `tools/ui-codegen/src/main` names an application, a bundle id or an
// output directory of one. Grepping this tree for either app's reverse-DNS prefix is the
// gate, and it answers zero.
//
// The spec is NOT a field. There is one screen spec and every target is generated from
// it — a target that generated a different spec would be a second scaffold rather than
// the same one in a second app, which is the whole point of having a shared vocabulary.
//
// [tableRoot] is the one optional field, and it is what makes a target a full consumer or
// a partial one. The case table and the Maestro flows are the SPEC's artefacts, not an
// app's: they name cells, fixtures and expectations, and exactly one app installs the
// fixtures those cells run against. A second consumer that emitted its own copy of them
// would be claiming coverage its own runner never provides, so it takes `null` and gets
// scaffolds alone (decision E6, E7).

package xyz.superfunction.spfn.uicodegen

/** One app the generator writes a scaffold into. */
data class Target(
    /** What the invocation calls this target, for a refusal to name. Never emitted. */
    val name: String,

    /** Repository-relative directory the Swift half is written under. */
    val swiftRoot: String,

    /** Repository-relative directory the Kotlin half is written under. */
    val kotlinRoot: String,

    /** The package every emitted Kotlin file declares and imports its siblings from. */
    val kotlinPackage: String,

    /** The application id a runner launches this target by, for the table to print. */
    val appId: String,

    /** Where the case table and the flows go, or null when this target emits neither. */
    val tableRoot: String?,

    /**
     * The Gradle task that rewrites this target's files, for every header to print.
     *
     * A header that named the wrong task would be an instruction to regenerate somebody
     * else's app, which is worse than no instruction: the reader runs it, sees nothing
     * change and concludes the file is up to date.
     */
    val generateTask: String,

    /** The Gradle task that fails when this target's files have drifted. */
    val verifyTask: String
)
{
    companion object
    {
        /**
         * A target out of `--key=value` arguments, or a message naming what is missing.
         *
         * Named rather than positional because six fields in a row is a shape nobody can
         * read at a Gradle call site, and five of them are paths that would swap silently.
         * An unknown key is refused rather than ignored: a misspelled `--kotlin-packge`
         * that fell through would generate the whole scaffold into the wrong package and
         * report success.
         */
        fun parse(arguments: List<String>): Target
        {
            val fields = mutableMapOf<String, String>();
            arguments.forEach { argument ->
                val key = argument.substringBefore('=');
                if (!argument.startsWith("--") || !argument.contains('='))
                {
                    throw IllegalArgumentException("target argument '$argument' is not --key=value");
                }
                if (key !in KEYS)
                {
                    throw IllegalArgumentException("unknown target argument '$key'; expected one of $KEYS");
                }
                fields[key] = argument.substringAfter('=');
            };

            return Target(
                name = required(fields, "--target"),
                swiftRoot = required(fields, "--swift-root"),
                kotlinRoot = required(fields, "--kotlin-root"),
                kotlinPackage = required(fields, "--kotlin-package"),
                appId = required(fields, "--app-id"),
                tableRoot = fields["--table-root"],
                generateTask = required(fields, "--generate-task"),
                verifyTask = required(fields, "--verify-task")
            );
        }

        private val KEYS: Set<String> = setOf(
            "--target",
            "--swift-root",
            "--kotlin-root",
            "--kotlin-package",
            "--app-id",
            "--table-root",
            "--generate-task",
            "--verify-task"
        )

        private fun required(fields: Map<String, String>, key: String): String
        {
            val value = fields[key];
            if (value.isNullOrEmpty())
            {
                throw IllegalArgumentException("the target names no $key");
            }
            return value;
        }
    }
}
