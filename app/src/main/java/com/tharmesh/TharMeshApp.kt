package com.tharmesh

import android.app.Application
import com.tharmesh.data.MessageRepository
import com.tharmesh.data.UserPrefs
import com.tharmesh.db.AppDatabase
import com.tharmesh.dtn.MeshEngine
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

    private var transport: Transport? = null
    private var meshEngine: MeshEngine? = null
    private var started: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        database = AppDatabase.getInstance(this)
    }

    /**
     * Idempotently creates the [MeshEngine] and [MessageRepository] for the local user, then
     * starts advertising + discovering over Nearby. Safe to call from multiple activities.
     */
    fun ensureMeshStarted() {
        if (started) return
        val profile = UserPrefs.ensureProfile(this)
        val t: Transport = NearbyConnectionsTransport(this)
        val engine = MeshEngine(profile.userId, t)
        val repo = MessageRepository(
            db = database,
            mesh = engine,
            myUserId = { profile.userId },
            scope = appScope
        )
        transport = t
        meshEngine = engine
        repository = repo
        engine.start()
        started = true
    }

    fun stopMesh() {
        if (!started) return
        meshEngine?.stop()
        started = false
    }

    companion object {
        @Volatile
        private var instance: TharMeshApp? = null

        fun get(): TharMeshApp =
            requireNotNull(instance) { "TharMeshApp not initialized; declare android:name=\".TharMeshApp\" in the manifest" }
    }
}
