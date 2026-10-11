package com.photosync.android.data

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Local, hour-rotated diagnostics. Keep enabled during beta support only. */
class DiagnosticLog(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
    private companion object { val lock = Any() }

    fun append(message: String) = synchronized(lock) {
        runCatching {
            val now = Instant.now()
            val hour = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC).format(now)
            val target = File(directory, "photosync-$hour.log")
            // Hard cap even during retry storms; diagnostics must never fail an upload.
            if (target.length() > 512 * 1024) {
                val previous = File(directory, "photosync-$hour-previous.log")
                previous.delete()
                target.renameTo(previous)
            }
            target.appendText("${now}\t${message.take(2048)}\n")
            directory.listFiles()?.filter { it.name.startsWith("photosync-") }
                ?.sortedByDescending { it.name }?.drop(24)?.forEach { it.delete() }
        }
        Unit
    }
}
