package com.rocketgs.core

// Plain data passed from a data source to the UI. No Android code in core/,
// so it can be tested on any JVM (see app/src/test).

/** One "$T" packet in real units (same values the CODE-001 receiver prints). */
data class Telemetry(
    val t: Double,          // data time [s]
    val seq: Int,           // 0..65535
    val state: Int,
    val flags: Int,
    val f1: Double, val f2: Double,   // [V]
    val g: Double,                    // [g]
    val alt: Double, val vel: Double, val maxAlt: Double, // [m], [m/s], [m]
    val roll: Double, val pitch: Double, val yaw: Double, // [deg]
    val rssi: Int?,                   // [dBm], null = N/A
) {
    fun flag(bit: Int) = (flags shr bit) and 1 == 1
}

/** One "$G" packet. */
data class GpsFix(val t: Double, val seq: Int, val lat: Double, val lon: Double, val rssi: Int?)

enum class Level { INFO, WARN, ERROR }

sealed class Event {
    abstract val t: Double
    data class Tm(val tm: Telemetry) : Event() { override val t get() = tm.t }
    data class Gps(val fix: GpsFix) : Event() { override val t get() = fix.t }
    data class Notice(override val t: Double, val level: Level, val text: String) : Event()
    data class RawLine(override val t: Double, val text: String) : Event()
}

/** Things that depend on YOUR rocket. TODO items need the flight computer team. */
object Config {
    const val USB_BAUD = 115200

    val STATE_NAMES = listOf("IDLE", "BOOST", "COAST", "APOGEE", "DESCENT", "SAFE_ERROR", "LANDED", "RECOVERY", "TEST")
    fun stateName(st: Int) = STATE_NAMES.getOrElse(st) { "UNKNOWN" }

    /** label, zeroIsBad (GPS/BARO), oneIsAlert (ARM). TODO: confirm C1..D2 meanings. */
    data class FlagInfo(val label: String, val zeroIsBad: Boolean = false, val oneIsAlert: Boolean = false)
    val FLAGS = listOf(
        FlagInfo("ARM", oneIsAlert = true), FlagInfo("C1"), FlagInfo("C2"), FlagInfo("P1"), FlagInfo("P2"),
        FlagInfo("D1"), FlagInfo("D2"), FlagInfo("GPS", zeroIsBad = true), FlagInfo("BARO", zeroIsBad = true),
    )

    const val RSSI_WARN_DBM = -100
    const val RSSI_BAD_DBM = -110
    const val LINK_STALE_SEC = 1.5
    const val LINK_LOST_SEC = 3.0

    /** Fixed history size: 36000 samples = 2 h at 5 Hz. Memory never grows. */
    const val HISTORY = 36000
    const val GPS_HISTORY = 7200
}
