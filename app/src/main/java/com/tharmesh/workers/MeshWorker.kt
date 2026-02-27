package com.tharmesh.workers

import com.tharmesh.dtn.MeshEngine

class MeshWorker(private val meshEngine: MeshEngine) {

    fun runSyncTick() {
        // TODO: replace with WorkManager periodic work when background policy is finalized.
        meshEngine.start()
    }

    fun stopSyncTick() {
        meshEngine.stop()
    }
}
