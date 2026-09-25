package xyz.superfunction.spfn.harness

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * This app declares the back gesture the SDK animates.
 *
 * `NavigationHost` states a `predictivePopTransitionSpec`, and that spec runs only if the
 * system hands the window the gesture's PROGRESS — which `android:enableOnBackInvokedCallback`
 * on `<application>` turns on and nothing inside the SDK can. Off, the back still pops and
 * every cell stays green; only the animation under a held gesture is gone
 * (docs/IMPLEMENTATION-PITFALLS.md P35).
 *
 * The manifest is parsed rather than searched, so the attribute the manifest's own comment
 * names does not count as the declaration, and the attribute is read by its namespace.
 */
class PredictiveBackManifestTest
{
    @Test
    fun `the application element enables the OnBackInvokedCallback path`()
    {
        val factory = DocumentBuilderFactory.newInstance();
        factory.isNamespaceAware = true;
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"));
        val application = manifest.getElementsByTagName("application");

        assertEquals("the manifest holds one application element", 1, application.length);
        val declared = (application.item(0) as Element)
            .getAttributeNS(ANDROID_NAMESPACE, "enableOnBackInvokedCallback");
        assertEquals("android:enableOnBackInvokedCallback on <application>", "true", declared);
    }

    private companion object
    {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
    }
}
