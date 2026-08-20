package com.issam.apollo.tools

import java.io.File

/**
 * Utility for resolving Android SDK tools (aapt2, android.jar) across platforms (Windows, Linux, macOS).
 */
object AndroidSdkResolver {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val aapt2ExecutableName = if (isWindows) "aapt2.exe" else "aapt2"

    val defaultStubJarPath: File = File("").absoluteFile.resolve("libs/android-stubs/android.jar")

    /**
     * Resolves the AAPT2 binary location by checking:
     * 1. ANDROID_BUILD_TOOLS_PATH environment variable (direct executable or build-tools version directory)
     * 2. ANDROID_HOME / ANDROID_SDK_ROOT environment variables -> build-tools/<version>/aapt2
     * 3. Standard platform default SDK installation directories
     * 4. System PATH
     */
    fun findAapt2Binary(): File? {
        // 1. Explicit env var
        val customBuildTools = System.getenv("ANDROID_BUILD_TOOLS_PATH")
        if (!customBuildTools.isNullOrBlank()) {
            val customFile = File(customBuildTools)
            if (customFile.isFile && customFile.name.startsWith("aapt2", ignoreCase = true) && customFile.exists()) {
                return customFile
            }
            if (customFile.isDirectory) {
                val candidate = customFile.resolve(aapt2ExecutableName)
                if (candidate.exists()) return candidate
            }
        }

        // 2. Candidate SDK root directories
        val sdkRoots = mutableListOf<File>()
        System.getenv("ANDROID_HOME")?.let { sdkRoots.add(File(it)) }
        System.getenv("ANDROID_SDK_ROOT")?.let { sdkRoots.add(File(it)) }

        // Standard OS locations
        val userHome = System.getProperty("user.home", "")
        if (isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA")
            if (!localAppData.isNullOrBlank()) {
                sdkRoots.add(File(localAppData, "Android/Sdk"))
            }
            if (userHome.isNotBlank()) {
                sdkRoots.add(File(userHome, "AppData/Local/Android/Sdk"))
            }
        } else {
            // Linux / macOS
            if (userHome.isNotBlank()) {
                sdkRoots.add(File(userHome, "Android/Sdk"))
                sdkRoots.add(File(userHome, "Library/Android/sdk"))
            }
            sdkRoots.add(File("/usr/lib/android-sdk"))
            sdkRoots.add(File("/opt/android-sdk"))
        }

        for (sdkRoot in sdkRoots.filter { it.exists() && it.isDirectory }) {
            val buildToolsDir = sdkRoot.resolve("build-tools")
            if (buildToolsDir.exists() && buildToolsDir.isDirectory) {
                val versionDirs = buildToolsDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedWith(compareByDescending { parseVersionKey(it.name) })
                    ?: emptyList()

                for (verDir in versionDirs) {
                    val candidate = verDir.resolve(aapt2ExecutableName)
                    if (candidate.exists() && (isWindows || candidate.canExecute())) {
                        return candidate
                    }
                }
            }
        }

        // 3. Search system PATH
        val pathVar = System.getenv("PATH") ?: ""
        for (dir in pathVar.split(File.pathSeparator).filter { it.isNotBlank() }) {
            val candidate = File(dir, aapt2ExecutableName)
            if (candidate.exists() && (isWindows || candidate.canExecute())) {
                return candidate
            }
        }

        return null
    }

