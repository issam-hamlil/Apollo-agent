package com.clinton.simpletodo.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.ArrayList

open class TasksDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "TasksDatabaseHelper"

        // Database Info
        private const val DATABASE_NAME = "tasksDatabase.db"
        private const val DATABASE_VERSION = 1
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        //db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        val CREATE_TASKS_TABLE = "CREATE TABLE " + TaskContract.TaskEntry.TABLE_NAME + " (" +
                "id" + " INTEGER PRIMARY KEY, " +
                TaskContract.TaskEntry.COLUMN_NAME_TITLE + " TEXT" +
                ")"

        db.execSQL(CREATE_TASKS_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TaskContract.TaskEntry.TABLE_NAME)
            onCreate(db)
        }
    }

    fun addTask(task: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(TaskContract.TaskEntry.COLUMN_NAME_TITLE, task)
            }
            db.insertOrThrow(TaskContract.TaskEntry.TABLE_NAME, null, values)
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.d(TAG, "Error while trying to add post to database", e)
        } finally {
            db.endTransaction()
        }
    }

    fun addOrUpdateTask(task: Task): Long {
        val db = writableDatabase
        var taskId: Long = -1
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(TaskContract.TaskEntry.COLUMN_NAME_TITLE, task.text)
            }

            val rows = db.update(
                TaskContract.TaskEntry.TABLE_NAME,
                values,
                TaskContract.TaskEntry.COLUMN_NAME_TITLE + " = ?",
                arrayOf(task.text ?: "")
            )

            if (rows == 1) {
                val tasksSelectQuery = "SELECT " + "id" + " FROM " + TaskContract.TaskEntry.TABLE_NAME + " WHERE " + TaskContract.TaskEntry.COLUMN_NAME_TITLE + " = ?"
                val cursor = db.rawQuery(tasksSelectQuery, arrayOf(task.text ?: ""))
                if (cursor.moveToFirst()) {
                    taskId = cursor.getLong(0)
                    db.setTransactionSuccessful()
                }
                cursor.close()
            } else {
                taskId = db.insertOrThrow(TaskContract.TaskEntry.TABLE_NAME, null, values)
                db.setTransactionSuccessful()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error while trying to add or update task", e)
        } finally {
            db.endTransaction()
        }
        return taskId
    }

    fun getAllTasks(): ArrayList<String> {
        val tasks = ArrayList<String>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM " + TaskContract.TaskEntry.TABLE_NAME, null)
        try {
            if (cursor.moveToFirst()) {
                val titleIndex = cursor.getColumnIndexOrThrow(TaskContract.TaskEntry.COLUMN_NAME_TITLE)
                do {
                    val taskText = cursor.getString(titleIndex)
                    if (taskText != null) {
                        tasks.add(taskText)
                    }
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error while trying to get tasks from database", e)
        } finally {
            cursor.close()
        }
        return tasks
    }

    fun deleteAllTasks() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TaskContract.TaskEntry.TABLE_NAME, null, null)
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.d(TAG, "Error while trying to delete all tasks", e)
        } finally {
            db.endTransaction()
        }
    }
}