package com.example.wlr_remote_control.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val SERVICE_TYPE = "_wlr-remote._udp"

data class DiscoveredService(
    val name: String,
    val host: String,
    val port: Int,
)

@Composable
fun rememberDiscoveredServices(): List<DiscoveredService> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = remember { mutableStateListOf<DiscoveredService>() }

    DisposableEffect(Unit) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val resolveChannel = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        val resolveJob = scope.launch(Dispatchers.IO) {
            for (serviceInfo in resolveChannel) {
                val resolved = suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val cb = object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                            override fun onServiceUpdated(info: NsdServiceInfo) {
                                if (cont.isActive) cont.resume(info)
                                try { nsdManager.unregisterServiceInfoCallback(this) } catch (_: Exception) {}
                            }
                            override fun onServiceLost() {
                                if (cont.isActive) cont.resume(null)
                            }
                            override fun onServiceInfoCallbackUnregistered() {}
                        }
                        nsdManager.registerServiceInfoCallback(serviceInfo, { it.run() }, cb)
                        cont.invokeOnCancellation {
                            try { nsdManager.unregisterServiceInfoCallback(cb) } catch (_: Exception) {}
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                cont.resume(null)
                            }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                cont.resume(info)
                            }
                        })
                    }
                } ?: continue

                val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    resolved.hostAddresses.firstOrNull()?.hostAddress?.substringBefore('%')
                } else {
                    @Suppress("DEPRECATION")
                    resolved.host?.hostAddress?.substringBefore('%')
                } ?: continue
                val name = resolved.serviceName ?: continue
                val port = resolved.port
                val discovered = DiscoveredService(name, host, port)

                withContext(Dispatchers.Main) {
                    val idx = services.indexOfFirst { it.name == name }
                    if (idx >= 0) services[idx] = discovered else services.add(discovered)
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveChannel.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                scope.launch(Dispatchers.Main) {
                    services.removeAll { it.name == serviceInfo.serviceName }
                }
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        onDispose {
            resolveChannel.close()
            resolveJob.cancel()
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {
                // ignore if discovery never started properly
            }
        }
    }

    return services
}


