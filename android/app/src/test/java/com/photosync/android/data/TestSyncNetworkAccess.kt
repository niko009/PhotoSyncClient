package com.photosync.android.data

import java.net.HttpURLConnection
import java.net.URL

/** Explicit test-only transport for local HTTP protocol servers. */
class TestSyncNetworkAccess : SyncNetworkAccess {
    var policy = SyncNetworkPolicy.WifiOnly
    var network = SyncNetworkState(NetworkTransport.WiFi, true, false)
    override fun state() = network
    override fun isAllowed() = network.allows(policy)
    override fun acquire(): MediaNetworkLease {
        if (!isAllowed()) throw SyncNetworkPausedException()
        val initial = network
        return object : MediaNetworkLease {
            override val state = initial
            override fun checkAllowed() {
                if (network != initial || !isAllowed()) throw SyncNetworkPausedException()
            }
            override fun openConnection(url: URL): HttpURLConnection {
                checkAllowed()
                return url.openConnection() as HttpURLConnection
            }
        }
    }
}
