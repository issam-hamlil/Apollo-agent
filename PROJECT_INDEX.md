# 🧭 Apollo Agent — AI Assistant & Copilot Operational Index

> **IMPORTANT FOR ALL AI ASSISTANTS / COPILOTS (Antigravity, Cursor, GitHub Copilot, Claude):**  
> **READ THIS FILE FIRST BEFORE MODIFYING CODE, RUNNING COMMANDS, OR EXECUTING TESTS.**  
> This document outlines the project architecture, operational rules, command behaviors, test execution times, and critical gotchas.

---

## 📌 1. Project Purpose & Core Workflow

Apollo Agent is an autonomous, multi-stage stateful agentic pipeline that migrates legacy Java applications (pure Java or Android) into compilable, idiomatic Kotlin.

### 6-Stage Graph Workflow (`ModernizationGraph`):
1. **Stage 0: Pre-Processor** (`Aapt2Tool`, `GradleDependencyResolver`) — Generates `R.java` from Android resources and resolves external AAR/JAR dependencies into `libs/android-stubs/androidx-cache/`.
2. **Stage 1: Analyzer Agent** (`AnalyzerAgent`) — AST parsing via JavaParser, Kahn's topological sort, architectural summaries under `reports/specs/`.
3. **Stage 2: Characterization Tool** (`CharacterizationTool`) — Dynamic Java compilation, reflective method execution, ground-truth JSON capture under `reports/characterization/`.
4. **Stage 3: Migrator Agent** (`MigratorAgent`) — Dependency-aware topological Kotlin synthesis using primary model (`qwen3-coder:30b`) and `knowledge-base/java-to-kotlin-patterns.json`.
5. **Stage 4: Verifier Agent** (`VerifierAgent`) — Embedded compilation via `K2JVMCompiler`, sandboxed classloader execution, ground-truth comparison, symmetric Android `STUB!` handling.
6. **Stage 5: Fixer Agent** (`FixerAgent`) — Diagnostic-driven repair loop. Attempts 0–2 (runs 1–3) use primary (`qwen3-coder:30b`); attempts 3+ (4th run and the rest) escalate to (`qwen3.8:27b`).
7. **Stage 6: Orchestrator & Reporting** (`ModernizationGraph`, `ResumeManager`, `Main.kt`) — Graph state machine, incremental resume validation, summary generation, clean JVM termination.

---

## 🗂️ 2. Repository File Index & Responsibilities

