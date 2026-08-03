// The contract bundle, as the generator understands it.
//
// Reading is strict: a key the generator does not understand is left alone, but a key
// it needs and cannot find is a hard failure. Guessing a default here would put a
// contract decision inside a build tool.

package xyz.superfunction.spfn.codegen

data class Field(
    val name: String,
    val type: String,
    val optional: Boolean
)

data class TypeDefinition(
    val name: String,
    val fields: List<Field>
)

data class Operation(
    val id: String,
    val method: String,
    val path: String,
    val authProfile: String,
    val requiresSession: Boolean,
    val requestType: String,
    val responseType: String,
    val summary: String
)

data class ErrorDefinition(
    val code: String,
    val httpStatus: Long,
    val retryable: Boolean,
    val summary: String
)

data class Bundle(
    val contractVersion: String,
    val contractMajor: Int,
    val contractMinor: Int,
    val supportedRange: String,
    val origin: String,
    val sha256: String,
    val replayWindowMillis: Long,
    val proofInputFields: List<String>,
    /** The declared operation auth classes, sorted for deterministic emission. */
    val authClasses: List<String>,
    /** `keyPolicy.ttlDays`: how long a registered key stays usable. */
    val keyPolicyTtlDays: Long,
    /** `keyPolicy.rotationOperation`: the operation that replaces a key. */
    val keyRotationOperationId: String,
    /** `clientProofV1.clientIdRule`: what a clientId must identify on the REST surface. */
    val clientIdRule: String,
    val types: List<TypeDefinition>,
    val operations: List<Operation>,
    val errors: List<ErrorDefinition>
)
{
    fun typeNamed(name: String): TypeDefinition =
        types.firstOrNull { it.name == name }
            ?: throw JsonException("operation references unknown type '$name'")

    companion object
    {
        fun read(
            bundleText: String,
            sha256: String,
            supportedRange: String,
            contractMajor: Int,
            contractMinor: Int
        ): Bundle
        {
            val root = Json.parse(bundleText).obj();
            val proof = root.required("clientProofV1").obj();
            val proofInput = proof.required("proofInput").obj();

            // Contract 0.3.0 sections. Each is required rather than defaulted: a bundle
            // without one is an older or foreign contract, and generating plausible
            // clients from it would put a contract decision inside a build tool. The
            // `restOperations` section carries wire rules for the /_auth surface as
            // prose; nothing in it is emitted, but its absence still refuses generation
            // so a bundle that dropped the surface cannot pass as one that has it.
            val authClassSection = root.required("operationAuthClasses").obj();
            val authClasses = authClassSection.keys.filter { it != "rule" }.sorted();
            if (authClasses.isEmpty())
            {
                throw JsonException("operationAuthClasses declares no auth class");
            }
            val keyPolicy = root.required("keyPolicy").obj();
            val restOperations = root.required("restOperations").obj();
            if (restOperations.isEmpty())
            {
                throw JsonException("restOperations is present but empty");
            }

            val operations = root.required("operations").arr().map { readOperation(it) };

            // Fail closed on the cross-references the sections make. An operation whose
            // auth class is undeclared, or a rotation operation that names no operation,
            // is a contract error to refuse — not an unknown to pass through as a name.
            operations.forEach { operation ->
                if (operation.authProfile !in authClasses)
                {
                    throw JsonException(
                        "operation '${operation.id}' names auth class '${operation.authProfile}', " +
                            "which operationAuthClasses does not declare"
                    );
                }
            }
            val rotationOperationId = keyPolicy.required("rotationOperation").text();
            if (operations.none { it.id == rotationOperationId })
            {
                throw JsonException(
                    "keyPolicy.rotationOperation names '$rotationOperationId', which is not an operation"
                );
            }

            return Bundle(
                contractVersion = root.required("contractVersion").text(),
                contractMajor = contractMajor,
                contractMinor = contractMinor,
                supportedRange = supportedRange,
                origin = root.required("origin").text(),
                sha256 = sha256,
                replayWindowMillis = proof.required("replayWindowMillis").number(),
                proofInputFields = proofInput.required("fields").arr().map { it.text() },
                authClasses = authClasses,
                keyPolicyTtlDays = keyPolicy.required("ttlDays").number(),
                keyRotationOperationId = rotationOperationId,
                clientIdRule = proof.required("clientIdRule").text(),
                types = root.required("types").arr().map { readType(it) },
                operations = operations,
                errors = root.required("errors").arr().map { readError(it) }
            );
        }

        private fun readType(value: JsonValue): TypeDefinition
        {
            val members = value.obj();
            return TypeDefinition(
                name = members.required("name").text(),
                fields = members.required("fields").arr().map { field ->
                    val entry = field.obj();
                    Field(
                        name = entry.required("name").text(),
                        type = entry.required("type").text(),
                        optional = entry.required("optional").bool()
                    );
                }
            );
        }

        private fun readOperation(value: JsonValue): Operation
        {
            val members = value.obj();
            return Operation(
                id = members.required("id").text(),
                method = members.required("method").text(),
                path = members.required("path").text(),
                authProfile = members.required("authProfile").text(),
                requiresSession = members.required("requiresSession").bool(),
                requestType = members.required("requestType").text(),
                responseType = members.required("responseType").text(),
                summary = members.required("summary").text()
            );
        }

        private fun readError(value: JsonValue): ErrorDefinition
        {
            val members = value.obj();
            return ErrorDefinition(
                code = members.required("code").text(),
                httpStatus = members.required("httpStatus").number(),
                retryable = members.required("retryable").bool(),
                summary = members.required("summary").text()
            );
        }
    }
}

/** Field type as the bundle writes it, resolved into something an emitter can use. */
sealed interface FieldType
{
    data object StringType : FieldType

    data object IntegerType : FieldType

    data object BooleanType : FieldType

    data class Named(val name: String) : FieldType

    data class ArrayOf(val element: FieldType) : FieldType

    companion object
    {
        fun parse(text: String): FieldType = when
        {
            text == "string" -> StringType
            text == "integer" -> IntegerType
            text == "boolean" -> BooleanType
            text.startsWith("array<") && text.endsWith(">") ->
                ArrayOf(parse(text.substring("array<".length, text.length - 1)))
            else -> Named(text)
        }
    }
}

object Names
{
    /** `auth.clientProof.handshake` becomes `authClientProofHandshake`. */
    fun lowerCamel(id: String): String
    {
        val parts = id.split('.', '-', '_').filter { it.isNotEmpty() };
        return parts.mapIndexed { index, part ->
            if (index == 0) part.replaceFirstChar { it.lowercase() }
            else part.replaceFirstChar { it.uppercase() }
        }.joinToString("");
    }

    /** `PROOF_INVALID` becomes `proofInvalid`. */
    fun enumCase(code: String): String
    {
        val parts = code.split('_').filter { it.isNotEmpty() };
        return parts.mapIndexed { index, part ->
            if (index == 0) part.lowercase()
            else part.lowercase().replaceFirstChar { it.uppercase() }
        }.joinToString("");
    }

    /** `clientProofV1` becomes `CLIENT_PROOF_V1`; `none` becomes `NONE`. */
    fun upperSnake(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    fun swiftType(name: String): String = "SPFN$name"

    fun kotlinType(name: String): String = "Spfn$name"
}
