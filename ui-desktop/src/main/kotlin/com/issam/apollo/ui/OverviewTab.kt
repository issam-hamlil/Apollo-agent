package com.issam.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The "is it working?" screen. One row per Java class being migrated, with a
 * behaviour-test score, what it is doing right now, and why it is failing.
 */
@Composable
fun OverviewTab(state: RunState) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {

        SectionTitle(
            "Modules",
            "Each row is one class from your project. The bar shows how many recorded behaviours " +
                "the migrated Kotlin reproduces. Green means it matches the original Java exactly."
        )

        if (state.modules.isEmpty()) {
            EmptyHint("No modules yet. Press Start to begin a run.")
            return@Column
        }

        Panel(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().background(Ink.panelAlt).padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HeaderCell("Class", 2.2f)
                        HeaderCell("Behaviour tests", 2.6f)
                        HeaderCell("Status", 1.2f)
                        HeaderCell("Doing now", 2.0f)
                    }
                }

                items(state.modules) { m ->
                    val frac = if (m.total == 0) 1f else m.passed.toFloat() / m.total
                    val barColor = when {
                        m.total == 0 -> Ink.idle
                        m.passed == m.total -> Ink.good
                        m.passed == 0 -> Ink.bad
                        else -> Ink.warn
                    }

                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(2.2f)) {
                                Text(m.name, color = Ink.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                if (m.failures.isNotEmpty()) {
                                    Text(
                                        "${m.failures.size} behaviour mismatch${if (m.failures.size == 1) "" else "es"}",
                                        color = Ink.textDim, fontSize = 11.sp
                                    )
                                }
                            }

                            Column(Modifier.weight(2.6f).padding(end = 16.dp)) {
                                Text(
                                    if (m.total == 0) "no recorded behaviours" else "${m.passed} of ${m.total} match",
                                    color = Ink.textDim, fontSize = 11.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Bar(frac, barColor, Modifier.fillMaxWidth())
                            }

                            Box(Modifier.weight(1.2f)) { Pill(m.status, statusColor(m.status)) }

                            Box(Modifier.weight(2.0f)) {
                                if (m.busy) {
                                    Pill(if (m.activity.isBlank()) "working" else m.activity, Ink.busy)
                                } else {
                                    Text(
                                        m.activity.ifBlank { "-" },
                                        color = Ink.textDim, fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        if (m.failures.isNotEmpty()) {
                            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                                m.failures.take(4).forEach {
                                    Mono("- $it", Ink.textDim, 11)
                                }
                                if (m.failures.size > 4) {
                                    Text(
                                        "+ ${m.failures.size - 4} more",
                                        color = Ink.textDim, fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        rowDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text.uppercase(),
        color = Ink.textDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(weight)
    )
}
