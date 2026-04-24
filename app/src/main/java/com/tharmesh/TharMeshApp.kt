package com.tharmesh

import android.app.Application
import com.tharmesh.data.MessageRepository
import com.tharmesh.data.UserPrefs
import com.tharmesh.db.AppDatabase
import com.tharmesh.dtn.MeshEngine
import com.tharmesh.mesh.EmptyMeshDataSource
import com.tharmesh.mesh.NearbyDirectory
import com.tharmesh.mesh.NearbyMeshDataSource
import com.tharmesh.permissions.NearbyPermissions
import com.tharmesh.transport.Transport
import com.tharmesh.transport.nearby.NearbyConnectionsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process singleton. Wires identity → transport → mesh engine → message repository.
 *
 * Kept deliberately lightweight — the mesh is only started once we have a signed-in identity.
 * Call [ensureMeshStarted] from the first screen that runs post-login.
 */
class TharMeshApp : Application() {

    lateinit var appScope: CoroutineScope
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var repository: MessageRepository
        private set

    /**
     * Process-wide directory. Created once in [onCreate]; its underlying data source is
     * swapped via [NearbyDirectory.setSource] as the mesh engine comes up and down.
     * Fragments capture this reference in `onViewCreated` and keep it for the view
     * lifetime — so we MUST NOT reassign [directory], otherwise peers discovered after
     * a deferred [ensureMeshStarted] (e.g. post-permission grant) would never reach the
     * UI until the fragment recreated.
     */
    lateinit var directory: NearbyDirectory
        private set

    private var transport: Transport? = null
    private var meshEngine: MeshEngine? = null
    @Volatile private var started: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        database = AppDatabase.getInstance(this)
        // Pre-login placeholder. The directory holds its own StateFlows; setSource()
        // will swap in NearbyMeshDataSource inside ensureMeshStarted() without changing
        // the directory reference any Fragment is holding.
        directory = NearbyDirectory(appScope, EmptyMeshDataSource())
    }

    /**
     * Idempotently creates the [MeshEngine] and [MessageRepository] for the local user, then
     * starts advertising + discovering over Nearby. Safe to call from multiple activities and
     * from background threads — `@Synchronized` guarantees two racing callers cannot both
     * observe `started == false` and wire duplicate transports / repositories.
     */
    @Synchronized
    fun ensureMeshStarted() {
        if (started) return
        // Refuse to wire the transport before runtime Bluetooth/Location permissions are
        // granted — Nearby's startAdvertising / startDiscovery silently fail without them,
        // and we must NOT flip `started = true` in that state, otherwise this method
        // becomes a permanent no-op and the mesh stays dead forever. MainActivity calls
        // us again from onRequestPermissionsResult once the user grants, and from then on
        // the normal idempotency kicks in.
        if (!NearbyPermissions.allGranted(this)) return
        val profile = UserPrefs.ensureProfile(this)
        val t: Transport = NearbyConnectionsTransport(this)
        val engine = MeshEngine(profile.userId, t)
        val repo = MessageRepository(
            db = database,
            mesh = engine,
            myUserId = { profile.userId },
            scope = appScope
        )
        // Swap the UI-facing data source to the real one now that the engine exists.
        // directory is a stable reference — callers that already captured it now see
        // updates flow through from the real NearbyMeshDataSource without re-reading.
        val realSource = NearbyMeshDataSource(engine)
        directory.setSource(realSource)
        transport = t
        meshEngine = engine
        repository = repo
        engine.start()
        repo.startStoreAndForwardLoop()
        // Data source flips to SCANNING until the first peer arrives; makes the Devices
        // tab show the correct empty-vs-searching copy without a separate button click.
        realSource.startScan()
        started = true
    }

    @Synchronized
    fun stopMesh() {
        if (!started) return
        repository.stopStoreAndForwardLoop()
        meshEngine?.stop()
        // Swap back to an empty source so any residual peer list is dropped — otherwise
        // a user signing out and a different user signing back in would briefly see
        // stale peers. Directory reference stays stable.
        directory.setSource(EmptyMeshDataSource())
        started = false
    }

    /**
     * Check whether the mesh has been started for the current user. Some Activities
     * (Dashboard, Devices) may render before sign-in; they should only reach for
     * [repository] / [meshEngine] when this returns true.
     */
    fun isMeshStarted(): Boolean = started

    companion object {
        @Volatile
        private var instance: TharMeshApp? = null

        fun get(): TharMeshApp =
            requireNotNull(instance) { "TharMeshApp not initialized; declare android:name=\".TharMeshApp\" in the manifest" }
    }
}
