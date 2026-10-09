package com.rocketgs.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgs.GroundStationViewModel
import com.rocketgs.core.Config
import com.rocketgs.core.Geo
import com.rocketgs.core.Level

/**
 * Main screen, mockup D "Range Console". Phone: one scrolling column.
 * Landscape / tablet (>= 720 dp wide): two columns.
 */
@Composable
fun ConsoleScreen(vm: GroundStationViewModel, onSetup: () -> Unit) {
    val v = vm.version          // read so the screen redraws on new data
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Header(vm, onSetup)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            if (!wide) {
                LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { TelemetryPanel(vm, v) }
                    item { TracePanel(vm, v) }
                    item { AttitudePanel(vm, v) }
                    item { PositionPanel(vm, v) }
                    item { LinkPanel(vm, v) }
                    item { FlagsPanel(vm, v) }
                    item { EventsPanel(vm, v) }
                    item { RawPanel(vm, v) }
                }
            } else {
                Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { TelemetryPanel(vm, v) }
                        item { AttitudePanel(vm, v) }
                        item { PositionPanel(vm, v) }
                        item { FlagsPanel(vm, v) }
                    }
                    LazyColumn(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { TracePanel(vm, v) }
                        item { LinkPanel(vm, v) }
                        item { EventsPanel(vm, v) }
                        item { RawPanel(vm, v) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun Header(vm: GroundStationViewModel, onSetup: () -> Unit) {
    val d = vm.data
    val now = vm.clock
    val tm = d.tm
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("GS-1", C.fg, 26.sp, big = true)
            Spacer(Modifier.width(12.dp))
            if (tm != null) {
                val st = Config.stateName(tm.state)
                T(" $st ", if (tm.state == 5) C.red else C.bg, 18.sp,
                    modifier = Modifier.background(if (tm.state == 5) C.bg else C.fg).padding(horizontal = 4.dp))
                Spacer(Modifier.width(10.dp))
                if (tm.flag(0)) T("ARMED", C.amber, 16.sp) else T("SAFE", C.dim, 16.sp)
            } else T("NO DATA", C.dim, 16.sp)
            Spacer(Modifier.weight(1f))
            val tp = vm.tPlus()
            T(if (tp != null) "T+" + clockText(tp) else "PAD", C.fg, 30.sp, big = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val age = if (d.lastPacketWall < 0) null else now - d.lastPacketWall
            val (txt, col) = when {
                age == null -> "NO PACKETS" to C.dim
                age < Config.LINK_STALE_SEC -> "LIVE ${fmt("%.1f", age)}s" to C.fg
                age < Config.LINK_LOST_SEC -> "STALE ${fmt("%.1f", age)}s" to C.amber
                else -> "LOST ${fmt("%.0f", age)}s" to C.red
            }
            T(txt, col, 14.sp, modifier = Modifier.width(110.dp))
            T(vm.status, if (vm.sourceOk) C.dim else C.amber, 13.sp, modifier = Modifier.weight(1f))
            Btn("SETUP", onSetup)
        }
    }
}

private fun clockText(sec: Double): String {
    val s = sec.coerceAtLeast(0.0)
    val m = (s / 60).toInt()
    return fmt("%02d:%04.1f", m, s - m * 60)
}

// ---------------------------------------------------------------------------
@Composable
private fun TelemetryPanel(vm: GroundStationViewModel, @Suppress("UNUSED_PARAMETER") v: Int) {
    val tm = vm.data.tm
    Panel("TELEMETRY", right = tm?.let { "SEQ ${it.seq}" }) {
        fun f(x: Double?, p: String) = if (x == null) "---" else fmt(p, x)
        Readout("ALT", f(tm?.alt, "%.1f"), "m", 64.sp)
        Readout("MAX", f(tm?.maxAlt, "%.1f"), "m", 36.sp)
        Row {
            Readout("VEL", f(tm?.vel, "%+.1f"), "m/s", 40.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Readout("ACC", f(tm?.g, "%.2f"), "g", 40.sp, modifier = Modifier.weight(1f))
        }
        if (tm != null) T("peak ${fmt("%.0f", vm.data.peakVel)} m/s   ${fmt("%.1f", vm.data.peakG)} g", C.dim, 13.sp)
    }
}

@Composable
private fun TracePanel(vm: GroundStationViewModel, v: Int) {
    var kind by remember { mutableStateOf(TraceKind.ALT) }
    Panel("TRACE", right = kind.unit) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in TraceKind.entries) Btn(k.label, { kind = k }, selected = k == kind)
        }
        Spacer(Modifier.height(8.dp))
        TraceChart(vm.data, kind, vm.plotWindowSec.toDouble(), vm.wholeFlight, v, Modifier.fillMaxWidth().height(200.dp))
        if (kind == TraceKind.ATT) T("roll   pitch   yaw", C.dim, 12.sp)
        if (kind == TraceKind.PWR) T("F1   F2", C.dim, 12.sp)
        ButtonRow {
            for (w in listOf(30f, 60f, 120f, 300f))
                Btn("${w.toInt()}s", { vm.updatePlot(w, false) }, selected = !vm.wholeFlight && vm.plotWindowSec == w)
            Btn("ALL", { vm.updatePlot(vm.plotWindowSec, true) }, selected = vm.wholeFlight)
        }
    }
}

