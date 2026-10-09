package com.rocketgs.core

import kotlin.math.abs

/**
 * Core tests, ported from the desktop app's tests/test_main.cpp.
 * Run from Android Studio (CoreTest, JUnit) or without Android at all:
 *   kotlinc app/src/main/java/com/rocketgs/core app/src/test/java/com/rocketgs/core/CoreSelfTest.kt -include-runtime -d t.jar
 *   java -cp t.jar com.rocketgs.core.CoreSelfTestKt
 */
object CoreSelfTest {
    private var pass = 0
    private val failures = ArrayList<String>()

    private fun check(cond: Boolean, what: String) { if (cond) pass++ else failures += what }
    private fun near(a: Double?, b: Double, what: String) = check(a != null && abs(a - b) < 1e-6, "$what: $a != $b")

    private fun feed(p: BlockParser, text: String): List<Event> {
        val out = ArrayList<Event>()
        var t = 0.0
        for (l in text.split("\n")) { t += 0.001; p.feedLine(l.removeSuffix("\r"), t, out) }
        return out
    }

    private val TELEM = """

========== TELEMETRI ==========
SEQ=123 OK  COUNT=45
RSSI=-71 dBm  RAW=0xB9
STATE=1 / BOOST
FLAGS=0x103 [ARM:1 C1:1 C2:0 P1:0 P2:0 D1:0 D2:0 GPS:0 BARO:1]
F1V=3.70 V, F2V=3.65 V
G=-1.02 g, ALT=12.3 m, V=-4.5 m/s, MAX=15.0 m
ROLL=1.0 deg, PITCH=-2.5 deg, YAW=359.9 deg
ARM DURUMU: ARMED / dikkat
BILGI: GPS fix yok; ${'$'}G uretilmez.
"""

    private val GPS = """

------------- GPS -------------
SEQ=124 OK  GPS_COUNT=3
RSSI=N/A
LAT=41.01234, LON=-28.97654
"""

