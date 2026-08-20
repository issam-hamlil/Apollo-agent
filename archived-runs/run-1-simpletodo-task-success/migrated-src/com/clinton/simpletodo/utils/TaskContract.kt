package com.clinton.simpletodo.utils

import android.provider.BaseColumns

object TaskContract {
    /* Inner class that defines the table contents */
    object TaskEntry : BaseColumns {
        const val TABLE_NAME = "tasks"
        const val COLUMN_NAME_TITLE = "task_text"
        const val COLUMN_NAME_DESCRIPTION = "task_description"
        const val COLUMN_NAME_PRIORITY = "task_priority"
        const val COLUMN_NAME_DEADLINE = "task_deadline"
        const val COLUMN_NAME_COMPLETED = "task_completed"
    }
}