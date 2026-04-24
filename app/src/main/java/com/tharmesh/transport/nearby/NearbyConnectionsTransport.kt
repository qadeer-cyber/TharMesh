package com.tharmesh.transport.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Nearby Connections backed [Transport].
 *
 * Uses [Strategy.P2P_CLUSTER] so every device can talk to every other device in the area over
 * Bluetooth / Wi-Fi. The advertised endpoint *name* is the local userId, which is how we
 * map endpointIds ⇄ userIds once a connection completes.
 *
 * **Runtime permissions are the caller's responsibility.** This class does not request
 * permissions — call it only after the Activity has granted BLUETOOTH_SCAN / CONNECT /
 * ADVERTISE (Android 12+) and ACCESS_FINE_LOCATION (all supported versions). See
 * [com.tharmesh.permissions.NearbyPermissions] for the canonical list.
 */
class NearbyConnectionsTransport(
    context: Context,
    private val serviceId: String = DEFAULT_SERVICE_ID
) : Transport {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)

    private var listener: ((TransportEvent) -> Unit)? = null
    private var localPeerId: String = ""

    /** endpointId (Nearby's opaque string) → userId (our logical ID). */
    private val endpointToUserId: MutableMap<String, String> = ConcurrentHashMap()

    /** userId → endpointId, for send(peerId=userId). */
    private val userIdToEndpoint: MutableMap<String, String> = ConcurrentHashMap()

    override fun setListener(listener: (TransportEvent) -> Unit) {
        this.listener = listener
    }

    override fun start(localPeerId: String) {
        this.localPeerId = localPeerId
        startAdvertising()
        startDiscovery()
    }

    override fun stop() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        endpointToUserId.clear()
        userIdToEndpoint.clear()
    }

    override fun send(peerId: String, payload: ByteArray): Boolean {
        val endpointId = userIdToEndpoint[peerId]
        if (endpointId == null) {
            listener?.invoke(TransportEvent.Error(peerId, "No Nearby endpoint for userId"))
            return false
        }
        client.sendPayload(endpointId, Payload.fromBytes(payload))
            .addOnSuccessListener { listener?.invoke(TransportEvent.PayloadSent(peerId, payload.size)) }
            .addOnFailureListener { e ->
                listener?.invoke(TransportEvent.Error(peerId, "sendPayload failed: ${e.message}"))
            }
        return true
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(localPeerId, serviceId, connectionLifecycleCallback, options)
            .addOnFailureListener { e ->
                Log.w(TAG, "startAdvertising failed", e)
                listener?.invoke(TransportEvent.Error(null, "startAdvertising: ${e.message}"))
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnFailureListener { e ->
                Log.w(TAG, "startDiscovery failed", e)
                listener?.invoke(TransportEvent.Error(null, "startDiscovery: ${e.message}"))
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val remoteUserId = info.endpointName
            listener?.invoke(TransportEvent.PeerFound(remoteUserId, remoteUserId))
            // Auto-request connection.
            client.requestConnection(localPeerId, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    Log.w(TAG, "requestConnection failed for $endpointId", e)
                }
        }

        override fun onEndpointLost(endpointId: String) {
            val userId = endpointToUserId.remove(endpointId)
            if (userId != null) {
                userIdToEndpoint.remove(userId, endpointId)
                listener?.invoke(TransportEvent.PeerDisconnected(userId))
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val remoteUserId = info.endpointName
            endpointToUserId[endpointId] = remoteUserId
            userIdToEndpoint[remoteUserId] = endpointId
            // Auto-accept. In a real deployment we'd verify info.authenticationDigits
            // against a QR / out-of-band channel; MVP auto-accepts everyone in range.
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                val userId = endpointToUserId[endpointId] ?: return
                listener?.invoke(TransportEvent.PeerConnected(userId))
            } else {
                val userId = endpointToUserId.remove(endpointId)
                if (userId != null) {
                    userIdToEndpoint.remove(userId, endpointId)
                }
                listener?.invoke(
                    TransportEvent.Error(userId, "connection failed: ${resolution.status.statusMessage}")
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            val userId = endpointToUserId.remove(endpointId)
            if (userId != null) {
                userIdToEndpoint.remove(userId, endpointId)
                listener?.invoke(TransportEvent.PeerDisconnected(userId))
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val userId = endpointToUserId[endpointId] ?: return
            val bytes = payload.asBytes() ?: return
            listener?.invoke(TransportEvent.PayloadReceived(userId, bytes))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Bytes payloads don't need chunked progress; ignore.
        }
    }

    companion object {
        private const val TAG = "NearbyTransport"

        /** Must be identical on sender and receiver builds; defines the service namespace. */
        const val DEFAULT_SERVICE_ID: String = "tharmesh.app.mesh.v1"
    }
}
