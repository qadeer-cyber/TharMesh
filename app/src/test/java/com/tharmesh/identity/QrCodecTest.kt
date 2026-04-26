package com.tharmesh.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7 PR D — wire-format tests for [QrCodec].
 *
 * Verifies the new `tharmesh://invite?…` URI form round-trips and that
 * legacy JSON QRs minted by pre-PR-D builds still decode (forward
 * compatibility — older devices upgrading must continue to scan QRs
 * they generated themselves).
 */
class QrCodecTest {

    @Test
    fun encode_emits_invite_uri_with_required_uid_param() {
        val encoded = QrCodec.encode(
            IdentityQrPayload(
                userId = "user-deadbeef",
                name = "Nohri",
                publicKeyBase64 = "abc=="
            )
        )
        assertTrue(encoded.startsWith("tharmesh://invite?"))
        assertTrue(encoded.contains("uid=user-deadbeef"))
        assertTrue(encoded.contains("pub=abc%3D%3D"))
        assertTrue(encoded.contains("name=Nohri"))
    }

    @Test
    fun encode_then_decode_round_trips_all_fields() {
        val original = IdentityQrPayload(
            userId = "tm-deadbeefcafe",
            name = "Nohri Q.",
            publicKeyBase64 = "MIIBIjANBgkq+/abc=="
        )
        val encoded = QrCodec.encode(original)
        val decoded = QrCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun encode_omits_blank_pub_and_name() {
        val encoded = QrCodec.encode(
            IdentityQrPayload(userId = "user-x", name = "", publicKeyBase64 = "")
        )
        assertEquals("tharmesh://invite?uid=user-x", encoded)
        val decoded = QrCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("user-x", decoded!!.userId)
        assertEquals("", decoded.name)
        assertEquals("", decoded.publicKeyBase64)
    }

    @Test
    fun decode_handles_legacy_json_form_for_back_compat() {
        // This is exactly what pre-PR-D builds (and the user's own
        // device pre-upgrade) emitted for [QrCodec.encode].
        val legacy = "{\"userId\":\"user-old\",\"name\":\"Ali\",\"pubKey\":\"BASE64=\"}"
        val decoded = QrCodec.decode(legacy)
        assertNotNull(decoded)
        assertEquals("user-old", decoded!!.userId)
        assertEquals("Ali", decoded.name)
        assertEquals("BASE64=", decoded.publicKeyBase64)
    }

    @Test
    fun decode_handles_legacy_json_with_missing_optional_fields() {
        val legacy = "{\"userId\":\"user-min\"}"
        val decoded = QrCodec.decode(legacy)
        assertNotNull(decoded)
        assertEquals("user-min", decoded!!.userId)
        assertEquals("", decoded.name)
        assertEquals("", decoded.publicKeyBase64)
    }

    @Test
    fun decode_returns_null_for_empty_or_garbage_input() {
        assertNull(QrCodec.decode(""))
        assertNull(QrCodec.decode("   "))
        assertNull(QrCodec.decode("not a real qr"))
        assertNull(QrCodec.decode("https://example.com/foo"))
        // URI shape but missing uid → reject (we never auto-add a
        // contact without a userId)
        assertNull(QrCodec.decode("tharmesh://invite?name=Nope"))
    }

    @Test
    fun decode_uri_handles_url_encoded_special_characters_in_name() {
        val original = IdentityQrPayload(
            userId = "tm-roundtrip",
            name = "Nohri & Friends — مرحبا",
            publicKeyBase64 = "ZGF0YQ=="
        )
        val encoded = QrCodec.encode(original)
        val decoded = QrCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_is_case_insensitive_on_scheme_prefix() {
        // Some QR scanners normalize the scheme; we should still accept.
        val encoded = "TharMesh://invite?uid=user-mixedcase"
        val decoded = QrCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("user-mixedcase", decoded!!.userId)
    }
}
