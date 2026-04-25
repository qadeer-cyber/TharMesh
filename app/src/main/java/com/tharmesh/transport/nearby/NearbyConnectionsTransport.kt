package com.tharmesh.transport.nearby

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
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

    /**
     * userIds for which we have already dispatched a [TransportEvent.PeerFound]
     * during the lifetime of this transport. Nearby calls
     * [EndpointDiscoveryCallback.onEndpointFound] only on the *discovering*
     * side; the *advertising* side jumps straight to
     * [ConnectionLifecycleCallback.onConnectionInitiated], which means
     * downstream consumers (and the diagnostics counters) never see a
     * PeerFound event for advertise-only roles. We dispatch a synthetic
     * PeerFound on connection initiation when we have not yet recorded one
     * for that userId, so both sides see the same lifecycle.
     */
    private val peerFoundDispatched: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Background dispatch thread for every [TransportEvent] we hand to the
     * listener. Nearby Connections delivers all of its callbacks (
     * [PayloadCallback.onPayloadReceived], [ConnectionLifecycleCallback]
     * methods, [EndpointDiscoveryCallback] methods, and the Tasks returned by
     * `sendPayload` / `startAdvertising` / `startDiscovery`) on the
     * application's **main thread**. The engine's transport listener routes
     * incoming `BUNDLE` and `ACK` frames into Room via
     * `BundleStore.upsert` / `BundleStore.updateStatus`, which Room rejects on
     * the main thread with `IllegalStateException("Cannot access database on
     * the main thread …")`. Field-test repro: phone A sends first message,
     * phone B's app crashes inside `MeshEngine.handleBundle` →
     * `bundleStore?.upsert(bundle)` on its way to caching the freshly
     * received bundle.
     *
     * A single dedicated [HandlerThread] preserves event ordering (the
     * engine's cache logic relies on first-arrival semantics — concurrent
     * delivery would race the cache check) while keeping every Room write
     * off the main thread.
     *
     * Lifecycle: lazily created in [start] and torn down in [stop] so a
     * sign-out → sign-in cycle does not leak a thread per cycle.
     */
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    override fun setListener(listener: (TransportEvent) -> Unit) {
        this.listener = listener
    }

    override fun start(localPeerId: String) {
        this.localPeerId = localPeerId
        if (callbackThread == null) {
            val t = HandlerThread("NearbyTransport-callbacks").apply { start() }
            callbackThread = t
            callbackHandler = Handler(t.looper)
        }
        startAdvertising()
        startDiscovery()
    }

    override fun stop() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        endpointToUserId.clear()
        userIdToEndpoint.clear()
        peerFoundDispatched.clear()
        // Drain in-flight callbacks then quit. quitSafely lets queued events
        // fire (so the engine sees a clean PeerDisconnected sequence on
        // teardown) before the looper exits.
        callbackThread?.quitSafely()
        callbackThread = null
        callbackHandler = null
    }

    override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean {
        val endpointId = userIdToEndpoint[peerId]
        if (endpointId == null) {
            // No endpoint: report failure via the dispatch thread. Caller must
            // not assume the synchronous `false` return on its own — the
            // Error event carries sendId. Dispatch (rather than synchronous
            // invoke) so the listener never sees an event on the main thread.
            dispatch(TransportEvent.Error(peerId, sendId, "No Nearby endpoint for userId"))
            return false
        }
        client.sendPayload(endpointId, Payload.fromBytes(payload))
            .addOnSuccessListener {
                dispatch(TransportEvent.PayloadSent(peerId, sendId, payload.size))
            }
            .addOnFailureListener { e ->
                dispatch(TransportEvent.Error(peerId, sendId, "sendPayload failed: ${e.message}"))
            }
        return true
    }

    /**
     * Hand [event] to the registered listener on the background
     * [callbackThread]. Defensive [Throwable] catch so a buggy listener
     * (e.g. an uncaught NPE in a future feature) doesn't kill the dispatch
     * thread and silently freeze the entire transport.
     */
    private fun dispatch(event: TransportEvent) {
        val handler = callbackHandler
        if (handler == null) {
            // Late callback after stop(). Drop — engine is being torn down.
            return
        }
        handler.post {
            try {
                listener?.invoke(event)
            } catch (t: Throwable) {
                Log.e(TAG, "Listener threw on event ${event::class.simpleName}", t)
            }
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(localPeerId, serviceId, connectionLifecycleCallback, options)
            .addOnFailureListener { e ->
                Log.w(TAG, "startAdvertising failed", e)
                dispatch(TransportEvent.Error(null, 0L, "startAdvertising: ${e.message}"))
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnFailureListener { e ->
                Log.w(TAG, "startDiscovery failed", e)
                dispatch(TransportEvent.Error(null, 0L, "startDiscovery: ${e.message}"))
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val remoteUserId = info.endpointName
            if (peerFoundDispatched.add(remoteUserId)) {
                dispatch(TransportEvent.PeerFound(remoteUserId, remoteUserId))
            }
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
                peerFoundDispatched.remove(userId)
                dispatch(TransportEvent.PeerDisconnected(userId))
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val remoteUserId = info.endpointName
            endpointToUserId[endpointId] = remoteUserId
            userIdToEndpoint[remoteUserId] = endpointId
            // Advertise-only side never gets onEndpointFound; emit a
            // synthetic PeerFound so listeners see a consistent lifecycle.
            if (peerFoundDispatched.add(remoteUserId)) {
                dispatch(TransportEvent.PeerFound(remoteUserId, remoteUserId))
            }
            // Auto-accept. In a real deployment we'd verify info.authenticationDigits
            // against a QR / out-of-band channel; MVP auto-accepts everyone in range.
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                val userId = endpointToUserId[endpointId] ?: return
                dispatch(TransportEvent.PeerConnected(userId))
            } else {
                val userId = endpointToUserId.remove(endpointId)
                if (userId != null) {
                    userIdToEndpoint.remove(userId, endpointId)
                }
                dispatch(
                    TransportEvent.Error(userId, 0L, "connection failed: ${resolution.status.statusMessage}")
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            val userId = endpointToUserId.remove(endpointId)
            if (userId != null) {
                userIdToEndpoint.remove(userId, endpointId)
                peerFoundDispatched.remove(userId)
                dispatch(TransportEvent.PeerDisconnected(userId))
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val userId = endpointToUserId[endpointId] ?: return
            val bytes = payload.asBytes() ?: return
            dispatch(TransportEvent.PayloadReceived(userId, bytes))
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
