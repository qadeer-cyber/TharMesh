// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import com.tharmesh.db.MessageStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNIT tests. No Android instrumentation, no live Supabase, no radio.
 * Directory calls use an in-process HTTP fake and are labeled in the test name.
 */
class AccountProductTest {

    @Test
    fun freshInstall_cannotSkipRegistration_existingProfileIsNotBlocked() {
        assertEquals(
            RegistrationGate.Decision.BLOCKED_UNCONFIGURED,
            RegistrationGate.decide(hasExistingProfile = false, cloudConfigured = false)
        )
        assertEquals(
            RegistrationGate.Decision.REGISTER,
            RegistrationGate.decide(hasExistingProfile = false, cloudConfigured = true)
        )
        assertEquals(
            RegistrationGate.Decision.ENTER_EXISTING,
            RegistrationGate.decide(hasExistingProfile = true, cloudConfigured = false)
        )
        assertEquals(
            RegistrationGate.Decision.ENTER_EXISTING,
            RegistrationGate.decide(hasExistingProfile = true, cloudConfigured = true)
        )
    }

    @Test
    fun newAccountUsesServerId_existingMeshIdIsKept() {
        assertEquals("acct-uuid", CanonicalAccount.meshUserIdForNewAccount("acct-uuid", null))
        assertEquals("user-legacy", CanonicalAccount.meshUserIdForNewAccount("acct-uuid", "user-legacy"))
        assertNull(CanonicalAccount.meshUserIdForNewAccount("  ", null))
    }

    @Test
    fun discovery_mergesNearbyAndSavedIntoOneContact() {
        val saved = DiscoveredPerson("acct-1", "Amy", DiscoverySource.SAVED, nearby = false, trusted = true)
        val nearby = DiscoveredPerson("acct-1", "Amy", DiscoverySource.NEARBY, nearby = true, trusted = false)
        val other = DiscoveredPerson("acct-2", "Bea", DiscoverySource.DIRECTORY, nearby = false, trusted = false)
        val merged = ContactDiscovery.merge(listOf(saved), listOf(nearby), listOf(other))
        assertEquals(2, merged.size)
        val first = merged.first { it.accountId == "acct-1" }
        assertTrue(first.nearby)
        assertTrue(first.trusted)
        assertEquals(DiscoverySource.SAVED, first.source)
        assertEquals(listOf(first), ContactDiscovery.filter(merged, "amy"))
    }

    @Test
    fun chatsStayOneList_pinnedFirst_archivedHiddenUntilSearch() {
        val chats = listOf(
            ChatRow("a", "Amy", "hello", 10, 0, MessageStatus.QUEUED),
            ChatRow("b", "Bea", "yo", 20, 1, MessageStatus.DELIVERED)
        )
        val flags = ChatFlags(pinned = setOf("a"), archived = setOf("b"))
        val main = ChatListOrganizer.arrange(chats, flags, "")
        assertEquals(listOf("a"), main.map { it.userId })
        val searched = ChatListOrganizer.arrange(chats, flags, "yo")
        assertEquals(listOf("b"), searched.map { it.userId })
    }

    @Test
    fun deliveryGlyph_doubleCheckOnlyForSignedReceipts() {
        assertEquals("", DeliveryGlyph.prefix(MessageStatus.AWAITING_KEY))
        assertEquals("", DeliveryGlyph.prefix(MessageStatus.QUEUED))
        assertEquals("", DeliveryGlyph.prefix(MessageStatus.SENDING))
        assertEquals("", DeliveryGlyph.prefix(MessageStatus.FAILED))
        assertEquals("✓ ", DeliveryGlyph.prefix(MessageStatus.SENT))
        assertEquals("✓✓ ", DeliveryGlyph.prefix(MessageStatus.DELIVERED))
        assertEquals("✓✓ ", DeliveryGlyph.prefix(MessageStatus.READ))
        assertFalse(DeliveryGlyph.isSignedReceipt(MessageStatus.SENT))
        assertTrue(DeliveryGlyph.isSignedReceipt(MessageStatus.DELIVERED))
        assertTrue(DeliveryGlyph.isSignedReceipt(MessageStatus.READ))
    }

    @Test
    fun directorySearch_unconfigured_doesNotInventUsers() {
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                throw AssertionError("directory must not post when unconfigured")
            }
            override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
                throw AssertionError("directory must not call the network when unconfigured")
            }
        }
        val result = DirectoryLookup.search(http, "", "", "", "sara")
        assertTrue(result is DirectoryLookup.Result.Unavailable)
        assertEquals("cloud_auth_not_configured", (result as DirectoryLookup.Result.Unavailable).reason)
    }

    @Test
    fun directorySearch_simulatedHttp_returnsAccounts_notReachability() {
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String) =
                HttpTransport.Response(500, "")
            override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
                assertTrue(url.contains("handle=ilike.sara"))
                assertEquals("tok", headers["Authorization"]?.removePrefix("Bearer "))
                return HttpTransport.Response(
                    200,
                    "[{\"account_id\":\"acct-sara\",\"handle\":\"sara\",\"display_name\":\"Sara\"}]"
                )
            }
        }
        val result = DirectoryLookup.search(
            http, "https://example.supabase.co", "anon", "tok", "sara"
        ) as DirectoryLookup.Result.Hits
        assertEquals("acct-sara", result.people.single().accountId)
        assertFalse(result.people.single().handle.isBlank())
    }

    @Test
    fun schema_directoryIsSeparateFromRecovery() {
        val sql = listOf(File("supabase/schema.sql"), File("../supabase/schema.sql")).first { it.isFile }.readText()
        assertTrue(sql.contains("profiles_select_directory"))
        assertTrue(sql.contains("recovery_select_own"))
        assertTrue(sql.contains("auth.uid() = account_id"))
        val recovery = SchemaReview.columnNames(SchemaReview.tableBody(sql, "recovery_blobs")!!)
        assertFalse(recovery.contains("private_key"))
    }
}
