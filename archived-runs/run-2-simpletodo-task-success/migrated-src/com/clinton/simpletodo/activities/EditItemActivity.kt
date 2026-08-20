package com.clinton.simpletodo.activities

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.clinton.simpletodo.R

/**
 * Modernized by Apollo Agent (Stage 3 Migrator Fallback)
 * Class: EditItemActivity
 */
open class EditItemActivity : AppCompatActivity() {

    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_item)

        editText = findViewById(R.id.editText)
        setupReturnKeyListenerForEditText()
        populateEditText(intent.getStringExtra("editItemText") ?: "")
    }

    fun setupReturnKeyListenerForEditText() {
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSubmit(editText)
                true
            } else {
                false
            }
        }
    }

    fun populateEditText(editItemText: String) {
        editText.setText(editItemText)
    }

    fun onSave(v: View) {
        val resultIntent = Intent()
        resultIntent.putExtra("editItemText", editText.text.toString())
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    fun onCancel(view: View) {
        finish()
    }

    fun onSubmit(v: View) {
        onSave(v)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.edit_item_menu, menu)
        return true
    }

    fun preferredCase(original: String): String =
        original.capitalize()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> {
                onSave(editText)
                return true
            }
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}