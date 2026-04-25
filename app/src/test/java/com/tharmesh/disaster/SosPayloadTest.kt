package com.tharmesh.disaster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosPayloadTest {

    @Test
    fun encode_prepends_prefix() {
        assertEquals("SOS::help", SosPayload.encode("help"))
    }

    @Test
    fun encode_is_idempotent() {
        val once = SosPayload.encode("help")
        val twice = SosPayload.encode(once)
        assertEquals(once, twice)
    }

    @Test
    fun decode_strips_prefix_when_present() {
        val out = SosPayload.decode("SOS::help")
        assertTrue(out.isSos)
        assertEquals("help", out.body)
    }

    @Test
    fun decode_returns_body_unchanged_when_no_prefix() {
        val out = SosPayload.decode("regular message")
        assertFalse(out.isSos)
        assertEquals("regular message", out.body)
    }

    @Test
    fun isSos_only_matches_at_start() {
        assertFalse(SosPayload.isSos("SOSSOS::"))  // missing the leading SOS:: literal at offset 0 would still match — verify literal start
        assertTrue(SosPayload.isSos("SOS::"))
        assertTrue(SosPayload.isSos("SOS::body"))
        assertFalse(SosPayload.isSos("body SOS::"))
    }

    @Test
    fun decode_handles_empty_marked_body() {
        val out = SosPayload.decode("SOS::")
        assertTrue(out.isSos)
        assertEquals("", out.body)
    }
}