@Composable
private fun AttitudePanel(vm: GroundStationViewModel, @Suppress("UNUSED_PARAMETER") v: Int) {
    val tm = vm.data.tm
    val tilt = vm.tilt()
    Panel("ATTITUDE SCOPE", right = if (vm.tare != null) "RELATIVE" else null, rightColor = C.amber) {
        AttitudeScope(tilt?.tiltDeg, tilt?.towardDeg, tm?.roll, Modifier.fillMaxWidth().aspectRatio(1.2f))
        if (tilt != null && tm != null) {
            T("TILT ${fmt("%5.1f", tilt.tiltDeg)}  -> ${fmt("%03.0f", tilt.towardDeg)} ${Geo.compass(tilt.towardDeg)}", C.fg, 16.sp)
            T("ROLL ${fmt("%.1f", tm.roll)}  PITCH ${fmt("%.1f", tm.pitch)}  YAW ${fmt("%.1f", tm.yaw)}", C.dim, 13.sp)
        } else T("no attitude data yet", C.dim, 14.sp)
        ButtonRow {
            Btn("SET UPRIGHT", { vm.setUpright() }, enabled = tm != null)
            Btn("CLEAR", { vm.clearUpright() }, enabled = vm.tare != null)
        }
        T("Display only. Do the tilt test before trusting it (see SETUP).", C.dim, 12.sp, maxLines = 2)
    }
}

@Composable
private fun PositionPanel(vm: GroundStationViewModel, v: Int) {
    val d = vm.data
    val g = d.gps
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    Panel("POSITION", right = if (g == null && d.tm != null && !d.tm!!.flag(7)) "NO FIX" else null, rightColor = C.amber) {
        if (g == null) T("no GPS fix yet", C.dim, 14.sp)
        else {
            val br = d.bearingFromOrigin()
            T("${fmt("%.0f", d.distanceFromOrigin())} m  ${Geo.compass(br)} ${fmt("%03.0f", br)}", C.fg, 34.sp, big = true)
            T("LAT ${fmt("%.5f", g.lat)}  LON ${fmt("%.5f", g.lon)}", C.fg, 15.sp)
            T("from origin ${fmt("%.5f", d.originLat)}, ${fmt("%.5f", d.originLon)}", C.dim, 12.sp)
        }
        TrackPlot(d, v, Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp))
        ButtonRow {
            Btn("MAP", {
                val q = "${fmt("%.6f", g!!.lat)},${fmt("%.6f", g.lon)}"
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$q?q=$q(Rocket)")))
            }, enabled = g != null)
            Btn("COPY", { clip.setText(AnnotatedString("${fmt("%.5f", g!!.lat)}, ${fmt("%.5f", g.lon)}")) }, enabled = g != null)
            Btn("ORIGIN HERE", { vm.setGpsOrigin() }, enabled = g != null)
        }
    }
}

