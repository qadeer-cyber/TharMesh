// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.widget

import android.animation.AnimatorInflater
import android.app.Activity
import android.view.View
import tharmesh.app.R

/**
 * Stage 9.2 — central wiring helpers for the unified motion system.
 *
 * The Stage 9.2 spec mandates that **every** interactive element in the app
 * shares the same press-feedback + activity-transition behaviour so the UI
 * feels like one system. Rather than scatter `setStateListAnimator(...)` and
 * `overridePendingTransition(...)` calls across 30+ activities and 20+
 * adapters, we centralise them here.
 *
 * Usage:
 *   - On any clickable [View] at inflate time:
 *       `view.applyPremiumPress()`
 *   - On any [Activity] right after [Activity.startActivity] or
 *     [Activity.finish]:
 *       `applyPremiumTransitions()`
 *
 * Both helpers are idempotent and safe to call multiple times. Both no-op
 * gracefully on devices where the underlying resource cannot be inflated
 * (e.g. older OEM ROMs that miss [@android:anim/overshoot_interpolator]).
 */

/**
 * Apply the unified press-feedback animator to [this] view. Subclasses of
 * [com.google.android.material.floatingactionbutton.FloatingActionButton]
 * automatically get [R.animator.fab_morph] instead so the FAB lift behaviour
 * remains distinct from regular button press.
 *
 * Honours [Settings.Global.ANIMATOR_DURATION_SCALE] automatically: when
 * the user has animations disabled, [ObjectAnimator] becomes a no-op and
 * the press feedback simply doesn't animate (still functional, just
 * instant).
 */
fun View.applyPremiumPress() {
    val res = if (this is com.google.android.material.floatingactionbutton.FloatingActionButton) {
        R.animator.fab_morph
    } else {
        R.animator.press_scale_strong
    }
    val animator = try {
        AnimatorInflater.loadStateListAnimator(context, res)
    } catch (_: RuntimeException) {
        // If the device's framework can't load the animator (very rare on
        // minSdk 24+, but defensive), just skip — view stays clickable
        // without the bounce.
        null
    }
    if (animator != null) stateListAnimator = animator
}

/**
 * Wire the unified entry/exit transition for a freshly-started activity. Call
 * after every [Activity.startActivity] in screens that opt into the system,
 * and at the top of every [Activity.finish] override (or in
 * `onBackPressed`) so back navigation gets the matching pop animation.
 *
 *   override fun onResume() {
 *     super.onResume()
 *     // No-op
 *   }
 *
 *   override fun finish() {
 *     super.finish()
 *     applyPremiumPopTransition()
 *   }
 */
fun Activity.applyPremiumEnterTransition() {
    overridePendingTransition(R.anim.screen_enter, R.anim.screen_exit)
}

fun Activity.applyPremiumPopTransition() {
    overridePendingTransition(R.anim.screen_pop_enter, R.anim.screen_pop_exit)
}
