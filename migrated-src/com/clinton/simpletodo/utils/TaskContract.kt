package com.clinton.simpletodo.utils

import android.provider.BaseColumns

object TaskContract {
    /* Inner class that defines the table contents */
    object TaskEntry : BaseColumns {
        const val TABLE_NAME: String = "tasks"
        const val COLUMN_NAME_TITLE: String = "task_text"
    }
}