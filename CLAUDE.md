# CLAUDE.md — Apollo Agent Developer & Architecture Guide

> **CRITICAL DIRECTIVE FOR CLAUDE CODE & AI ASSISTANTS:**
> This file is the primary entry point and technical manual for Apollo Agent. Read this document completely to understand the project architecture, operational constraints, build workflows, LLM routing strategies, and debugging procedures before reading, editing, or testing any code.

---

## 1. Project Overview & Mission

**Apollo Agent** is an autonomous, multi-stage stateful agentic pipeline written in Kotlin. Its mission is to migrate legacy Java applications (standard JVM Java or legacy Android apps using the Android Support Library and Ant/Maven/Gradle structures) into modern, compilable, idiomatic Kotlin code that is behaviorally equivalent and verified against runtime ground-truth tests.

### Key Capabilities
- **AST Parsing & Dependency Sorting**: Parses Java ASTs with JavaParser, constructs directed dependency graphs, and executes Kahn's topological sort so downstream modules are migrated in strict dependency order.
- **Empirical Characterization Testing**: Before touching any code, reflectively compiles legacy Java classes and runs diverse input vectors to capture golden input/output characterization snapshots (`reports/characterization/*.json`).
- **Knowledge-Grounded Migration**: Uses curated Java-to-Kotlin design patterns (`knowledge-base/java-to-kotlin-patterns.json`) covering POJOs, singletons, static utils, Android Activities, SQLite helpers, and concurrency constructs.
- **In-Process Embedded Kotlin Compilation**: Uses JetBrains' embedded `K2JVMCompiler` to compile migrated Kotlin source in sandbox directories without spawning slow child Gradle daemons.
- **Self-Healing Iterative Repair (Fixer Loop)**: Feeds exact compiler error messages, line numbers, and ground-truth mismatches back to targeted LLM repair prompts.
- **2-Tier Model Routing**: `qwen3-coder:30b` is the default model for fresh starts, first try resumes, and initial runs 1–3. `qwen3.8:27b` is the default model for the 4th run and the rest.
- **Resilience & Watchdog Guards**: Instant circuit breakers for remote LLM providers (e.g. Groq 429/failures), 5-identical-compiler-error detection, and 3-consecutive-error-count bottleneck halts.
- **Android AAPT2 & Dependency Extraction**: Automatic compilation of `res/` using Android SDK AAPT2 to generate `R.java`, parsing of Gradle dependency notations, and automatic extraction of `classes.jar` from Android Archive (`.aar`) packages.
- **Incremental Resume Engine**: Full checkpointing of stage artifacts allowing interrupted migrations to resume instantly from Stage 4 (Verifier) or Stage 3 (Migrator) without repeating AST analysis or characterization.

---

## 2. System Architecture & 6-Stage Graph Workflow

The modernization workflow is governed by a state machine implemented in `com.issam.apollo.orchestrator.ModernizationGraph`.

```
                  +----------------------------------------------+
                  | Stage 0: Pre-Processor                       |
                  | AAPT2 R.java Generation & AAR Jar Extraction |
                  +----------------------------------------------+
                                         |
                                         v
                  +----------------------------------------------+
                  | Stage 1: Analyzer Agent                      |
                  | AST Analysis, Topo-Sort & Spec Generation    |
                  +----------------------------------------------+
                                         |
                                         v
                  +----------------------------------------------+
                  | Stage 2: Characterization Tool               |
                  | Dynamic Java Run & Ground-Truth Test Capture |
                  +----------------------------------------------+
                                         |
                                         v
                  +----------------------------------------------+
                  | Stage 3: Migrator Agent                      |
                  | Topo-Ordered Kotlin Synthesis (Tier-1 Model) |
                  +----------------------------------------------+
                                         |
                                         v
           +-------------------> +--------------------------------+
           |                     | Stage 4: Verifier Agent        |
           |                     | K2JVMCompiler, Reflection Test |
           |                     +--------------------------------+
           |                                     |
    [Retry Loop / Fixes]                 [All Passed: 100%]
           |                                     |
           v                                     v
+-----------------------+              +-------------------+
| Stage 5: Fixer Agent  |              | Stage 6: Complete |
| 2-Tier Diagnostic Fix |              | Reports Generated |
+-----------------------+              +-------------------+
```

### Stage-by-Stage Breakdown

