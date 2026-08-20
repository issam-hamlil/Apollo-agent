package com.issam.apollo.agents

import com.issam.apollo.state.TestCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 2 (CharacterizationTool) and Stage 4 (VerifierAgent) must classify an
 * un-buildable test fixture identically. When they diverge, the comparison fails by
 * construction no matter how good the migrated Kotlin is - and the migrated code is
 * never actually executed, so the Fixer is handed an impossible goal.
 *
 * These fixtures reproduce the two shapes that dominated the SimpleToDo run:
 *   - an Android-stub class whose constructor throws RuntimeException("Stub!")
 *   - a class with no zero-arg constructor that cannot be built with default arguments
 */
class VerifierHarnessSymmetryTest {

    /** Mimics an android.jar stub Activity: has a zero-arg ctor, but it throws "Stub!". */
    class StubActivityFixture {
        init {
            throw RuntimeException("Stub!")
        }

        fun onCreateOptionsMenu(): String = "never reached"
    }

    /** Stands in for android.content.Context - a reference type with no usable default. */
    class FakeContext

    /** Mimics TasksDatabaseHelper: only ctor needs a Context, and rejects the null default. */
    class ContextRequiringFixture(ctx: FakeContext?) {
        init {
            requireNotNull(ctx) { "context required" }
        }

        fun onCreate(): String = "never reached"
    }

    private val verifier = VerifierAgent()

    @Test
    fun `test an Android stub constructor yields the STUB sentinel instead of a null-receiver NPE`() {
        val testCase = TestCase(
            testId = "TC-1",
            className = "StubActivityFixture",
            methodName = "onCreateOptionsMenu",
            inputs = emptyList(),
            expectedOutput = "STUB!: StubActivityFixture.onCreateOptionsMenu"
        )

        val result = verifier.runTestCaseOnKotlinClass(
            StubActivityFixture::class.java,
            verifier.getOrInstanceObject(StubActivityFixture::class.java),
            testCase
        )

        assertEquals(
            "STUB!: StubActivityFixture.onCreateOptionsMenu",
            result.actualOutput,
            "Stage 4 must emit the same STUB! sentinel Stage 2 records, not NullPointerException: null"
        )
        assertTrue(result.passed, "A stub on both sides is behavioural equivalence and must PASS")
    }

    @Test
    fun `test an unbuildable fixture reports the constructor failure Stage 2 recorded`() {
        val testCase = TestCase(
            testId = "TC-2",
            className = "ContextRequiringFixture",
            methodName = "onCreate",
            inputs = emptyList(),
            expectedOutput = "EXCEPTION: NoSuchMethodException: " +
                ContextRequiringFixture::class.java.name + ".<init>()"
        )

        val result = verifier.runTestCaseOnKotlinClass(
            ContextRequiringFixture::class.java,
            verifier.getOrInstanceObject(ContextRequiringFixture::class.java),
            testCase
        )

        assertTrue(
            result.actualOutput.startsWith("EXCEPTION: NoSuchMethodException"),
            "Stage 4 must surface the constructor lookup failure like Stage 2 did, " +
                "not invoke(null, ...) -> NullPointerException. Got: ${result.actualOutput}"
        )
        assertTrue(
            result.passed,
            "Both stages agree the fixture cannot be built, so the case must PASS. Got: ${result.actualOutput}"
        )
    }

    @Test
    fun `test a normal class is still constructed and executed for real`() {
        val testCase = TestCase(
            testId = "TC-3",
            className = "Greeter",
            methodName = "greet",
            inputs = emptyList(),
            expectedOutput = "hello"
        )

        val result = verifier.runTestCaseOnKotlinClass(
            Greeter::class.java,
            verifier.getOrInstanceObject(Greeter::class.java),
            testCase
        )

        assertEquals("hello", result.actualOutput, "A buildable class must still be executed normally")
        assertTrue(result.passed)
    }

    class Greeter {
        fun greet(): String = "hello"
    }
}