| Path | Purpose & Responsibilities |
| :--- | :--- |
| `src/main/kotlin/com/issam/apollo/Main.kt` | CLI entry point, argument parsing (`--resume`, `--server`), banner rendering, explicit `kotlin.system.exitProcess(exitCode)` to prevent Gradle hangs. |
| `src/main/kotlin/com/issam/apollo/orchestrator/ModernizationGraph.kt` | The central state machine orchestrating stage transitions, retry pass budgets, and final report generation. |
| `src/main/kotlin/com/issam/apollo/orchestrator/ResumeManager.kt` | Validates integrity of previously generated artifacts (specs, char tests, Kotlin code, verification reports) and handles stage skip/continuity on resume. |
| `src/main/kotlin/com/issam/apollo/config/LlmConfig.kt` | 2-Tier Ollama routing (`selectOllamaModel`), instant-trip Groq circuit breaker, streaming timeout watchdogs, active stream termination. |
| `src/main/kotlin/com/issam/apollo/agents/AnalyzerAgent.kt` | Java AST analysis, Kahn's topological dependency sorting, Markdown/JSON spec generation. |
| `src/main/kotlin/com/issam/apollo/agents/MigratorAgent.kt` | Topological Java-to-Kotlin migration with knowledge-base pattern injection. Always uses Tier-1 model. |
| `src/main/kotlin/com/issam/apollo/agents/VerifierAgent.kt` | In-process Kotlin compilation (`K2JVMCompiler`) and reflective test execution against Stage 2 ground-truth. |
| `src/main/kotlin/com/issam/apollo/agents/FixerAgent.kt` | Targeted code repair using compiler logs, 2-tier escalation routing, and 5-identical-error bottleneck detection. |
| `src/main/kotlin/com/issam/apollo/tools/Aapt2Tool.kt` | Android SDK AAPT2 resource compiler (`compile` and `link` to produce `R.java`). |
| `src/main/kotlin/com/issam/apollo/tools/GradleDependencyResolver.kt` | Resolves Maven coordinates, downloads AARs/JARs, extracts `classes.jar` to `libs/android-stubs/androidx-cache/`. |
| `src/main/kotlin/com/issam/apollo/tools/CharacterizationTool.kt` | Compiles original Java classes and reflectively captures ground-truth test vectors. |
| `src/main/kotlin/com/issam/apollo/tools/KotlinCompileTool.kt` | Wrapper around Kotlin's embedded `K2JVMCompiler`. |
| `src/main/kotlin/com/issam/apollo/knowledge/MigrationPatterns.kt` | Loads and formats `knowledge-base/java-to-kotlin-patterns.json` into prompt context. |
| `src/main/kotlin/com/issam/apollo/mcp/McpServer.kt` | Embedded Ktor Netty HTTP server providing REST endpoints for external IDEs/agents. |
| `knowledge-base/java-to-kotlin-patterns.json` | Curated library of Java-to-Kotlin translation rules (POJO, utility, null safety, Android UI, SQLite). |
| `sample-legacy/` | Built-in testbed containing 5 legacy Java classes (`User`, `StringUtils`, `TrickyMath`, `AsyncDataLoader`, `UserService`). |

---

## ⚡ 3. Commands: What Works vs What Fails / Hangs

### ✅ Working Commands

#### 1. Agent-by-Agent Testing (RECOMMENDED):
Always test **agent by agent** during development. These unit test suites run in isolation and finish in **10–20 seconds**:

```powershell
# Analyzer Agent (AST & Topo Sort)
./gradlew test --tests com.issam.apollo.agents.AnalyzerAgentTest

# LLM Config (2-Tier Routing & Circuit Breaker)
./gradlew test --tests com.issam.apollo.config.LlmConfigTest

# Resume Manager (Artifact validation & state restoration)
./gradlew test --tests com.issam.apollo.orchestrator.ResumeManagerTest

# Fixer Agent (Self-healing repair & retry cap logic)
./gradlew test --tests com.issam.apollo.agents.FixerAgentTest
```

#### 2. End-to-End Testing (Heavy / Live LLMs):
These tests invoke live Ollama inference across all 5 sample legacy modules:
```powershell
# Verifier Agent End-to-End Test (~5-10 minutes)
./gradlew test --tests com.issam.apollo.agents.VerifierAgentTest

# Graph Orchestrator End-to-End Test (~5-10 minutes)
./gradlew test --tests com.issam.apollo.orchestrator.OrchestratorTest
```

#### 3. CLI Pipeline Execution:
```powershell
# Migrate sample project from scratch
./gradlew run --args="sample-legacy"

# Resume an interrupted migration
./gradlew run --args="--resume sample-legacy"

# Migrate a custom project
./gradlew run --args="D:/Projects/MyTargetApp"
```

---

### ❌ Commands That Will Fail, Hang, or Waste Time

| Anti-Pattern Command | Why It Fails or Hangs | What To Do Instead |
| :--- | :--- | :--- |
| `cd <dir>` | Tool runner disallows `cd`. | Always specify `Cwd` parameter in tool calls. |
| Blind `./gradlew test` (without filtering) | Executes both fast unit tests and heavy 10-minute live LLM tests sequentially, risking timeouts. | **Run agent-by-agent tests** with `--tests com.issam.apollo.agents.<TestName>`. |
| Running `gradlew` without `exitProcess` in code | Gradle daemon waits for non-daemon threads and hangs at `<==========---> 83% EXECUTING`. | Ensure `Main.kt` invokes `kotlin.system.exitProcess(exitCode)` (already implemented). |
| Polling `manage_task` in a tight loop | Spams the IDE task runner and exhausts execution budget. | Call the background task and wait for the system notification. |

