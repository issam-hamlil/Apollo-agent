# Apollo Agent Console (Compose Multiplatform Desktop)

A visual front-end for the migration pipeline, for people who should not have to read
terminal output to understand what is happening.

## Run it

```bash
./gradlew :ui-desktop:run
```

Type the folder to migrate in the top bar, tick **Resume** to continue a previous run
(untick it for a clean start), and press **Start migration**.

## What each tab shows

| Tab | Answers |
| :-- | :-- |
| **Overview** | Which classes pass, how many recorded behaviours each one reproduces, and what each is doing right now. |
| **LLM Prompts** | Every request sent to a model — the full system prompt, the full user prompt, and the reply. This is where you see *why* a model made a bad edit. |
| **Compile Errors** | Error count and each compiler error grouped by file and line. Warnings are separated because they never block a run. |
| **Files** | Everything generated on disk — migrated Kotlin, Stage 1 specs, Stage 2 ground truth, Stage 4 verification, run reports — with a built-in viewer. |
| **Live Log** | The raw console, mirrored, for when the detail is wanted. |

The header strip always shows the current stage, the model currently in use, the compile
error count, the behaviour-test score, and which files the model is working on right now.

## How it works

The pipeline publishes typed events to `com.issam.apollo.telemetry.ApolloTelemetry`
(a `SharedFlow` in the core module). The UI runs `ModernizationGraph` in-process and
renders those events, so there is no polling, no IPC, and no log scraping.

Emitting is fire-and-forget and never throws, so running the CLI with no UI attached
behaves exactly as it did before:

```bash
./gradlew run --args="path/to/project"
```

`PipelineRunner.installConsoleMirror()` additionally tees `System.out`/`System.err` into
the Live Log, so console output written by code that predates the telemetry bus still
appears in the UI.

## Notes

- **Stop** cancels the run's coroutine. Work already in flight on another thread (an open
  LLM stream, a compile) finishes on its own — it is a request to stop, not a kill.
- The Files tab reads from disk each time an artifact is announced, so it also shows
  artifacts left over from earlier runs.
