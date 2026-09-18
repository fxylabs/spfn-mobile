// What a spec name is called in the emitted code, once per language.
//
// The six emitters of each half — service, flow, model, view, failure, container — all spell
// the same type names, and a second spelling of `type(name, kind)` inside any one of them is
// how the halves of one scaffold drift: a container that built `EnterCodeModel` while the
// model emitter wrote `EntercodeModel` would be a compile error in a file nobody wrote.
//
// So each half's names live in ONE base class its six emitters extend, and the two classes sit
// in one file, next to each other, because the property that matters is that they agree. Every
// declaration below has its twin directly beneath it; a name added to one half with no twin in
// the other is visible here as a gap rather than as a difference between two files nobody
// reads together.
//
// What is NOT here is anything a name depends on beyond the spec string and the kind: the
// target's roots are here because every emitter writes under them, and nothing else is.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.FieldType
import xyz.superfunction.spfn.codegen.Names

/** What the Swift half calls things, and the roots it writes them under. */
abstract class SwiftNames(target: Target)
{
    protected val root: String = target.swiftRoot;

    protected val readouts: Boolean = target.runnerReadouts;

    protected fun type(name: String, kind: String): String = UiNames.swiftType(name, kind)

    protected fun route(flow: FlowDefinition): String = type(flow.name, "Route")

    protected fun routeCase(screen: ScreenDefinition): String = screen.name

    protected fun request(method: ServiceMethod): String =
        Names.swiftType(method.declaration.requestType ?: "Void")

    protected fun response(method: ServiceMethod): String =
        method.declaration.responseType?.let { Names.swiftType(it) } ?: "Void"

    protected fun swiftType(type: FieldType): String = when (type)
    {
        is FieldType.IntegerType -> "Int64"
        else -> "String"
    }

    protected fun header(inputs: Inputs): String = Header.slashes(inputs)

    /** One Swift string literal, for a title an author wrote. */
    protected fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** What the Kotlin half calls things, and the root and package it writes them under. */
abstract class KotlinNames(target: Target)
{
    protected val root: String = target.kotlinRoot;

    protected val readouts: Boolean = target.runnerReadouts;

    protected val pkg: String = target.kotlinPackage;

    protected fun type(name: String, kind: String): String = UiNames.kotlinType(name, kind)

    protected fun route(flow: FlowDefinition): String = type(flow.name, "Route")

    protected fun routeCase(screen: ScreenDefinition): String = UiNames.pascal(screen.name)

    protected fun request(method: ServiceMethod): String =
        Names.kotlinType(method.declaration.requestType ?: "Unit")

    protected fun response(method: ServiceMethod): String =
        method.declaration.responseType?.let { Names.kotlinType(it) } ?: "Unit"

    protected fun kotlinType(type: FieldType): String = when (type)
    {
        is FieldType.IntegerType -> "Long"
        else -> "String"
    }

    protected fun header(inputs: Inputs): String = Header.slashes(inputs)

    /**
     * What a model that checks its fields names out of the vocabulary.
     *
     * `FieldValidator` is on the list whether or not a rule names a custom one, because the
     * constructor parameter is there either way — a screen with no custom rule takes a
     * validator it never consults, so that adding one later is a spec edit rather than a
     * change to what the app hands the model.
     */
    protected val CHECKED_IMPORTS: List<String> =
        listOf("FieldError", "FieldRules", "FieldValidator", "Form")

    /** One Kotlin string literal, for a title an author wrote. */
    protected fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
}
