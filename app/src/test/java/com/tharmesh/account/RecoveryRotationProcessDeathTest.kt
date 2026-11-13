// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import android.content.Context
import android.content.SharedPreferences

/**
 * Process-death between server commit and local clear: staged prefs must survive
 * until upload success clears them (no second UUID on resume).
 */
class RecoveryRotationProcessDeathTest {

    private class MemPrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, def: String?): String? = map[key] as? String ?: def
        override fun getStringSet(key: String, def: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
        override fun getLong(key: String, def: Long) = map[key] as? Long ?: def
        override fun getFloat(key: String, def: Float) = 0f
        override fun getBoolean(key: String, def: Boolean) = false
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

        private class Editor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(k: String, v: String?) = apply { pending[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putInt(k: String, v: Int) = apply { pending[k] = v }
            override fun putLong(k: String, v: Long) = apply { pending[k] = v }
            override fun putFloat(k: String, v: Float) = this
            override fun putBoolean(k: String, v: Boolean) = this
            override fun remove(k: String) = apply { pending[k] = null }
            override fun clear() = apply { pending.clear(); map.clear() }
            override fun commit(): Boolean {
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    @Test
    fun stagedBlob_survivesSimulatedProcessDeath_beforeLocalClear() {
        val prefs = MemPrefs()
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.getSharedPreferences(Mockito.eq("tharmesh_recovery_rotation_stage"), Mockito.anyInt()))
            .thenReturn(prefs)

        val sealed = IdentityRecovery.seal("priv", IdentityRecovery.generatePhrase(), java.security.SecureRandom())
        val requestId = "req-death-001"
        RecoveryRotationStage.save(ctx, "phrase words", sealed, "acct-1", 3, requestId)

        val afterDeath = RecoveryRotationStage.read(ctx)
        assertNotNull(afterDeath)
        assertEquals(requestId, afterDeath!!.requestId)
        assertEquals(3, afterDeath.expectedGeneration)
        assertNotNull(RecoveryRotationStage.sealedFromStaged(afterDeath))

        RecoveryRotationStage.clear(ctx)
        assertNull(RecoveryRotationStage.read(ctx))
    }
}
