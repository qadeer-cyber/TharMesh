// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.theme

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNIT. Reads layout and drawable XML. Does not render pixels.
 */
class LayoutReconstructionTest {

    @Test
    fun chatScreen_usesComposerBackAndFlatBubbles() {
        val chat = read("layout/activity_chat.xml")
        assertTrue(chat.contains("button_chat_back"))
        assertTrue(chat.contains("@drawable/bg_composer"))
        assertTrue(chat.contains("android:minHeight=\"48dp\""))
        assertFalse(chat.contains("bg_screen_gradient"))
        assertFalse(chat.contains("bg_glass_floating"))
        val bubble = read("drawable/bg_bubble_me.xml")
        assertFalse(bubble.contains("<gradient"))
        val canvas = read("drawable/bg_screen_gradient.xml")
        assertFalse(canvas.contains("android:type=\"radial\""))
        assertFalse(canvas.contains("android:type=\"linear\""))
    }

    @Test
    fun chatsList_showsPinMuteAndExplainsNewChatActions() {
        val row = read("layout/item_chat.xml")
        assertTrue(row.contains("@+id/text_pin"))
        assertTrue(row.contains("@+id/text_mute"))
        assertTrue(row.contains("@string/chat_row_pinned"))
        val sheet = read("layout/sheet_new_chat.xml")
        assertTrue(sheet.contains("@+id/new_chat_action_scan"))
        assertTrue(sheet.contains("@+id/new_chat_action_nearby"))
        assertTrue(sheet.contains("@+id/new_chat_action_username"))
        assertTrue(sheet.contains("@+id/new_chat_action_userid"))
        assertTrue(sheet.contains("@string/new_chat_nearby_hint"))
        assertTrue(sheet.contains("@string/new_chat_username_hint_row"))
        assertTrue(sheet.contains("@drawable/ic_wifi"))
        assertTrue(sheet.contains("android:minHeight=\"64dp\""))
    }

    @Test
    fun radialGlows_doNotUseThemeAttributes() {
        for (name in listOf(
            "drawable/bg_nav_pill_glow.xml",
            "drawable/bg_avatar_glow_online.xml",
            "drawable/bg_pulse_red.xml",
            "drawable-night/bg_nav_pill_glow.xml",
            "drawable-night/bg_avatar_glow_online.xml",
            "drawable-night/bg_pulse_red.xml"
        )) {
            val xml = read(name)
            assertFalse(name, xml.contains("?attr/"))
            assertTrue(name, xml.contains("gradientRadius"))
        }
    }

    private fun read(relative: String): String {
        val file = listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative")
        ).first { it.isFile }
        return file.readText()
    }
}
