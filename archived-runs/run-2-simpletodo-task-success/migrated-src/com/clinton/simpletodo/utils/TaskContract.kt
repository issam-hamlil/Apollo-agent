package com.clinton.simpletodo.utils

import android.provider.BaseColumns

object TaskContract {
    /* Inner class that defines the table contents */
    object TaskEntry : BaseColumns {
        const val TABLE_NAME = "tasks"
        const val COLUMN_NAME_TITLE = "task_text"
        override val _ID: String get() = "_id" // Ensure _ID is defined to match BaseColumns
    }
}