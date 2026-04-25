package com.tharmesh.db

import com.tharmesh.db.dao.BundleDao
import com.tharmesh.db.entity.BundleEntity
import com.tharmesh.dtn.BundleStore
import com.tharmesh.dtn.MeshBundle

/**
 * Room-backed [BundleStore]. Maps [MeshBundle] ⇄ [BundleEntity] and forwards every
 * call to [BundleDao]. Stateless — instances can be reused across MeshEngine restarts.
 *
 * Thread safety: delegates to Room which serializes writes to the underlying SQLite
 * database. Call sites (MeshEngine) are already off the main thread (Nearby callback
 * workers and the store-and-forward IO coroutine), so the synchronous DAO methods
 * here are safe to invoke directly.
 */
class RoomBundleStore(
    private val dao: BundleDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : BundleStore {

    override fun upsert(bundle: MeshBundle) {
        dao.upsert(
            BundleEntity(
                bundleId = bundle.bundleId,
                srcId = bundle.srcId,
                destId = bundle.destId,
                payloadCiphertext = bundle.payloadCiphertext,
                ttlUntil = bundle.ttlUntil,
                hopsLeft = bundle.hopsLeft,
                signature = bundle.signature,
                status = bundle.status,
                createdAt = clock()
            )
        )
    }

    override fun loadActive(nowMs: Long): List<MeshBundle> =
        dao.loadActive(nowMs).map { row ->
            MeshBundle(
                bundleId = row.bundleId,
                srcId = row.srcId,
                destId = row.destId,
                payloadCiphertext = row.payloadCiphertext,
                ttlUntil = row.ttlUntil,
                hopsLeft = row.hopsLeft,
                signature = row.signature,
                status = row.status
            )
        }

    override fun updateStatus(bundleId: String, status: String) {
        dao.updateStatus(bundleId, status)
    }

    override fun delete(bundleId: String) {
        dao.deleteByBundleId(bundleId)
    }

    override fun deleteExpired(nowMs: Long): Int = dao.deleteExpired(nowMs)
}
