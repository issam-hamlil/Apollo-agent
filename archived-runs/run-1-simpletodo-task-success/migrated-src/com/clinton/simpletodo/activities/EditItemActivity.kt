package com.clinton.simpletodo.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_edit_item.*

class EditItemActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_item)
        populateEditText()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.edit_item_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> onSave()
            R.id.action_cancel -> onCancel()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun populateEditText() {
        // Implement logic to populate EditText fields
    }

    private fun onCancel() {
        // Implement cancel logic
    }

    private fun onSave() {
        // Implement save logic
    }

    private fun preferredCase(input: String?): String? {
        return input?.trim()?.takeIf { it.isNotEmpty() } ?: ""
    }
}