package com.aktivesolutions.dalan.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.FilterRule
import com.aktivesolutions.dalan.data.Filters
import com.aktivesolutions.dalan.data.Scope
import java.text.DateFormat
import java.util.Date

/** Lists the content filters: tap to edit, long-press to delete, switch to enable / disable. */
class FiltersActivity : ThemedActivity() {
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private val adapter = RuleAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.filters_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val root = FrameLayout(this)
        list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> edit(adapter.getItem(pos)) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(adapter.getItem(pos)); true }
        empty = TextView(this)
        empty.setText(R.string.filters_empty)
        empty.gravity = Gravity.CENTER
        empty.setTextColor(themeColor(R.attr.dalanTextSecondary))
        empty.setPadding(dp(this, 32), 0, dp(this, 32), 0)
        root.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!recreating) refresh()
    }

    private fun refresh() {
        adapter.rules = Filters.rules
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun edit(r: FilterRule) {
        val current = Filters.rules.firstOrNull { it.id == r.id } ?: return // the row may be stale (e.g. just toggled)
        FilterEditDialog.show(this, current) { refresh() }
    }

    private fun confirmDelete(r: FilterRule) {
        AlertDialog.Builder(this)
            .setTitle(R.string.filter_delete_title)
            .setMessage(getString(R.string.filter_delete_msg, displayName(r)))
            .setPositiveButton(R.string.delete) { _, _ -> Filters.delete(r.id); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD, 0, R.string.filter_add).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        MENU_ADD -> { FilterEditDialog.show(this, null) { refresh() }; true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun displayName(r: FilterRule) =
        if (r.id == Filters.BLOCKED_ID) getString(R.string.filter_blocked_channels) else r.name

    private inner class RuleAdapter : BaseAdapter() {
        var rules: List<FilterRule> = emptyList()
        private val df = DateFormat.getDateInstance(DateFormat.MEDIUM)

        override fun getCount() = rules.size
        override fun getItem(position: Int) = rules[position]
        override fun getItemId(position: Int) = position.toLong()

        private fun summary(r: FilterRule): String {
            val parts = ArrayList<String>()
            if (r.terms.isNotEmpty()) parts.add(r.terms.take(5).joinToString(", ") + if (r.terms.size > 5) ", …" else "")
            if (r.channelIds.isNotEmpty()) parts.add(resources.getQuantityString(R.plurals.filter_channels_count, r.channelIds.size, r.channelIds.size))
            return parts.joinToString(" · ")
        }

        private fun detail(r: FilterRule): String {
            val parts = ArrayList<String>()
            if (r.terms.isNotEmpty()) parts.add(getString(when (r.scope) {
                Scope.BOTH -> R.string.filter_scope_both
                Scope.TITLE -> R.string.filter_scope_title
                Scope.CHANNEL -> R.string.filter_scope_channel
            }))
            val exp = r.expiresAt
            if (exp != null) parts.add(if (Filters.isExpired(r)) getString(R.string.filter_expired) else getString(R.string.filter_expires, df.format(Date(exp))))
            return parts.joinToString(" · ")
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h: Row
            val v: View
            if (convertView == null) {
                h = Row(); v = h.root
                v.tag = h
            } else { v = convertView; h = v.tag as Row }
            val r = rules[position]
            h.name.text = displayName(r)
            h.terms.text = summary(r)
            h.detail.text = detail(r)
            h.detail.visibility = if (h.detail.text.isEmpty()) View.GONE else View.VISIBLE
            h.name.alpha = if (r.enabled) 1f else 0.5f
            h.toggle.setOnCheckedChangeListener(null)
            h.toggle.isChecked = r.enabled
            h.toggle.setOnCheckedChangeListener { _, on -> Filters.setEnabled(r.id, on); rules = Filters.rules; h.name.alpha = if (on) 1f else 0.5f }
            return v
        }
    }

    private inner class Row {
        val name = TextView(this@FiltersActivity)
        val terms = TextView(this@FiltersActivity)
        val detail = TextView(this@FiltersActivity)
        val toggle = Switch(this@FiltersActivity)
        val root = LinearLayout(this@FiltersActivity)

        init {
            val c = this@FiltersActivity
            name.setTextColor(c.themeColor(R.attr.dalanTextPrimary)); name.textSize = 16f
            name.setSingleLine(); name.ellipsize = TextUtils.TruncateAt.END
            terms.setTextColor(c.themeColor(R.attr.dalanTextPrimary)); terms.textSize = 13f
            terms.maxLines = 2; terms.ellipsize = TextUtils.TruncateAt.END
            detail.setTextColor(c.themeColor(R.attr.dalanTextSecondary)); detail.textSize = 12f
            val col = LinearLayout(c)
            col.orientation = LinearLayout.VERTICAL
            col.addView(name); col.addView(terms); col.addView(detail)
            toggle.isFocusable = false // a focusable child would swallow the row's tap / long-press
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10))
            root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private companion object { const val MENU_ADD = 1 }
}
