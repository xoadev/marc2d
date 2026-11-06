package dev.xoa.marc2d

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** The panel's IPv4 address on the home network, or nothing while it has none. A VPN is not where clients will look for it. */
fun panelAddress(context: Context): Flow<String?> =
	callbackFlow {
		val manager = context.getSystemService(ConnectivityManager::class.java)
		val request =
			NetworkRequest
				.Builder()
				.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
				.build()
		val callback =
			object : ConnectivityManager.NetworkCallback() {
				override fun onLinkPropertiesChanged(
					network: Network,
					properties: LinkProperties,
				) {
					trySend(
						properties.linkAddresses
							.map { it.address }
							.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
							?.hostAddress,
					)
				}

				override fun onLost(network: Network) {
					trySend(null)
				}
			}
		manager.registerNetworkCallback(request, callback)
		awaitClose { manager.unregisterNetworkCallback(callback) }
	}.distinctUntilChanged()