    /**
     * Requires AAPT2 binary or throws a descriptive, user-friendly exception.
     */
    fun requireAapt2Binary(): File {
        val aapt2 = findAapt2Binary()
        if (aapt2 != null && aapt2.exists()) {
            return aapt2
        }

        val osName = System.getProperty("os.name")
        val examplePath = if (isWindows) {
            "C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk\\build-tools\\34.0.0\\aapt2.exe"
        } else {
            "/home/<user>/Android/Sdk/build-tools/34.0.0/aapt2"
        }

        throw IllegalStateException(
            "\n" +
            "====================================================================\n" +
            "  Apollo: AAPT2 binary not found!\n" +
            "  Operating System: $osName\n" +
            "\n" +
            "  Apollo requires AAPT2 from the Android SDK build-tools to generate\n" +
            "  the R class and link Android resources.\n" +
            "\n" +
            "  To fix this:\n" +
            "  1. Install Android SDK Build-Tools (e.g. 34.0.0 or higher) via\n" +
            "     Android Studio (Tools > SDK Manager > SDK Tools > Android SDK Build-Tools)\n" +
            "     or using the command-line: sdkmanager \"build-tools;34.0.0\"\n" +
            "  2. Set your ANDROID_HOME environment variable to your Android SDK path,\n" +
            "     or set ANDROID_BUILD_TOOLS_PATH directly to the build-tools directory or aapt2 executable.\n" +
            "     Example expected path: $examplePath\n" +
            "===================================================================="
        )
    }

    /**
     * Resolves the Android SDK stub jar (android.jar) or returns null if not found.
     */
    fun findAndroidJar(customStubPath: File? = null): File? {
        val stub = customStubPath ?: defaultStubJarPath
        if (stub.exists()) {
            return stub
        }

        // Search ANDROID_HOME/platforms/android-*/android.jar
        val sdkRoots = mutableListOf<File>()
        System.getenv("ANDROID_HOME")?.let { sdkRoots.add(File(it)) }
        System.getenv("ANDROID_SDK_ROOT")?.let { sdkRoots.add(File(it)) }

        val userHome = System.getProperty("user.home", "")
        if (isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA")
            if (!localAppData.isNullOrBlank()) sdkRoots.add(File(localAppData, "Android/Sdk"))
            if (userHome.isNotBlank()) sdkRoots.add(File(userHome, "AppData/Local/Android/Sdk"))
        } else {
            if (userHome.isNotBlank()) {
                sdkRoots.add(File(userHome, "Android/Sdk"))
                sdkRoots.add(File(userHome, "Library/Android/sdk"))
            }
            sdkRoots.add(File("/usr/lib/android-sdk"))
            sdkRoots.add(File("/opt/android-sdk"))
        }

        for (sdkRoot in sdkRoots.filter { it.exists() && it.isDirectory }) {
            val platforms = sdkRoot.resolve("platforms")
            if (platforms.exists() && platforms.isDirectory) {
                val candidate = platforms.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("android-") }
                    ?.sortedByDescending { it.name.removePrefix("android-").toIntOrNull() ?: 0 }
                    ?.map { it.resolve("android.jar") }
                    ?.firstOrNull { it.exists() }
                if (candidate != null) return candidate
            }
        }

        return null
    }

    /**
     * Resolves the Android SDK stub jar (android.jar) or throws a user-friendly error.
     */
    fun requireAndroidStubJar(customStubPath: File? = null): File {
        val jar = findAndroidJar(customStubPath)
        if (jar != null && jar.exists()) {
            return jar
        }

        val stub = customStubPath ?: defaultStubJarPath
        throw IllegalStateException(
            "\n" +
            "====================================================================\n" +
            "  Apollo: android.jar stub not found!\n" +
            "  Expected: ${stub.absolutePath}\n" +
            "\n" +
            "  To fix this:\n" +
            "  1. Locate your Android SDK: \$ANDROID_HOME (or check Android Studio\n" +
            "     > SDK Manager for the SDK path).\n" +
            "  2. Copy the stub jar for API 34 (or another recent level):\n" +
            "       \$ANDROID_HOME/platforms/android-34/android.jar\n" +
            "  3. Place it at: ${stub.absolutePath}\n" +
            "\n" +
            "  The jar is used only for compilation — Apollo does NOT execute\n" +
            "  Android framework code at runtime.\n" +
            "===================================================================="
        )
    }

    /**
     * Helper to sort semver-like version directory names (e.g. "35.0.1" > "34.0.0" > "23.0.3").
     */
    private fun parseVersionKey(versionStr: String): String {
        return versionStr.split(".", "-")
            .mapNotNull { it.toIntOrNull() }
            .joinToString(".") { "%05d".format(it) }
    }
}
