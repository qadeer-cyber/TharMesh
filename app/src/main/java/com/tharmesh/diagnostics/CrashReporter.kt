// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.diagnostics

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Lightweight crash diagnostics used to track down platform-specific
 * crashes (e.g. an Android 9 issue that does not reproduce on later
 * versions).
 *
 * Design goals:
 *   1. Capture the full stack trace + device metadata the moment the
 *      default uncaught-exception handler fires.
 *   2. Persist the trace to the app's private [filesDir] AND to the
 *      app-scoped external files dir (so the user can grab it via a
 *      file manager without adb if the in-app viewer also misbehaves).
 *   3. Append a `BREADCRUMB` line for every checkpoint the startup
 *      path hits, so we can localize the crash to a specific step even
 *      if the exception handler itself fails to write.
 *   4. On the next cold start, if a crash file is pending, bypass all
 *      of the normal TharMesh initialisation and open the bare-bones
 *      [CrashViewerActivity] — it uses a plain platform theme with
 *      no `?attr/tm*` or Material lookups, so a theme-related API 28
 *      issue cannot suppress the dialog.
 */
object CrashReporter {

    private const val TAG = "TharMeshCrash"
    private const val CRASH_FILE = "crash.txt"
    private const val BREADCRUMB_FILE = "breadcrumbs.txt"
    private const val MAX_BYTES = 128 * 1024

    @SuppressLint("StaticFieldLeak")
    private var appContext: Context? = null

    @JvmStatic
    fun install(app: Application) {
        appContext = app.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(app.applicationContext, thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to persist crash", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Append a timestamped line to [BREADCRUMB_FILE] so we can see which
     * startup step was last reached before a crash. Call sites should be
     * cheap — just a tag string, no formatting. Safe to call from any
     * thread; writes are best-effort and never throw.
     */
    @JvmStatic
    fun checkpoint(tag: String) {
        val ctx = appContext ?: return
        try {
            val line = System.currentTimeMillis().toString() + " " + tag + "\n"
            val f = File(ctx.filesDir, BREADCRUMB_FILE)
            // Cap breadcrumb file at 16 KB so we never fill storage.
            if (f.exists() && f.length() > 16 * 1024) f.delete()
            f.appendText(line)
        } catch (t: Throwable) {
            // Swallow — breadcrumbs are best-effort.
        }
    }

    @JvmStatic
    fun hasPendingCrash(context: Context): Boolean {
        return File(context.filesDir, CRASH_FILE).exists()
    }

    @JvmStatic
    fun readCrash(context: Context): String {
        val main = File(context.filesDir, CRASH_FILE)
        val bread = File(context.filesDir, BREADCRUMB_FILE)
        val sb = StringBuilder()
        if (bread.exists()) {
            sb.append("--- BREADCRUMBS (last startup) ---\n")
            sb.append(runCatching { bread.readText() }.getOrDefault(""))
            sb.append("\n")
        }
        if (main.exists()) {
            sb.append(runCatching { main.readText() }.getOrDefault(""))
        }
        return sb.toString()
    }

    @JvmStatic
    fun clearCrash(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
        File(context.filesDir, BREADCRUMB_FILE).delete()
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("TharMesh crash report")
        pw.println("timestamp: " + System.currentTimeMillis())
        pw.println("thread:    " + thread.name)
        pw.println("android:   " + android.os.Build.VERSION.RELEASE + " (sdk " + android.os.Build.VERSION.SDK_INT + ")")
        pw.println("device:    " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        pw.println("abi:       " + android.os.Build.SUPPORTED_ABIS.joinToString(","))
        pw.println("--- stack trace ---")
        throwable.printStackTrace(pw)
        var cause: Throwable? = throwable.cause
        var depth = 0
        while (cause != null && depth < 8) {
            pw.println("--- caused by ---")
            cause.printStackTrace(pw)
            cause = cause.cause
            depth++
        }
        pw.flush()
        val text = sw.toString().take(MAX_BYTES)
        Log.e(TAG, text)
        // Private copy — always succeeds.
        runCatching {
            File(context.filesDir, CRASH_FILE).writeText(text)
        }
        // External copy so the user can grab it via a file manager if the
        // in-app viewer fails. App-scoped external files dir — works on
        // API 19+ without WRITE_EXTERNAL_STORAGE.
        runCatching {
            val ext = context.getExternalFilesDir(null)
            if (ext != null) {
                if (!ext.exists()) ext.mkdirs()
                File(ext, CRASH_FILE).writeText(text)
            }
        }
    }
}

/**
 * Minimal, theme-free activity used to surface a persisted crash log on
 * the next launch. Kept in the same file as [CrashReporter] so review
 * can audit the whole diagnostic path in one read.
 *
 * Uses the platform's default theme (no `?attr/tm*` attrs, no Material
 * parent), so a theme-related crash cannot also bring this Activity
 * down. The layout is built programmatically to avoid any resource
 * inflation dependency.
 */
class CrashViewerActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ctx = this
        val body = CrashReporter.readCrash(ctx).ifBlank { "(no crash file found)" }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 64, 24, 24)
        }

        val title = TextView(ctx).apply {
            text = "TharMesh crashed on last launch"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "Please tap Copy and send the text back to the developer."
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 8, 0, 16)
        }
        root.addView(subtitle)

        val scroll = ScrollView(ctx)
        val trace = TextView(ctx).apply {
            text = body
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE)
            textSize = 11f
            setPadding(0, 0, 0, 16)
            setTextIsSelectable(true)
        }
        scroll.addView(trace)
        val scrollLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        root.addView(scroll, scrollLp)

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 16, 0, 0)
        }

        val copyBtn = Button(ctx).apply {
            text = "Copy"
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("TharMesh crash", body))
                Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        buttons.addView(copyBtn)

        val shareBtn = Button(ctx).apply {
            text = "Share"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TharMesh crash log")
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                ctx.startActivity(Intent.createChooser(intent, "Share crash log"))
            }
        }
        buttons.addView(shareBtn)

        val dismissBtn = Button(ctx).apply {
            text = "Dismiss"
            setOnClickListener {
                CrashReporter.clearCrash(ctx)
                Toast.makeText(ctx, "Crash cleared — restart the app", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        buttons.addView(dismissBtn)

        root.addView(buttons)

        setContentView(root)
    }
}
