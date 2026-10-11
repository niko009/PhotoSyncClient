package com.photosync.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Triggers a repository refresh when Android reports validated Internet access.
 * The repository itself serializes work, so repeated network callbacks are safe.
 */
class NetworkSyncObserver(
    context: Context,
    private val repository: PhotoSyncRepository,
) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<Unit>(Channel.CONFLATED)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRefresh()
        override fun onLost(network: Network) = scheduleRefresh()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            scheduleRefresh()
        }
    }

    fun start() {
        scope.launch {
            for (change in changes) {
                delay(750)
                runCatching { repository.onSyncNetworkChanged() }
                    .onFailure { error -> Log.e(TAG, "Connectivity-triggered sync failed", error) }
            }
        }
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            scheduleRefresh()
        }.onFailure { error ->
            Log.e(TAG, "Could not register connectivity observer", error)
        }
    }

    fun stop() {
        connectivityManager.unregisterNetworkCallback(callback)
        changes.close()
        scope.cancel()
    }

    private fun scheduleRefresh() { changes.trySend(Unit) }

    companion object {
        private const val TAG = "PhotoSyncNetwork"
    }
}
