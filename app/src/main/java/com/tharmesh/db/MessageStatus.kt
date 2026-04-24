package com.tharmesh.db

object MessageStatus {
    const val QUEUED = "QUEUED"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val READ = "READ"
    const val FAILED = "FAILED"

    fun rank(status: String): Int = when (status) {
        QUEUED -> 0
        SENT -> 1
        DELIVERED -> 2
        READ -> 3
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
