// JVM-only stand-in for the Android API used by the shipped proof clock.
//
// The reference-server integration suite compiles android/spfn-client main sources
// directly on a plain JVM. Production Android resolves android.os.SystemClock from the
// platform (elapsedRealtime exists since API 1); this file is visible only to the JVM
// test source set and supplies the same monotonic-millisecond shape there.

package android.os

object SystemClock
{
    @JvmStatic
    fun elapsedRealtimeNanos(): Long = System.nanoTime()
}
