package com.terminalscout.app.prod.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Is there a usable internet connection right now, and tell me when there is one.
 *
 * ### Why the pipeline has to ask
 * Three of the six stages leave the device: Stage 3's board audit and layout read, Stage 4's OCR, and
 * Stage 5's verification pass. A run started with no network does not fail — it completes, having
 * silently skipped all four calls, and emits a well-formed answer with no prices in it. That answer is
 * indistinguishable downstream from a board that genuinely had nothing readable on it.
 *
 * So the queue asks BEFORE it starts a clip rather than discovering it four stages in. A clip that
 * cannot be processed properly is left owed instead of being spent.
 *
 * ### What "online" means here
 * A validated internet capability, not merely an attached network. A phone joined to a captive-portal
 * Wi-Fi or a mobile network that is up but not routing reports `NET_CAPABILITY_INTERNET` without
 * `NET_CAPABILITY_VALIDATED`; starting a run on one of those is the same wasted run as starting with no
 * network at all. Requiring validation is not a guarantee — a connection can still die between this
 * check and the first call — which is why the stages keep their own failure paths.
 */
object Connectivity {

    private const val TAG = "Connectivity"

    fun isOnline(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Calls [onAvailable] ONCE, the next time a validated connection appears, and unregisters itself.
     *
     * Returned handle so a caller that goes away first can cancel; calling it twice is harmless. The
     * callback arrives on a binder thread, so a caller touching UI must post to its own.
     *
     * One-shot on purpose: this exists to wake a held queue, and a queue that has already resumed does
     * not want to hear about every network change for the rest of the session.
     */
    fun whenOnline(context: Context, onAvailable: () -> Unit): Cancellable {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Cancellable {}

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        var fired = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (fired) return
                fired = true
                Log.i(TAG, "network back — releasing the hold")
                runCatching { cm.unregisterNetworkCallback(this) }
                onAvailable()
            }
        }
        return try {
            cm.registerNetworkCallback(request, callback)
            Cancellable { runCatching { cm.unregisterNetworkCallback(callback) } }
        } catch (e: Exception) {
            // registerNetworkCallback can throw when too many callbacks are live. Better to fall back to
            // "resume on the next queue event" than to take the app down waiting for a network.
            Log.w(TAG, "could not watch for a connection", e)
            Cancellable {}
        }
    }

    /** Undo handle for [whenOnline]. */
    fun interface Cancellable {
        fun cancel()
    }
}
