/*
 * WARNING — running the instrumented suite UNINSTALLS AND REINSTALLS THE APP.
 *
 * `connectedDebugAndroidTest` tears the app down when it finishes, which deletes app storage: the
 * sideloaded test clips under `files/testclips/` (2.4 GB of validation corpus) and the downloaded
 * models under `files/models/`. Both then have to be pushed and re-downloaded by hand, and All-files
 * access has to be re-granted because the uid changes. That is not hypothetical — it happened once and
 * cost the corpus.
 *
 * Run these only when the device holds nothing you would mind losing.
 */

package com.terminalscout.app.prod.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Connectivity.isOnline] against the device's real network state.
 *
 * The point of this stage gate is that it agrees with the system rather than with an assumption, so the
 * test asks the framework the same question a second way and requires the two answers to match. It is
 * therefore valid in both directions: run it with the phone offline and it still holds.
 */
@RunWith(AndroidJUnit4::class)
class ConnectivityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** What the framework says, computed independently of the object under test. */
    private fun frameworkSaysOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Test
    fun agreesWithTheFramework() {
        assertEquals(frameworkSaysOnline(), Connectivity.isOnline(context))
    }

    @Test
    fun isStableAcrossRepeatedCalls() {
        // The gate is consulted once per clip; an answer that flickered between two calls a millisecond
        // apart would hold a queue at random.
        val first = Connectivity.isOnline(context)
        repeat(20) { assertEquals(first, Connectivity.isOnline(context)) }
    }

    @Test
    fun aCancelledWatchDoesNotFire() {
        // The screen going away must not leave a callback behind that later touches a dead view.
        var fired = false
        val watch = Connectivity.whenOnline(context) { fired = true }
        watch.cancel()
        watch.cancel()   // idempotent
        assertEquals(false, fired)
    }
}
