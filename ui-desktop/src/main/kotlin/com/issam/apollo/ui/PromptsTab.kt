package com.issam.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Exactly what Apollo asked the model, and exactly what came back.
 * This is the screen that tells you whether a failure is the model's fault
 * or the fault of what it was given.
 */
@Composable
fun PromptsTab(state: RunState) {
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = state.exchanges.lastOrNull { it.callId == selectedId }
        ?: state.exchanges.lastOrNull()

    Row(Modifier.fillMaxSize().padding(16.dp)) {

        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle(
                "LLM requests (${state.exchanges.size})",
                "Newest last. Click one to read the full prompt."
            )
            Panel(Modifier.fillMaxSize()) {
                if (state.exchanges.isEmpty()) {
                    EmptyHint("No requests yet.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.exchanges) { ex ->
                            val isSel = ex.callId == (selected?.callId ?: -1L)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (isSel) Ink.panelAlt else Ink.panel)
                                    .clickable { selectedId = ex.callId }
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        ex.module.ifBlank { "(no module)" },
                                        color = Ink.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    when {
                                        !ex.finished -> Pill("waiting", Ink.busy)
                                        ex.ok -> Pill("replied", Ink.good)
                                        else -> Pill("failed", Ink.bad)
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "${ex.provider} - ${ex.model}",
                                    color = Ink.textDim, fontSize = 11.sp
                                )
                                Text(
                                    "attempt ${ex.attempt} - ${ex.promptChars} chars (~${ex.estimatedTokens} tokens)" +
                                        if (ex.finished) " - ${ex.elapsedMs / 1000}s" else "",
                                    color = Ink.textDim, fontSize = 10.sp
                                )
                            }
                            rowDivider()
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1.6f).fillMaxHeight()) {
            if (selected == null) {
                SectionTitle("Prompt detail")
                Panel(Modifier.fillMaxSize()) { EmptyHint("Select a request on the left.") }
            } else {
                SectionTitle(
                    "${selected.module.ifBlank { "request" }} -> ${selected.model}",
                    "${selected.provider} (${selected.tier}) - attempt ${selected.attempt}"
                )
                Panel(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        PromptBlock("System prompt - the rules Apollo gives the model", selected.systemPrompt)
                        Spacer(Modifier.height(12.dp))
                        PromptBlock("User prompt - the actual task and code", selected.userPrompt)
                        Spacer(Modifier.height(12.dp))
                        when {
                            !selected.finished -> PromptBlock("Reply", "Still waiting for the model...")
                            selected.ok -> PromptBlock("Reply from the model", selected.response)
                            else -> PromptBlock("Request failed", selected.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptBlock(title: String, body: String) {
    Column {
        Text(title.uppercase(), color = Ink.textDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Ink.bg)
                .padding(10.dp)
        ) {
            SelectionContainer { Mono(body.ifBlank { "(empty)" }, Ink.text, 11) }
        }
    }
}
