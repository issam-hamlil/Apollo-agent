package com.issam.apollo.tools

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleDependencyParserTest {

    @Test
    fun `test parse Groovy build gradle dependencies`() {
        val tempFile = File.createTempFile("build", ".gradle").apply {
            writeText(
                """
                apply plugin: 'com.android.application'
                def supportVersion = '24.2.0'
                dependencies {
                    compile fileTree(dir: 'libs', include: ['*.jar'])
                    testCompile 'junit:junit:4.12'
                    compile "com.android.support:appcompat-v7:${'$'}supportVersion"
                    compile 'commons-io:commons-io:2.4'
                }
                """.trimIndent()
            )
            deleteOnExit()
        }

        val deps = GradleDependencyParser.parseBuildFile(tempFile)
        val notations = deps.map { it.notation }

        assertTrue(notations.contains("com.android.support:appcompat-v7:24.2.0"), "Should parse and resolve supportVersion")
        assertTrue(notations.contains("commons-io:commons-io:2.4"), "Should parse commons-io")
        assertTrue(notations.contains("junit:junit:4.12"), "Should parse junit")
    }

    @Test
    fun `test parse Kotlin DSL build gradle kts dependencies`() {
        val tempFile = File.createTempFile("build", ".gradle.kts").apply {
            writeText(
                """
                plugins {
                    id("com.android.application")
                }
                val lifecycleVersion = "2.6.2"
                dependencies {
                    implementation("androidx.appcompat:appcompat:1.7.0")
                    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${'$'}lifecycleVersion")
                    api("com.google.android.material:material:1.11.0")
                }
                """.trimIndent()
            )
            deleteOnExit()
        }

        val deps = GradleDependencyParser.parseBuildFile(tempFile)
        val notations = deps.map { it.notation }

        assertTrue(notations.contains("androidx.appcompat:appcompat:1.7.0"), "Should parse androidx appcompat")
        assertTrue(notations.contains("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2"), "Should parse lifecycle")
        assertTrue(notations.contains("com.google.android.material:material:1.11.0"), "Should parse material")
    }

    @Test
    fun `test parse SimpleToDo project dependencies directly`() {
        val simpleToDoDir = File("D:/Projects/projects-to-test-on/SimpleToDo")
        if (simpleToDoDir.exists()) {
            val deps = GradleDependencyParser.parseProjectDependencies(simpleToDoDir)
            val notations = deps.map { it.notation }

            assertTrue(notations.contains("com.android.support:appcompat-v7:24.2.0"), "SimpleToDo must include appcompat-v7")
            assertTrue(notations.contains("commons-io:commons-io:2.4"), "SimpleToDo must include commons-io")
        }
    }
}
