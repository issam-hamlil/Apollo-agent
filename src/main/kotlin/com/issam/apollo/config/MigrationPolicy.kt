package com.issam.apollo.config

/**
 * What the migrated output is allowed to target.
 *
 * This exists so modernisation decisions are driven by one declared floor rather than by
 * per-entry hedging in the knowledge base. If the floor is Android 10 (API 29) then every
 * platform API introduced at or below 29 - java.time (26), VibrationEffect (26),
 * NetworkCapabilities (23/29), Handler(Looper) - is available unconditionally, and the
 * model should be told so instead of being nudged to preserve legacy types "just in case".
 *
 * Dropping support for older devices is a deliberate product decision, not a regression:
 * a migration that must stay compatible with API 19 cannot modernise much of anything.
 */
object MigrationPolicy {

    /** Android 10. Devices below this are explicitly out of scope for migrated output. */
    const val DEFAULT_MIN_SDK = 29

    /**
     * Minimum Android API level the migrated code must run on.
     * Override with `APOLLO_MIN_SDK` in `.env` or the environment.
     */
    val minSdk: Int =
        (System.getenv("APOLLO_MIN_SDK") ?: LlmConfig.rawSetting("APOLLO_MIN_SDK"))
            ?.trim()?.toIntOrNull()
            ?.takeIf { it in 1..99 }
            ?: DEFAULT_MIN_SDK

    /** True when [apiLevel] is guaranteed present on every supported device. */
    fun isAvailable(apiLevel: Int): Boolean = apiLevel <= minSdk

    /** Human-readable Android version for [minSdk], for prompts and reports. */
    val minSdkName: String = when (minSdk) {
        in 34..99 -> "Android 14+"
        33 -> "Android 13"
        32, 31 -> "Android 12"
        30 -> "Android 11"
        29 -> "Android 10"
        28 -> "Android 9"
        in 1..27 -> "Android 8.1 or older"
        else -> "API $minSdk"
    }

    /** The line handed to the model so it knows which platform APIs it may use freely. */
    fun promptDirective(): String =
        "TARGET PLATFORM: the migrated code must run on API $minSdk ($minSdkName) and above. " +
            "Every platform API introduced at or below API $minSdk is available unconditionally - " +
            "use it directly, with no version guards, no desugaring caveats, and no compatibility " +
            "shims. Supporting devices below API $minSdk is explicitly NOT a requirement, so never " +
            "keep a deprecated or legacy type just to stay compatible with an older release."
}
