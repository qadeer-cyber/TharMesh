// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.ui.debug

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.account.IdentityRecovery
import com.tharmesh.account.RecoveryRotationStage
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile
import java.security.SecureRandom

/**
 * Writes a staged recovery rotation (simulating post-rotate, pre-upload), then exits
 * as if the process died. Next launch should still read the same stage via
 * [RecoveryRotationStage.read] / account resume UI.
 */
class RecoveryStageDeathSmokeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountId = "recovery-death-acct"
        UserPrefs.saveProfile(
            this,
            UserProfile(userId = accountId, username = "Stage Death", authProvider = UserPrefs.PROVIDER_EMAIL)
        )
        val sealed = IdentityRecovery.seal("fake-priv-key-material", IdentityRecovery.generatePhrase(), SecureRandom())
        RecoveryRotationStage.save(
            this,
            "alpha beta gamma delta",
            sealed,
            accountId,
            expectedGeneration = 2,
            requestId = "req-emulator-death-001"
        )
        finish()
    }
}
