package com.dashdeck.data

import android.content.Context
import java.io.File

/**
 * Copies DashDeck's own log lines (and crashes) into a file, because the phone's shared log fills up
 * in seconds and a drive's worth is gone by the time the phone is plugged into a computer. Read it
 * with `adb shell cat /sdcard/Android/data/com.dashdeck/files/dashdeck.log`.
 */
object DiagnosticsLog {
    fun start(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, "dashdeck.log")
        Thread({ record(file) }, "DashDeck log").apply { isDaemon = true }.start()
    }

    private fun record(file: File) = runCatching {
        // An app may read its own process's log lines; DashDeck's tag plus any crash, from now on.
        val process = ProcessBuilder("logcat", "-T", "1", "-v", "time", "-s", "DashDeck:V", "AndroidRuntime:E")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (file.length() > MAX_BYTES) {
                    // Keep the newer half so the latest drive is always there.
                    val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                    file.writeText(keep.substringAfter('\n'))
                }
                file.appendText(line + "\n")
            }
        }
    }

    private const val MAX_BYTES = 512L * 1024
}
