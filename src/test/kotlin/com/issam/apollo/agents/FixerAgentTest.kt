package com.issam.apollo.agents

import com.issam.apollo.config.LlmConfig
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleSpec
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.VerificationResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixerAgentTest {

    @BeforeTest
    @AfterTest
    fun cleanup() {
        FixerAgent.resetErrorHistories()
        LlmConfig.resetWatchdogAbortCounts()
        LlmConfig.resetGroqRateLimit()
    }

    @Test
    fun `test FixerAgent increments regenCounters and respects retry cap`() {
        val fixerAgent = FixerAgent(maxRetriesPerModule = 2)

        var state = GraphState(
            targetProjectPath = "sample-legacy",
            moduleSpecs = mapOf(
                "UserService" to ModuleSpec(className = "UserService", packageName = "com.example.legacy")
            ),
            migratedCode = mutableMapOf("UserService.kt" to "broken code class class UserService {}"),
            moduleStatuses = mapOf("UserService" to ModuleStatus.FAILED),
            verificationResult = VerificationResult(
                compiledSuccessfully = false,
                compilerErrors = listOf("UserService.kt:1:1: error: Syntax error 1")
            )
        )

        // Iteration 1 to 2 with varying errors
        for (i in 1..2) {
            state = state.copy(
                verificationResult = VerificationResult(
                    compiledSuccessfully = false,
                    compilerErrors = listOf("UserService.kt:$i:1: error: Syntax error $i")
                )
            )
            state = fixerAgent.execute(state)
            assertEquals(i, state.regenCounters["UserService"])
            assertEquals("FIX_APPLIED", state.currentStage)
        }

        // Iteration 3 (Cap reached at 2 attempts)
        state = fixerAgent.execute(state)
        assertEquals(2, state.regenCounters["UserService"], "Regen counter should cap at 2")
        assertEquals(ModuleStatus.FAILED, state.moduleStatuses["UserService"], "Module status should remain FAILED after cap")
    }

    @Test
    fun `test FixerAgent ignores modules with 0 errors and preserves their code`() {
        val fixerAgent = FixerAgent(maxRetriesPerModule = 5)

        val cleanCode = "package com.example.legacy\n\nclass CleanHelper {\n    fun ok() = true\n}"
        val brokenCode = "package com.example.legacy\n\nclass BrokenService {\n    fun fail() = \n}"

        val state = GraphState(
            targetProjectPath = "sample-legacy",
            moduleSpecs = mapOf(
                "CleanHelper" to ModuleSpec(className = "CleanHelper", packageName = "com.example.legacy"),
                "BrokenService" to ModuleSpec(className = "BrokenService", packageName = "com.example.legacy")
            ),
            migratedCode = mutableMapOf(
                "CleanHelper.kt" to cleanCode,
                "BrokenService.kt" to brokenCode
            ),
            moduleStatuses = mapOf(
                "CleanHelper" to ModuleStatus.MIGRATED,
                "BrokenService" to ModuleStatus.FAILED
            ),
            verificationResult = VerificationResult(
                compiledSuccessfully = false,
                compilerErrors = listOf("BrokenService.kt:4:19: error: expression expected")
            )
        )

        val updated = fixerAgent.execute(state)

        // CleanHelper must NOT be attempted or touched
        assertEquals(null, updated.regenCounters["CleanHelper"])
        assertEquals(cleanCode, updated.migratedCode["CleanHelper.kt"])

        // BrokenService must be attempted
        assertEquals(1, updated.regenCounters["BrokenService"])
    }

    @Test
    fun `test applySearchReplacePatch with exact and multi-block replacements`() {
        val fixer = FixerAgent()

        val originalSource = """
            package com.example.simpletodo

            import android.os.Bundle
            import android.widget.TextView

            class EditItemActivity {
                fun onCreate(savedInstanceState: Bundle?) {
                    supportActionBar?.title = "Edit Item"
                    val tv = findViewById(R.id.tv)
                }
            }
        """.trimIndent()

        val patchResponse = """
            <<<<<<< SEARCH
            import android.os.Bundle
            import android.widget.TextView
            =======
            import android.os.Bundle
            import android.widget.TextView
            import android.view.WindowManager
            >>>>>>> REPLACE

            <<<<<<< SEARCH
                    supportActionBar?.title = "Edit Item"
            =======
                    title = "Edit Item"
            >>>>>>> REPLACE
        """.trimIndent()

        val patched = fixer.applySearchReplacePatch(originalSource, patchResponse)

        assertTrue(patched.contains("import android.view.WindowManager"), "Should have added WindowManager import")
        assertTrue(patched.contains("title = \"Edit Item\""), "Should have replaced supportActionBar?.title")
        assertFalse(patched.contains("supportActionBar?.title"), "Old title code must be removed")
        assertTrue(patched.contains("class EditItemActivity"), "Untouched code should be preserved")
    }

    @Test
    fun `test applySearchReplacePatch with whitespace and line ending tolerance`() {
        val fixer = FixerAgent()

        val originalSource = "package com.example.todo\r\n\r\nclass MainActivity {\r\n    val x = 1\r\n}\r\n"
        val patch = """
            <<<<<<< SEARCH
                val x = 1
            =======
                val x = 2
            >>>>>>> REPLACE
        """.trimIndent()

        val patched = fixer.applySearchReplacePatch(originalSource, patch)
        assertTrue(patched.contains("val x = 2"))
        assertFalse(patched.contains("val x = 1"))
    }

    @Test
    fun `test cleanLLMOutput handles prose, markdown fences, duplicate imports, and cross-module redeclarations`() {
        val fixerAgent = FixerAgent()

        val rawLlmResponseWithProseAndDuplicates = """
            Here is the fixed file for Task:
            ```kotlin
            package com.clinton.simpletodo.data;

            import android.content.Intent;
            import android.os.Bundle;
            import android.content.Intent;

            class TaskContract {
                val TABLE_NAME = "tasks"
            }

            class Task {
                var id: Long = 0
            }
            ```
            Hope this helps!
        """.trimIndent()

        val cleaned = fixerAgent.cleanLLMOutput(
            rawText = rawLlmResponseWithProseAndDuplicates,
            className = "Task",
            otherModuleNames = setOf("TaskContract", "MainActivity")
        )

        // 1. Should not contain prose or markdown fences
        assertFalse(cleaned.contains("Here is the fixed file"), "Should strip prose before code block")
        assertFalse(cleaned.contains("Hope this helps"), "Should strip prose after code block")
        assertFalse(cleaned.contains("```"), "Should strip code fences")

        // 2. Should strip trailing semicolons
        assertTrue(cleaned.contains("package com.clinton.simpletodo.data"), "Should contain package statement without semicolon")
        assertFalse(cleaned.contains("package com.clinton.simpletodo.data;"), "Should not have trailing semicolon on package")

        // 3. Should deduplicate imports
        val intentImportCount = cleaned.lines().count { it.trim() == "import android.content.Intent" }
        assertEquals(1, intentImportCount, "Import 'android.content.Intent' must appear exactly once")

        // 4. Should remove top-level redeclaration of TaskContract
        assertFalse(cleaned.contains("class TaskContract"), "Task.kt should not redeclare TaskContract")
        assertTrue(cleaned.contains("class Task"), "Task.kt must retain class Task definition")
    }

    @Test
    fun `test consecutive Fixer attempts perform full wholesale overwrite without content duplication`() {
        val tempDir = java.io.File.createTempFile("apollo-migrated-test", "").apply {
            delete()
            mkdirs()
        }
        try {
            val fixerAgent = FixerAgent(migratedOutputDir = tempDir, maxRetriesPerModule = 5)

            val initialBrokenTask = """
                package com.clinton.simpletodo.data

                import android.content.Intent
                import android.content.Intent

                class TaskContract {
                    val TABLE_NAME = "tasks"
                }

                class class Task {
                    var title: String = ""
                }
            """.trimIndent()

            var state = GraphState(
                targetProjectPath = "sample-legacy",
                moduleSpecs = mapOf(
                    "Task" to ModuleSpec(
                        className = "Task",
                        packageName = "com.clinton.simpletodo.data"
                    ),
                    "TaskContract" to ModuleSpec(
                        className = "TaskContract",
                        packageName = "com.clinton.simpletodo.data"
                    )
                ),
                migratedCode = mutableMapOf("Task.kt" to initialBrokenTask),
                moduleStatuses = mapOf("Task" to ModuleStatus.FAILED),
                verificationResult = VerificationResult(
                    compiledSuccessfully = false,
                    compilerErrors = listOf("Task.kt: Syntax error: class class Task", "Unresolved reference: TaskContract")
                )
            )

            // Attempt 1
            state = fixerAgent.execute(state)
            assertEquals(1, state.regenCounters["Task"])

            val outputSubfolder = java.io.File(tempDir, "com/clinton/simpletodo/data")
            val taskFile = java.io.File(outputSubfolder, "Task.kt")
            assertTrue(taskFile.exists(), "Task.kt should be written on attempt 1")
            val contentAttempt1 = taskFile.readText()
            println("=== CONTENT ATTEMPT 1 ===\n$contentAttempt1\n=========================")

            // Verify attempt 1 is a valid file that does not redeclare other modules and fixes syntax
            assertFalse(contentAttempt1.contains("class TaskContract"), "Attempt 1 output must not redeclare TaskContract")
            assertFalse(contentAttempt1.contains("class class Task"), "Attempt 1 output must fix duplicate class keyword")
            val classTaskCountAttempt1 = Regex("""\bclass\s+Task\b""").findAll(contentAttempt1).count()
            assertEquals(1, classTaskCountAttempt1, "class Task must be declared exactly once on attempt 1")

            // Attempt 2 on same module
            state = state.copy(
                verificationResult = VerificationResult(
                    compiledSuccessfully = false,
                    compilerErrors = listOf("Task.kt: Unresolved reference: java.util")
                ),
                moduleStatuses = mapOf("Task" to ModuleStatus.FAILED)
            )
            state = fixerAgent.execute(state)
            assertEquals(2, state.regenCounters["Task"])

            val contentAttempt2 = taskFile.readText()

            // Verify attempt 2 is a full wholesale replacement and has NOT accumulated duplicate declarations
            assertFalse(contentAttempt2.contains("class TaskContract"), "Attempt 2 output must NOT accumulate TaskContract redeclaration")
            val classTaskCountAttempt2 = Regex("""\bclass\s+Task\b""").findAll(contentAttempt2).count()
            assertEquals(1, classTaskCountAttempt2, "class Task must be declared exactly once in Task.kt across retries")
            assertTrue(contentAttempt2.contains("class Task"), "Task.kt must still contain class Task definition")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test applyRuleBasedFix with spec produces compilable stub using real class fields and methods`() {
        val fixer = FixerAgent()

        val spec = ModuleSpec(
            className = "SplashActivity",
            packageName = "com.clinton.simpletodo",
            fields = listOf("int SPLASH_DURATION"),
            methods = listOf("void onCreate(Bundle savedInstanceState)", "boolean isFirstLaunch()")
        )

        val result = fixer.applyRuleBasedFix(
            brokenKotlin = "",
            compilerErrors = emptyList(),
            className = "SplashActivity",
            otherModuleNames = setOf("MainActivity", "TasksDatabaseHelper"),
            spec = spec
        )

        // Must use the correct package
        assertTrue(result.contains("package com.clinton.simpletodo"), "Must use real package name")

        // Must declare the correct class name — NOT any sample-legacy class
        assertTrue(result.contains("class SplashActivity"), "Must declare SplashActivity")
        assertFalse(result.contains("class User"), "Must NOT reference User — that belongs to a different project")
        assertFalse(result.contains("userList"), "Must NOT reference userList — sample-legacy artifact")
        assertFalse(result.contains("findById"), "Must NOT reference findById — sample-legacy artifact")
        assertFalse(result.contains("com.example.legacy"), "Must NOT use sample-legacy package")

        // Real field and method names from the spec must appear
        assertTrue(result.contains("SPLASH_DURATION"), "Must include SPLASH_DURATION from spec fields")
        assertTrue(result.contains("fun onCreate"), "Must include onCreate method from spec")
        assertTrue(result.contains("fun isFirstLaunch"), "Must include isFirstLaunch method from spec")

        // All methods must have TODO stubs — indicating safe compilable bodies
        assertTrue(result.contains("TODO("), "Methods must have TODO stub bodies")
    }

    @Test
    fun `test applyRuleBasedFix without spec emits empty compilable stub rather than crashing or emitting sample-legacy code`() {
        val fixer = FixerAgent()

        val result = fixer.applyRuleBasedFix(
            brokenKotlin = "",
            compilerErrors = emptyList(),
            className = "EditItemActivity",
            otherModuleNames = emptySet(),
            spec = null
        )

        // Must declare the target class name
        assertTrue(result.contains("class EditItemActivity"), "Stub must declare EditItemActivity")
        assertFalse(result.contains("User"), "Empty stub must not reference User")
        assertFalse(result.contains("com.example.legacy"), "Empty stub must not use sample-legacy package")
    }

    @Test
    fun `test gatherFailureLogsForModule includes all compiler diagnostics with line numbers and snippets without truncation`() {
        val fixer = FixerAgent()

        val compilerLogs = listOf(
            "D:\\Projects\\SimpleToDo\\MainActivity.kt:42:15: error: unresolved reference: btnAddItem",
            "        val btnAddItem = findViewById<Button>(R.id.btnAddItem)",
            "              ^",
            "D:\\Projects\\SimpleToDo\\MainActivity.kt:48:9: error: type mismatch: inferred type is String? but String was expected",
            "        val title: String = intent.getStringExtra(\"title\")",
            "                            ^",
            "D:\\Projects\\SimpleToDo\\MainActivity.kt:55:12: error: cannot find a parameter with this name: count",
            "        update(count = 1)",
            "               ^",
            "D:\\Projects\\SimpleToDo\\EditItemActivity.kt:20:5: error: unresolved reference: itemPosition",
            "        this.itemPosition = pos",
            "             ^"
        )

        val verification = VerificationResult(
            compiledSuccessfully = false,
            compilerErrors = compilerLogs
        )

        val (mainLogs, mainIssues) = fixer.gatherFailureLogsForModule("MainActivity", verification)

        // Must count all 3 MainActivity errors
        assertEquals(3, mainIssues, "Must detect exactly 3 compiler errors for MainActivity")

        // Must include all 3 error headers, locations, and snippets
        assertTrue(mainLogs.contains("unresolved reference: btnAddItem"), "Must contain error 1 message")
        assertTrue(mainLogs.contains("Line 42, Column 15"), "Must contain error 1 location")
        assertTrue(mainLogs.contains("val btnAddItem = findViewById<Button>(R.id.btnAddItem)"), "Must contain error 1 snippet")

        assertTrue(mainLogs.contains("type mismatch: inferred type is String? but String was expected"), "Must contain error 2 message")
        assertTrue(mainLogs.contains("Line 48, Column 9"), "Must contain error 2 location")
        assertTrue(mainLogs.contains("val title: String = intent.getStringExtra(\"title\")"), "Must contain error 2 snippet")

        assertTrue(mainLogs.contains("cannot find a parameter with this name: count"), "Must contain error 3 message")
        assertTrue(mainLogs.contains("Line 55, Column 12"), "Must contain error 3 location")
        assertTrue(mainLogs.contains("update(count = 1)"), "Must contain error 3 snippet")

        // Must NOT leak EditItemActivity errors into MainActivity logs
        assertFalse(mainLogs.contains("unresolved reference: itemPosition"), "MainActivity logs must not include EditItemActivity errors")

        // Check EditItemActivity logs
        val (editLogs, editIssues) = fixer.gatherFailureLogsForModule("EditItemActivity", verification)
        assertEquals(1, editIssues, "Must detect exactly 1 compiler error for EditItemActivity")
        assertTrue(editLogs.contains("unresolved reference: itemPosition"), "Must contain EditItemActivity error message")
        assertTrue(editLogs.contains("Line 20, Column 5"), "Must contain EditItemActivity location")
    }

    @Test
    fun `test transient LLM failure is reported as a no-op and never counted as a repair`() {
        val tempDir = java.io.File.createTempFile("apollo-noop-test", "").apply {
            delete()
            mkdirs()
        }
        try {
            // Reproduces the provider outage seen in production: every fallback provider fails,
            // so the fixer preserves the existing source byte-for-byte.
            val fixerAgent = FixerAgent(
                migratedOutputDir = tempDir,
                maxRetriesPerModule = 5,
                llmCall = { _, _, _, moduleName, _ ->
                    throw RuntimeException("Ollama streaming returned empty content for '$moduleName'.")
                }
            )

            val existingCode = "package com.clinton.simpletodo\n\nclass TasksDatabaseHelper {\n    fun query(): String? = null\n}"

            val state = GraphState(
                targetProjectPath = "sample-legacy",
                moduleSpecs = mapOf(
                    "TasksDatabaseHelper" to ModuleSpec(className = "TasksDatabaseHelper", packageName = "com.clinton.simpletodo")
                ),
                migratedCode = mutableMapOf("TasksDatabaseHelper.kt" to existingCode),
                moduleStatuses = mapOf("TasksDatabaseHelper" to ModuleStatus.FAILED),
                verificationResult = VerificationResult(
                    compiledSuccessfully = true,
                    testsFailed = 21,
                    testFailures = listOf("TasksDatabaseHelper.testInsert expected 1 got 0")
                )
            )

            val updated = fixerAgent.execute(state)

            // The source must be preserved untouched...
            assertEquals(existingCode, updated.migratedCode["TasksDatabaseHelper.kt"], "Failed LLM call must preserve existing source")

            // ...and must NOT be advertised to the orchestrator as a repair.
            val metrics = updated.reports.last().metrics
            assertEquals("0", metrics["repairedModules"], "A byte-identical module must not count as a repaired module")
            assertEquals("1", metrics["noopModules"], "A byte-identical module must be reported as a no-op")
            assertEquals("TasksDatabaseHelper", metrics["noopModuleList"])
            assertFalse(
                metrics["repairedModuleList"].orEmpty().contains("TasksDatabaseHelper"),
                "Unchanged module must not appear in the repaired module list"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test effective patch is counted as a repair and not as a no-op`() {
        val tempDir = java.io.File.createTempFile("apollo-repair-test", "").apply {
            delete()
            mkdirs()
        }
        try {
            val fixerAgent = FixerAgent(
                migratedOutputDir = tempDir,
                maxRetriesPerModule = 5,
                llmCall = { _, _, _, _, _ ->
                    """
                    <<<<<<< SEARCH
                        fun query(): String = null
                    =======
                        fun query(): String? = null
                    >>>>>>> REPLACE
                    """.trimIndent()
                }
            )

            val brokenCode = "package com.clinton.simpletodo\n\nclass TasksDatabaseHelper {\n    fun query(): String = null\n}"

            val state = GraphState(
                targetProjectPath = "sample-legacy",
                moduleSpecs = mapOf(
                    "TasksDatabaseHelper" to ModuleSpec(className = "TasksDatabaseHelper", packageName = "com.clinton.simpletodo")
                ),
                migratedCode = mutableMapOf("TasksDatabaseHelper.kt" to brokenCode),
                moduleStatuses = mapOf("TasksDatabaseHelper" to ModuleStatus.FAILED),
                verificationResult = VerificationResult(
                    compiledSuccessfully = false,
                    compilerErrors = listOf("TasksDatabaseHelper.kt:4:28: error: null can not be a value of a non-null type String")
                )
            )

            val updated = fixerAgent.execute(state)

            assertTrue(
                updated.migratedCode["TasksDatabaseHelper.kt"]!!.contains("fun query(): String? = null"),
                "Patch should have been applied"
            )

            val metrics = updated.reports.last().metrics
            assertEquals("1", metrics["repairedModules"], "An effective patch must count as a repaired module")
            assertEquals("0", metrics["noopModules"], "An effective patch must not be reported as a no-op")
            assertEquals("TasksDatabaseHelper", metrics["repairedModuleList"])
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test deduplicateAnnotations collapses patch-duplicated annotations without touching distinct ones`() {
        val fixer = FixerAgent()

        // Exactly the shape that broke the SimpleToDo run:
        // "EditItemActivity.kt:90:9: error: this annotation is not repeatable."
        val duplicated = """
            class EditItemActivity {
                companion object {
                    @JvmStatic
                    @JvmStatic
                    fun preferredCase(original: String?): String? {
                        return original
                    }
                }
            }
        """.trimIndent()

        val cleaned = fixer.deduplicateAnnotations(duplicated)

        assertEquals(1, cleaned.lines().count { it.trim() == "@JvmStatic" }, "Repeated @JvmStatic must collapse to one")
        assertTrue(cleaned.contains("fun preferredCase"), "The annotated declaration must be preserved")

        // Distinct annotations stacked on one declaration must all survive.
        val distinct = """
            @JvmStatic
            @JvmOverloads
            fun build(a: String, b: Int = 0) = a
        """.trimIndent()
        assertEquals(distinct, fixer.deduplicateAnnotations(distinct), "Distinct annotations must be left alone")

        // The same annotation on two different declarations is not a duplicate.
        val separateDeclarations = """
            @JvmStatic
            fun a() = 1

            @JvmStatic
            fun b() = 2
        """.trimIndent()
        assertEquals(
            2,
            fixer.deduplicateAnnotations(separateDeclarations).lines().count { it.trim() == "@JvmStatic" },
            "Annotations on separate declarations must both survive"
        )

        // A repeatable annotation carrying different arguments must be left alone.
        val differentArgs = """
            @Suppress("UNCHECKED_CAST")
            @Suppress("DEPRECATION")
            fun c() = 3
        """.trimIndent()
        assertEquals(differentArgs, fixer.deduplicateAnnotations(differentArgs), "Different arguments means different annotations")

        // Inline repetition on a single line collapses too.
        assertEquals(
            "@JvmStatic fun d() = 4",
            fixer.deduplicateAnnotations("@JvmStatic @JvmStatic fun d() = 4")
        )
    }

    @Test
    fun `test cleanLLMOutput removes a duplicated annotation introduced by a patch`() {
        val fixer = FixerAgent()

        val patched = """
            package com.clinton.simpletodo.activities

            class EditItemActivity {
                companion object {
                    @JvmStatic
                    @JvmStatic
                    fun preferredCase(original: String?): String? = original
                }
            }
        """.trimIndent()

        val cleaned = fixer.cleanLLMOutput(patched, className = "EditItemActivity", otherModuleNames = emptySet())

        assertEquals(
            1,
            cleaned.lines().count { it.trim() == "@JvmStatic" },
            "cleanLLMOutput must not emit a non-repeatable annotation twice"
        )
        assertTrue(cleaned.contains("fun preferredCase"), "Declaration must survive cleaning")
    }
}
