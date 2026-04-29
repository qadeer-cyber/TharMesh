// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.about

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 10.1 — pure-logic tests for [AboutBuildInfo.format]. Verifies the
 * sanitization rules so the About screen renders cleanly even when one of
 * the [BuildConfig] fields is empty / null / a full-length SHA.
 */
class AboutBuildInfoTest {

    @Test
    fun `version trimmed, build type lowercased, short commit unchanged`() {
        val d = AboutBuildInfo.format(
            version = "  0.1  ",
            buildType = "DEBUG",
            commit = "a9a6513"
        )
        assertEquals("0.1", d.version)
        assertEquals("debug", d.buildType)
        assertEquals("a9a6513", d.commit)
    }

    @Test
    fun `null and blank inputs render the em-dash placeholder`() {
        val d = AboutBuildInfo.format(version = null, buildType = "", commit = "  ")
        assertEquals("—", d.version)
        assertEquals("—", d.buildType)
        assertEquals("—", d.commit)
    }

    @Test
    fun `dash-string commit is preserved verbatim`() {
        // app/build.gradle falls back to "—" when git is unavailable. The
        // formatter must not collapse it into a different placeholder.
        val d = AboutBuildInfo.format(version = "0.1", buildType = "release", commit = "—")
        assertEquals("—", d.commit)
    }

    @Test
    fun `full 40-char SHA is truncated to 7-char short form`() {
        val full = "a9a6513f0123456789abcdef0123456789abcdef"
        val d = AboutBuildInfo.format(version = "0.1", buildType = "debug", commit = full)
        assertEquals(7, d.commit.length)
        assertEquals("a9a6513", d.commit)
    }

    @Test
    fun `short commits below 7 chars are returned unchanged`() {
        val d = AboutBuildInfo.format(version = "0.1", buildType = "debug", commit = "abc")
        assertEquals("abc", d.commit)
    }

    @Test
    fun `mixed-case build type is normalized to lower case`() {
        val d = AboutBuildInfo.format(version = "0.1", buildType = "ReLeAsE", commit = "abc1234")
        assertEquals("release", d.buildType)
    }
}
