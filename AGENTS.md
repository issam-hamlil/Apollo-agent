# 🤖 Apollo Agent — Workspace AI Agent Guidelines

> **CRITICAL DIRECTIVE FOR ALL AI AGENTS & COPILOTS:**  
> Before analyzing, modifying, or testing any part of this codebase, read [PROJECT_INDEX.md](file:///d:/Projects/Apollo-agent/PROJECT_INDEX.md) and [README.md](file:///d:/Projects/Apollo-agent/README.md).

---

## ⚡ Quick Reference for Agents

### 1. Key Rules
- **2-Tier Model Routing**: `qwen3-coder:30b` is the default model for fresh starts, first try resumes, and initial runs 1–3. `qwen3.8:27b` is the default model for the 4th run and the rest.
- **Resilience**: Groq circuit breaker trips on ANY non-success response. 5-identical compiler errors or 3-consecutive watchdog aborts will halt execution immediately.
- **Process Termination**: `Main.kt` must always call `kotlin.system.exitProcess(exitCode)` to prevent Gradle daemon hangs at `<==========---> 83% EXECUTING`.

### 2. Testing Strategy
- **Always test agent-by-agent** (`./gradlew test --tests com.issam.apollo.agents.<AgentName>Test`). Agent unit tests take **10–20 seconds**.
- Avoid running blind `./gradlew test` unless explicitly requested, because `VerifierAgentTest` and `OrchestratorTest` run live LLM inferences on 5 classes and take **5–10 minutes**.

For full details on architecture, commands, and file indices, see [PROJECT_INDEX.md](file:///d:/Projects/Apollo-agent/PROJECT_INDEX.md).