### Stage 0: Pre-Processor (`Aapt2Tool`, `GradleDependencyResolver`)
- **Location**: `src/main/kotlin/com/issam/apollo/tools/`
- **Responsibilities**:
  - Scans target project for `AndroidManifest.xml` and `res/` folder.
  - If Android project: invokes `aapt2 compile --dir <res>` and `aapt2 link --manifest <manifest> -I <android.jar> --java <out>` to generate real `R.java`.
  - Parses `build.gradle` for `dependencies { ... }` blocks (both Android Support v7/v4 and modern AndroidX).
  - Resolves dependencies and unpacks `.aar` archives to extract embedded `classes.jar` into `libs/android-stubs/androidx-cache/`.

### Stage 1: Analyzer Agent (`AnalyzerAgent`)
- **Location**: `src/main/kotlin/com/issam/apollo/agents/AnalyzerAgent.kt`
- **Responsibilities**:
  - Discovers all `.java` source files in the target project.
  - Parses AST using `JavaParser`, extracting package names, imports, class types, interfaces, fields, and method signatures.
  - Builds directed dependency graph and computes topological sort order using Kahn's algorithm so zero-dependency models are modernized before consumers.
  - Writes architectural module specifications to `reports/specs/<ClassName>/<ClassName>-spec.json` and Markdown summaries to `reports/specs/<ClassName>/<ClassName>-spec.md`.

### Stage 2: Characterization Tool (`CharacterizationTool`)
- **Location**: `src/main/kotlin/com/issam/apollo/tools/CharacterizationTool.kt`
- **Responsibilities**:
  - Dynamically compiles legacy Java classes using standard `javax.tools.JavaCompiler`.
  - Reflectively instantiates classes and executes public methods against deterministic test input vectors (boundary ints, edge strings, nulls, collections).
  - Captures exact outputs as golden ground-truth specifications in `reports/characterization/<ClassName>-ground-truth.json`.
  - For Android components invoking mocked or SDK APIs, records `STUB!:<exception>` sentinels to allow symmetric comparison during verification.

### Stage 3: Migrator Agent (`MigratorAgent`)
- **Location**: `src/main/kotlin/com/issam/apollo/agents/MigratorAgent.kt`
- **Responsibilities**:
  - Migrates Java classes to Kotlin in topological order.
  - Injects relevant transformation patterns from `knowledge-base/java-to-kotlin-patterns.json` into prompt context.
  - Provides signatures of upstream already-migrated Kotlin modules to maintain inter-module type safety.
  - Invokes `qwen3-coder:30b` as primary model via `LlmConfig`.
  - Cleans Markdown fences, normalizes package headers, and writes `.kt` files to `migrated-src/<packagePath>/<ClassName>.kt`.

### Stage 4: Verifier Agent (`VerifierAgent`)
- **Location**: `src/main/kotlin/com/issam/apollo/agents/VerifierAgent.kt`
- **Responsibilities**:
  - Collects all migrated Kotlin files and combines them with generated `R.java`, `android.jar`, and resolved dependency JARs.
  - Executes in-process compilation using `KotlinCompileTool` (`K2JVMCompiler`).
  - Runs `KtLintTool` to check Kotlin coding standards and formatting.
  - Builds a sandboxed `URLClassLoader` and reflectively executes the same characterization test vectors captured in Stage 2 against the compiled Kotlin `.class` files.
  - Compares expected vs actual output. Evaluates Android `STUB!` sentinels as behavioral matches if both sides throw the expected framework stub sentinel.
  - Outputs detailed module logs and verification summary to `reports/verification/verification-summary.md`.

### Stage 5: Fixer Agent (`FixerAgent`)
- **Location**: `src/main/kotlin/com/issam/apollo/agents/FixerAgent.kt`
- **Responsibilities**:
  - Analyzes compiler error logs, missing symbols, type mismatches, and failed test cases.
  - Tracks individual per-module retry counters (`regenCounters[className]`).
  - **2-Tier Routing**: Attempts 0, 1, and 2 (runs 1–3) use primary (`qwen3-coder:30b`). Attempt 3 and above (4th run and the rest) escalate to (`qwen3.8:27b`).
  - **Fatal Persistence Guard**: If the exact same compiler error persists across 5 consecutive attempts on a module, halts execution with `FatalWatchdogAbortException` to request human developer review.
  - Logs write audits with line counts and SHA-256 hashes to detect no-op patches.

