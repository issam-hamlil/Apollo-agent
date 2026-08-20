package com.issam.apollo.tools

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class AndroidFullPipelineTest {

    @Test
    fun `test end to end Android compilation with real AAPT2 R and resolved dependencies`() {
        val simpleToDoDir = File("D:/Projects/projects-to-test-on/SimpleToDo/app/src/main/java")
        if (!simpleToDoDir.exists()) {
            println("SimpleToDo directory not found at $simpleToDoDir, skipping test.")
            return
        }

        // 1. Dependency Resolution
        val resolvedJars = GradleDependencyResolver.resolveProjectDependencies(simpleToDoDir)
        assertTrue(resolvedJars.isNotEmpty(), "Must resolve dependencies (appcompat, support libs, commons-io)")
        assertTrue(resolvedJars.any { it.name.contains("appcompat") }, "Must include appcompat jar")
        assertTrue(resolvedJars.any { it.name.contains("commons-io") }, "Must include commons-io jar")

        // 2. AAPT2 R.java generation
        val aaptResult = Aapt2Tool.generateRForProject(simpleToDoDir)
        assertTrue(aaptResult.success, "AAPT2 must succeed generating R.java: ${aaptResult.errorMessage}")
        assertTrue(aaptResult.rJavaFiles.isNotEmpty(), "Must have generated R.java files")

        // 3. Test Kotlin Compilation of an Android Activity referencing AppCompatActivity and R resources
        val kotlinTestCode = """
            package com.clinton.simpletodo

            import android.os.Bundle
            import android.support.v7.app.AppCompatActivity
            import android.widget.ListView
            import org.apache.commons.io.FileUtils
            import java.io.File

            class TestMainActivity : AppCompatActivity() {
                private var itemsListView: ListView? = null

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                    itemsListView = findViewById(R.id.lvItems) as ListView
                }

                fun readLinesSafely(file: File): List<String> {
                    return FileUtils.readLines(file)
                }
            }
        """.trimIndent()

        val compileTool = KotlinCompileTool()
        val result = compileTool.compileKotlinFiles(
            mapOf("TestMainActivity.kt" to kotlinTestCode),
            tempDir = File("build/test-e2e-kotlin-sandbox")
        )

        if (!result.compiledSuccessfully) {
            println("Compilation errors: ${result.compilerErrors.joinToString("\n")}")
        }
        assertTrue(result.compiledSuccessfully, "Kotlin Android Activity using real AndroidX and AAPT2 R must compile successfully!")
    }
}
