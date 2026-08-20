package com.issam.apollo.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaAstToolTest {

    private val astTool = JavaAstTool()
    private val sampleLegacyDir = File("sample-legacy")

    @Test
    fun `test analyzeRepo parses all java files and extracts specs`() {
        assertTrue(sampleLegacyDir.exists(), "sample-legacy directory must exist")

        val result = astTool.analyzeRepo(sampleLegacyDir)

        assertTrue(result.specs.size >= 4, "Should parse at least 4 legacy Java files")
        assertTrue(result.specs.containsKey("StringUtils"))
        assertTrue(result.specs.containsKey("User"))
        assertTrue(result.specs.containsKey("UserService"))
        assertTrue(result.specs.containsKey("TrickyMath"))
    }

    @Test
    fun `test dependency graph detection`() {
        val result = astTool.analyzeRepo(sampleLegacyDir)

        val userServiceDeps = result.dependencyGraph["UserService"] ?: emptyList()
        assertTrue(userServiceDeps.contains("User"), "UserService should depend on User")

        val stringUtilsDeps = result.dependencyGraph["StringUtils"] ?: emptyList()
        assertTrue(stringUtilsDeps.isEmpty(), "StringUtils should have 0 dependencies")
    }

    @Test
    fun `test topological sort places dependencies before consumers`() {
        val result = astTool.analyzeRepo(sampleLegacyDir)
        val order = result.topologicalOrder

        assertTrue(order.size >= 4, "Topological order should contain at least 4 modules")

        val userIndex = order.indexOf("User")
        val userServiceIndex = order.indexOf("UserService")

        assertTrue(userIndex >= 0 && userServiceIndex >= 0, "Both User and UserService should be in order")
        assertTrue(userIndex < userServiceIndex, "User must appear before UserService in topological order")
    }
}
