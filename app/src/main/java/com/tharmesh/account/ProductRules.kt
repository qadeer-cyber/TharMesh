// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import com.tharmesh.db.MessageStatus
import org.json.JSONArray

/**
 * Fresh-install gate. An existing local profile is never blocked and never
 * erased. A new install with no cloud project cannot invent an account.
 */
object RegistrationGate {
    enum class Decision { ENTER_EXISTING, REGISTER, BLOCKED_UNCONFIGURED }

    fun decide(hasExistingProfile: Boolean, cloudConfigured: Boolean): Decision {
        if (hasExistingProfile) return Decision.ENTER_EXISTING
        if (!cloudConfigured) return Decision.BLOCKED_UNCONFIGURED
        return Decision.REGISTER
    }
}

/**
 * The mesh user id is the canonical id for new accounts (the server account id).
 * An install that already has a mesh user id keeps it so messages and QR trust
 * stay attached to the same row.
 */
object CanonicalAccount {
    fun meshUserIdForNewAccount(accountId: String?, existingMeshUserId: String?): String? {
        if (!existingMeshUserId.isNullOrBlank()) return existingMeshUserId
        val id = accountId?.trim().orEmpty()
        return id.ifBlank { null }
    }
}

enum class DiscoverySource { SAVED, NEARBY, DIRECTORY }

data class DiscoveredPerson(
    val accountId: String,
    val displayName: String,
    val source: DiscoverySource,
    val nearby: Boolean,
    val trusted: Boolean
)

/** One row per account id. Nearby does not create a second contact. */
object ContactDiscovery {
    fun merge(
        saved: List<DiscoveredPerson>,
        nearby: List<DiscoveredPerson>,
        directory: List<DiscoveredPerson>
    ): List<DiscoveredPerson> {
        val byId = LinkedHashMap<String, DiscoveredPerson>()
        for (person in saved + nearby + directory) {
            if (person.accountId.isBlank()) continue
            val prior = byId[person.accountId]
            if (prior == null) {
                byId[person.accountId] = person
            } else {
                byId[person.accountId] = prior.copy(
                    displayName = prior.displayName.ifBlank { person.displayName },
                    nearby = prior.nearby || person.nearby,
                    trusted = prior.trusted || person.trusted,
                    source = if (prior.source == DiscoverySource.SAVED) DiscoverySource.SAVED else person.source
                )
            }
        }
        return byId.values.toList()
    }

    fun filter(people: List<DiscoveredPerson>, query: String): List<DiscoveredPerson> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return people
        return people.filter {
            it.displayName.lowercase().contains(q) || it.accountId.lowercase().contains(q)
        }
    }
}

/**
 * Checkmarks are evidence. Double checks are only for signed delivery and
 * signed read. Queued, sending, and awaiting-verification get none.
 */
object DeliveryGlyph {
    fun prefix(status: String): String = when (status) {
        MessageStatus.SENT -> "✓ "
        MessageStatus.DELIVERED, MessageStatus.READ -> "✓✓ "
        else -> ""
    }

    fun isSignedReceipt(status: String): Boolean {
        return status == MessageStatus.DELIVERED || status == MessageStatus.READ
    }
}

data class ChatRow(
    val userId: String,
    val title: String,
    val preview: String,
    val timestamp: Long,
    val unread: Int,
    val status: String
)

data class ChatFlags(
    val pinned: Set<String> = emptySet(),
    val archived: Set<String> = emptySet(),
    val muted: Set<String> = emptySet()
)

/** One chats list. Archived rows stay in the same list and show up in search. */
object ChatListOrganizer {
    fun arrange(chats: List<ChatRow>, flags: ChatFlags, query: String): List<ChatRow> {
        val q = query.trim().lowercase()
        val visible = chats.filter { chat ->
            val archived = chat.userId in flags.archived
            if (archived && q.isEmpty()) return@filter false
            if (q.isEmpty()) return@filter true
            chat.title.lowercase().contains(q) || chat.preview.lowercase().contains(q)
        }
        return visible.sortedWith(
            compareByDescending<ChatRow> { it.userId in flags.pinned }
                .thenByDescending { it.timestamp }
        )
    }
}

/** Online username directory. Not a claim that the person is on the mesh. */
object DirectoryLookup {
    data class Person(val accountId: String, val handle: String, val displayName: String)

    sealed class Result {
        data class Hits(val people: List<Person>) : Result()
        data class Unavailable(val reason: String) : Result()
    }

    fun search(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        query: String
    ): Result {
        if (!AuthBackend.configured(baseUrl, anonKey) || accessToken.isBlank()) {
            return Result.Unavailable("cloud_auth_not_configured")
        }
        val prefix = query.trim().lowercase()
        if (prefix.length < 2 || !prefix.matches(Regex("[a-z0-9_]+"))) {
            return Result.Unavailable("invalid_handle")
        }
        val url = baseUrl.trimEnd('/') +
            "/rest/v1/profiles?select=account_id,handle,display_name&handle=ilike." +
            prefix + "*"
        val response = try {
            http.get(
                url,
                mapOf(
                    "apikey" to anonKey,
                    "Authorization" to "Bearer $accessToken",
                    "Accept" to "application/json"
                )
            )
        } catch (ignored: Throwable) {
            return Result.Unavailable("network")
        }
        if (response.code !in 200..299) return Result.Unavailable("http_${response.code}")
        return try {
            val array = JSONArray(response.body)
            val people = ArrayList<Person>(array.length())
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                val id = row.optString("account_id", "")
                val handle = row.optString("handle", "")
                if (id.isBlank() || handle.isBlank()) continue
                people += Person(id, handle, row.optString("display_name", handle))
            }
            Result.Hits(people)
        } catch (ignored: Throwable) {
            Result.Unavailable("bad_response")
        }
    }
}
