package com.rocketgs.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class SimOptions(
    val telemHz: Double = 5.0,     // $T packets per second
    val gpsEvery: Int = 5,         // one $G after every N $T packets
    val lossPct: Double = 3.0,     // % of radio packets dropped
    val garbagePct: Double = 1.0,  // % of printed blocks corrupted
    val rssi: Boolean = true,
)

/**
 * Fake ~6 km flight that prints EXACTLY the text the CODE-001 receiver prints
 * (with ALT/MAX as 32-bit 'long', i.e. the fixed receiver), so the real parser
 * is exercised without hardware. Port of the desktop src/source/sim.cpp.
 */
class FlightSim(private val opt: SimOptions = SimOptions(), seed: Int = 1) {
    private var rng = if (seed == 0) 1 else seed
    var time = 0.0; private set
    private var landT = 0.0; private var apogeeT = 0.0
    private var alt = 0.0; private var vel = 0.0; private var acc = 0.0; private var maxAlt = 0.0
    private var roll = 0.0; private var tiltN = 0.0; private var tiltE = 0.0; private var rollRate = 0.0
    private val lat0 = 39.92500; private val lon0 = 32.83700
    private var driftE = 0.0; private var driftN = 0.0
    private var state = 0; private var sinceGps = 0
    private var seq = 0; private var rxSeq = 0; private var firstRx = true
    private var flags = 0x106    // C1 C2 BARO
    private var count = 0; private var gpsCount = 0

    val finished get() = (state == 7 && time > landT + 30) || time > 1500

    private fun rnd(): Double {          // xorshift32, deterministic per seed
        rng = rng xor (rng shl 13); rng = rng xor (rng ushr 17); rng = rng xor (rng shl 5)
        return (rng and 0xFFFFFF) / 16777216.0
    }
    private fun gauss() = (rnd() + rnd() + rnd() + rnd() - 2.0) * 1.7

    /** The Uno receiver's 16-bit int (used for every field except ALT/MAX). */
    private fun avrInt(v: Double): Int = v.roundToLong().toInt().toShort().toInt()
    private fun f(fmt: String, vararg a: Any) = String.format(Locale.US, fmt, *a)

    private fun seqLine(countKey: String, cnt: Int): String {
        val sb = StringBuilder("SEQ=$seq")
        if (firstRx) { sb.append(" FIRST"); firstRx = false }
        else {
            val d = (seq - rxSeq) and 0xFFFF
            when {
                d == 1 -> sb.append(" OK")
                d == 0 -> sb.append(" DUPLICATE")
                d < 32768 -> sb.append(" WARN lost~${(d - 1) and 0xFFFF} expected=${(rxSeq + 1) and 0xFFFF}")
                else -> sb.append(" OUT_OF_ORDER")
            }
        }
        rxSeq = seq
        sb.append("  $countKey=$cnt")
        return sb.toString()
    }

    private fun rssiLine(has: Boolean, dbm: Int) =
        if (!has) "RSSI=N/A" else f("RSSI=%d dBm  RAW=0x%02X", dbm, (dbm + 256) and 0xFF)

    private fun printTelemetry(out: MutableList<String>, rssiDbm: Int) {
        count++
        val f1 = avrInt((3.95 - time * 0.0008 + gauss() * 0.005) * 100)
        val f2 = avrInt((7.40 - time * 0.0012 + gauss() * 0.005) * 100)
        val gc = avrInt(acc / 9.80665 * 100)
        val altDm = (alt * 10).roundToLong(); val maxDm = (maxAlt * 10).roundToLong()
        val vDms = avrInt(vel * 10)
        val tilt = hypot(tiltN, tiltE)
        val az = Math.toDegrees(atan2(tiltE, tiltN))
        val pitch = 90.0 - tilt
        val r = ((roll + 540.0) % 360.0) - 180.0
        val rD = avrInt(r * 10); val pD = avrInt(pitch * 10); val yD = avrInt((if (az < 0) az + 360 else az) * 10)

        out += ""
        out += "========== TELEMETRI =========="
        out += seqLine("COUNT", count)
        out += rssiLine(opt.rssi, rssiDbm)
        out += "STATE=$state / ${Config.stateName(state)}"
        val names = listOf("ARM", "C1", "C2", "P1", "P2", "D1", "D2", "GPS", "BARO")
        out += f("FLAGS=0x%03X [", flags) + names.indices.joinToString(" ") { "${names[it]}:${(flags shr it) and 1}" } + "]"
        out += f("F1V=%.2f V, F2V=%.2f V", f1 / 100.0, f2 / 100.0)
        out += f("G=%.2f g, ALT=%.1f m, V=%.1f m/s, MAX=%.1f m", gc / 100.0, altDm / 10.0, vDms / 10.0, maxDm / 10.0)
        out += f("ROLL=%.1f deg, PITCH=%.1f deg, YAW=%.1f deg", rD / 10.0, pD / 10.0, yD / 10.0)
        out += if (flags and 1 != 0) "ARM DURUMU: ARMED / dikkat" else "ARM DURUMU: SAFE / ARM kapali"
        if (flags and 0x100 == 0) out += "UYARI: Barometre OK degil"
        if (flags and 0x080 == 0) out += "BILGI: GPS fix yok; \$G uretilmez."
    }

