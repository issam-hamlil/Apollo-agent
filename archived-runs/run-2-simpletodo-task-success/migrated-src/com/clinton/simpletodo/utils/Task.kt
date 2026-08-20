package com.clinton.simpletodo.utils

data class Task(var id: Long = 0, var text: String? = null) : TaskContract {
    override val _ID: Long get() = id
}