### Stage 6: Orchestration, Resume & Reporting (`ModernizationGraph`, `ResumeManager`, `Main.kt`)
- **Location**: `src/main/kotlin/com/issam/apollo/orchestrator/` & `Main.kt`
- **Responsibilities**:
  - Coordinates transitions between `ANALYZER`, `CHARACTERIZATION`, `MIGRATOR`, `VERIFIER`, `FIXER`, and `COMPLETED`.
  - Detects global LLM bottlenecks (if the total count of failing tests/errors is identical for 3 consecutive verification passes, halts immediately).
  - Saves structured migration envelopes (`reports/migration-report-<timestamp>.json`) and Markdown summaries (`reports/migration-summary.md`).
  - Ensures clean JVM process exit (`kotlin.system.exitProcess`) to prevent Gradle daemon hangs.

---

## 3. Directory Layout & Key Files Map

```
Apollo-agent/
|-- .env                                  # LLM provider configuration, API keys, host URLs, model tags
|-- build.gradle.kts                      # Project Gradle build configuration (Kotlin 2.3.21, Java 17)
|-- settings.gradle.kts                   # Project name and repository settings
|-- PROJECT_INDEX.md                      # Operational guide for AI copilots
|-- README.md                             # Comprehensive technical documentation
|-- CLAUDE.md                             # This file (Complete Claude Code manual)
|
|-- knowledge-base/
|   `-- java-to-kotlin-patterns.json      # Curated migration patterns (POJOs, Android, SQLite, Coroutines)
|
|-- libs/
|   `-- android-stubs/                    # Android framework stubs and cached dependencies
|       |-- android.jar                   # Android API Level 26 / 34 framework stubs
|       |-- androidx-stubs.jar            # AndroidX basic stub classes (AppCompatActivity, etc.)
|       `-- androidx-cache/               # Cached extracted classes.jar from resolved AAR packages
|
|-- reports/                              # Generated run artifacts & execution reports
|   |-- specs/                            # Module specifications (<ClassName>/<ClassName>-spec.json & .md)
|   |-- characterization/                 # Stage 2 ground-truth test vectors (<ClassName>-ground-truth.json)
|   |-- verification/                     # Stage 4 test reports & verification-summary.md
|   |-- topo-order.md                     # Computed topological sort order
|   |-- migration-summary.md              # Human-readable pipeline execution summary
|   `-- migration-report-*.json           # Machine-readable state envelopes
|
|-- migrated-src/                         # Output folder for synthesized Kotlin source files
|
|-- sample-legacy/                        # Built-in legacy Java testbed (5 test classes)
|   `-- src/main/java/com/example/legacy/
|       |-- User.java                     # Model / POJO with equals/hashCode
|       |-- StringUtils.java              # Static utility class
|       |-- TrickyMath.java               # Edge case arithmetic & overflow logic
|       |-- AsyncDataLoader.java          # Concurrency / callback handling
|       `-- UserService.java              # Service class depending on User & StringUtils
|
`-- src/
    |-- main/kotlin/com/issam/apollo/
    |   |-- Main.kt                       # Entry point, CLI flags, terminal rendering, exitProcess
    |   |-- agents/
    |   |   |-- AnalyzerAgent.kt          # Stage 1: JavaParser AST, Kahn's topo-sort, spec generator
    |   |   |-- MigratorAgent.kt          # Stage 3: Dependency-aware Kotlin synthesizer
    |   |   |-- VerifierAgent.kt          # Stage 4: K2JVMCompiler, sandbox classloading, test evaluation
    |   |   `-- FixerAgent.kt             # Stage 5: Targeted repair, 2-tier escalation, persistence halt
    |   |-- orchestrator/
    |   |   |-- ModernizationGraph.kt     # Stage 6: Graph engine, bottleneck detector, workflow loop
    |   |   `-- ResumeManager.kt          # Resume validation, artifact checker, project isolation
    |   |-- config/
    |   |   `-- LlmConfig.kt              # 2-tier Ollama routing, Groq circuit breaker, HTTP clients
    |   |-- tools/
    |   |   |-- Aapt2Tool.kt              # AAPT2 compiler for Android R.java generation
    |   |   |-- AndroidSdkResolver.kt     # Android SDK & android.jar discovery
    |   |   |-- CharacterizationTool.kt   # Stage 2: Java dynamic compiler & reflective tester
    |   |   |-- GradleDependencyParser.kt # Regex & AST parser for build.gradle dependencies
    |   |   |-- GradleDependencyResolver.kt# Gradle resolver & AAR extractor
    |   |   |-- KotlinCompileTool.kt      # Embedded K2JVMCompiler wrapper
    |   |   `-- KtLintTool.kt             # KtLint integration for code style auditing
    |   |-- knowledge/
    |   |   `-- MigrationPatterns.kt      # Knowledge base loader & prompt injector
    |   `-- mcp/
    |       `-- McpServer.kt              # Ktor embedded server for MCP REST endpoints
    `-- test/kotlin/com/issam/apollo/
        |-- agents/
        |   |-- AnalyzerAgentTest.kt      # Unit tests for AnalyzerAgent
        |   |-- FixerAgentTest.kt         # Unit tests for FixerAgent (9 test cases)
        |   `-- VerifierAgentTest.kt      # E2E live LLM test for VerifierAgent (~5-10 min)
        |-- config/
        |   `-- LlmConfigTest.kt          # Unit tests for 2-tier routing & circuit breaker
        `-- orchestrator/
            |-- ResumeManagerTest.kt      # Unit tests for ResumeManager
            `-- OrchestratorTest.kt       # E2E live LLM test for ModernizationGraph (~5-10 min)
