// SPFN Mobile — the Lint checks this repository adds, registered for every module that applies
// them with `lintChecks(project(":ui-lint"))`.

package xyz.superfunction.spfn.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class SpfnIssueRegistry : IssueRegistry()
{
    override val issues: List<Issue> = listOf(
        BlanketPointerConsumptionDetector.ISSUE,
        NavDisplayTransitionsDetector.ISSUE,
        PagedViewScrollDetector.ISSUE
    );

    override val api: Int = CURRENT_API;

    override val vendor: Vendor = Vendor(vendorName = "SPFN Mobile", identifier = "spfn-ui-lint");
}
