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

/**
 * A named set of string values, declared in `enums` rather than in `types`: its
 * declaration carries values instead of fields.
 *
 * The set is what the server accepts and sends now, and the contract promises no set
 * stays as it is — a value can be added, and one can be withdrawn for a weakness found
 * later. That is why a generated enum decodes strictly and reports the raw string it
 * refused rather than falling back to a nearest member.
 */
data class EnumDefinition(
    val name: String,
    val values: List<String>
)

data class Operation(
    val id: String,
    val method: String,
    val path: String,
    val authProfile: String,
    val requiresSession: Boolean,
    /** Absent only for a contract-declared bodyless operation such as `core.time`. */
    val requestType: String?,
    /**
     * Absent for an operation that declares no response body. Contract 0.10.0's
     * `restOperations.responseBody` states what that means on the wire: "An operation
     * that declares no responseType answers 204 with an empty body and there is nothing
     * to decode". `auth.device.deny` is the first one.
     */
    val responseType: String?,
    val summary: String
)
{
    /**
     * Whether the contract declares a response body for this operation, which is the one
     * fact the emitted descriptor carries about it. Named for the contract's own word —
     * an operation *declares* a response type or it does not — rather than for the 204
     * the rule happens to answer with, so a later status rule does not rename the field.
     */
    val declaresResponse: Boolean get() = responseType != null
}

