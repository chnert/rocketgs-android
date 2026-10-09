package com.rocketgs.core

/**
 * Parses the human-readable text the CODE-001 receiver prints on USB.
 * Port of the desktop app's src/parser/block_parser.cpp (same rules, same tests).
 *
 *   ========== TELEMETRI ==========      <- header, starts a $T block
 *   SEQ=123 OK  COUNT=45
 *   RSSI=-71 dBm  RAW=0xB9               (or RSSI=N/A)
 *   STATE=1 / BOOST
 *   FLAGS=0x003 [ARM:1 C1:1 ...]
 *   F1V=3.70 V, F2V=3.65 V
 *   G=1.02 g, ALT=12.3 m, V=4.5 m/s, MAX=12.3 m
 *   ROLL=1.0 deg, PITCH=2.0 deg, YAW=3.0 deg   <- committed here
 *   ARM DURUMU / UYARI / BILGI lines             (trailing notices)
 *
 *   ------------- GPS -------------       <- header, starts a $G block
 *   SEQ=124 OK  GPS_COUNT=3
 *   RSSI=-71 dBm  RAW=0xB9
 *   LAT=41.01234, LON=28.97654           <- committed here
 *
 * Anything unexpected inside a block rejects the whole block and counts it in
 * [badBlocks], so corruption is visible instead of producing wrong numbers.
 */
class BlockParser {
    private enum class Mode { IDLE, TELEM, GPS }

    var badBlocks = 0; private set
    var unknownLines = 0; private set

    private var mode = Mode.IDLE
    private var blockStart = 0.0
    private var have = 0
    private var restOfAborted = false

    // fields of the block being read
    private var seq = 0
    private var rssi: Int? = null
    private var state = 0
    private var flags = 0
    private var f1 = 0.0; private var f2 = 0.0
    private var g = 0.0; private var alt = 0.0; private var vel = 0.0; private var maxAlt = 0.0
    private var lat = 0.0; private var lon = 0.0

    // Trailing lines repeat after every packet: report only changes.
    private var prevTrailing = mutableListOf<String>()
    private var curTrailing = mutableListOf<String>()

    fun reset() {
        mode = Mode.IDLE; have = 0; restOfAborted = false
        prevTrailing.clear(); curTrailing.clear()
    }

    /** Aborts a block that never finished (stream stalled). */
    fun tick(t: Double, out: MutableList<Event>) {
        if (mode != Mode.IDLE && t - blockStart > 0.5) abortBlock(t, "incomplete (stream stalled)", out)
    }

    fun feedLine(line: String, t: Double, out: MutableList<Event>) {
        tick(t, out)
        if (mode == Mode.IDLE) { handleIdleLine(line, t, out); return }
        if (isTelemHeader(line) || isGpsHeader(line)) { handleIdleLine(line, t, out); return }

        fun field(bit: Int): Boolean { if (have and bit != 0) return false; have = have or bit; return true }
        fun fail(why: String) {
            abortBlock(t, why, out)
            // A broken field line is already counted in the bad block.
            if (FIELD_PREFIXES.any { line.startsWith(it) }) return
            handleIdleLine(line, t, out)
        }

        when {
            line.startsWith("SEQ=") -> {
                val v = Parse.long(line, "SEQ=")
                if (!field(F_SEQ) || v == null) return fail("bad SEQ line")
                seq = (v and 0xFFFF).toInt()
            }
            line.startsWith("RSSI=") -> {
                if (!field(F_RSSI)) return fail("duplicate RSSI line")
                rssi = if (line.startsWith("RSSI=N/A")) null
                       else Parse.long(line, "RSSI=")?.toInt() ?: return fail("bad RSSI line")
            }
            mode == Mode.GPS -> {
                if (!line.startsWith("LAT=")) return fail("unexpected line")
                if (have != GPS_ALL) return fail("LAT line before SEQ/RSSI")
                val la = Parse.number(line, "LAT="); val lo = Parse.number(line, "LON=")
                if (la == null || lo == null) return fail("bad LAT/LON line")
                if (la < -90 || la > 90 || lo < -180 || lo > 180) return fail("LAT/LON out of range")
                out += Event.Gps(GpsFix(blockStart, seq, la, lo, rssi))
                mode = Mode.IDLE; have = 0
            }
            line.startsWith("STATE=") -> {
                val v = Parse.long(line, "STATE=")
                if (!field(F_STATE) || v == null) return fail("bad STATE line")
                state = v.toInt()
            }
            line.startsWith("FLAGS=") -> {
                val v = Parse.hex(line, "FLAGS=")
                if (!field(F_FLAGS) || v == null) return fail("bad FLAGS line")
                flags = (v and 0xFFFF).toInt()
            }
            line.startsWith("F1V=") -> {
                val a = Parse.number(line, "F1V="); val b = Parse.number(line, "F2V=")
                if (!field(F_VOLT) || a == null || b == null) return fail("bad F1V/F2V line")
                f1 = a; f2 = b
            }
            line.startsWith("G=") -> {
                val a = Parse.number(line, "G="); val b = Parse.number(line, "ALT=")
                val c = Parse.number(line, "V="); val d = Parse.number(line, "MAX=")
                if (!field(F_GAV) || a == null || b == null || c == null || d == null) return fail("bad G/ALT/V/MAX line")
                g = a; alt = b; vel = c; maxAlt = d
            }
            line.startsWith("ROLL=") -> {
                if (have != TELEM_ALL) return fail("ROLL line before all other fields")
                val r = Parse.number(line, "ROLL="); val p = Parse.number(line, "PITCH="); val y = Parse.number(line, "YAW=")
                if (r == null || p == null || y == null) return fail("bad ROLL/PITCH/YAW line")
                out += Event.Tm(Telemetry(blockStart, seq, state, flags, f1, f2, g, alt, vel, maxAlt, r, p, y, rssi))
                mode = Mode.IDLE; have = 0
            }
            else -> fail("unexpected line")
        }
    }

