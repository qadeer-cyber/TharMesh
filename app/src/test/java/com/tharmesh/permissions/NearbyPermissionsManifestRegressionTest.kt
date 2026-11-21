package com.tharmesh.permissions

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Android 13/14/15 (API 33/34/35) regression gate, JVM side.
 *
 * `requestPermissions` for a permission the manifest does not declare is a silent
 * no-op on Android: the dialog never appears, the grant never arrives, and Nearby
 * then fails at runtime with no user-visible cause. 0.9 added
 * `NEARBY_WIFI_DEVICES` to [NearbyPermissions.requiredForSdk] *and* to the
 * manifest; this test makes the two sides inseparable, so dropping either one
 * fails the build instead of shipping a mesh that cannot start on API 33+.
 *
 * This is a static manifest/runtime-contract check. It is NOT device evidence:
 * actual grant behaviour per API level comes from the
 * `Android API 33-35 evidence` workflow (`scripts/api-emulator-evidence.sh`).
 */
class NearbyPermissionsManifestRegressionTest {

    private lateinit var declared: Set<String>

    @Before
    fun parseManifest() {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml")
        )
        val manifest = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "AndroidManifest.xml not found from ${File(".").absolutePath}; " +
                    "tried ${candidates.map { it.path }}"
            )
        val text = manifest.readText()
        declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("manifest parse produced no permissions", declared.size > 5)
    }

    @Test
    fun api33to35_runtimePermissionsAreAllDeclaredInManifest() {
        for (sdk in 33..35) {
            for (perm in NearbyPermissions.requiredForSdk(sdk)) {
                assertTrue(
                    "API $sdk requests $perm at runtime but the manifest does not " +
                        "declare it — requestPermissions would be a silent no-op",
                    declared.contains(perm)
                )
            }
        }
    }

    @Test
    fun api31and30_runtimePermissionsAreAllDeclaredInManifest() {
        for (sdk in intArrayOf(24, 29, 30, 31, 32)) {
            for (perm in NearbyPermissions.requiredForSdk(sdk)) {
                assertTrue(
                    "API $sdk requests $perm at runtime but the manifest does not declare it",
                    declared.contains(perm)
                )
            }
        }
    }

    @Test
    fun api33PlusDeclaresNearbyWifiDevicesAndPostNotifications() {
        assertTrue(
            "NEARBY_WIFI_DEVICES is mandatory for Wi-Fi based Nearby transports on API 33+",
            declared.contains("android.permission.NEARBY_WIFI_DEVICES")
        )
        assertTrue(
            "POST_NOTIFICATIONS is required for the mesh foreground notification on API 33+",
            declared.contains("android.permission.POST_NOTIFICATIONS")
        )
        assertTrue(
            "FOREGROUND_SERVICE_CONNECTED_DEVICE is required for the API 34+ FGS type",
            declared.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
        )
    }

    @Test
    fun nearbyWifiDevicesIsNotRequestedBelowApi33() {
        for (sdk in intArrayOf(24, 29, 30, 31, 32)) {
            assertTrue(
                "NEARBY_WIFI_DEVICES did not exist before API 33; requesting it on $sdk " +
                    "would fail the permission dialog",
                NearbyPermissions.requiredForSdk(sdk)
                    .none { it == "android.permission.NEARBY_WIFI_DEVICES" }
            )
        }
    }

    @Test
    fun api34and35MatchApi33Exactly() {
        val p33 = NearbyPermissions.requiredForSdk(33).sorted()
        assertEquals(p33, NearbyPermissions.requiredForSdk(34).sorted())
        assertEquals(p33, NearbyPermissions.requiredForSdk(35).sorted())
    }
}
