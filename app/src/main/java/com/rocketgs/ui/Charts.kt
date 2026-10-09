package com.rocketgs.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgs.core.FlightData
import com.rocketgs.core.History
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class TraceKind(val label: String, val unit: String) {
    ALT("ALT", "m"), VEL("VEL", "m/s"), ACC("ACC", "g"), RSSI("RSSI", "dBm"), ATT("ATT", "deg"), PWR("PWR", "V")
}

private fun seriesFor(d: FlightData, k: TraceKind): List<Pair<History, Color>> = when (k) {
    TraceKind.ALT -> listOf(d.alt to C.fg)
    TraceKind.VEL -> listOf(d.vel to C.fg)
    TraceKind.ACC -> listOf(d.g to C.fg)
    TraceKind.RSSI -> listOf(d.rssi to C.amber)
    TraceKind.ATT -> listOf(d.roll to C.fg, d.pitch to C.amber, d.yaw to C.cyan)
    TraceKind.PWR -> listOf(d.f1 to C.fg, d.f2 to C.amber)
}

/**
 * Line chart over time. Only the visible range is drawn, and long ranges are
 * thinned to about two points per pixel, so cost does not grow with flight length.
 */
@Composable
@Suppress("UNUSED_PARAMETER")   // version: makes the chart redraw when new data arrives
fun TraceChart(d: FlightData, kind: TraceKind, windowSec: Double, whole: Boolean, version: Int, modifier: Modifier) {
    val n = d.t.size
    val tEnd = if (n > 0) d.t.last() else 0.0
    val tStart = if (whole && n > 0) d.t[0] else tEnd - windowSec
    val i0 = if (whole) 0 else max(0, d.t.lowerBound(tStart) - 1)
    val series = seriesFor(d, kind)

    // y range from visible data
    var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
    for ((h, _) in series) for (i in i0 until n) { val v = h[i]; if (!v.isNaN()) { lo = min(lo, v); hi = max(hi, v) } }
    if (lo > hi) { lo = 0.0; hi = 1.0 }
    if (hi - lo < 1e-6) { lo -= 1.0; hi += 1.0 }
    val pad = (hi - lo) * 0.08; lo -= pad; hi += pad

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            for (k in 1..3) {
                drawLine(C.grid, Offset(0f, h * k / 4), Offset(w, h * k / 4), 1f)
                drawLine(C.grid, Offset(w * k / 4, 0f), Offset(w * k / 4, h), 1f)
            }
            if (n < 2 || tEnd <= tStart) return@Canvas
            fun x(t: Double) = ((t - tStart) / (tEnd - tStart) * w).toFloat()
            fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
            for ((t, st) in d.stateChanges) if (t in tStart..tEnd) {
                drawLine(if (st == 1) C.amber else C.dim, Offset(x(t), 0f), Offset(x(t), h), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            val step = max(1, (n - i0) / max(1, (w * 2).toInt()))
            for ((hist, color) in series) {
                val p = Path(); var pen = false
                var i = i0
                while (i < n) {
                    val v = hist[i]
                    if (v.isNaN()) pen = false
                    else if (!pen) { p.moveTo(x(d.t[i]), y(v)); pen = true } else p.lineTo(x(d.t[i]), y(v))
                    i = if (i + step >= n && i != n - 1) n - 1 else i + step
                }
                drawPath(p, color, style = Stroke(2.dp.toPx()))
            }
        }
        T(fmtAxis(hi), C.dim, 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        T(fmtAxis(lo), C.dim, 12.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
        T(if (whole) "whole flight" else "last ${windowSec.toInt()} s", C.dim, 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
    }
}

private fun fmtAxis(v: Double) = if (abs(v) >= 100) fmt("%.0f", v) else fmt("%.1f", v)

/**
 * Attitude scope: looking down from above. The marker shows where the nose
 * leans (distance from centre = tilt, 10 deg per ring, edge = 30 deg; larger
 * tilts sit hollow on the edge). The outer triangle is the roll angle.
 */
@Composable
fun AttitudeScope(tiltDeg: Double?, towardDeg: Double?, rollDeg: Double?, modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = min(size.width, size.height) / 2 * 0.86f
            drawCircle(C.fg, r, c, style = Stroke(1.5.dp.toPx()))
            for (k in 1..2) drawCircle(C.grid, r * k / 3, c, style = Stroke(1.5.dp.toPx()))
            drawLine(C.grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
            drawLine(C.grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
            if (tiltDeg == null || towardDeg == null) return@Canvas
            val beyond = tiltDeg > 30
            val dist = (min(tiltDeg, 30.0) / 30.0 * r).toFloat()
            val a = Math.toRadians(towardDeg)
            val p = Offset(c.x + dist * sin(a).toFloat(), c.y - dist * cos(a).toFloat())
            drawLine(C.fg, c, p, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val s = 9.dp.toPx()
            drawRect(C.fg, Offset(p.x - s, p.y - s), Size(2 * s, 2 * s), style = Stroke(if (beyond) 1.5f else 3f))
            if (rollDeg != null) rollPointer(c, r, rollDeg)
        }
        T("N", C.dim, 12.sp, modifier = Modifier.align(Alignment.TopCenter))
    }
}

private fun DrawScope.rollPointer(c: Offset, r: Float, rollDeg: Double) {
    val a = Math.toRadians(rollDeg)
    fun pt(rr: Float, da: Double) = Offset(c.x + rr * sin(a + da).toFloat(), c.y - rr * cos(a + da).toFloat())
    val p = Path().apply {
        val tip = pt(r - 2, 0.0); moveTo(tip.x, tip.y)
        val b1 = pt(r + 14, 0.08); lineTo(b1.x, b1.y)
        val b2 = pt(r + 14, -0.08); lineTo(b2.x, b2.y); close()
    }
    drawPath(p, C.amber)
}

/** GPS track in metres from the origin, equal scale on both axes. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun TrackPlot(d: FlightData, version: Int, modifier: Modifier) {
    Canvas(modifier) {
        val n = d.gpsEast.size
        var ext = 50.0
        for (i in 0 until n) ext = max(ext, max(abs(d.gpsEast[i]), abs(d.gpsNorth[i])))
        ext *= 1.15
        val c = Offset(size.width / 2, size.height / 2)
        val sc = (min(size.width, size.height) / 2 / ext).toFloat()
        drawLine(C.grid, Offset(0f, c.y), Offset(size.width, c.y), 1f)
        drawLine(C.grid, Offset(c.x, 0f), Offset(c.x, size.height), 1f)
        drawRect(C.dim, Offset(c.x - 5, c.y - 5), Size(10f, 10f))
        if (n == 0) return@Canvas
        fun pt(i: Int) = Offset(c.x + (d.gpsEast[i] * sc).toFloat(), c.y - (d.gpsNorth[i] * sc).toFloat())
        val p = Path(); p.moveTo(pt(0).x, pt(0).y)
        for (i in 1 until n) p.lineTo(pt(i).x, pt(i).y)
        drawPath(p, C.cyan, style = Stroke(2.dp.toPx()))
        drawCircle(C.amber, 6.dp.toPx(), pt(n - 1))
    }
}