    // ------------------------------------------------------------------
    private fun abortBlock(t: Double, why: String, out: MutableList<Event>) {
        if (mode == Mode.IDLE) return
        badBlocks++
        out += Event.Notice(t, Level.WARN, (if (mode == Mode.TELEM) "Bad telemetry block: " else "Bad GPS block: ") + why)
        mode = Mode.IDLE; have = 0
        restOfAborted = true   // the block's remaining lines are already counted
    }

    private fun beginBlock(m: Mode, t: Double, out: MutableList<Event>) {
        if (mode != Mode.IDLE) abortBlock(t, "next header arrived early", out)
        if (m == Mode.TELEM) {
            for (p in prevTrailing) if (p !in curTrailing) out += Event.Notice(t, Level.INFO, "No longer reported: $p")
            prevTrailing = curTrailing
            curTrailing = mutableListOf()
        }
        rssi = null
        mode = m; have = 0; blockStart = t; restOfAborted = false
    }

    private fun handleTrailing(line: String, t: Double, out: MutableList<Event>) {
        if (line in curTrailing) return
        curTrailing += line
        if (line !in prevTrailing) {
            val lvl = if (line.startsWith("UYARI") || line.contains("ARMED")) Level.WARN else Level.INFO
            out += Event.Notice(t, lvl, "Receiver: $line")
        }
    }

    private fun handleIdleLine(line: String, t: Double, out: MutableList<Event>) {
        if (line.isEmpty()) return
        if (isTelemHeader(line)) { beginBlock(Mode.TELEM, t, out); return }
        if (isGpsHeader(line)) { beginBlock(Mode.GPS, t, out); return }

        if (line.startsWith("ARM DURUMU") || line.startsWith("UYARI:") || line.startsWith("BILGI:")) {
            handleTrailing(line, t, out); return
        }
        if (line.startsWith("[UYARI]") || line.startsWith("[PARSER HATA]") || line.startsWith("[HATA]") ||
            line.startsWith("[BILINMEYEN]")) {
            out += Event.Notice(t, Level.WARN, "Receiver: $line"); return
        }
        if (line.startsWith("[E22")) {
            val bad = line.contains("HATA") || line.contains("ACILAMADI")
            out += Event.Notice(t, if (bad) Level.WARN else Level.INFO, "Receiver: $line"); return
        }
        if (line.contains("yer istasyonu baslatiliyor")) {
            out += Event.Notice(t, Level.WARN, "Receiver (re)started"); return
        }
        // Startup banner and the RSSI line printed after [BILINMEYEN]: ignore.
        if (line.all { it == '=' } || line.all { it == '-' } || line.startsWith("Beklenen formatlar") ||
            line.startsWith("\$T,") || line.startsWith("\$G,") || line.startsWith("USB Serial") ||
            line.startsWith("Paketler yine") || line.startsWith("RSSI=")) return

        // Field lines without a header: the header itself was corrupted.
        // Drop them and count the whole block once, at its last line.
        if (FIELD_PREFIXES.any { line.startsWith(it) }) {
            if (line.startsWith("ROLL=") || line.startsWith("LAT=")) {
                if (!restOfAborted) {
                    badBlocks++
                    out += Event.Notice(t, Level.WARN, "Bad block: header line lost")
                }
                restOfAborted = false
            }
            return
        }

        unknownLines++
        out += Event.Notice(t, Level.WARN, "Unrecognized line: " + line.take(80))
    }

    private fun isTelemHeader(l: String) = l.startsWith("==========") && l.contains("TELEMETRI")
    private fun isGpsHeader(l: String) = l.startsWith("-------------") && l.contains("GPS")

    companion object {
        private const val F_SEQ = 1; private const val F_RSSI = 2; private const val F_STATE = 4
        private const val F_FLAGS = 8; private const val F_VOLT = 16; private const val F_GAV = 32
        private const val TELEM_ALL = 63; private const val GPS_ALL = 3
        private val FIELD_PREFIXES = listOf("SEQ=", "STATE=", "FLAGS=", "F1V=", "G=", "ROLL=", "LAT=")
    }
}

/** "KEY=value" helpers. The key must be at the start or after ' ' / ',' so "V=" does not match "F1V=". */
object Parse {
    private fun valueStart(line: String, key: String): Int {
        var pos = 0
        while (true) {
            val i = line.indexOf(key, pos)
            if (i < 0) return -1
            if (i == 0 || line[i - 1] == ' ' || line[i - 1] == ',') return i + key.length
            pos = i + 1
        }
    }

    private fun token(line: String, key: String, allowed: (Char) -> Boolean): String? {
        val s = valueStart(line, key)
        if (s < 0) return null
        var e = s
        while (e < line.length && allowed(line[e])) e++
        return if (e > s) line.substring(s, e) else null
    }

    /** Locale independent: '.' is always the decimal point. */
    fun number(line: String, key: String): Double? =
        token(line, key) { it.isDigit() || it == '.' || it == '-' }?.toDoubleOrNull()

    fun long(line: String, key: String): Long? =
        token(line, key) { it.isDigit() || it == '-' }?.toLongOrNull()

    fun hex(line: String, key: String): Long? {
        val s = valueStart(line, key)
        if (s < 0) return null
        var v = line.substring(s)
        if (v.startsWith("0x") || v.startsWith("0X")) v = v.substring(2)
        val h = v.takeWhile { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        return if (h.isEmpty()) null else h.toLongOrNull(16)
    }
}
