package com.rocketgs.core

import kotlin.math.max

/**
 * Fixed-capacity history. Memory is allocated once; when full, the oldest half
 * is dropped so the data stays contiguous (cheap to draw a sub-range).
 */
class History(private val cap: Int) {
    val data = DoubleArray(cap)
    var size = 0; private set
    fun push(v: Double) {
        if (size == cap) {
            System.arraycopy(data, cap / 2, data, 0, cap - cap / 2)
            size = cap - cap / 2
        }
        data[size++] = v
    }
    fun clear() { size = 0 }
    operator fun get(i: Int) = data[i]
    fun last() = if (size > 0) data[size - 1] else Double.NaN
    /** First index with value >= x (values must be increasing, e.g. time). */
    fun lowerBound(x: Double): Int {
        var lo = 0; var hi = size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (data[m] < x) lo = m + 1 else hi = m }
        return lo
    }
}

/** Packet loss and duplicates from SEQ ($T and $G share one counter). */
class LinkStats {
    var received = 0L; private set
    var lost = 0L; private set
    var duplicates = 0L; private set
    var resets = 0L; private set
    private var haveSeq = false
    private var lastSeq = 0
    // Last 128 expected packets: true = received. For "recent loss %".
    private val window = BooleanArray(128)
    private var winPos = 0
    private var winFill = 0

    private fun mark(ok: Boolean) {
        window[winPos] = ok; winPos = (winPos + 1) % 128; if (winFill < 128) winFill++
    }

    /** Returns a description when something noteworthy happened, else null. */
    fun onSeq(seq: Int): String? {
        if (!haveSeq) { haveSeq = true; lastSeq = seq; received++; mark(true); return null }
        val delta = (seq - lastSeq) and 0xFFFF          // wraps correctly at 65535
        if (delta == 0) { duplicates++; return "duplicate SEQ" }
        if (delta <= 1000) {
            val gap = delta - 1
            lost += gap
            repeat(minOf(gap, 128)) { mark(false) }
            received++; mark(true); lastSeq = seq
            return null
        }
        resets++; received++; mark(true); lastSeq = seq
        return "SEQ jumped (flight computer restart?) - loss counting resynced"
    }

    fun totalLossPct(): Double { val n = received + lost; return if (n > 0) 100.0 * lost / n else 0.0 }
    fun recentLossPct(): Double {
        if (winFill == 0) return 0.0
        var n = 0
        for (i in 0 until winFill) if (!window[i]) n++
        return 100.0 * n / winFill
    }
}

data class Message(val wall: Double, val level: Level, val text: String)

/** Everything the screen shows. Only touched by the UI thread. */
class FlightData {
    var tm: Telemetry? = null; private set
    var gps: GpsFix? = null; private set
    var lastPacketWall = -1.0; private set
    var lastRssi: Int? = null; private set
    var link = LinkStats(); private set
    var tmCount = 0; private set
    var gpsCount = 0; private set
    var peakVel = 0.0; private set
    var peakG = 0.0; private set

    val t = History(Config.HISTORY)
    val alt = History(Config.HISTORY)
    val vel = History(Config.HISTORY)
    val g = History(Config.HISTORY)
    val f1 = History(Config.HISTORY)
    val f2 = History(Config.HISTORY)
    val rssi = History(Config.HISTORY)
    val roll = History(Config.HISTORY)
    val pitch = History(Config.HISTORY)
    val yaw = History(Config.HISTORY)
    val stateChanges = ArrayList<Pair<Double, Int>>()

    var originLat = 0.0; private set
    var originLon = 0.0; private set
    var hasOrigin = false; private set
    val gpsEast = History(Config.GPS_HISTORY)
    val gpsNorth = History(Config.GPS_HISTORY)
    private val gpsLat = History(Config.GPS_HISTORY)
    private val gpsLon = History(Config.GPS_HISTORY)

    val messages = ArrayList<Message>()
    val raw = ArrayList<String>()
    var warnCount = 0; private set

    private val arrivals = DoubleArray(64)
    private var arrPos = 0
    private var arrFill = 0

    fun clear() {
        tm = null; gps = null; lastPacketWall = -1.0; lastRssi = null; link = LinkStats()
        tmCount = 0; gpsCount = 0; peakVel = 0.0; peakG = 0.0; hasOrigin = false
        listOf(t, alt, vel, g, f1, f2, rssi, roll, pitch, yaw, gpsEast, gpsNorth, gpsLat, gpsLon).forEach { it.clear() }
        stateChanges.clear(); messages.clear(); raw.clear(); warnCount = 0
        arrPos = 0; arrFill = 0
    }

