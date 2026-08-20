package com.example.legacy

object StringUtils {
    fun isEmpty(str: String?): Boolean = str.isNullOrBlank()

    fun capitalize(str: String?): String {
        if (isEmpty(str)) {
            return str ?: ""
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase()
    }
}