package com.issam.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private class Group(val label: String, val hint: String, val dir: File, val match: (File) -> Boolean)

/**
 * Everything Apollo produced on disk, grouped by what it is for, with a viewer -
 * so nobody has to go hunting through folders.
 */
@Composable
fun FilesTab(state: RunState) {
    var selected by remember { mutableStateOf<File?>(null) }
    // Recomputed whenever a new artifact is announced.
    val stamp = state.files.size

    val groups = remember(stamp) {
        val root = File("").absoluteFile
        listOf(
            Group("Migrated Kotlin", "The converted code Apollo produced", File(root, "migrated-src")) { it.extension == "kt" },
            Group("Specs (Stage 1)", "What Apollo understood about each class", File(root, "reports/specs")) { it.extension in setOf("json", "md") },
            Group("Ground truth (Stage 2)", "Recorded behaviour of the original Java", File(root, "reports/characterization")) { it.extension in setOf("json", "md") },
            Group("Verification (Stage 4)", "Test results per module", File(root, "reports/verification")) { it.extension in setOf("json", "md") },
            Group("Run reports", "Machine-readable summaries of each run", File(root, "reports")) { it.isFile && it.extension in setOf("json", "md") }
        )
    }

    // Scanning the disk is done once per change, outside the lazy list: `remember` is not
    // callable from inside a LazyListScope block.
    val listing = remember(stamp) {
        groups.map { g ->
            val files =
                if (!g.dir.exists()) emptyList()
                else g.dir.walkTopDown().maxDepth(4).filter { it.isFile && g.match(it) }
                    .sortedBy { it.name }.take(200).toList()
            g to files
        }
    }

    Row(Modifier.fillMaxSize().padding(16.dp)) {

        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("Generated files", "Click any file to read it.")
            Panel(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    listing.forEach { (g, files) ->
                        item {
                            Column(Modifier.fillMaxWidth().background(Ink.panelAlt).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("${g.label}  (${files.size})", color = Ink.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(g.hint, color = Ink.textDim, fontSize = 10.sp)
                            }
                        }

                        if (files.isEmpty()) {
                            item { Text("  none yet", color = Ink.textDim, fontSize = 11.sp, modifier = Modifier.padding(12.dp)) }
                        } else {
                            items(files) { f ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (selected?.path == f.path) Ink.panelAlt else Ink.panel)
                                        .clickable { selected = f }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(f.name, color = Ink.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text("${f.length() / 1024} KB", color = Ink.textDim, fontSize = 10.sp)
                                }
                                rowDivider()
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1.5f).fillMaxHeight()) {
            SectionTitle(selected?.name ?: "File viewer", selected?.parent ?: "Select a file on the left.")
            Panel(Modifier.fillMaxSize()) {
                val body = remember(selected?.path, selected?.lastModified()) {
                    val f = selected
                    when {
                        f == null -> ""
                        f.length() > 600_000 -> "(file too large to preview)"
                        else -> runCatching { f.readText() }.getOrElse { "(could not read: ${it.message})" }
                    }
                }
                if (selected == null) {
                    EmptyHint("Nothing selected.")
                } else {
                    Box(Modifier.fillMaxSize().background(Ink.bg).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SelectionContainer { Mono(body, Ink.text, 11) }
                    }
                }
            }
        }
    }
}
