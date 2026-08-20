package com.issam.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LOCATION = Regex("""^(.*?\.kt):(\d+):(\d+):\s*error:\s*(.*)$""")

private class Diag(val file: String, val line: String, val message: String, val extra: MutableList<String> = mutableListOf())

/**
 * Compiler output, grouped per error rather than dumped as a wall of text.
 * Warnings are separated out because they never block a run.
 */
@Composable
fun ErrorsTab(state: RunState) {
    val raw = state.compileErrors
    val warnings = raw.filter { it.trimStart().startsWith("warning:") }

    val diags = mutableListOf<Diag>()
    raw.filterNot { it.trimStart().startsWith("warning:") }.forEach { line ->
        val m = LOCATION.find(line.trim())
        if (m != null) {
            val fileName = m.groupValues[1].takeLastWhile { it != '/' && it.code != 92 }
            diags.add(Diag(fileName, m.groupValues[2], m.groupValues[4]))
        } else if (diags.isNotEmpty()) {
            diags.last().extra.add(line)
        } else {
            diags.add(Diag("", "", line))
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        Row(Modifier.fillMaxWidth()) {
            StatCard(
                "Compile status",
                when (state.compileOk) { null -> "not run"; true -> "success"; false -> "failed" },
                when (state.compileOk) { null -> Ink.idle; true -> Ink.good; false -> Ink.bad },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            StatCard(
                "Errors",
                diags.size.toString(),
                if (diags.isEmpty()) Ink.good else Ink.bad,
                "these stop the build",
                Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            StatCard(
                "Warnings",
                warnings.size.toString(),
                Ink.idle,
                "safe to ignore",
                Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle(
            "Compiler errors",
            "Each entry is one problem in the generated Kotlin, with the file and line it came from."
        )

        Panel(Modifier.fillMaxWidth().weight(1f)) {
            if (diags.isEmpty()) {
                EmptyHint(
                    if (state.compileOk == true) "The generated Kotlin compiles cleanly."
                    else "No compiler errors recorded yet."
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(10.dp)) {
                    items(diags) { d ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Ink.bg)
                                .padding(10.dp)
                        ) {
                            SelectionContainer {
                                Column {
                                    if (d.file.isNotBlank()) {
                                        Text(
                                            "${d.file}  line ${d.line}",
                                            color = Ink.warn, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(3.dp))
                                    }
                                    Mono(d.message, Ink.text, 12)
                                    d.extra.forEach { Mono(it, Ink.textDim, 11) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
