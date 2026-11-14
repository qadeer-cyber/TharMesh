// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.ui.debug

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.ui.account.AccountFlowActivity

/**
 * Activation-path smoke without live Google/OTP: lands account flow with a faked
 * verified profile and mesh identity, as if provisioning was interrupted mid-flight.
 */
class FakeProvisionSmokeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mesh = CryptoIdentity.generate()
        UserPrefs.saveProfile(
            this,
            UserProfile(userId = "fake-provision-user", username = "Provision Smoke", authProvider = UserPrefs.PROVIDER_EMAIL)
        )
        UserPrefs.writeIdentity(this, mesh, allowReplace = true)
        UserPrefs.markOnboarded(this)
        UserPrefs.markTermsAccepted(this)
        startActivity(
            Intent(this, AccountFlowActivity::class.java)
                .putExtra("account_id", "fake-provision-user")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
