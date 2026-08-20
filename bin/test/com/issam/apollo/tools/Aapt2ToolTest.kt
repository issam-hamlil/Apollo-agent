package com.issam.apollo.tools

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Aapt2ToolTest {

    @Test
    fun `test AAPT2 generates genuine R java for SimpleToDo project`() {
        val simpleToDoDir = File("D:/Projects/projects-to-test-on/SimpleToDo/app/src/main/java")
        if (!simpleToDoDir.exists()) return

        val resDir = Aapt2Tool.findResDirectory(simpleToDoDir)
        assertNotNull(resDir, "Should find res directory")
        assertTrue(resDir.exists(), "res directory must exist")

        val manifestFile = Aapt2Tool.findManifestFile(simpleToDoDir)
        assertNotNull(manifestFile, "Should find AndroidManifest.xml")
        assertTrue(manifestFile.exists(), "AndroidManifest.xml must exist")

        val pkg = Aapt2Tool.extractPackageName(manifestFile, simpleToDoDir)
        assertEquals("com.clinton.simpletodo", pkg, "Package name should match manifest")

        val tempOutputDir = File("build/test-aapt2-unit")
        val result = Aapt2Tool.generateRForProject(simpleToDoDir, tempOutputDir)

        assertTrue(result.success, "AAPT2 execution must succeed. Error: ${result.errorMessage}")
        assertTrue(result.rJavaFiles.isNotEmpty(), "Must generate R.java")

        val rJava = result.rJavaFiles.first()
        val rContent = rJava.readText()

        // Verify key resource IDs and classes from SimpleToDo XMLs
        assertTrue(rContent.contains("package com.clinton.simpletodo;"), "Must declare correct package")
        assertTrue(rContent.contains("class layout"), "Must declare layout inner class")
        assertTrue(rContent.contains("activity_main"), "Must declare activity_main layout")
        assertTrue(rContent.contains("activity_edit_item"), "Must declare activity_edit_item layout")
        assertTrue(rContent.contains("class id"), "Must declare id inner class")
        assertTrue(rContent.contains("lvItems"), "Must declare lvItems ID")
        assertTrue(rContent.contains("etEditText"), "Must declare etEditText ID")
        assertTrue(rContent.contains("class menu"), "Must declare menu inner class")
        assertTrue(rContent.contains("menu_main"), "Must declare menu_main")
        assertTrue(rContent.contains("class string"), "Must declare string inner class")
        assertTrue(rContent.contains("app_name"), "Must declare app_name string")
    }

    @Test
    fun `test graceful degradation when resources are missing`() {
        val tempEmptyDir = File("build/test-empty-project").apply {
            deleteRecursively()
            mkdirs()
        }

        val result = Aapt2Tool.generateRForProject(tempEmptyDir)
        assertFalse(result.success, "Should gracefully fail without crash when no res/ found")
        assertNotNull(result.errorMessage, "Should provide descriptive error message")
    }
}
