package com.tharmesh.ui.contacts

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MyQrActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val qrText = TextView(this)
        qrText.textSize = 20f
        qrText.gravity = Gravity.CENTER
        val userId = loadMyUserIdSafely()
        qrText.text = "QR: $userId"

        root.addView(
            qrText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
        title = "My QR"
    }

    private fun loadMyUserIdSafely(): String {
        return try {
            val prefsClass = Class.forName("com.tharmesh.data.UserPrefs")
            val ensureMethod = prefsClass.methods.firstOrNull { method ->
                method.name == "ensureProfile" && method.parameterTypes.size == 1
            }
            val profile = ensureMethod?.invoke(null, applicationContext)
            if (profile != null) {
                readUserIdFromProfile(profile) ?: "local-user"
            } else {
                "local-user"
            }
        } catch (ignored: Throwable) {
            "local-user"
        }
    }

    private fun readUserIdFromProfile(profile: Any): String? {
        return try {
            val getter = profile.javaClass.methods.firstOrNull { method ->
                method.name.equals("getUserId", ignoreCase = true) && method.parameterTypes.isEmpty()
            }
            getter?.invoke(profile)?.toString()
        } catch (ignored: Throwable) {
            null
        }
    }
}
