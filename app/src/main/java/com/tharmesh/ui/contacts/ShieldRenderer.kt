package com.tharmesh.ui.contacts

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import androidx.core.widget.ImageViewCompat
import com.tharmesh.identity.PeerTrustStore
import tharmesh.app.R

/**
 * Stage 6.2 — paints a shield [ImageView] to reflect a peer's trust state:
 *
 * - [PeerTrustStore.TrustState.Verified]   → green check shield (tmNeonGreen)
 * - [PeerTrustStore.TrustState.TofuOnly]   → muted gray shield (tmTextSecondary)
 * - [PeerTrustStore.TrustState.Mismatch]   → red warning shield (tmDanger)
 * - [PeerTrustStore.TrustState.Unknown]    → hidden (View.GONE) — no row yet
 *
 * Uses theme attrs so the same code lights up correctly in light and dark
 * themes without per-mode duplication.
 */
object ShieldRenderer {

    fun bind(view: ImageView, state: PeerTrustStore.TrustState) {
        val ctx = view.context
        when (state) {
            PeerTrustStore.TrustState.Unknown -> {
                view.visibility = View.GONE
                return
            }
            PeerTrustStore.TrustState.Verified -> {
                view.visibility = View.VISIBLE
                view.setImageResource(R.drawable.ic_shield_check)
                view.contentDescription = ctx.getString(R.string.cd_shield_verified)
                tint(view, R.attr.tmNeonGreen)
            }
            PeerTrustStore.TrustState.TofuOnly -> {
                view.visibility = View.VISIBLE
                view.setImageResource(R.drawable.ic_shield_outline)
                view.contentDescription = ctx.getString(R.string.cd_shield_tofu)
                tint(view, R.attr.tmTextSecondary)
            }
            is PeerTrustStore.TrustState.Mismatch -> {
                view.visibility = View.VISIBLE
                view.setImageResource(R.drawable.ic_shield_alert)
                view.contentDescription = ctx.getString(R.string.cd_shield_mismatch)
                tint(view, R.attr.tmDanger)
            }
        }
    }

    private fun tint(view: ImageView, attr: Int) {
        val color = resolveAttrColor(view.context, attr)
        ImageViewCompat.setImageTintList(
            view,
            android.content.res.ColorStateList.valueOf(color)
        )
    }

    private fun resolveAttrColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
