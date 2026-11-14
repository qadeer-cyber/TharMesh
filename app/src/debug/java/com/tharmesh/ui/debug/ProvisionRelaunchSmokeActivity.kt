// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.ui.debug

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.ui.account.AccountFlowActivity

/** Exported relaunch entry for emulator kill/resume provisioning checks. */
class ProvisionRelaunchSmokeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, AccountFlowActivity::class.java).putExtra("account_id", "fake-provision-user"))
        finish()
    }
}
