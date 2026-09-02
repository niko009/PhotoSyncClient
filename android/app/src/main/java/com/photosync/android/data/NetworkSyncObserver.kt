package com.photosync.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    @Volatile private var refreshJob: Job? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRefresh()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                scheduleRefresh()
            }
        }
    }

    fun start() {
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            scheduleRefresh()
        }.onFailure { error ->
            Log.e(TAG, "Could not register connectivity observer", error)
        }
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(750)
            runCatching { repository.refresh() }
                .onFailure { error -> Log.e(TAG, "Connectivity-triggered sync failed", error) }
        }
    }

    companion object {
        private const val TAG = "PhotoSyncNetwork"
    }
}
