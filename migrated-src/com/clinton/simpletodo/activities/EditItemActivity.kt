package com.clinton.simpletodo.activities

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.clinton.simpletodo.R

open class EditItemActivity : AppCompatActivity() {

    private var etEditItem: EditText? = null
    private var editItemIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_item)

        etEditItem = findViewById<EditText>(R.id.etTextToEdit)
        editItemIndex = intent.getIntExtra("itemIndex", 0)

        val editItemText = intent.getStringExtra("todoItem")
        populateEditText(editItemText)
        setupReturnKeyListenerForEditText()
    }

    private fun setupReturnKeyListenerForEditText() {
        etEditItem?.setOnEditorActionListener { _: TextView?, actionId: Int, _: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSubmit(etEditItem)
                true
            } else {
                false
            }
        }
    }

    fun populateEditText(editItemText: String?) {
        val editText = etEditItem ?: return
        editText.setText(editItemText?.orEmpty())
        editText.setSelection(editText.text.length)
    }

    fun onSave(v: View?) {
        val updatedText = etEditItem?.let { preferredCase(it.text.toString()) } ?: ""
        val resultIntent = Intent().apply {
            putExtra("editedItemText", updatedText)
            putExtra("editedItemIndex", editItemIndex)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    fun onCancel(view: View?) {
        setResult(RESULT_CANCELED)
        finish()
    }

    fun onSubmit(v: View?) {
        onSave(v)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_edit_item, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                onSave(null)
                true
            }
            R.id.action_cancel -> {
                onCancel(null)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        @JvmStatic
        fun preferredCase(original: String): String {
            if (original.isEmpty()) {
                return ""
            }
            val firstChar = original[0].uppercaseChar()
            val rest = if (original.length > 1) original.substring(1).lowercase() else ""
            return firstChar + rest
        }
    }
}