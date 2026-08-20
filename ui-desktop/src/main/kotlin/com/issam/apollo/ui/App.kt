package com.issam.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private enum class ConsoleTab(val label: String, val hint: String) {
    OVERVIEW("Overview", "Which classes pass"),
    PROMPTS("LLM Prompts", "What the model is asked"),
    ERRORS("Compile Errors", "What failed to build"),
    FILES("Files", "What Apollo generated"),
    LOG("Live Log", "Raw output")
}

/**
 * Optional arguments, so the console can be pointed at a project without typing:
 *   ./gradlew :ui-desktop:run --args="<path> [--fresh] [--autostart]"
 */
fun main(args: Array<String>) = application {
    // Installed before anything prints so the log pane captures the whole run.
    PipelineRunner.installConsoleMirror()

    val startPath = args.firstOrNull { !it.startsWith("--") } ?: "sample-legacy"
    val startResume = !args.contains("--fresh")
    val autoStart = args.contains("--autostart")

    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val state = remember { RunState(scope).also { it.start() } }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Apollo Agent Console",
        state = rememberWindowState(width = 1500.dp, height = 950.dp)
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(primary = Ink.busy, background = Ink.bg, surface = Ink.panel)
        ) {
            Console(state, scope, startPath, startResume, autoStart)
        }
    }
}

@Composable
private fun Console(
    state: RunState,
    scope: CoroutineScope,
    startPath: String,
    startResume: Boolean,
    autoStart: Boolean
) {
    var tab by remember { mutableStateOf(ConsoleTab.OVERVIEW) }
    var path by remember { mutableStateOf(startPath) }
    var resume by remember { mutableStateOf(startResume) }

    LaunchedEffect(Unit) {
        if (autoStart) PipelineRunner.start(scope, state, path.trim(), resume)
    }

    Column(Modifier.fillMaxSize().background(Ink.bg)) {

        // ---- Control bar --------------------------------------------------
        Row(
            Modifier.fillMaxWidth().background(Ink.panel).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(end = 20.dp)) {
                Text("APOLLO AGENT", color = Ink.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Java to Kotlin migration console", color = Ink.textDim, fontSize = 11.sp)
            }

            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Project folder to migrate", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 12.dp)
            )

            Checkbox(resume, { resume = it })
            Text("Resume", color = Ink.textDim, fontSize = 12.sp, modifier = Modifier.padding(end = 14.dp))

            Button(
                onClick = {
                    if (state.running) PipelineRunner.stop()
                    else PipelineRunner.start(scope, state, path.trim(), resume)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.running) Ink.bad else Ink.good,
                    contentColor = Ink.bg
                )
            ) {
                Text(if (state.running) "Stop" else "Start migration", fontWeight = FontWeight.Bold)
            }
        }

        // ---- Headline numbers ---------------------------------------------
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val stageHint = when (state.stage) {
                "ANALYZER" -> "reading your Java code"
                "CHARACTERIZATION" -> "recording original behaviour"
                "MIGRATOR" -> "writing Kotlin"
                "VERIFIER" -> "compiling and testing"
                "FIXER" -> "repairing failures"
                "COMPLETED" -> "done"
                else -> if (state.running) "working" else "not running"
            }

            StatCard(
                "Current stage", state.stage,
                if (state.running) Ink.busy else Ink.idle, stageHint, Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            StatCard("Model in use", state.currentModel, Ink.text, state.currentProvider, Modifier.weight(1.3f))
            Spacer(Modifier.width(12.dp))
            StatCard(
                "Compile errors",
                state.hardErrorCount.toString(),
                if (state.hardErrorCount == 0) Ink.good else Ink.bad,
                if (state.compileOk == true) "builds cleanly" else "must reach zero",
                Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            StatCard(
                "Behaviour tests",
                if (state.totalTests == 0) "-" else state.totalPassed.toString() + " / " + state.totalTests,
                if (state.totalTests > 0 && state.totalPassed == state.totalTests) Ink.good else Ink.warn,
                "migrated code matching the original",
                Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            StatCard(
                "Working on",
                state.modules.filter { it.busy }.joinToString(", ") { it.name }.ifBlank { "-" },
                Ink.busy,
                "files the model is handling now",
                Modifier.weight(1.4f)
            )
        }

        // ---- Result banner --------------------------------------------------
        state.finishedOk?.let { ok ->
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background((if (ok) Ink.good else Ink.bad).copy(alpha = 0.14f))
                    .padding(11.dp)
            ) {
                Text(
                    (if (ok) "Run finished: " else "Run stopped: ") + state.finishedSummary,
                    color = if (ok) Ink.good else Ink.bad,
                    fontSize = 12.sp
                )
            }
        }

        // ---- Tabs -----------------------------------------------------------
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            ConsoleTab.entries.forEach { t ->
                val sel = t == tab
                val badge = when (t) {
                    ConsoleTab.PROMPTS -> state.exchanges.size
                    ConsoleTab.ERRORS -> state.hardErrorCount
                    else -> 0
                }
                Box(
                    Modifier.padding(end = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Ink.busy.copy(alpha = 0.18f) else Ink.panel)
                        .clickable { tab = t }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Column {
                        Text(
                            t.label + if (badge > 0) "  (" + badge + ")" else "",
                            color = if (sel) Ink.busy else Ink.text,
                            fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(t.hint, color = Ink.textDim, fontSize = 9.sp)
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (tab) {
                ConsoleTab.OVERVIEW -> OverviewTab(state)
                ConsoleTab.PROMPTS -> PromptsTab(state)
                ConsoleTab.ERRORS -> ErrorsTab(state)
                ConsoleTab.FILES -> FilesTab(state)
                ConsoleTab.LOG -> LogTab(state)
            }
        }
    }
}
