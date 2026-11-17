package io.narl.protongallery

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Keeps what a tester needs when the app closed unexpectedly and no cable is
 * attached: the Kotlin stack trace, or Android's record of a native crash or
 * freeze. Shown once on the next start, with a Copy button. Holds no media,
 * names of files or credentials: stack traces carry code locations only.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val SEEN = "crash-log"
    private const val LIMIT = 24_000

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "Crash on thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Record a failure that was caught, so a later report still shows it. */
    fun note(context: Context, error: Throwable) {
        runCatching { write(context.applicationContext, "Handled failure", error) }
    }

    private fun write(context: Context, title: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        File(context.filesDir, FILE).writeText(header(context, title) + trace.take(LIMIT))
    }

    private fun header(context: Context, title: String): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "$title at ${Instant.now()}\nProton Gallery $version, Android ${Build.VERSION.RELEASE} (${Build.MODEL})\n\n"
    }

    /**
     * The report from the last unexpected exit, or `null`. Each report is
     * returned once; the next call returns `null` until something new happens.
     */
    fun takeReport(context: Context): String? {
        val file = File(context.filesDir, FILE)
        val kotlin = file.takeIf { it.isFile }?.readText()
        val native = lastSystemExit(context)
        // Written by the Rust panic hook (see pg-mobile diagnostics).
        val panicFile = File(context.noBackupFilesDir, "gallery/last-panic.txt")
        val panic = panicFile.takeIf { it.isFile }?.readText()?.take(4_000)
        file.delete(); panicFile.delete()
        val report = when {
            kotlin != null && kotlin.startsWith("Crash") -> kotlin
            native != null -> native
            else -> return null
        }
        return if (panic != null) "$report\n\nLast Rust panic:\n$panic" else report
    }

    /** Android's record of a native crash or freeze since the last report. */
    private fun lastSystemExit(context: Context): String? {
        val preferences = context.getSharedPreferences(SEEN, Context.MODE_PRIVATE)
        val seen = preferences.getLong("seen", 0)
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching { manager.getHistoricalProcessExitReasons(null, 0, 5) }.getOrNull()
            ?.firstOrNull { it.timestamp > seen } ?: return null
        preferences.edit().putLong("seen", exit.timestamp).apply()
        val kind = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "App froze (not responding)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> return null
            ApplicationExitInfo.REASON_CRASH -> "Crash"
            else -> return null
        }
        val trace = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
        val text = trace?.let { if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) readable(it) else String(it).take(LIMIT) }
        return header(context, kind).replaceFirst(Regex(" at [^\\n]+"), " at ${Instant.ofEpochMilli(exit.timestamp)}") +
            "Reason: ${exit.description ?: kind}, status ${exit.status}\n\n" + (text ?: "No trace was recorded.")
    }

    // Frames and abort messages only: a tombstone also holds raw memory,
    // which may contain session data and must never reach a copied report.
    private val useful = Regex("""\.so\b|\.apk\b|panick|overflow|[Aa]bort|SIG[A-Z]{2,}|thread '|pg_mobile""")

    /** A native tombstone is binary; keep its readable frame and abort strings. */
    private fun readable(bytes: ByteArray): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 6 && useful.containsMatchIn(run)) out.append(run).append('\n')
            run.clear()
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
            if (out.length > LIMIT) break
        }
        flush()
        return out.toString().take(LIMIT)
    }
}
