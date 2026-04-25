package com.tharmesh.disaster

/**
 * Stage 6.3 — wire-stable SOS marker.
 *
 * SOS / disaster-mode bundles are flagged on the wire by prepending [PREFIX]
 * to the message body. This is wire-additive: peers running pre-6.3 builds
 * still receive the bundle and render it as a regular message (the prefix
 * is visible in the body, but no functional regression). Peers on 6.3+ use
 * [decode] to strip the prefix before persisting AND to detect that the
 * bundle should trigger the disaster-mode alert.
 *
 * No protocol enum changes — the [com.tharmesh.dtn.Protocol] frame is
 * untouched, so phones already on PR-#16 main keep relaying these bundles
 * without code changes.
 */
object SosPayload {

    /** Marker prepended to the body of every SOS-priority outgoing bundle. */
    const val PREFIX: String = "SOS::"

    /** True if the body carries the [PREFIX] marker. */
    fun isSos(body: String): Boolean = body.startsWith(PREFIX)

    /** Wrap [body] with the SOS marker. Idempotent — never double-prefixes. */
    fun encode(body: String): String =
        if (isSos(body)) body else PREFIX + body

    /**
     * Strip the SOS marker from [body] for display / persistence. Returns
     * a [Decoded] capturing the cleaned body and whether the marker was
     * present. Never blocks the receive path on prefix-detection failure —
     * an absent marker just yields `Decoded(body, isSos = false)`.
     */
    fun decode(body: String): Decoded =
        if (isSos(body)) Decoded(body.removePrefix(PREFIX), isSos = true)
        else Decoded(body, isSos = false)

    data class Decoded(val body: String, val isSos: Boolean)
}
