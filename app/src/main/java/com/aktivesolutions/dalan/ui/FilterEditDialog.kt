package com.aktivesolutions.dalan.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.FilterRule
import com.aktivesolutions.dalan.data.Filters
import com.aktivesolutions.dalan.data.Scope
import com.aktivesolutions.dalan.data.parseTerms
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID

/** Add / edit dialog for one [FilterRule]. */
object FilterEditDialog {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * @param existing the rule to edit, or null to create one
     * @param prefill text for the name and words of a new rule (e.g. a video title); selected so it can be trimmed
     * @param onSaved called after the rule was saved
     */
    fun show(context: Context, existing: FilterRule?, prefill: String? = null, onSaved: () -> Unit) {
        val pad = dp(context, 16)
        val text1 = context.themeColor(R.attr.dalanTextPrimary)
        val text2 = context.themeColor(R.attr.dalanTextSecondary)
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, dp(context, 8), pad, 0)

        fun label(res: Int) = TextView(context).apply {
            setText(res); setTextColor(text2); textSize = 12f; setPadding(0, dp(context, 12), 0, 0)
        }

        val name = EditText(context)
        name.setHint(R.string.filter_name_hint)
        name.setSingleLine()
        name.setText(existing?.name ?: prefill ?: "")
        val terms = EditText(context)
        terms.hint = context.getString(R.string.filter_terms_hint)
        terms.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        terms.minLines = 3
        terms.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        terms.setText(existing?.terms?.joinToString("\n") ?: prefill ?: "")

        val scopes = listOf(Scope.BOTH, Scope.TITLE, Scope.CHANNEL)
        val scopeLabels = listOf(R.string.filter_scope_both, R.string.filter_scope_title, R.string.filter_scope_channel).map { context.getString(it) }
        val scopeSpinner = Spinner(context)
        scopeSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, scopeLabels)
        scopeSpinner.setSelection(scopes.indexOf(existing?.scope ?: Scope.BOTH).coerceAtLeast(0))

        // Expiry: 0 never, 1 week, 2 month, 3 three months, 4 pick a date, 5 (only when a date is set) "Until <date>".
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        var customDate: Long? = existing?.expiresAt
        val expiryItems = ArrayList<String>()
        fun fillExpiry() {
            expiryItems.clear()
            expiryItems.add(context.getString(R.string.filter_expiry_never))
            expiryItems.add(context.getString(R.string.filter_expiry_week))
            expiryItems.add(context.getString(R.string.filter_expiry_month))
            expiryItems.add(context.getString(R.string.filter_expiry_3months))
            expiryItems.add(context.getString(R.string.filter_expiry_pick))
            customDate?.let {
                val res = if (it <= System.currentTimeMillis()) R.string.filter_expiry_expired_on else R.string.filter_expiry_until
                expiryItems.add(context.getString(res, df.format(Date(it))))
            }
        }
        fillExpiry()
        val expiryAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, expiryItems)
        val expirySpinner = Spinner(context)
        expirySpinner.adapter = expiryAdapter
        var lastSel = if (customDate != null) 5 else 0
        expirySpinner.setSelection(lastSel)
        expirySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != 4) { lastSel = position; return }
                val c = Calendar.getInstance().apply { timeInMillis = customDate ?: (System.currentTimeMillis() + 7 * DAY_MS) }
                val dp = DatePickerDialog(context, { _, y, m, d ->
                    val end = Calendar.getInstance().apply { set(y, m, d, 23, 59, 59); set(Calendar.MILLISECOND, 0) }
                    customDate = end.timeInMillis
                    fillExpiry(); expiryAdapter.notifyDataSetChanged()
                    lastSel = 5; expirySpinner.setSelection(5)
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
                dp.datePicker.minDate = System.currentTimeMillis()
                dp.setOnCancelListener { expirySpinner.setSelection(lastSel) }
                dp.show()
            }
        }

        // Blocked channels (only a rule that has some): each can be removed here.
        val channels = LinkedHashMap<String, String>()
        existing?.channelIds?.let { channels.putAll(it) }
        val channelBox = LinearLayout(context)
        channelBox.orientation = LinearLayout.VERTICAL
        fun fillChannels() {
            channelBox.removeAllViews()
            for ((id, nm) in channels.entries.toList()) {
                val row = LinearLayout(context)
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                val t = TextView(context).apply { text = nm; setTextColor(text1); textSize = 14f }
                row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val b = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    setText(R.string.filter_remove_channel); isAllCaps = false
                    setOnClickListener { channels.remove(id); fillChannels() }
                }
                row.addView(b)
                channelBox.addView(row)
            }
        }
        fillChannels()

        box.addView(name)
        box.addView(terms)
        box.addView(label(R.string.filter_scope_label)); box.addView(scopeSpinner)
        box.addView(label(R.string.filter_expiry_label)); box.addView(expirySpinner)
        if (channels.isNotEmpty()) { box.addView(label(R.string.filter_blocked_label)); box.addView(channelBox) }
        val scroll = ScrollView(context)
        scroll.addView(box)

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (existing == null) R.string.filter_add else R.string.filter_edit)
            .setView(scroll)
            .setPositiveButton(R.string.filter_save, null) // wired below so a validation error keeps the dialog open
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        if (existing == null && !prefill.isNullOrEmpty()) {
            terms.requestFocus(); terms.selectAll()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val list = parseTerms(terms.text.toString())
            if (list.isEmpty() && channels.isEmpty()) {
                Toast.makeText(context, R.string.filter_need_something, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val now = System.currentTimeMillis()
            val expires: Long? = when (expirySpinner.selectedItemPosition) {
                1 -> now + 7 * DAY_MS
                2 -> now + 30 * DAY_MS
                3 -> now + 90 * DAY_MS
                5 -> customDate
                else -> null
            }
            val finalName = name.text.toString().trim().ifEmpty { list.firstOrNull() ?: existing?.name ?: "" }
            val rule = FilterRule(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = finalName,
                terms = list,
                scope = scopes[scopeSpinner.selectedItemPosition],
                channelIds = channels.toMap(),
                enabled = existing?.enabled ?: true,
                expiresAt = expires,
            )
            if (existing == null) Filters.add(rule) else Filters.update(rule)
            dialog.dismiss()
            onSaved()
        }
    }
}