---

## ⏱️ 4. Test Duration Reference Table

| Test Suite | Execution Mode | Estimated Time | Purpose |
| :--- | :--- | :--- | :--- |
| `AnalyzerAgentTest` | In-memory AST | **~10–15s** | Validates JavaParser AST extraction and topological sort. |
| `LlmConfigTest` | Mocked / In-memory | **~10–15s** | Validates 2-tier model selection and circuit breaker. |
| `ResumeManagerTest` | File system check | **~10–15s** | Validates artifact integrity checks and stage skip logic. |
| `FixerAgentTest` | Unit / Mocked LLM | **~15–20s** | Validates self-healing retry counters and error history. |
| `VerifierAgentTest` | **Live Ollama LLM** | **~5–10 min** | Executes live migration, compilation, and test verification. |
| `OrchestratorTest` | **Live Ollama LLM** | **~5–10 min** | Executes full 6-stage pipeline across all 5 sample modules. |

> 💡 **Testing Rule of Thumb:** When making edits to agents or configuration, **always run the corresponding agent test first**. Only run `VerifierAgentTest` or `OrchestratorTest` when validating full end-to-end pipeline changes.

---

## 🛡️ 5. Critical Architectural Rules & Invariants

### 1. 2-Tier Ollama Routing Rules:
- **Default Primary (`OLLAMA_MODEL_PRIMARY=qwen3-coder:30b`)**: Used for fresh starts, first try resumes, all initial migrations, and FixerAgent repair attempts 0, 1, and 2 (runs 1–3).
- **4th+ Run Escalation (`OLLAMA_MODEL_ESCALATION=qwen3.8:27b`)**: Used for FixerAgent repair attempts 3+ (the 4th and the rest runs).
- Never hardcode model names in agent code — always route through `LlmConfig.selectOllamaModel(moduleName, attemptCount)`.

### 2. Watchdog & Bottleneck Invariants:
- **5 Identical Errors**: If a module encounters the exact same compiler error signature 5 times in a row, the pipeline MUST halt immediately with a bottleneck message.
- **3 Watchdog Aborts**: If 3 LLM calls time out consecutively, the pipeline MUST halt immediately.
- **Groq Circuit Breaker**: Trips on ANY non-success response (429, 404, 500, network error) and stays tripped for the rest of the run.

### 3. Test Lifecycle State Resets:
Whenever creating or editing unit tests that interact with `FixerAgent` or `LlmConfig`, ALWAYS include `@BeforeTest` and `@AfterTest` lifecycle methods:
```kotlin
@BeforeTest
@AfterTest
fun cleanup() {
    FixerAgent.resetErrorHistories()
    LlmConfig.resetWatchdogAbortCounts()
    LlmConfig.resetGroqRateLimit()
}
```

### 4. Resume Budget Invariants:
- On resume, the global graph-level pass budget (`pass < 10`) is reset to 0 for the new invocation.
- In `ResumeManager.kt`, `adjustedRegenCounters[module] = 0` on resume so that modules requiring repair receive a fresh set of attempts using newly updated Knowledge Base rules.

### 5. Stream Termination Invariants:
- In `LlmConfig.kt`, whenever a streaming watchdog times out, the active HTTP `InputStream` and `BufferedReader` MUST be explicitly closed so underlying I/O worker threads are unblocked.

---

## 🎯 6. Quick Checklist for Code Modifications

Before concluding any task in this codebase, ensure:
1. All changes compile cleanly: `./gradlew compileKotlin compileTestKotlin`
2. Relevant agent unit test passes: `./gradlew test --tests com.issam.apollo.<agents|config|orchestrator>.<TestClass>`
3. State cleanup methods are called in test lifecycle if error histories or rate limits are touched.
4. Process termination behavior is preserved (`kotlin.system.exitProcess`).