    fun run(): List<String> {
        pass = 0; failures.clear()

        // numbers
        check(Parse.number("F1V=3.70 V, F2V=3.65 V", "V=") == null, "V= must not match inside F1V=")
        check(Parse.number("G=1 g, ALT=2 m, V=-4.5 m/s", "V=") == -4.5, "V parse")
        check(Parse.long("SEQ=65535 OK", "SEQ=") == 65535L, "SEQ parse")
        check(Parse.hex("FLAGS=0x1FF [", "FLAGS=") == 0x1FFL, "hex parse")
        check(Parse.number("ALT=abc", "ALT=") == null, "bad number")

        // telemetry block
        run {
            val p = BlockParser()
            val ev = feed(p, TELEM)
            val tms = ev.filterIsInstance<Event.Tm>()
            check(tms.size == 1, "one telemetry")
            tms.firstOrNull()?.tm?.let { t ->
                check(t.seq == 123 && t.rssi == -71 && t.state == 1 && t.flags == 0x103, "telemetry header fields")
                check(t.flag(0) && t.flag(1) && !t.flag(2) && t.flag(8), "flag bits")
                near(t.f1, 3.70, "f1"); near(t.f2, 3.65, "f2"); near(t.g, -1.02, "g"); near(t.alt, 12.3, "alt")
                near(t.vel, -4.5, "vel"); near(t.maxAlt, 15.0, "max"); near(t.roll, 1.0, "roll")
                near(t.pitch, -2.5, "pitch"); near(t.yaw, 359.9, "yaw")
            }
            check(p.badBlocks == 0 && p.unknownLines == 0, "clean telemetry")
            check(ev.filterIsInstance<Event.Notice>().size == 2, "trailing notices once")
            check(feed(p, TELEM).filterIsInstance<Event.Notice>().isEmpty(), "trailing not repeated")
        }

        // GPS block
        run {
            val ev = feed(BlockParser(), GPS).filterIsInstance<Event.Gps>()
            check(ev.size == 1, "one gps")
            ev.firstOrNull()?.fix?.let { check(it.seq == 124 && it.rssi == null, "gps seq/rssi"); near(it.lat, 41.01234, "lat"); near(it.lon, -28.97654, "lon") }
        }

        // corruption
        run {
            val p = BlockParser()
            feed(p, TELEM.replace("STATE=1 / BOOST\n", ""))
            check(p.badBlocks == 1, "missing line -> bad block")

            val p2 = BlockParser()
            val ev2 = feed(p2, TELEM.replace("G=-1.02 g, ALT=12.3 m,", "G=-1.0") + GPS)
            check(ev2.none { it is Event.Tm } && ev2.count { it is Event.Gps } == 1, "truncated telem, gps ok")
            check(p2.badBlocks == 1, "truncated counted once")

            val p3 = BlockParser()
            val ev3 = feed(p3, TELEM.substring(0, TELEM.indexOf("F1V")) + TELEM)
            check(ev3.count { it is Event.Tm } == 1 && p3.badBlocks == 1, "early header")

            val p4 = BlockParser()
            feed(p4, """
==========================================
E22 LoRa yer istasyonu baslatiliyor
==========================================
[E22 CFG] Config mode'a geciliyor...
[E22 CFG] Eski REG3 = 0x00
[E22] PACKET RSSI HAZIR
Beklenen formatlar:
${'$'}T,SEQ,ST,FLAGS_HEX,F1,F2,G,ALT,V,MAX,R,P,Y
${'$'}G,SEQ,LAT_E5,LON_E5
USB Serial Monitor: 115200 baud
------------------------------------------
[BILINMEYEN] hello
RSSI=-80 dBm  RAW=0xB0
[UYARI] 3 saniyeden uzun suredir gecerli LoRa paketi parse edilmedi.
""")
            check(p4.unknownLines == 0 && p4.badBlocks == 0, "banner recognised")

            val p6 = BlockParser()
            feed(p6, TELEM.replace("========== TELEMETRI ==========\n", ""))
            check(p6.badBlocks == 1 && p6.unknownLines == 0, "lost header -> one bad block")

            val p5 = BlockParser()
            val out = ArrayList<Event>()
            p5.feedLine("========== TELEMETRI ==========", 1.0, out)
            p5.feedLine("SEQ=1 OK  COUNT=1", 1.0, out)
            p5.tick(2.0, out)
            check(p5.badBlocks == 1, "stalled block")
        }

        // link stats
        run {
            val s = LinkStats()
            check(s.onSeq(65534) == null && s.onSeq(65535) == null && s.onSeq(0) == null && s.lost == 0L, "wrap is not loss")
            check(s.onSeq(3) == null && s.lost == 2L, "gap")
            check(s.onSeq(3) != null && s.duplicates == 1L, "duplicate")
            check(s.onSeq(10) == null && s.lost == 8L, "gap 2")
            check(s.onSeq(5) != null && s.resets == 1L, "reset")
            near(s.totalLossPct(), 100.0 * 8 / 14, "loss pct")
        }

        // geo
        check(abs(Geo.distanceM(40.0, 30.0, 41.0, 30.0) - 111195) < 50, "distance")
        check(abs(Geo.bearingDeg(40.0, 30.0, 41.0, 30.0)) < 1e-6, "bearing N")
        check(abs(Geo.bearingDeg(40.0, 30.0, 40.0, 30.01) - 90) < 0.1, "bearing E")

        // attitude: nose = body +X, pitch 90 => straight up; 80 => 10 deg tilt toward yaw
        run {
            val s = AttitudeSettings()
            check(Attitude.tilt(0.0, 90.0, 0.0, s, null).tiltDeg < 1e-6, "upright")
            val t = Attitude.tilt(0.0, 80.0, 63.0, s, null)
            check(abs(t.tiltDeg - 10.0) < 1e-6 && abs(t.towardDeg - 63.0) < 1e-6, "tilt 10 toward 63: $t")
            val tare = Attitude.bodyToEnu(30.0, 20.0, 10.0, s)
            check(Attitude.tilt(30.0, 20.0, 10.0, s, tare).tiltDeg < 1e-6, "tare makes current upright")
        }

        // simulator round trip through the parser
        run {
            val sim = FlightSim(SimOptions(lossPct = 0.0, garbagePct = 0.0), 42)
            val p = BlockParser()
            val out = ArrayList<Event>(); val lines = ArrayList<String>()
            var t = 0.0; var telem = 0; var maxAlt = 0.0; var states = 0
            while (!sim.finished) {
                lines.clear(); sim.step(lines); t += 0.2
                for (l in lines) p.feedLine(l, t, out)
                for (e in out) if (e is Event.Tm) { telem++; maxAlt = maxOf(maxAlt, e.tm.alt); states = states or (1 shl e.tm.state) }
                out.clear()
            }
            check(p.badBlocks == 0 && p.unknownLines == 0, "sim parses cleanly")
            check(telem > 1000, "sim length")
            check(maxAlt > 4000 && maxAlt < 9000, "sim apogee $maxAlt")
            check(states and 0xDF == 0xDF, "sim visits all normal states")
            println("  sim: $telem telemetry packets, apogee ${"%.1f".format(maxAlt)} m")
        }

        println("$pass passed, ${failures.size} failed")
        failures.forEach { println("FAIL $it") }
        return failures
    }
}

fun main() { if (CoreSelfTest.run().isNotEmpty()) kotlin.system.exitProcess(1) }
