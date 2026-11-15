package com.mremotedroid.app.remote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.mremotedroid.app.R

/** Dialogs and the key row shared by the SSH and VNC windows. */
object SessionUi {

    /** Shows [prompt] (dismissing [previous]) and returns the new dialog, if any. */
    fun showPrompt(activity: Activity, session: RemoteSession, prompt: Prompt?, previous: AlertDialog?): AlertDialog? {
        previous?.dismiss()
        return when (prompt) {
            null -> null
            is Prompt.HostKey -> AlertDialog.Builder(activity)
                .setTitle(if (prompt.changed) R.string.hostkey_changed_title else R.string.hostkey_new_title)
                .setMessage(
                    activity.getString(
                        if (prompt.changed) R.string.hostkey_changed_message else R.string.hostkey_new_message,
                        prompt.host, prompt.type, prompt.fingerprint
                    )
                )
                .setCancelable(false)
                .setPositiveButton(R.string.hostkey_trust) { _, _ -> session.answer(true) }
                .setNegativeButton(R.string.action_cancel) { _, _ -> session.answer(false) }
                .show()
            is Prompt.Secret -> {
                val field = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = activity.getString(R.string.field_password)
                }
                val box = LinearLayout(activity).apply {
                    setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
                    addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                AlertDialog.Builder(activity)
                    .setTitle(prompt.label?.ifBlank { null } ?: activity.getString(R.string.field_password))
                    .setView(box)
                    .setCancelable(false)
                    .setPositiveButton(R.string.action_ok) { _, _ -> session.answer(field.text.toString()) }
                    .setNegativeButton(R.string.action_cancel) { _, _ -> session.answer(null) }
                    .show()
            }
        }
    }

    /** Back in a session: disconnect, or keep it running and go to the list. */
    fun confirmDisconnect(activity: Activity, running: Boolean, onDisconnect: () -> Unit) {
        if (!running) {
            onDisconnect()
            return
        }
        AlertDialog.Builder(activity)
            .setMessage(R.string.disconnect_confirm)
            .setPositiveButton(R.string.action_disconnect) { _, _ -> onDisconnect() }
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.action_to_list) { _, _ -> activity.moveTaskToBack(true) }
            .show()
    }

    /** A horizontally scrolling row of key buttons; [add] adds keys with `key(label) { }`. */
    fun keyRow(activity: Activity, add: KeyRow.() -> Unit): View {
        val row = KeyRow(activity)
        row.add()
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF202124.toInt())
            addView(row.row)
        }
    }

    class KeyRow(private val activity: Activity) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        fun key(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
            text = label
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            minWidth = dp(activity, 46)
            setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
            setOnClickListener { onClick() }
            this@KeyRow.row.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    fun setToggled(key: TextView, on: Boolean) {
        key.setBackgroundColor(if (on) 0xFF1A73E8.toInt() else Color.TRANSPARENT)
    }

    fun dp(activity: Activity, v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
