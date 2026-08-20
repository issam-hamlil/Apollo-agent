package com.issam.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The raw console, mirrored - for when someone does want the terminal detail. */
@Composable
fun LogTab(state: RunState) {
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.logLines.size, autoScroll) {
        if (autoScroll && state.logLines.isNotEmpty()) {
            listState.scrollToItem(state.logLines.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Live log", "Everything the terminal would show, as it happens.")
            }
            Checkbox(autoScroll, { autoScroll = it })
            Text("Follow", color = Ink.textDim, fontSize = 12.sp)
        }

        Panel(Modifier.fillMaxWidth().weight(1f)) {
            if (state.logLines.isEmpty()) {
                EmptyHint("Nothing logged yet.")
            } else {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(Ink.bg).padding(10.dp)) {
                        items(state.logLines) { line ->
                            val c = when {
                                line.contains("[FATAL", true) || line.contains("error:", true) -> Ink.bad
                                line.contains("WARN", true) || line.contains("CIRCUIT BREAKER") -> Ink.warn
                                line.contains("PASS") || line.contains("VERIFIED") -> Ink.good
                                else -> Ink.textDim
                            }
                            Mono(line, c, 11)
                        }
                    }
                }
            }
        }
    }
}