```

---

## 4. LLM Routing, Resilience & Watchdogs

### 2-Tier Ollama Model Routing

The system uses a strict 2-Tier routing model defined in `LlmConfig.kt`:

| Stage | Target / Attempt Count | Model Used | Env Variable | Rationale |
| :--- | :--- | :--- | :--- | :--- |
| **Stage 1, 3 & 5 (Primary)** | Fresh starts, 1st try resume, runs 1–3 | `qwen3-coder:30b` | `OLLAMA_MODEL_PRIMARY` | Specialized coding model for initial analysis, full Java-to-Kotlin migrations, and initial repairs. |
| **Stage 5 (Escalation)** | 4th run and the rest (attempts 4+) | `qwen3.8:27b` | `OLLAMA_MODEL_ESCALATION` | Generalist reasoning model for breaking persistent bottlenecks in stubborn modules. |

### Circuit Breakers & Watchdogs

1. **Instant-Trip Provider Circuit Breaker**:
   - If an external cloud provider (e.g. Groq) encounters a `429 Too Many Requests`, `503 Service Unavailable`, or any non-success HTTP status, the circuit breaker trips immediately.
   - Once tripped, the provider is bypassed for the remainder of the pipeline run and requests fail over directly to Ollama.
2. **5-Identical-Error Persistence Abort**:
   - In `FixerAgent.kt`, an error history tracks compiler error messages per module.
   - If the exact same compiler error persists across 5 consecutive fixer attempts without any modification or progress, execution halts immediately with `FatalWatchdogAbortException`.
3. **3-Consecutive Error Count Bottleneck Abort**:
   - In `ModernizationGraph.kt`, if the total error count (compile errors + failing tests) is identical for 3 consecutive verification passes, the pipeline halts immediately to prevent infinite token loops.
4. **Streaming Inactivity Watchdog**:
   - LLM streaming connections monitor chunk arrivals. If no chunk is received within `timeoutSeconds`, the connection is actively aborted and the pipeline proceeds to fallback or preserves the existing source for the next retry.

---

## 5. Build, Test, and Execution Commands

### Essential Gradle Commands

#### 1. Compile & Build Verification
```powershell
# Compile main Kotlin source and test source (fast, < 5 seconds)
./gradlew compileKotlin compileTestKotlin
```

#### 2. Agent-by-Agent Unit Testing (FAST: 10–20 seconds)
Always run unit tests agent-by-agent. These tests run in isolation with mock/pre-canned data:
```powershell
# Test Analyzer Agent (AST parsing, dependencies, topological sort)
./gradlew test --tests com.issam.apollo.agents.AnalyzerAgentTest

# Test LLM Config (2-Tier routing, fallback, circuit breaker)
./gradlew test --tests com.issam.apollo.config.LlmConfigTest

# Test Resume Manager (State persistence, artifact validation, scoping)
./gradlew test --tests com.issam.apollo.orchestrator.ResumeManagerTest

# Test Fixer Agent (Repair loop, escalation triggers, error histories)
./gradlew test --tests com.issam.apollo.agents.FixerAgentTest
```

#### 3. Live LLM End-to-End Testing (HEAVY: 5–10 minutes)
These tests perform full live LLM inference across all 5 sample legacy modules:
```powershell
# Verifier Agent Live LLM Test
./gradlew test --tests com.issam.apollo.agents.VerifierAgentTest

# Graph Orchestrator Live LLM Test
./gradlew test --tests com.issam.apollo.orchestrator.OrchestratorTest
```

#### 4. Running the Migration Pipeline CLI
```powershell
# Clean run on built-in sample project
./gradlew run --args="sample-legacy"

