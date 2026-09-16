package com.issam.apollo.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeprecationScannerTest {

    private val scanner = DeprecationScanner()

    @Test
    fun `test the knowledge base loads`() {
        assertTrue(scanner.allMigrations().isNotEmpty(), "deprecated-api-migrations.json must load")
    }

    @Test
    fun `test the support library and the activity result pair are both detected`() {
        // Exactly what SimpleToDo's legacy Java contains.
        val java = """
            package com.clinton.simpletodo.activities;
            import android.support.v7.app.AppCompatActivity;
            public class MainActivity extends AppCompatActivity {
                void edit() { startActivityForResult(intent, 20); }
                protected void onActivityResult(int a, int b, Intent data) { }
            }
        """.trimIndent()

        val ids = scanner.scanSource(java).map { it.migration.id }
        assertTrue(ids.contains("support-library-to-androidx"), "android.support.* must be flagged")
        assertTrue(ids.contains("activity-result-api"), "startActivityForResult/onActivityResult must be flagged")
    }

    @Test
    fun `test modern code produces no findings`() {
        val modern = """
            package com.demo
            import androidx.appcompat.app.AppCompatActivity
            class MainActivity : AppCompatActivity() {
                private val launcher = registerForActivityResult(StartActivityForResult()) { }
            }
        """.trimIndent()
        assertTrue(
            scanner.scanSource(modern).isEmpty(),
            "Already-modern code must not be flagged: ${scanner.scanSource(modern).map { it.migration.id }}"
        )
    }

    @Test
    fun `test a library that is merely old but not deprecated is left alone`() {
        // commons-io and junit 4 are old, still supported, and work fine from Kotlin.
        // Per the migration policy they must not be forced to change.
        val java = """
            import org.apache.commons.io.FileUtils;
            import org.junit.Test;
            class Thing { void f() { FileUtils.readFileToString(file); } }
        """.trimIndent()

        val ids = scanner.scanSource(java).map { it.migration.id }
        assertFalse(ids.any { it.contains("commons") }, "commons-io is not deprecated - leave it")
        assertFalse(ids.any { it.contains("junit") }, "JUnit 4 is not deprecated - leave it")
    }

    @Test
    fun `test findings carry the evidence and a replacement`() {
        val finding = scanner.scanSource("AsyncTask task = new AsyncTask();").single()
        assertEquals("asynctask-to-coroutines", finding.migration.id)
        assertTrue(finding.matched.contains("AsyncTask"), "the matched symbol is the evidence")
        assertTrue(finding.migration.modern.contains("coroutines"), "a concrete replacement must be named")
        assertTrue(finding.migration.guidance.isNotBlank(), "guidance must say how")
    }

    @Test
    fun `test prompt text is empty when nothing is deprecated and detailed when something is`() {
        assertEquals("", scanner.formatForPrompt(emptyList()), "no findings must add nothing to the prompt")

        val prompt = scanner.formatForPrompt(scanner.scanSource("class A { void f() { new Handler(); } }"))
        assertTrue(prompt.contains("DEPRECATED APIs DETECTED"))
        assertTrue(prompt.contains("Looper"), "the replacement must reach the model")
    }

    @Test
    fun `test the prompt never instructs the model to keep the original libraries`() {
        // The model must stay free to choose a better library.
        val everything = scanner.formatForPrompt(
            scanner.allMigrations().map { DeprecationFinding(it, it.match.take(1)) }
        ).lowercase()

        listOf("keep the same librar", "do not change librar", "must use the same librar").forEach {
            assertFalse(everything.contains(it), "guidance must not restrict library choice: found '$it'")
        }
    }
}