data class ErrorDefinition(
    val code: String,
    val httpStatus: Long,
    val retryable: Boolean,
    /**
     * Which surface answers with this code: `clientProofV1` for the proven middleware's
     * six, `rest` for the /_auth operations' own.
     *
     * The two sets sit in one array and mean different things. A proven call can be met
     * by a clientProofV1 refusal and never by a REST one; a REST call is the reverse. A
     * consumer that read the array as one list would build a refusal enum with twelve
     * members that cannot occur on the surface it guards.
     */
    val surface: String,
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
    /** `clockSynchronization.operation`: the unproven operation used before the first proof. */
    val clockSynchronizationOperationId: String,
    /** `clockSynchronization.epochField`: the integer response field anchoring proof time. */
    val clockSynchronizationEpochField: String,
    /** The declared operation auth classes, sorted for deterministic emission. */
    val authClasses: List<String>,
    /** `keyPolicy.ttlDays`: how long a registered key stays usable. */
    val keyPolicyTtlDays: Long,
    /** `keyPolicy.rotationOperation`: the operation that replaces a key. */
    val keyRotationOperationId: String,
    /** `clientProofV1.clientIdRule`: what a clientId must identify on the REST surface. */
    val clientIdRule: String,
    val types: List<TypeDefinition>,
    /** `enums`: named string sets a field type can name, declared beside `types`. */
    val enums: List<EnumDefinition>,
    val operations: List<Operation>,
    val errors: List<ErrorDefinition>
)
{
    fun typeNamed(name: String): TypeDefinition =
        types.firstOrNull { it.name == name }
            ?: throw JsonException("operation references unknown type '$name'")

    /**
     * A field's type, resolved against this bundle's declarations.
     *
     * Every emitter goes through here rather than calling `FieldType.parse` with the raw
     * text, because only the bundle knows which names are enums. An emitter that parsed
     * on its own would read `KeyAlgorithm` as a struct reference and emit a call to a
     * decoder that does not exist.
     */
    fun fieldType(field: Field): FieldType = FieldType.parse(field.type, enums.map { it.name }.toSet())

    /**
     * Whether any declared field carries a decimal, at any nesting. Both emitters ask
     * this to decide whether the generated file needs the decimal imports, so a bundle
     * without one keeps its output free of an import nothing uses.
     */
    fun usesDecimal(): Boolean = types.any { type ->
        type.fields.any { containsDecimal(fieldType(it)) }
    }

    private fun containsDecimal(type: FieldType): Boolean = when (type)
    {
        is FieldType.DecimalType -> true
        is FieldType.ArrayOf -> containsDecimal(type.element)
        is FieldType.MapOf -> containsDecimal(type.value)
        else -> false
    }

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
            val clockSynchronization = root.required("clockSynchronization").obj();

            val clockOperationId = clockSynchronization.required("operation").text();
            val clockEpochField = clockSynchronization.required("epochField").text();
            if (clockSynchronization.required("appliesTo").text() != "clientProofV1")
            {
                throw JsonException("clockSynchronization applies to an unsupported auth profile");
            }
            if (clockSynchronization.required("requestBody").text() != "none")
            {
                throw JsonException("clockSynchronization requestBody must be 'none'");
            }
            if (clockSynchronization.required("unavailableBehavior").text() != "failClosed")
            {
                throw JsonException("clockSynchronization unavailableBehavior must be 'failClosed'");
            }
            if (clockSynchronization.required("fallbackClock").text() != "prohibited")
            {
                throw JsonException("clockSynchronization fallbackClock must be 'prohibited'");
            }

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

            // Contract 0.5.0 declares enums beside types. Required rather than defaulted,
            // like every other section: a bundle without the key is an older contract, and
            // treating its absence as "no enums" would let an emitter read an enum name as
            // a struct reference — which is exactly the failure below refuses.
            val enums = root.required("enums").arr().map { readEnum(it) };
            val types = root.required("types").arr().map { readType(it) };

            checkDeclarations(types, enums);
            checkOperationTypes(operations, types, clockOperationId, clockEpochField);

            return Bundle(
                contractVersion = root.required("contractVersion").text(),
                contractMajor = contractMajor,
                contractMinor = contractMinor,
                supportedRange = supportedRange,
                origin = root.required("origin").text(),
                sha256 = sha256,
                replayWindowMillis = proof.required("replayWindowMillis").number(),
                proofInputFields = proofInput.required("fields").arr().map { it.text() },
                clockSynchronizationOperationId = clockOperationId,
                clockSynchronizationEpochField = clockEpochField,
                authClasses = authClasses,
                keyPolicyTtlDays = keyPolicy.required("ttlDays").number(),
                keyRotationOperationId = rotationOperationId,
                clientIdRule = proof.required("clientIdRule").text(),
                types = types,
                enums = enums,
                operations = operations,
                errors = root.required("errors").arr().map { readError(it) }
            );
        }

        /**
         * Refuses a bundle this generator cannot turn into compiling clients.
         *
         * The contract's own type grammar states the rule this enforces: "a field type
         * outside this grammar is a contract error, not something to guess at: a consumer
         * that does not recognise a container spelling reads it as a type name and fails
         * at compile time." That is not hypothetical. The 0.6.0 pin introduced the enum
         * `KeyAlgorithm`, `FieldType.parse` read it as a struct reference because it read
         * everything unrecognised that way, and both SDKs emitted references to a type
         * nothing declared. Swift failed with "cannot find type in scope" — a build error
         * for a contract error, one stage too late and in the wrong repository's language.
         *
         * So an unresolvable field type stops generation here, where the message can name
         * the field and the contract.
         */
        private fun checkDeclarations(types: List<TypeDefinition>, enums: List<EnumDefinition>)
        {
            val typeNames = types.map { it.name }.toSet();
            val enumNames = enums.map { it.name }.toSet();

            val collisions = typeNames.intersect(enumNames);
            if (collisions.isNotEmpty())
            {
                throw JsonException(
                    "'${collisions.sorted().joinToString("', '")}' is declared as both a type and an enum"
                );
            }

            // Two values that differ only in the separators a generated case name drops
            // would emit the same case twice, which is a compile error in a file nobody
            // wrote by hand. Caught here so the message names the enum.
            enums.forEach { declaration ->
                val cases = declaration.values.groupBy { Names.enumCase(it) }.filterValues { it.size > 1 };
                if (cases.isNotEmpty())
                {
                    throw JsonException(
                        "enum '${declaration.name}' has values that generate one case name: " +
                            cases.values.flatten().sorted().joinToString(", ")
                    );
                }
            }

            types.forEach { type ->
                type.fields.forEach { field ->
                    checkFieldType(FieldType.parse(field.type, enumNames), "${type.name}.${field.name}", typeNames);
                }
            }
        }

        /**
         * Resolves every operation type and closes the two intentional body gaps.
         *
         * A missing request type is valid only for the exact operation the synchronization
         * policy names, whose requestBody is `none`; every other omission is a contract
         * error rather than a bodyless operation inferred by the generator.
         *
         * A missing response type is valid anywhere, because contract 0.10.0 gave it a
         * meaning every operation can carry: one that declares no responseType answers 204
         * with an empty body. It is still never inferred — the bundle's own omission is the
         * only way to get it — and the clock operation is exempt below, because its
         * response is what anchors proof time.
         */
        private fun checkOperationTypes(
            operations: List<Operation>,
            types: List<TypeDefinition>,
            clockOperationId: String,
            clockEpochField: String
        )
        {
            val typesByName = types.associateBy { it.name };
            val clockOperation = operations.firstOrNull { it.id == clockOperationId }
                ?: throw JsonException(
                    "clockSynchronization.operation names '$clockOperationId', which is not an operation"
                );
            operations.forEach { operation ->
                val requestType = operation.requestType;
                if (requestType != null)
                {
                    if (requestType !in typesByName)
                    {
                        throw JsonException("operation '${operation.id}' references unknown request type '$requestType'");
                    }
                }
                else if (operation.id != clockOperationId)
                {
                    throw JsonException("operation '${operation.id}' is missing required key 'requestType'");
                }

                // A declared response type must name something. An absent one is the
                // contract's bodyless answer and needs no type — but it is never
                // inferred: only the bundle's own omission produces it.
                val responseType = operation.responseType;
                if (responseType != null && responseType !in typesByName)
                {
                    throw JsonException(
                        "operation '${operation.id}' references unknown response type '$responseType'"
                    );
                }
            }

            if (clockOperation.requestType != null)
            {
                throw JsonException("clock synchronization operation '$clockOperationId' must have no requestType");
            }
            if (clockOperation.authProfile != "none" || clockOperation.requiresSession)
            {
                throw JsonException(
                    "clock synchronization operation '$clockOperationId' must be unproven and session-free"
                );
            }

            // Loudly, and before the epoch field is looked for. The clock operation is
            // the one whose response the client cannot do without: it is what anchors
            // proof time, and a bundle that dropped its responseType would otherwise
            // reach the new bodyless branch and generate a clock that reads nothing.
            val clockResponseType = clockOperation.responseType
                ?: throw JsonException(
                    "clock synchronization operation '$clockOperationId' must declare a responseType"
                );
            val response = typesByName.getValue(clockResponseType);
            val epoch = response.fields.firstOrNull { it.name == clockEpochField }
                ?: throw JsonException(
                    "clock synchronization response '${response.name}' has no epoch field '$clockEpochField'"
                );
            if (epoch.type != "integer" || epoch.optional)
            {
                throw JsonException(
                    "clock synchronization epoch '${response.name}.$clockEpochField' must be a required integer"
                );
            }
        }

        private fun checkFieldType(type: FieldType, where: String, typeNames: Set<String>): Unit = when (type)
        {
            is FieldType.StringType, is FieldType.IntegerType, is FieldType.BooleanType -> Unit
            is FieldType.EnumRef -> Unit
            is FieldType.ArrayOf -> checkFieldType(type.element, where, typeNames)
            // Contract 0.7.0 removed `number` from the grammar: canonical JSON was never
            // able to carry a float, and `decimal<scale>` is the spelling that replaced
            // it. Parsed so the refusal can say that, instead of reading `number` as the
            // name of an undeclared type.
            is FieldType.NumberType -> throw JsonException(
                "$where is 'number', which contract 0.7.0 removed from the grammar: " +
                    "SPFN-CANON-JSON-1 carries signed 64-bit integers only, and decimal<scale> " +
                    "is the spelling for a fractional value"
            )
            // The wire shape is #95's: a scaled integer, decimal<2> carrying 1999 for
            // 19.99. Emission goes through SPFNDecimalCoding / SpfnDecimalCoding, whose
            // encode side rejects — never rounds — a value finer than the scale, before
            // the proof is signed. Only the scale bounds are checked here; the grammar
            // fixes them, and a bundle outside them is a contract error to refuse.
            is FieldType.DecimalType ->
                if (type.scale < 1 || type.scale > 18) throw JsonException(
                    "$where declares decimal<${type.scale}>, whose scale is outside 1..18: " +
                        "0 is integer written the long way, and above 18 no integer part " +
                        "fits a signed 64-bit wire value"
                )
                else Unit
            // Same reason, different gap: no map appears in this contract, and guessing an
            // encoding for one would fix a wire shape nothing has agreed on.
            is FieldType.MapOf -> throw JsonException(
                "$where is a map, which this generator does not emit; no operation in this contract uses one"
            )
            is FieldType.Named ->
                if (type.name in typeNames) Unit
                else throw JsonException(
                    "$where names '${type.name}', which the contract declares as neither a type nor an enum"
                )
        }

        private fun readEnum(value: JsonValue): EnumDefinition
        {
            val members = value.obj();
            val name = members.required("name").text();
            val values = members.required("values").arr().map { it.text() };
            if (values.isEmpty())
            {
                throw JsonException("enum '$name' declares no values");
            }
            if (values.size != values.toSet().size)
            {
                throw JsonException("enum '$name' repeats a value");
            }
            return EnumDefinition(name = name, values = values);
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
                requestType = members["requestType"]?.text(),
                responseType = members["responseType"]?.text(),
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
                surface = members.required("surface").text(),
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

    /**
     * Floating point, which contract 0.7.0 removed from the grammar. Parsed so it can
     * be refused by name — with the spelling that replaced it — rather than read as an
     * undeclared type; never emitted.
     */
    data object NumberType : FieldType

    /**
     * `decimal<scale>`: a scaled integer on the wire, meaning that integer divided by
     * 10^scale. The scale is part of the type — changing it is a breaking change that
     * renames the field. Emitted as Swift `Decimal` / Kotlin `BigDecimal`, with the
     * scale conversions in SPFNDecimalCoding / SpfnDecimalCoding: encode refuses a
     * value finer than the scale rather than rounding it, before the proof is signed.
     */
    data class DecimalType(val scale: Long) : FieldType

    data class Named(val name: String) : FieldType

    /** A name the bundle declares in `enums` rather than in `types`. */
    data class EnumRef(val name: String) : FieldType

    data class ArrayOf(val element: FieldType) : FieldType

    /** `map<string,T>`. The key is always string because JSON has no other key type. */
    data class MapOf(val value: FieldType) : FieldType

    companion object
    {
        private const val MAP_PREFIX = "map<string,";
        private const val DECIMAL_PREFIX = "decimal<";

        /**
         * Resolves the bundle's spelling of a field type.
         *
         * `enumNames` is not optional and has no default: the whole point is that a name
         * cannot be classified without knowing what the bundle declared, and a default
         * empty set would silently restore the behaviour this parameter exists to remove.
         */
        fun parse(text: String, enumNames: Set<String>): FieldType = when
        {
            text == "string" -> StringType
            text == "integer" -> IntegerType
            text == "number" -> NumberType
            text == "boolean" -> BooleanType
            text.startsWith("array<") && text.endsWith(">") ->
                ArrayOf(parse(text.substring("array<".length, text.length - 1), enumNames))
            text.startsWith(MAP_PREFIX) && text.endsWith(">") ->
                MapOf(parse(text.substring(MAP_PREFIX.length, text.length - 1).trim(), enumNames))
            // A malformed scale is refused here rather than falling through to Named:
            // `decimal<2x>` read as a type name is exactly the P8 failure — a plausible
            // client emitted from a spelling the parser never understood.
            text.startsWith(DECIMAL_PREFIX) && text.endsWith(">") ->
                DecimalType(
                    text.substring(DECIMAL_PREFIX.length, text.length - 1).trim().toLongOrNull()
                        ?: throw JsonException(
                            "'$text' is not a decimal spelling: the scale must be an integer, " +
                                "as in decimal<2>"
                        )
                )
            text in enumNames -> EnumRef(text)
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

    /**
     * `PROOF_INVALID` becomes `proofInvalid`, and `NonceKeyBindingError` becomes
     * `nonceKeyBindingError`.
     *
     * Two spellings arrive here because the contract carries two surfaces. The
     * clientProofV1 refusals are SCREAMING_SNAKE and the /_auth codes are the error
     * class names themselves, which are PascalCase. Lowercasing a whole part is right
     * for the first and destroys the second: this function used to do it unconditionally
     * and produced `noncekeybindingerror`, a name no reader can split back into words.
     *
     * So a part that is already mixed case keeps its shape, and only a part that carries
     * no lowercase letter — `PROOF`, `ES256` — is lowered as a unit.
     */
    fun enumCase(code: String): String
    {
        val parts = code.split('_').filter { it.isNotEmpty() };
        return parts.mapIndexed { index, part ->
            val word = if (part.none { it.isLowerCase() }) part.lowercase() else part;
            if (index == 0) word.replaceFirstChar { it.lowercase() }
            else word.replaceFirstChar { it.uppercase() }
        }.joinToString("");
    }

    /** `clientProofV1` becomes `CLIENT_PROOF_V1`; `none` becomes `NONE`. */
    fun upperSnake(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    fun swiftType(name: String): String = "SPFN$name"

    fun kotlinType(name: String): String = "Spfn$name"
}
