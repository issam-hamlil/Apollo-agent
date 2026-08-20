package com.clinton.simpletodo.utils

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.ArrayList

class TasksDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "app.db", null, 1) {

    // ── Fields (from Analyzer spec) ──────────────────
    private val TAG = "TasksDatabaseHelper"
    private val DATABASE_NAME = "app.db"
    private val DATABASE_VERSION = 1

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE IF NOT EXISTS tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task TEXT NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS tasks")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Enable foreign key constraints
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun addTask(task: String): Long {
        val values = ContentValues().apply {
            put("task", task)
        }
        return writableDatabase.insert("tasks", null, values)
    }

    fun addOrUpdateTask(task: Task): Long {
        val values = ContentValues().apply {
            put("task", task.task)
        }
        if (task.id > 0) {
            return writableDatabase.update("tasks", values, "id = ?", arrayOf(task.id.toString())).toLong()
        } else {
            return addTask(task.task)
        }
    }

    fun getAllTasks(): ArrayList<String> {
        val tasks = ArrayList<String>()
        val cursor: Cursor? = readableDatabase.query(
            "tasks",
            null,
            null,
            null,
            null,
            null,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                do {
                    tasks.add(it.getString(it.getColumnIndexOrThrow("task")))
                } while (it.moveToNext())
            }
        }
        return tasks
    }

    fun deleteAllTasks() {
        writableDatabase.delete("tasks", null, null)
    }
}