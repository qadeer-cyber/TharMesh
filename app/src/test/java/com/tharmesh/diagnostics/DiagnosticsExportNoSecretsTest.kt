package com.tharmesh.diagnostics

import com.tharmesh.dtn.MeshBundle
import com.tharmesh.dtn.MeshEvent
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsExportNoSecretsTest {

    @Test
    fun exportJson_neverContainsAnonKeyOrPrivateKeyMarkers() {
        val c = DiagnosticsCollector(recentCapacity = 8)
        c.onEvent(MeshEvent.BundleDelivered(
            MeshBundle(
                bundleId = "b1",
                srcId = "a",
                destId = "b",
                payloadCiphertext = "BEGIN PRIVATE KEY-----leak",
                ttlUntil = 9_999_999L,
                hopsLeft = 3,
                signature = "anon-service_role-key",
                status = "PENDING"
            )
        ))
        val json = c.exportJson().lowercase()
        assertFalse(json.contains("begin private key"))
        assertFalse(json.contains("service_role"))
        assertFalse(json.contains("leak"))
        val root = JSONObject(c.exportJson())
        assertTrue(root.has("counters"))
        assertTrue(root.has("recentEvents"))
    }
}