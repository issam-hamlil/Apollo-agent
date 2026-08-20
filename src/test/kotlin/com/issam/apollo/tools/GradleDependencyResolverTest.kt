package com.issam.apollo.tools

import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradleDependencyResolverTest {

    @Test
    fun `test resolve and extract real dependencies with caching`() {
        val testDeps = listOf(
            GradleDependency("com.android.support", "appcompat-v7", "24.2.0"),
            GradleDependency("commons-io", "commons-io", "2.4")
        )

        val tempCacheDir = File("build/test-cache-deps").apply {
            deleteRecursively()
            mkdirs()
        }

        // 1. First resolution: downloads and extracts
        val jars = GradleDependencyResolver.resolveAndExtract(testDeps, tempCacheDir)
        assertTrue(jars.isNotEmpty(), "Should extract resolved jars")

        val appcompatJar = jars.find { it.name.contains("appcompat-v7") && it.name.endsWith(".jar") }
        assertNotNull(appcompatJar, "Must have extracted appcompat-v7 jar")
        assertTrue(appcompatJar.exists(), "Extracted appcompat jar must exist on disk")

        // Spot-check: Verify AppCompatActivity.class exists inside the extracted jar
        var foundAppCompatActivity = false
        ZipFile(appcompatJar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.contains("AppCompatActivity.class")) {
                    foundAppCompatActivity = true
                    break
                }
            }
        }
        assertTrue(foundAppCompatActivity, "Extracted appcompat jar must contain AppCompatActivity.class")

        val commonsIoJar = jars.find { it.name.contains("commons-io") }
        assertNotNull(commonsIoJar, "Must include commons-io jar")

        // 2. Second resolution: must hit cache and return exact same files
        val cachedJars = GradleDependencyResolver.resolveAndExtract(testDeps, tempCacheDir)
        assertTrue(cachedJars.isNotEmpty(), "Cached call should return jars")
        assertTrue(cachedJars.map { it.name }.containsAll(jars.map { it.name }), "Cache must return all jars")
    }
}
