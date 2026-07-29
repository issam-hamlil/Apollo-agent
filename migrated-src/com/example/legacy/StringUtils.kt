package com.example.legacy

object StringUtils {
    fun isEmpty(str: String?): Boolean = str.isNullOrBlank()

    fun capitalize(str: String?): String? = str?.let { 
        if (isEmpty(it)) it else it.substring(0, 1).uppercase() + it.substring(1).lowercase()
    }
}