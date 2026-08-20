package com.issam.apollo.tools

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidSdkResolverTest {

    @Test
    fun `test findAapt2Binary resolves valid executable on development host`() {
        val aapt2 = AndroidSdkResolver.findAapt2Binary()
        // If Android SDK is installed locally, verify it resolves
        if (aapt2 != null) {
            assertTrue(aapt2.exists(), "Resolved aapt2 must exist: ${aapt2.absolutePath}")
            val name = aapt2.name.lowercase()
            assertTrue(name.startsWith("aapt2"), "File name should start with aapt2: $name")
        }
    }

    @Test
    fun `test requireAndroidStubJar resolves default stub jar`() {
        val stub = AndroidSdkResolver.requireAndroidStubJar()
        assertTrue(stub.exists(), "android.jar stub must exist at ${stub.absolutePath}")
    }
}
