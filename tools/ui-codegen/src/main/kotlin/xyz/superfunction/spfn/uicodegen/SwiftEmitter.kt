// The Swift half of the scaffold: one SwiftUI app's generated sources.
//
// Which app is the `Target`'s to say, exactly as in the Kotlin half: the output root is
// read from it and this file names no app.
//
// This file names the FILES and nothing else. What each of them says is one emitter per
// layer — `SwiftServiceEmitter` through `SwiftContainerEmitter` — and each of those has a
// Kotlin twin of the same name and the same shape, because SwiftUI does not compile on the
// Linux host this repository's Swift gate runs on: everything under
// `examples/ios-swiftui/Generated` is written blind and first compiled on a Mac, and a fix
// that Mac forces has to map back onto the Kotlin half declaration for declaration. Six
// files against six files is what keeps "the same fix, in the same place" a thing a reader
// can check.
//
// The output is app code, not package code: every target's Swift root is outside the
// package's `Sources/`, so `swift build` here never sees it. It is still kept syntactically
// careful, because the first reader after this generator is a compiler nobody on this host
// can run.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

class SwiftEmitter(target: Target) : SwiftNames(target)
{
    private val services = SwiftServiceEmitter(target);

    private val flows = SwiftFlowEmitter(target);

    private val models = SwiftModelEmitter(target);

    private val views = SwiftViewEmitter(target);

    private val failures = SwiftFailureEmitter(target);

    // The container is the one emitter handed another: it builds every model, so it asks the
    // model emitter which of them take a validator rather than deriving that a second time.
    private val containers = SwiftContainerEmitter(target, models);

    fun emit(spec: Spec, bundle: Bundle, inputs: Inputs): Map<String, String>
    {
        val files = mutableMapOf<String, String>();
        spec.services.forEach { service ->
            files["$root/Services/${type(service.name, "Service")}.swift"] = services.service(service, inputs);
        };
        spec.flows.forEach { flow ->
            files["$root/Flows/${type(flow.name, "Flow")}.swift"] = flows.flow(spec, flow, bundle, inputs);
        };
        files["$root/Screens/ScreenFailure.swift"] = failures.failure(spec, bundle, inputs);
        spec.screens.forEach { screen ->
            files["$root/Screens/${type(screen.name, "Model")}.swift"] = models.model(spec, screen, bundle, inputs);
            if (screen.usecase)
            {
                files["$root/Screens/${type(screen.name, "UseCase")}.swift"] = models.useCase(screen, bundle, inputs);
            }
            if (!spec.viewIsAuthored(screen))
            {
                files["$root/Views/${type(screen.name, "View")}.swift"] = views.view(screen, bundle, inputs);
            }
        };
        files["$root/AppContainer.swift"] = containers.container(spec, bundle, inputs);
        return files;
    }

    /**
     * The view files of the flows a person writes: not emitted above, and not to be deleted.
     *
     * Named here rather than in `Main` because the path is this emitter's own spelling —
     * `Views/<Screen>View.swift` — and a second copy of it would drift from the one line
     * above that writes the file.
     */
    fun authoredViews(spec: Spec): Set<String> =
        spec.screens.filter { spec.viewIsAuthored(it) }
            .map { "$root/Views/${type(it.name, "View")}.swift" }
            .toSet()
}
