package com.photosync.android.data

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Local, hour-rotated diagnostics. Keep enabled during beta support only. */
class DiagnosticLog(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
    private val lock = Any()

    fun append(message: String) = synchronized(lock) {
        val now = Instant.now()
        val hour = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC).format(now)
        File(directory, "photosync-$hour.log").appendText("${now}\t$message\n")
        directory.listFiles()?.filter { it.name.startsWith("photosync-") }
            ?.sortedByDescending { it.name }?.drop(24)?.forEach { it.delete() }
    }
}
