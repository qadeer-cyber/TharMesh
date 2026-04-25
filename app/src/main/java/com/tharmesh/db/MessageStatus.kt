package com.tharmesh.db

object MessageStatus {
    const val QUEUED = "QUEUED"
    /**
     * Transport.send(...) was accepted (endpoint known, bytes queued in Nearby's send
     * buffer) but the PayloadSent callback has not yet fired. This is the authoritative
     * "in flight" state between QUEUED and SENT — we used to collapse it into QUEUED,
     * but then there was no way to tell "not yet handed to transport" from "handed to
     * transport, awaiting confirmation" for a stuck retry. Rendered in the UI exactly
     * like QUEUED (no tick yet) to keep the layout unchanged.
     */
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val READ = "READ"
    const val FAILED = "FAILED"

    fun rank(status: String): Int = when (status) {
        QUEUED -> 0
        SENDING -> 1
        SENT -> 2
        DELIVERED -> 3
        READ -> 4
        FAILED -> -1
        else -> -1
    }

    /**
     * Never regress a status e.g. don't flip READ back to SENT on a stale ack. FAILED
     * has rank -1, so a successful retry (SENT/DELIVERED/READ) can advance it forward
     * — matching [com.tharmesh.db.dao.MessageDao.advanceStatusByBundleId]. Targeting
     * FAILED is always accepted because a transport Error means the send genuinely
     * failed; the retry loop is responsible for any subsequent forward motion.
     */
    fun advance(current: String, target: String): String {
        if (target == FAILED) return target
        return if (rank(target) > rank(current)) target else current
    }
}
