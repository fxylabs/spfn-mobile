// The Kotlin half of the scaffold: one Compose app's generated sources.
//
// Which app is the `Target`'s to say. The root and the package are read from it, so the
// same spec produces the example app's scaffold and the harness's from one emitter and
// one set of rules — and this file names neither of them.
//
// This file names the FILES and nothing else. What each of them says is one emitter per
// layer — `KotlinServiceEmitter` through `KotlinContainerEmitter` — and each of those has a
// Swift twin of the same name and the same shape. That is not tidiness: the Swift half is
// written blind on a Linux host where SwiftUI does not compile, so a fix a Mac forces has to
// map back onto this half declaration for declaration, and six files against six files is
// what keeps that a thing a reader can check.
//
// The layering the emitted code holds to (docs/architecture/README.md):
//   services  — the ONLY layer that names a call descriptor
//   use cases — optional, one per screen that asks for a seam
//   models    — state and rules, no toolkit
//   views     — the toolkit, and nothing else

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

class KotlinEmitter(target: Target) : KotlinNames(target)
{
    private val services = KotlinServiceEmitter(target);

    private val flows = KotlinFlowEmitter(target);

    private val models = KotlinModelEmitter(target);

    private val views = KotlinViewEmitter(target);

    private val failures = KotlinFailureEmitter(target);

    // The container is the one emitter handed another: it builds every model, so it asks the
    // model emitter which of them take a validator rather than deriving that a second time.
    private val containers = KotlinContainerEmitter(target, models);

    fun emit(spec: Spec, bundle: Bundle, inputs: Inputs): Map<String, String>
    {
        val files = mutableMapOf<String, String>();
        spec.services.forEach { service ->
            files["$root/services/${type(service.name, "Service")}.kt"] = services.service(service, inputs);
        };
        spec.flows.forEach { flow ->
            files["$root/flows/${type(flow.name, "Flow")}.kt"] = flows.flow(spec, flow, bundle, inputs);
        };
        files["$root/screens/ScreenFailure.kt"] = failures.failure(spec, bundle, inputs);
        spec.screens.forEach { screen ->
            files["$root/screens/${type(screen.name, "Model")}.kt"] = models.model(spec, screen, bundle, inputs);
            if (screen.usecase)
            {
                files["$root/screens/${type(screen.name, "UseCase")}.kt"] = models.useCase(screen, bundle, inputs);
            }
            if (!spec.viewIsAuthored(screen))
            {
                files["$root/views/${type(screen.name, "Screen")}.kt"] = views.view(screen, bundle, inputs);
            }
        };
        files["$root/AppContainer.kt"] = containers.container(spec, bundle, inputs);
        return files;
    }

    /**
     * The view files of the flows a person writes: not emitted above, and not to be deleted.
     *
     * Named here rather than in `Main` because the path is this emitter's own spelling —
     * `views/<Screen>Screen.kt` — and a second copy of it would drift from the one line
     * above that writes the file.
     */
    fun authoredViews(spec: Spec): Set<String> =
        spec.screens.filter { spec.viewIsAuthored(it) }
            .map { "$root/views/${type(it.name, "Screen")}.kt" }
            .toSet()
}
