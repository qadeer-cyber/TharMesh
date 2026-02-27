package com.tharmesh.dtn

class Router {

    private val sentToPeer: MutableSet<String> = mutableSetOf()

    fun shouldForward(bundle: MeshBundle, peerId: String, nowMs: Long): Boolean {
        if (bundle.hopsLeft <= 0) {
            return false
        }
        if (bundle.ttlUntil < nowMs) {
            return false
        }
        val key = bundle.bundleId + "@" + peerId
        if (sentToPeer.contains(key)) {
            return false
        }
        sentToPeer.add(key)
        return true
    }
}
