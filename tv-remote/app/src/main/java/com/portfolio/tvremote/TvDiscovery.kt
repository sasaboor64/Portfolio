package com.portfolio.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

data class TvDevice(val name: String, val host: String)

/** Finds Android TV / Google TV devices advertising the remote service over mDNS. */
class TvDiscovery(context: Context, private val onChanged: (List<TvDevice>) -> Unit) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, TvDevice>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (listener != null) return
        multicastLock = wifi.createMulticastLock("tv-remote").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { enqueue(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (devices.remove(info.serviceName) != null) publish()
                }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    private fun enqueue(info: NsdServiceInfo) {
        pending.addLast(info)
        resolveNext()
    }

    // Older Android versions only allow one resolve at a time.
    private fun resolveNext() {
        if (resolving || listener == null) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = serviceInfo.host?.hostAddress
                main.post {
                    resolving = false
                    if (host != null && listener != null) {
                        devices[serviceInfo.serviceName] = TvDevice(serviceInfo.serviceName, host)
                        publish()
                    }
                    resolveNext()
                }
            }
        })
    }

    private fun publish() = onChanged(devices.values.toList())

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp."
    }
}