    fun addMessage(wall: Double, level: Level, text: String) {
        if (messages.size >= 500) messages.subList(0, 100).clear()
        if (level != Level.INFO) warnCount++
        messages += Message(wall, level, text)
    }

    /** Packets per second over the last 3 s. */
    fun packetRate(wallNow: Double): Double {
        var n = 0
        for (i in 0 until arrFill) if (wallNow - arrivals[i] <= 3.0) n++
        return n / 3.0
    }

    private fun packetArrived(seq: Int, r: Int?, wallNow: Double) {
        arrivals[arrPos] = wallNow; arrPos = (arrPos + 1) % 64; if (arrFill < 64) arrFill++
        lastPacketWall = wallNow
        if (r != null) lastRssi = r
        link.onSeq(seq)?.let { addMessage(wallNow, Level.WARN, "SEQ $seq: $it") }
    }

    fun apply(e: Event, wallNow: Double) {
        when (e) {
            is Event.Tm -> {
                val m = e.tm
                tm = m; tmCount++
                packetArrived(m.seq, m.rssi, wallNow)
                t.push(m.t); alt.push(m.alt); vel.push(m.vel); g.push(m.g); f1.push(m.f1); f2.push(m.f2)
                rssi.push(m.rssi?.toDouble() ?: Double.NaN)
                roll.push(m.roll); pitch.push(m.pitch); yaw.push(m.yaw)
                peakVel = max(peakVel, m.vel); peakG = max(peakG, m.g)
                if (stateChanges.isEmpty() || stateChanges.last().second != m.state) {
                    if (stateChanges.size > 200) stateChanges.removeAt(0)
                    stateChanges += m.t to m.state
                    if (stateChanges.size > 1)
                        addMessage(wallNow, if (m.state == 5) Level.ERROR else Level.INFO, "State -> ${Config.stateName(m.state)}")
                }
            }
            is Event.Gps -> {
                val f = e.fix
                gps = f; gpsCount++
                packetArrived(f.seq, f.rssi, wallNow)
                if (!hasOrigin) {
                    hasOrigin = true; originLat = f.lat; originLon = f.lon
                    addMessage(wallNow, Level.INFO, "GPS origin set to first fix")
                }
                val (east, north) = Geo.toLocalEN(originLat, originLon, f.lat, f.lon)
                gpsEast.push(east); gpsNorth.push(north); gpsLat.push(f.lat); gpsLon.push(f.lon)
            }
            is Event.Notice -> addMessage(wallNow, e.level, e.text)
            is Event.RawLine -> {
                if (raw.size >= 500) raw.subList(0, 100).clear()
                raw += e.text
            }
        }
    }

    fun setOriginToCurrent() {
        val f = gps ?: return
        originLat = f.lat; originLon = f.lon; hasOrigin = true
        gpsEast.clear(); gpsNorth.clear()
        for (i in 0 until gpsLat.size) {
            val (e, n) = Geo.toLocalEN(originLat, originLon, gpsLat[i], gpsLon[i])
            gpsEast.push(e); gpsNorth.push(n)
        }
    }

    fun distanceFromOrigin() = gps?.let { Geo.distanceM(originLat, originLon, it.lat, it.lon) } ?: 0.0
    fun bearingFromOrigin() = gps?.let { Geo.bearingDeg(originLat, originLon, it.lat, it.lon) } ?: 0.0
}

/** Spherical earth: plenty for a few km. */
object Geo {
    private const val R = 6371000.0
    private fun rad(d: Double) = Math.toRadians(d)

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1); val p2 = rad(lat2); val dp = p2 - p1; val dl = rad(lon2 - lon1)
        val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1); val p2 = rad(lat2); val dl = rad(lon2 - lon1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        val b = Math.toDegrees(Math.atan2(y, x))
        return if (b < 0) b + 360 else b
    }

    fun toLocalEN(lat0: Double, lon0: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val n = rad(lat - lat0) * R
        val e = rad(lon - lon0) * R * Math.cos(rad(lat0))
        return e to n
    }

    private val DIRS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    fun compass(bearing: Double) = DIRS[(((bearing + 22.5) / 45).toInt()) % 8]
}
