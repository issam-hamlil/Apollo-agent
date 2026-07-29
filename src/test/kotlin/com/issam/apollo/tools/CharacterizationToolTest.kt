package com.issam.apollo.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterizationToolTest {

    private val tool = CharacterizationTool()
    private val sampleLegacyDir = File("sample-legacy")

    @Test
    fun `test executeCharacterization compiles sample-legacy and generates test cases`() {
        assertTrue(sampleLegacyDir.exists(), "sample-legacy directory must exist")

        val tempReportsDir = File("build/test-char-reports-${System.currentTimeMillis()}")
        val testCases = tool.executeCharacterization(sampleLegacyDir, tempReportsDir)

        assertTrue(testCases.isNotEmpty(), "Test cases should be captured")

        // Check per-module test coverage
        val stringUtilsCases = testCases.filter { it.className == "StringUtils" }
        val userCases = testCases.filter { it.className == "User" }
        val userServiceCases = testCases.filter { it.className == "UserService" }

        assertTrue(stringUtilsCases.isNotEmpty(), "StringUtils test cases must be generated")
        assertTrue(userCases.isNotEmpty(), "User test cases must be generated")
        assertTrue(userServiceCases.isNotEmpty(), "UserService test cases must be generated")

        // Verify captured ground truth values for StringUtils.isEmpty
        val isEmptyNullCase = stringUtilsCases.find { it.methodName == "isEmpty" && it.inputs.contains("null") }
        if (isEmptyNullCase != null) {
            assertEquals("true", isEmptyNullCase.expectedOutput, "isEmpty(null) must return 'true'")
        }

        val isEmptyHelloCase = stringUtilsCases.find { it.methodName == "isEmpty" && it.inputs.contains("hello") }
        if (isEmptyHelloCase != null) {
            assertEquals("false", isEmptyHelloCase.expectedOutput, "isEmpty('hello') must return 'false'")
        }

        // Verify ground truth JSON report files generated
        val charDir = File(tempReportsDir, "characterization")
        assertTrue(charDir.exists(), "reports/characterization directory must be created")

        val stringUtilsJson = File(charDir, "StringUtils-ground-truth.json")
        val userJson = File(charDir, "User-ground-truth.json")
        val userServiceJson = File(charDir, "UserService-ground-truth.json")
        val summaryMd = File(charDir, "characterization-summary.md")

        assertTrue(stringUtilsJson.exists(), "StringUtils-ground-truth.json must exist")
        assertTrue(userJson.exists(), "User-ground-truth.json must exist")
        assertTrue(userServiceJson.exists(), "UserService-ground-truth.json must exist")
        assertTrue(summaryMd.exists(), "characterization-summary.md must exist")

        // Cleanup
        tempReportsDir.deleteRecursively()
    }
}
