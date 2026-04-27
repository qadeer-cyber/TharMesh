// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh

import android.app.Application
import com.tharmesh.data.MessageRepository
import com.tharmesh.data.UserPrefs
import com.tharmesh.db.AppDatabase
import com.tharmesh.disaster.DisasterModeController
import com.tharmesh.db.RoomBundleStore
import com.tharmesh.diagnostics.DiagnosticsCollector
import com.tharmesh.diagnostics.FieldTestMode
import com.tharmesh.dtn.MeshEngine
import com.tharmesh.dtn.MeshLog
import com.tharmesh.dtn.PerPeerSendPacer
import com.tharmesh.dtn.RetryConfig
import com.tharmesh.identity.PeerTrustStore
import com.tharmesh.identity.RoomPeerTrustStore
import com.tharmesh.mesh.EmptyMeshDataSource
import com.tharmesh.mesh.NearbyDirectory
import com.tharmesh.mesh.NearbyMeshDataSource
import com.tharmesh.permissions.NearbyPermissions
import com.tharmesh.transport.Transport
import com.tharmesh.transport.nearby.NearbyConnectionsTransport
import com.tharmesh.ui.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
     * Stage 6.2 — process-wide [com.tharmesh.identity.PeerTrustStore]. Cheap
     * stateless wrapper over [AppDatabase.peerIdentityDao]; exposed here so
     * UI code (Contacts, Chat shield) can call [com.tharmesh.identity.PeerTrustStore.markVerified]
     * / [com.tharmesh.identity.PeerTrustStore.trustState] without rebuilding
     * the wrapper at every call site, and so the same instance is shared
     * with [com.tharmesh.dtn.MeshEngine] inside [ensureMeshReady].
     */
    val peerTrustStore: PeerTrustStore by lazy {
        RoomPeerTrustStore(database.peerIdentityDao())
    }

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

    /**
     * Process-singleton diagnostics collector. Created lazily so it exists
     * even before the mesh engine is wired, and stays alive across sign-out
     * cycles so field testers don't lose their counters when they log back in.
     */
    val diagnostics: DiagnosticsCollector = DiagnosticsCollector()
    private var diagnosticsListener: ((com.tharmesh.dtn.MeshEvent) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Stage 6.1 — apply the persisted Theme Mode BEFORE any Activity is
        // created so the launcher Activity is inflated against the right
        // night-mode resources on the very first frame. AppCompat handles
        // recreation cleanly when the user later changes the mode from
        // Settings; we never call recreate() ourselves.
        ThemeManager.applyFromPrefs(this)
        // Stage 6.3 — bootstrap disaster-mode state from prefs and register
        // the battery-low broadcast receiver. Cheap; no side effects when
        // disabled (the controller's flows just stay false).
        DisasterModeController.init(this)
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
        // Stage 4.6: long-term signing identity. ensureIdentity is synchronized and
        // idempotent — call it the first time we construct the mesh graph for a
        // given userId. SharedPreferences is cheap, so doing it here (on the main
        // thread) adds <1ms. The private key never leaves the device.
        val identity = UserPrefs.ensureIdentity(this)
        MeshLog.identityReady(identity.fingerprint)
        // Wire the persistent bundle cache so the engine survives process death:
        // start() will restore non-expired bundles, every cachePut / status update
        // mirrors to disk, and the repository tick calls sweepExpiredPersistent().
        // Stage 5.2 — per-peer send pacer + diagnostics hooks. The pacer
        // enforces a 40 ms minimum gap between sends to the same peer (defaults
        // from [RetryConfig.DEFAULT.perPeerSendGapMs]); the hooks bump the
        // diagnostics counters as the engine fires the corresponding events.
        // Stage 5.3 — Field Test toggles can swap in FIELD_TEST_FAST or
        // FIELD_TEST_FLAT for A/B comparison without a code change. Resolved
        // at construction time; toggling the prefs takes effect on the next
        // ensureMeshReady (i.e. process restart or sign-out → sign-in cycle).
        val retryConfig = FieldTestMode.resolveRetryConfig(this)
        val pacer = PerPeerSendPacer(retryConfig.perPeerSendGapMs)
        val diag = diagnostics
        // Per-peer symmetric key ring over static ECDH on our P-256 signing
        // key. The ring resolves peer public keys via the pinned
        // [PeerTrustStore], so encryption switches on automatically the
        // first time we receive a signed bundle from a peer (TOFU) and can
        // be locked down further via the out-of-band QR verify flow.
        val keyRing = com.tharmesh.crypto.PeerKeyRing(
            localPrivateKey = identity.privateKey,
            localUserId = profile.userId,
            resolvePublicKeyBase64 = { peerId -> peerTrustStore.storedKey(peerId) }
        )
        val engine = MeshEngine(
            localUserId = profile.userId,
            transport = t,
            bundleStore = RoomBundleStore(database.bundleDao()),
            identity = identity,
            peerTrustStore = peerTrustStore,
            pacer = pacer,
            onSendPaced = { peerId -> diag.recordSendPaced(peerId) },
            onSendRejected = { peerId, bundleId -> diag.recordSendRejected(peerId, bundleId) },
            onTtlExpiredDrop = { bundleId -> diag.recordTtlExpiredDrop(bundleId) },
            onRelaySent = { peerId, bundleId, bytes ->
                diag.recordRelaySent(peerId, bundleId, bytes)
            }
        )
        val repo = MessageRepository(
            db = database,
            mesh = engine,
            myUserId = { profile.userId },
            scope = appScope,
            retryConfig = retryConfig,
            onRetryAttempt = { bundleId -> diag.recordRetryAttempt(bundleId) },
            onStuckSendingRecovered = { bundleId -> diag.recordStuckSendingRecovered(bundleId) },
            onPeerChurnSuppressed = { peerId -> diag.recordPeerChurnSuppressed(peerId) },
            onRetrySuppressedNoPeers = { bundleId -> diag.recordRetrySuppressedNoPeers(bundleId) },
            // Stage 6.3 — wire disaster-mode hooks. The send path consults
            // [isDisasterModeEnabled] on every outgoing bundle, and the
            // receive path calls [onSosReceived] on inbound SOS-marked
            // payloads so the controller can vibrate + ring (it is itself
            // gated by the persisted toggle, so off-mode peers stay silent).
            onSosReceived = { DisasterModeController.onSosReceived(this) },
            // Stage 7 PR E — feed growth-metric counters from the repo
            // so every code path that adds a contact / starts a chat
            // updates a single source of truth (no risk of forgetting
            // a call site). [GrowthMetrics] is process-state-free,
            // SharedPreferences-backed, and fully offline.
            onContactAdded = { _ -> com.tharmesh.data.GrowthMetrics.recordContactAdded(this) },
            onFirstChatStarted = { _ -> com.tharmesh.data.GrowthMetrics.recordChatStarted(this) },
            isDisasterModeEnabled = { DisasterModeController.shouldForcePriority() },
            peerKeyRing = keyRing,
            // Persist retry-curve state + SOS priority bit so a forced
            // process kill doesn't lose the aggressive SOS curve for
            // in-flight disaster bundles.
            retryStatePersistence = com.tharmesh.data.RetryStatePersistence.Room(
                database.retryStateDao()
            )
        )
        val source = NearbyMeshDataSource(engine)
        directory.setSource(source)
        // Stage 5.1 — mirror every MeshEvent into the diagnostics collector.
        // The listener is registered exactly once per engine; stopMesh clears
        // the engine reference, and the next ensureMeshReady builds a new
        // engine where this wiring runs again.
        val listener: (com.tharmesh.dtn.MeshEvent) -> Unit = { ev -> diagnostics.onEvent(ev) }
        engine.addEventListener(listener)
        diagnosticsListener = listener
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
        // flow back into the Devices tab on sign-out → sign-in cycles. This is
        // pure in-memory wiring — safe to do synchronously on the main thread.
        if (realSource == null) {
            val source = NearbyMeshDataSource(engine)
            directory.setSource(source)
            realSource = source
        }
        // Flip the started flag synchronously BEFORE launching the IO work so a
        // re-entrant call from a sibling Activity.onCreate (chats, contacts, …)
        // returns immediately and we don't double-start the transport.
        started = true
        // engine.start() rehydrates the persistent bundle cache via Room
        // (BundleStore.deleteExpired + loadActive) and must NOT run on the main
        // thread — Room throws IllegalStateException("Cannot access database on
        // the main thread …") otherwise. Crash repro: cold-launch with non-empty
        // bundle table → MainActivity.onCreate → ensureMeshStarted → engine.start
        // → BundleDao_Impl.deleteExpired. Move the whole startup chain (engine
        // rehydrate + transport.start + retry loop kickoff + scan) onto the IO
        // dispatcher so the launch path is uniformly off-main-thread.
        val capturedEngine = engine
        val capturedRepo = repo
        appScope.launch(Dispatchers.IO) {
            // PR #14 follow-up — the deferred startup must NOT proceed if a
            // stopMesh() (sign-out) or a fresh ensureMeshReady() (sign-in as
            // a different user) has run in the meantime. Without these gates,
            // the captured `engine` / `repo` references would restart the OLD
            // engine on the OLD identity, causing split-brain when the user
            // signs back in: two transports advertising in the same process.
            // Pre-flight gate: was the launch superseded before we got the
            // dispatcher slot? Cheap check; covers the common "fast sign-out
            // before any IO ran" case.
            if (!isStartCurrent(capturedEngine)) return@launch
            capturedEngine.start()
            capturedRepo.startStoreAndForwardLoop()
            // Data source flips to SCANNING until the first peer arrives; makes
            // the Devices tab show the correct empty-vs-searching copy without
            // a separate button click.
            realSource?.startScan()
            // Post-flight gate: if stopMesh / re-sign-in happened DURING the
            // chain above, undo the parts that are NOT shared with a possibly
            // freshly-created new engine — i.e. the store-and-forward loop on
            // the OLD repo. Crucially, we MUST NOT call capturedEngine.stop()
            // here: NearbyConnectionsTransport obtains its ConnectionsClient
            // from Nearby.getConnectionsClient(applicationContext), which is a
            // process-wide singleton. If a new engine has already started its
            // transport (via a sign-in cycle), calling stopAllEndpoints /
            // stopAdvertising / stopDiscovery on the old transport would tear
            // down the SAME shared client and silently kill the new engine's
            // advertising and discovery. Transport teardown is owned by
            // stopMesh(); if stopMesh has run, it has already called
            // meshEngine?.stop() on whatever the live engine was at that
            // moment. The only loose end here is the retry loop we just kicked
            // off on the old repository — that's safe to stop because each
            // repository owns its own retryJob (no singleton).
            if (!isStartCurrent(capturedEngine)) {
                capturedRepo.stopStoreAndForwardLoop()
            }
        }
    }

    /**
     * PR #14 follow-up — used by the deferred startup coroutine in
     * [ensureMeshStarted] to verify that the captured [MeshEngine] reference
     * is still the live one for the current sign-in. Returns false after a
     * [stopMesh] (which clears [started]) or after a subsequent
     * [ensureMeshReady] swapped in a fresh engine for a different identity.
     */
    @Synchronized
    private fun isStartCurrent(eng: MeshEngine): Boolean =
        started && meshEngine === eng

    @Synchronized
    fun stopMesh() {
        if (meshReady) {
            if (started) repository.stopStoreAndForwardLoop()
            diagnosticsListener?.let { meshEngine?.removeEventListener(it) }
            diagnosticsListener = null
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
