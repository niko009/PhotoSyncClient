package com.photosync.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

enum class SyncNetworkPolicy { WifiOnly, AnyNetwork }
enum class NetworkTransport { WiFi, Cellular, Other, None }
data class SyncNetworkState(val transport: NetworkTransport, val validated: Boolean, val metered: Boolean) {
    fun allows(policy: SyncNetworkPolicy): Boolean = validated && when (policy) {
        SyncNetworkPolicy.WifiOnly -> transport == NetworkTransport.WiFi
        SyncNetworkPolicy.AnyNetwork -> transport != NetworkTransport.None
    }
}

class SyncNetworkPausedException : IllegalStateException("Waiting for an allowed synchronization network")

/** A lease pins HTTP to one Android Network: it cannot fall back to the new cellular default. */
interface MediaNetworkLease {
    val state: SyncNetworkState
    fun checkAllowed()
    fun openConnection(url: URL): HttpURLConnection
}

interface SyncNetworkAccess {
    fun state(): SyncNetworkState
    fun isAllowed(): Boolean
    fun acquire(): MediaNetworkLease
}

class AndroidSyncNetworkAccess(context: Context) : SyncNetworkAccess {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val preferences = PreferencesStore(context.applicationContext)
    override fun state(): SyncNetworkState {
        val caps = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        return SyncNetworkState(
            when {
                caps == null -> NetworkTransport.None
                // Fail closed for ambiguous/VPN transports; never call cellular Wi-Fi.
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.WiFi
                else -> NetworkTransport.Other
            },
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
        )
    }
    override fun isAllowed() = state().allows(preferences.getSyncNetworkPolicy())
    override fun acquire(): MediaNetworkLease {
        val network = manager?.activeNetwork ?: throw SyncNetworkPausedException()
        if (!isAllowed()) throw SyncNetworkPausedException()
        return object : MediaNetworkLease {
            override val state = this@AndroidSyncNetworkAccess.state()
            override fun checkAllowed() {
                if (manager.activeNetwork != network || !isAllowed()) throw SyncNetworkPausedException()
            }
            override fun openConnection(url: URL): HttpURLConnection {
                checkAllowed()
                return network.openConnection(url) as HttpURLConnection
            }
        }
    }
}
