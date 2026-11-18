package com.example.clipkeys

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var store: ClipStore
    private lateinit var groupList: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ClipStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply { text = "ClipKeys"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "1. Enable the keyboard, 2. switch to it, 3. open the Clipboard tab to use groups."
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(Button(this).apply {
            text = "1. Enable ClipKeys"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Switch keyboard"
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        root.addView(EditText(this).apply { hint = "Try typing here" })

        root.addView(TextView(this).apply {
            text = "Clipboard groups"; textSize = 20f; setPadding(0, dp(20), 0, dp(4))
        })
        val newName = EditText(this).apply { hint = "New group name" }
        root.addView(newName)
        root.addView(Button(this).apply {
            text = "Add group"
            setOnClickListener {
                val n = newName.text.toString().trim()
                if (n.isNotEmpty()) { store.addGroup(n); newName.setText(""); renderGroups() }
            }
        })
        groupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(groupList)
        renderGroups()

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun renderGroups() {
        groupList.removeAllViews()
        store.groups().forEach { g ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = g.name; textSize = 16f
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { rename(g) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Delete"
                setOnClickListener { store.deleteGroup(g.id); renderGroups() }
            })
            groupList.addView(row)
        }
    }

    private fun rename(g: Group) {
        val input = EditText(this).apply { setText(g.name) }
        AlertDialog.Builder(this).setTitle("Rename group").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { store.renameGroup(g.id, n); renderGroups() }
            }.setNegativeButton("Cancel", null).show()
    }
}
