package com.lumis

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lumis.project.ProjectEntry
import com.lumis.project.ProjectIo
import com.lumis.project.ProjectStore
import java.io.File

class ProjectsActivity : Activity() {
    private lateinit var store: ProjectStore
    private lateinit var list: LinearLayout
    private lateinit var newButton: Button
    private var dialog: AlertDialog? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(File(filesDir, "projects"))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 11, 16))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(dp(20) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop,
                    dp(20) + insets.systemWindowInsetRight, dp(12) + insets.systemWindowInsetBottom)
                insets
            }
        }
        root.addView(label(getString(R.string.projects_heading), 28))
        root.addView(label(getString(R.string.projects_description), 14))
        newButton = action(getString(R.string.new_project)) { nameDialog(null) }.apply {
            id = R.id.new_project
            background = rounded(Color.rgb(167, 139, 250))
            setTextColor(Color.rgb(29, 20, 55))
        }
        root.addView(newButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(16) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; id = R.id.project_list }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        perform({ store.list() }) { entries ->
            list.removeAllViews()
            if (entries.isEmpty()) list.addView(label(getString(R.string.projects_empty), 18))
            entries.forEach { entry ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = rounded(Color.rgb(20, 24, 33))
                }
                card.addView(label(entry.name, 20))
                if (!entry.problem) card.addView(label(getString(R.string.project_summary,
                    resources.getQuantityString(R.plurals.project_surface_count, entry.surfaceCount, entry.surfaceCount),
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(entry.updatedAt))), 14))
                if (entry.problem) card.addView(label(getString(R.string.project_needs_recovery), 14))
                card.addView(action(getString(R.string.open_project)) { open(entry.id) }.apply {
                    contentDescription = getString(R.string.open_named_project, entry.name)
                })
                card.addView(action(getString(R.string.project_actions)) { actions(entry) }.apply {
                    contentDescription = getString(R.string.actions_named_project, entry.name)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            }
        }
    }

    private fun open(id: String) {
        if (busy) return
        startActivity(Intent(this, MainActivity::class.java).putExtra("project_id", id))
        finish()
    }

    private fun actions(entry: ProjectEntry) {
        dialog = AlertDialog.Builder(this).setTitle(entry.name)
            .setItems(arrayOf(getString(R.string.rename_project), getString(R.string.duplicate_project), getString(R.string.delete_project))) { _, which ->
                when (which) {
                    0 -> nameDialog(entry)
                    1 -> perform({ store.duplicate(entry.id) }) { refresh() }
                    2 -> {
                        dialog = AlertDialog.Builder(this).setTitle(getString(R.string.delete_named_project, entry.name))
                            .setMessage(R.string.delete_project_message)
                            .setNegativeButton(R.string.keep_project, null)
                            .setPositiveButton(R.string.delete_project) { _, _ -> perform({ store.delete(entry.id) }) { refresh() } }
                            .show().also { it.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(251, 113, 133)) }
                    }
                }
            }.setNegativeButton(R.string.done, null).show()
    }

    private fun nameDialog(entry: ProjectEntry?) {
        val input = EditText(this).apply {
            id = R.id.project_name
            hint = getString(R.string.project_name)
            contentDescription = hint
            isSingleLine = true
            setText(entry?.name ?: "")
            filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            setSelectAllOnFocus(true)
        }
        val panel = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(if (entry == null) R.string.new_project else R.string.rename_project)
            .setView(panel).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (entry == null) R.string.create_project else R.string.save_project, null)
            .show().also { alert ->
                alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        input.error = getString(R.string.project_name_required)
                    } else if (!busy) {
                        alert.dismiss()
                        if (entry == null) perform({ store.create(name) }) { open(it.id) }
                        else perform({ store.rename(entry.id, name) }) { refresh() }
                    }
                }
            }
    }

    private fun <T> perform(operation: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true
        newButton.isEnabled = false
        list.alpha = .5f
        ProjectIo.run(operation) { result ->
            if (isFinishing || isDestroyed) return@run
            busy = false
            newButton.isEnabled = true
            list.alpha = 1f
            result.fold(success) { error ->
                dialog = AlertDialog.Builder(this).setTitle(R.string.project_operation_failed)
                    .setMessage(error.localizedMessage)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.retry) { _, _ -> perform(operation, success) }.show()
            }
        }
    }

    private fun label(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(244, 245, 250))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun action(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        minHeight = dp(48)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(167, 139, 250))
        background = rounded(Color.rgb(28, 34, 48))
        setOnClickListener { if (!busy) action() }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
