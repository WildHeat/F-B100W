package dev.trrrst.watch.listen

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The listening test's own log, `filesDir/listen.log` (app-private, read with
 *  `run-as dev.trrrst.watch cat files/listen.log`). Logcat doesn't reach back a whole
 *  night, so the test writes here too. Every line also goes to logcat (`TrrrstListen`). */
class ListenLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "listen.log")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(message: String) {
        android.util.Log.d("TrrrstListen", message)
        try {
            file.appendText("${stamp.format(Date())}  $message\n")
            if (file.length() > MAX_BYTES) file.writeText(LogTrim.trim(file.readText(), KEEP_CHARS))
        } catch (e: Exception) {
            android.util.Log.w("TrrrstListen", "couldn't write listen.log", e)
        }
    }

    companion object {
        const val MAX_BYTES = 200_000L
        const val KEEP_CHARS = 100_000
    }
}

/** Pure part of [ListenLog], unit-tested. */
object LogTrim {
    /** Keep roughly the last [keep] characters of [text], starting at a whole line. */
    fun trim(text: String, keep: Int): String {
        if (text.length <= keep) return text
        val cut = text.length - keep
        val lineStart = text.indexOf('\n', cut - 1).let { if (it < 0) cut else it + 1 }
        return "(older lines removed)\n" + text.substring(lineStart)
    }
}
