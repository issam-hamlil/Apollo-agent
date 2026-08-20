package com.issam.apollo.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Build-environment faults must be recognised as such. If they leak into the repair loop
 * the model is asked to fix a classpath with a source edit, which it cannot do - it burns
 * the retry budget and produces confident nonsense.
 */
class EnvironmentPreflightTest {

    @Test
    fun `test the real world environment errors are classified as environment faults`() {
        // Both taken verbatim from a failing SimpleToDo run.
        val stdlib = "EditItemActivity.kt:21:15: error: cannot access built-in declaration " +
            "'kotlin.Unit'. Ensure that you have a dependency on the Kotlin standard library."
        val android = "EditItemActivity.kt:3:8: error: unresolved reference 'android'."

        assertTrue(EnvironmentPreflight.isEnvironmentError(stdlib), "missing stdlib is an environment fault")
        assertTrue(EnvironmentPreflight.isEnvironmentError(android), "missing android.jar is an environment fault")
    }

    @Test
    fun `test genuine code defects are not misread as environment faults`() {
        val codeErrors = listOf(
            "MainActivity.kt:215:20: error: overload resolution ambiguity between candidates:",
            "EditItemActivity.kt:90:9: error: this annotation is not repeatable.",
            "EditItemActivity.kt:74:5: error: 'onOptionsItemSelected' overrides nothing.",
            "EditItemActivity.kt:93:25: error: only safe (?.) or non-null asserted (!!.) calls " +
                "are allowed on a nullable receiver of type 'String?'."
        )
        codeErrors.forEach {
            assertFalse(
                EnvironmentPreflight.isEnvironmentError(it),
                "This is a real code defect the Fixer should see: $it"
            )
        }
    }

    @Test
    fun `test warnings are never treated as environment faults`() {
        val warning = "warning: unable to find kotlin-stdlib.jar in the Kotlin home directory. " +
            "Pass either '-no-stdlib' to prevent adding it to the classpath, or the correct '-kotlin-home'"
        assertFalse(
            EnvironmentPreflight.isEnvironmentError(warning),
            "This warning is emitted on every successful compile and must not halt a run"
        )
    }

    @Test
    fun `test partition splits a mixed compiler log correctly`() {
        val mixed = listOf(
            "A.kt:1:1: error: cannot access built-in declaration 'kotlin.Unit'.",
            "B.kt:2:2: error: unresolved reference 'android'.",
            "C.kt:3:3: error: this annotation is not repeatable.",
            "warning: unable to find kotlin-reflect.jar in the Kotlin home directory"
        )
        val (env, code) = EnvironmentPreflight.partition(mixed)

        assertEquals(2, env.size, "two environment faults expected")
        assertEquals(2, code.size, "the code defect and the warning stay out of the environment bucket")
        assertTrue(code.any { it.contains("not repeatable") })
    }

    @Test
    fun `test preflight passes in this repo because the stdlib really is reachable`() {
        // Not a mock: this compiles a file using Unit, listOf, map and apply.
        val report = EnvironmentPreflight.check(targetProjectPath = "sample-legacy")
        assertTrue(
            report.blockers.none { it.title.contains("standard library") },
            "The Kotlin stdlib must be reachable here. Blockers: ${report.blockers.map { it.title }}"
        )
    }

    @Test
    fun `test android requirement is detected from the sources being migrated`() {
        assertFalse(
            EnvironmentPreflight.requiresAndroid(java.io.File("sample-legacy")),
            "sample-legacy is plain JVM Java and must not demand android.jar"
        )
    }
}
