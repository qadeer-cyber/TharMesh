package com.tharmesh.ui.contacts

import com.tharmesh.db.entity.ContactEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PR B — pure-function tests for the search + filter logic that powers the
 * Contacts tab. The fragment delegates to
 * [ContactsFragment.Companion.filterContacts] on every emit; this test class
 * exercises the four filter chips (All / Online / Verified / Unverified)
 * and the case-insensitive name+userId search box without instantiating the
 * fragment.
 */
class ContactsFilterTest {

    private fun contact(userId: String, name: String) = ContactEntity(
        userId = userId,
        displayName = name,
        publicKey = "",
        addedAt = 0L,
        lastSeen = 0L
    )

    private val alice = contact("user-alice-001", "Alice")
    private val bob = contact("user-bob-002", "Bobby")
    private val carol = contact("user-carol-003", "Carol")
    private val all = listOf(alice, bob, carol)

    @Test
    fun all_filter_returnsEverything_whenQueryIsBlank() {
        val out = ContactsFragment.filterContacts(
            contacts = all,
            query = "",
            filter = ContactsFragment.Filter.ALL,
            online = emptySet(),
            verified = emptySet()
        )
        assertEquals(all, out)
    }

    @Test
    fun online_filter_keepsOnlyContactsInOnlineSet() {
        val out = ContactsFragment.filterContacts(
            contacts = all,
            query = "",
            filter = ContactsFragment.Filter.ONLINE,
            online = setOf(bob.userId),
            verified = emptySet()
        )
        assertEquals(listOf(bob), out)
    }

    @Test
    fun verified_filter_keepsOnlyContactsInVerifiedSet() {
        val out = ContactsFragment.filterContacts(
            contacts = all,
            query = "",
            filter = ContactsFragment.Filter.VERIFIED,
            online = emptySet(),
            verified = setOf(alice.userId, carol.userId)
        )
        assertEquals(listOf(alice, carol), out)
    }

    @Test
    fun unverified_filter_excludesEveryUserIdInVerifiedSet() {
        val out = ContactsFragment.filterContacts(
            contacts = all,
            query = "",
            filter = ContactsFragment.Filter.UNVERIFIED,
            online = emptySet(),
            verified = setOf(alice.userId)
        )
        assertEquals(listOf(bob, carol), out)
    }

    @Test
    fun search_matchesDisplayName_caseInsensitive() {
        val out = ContactsFragment.filterContacts(
            contacts = all,
            query = "BOB",
            filter = ContactsFragment.Filter.ALL,
            online = emptySet(),
            verified = emptySet()
        )
        assertEquals(listOf(bob), out)
    }

    @Test
    fun search_matchesUserIdSubstring() {
        val out = ContactsFragment.filterContacts(
            contacts = all,
            query = "carol-003",
            filter = ContactsFragment.Filter.ALL,
            online = emptySet(),
            verified = emptySet()
        )
        assertEquals(listOf(carol), out)
    }

    @Test
    fun search_andFilter_compose() {
        // Online ∩ name~"al" — only Alice.
        val out = ContactsFragment.filterContacts(
            contacts = all,
            query = "al",
            filter = ContactsFragment.Filter.ONLINE,
            online = setOf(alice.userId, bob.userId),
            verified = emptySet()
        )
        assertEquals(listOf(alice), out)
    }

    @Test
    fun shortFingerprint_takesFirstFourAndLastFour() {
        val fp = "1A2B3C4D5E6F7A8B9C0D1E2F"
        assertEquals("1A2B…1E2F", ContactsFragment.shortFingerprint(fp))
    }

    @Test
    fun shortFingerprint_stripsColonsBeforeShortening() {
        val fp = "1A:2B:3C:4D:5E:6F:7A:8B"
        assertEquals("1A2B…7A8B", ContactsFragment.shortFingerprint(fp))
    }

    @Test
    fun shortFingerprint_returnsAsIsForShortInputs() {
        val fp = "AB"
        assertEquals("AB", ContactsFragment.shortFingerprint(fp))
    }
}
