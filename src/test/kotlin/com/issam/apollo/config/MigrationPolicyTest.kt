package com.issam.apollo.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationPolicyTest {

    @Test
    fun `test the floor is Android 10 by default`() {
        assertEquals(29, MigrationPolicy.DEFAULT_MIN_SDK)
        assertEquals("Android 10", MigrationPolicy.minSdkName)
    }

    @Test
    fun `test platform APIs at or below the floor count as unconditionally available`() {
        // java.time (26), VibrationEffect (26), Handler(Looper) (all), NetworkCapabilities (23)
        listOf(21, 23, 26, 28, 29).forEach {
            assertTrue(MigrationPolicy.isAvailable(it), "API $it must be usable without guards")
        }
    }

    @Test
    fun `test APIs above the floor still need care`() {
        listOf(30, 31, 33, 34).forEach {
            assertFalse(MigrationPolicy.isAvailable(it), "API $it is above the floor")
        }
    }

    @Test
    fun `test the directive tells the model not to preserve legacy types for old releases`() {
        val d = MigrationPolicy.promptDirective()
        assertTrue(d.contains("API 29"), "the concrete floor must be stated")
        assertTrue(d.contains("Android 10"))
        assertTrue(d.contains("no desugaring"), "desugaring caveats are explicitly ruled out")
        assertTrue(
            d.contains("never") && d.contains("keep a deprecated"),
            "the model must be told not to preserve legacy types for older releases"
        )
    }
}
