package com.rocketgs.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgs.R
import java.util.Locale

/** Number formatting with '.' as decimal point on every phone language. */
fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.US, pattern, *args)

/** Range Console palette (mockup D). Text colours keep >= 4.5:1 on the background. */
object C {
    val bg = Color(0xFF050A06)
    val fg = Color(0xFF7CFF9B)      // phosphor
    val dim = Color(0xFF4FB06B)     // labels
    val grid = Color(0xFF1B3A24)
    val amber = Color(0xFFFFB547)
    val red = Color(0xFFFF6B6B)
    val cyan = Color(0xFF8FE3FF)
}

object Fonts {
    val mono = FontFamily(Font(R.font.share_tech_mono))
    val big = FontFamily(Font(R.font.vt323))
}

@Composable
fun T(text: String, color: Color = C.fg, size: TextUnit = 14.sp, big: Boolean = false, modifier: Modifier = Modifier, maxLines: Int = 1) =
    Text(text, color = color, fontSize = size, fontFamily = if (big) Fonts.big else Fonts.mono,
        maxLines = maxLines, overflow = TextOverflow.Clip, softWrap = maxLines > 1, modifier = modifier)

/** Bordered console window with a "[ TITLE ]" bar. */
@Composable
fun Panel(title: String, modifier: Modifier = Modifier, right: String? = null, rightColor: Color = C.dim,
          content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().border(1.dp, C.fg)) {
        Row(Modifier.fillMaxWidth().border(1.dp, C.fg).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            T("[ $title ]", C.dim, 13.sp)
            Box(Modifier.weight(1f))
            if (right != null) T(right, rightColor, 13.sp)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), content = content)
    }
}

/** Console text button. 48 dp tall: a comfortable touch target outdoors. */
@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false,
        enabled: Boolean = true, color: Color = C.fg) {
    val fgc = if (!enabled) C.grid else if (selected) C.bg else color
    Box(modifier
        .heightIn(min = 48.dp)
        .background(if (selected) color else C.bg)
        .border(1.dp, if (enabled) color else C.grid)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) {
        T(text, fgc, 15.sp)
    }
}

@Composable
fun ButtonRow(content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

/** Segmented bar gauge (drawn, because the console font has no block characters). */
@Composable
fun BarRow(label: String, fraction: Double, value: String, color: Color = C.fg) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, color, 14.sp, modifier = Modifier.width(52.dp))
        Canvas(Modifier.weight(1f).height(14.dp)) {
            val cells = 20
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (cells - 1)) / cells
            val lit = (fraction.coerceIn(0.0, 1.0) * cells + 0.5).toInt()
            for (i in 0 until cells) {
                drawRect(if (i < lit) color else C.grid, Offset(i * (w + gap), 0f), Size(w, size.height))
            }
        }
        T(value, color, 14.sp, modifier = Modifier.width(92.dp).padding(start = 10.dp))
    }
}

/** Label + value + unit, value in the big VT323 font. */
@Composable
fun Readout(label: String, value: String, unit: String, size: TextUnit, color: Color = C.fg, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        T(label, C.dim, 14.sp, modifier = Modifier.width(44.dp).padding(bottom = 6.dp))
        T(value, color, size, big = true, modifier = Modifier.weight(1f))
        T(unit, C.dim, 14.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
}