    private fun printGps(out: MutableList<String>, rssiDbm: Int) {
        gpsCount++
        val latE5 = ((lat0 + driftN / 111320.0) * 1e5).roundToLong()
        val lonE5 = ((lon0 + driftE / (111320.0 * cos(Math.toRadians(lat0)))) * 1e5).roundToLong()
        out += ""
        out += "------------- GPS -------------"
        out += seqLine("GPS_COUNT", gpsCount)
        out += rssiLine(opt.rssi, rssiDbm)
        out += f("LAT=%.5f, LON=%.5f", latE5 / 1e5, lonE5 / 1e5)
    }

    /** Advance one telemetry period; appends the printed lines. */
    fun step(lines: MutableList<String>) {
        val sub = 20
        val dt = 1.0 / opt.telemHz / sub
        val g0 = 9.80665
        repeat(sub) {
            time += dt
            val tilt = Math.toRadians(hypot(tiltN, tiltE))
            when (state) {
                0 -> {   // IDLE on the pad
                    if (time > 3) flags = flags or 0x001
                    if (time > 1) flags = flags or 0x080
                    if (time > 8) state = 1
                    acc = g0
                }
                1 -> {   // BOOST
                    val thrust = 11.0 * g0
                    val drag = 0.00012 * vel * vel
                    vel += (thrust - drag - g0 * cos(tilt)) * dt
                    acc = thrust - drag
                    rollRate = min(rollRate + 200 * dt, 220.0)
                    if (time > 13.0) state = 2
                }
                2 -> {   // COAST
                    val drag = 0.00012 * vel * abs(vel)
                    vel += (-drag - g0) * dt
                    acc = drag
                    rollRate *= (1 - 0.3 * dt)
                    if (vel <= 0) { state = 3; apogeeT = time; flags = flags or (1 shl 3) or (1 shl 5) }
                }
                3, 4 -> {   // APOGEE, DESCENT
                    val term = if (flags and (1 shl 6) != 0) -6.0 else -25.0
                    val dv = (term - vel) * min(1.0, 1.5 * dt)
                    vel += dv
                    acc = g0 + dv / dt
                    if (state == 3 && vel < -3 && time > apogeeT + 0.5) state = 4
                    if (alt < 300 && flags and (1 shl 6) == 0) flags = flags or (1 shl 4) or (1 shl 6)
                    driftE += 3.0 * dt; driftN += 1.0 * dt
                }
                6, 7 -> {   // LANDED, RECOVERY
                    vel = 0.0; acc = g0
                    if (state == 6 && time > landT + 15) state = 7
                }
            }
            if (state == 1 || state == 2) {
                val h = vel * sin(tilt) * dt
                val az = atan2(tiltE, tiltN)
                driftE += h * sin(az); driftN += h * cos(az)
            }
            alt += vel * dt
            if (state in 3..4 && alt <= 0) { alt = 0.0; vel = 0.0; state = 6; landT = time }
            if (alt < 0) alt = 0.0
            maxAlt = maxOf(maxAlt, alt)

            roll += rollRate * dt
            if (state <= 2) {
                if (state == 0) { tiltN = 2.0 + gauss() * 0.05; tiltE = 3.0 + gauss() * 0.05 }
                else { tiltN += 0.3 * dt + gauss() * 0.02; tiltE += 0.54 * dt + gauss() * 0.02 }
            } else if (state <= 4) {
                val target = 100 + 15 * sin(time * 1.3)
                val cur = hypot(tiltN, tiltE)
                val sc = if (cur > 0) (cur + (target - cur) * min(1.0, 2 * dt)) / cur else 1.0
                tiltN *= sc; tiltE *= sc
                rollRate = 20 * sin(time * 0.7)
            } else rollRate = 0.0
        }

        val range = sqrt(alt * alt + driftE * driftE + driftN * driftN)
        val rssiDbm = (-45 - 20 * log10(1 + range / 20) + gauss() * 2).roundToLong().toInt().coerceIn(-125, -20)

        fun emit(gps: Boolean) {
            seq = (seq + 1) and 0xFFFF
            if (rnd() * 100 < opt.lossPct) return          // lost on the air
            val block = ArrayList<String>()
            if (gps) printGps(block, rssiDbm) else printTelemetry(block, rssiDbm)
            if (rnd() * 100 < opt.garbagePct && block.size > 4) {
                val k = 2 + (rnd() * (block.size - 3)).toInt()
                if (rnd() < 0.5) block.removeAt(k) else block[k] = block[k].substring(0, block[k].length / 2)
            }
            lines += block
        }
        emit(false)
        if (flags and 0x080 != 0 && ++sinceGps >= opt.gpsEvery) { sinceGps = 0; emit(true) }
    }
}
