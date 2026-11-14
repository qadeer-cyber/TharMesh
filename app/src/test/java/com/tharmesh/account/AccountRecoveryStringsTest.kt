// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ensures 0.6 recovery lifecycle strings exist in default + ur + sd locales. */
class AccountRecoveryStringsTest {

  private val repoRoot: File
    get() {
      val cwd = File(System.getProperty("user.dir"))
      return if (cwd.name == "app") cwd.parentFile!! else cwd
    }

  private fun xmlHas(name: String, path: String): Boolean {
    val text = File(repoRoot, path).readText()
    return text.contains("""name="$name"""")
  }

  @Test
  fun recoveryLifecycle_stringsPresentInAllLocales() {
    val names = listOf(
      "account_rotate_upload_pending",
      "account_rotate_upload_conflict",
      "account_rotate_resume_body",
      "account_rotate_resume_upload",
      "account_uploaded",
      "account_rotate_recovery_done"
    )
    val bases = listOf(
      "app/src/main/res/values/strings.xml",
      "app/src/main/res/values-ur/strings.xml",
      "app/src/main/res/values-sd/strings.xml"
    )
  for (base in bases) {
      for (name in names) {
        assertTrue("$name in $base", xmlHas(name, base))
      }
    }
  }
}
