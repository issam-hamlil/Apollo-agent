package com.issam.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dark palette chosen for long unattended runs on a second monitor. */
object Ink {
    val bg = Color(0xFF11141A)
    val panel = Color(0xFF181C24)
    val panelAlt = Color(0xFF1F2430)
    val line = Color(0xFF2C3340)
    val text = Color(0xFFE6EAF2)
    val textDim = Color(0xFF98A2B3)
    val good = Color(0xFF3DD68C)
    val bad = Color(0xFFFF6B6B)
    val warn = Color(0xFFFFB454)
    val busy = Color(0xFF5AA9FF)
    val idle = Color(0xFF6B7280)
}

fun statusColor(status: String): Color = when (status.uppercase()) {
    "VERIFIED", "PASS", "OK", "COMPLETED" -> Ink.good
    "FAILED", "FAIL", "ERROR" -> Ink.bad
    "PENDING", "IDLE" -> Ink.idle
    else -> Ink.warn
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.panel)
            .border(1.dp, Ink.line, RoundedCornerShape(10.dp))
    ) { content() }
}

/** Large headline number with a plain-English caption underneath. */
@Composable
fun StatCard(
    label: String,
    value: String,
    accent: Color = Ink.text,
    hint: String = "",
    modifier: Modifier = Modifier
) {
    Panel(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label.uppercase(), color = Ink.textDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(
                value,
                color = accent,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (hint.isNotBlank()) {
                Text(hint, color = Ink.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Small coloured status pill. */
@Composable
fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Plain progress bar - avoids version-specific Material progress APIs. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Ink.panelAlt)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
    }
}

@Composable
fun SectionTitle(text: String, hint: String = "") {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(text, color = Ink.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (hint.isNotBlank()) Text(hint, color = Ink.textDim, fontSize = 12.sp)
    }
}

@Composable
fun Mono(text: String, color: Color = Ink.text, size: Int = 12) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = size.sp)
}

@Composable
fun EmptyHint(message: String) {
    Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Ink.textDim, fontSize = 13.sp)
    }
}

@Composable
fun rowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
}

@Suppress("unused")
val unusedTheme = MaterialTheme
