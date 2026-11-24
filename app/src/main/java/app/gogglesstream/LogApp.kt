package app.gogglesstream

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session logger: writes Download/Sim-Link/Sim-Link_<time>.txt with the app's logcat, screen
 * lifecycle events and crash stack traces, and notes when the previous session ended uncleanly.
 */
class LogApp : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "SimLinkLog"
        private const val MAX_LOGCAT_BYTES = 26_214_400L
        private val LOCK = Any()
        @Volatile private var out: OutputStream? = null
        private var sessionRef = ""
        private var whereHuman = "unavailable"
        private var logcatBytes = 0L
        private var capNoted = false

        private fun ts() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        private fun rawWrite(s: String) {
            synchronized(LOCK) {
                val o = out ?: return
                try { o.write(s.toByteArray(Charsets.UTF_8)); o.flush() } catch (_: Throwable) {}
            }
        }

        /** Adds a timestamped line to the session log (safe to call from anywhere, even before init). */
        fun event(s: String) = rawWrite("[" + ts() + "] " + s + "\n")

        private fun stackToString(t: Throwable): String {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            t.printStackTrace(pw)
            pw.flush()
            return sw.toString()
        }

        private fun safeLog(what: String, t: Throwable) {
            try { Log.e(TAG, "init step failed: $what", t) } catch (_: Throwable) {}
        }
    }

    private var liveActivities = 0

    override fun onCreate() {
        super.onCreate()
        try { openSessionFile() } catch (t: Throwable) { safeLog("openSessionFile", t) }
        try { writeHeader() } catch (t: Throwable) { safeLog("writeHeader", t) }
        try { handlePreviousSession() } catch (t: Throwable) { safeLog("previousSession", t) }
        try { installCrashHandler() } catch (t: Throwable) { safeLog("crashHandler", t) }
        try { registerActivityLifecycleCallbacks(this) } catch (t: Throwable) { safeLog("callbacks", t) }
        try { startLogcatThread() } catch (t: Throwable) { safeLog("logcat", t) }
    }

    // ------------------------------------------------------------------ session file

    private fun openSessionFile() {
        val name = "Sim-Link_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Sim-Link")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val stream = contentResolver.openOutputStream(uri, "w")
                if (stream != null) {
                    out = stream
                    sessionRef = uri.toString()
                    whereHuman = "Download/Sim-Link/$name"
                    return
                }
            }
        } catch (t: Throwable) { safeLog("mediastore", t) }
        try {
            val dir = getExternalFilesDir("logs") ?: File(filesDir, "logs")
            dir.mkdirs()
            val f = File(dir, name)
            out = FileOutputStream(f, true)
            sessionRef = f.absolutePath
            whereHuman = f.absolutePath + " (Download folder was not available)"
        } catch (t: Throwable) { safeLog("fallback", t) }
    }

    private fun writeHeader() {
        var ver = "?"
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            ver = pi.versionName + " (" + pi.versionCode + ")"
        } catch (_: Throwable) {}
        rawWrite(
            "==================== Sim-Link log ====================\n" +
                "Session start : ${ts()}\n" +
                "Saved to      : $whereHuman\n" +
                "App           : $packageName v$ver\n" +
                "Device        : ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Android       : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "PID           : ${Process.myPid()}\n" +
                "======================================================\n",
        )
    }

    // ------------------------------------------------------------------ previous-session detection

    private fun markerFile() = File(filesDir, "session_active")

    private fun writeMarker() {
        try { FileOutputStream(markerFile(), false).use { it.write(sessionRef.toByteArray(Charsets.UTF_8)) } } catch (_: Throwable) {}
    }

    private fun deleteMarker() {
        try { markerFile().delete() } catch (_: Throwable) {}
    }

    private fun appendToPrevious(ref: String, text: String) {
        try {
            val o: OutputStream? = if (ref.startsWith("content:"))
                contentResolver.openOutputStream(Uri.parse(ref), "wa")
            else FileOutputStream(File(ref), true)
            o?.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
        } catch (_: Throwable) {}
    }

    private fun handlePreviousSession() {
        val marker = markerFile()
        var prevRef: String? = null
        if (marker.exists()) {
            val line = try {
                BufferedReader(InputStreamReader(FileInputStream(marker), "UTF-8")).use { it.readLine() }
            } catch (_: Throwable) { null }
            if (line != null && line.isNotEmpty()) prevRef = line
        }
        var exits = ""
        if (Build.VERSION.SDK_INT >= 30) {
            try { exits = ExitReader.describeNew(this) } catch (t: Throwable) { safeLog("exitinfo", t) }
        }
        if (marker.exists()) {
            val msg = "[" + ts() + "] === PREVIOUS SESSION ENDED WITHOUT A CLEAN CLOSE " +
                "(crash, force stop, killed by system, ANR, or swiped away) ===\n" + exits
            prevRef?.let { appendToPrevious(it, msg) }
            rawWrite(msg)
        } else if (exits.isNotEmpty()) {
            rawWrite("[" + ts() + "] Previous process exit info:\n" + exits)
        }
        writeMarker()
    }

    private object ExitReader {
        fun describeNew(app: Application): String {
            val prefs = app.getSharedPreferences("simlink_log", 0)
            val last = prefs.getLong("last_exit_ts", 0L)
            val am = app.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val list = am.getHistoricalProcessExitReasons(app.packageName, 0, 8)
            val sb = StringBuilder()
            var newest = last
            for (i in list) {
                val t = i.timestamp
                if (t > last) {
                    if (t > newest) newest = t
                    sb.append("  System-reported exit at ")
                        .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t)))
                        .append(" | reason=").append(reasonName(i.reason))
                        .append(" | status=").append(i.status)
                        .append(" | importance=").append(i.importance)
                        .append(" | pid=").append(i.pid)
                    i.description?.let { sb.append(" | ").append(it) }
                    sb.append('\n')
                }
            }
            prefs.edit().putLong("last_exit_ts", newest).apply()
            return sb.toString()
        }

        fun reasonName(r: Int) = when (r) {
            1 -> "EXIT_SELF (app called exit)"
            2 -> "SIGNALED (killed by signal)"
            3 -> "LOW_MEMORY (killed by system)"
            4 -> "CRASH (Java exception)"
            5 -> "CRASH_NATIVE"
            6 -> "ANR (app not responding)"
            7 -> "INITIALIZATION_FAILURE"
            8 -> "PERMISSION_CHANGE"
            9 -> "EXCESSIVE_RESOURCE_USAGE"
            10 -> "USER_REQUESTED (force stop / removed by user)"
            11 -> "USER_STOPPED"
            12 -> "DEPENDENCY_DIED"
            13 -> "OTHER"
            else -> "UNKNOWN($r)"
        }
    }

    // ------------------------------------------------------------------ crash + logcat

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                val sb = StringBuilder()
                sb.append("\n[").append(ts()).append("] ============ APP CRASH (uncaught exception) ============\n")
                sb.append("Thread: ").append(thread?.name ?: "?").append('\n')
                sb.append(stackToString(t))
                sb.append("========================================================\n")
                rawWrite(sb.toString())
                Thread.sleep(250)
                event("Process is being terminated after crash.")
            } catch (_: Throwable) {}
            if (previous != null) previous.uncaughtException(thread, t)
            else { Process.killProcess(Process.myPid()); System.exit(10) }
        }
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() { event("JVM shutdown hook executed (process exiting).") }
        })
    }

    private fun startLogcatThread() {
        val th = Thread({
            try {
                val p = ProcessBuilder("logcat", "--pid=" + Process.myPid(), "-v", "threadtime")
                    .redirectErrorStream(true).start()
                val r = BufferedReader(InputStreamReader(p.inputStream, "UTF-8"), 8192)
                while (true) {
                    val line = r.readLine() ?: return@Thread
                    if (logcatBytes > MAX_LOGCAT_BYTES) {
                        if (!capNoted) {
                            capNoted = true
                            event("Logcat size cap (25 MB) reached - further logcat lines suppressed. Events and crashes are still recorded.")
                        }
                    } else {
                        logcatBytes += line.length + 1
                        rawWrite(line + "\n")
                    }
                }
            } catch (t: Throwable) {
                event("Logcat capture stopped: $t")
            }
        }, "SimLink-logcat")
        th.isDaemon = true
        th.start()
    }

    // ------------------------------------------------------------------ screen lifecycle

    private fun n(a: Activity) = a.javaClass.simpleName

    override fun onActivityCreated(a: Activity, b: Bundle?) {
        liveActivities++
        event("SCREEN opened: " + n(a) + (if (b != null) " (restored)" else ""))
        if (liveActivities == 1) writeMarker()
    }
    override fun onActivityStarted(a: Activity) { event("SCREEN started: " + n(a)) }
    override fun onActivityResumed(a: Activity) { event("SCREEN resumed (foreground): " + n(a)) }
    override fun onActivityPaused(a: Activity) { event("SCREEN paused: " + n(a)) }
    override fun onActivityStopped(a: Activity) { event("SCREEN stopped (background): " + n(a)) }
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {
        liveActivities--
        val finishing = a.isFinishing
        val config = a.isChangingConfigurations
        event("SCREEN destroyed: " + n(a) + " finishing=" + finishing + " configChange=" + config)
        if (liveActivities <= 0 && finishing && !config) {
            event("=== APP CLOSED (all screens finished) ===")
            deleteMarker()
        }
    }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); event("MEMORY onTrimMemory level=$level") }
    @Suppress("DEPRECATION")
    override fun onLowMemory() { super.onLowMemory(); event("MEMORY onLowMemory") }
}