# Resume an interrupted migration on built-in sample project
./gradlew run --args="--resume sample-legacy"

# Clean run on a real Android / Java project
./gradlew run --args="D:/Projects/projects-to-test-on/SimpleToDo/app/src/main/java"

# Resume an interrupted run on a real project
./gradlew run --args="--resume D:/Projects/projects-to-test-on/SimpleToDo/app/src/main/java"

# Launch MCP HTTP REST Server mode
./gradlew run --args="--server 8080"
```

---

## 6. Critical Operational Rules for AI Assistants

1. **Strict ASCII Console Output**:
   - Windows consoles running CP1252 / CP437 mangle Unicode box-drawing characters (`┌`, `─`, `│`, `├`, `└`) and emojis into garbled sequences (`ΓöîΓöÇΓöÇ...`).
   - All console output in `Main.kt`, `ModernizationGraph.kt`, `VerifierAgent.kt`, `FixerAgent.kt`, and `LlmConfig.kt` MUST strictly use standard ASCII (`+`, `-`, `|`, `=`, `[PASS]`, `[FAIL]`, `[Audit]`, `[WARN]`).
2. **Explicit JVM Exit**:
   - `Main.kt` must always call `kotlin.system.exitProcess(exitCode)` upon completion or abort. If omitted, background Gradle daemon threads cause the terminal to hang at `<==========---> 83% EXECUTING`.
3. **Project Isolation on Resume**:
   - `ResumeManager.kt` must strictly verify that all loaded `ModuleSpec` instances and migrated source files belong to the active `targetProjectPath`. Artifacts from other projects must never leak into the topological order or status map.
4. **Fresh Retry Budget on Resume**:
   - On `--resume`, the pipeline-level retry counter (`state.retryCount`) is reset to `0` so the resumed pipeline gets a fresh execution pass budget. Modules needing repair have their `regenCounters` reset to `0` so they receive their full allocation of 3 Tier-1 attempts followed by Tier-2 escalation.
5. **Never Use `cd` in Tool Calls**:
   - The tool execution environment does not allow `cd`. Always specify the working directory via the `Cwd` parameter.
6. **Stop Daemon on Lock Failures**:
   - If Kotlin incremental compilation complains of storage lock collisions (`Storage for [...] is already registered`), run `./gradlew --stop` to kill lingering background daemons before running tests.

---

## 7. Troubleshooting & Common Failure Modes

| Symptom | Cause | Solution |
| :--- | :--- | :--- |
| `Could not load compiled Kotlin class for <ClassName>` | Sandboxed classloader cannot resolve superclasses (e.g. `AppCompatActivity` or deeper AndroidX dependencies) via reflection. | Verify that `libs/android-stubs/androidx-stubs.jar` contains the required classes or that `GradleDependencyResolver` extracted the AAR dependencies into `libs/android-stubs/androidx-cache/`. |
| `Ollama streaming returned empty content` | Remote Ollama server timed out, was overloaded, or prompt exceeded context limits. | Check connectivity to the Ollama endpoint (`OLLAMA_HOST` in `.env`), verify GPU memory on the host, or inspect prompt token count. |
| `BOTTLENECK DETECTED: The exact same number of errors (N) was identified...` | The LLM is generating repairs that do not fix compilation or test failures across 3 consecutive cycles. | Inspect `reports/verification/verification-summary.md` to see the exact failing tests or compiler errors and adjust `knowledge-base/java-to-kotlin-patterns.json`. |
| `FATAL ERROR PERSISTENCE: The same error in module '...' persisted across 5 consecutive repair attempts` | A specific compiler error is unresolvable by the LLM without manual pattern intervention. | Review the compiler error message logged in the console, add the necessary pattern or stub, and re-run with `--resume`. |
| Gradle hangs at `83% EXECUTING` after run | Non-daemon thread remained open in Ktor or Coroutine scope. | Ensure `kotlin.system.exitProcess(0)` or `kotlin.system.exitProcess(1)` is reached at the end of `Main.kt`. |

---

## 8. Summary Checklist for Code Modifications

Before submitting any code change:
1. Did you run `./gradlew compileKotlin compileTestKotlin` to verify compilation?
2. Did you run the relevant agent unit test (`./gradlew test --tests com.issam.apollo.agents.<AgentName>Test`)?
3. Are all console log strings formatted in clean standard ASCII without Unicode box lines or emojis?
4. Does `ResumeManager` preserve project isolation and relative path compatibility?
5. Does `Main.kt` preserve clean exit codes and `exitProcess` calls?
