// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.chat

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UNIT. Pure transcript layout. No Android views. */
class ChatTranscriptTest {
    private val zone = TimeZone.getTimeZone("UTC")

    @Test
    fun empty_hasNoRows() {
        assertTrue(ChatTranscript.rows(emptyList(), zone).isEmpty())
    }

    @Test
    fun sameSenderInsideWindow_isGroupedUnderOneDay() {
        val first = at(2026, 3, 2, 10, 0)
        val second = first + 60_000L
        val rows = ChatTranscript.rows(
            listOf(
                ChatTranscript.Msg("me", first),
                ChatTranscript.Msg("me", second)
            ),
            zone
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0] is ChatTranscript.Row.Day)
        val a = rows[1] as ChatTranscript.Row.Bubble
        val b = rows[2] as ChatTranscript.Row.Bubble
        assertFalse(a.groupedWithPrevious)
        assertTrue(b.groupedWithPrevious)
        assertEquals(0, a.messageIndex)
        assertEquals(1, b.messageIndex)
    }

    @Test
    fun differentSenderOrLongGap_isNotGrouped() {
        val first = at(2026, 3, 2, 10, 0)
        val rows = ChatTranscript.rows(
            listOf(
                ChatTranscript.Msg("me", first),
                ChatTranscript.Msg("peer", first + 1_000L),
                ChatTranscript.Msg("peer", first + 1_000L + ChatTranscript.GROUP_WINDOW_MS + 1L)
            ),
            zone
        )
        val bubbles = rows.filterIsInstance<ChatTranscript.Row.Bubble>()
        assertEquals(3, bubbles.size)
        assertFalse(bubbles[1].groupedWithPrevious)
        assertFalse(bubbles[2].groupedWithPrevious)
    }

    @Test
    fun dayChange_insertsASecondDayChip() {
        val rows = ChatTranscript.rows(
            listOf(
                ChatTranscript.Msg("me", at(2026, 3, 2, 23, 50)),
                ChatTranscript.Msg("me", at(2026, 3, 3, 0, 5))
            ),
            zone
        )
        val days = rows.filterIsInstance<ChatTranscript.Row.Day>()
        assertEquals(2, days.size)
        assertTrue(days[1].dayStartMillis > days[0].dayStartMillis)
        val second = rows.filterIsInstance<ChatTranscript.Row.Bubble>()[1]
        assertFalse(second.groupedWithPrevious)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(zone)
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