@Composable
private fun LinkPanel(vm: GroundStationViewModel, @Suppress("UNUSED_PARAMETER") v: Int) {
    val d = vm.data
    val tm = d.tm
    Panel("POWER / LINK") {
        // TODO: F1/F2 full-scale values depend on what they measure (ask the flight computer team).
        BarRow("F1", (tm?.f1 ?: 0.0) / 4.2, tm?.let { fmt("%.2f V", it.f1) } ?: "---")
        BarRow("F2", (tm?.f2 ?: 0.0) / 8.4, tm?.let { fmt("%.2f V", it.f2) } ?: "---")
        val r = d.lastRssi
        val rc = when { r == null -> C.dim; r < Config.RSSI_BAD_DBM -> C.red; r < Config.RSSI_WARN_DBM -> C.amber; else -> C.fg }
        BarRow("RSSI", if (r == null) 0.0 else (r + 120) / 80.0, r?.let { "$it dBm" } ?: "N/A", rc)
        val loss = d.link.recentLossPct()
        BarRow("LOSS", loss / 20.0, fmt("%.1f %%", loss), if (loss > 20) C.red else if (loss > 5) C.amber else C.fg)
        val bad = vm.source?.badBlocks ?: 0
        T("RATE ${fmt("%.1f", d.packetRate(vm.clock))} Hz   BAD $bad   T/G ${d.tmCount}/${d.gpsCount}",
            if (bad > 0) C.amber else C.fg, 14.sp)
        T("total loss ${fmt("%.1f", d.link.totalLossPct())} %", C.dim, 12.sp)
    }
}

@Composable
private fun FlagsPanel(vm: GroundStationViewModel, @Suppress("UNUSED_PARAMETER") v: Int) {
    val tm = vm.data.tm
    Panel("FLAGS", right = tm?.let { fmt("0x%03X", it.flags) }) {
        for (row in Config.FLAGS.indices.chunked(3)) {
            Row(Modifier.fillMaxWidth()) {
                for (i in row) {
                    val info = Config.FLAGS[i]
                    val on = tm?.flag(i) == true
                    val col: Color = when {
                        tm == null -> C.grid
                        info.oneIsAlert && on -> C.amber
                        info.zeroIsBad && !on -> C.amber
                        on -> C.fg
                        else -> C.dim
                    }
                    T((if (on) "[x] " else "[ ] ") + info.label, col, 16.sp, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun EventsPanel(vm: GroundStationViewModel, @Suppress("UNUSED_PARAMETER") v: Int) {
    var all by remember { mutableStateOf(false) }
    val msgs = vm.data.messages
    Panel("EVENTS", right = "${vm.data.warnCount} warn") {
        if (msgs.isEmpty()) T("no events yet", C.dim, 14.sp)
        val shown = msgs.asReversed().take(if (all) 60 else 8)   // newest first
        for (m in shown) {
            val col = when (m.level) { Level.ERROR -> C.red; Level.WARN -> C.amber; Level.INFO -> C.fg }
            T("${fmt("%6.1f", m.wall)}  ${m.text}", col, 13.sp, maxLines = 2)
        }
        if (msgs.size > 8) ButtonRow { Btn(if (all) "LESS" else "MORE", { all = !all }) }
    }
}

@Composable
private fun RawPanel(vm: GroundStationViewModel, @Suppress("UNUSED_PARAMETER") v: Int) {
    Panel("RAW RECEIVER STREAM") {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            val raw = vm.data.raw
            if (raw.isEmpty()) T("nothing received yet", C.dim, 13.sp)
            for (l in raw.takeLast(14)) {
                val col = when {
                    l.startsWith("====") || l.startsWith("----") -> C.dim
                    l.startsWith("RSSI=") || l.startsWith("[") -> C.amber
                    else -> C.fg
                }
                T(l, col, 12.sp)
            }
        }
    }
}
