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

    private var realSource: NearbyMeshDataSource? = null
    @Volatile private var meshReady: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        database = AppDatabase.getInstance(this)
        // Pre-login placeholder. The directory holds its own StateFlows; setSource()
        // will swap in NearbyMeshDataSource inside ensureMeshReady() without changing
        // the directory reference any Fragment is holding.
        directory = NearbyDirectory(appScope, EmptyMeshDataSource())
        // Try to construct the mesh/repository graph eagerly for returning users. If
        // no profile exists yet (fresh install / signed out), this is a no-op — the
        // LoginActivity will call ensureMeshStarted() after sign-in, which wires the
        // graph at that point. DO NOT call UserPrefs.ensureProfile() here — it would
        // silently flip KEY_AUTHENTICATED=true and auto-login the user without ever
        // showing LoginActivity.
        ensureMeshReady()
    }

    /**
     * Construct [MeshEngine] + [MessageRepository] + [NearbyMeshDataSource] if a user
     * profile is persisted. No-op if no profile exists yet (fresh install / signed out)
     * — the caller (e.g. [com.tharmesh.ui.auth.LoginActivity]) must call this (or
     * [ensureMeshStarted]) after [UserPrefs.saveProfile] completes.
     *
     * The transport is NOT started here. [ensureMeshStarted] handles that, gated on
     * runtime permissions. Construction is perm-safe.
     */
    @Synchronized
    fun ensureMeshReady() {
        if (meshReady) return
        val profile = UserPrefs.readProfile(this) ?: return
        val t: Transport = NearbyConnectionsTransport(this)
        val engine = MeshEngine(profile.userId, t)
        val repo = MessageRepository(
            db = database,
            mesh = engine,
            myUserId = { profile.userId },
            scope = appScope
        )
        val source = NearbyMeshDataSource(engine)
        directory.setSource(source)
        transport = t
        meshEngine = engine
        repository = repo
        realSource = source
        meshReady = true
    }

    /**
     * Idempotently starts the transport (advertising + discovery) and the store-and-forward
     * retry loop. Safe to call from multiple activities and from background threads —
     * `@Synchronized` guarantees two racing callers cannot both observe `started == false`
     * and wire duplicate listeners.
     *
     * Permission-gated: Nearby's startAdvertising / startDiscovery silently fail without
     * BLUETOOTH_* / ACCESS_FINE_LOCATION, so we refuse to flip `started = true` until the
     * user grants. [com.tharmesh.ui.main.MainActivity.onRequestPermissionsResult] re-calls
     * us once the permission dialog resolves.
     *
     * Also ensures the mesh graph is constructed (via [ensureMeshReady]) if it wasn't
     * already — covers the fresh-install sign-in flow where the profile did not exist
     * at [onCreate] time.
     */
    @Synchronized
    fun ensureMeshStarted() {
        ensureMeshReady()
        if (started) return
        if (!NearbyPermissions.allGranted(this)) return
        val engine = meshEngine ?: return
        val repo = repository
        // After stopMesh(), realSource was closed + nulled and the directory swapped
        // to EmptyMeshDataSource. Re-wire a fresh NearbyMeshDataSource so peer events
        // flow back into the Devices tab on sign-out → sign-in cycles.
        if (realSource == null) {
            val source = NearbyMeshDataSource(engine)
            directory.setSource(source)
            realSource = source
        }
        engine.start()
        repo.startStoreAndForwardLoop()
        // Data source flips to SCANNING until the first peer arrives; makes the Devices
        // tab show the correct empty-vs-searching copy without a separate button click.
        realSource?.startScan()
        started = true
    }

    @Synchronized
    fun stopMesh() {
        if (meshReady) {
            if (started) repository.stopStoreAndForwardLoop()
            meshEngine?.stop()
        }
        // Close the old data source (unregister its engine peer listener) and swap back
        // to an empty source so any residual peer list is dropped — otherwise a user
        // signing out and a different user signing back in would briefly see stale peers.
        // Directory reference stays stable.
        realSource?.close()
        directory.setSource(EmptyMeshDataSource())
        realSource = null
        // Reset both flags so a subsequent sign-in (possibly with a different userId)
        // constructs a fresh engine + repository bound to the new identity.
        // repository is a lateinit var — we leave the existing reference in place until
        // ensureMeshReady() reassigns it; callers gated by UserPrefs.hasProfile() will
        // have been redirected to LoginActivity before they attempt to read it.
        meshReady = false
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
