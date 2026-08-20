package com.example.legacy

/**
 * Modernized by Apollo Agent (Stage 3 Migrator)
 * Pattern: Utility Class -> Kotlin Object & Extension Functions
 */
object StringUtils {
    fun isEmpty(str: String?): Boolean = str.isNullOrBlank()

    fun capitalize(str: String?): String? {
        if (str.isNullOrEmpty()) return str
        return str.substring(0, 1).uppercase() + str.substring(1).lowercase()
    }
}