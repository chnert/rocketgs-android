package com.rocketgs.core

import java.io.Writer
import java.util.Locale

/**
 * bytes -> lines -> raw log -> parser -> events.
 * The raw log is written BEFORE parsing, so it is the ground truth and can be
 * replayed later (format "seconds<TAB>line", same as the desktop app's logs).
 */
class LinePipeline(private val log: Writer?, private val emitRaw: Boolean = true) {
    val parser = BlockParser()
    private val line = StringBuilder()

    fun feedBytes(data: ByteArray, n: Int, t: Double, out: MutableList<Event>) {
        for (i in 0 until n) {
            val c = (data[i].toInt() and 0xFF).toChar()
            when {
                c == '\r' -> {}
                c == '\n' -> { feedLine(line.toString(), t, out); line.setLength(0) }
                line.length < 512 -> line.append(c)          // runaway line guard
            }
        }
    }

    fun feedLine(text: String, t: Double, out: MutableList<Event>) {
        log?.let {
            it.write(String.format(Locale.US, "%.3f\t", t)); it.write(text); it.write("\n"); it.flush()
        }
        if (emitRaw && text.isNotEmpty()) out += Event.RawLine(t, text)
        parser.feedLine(text, t, out)
    }

    fun tick(t: Double, out: MutableList<Event>) = parser.tick(t, out)

    fun close() { try { log?.close() } catch (_: Exception) {} }

    companion object {
        /** Splits "12.345<TAB>text" (our log) or plain text (a raw capture). */
        fun splitLogLine(raw: String): Pair<Double?, String> {
            val tab = raw.indexOf('\t')
            if (tab in 1..19) raw.substring(0, tab).toDoubleOrNull()?.let { return it to raw.substring(tab + 1) }
            return null to raw
        }
    }
}
