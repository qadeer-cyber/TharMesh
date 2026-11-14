// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.chat

import java.util.Calendar
import java.util.TimeZone

/**
 * Turns a chronological message list into transcript rows: a day chip whenever
 * the local calendar day changes, then a bubble. A bubble is grouped with the
 * previous one when the sender matches, the day matches, and the gap is at most
 * [GROUP_WINDOW_MS]. Grouping is visual only. It does not change delivery state.
 */
object ChatTranscript {
    const val GROUP_WINDOW_MS = 5 * 60 * 1000L

    data class Msg(val sender: String, val timestamp: Long)

    sealed class Row {
        data class Day(val dayStartMillis: Long) : Row()
        data class Bubble(val messageIndex: Int, val groupedWithPrevious: Boolean) : Row()
    }

    fun rows(
        messages: List<Msg>,
        zone: TimeZone = TimeZone.getDefault()
    ): List<Row> {
        val out = ArrayList<Row>(messages.size + 4)
        var prev: Msg? = null
        for ((index, msg) in messages.withIndex()) {
            val day = startOfDay(msg.timestamp, zone)
            val prevDay = prev?.let { startOfDay(it.timestamp, zone) }
            if (prevDay == null || prevDay != day) {
                out.add(Row.Day(day))
            }
            val previous = prev
            val grouped = previous != null &&
                prevDay == day &&
                previous.sender == msg.sender &&
                msg.timestamp >= previous.timestamp &&
                msg.timestamp - previous.timestamp <= GROUP_WINDOW_MS
            out.add(Row.Bubble(index, grouped))
            prev = msg
        }
        return out
    }

    fun startOfDay(timestamp: Long, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
