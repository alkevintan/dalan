package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.database.MatrixCursor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SearchView
import android.widget.SimpleCursorAdapter
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Filters
import com.aktivesolutions.dalan.data.Region
import com.aktivesolutions.dalan.data.Repo
import io.reactivex.disposables.Disposable

/**
 * Browse screen with a search box (signed in: Home, Subscriptions and History tabs lead and Home is the default; signed out: Music is the default). Tabs are topic feeds that work without an account (Music, Gaming,
 * News, Sports, Live); Home is added in front only when it returns rows (a logged-out Home is empty by
 * design). The default tab is Music.
 */
class MainActivity : ThemedActivity() {
    private enum class Tab(val label: Int) {
        HOME(R.string.home), SUBS(R.string.subscriptions), HISTORY(R.string.history), MUSIC(R.string.music), GAMING(R.string.gaming), NEWS(R.string.news),
        SPORTS(R.string.sports), LIVE(R.string.live)
    }

    private lateinit var feed: FeedView
    private lateinit var mini: MiniPlayer
    private lateinit var tabBar: LinearLayout
    private val tabButtons = HashMap<Tab, Button>()
    private var tab = Tab.MUSIC
    private var filtersSeen = Filters.version
    private var regionSeen = Region.version
    private var signedIn = false
    private val accountListener: () -> Unit = { onAccountChanged() }
    private var loadAction: Disposable? = null
    private var homeProbe: Disposable? = null
    private var suggestAction: Disposable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var suggestRunnable: Runnable? = null
    private lateinit var suggestAdapter: SimpleCursorAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        tabBar = LinearLayout(this)
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(tabBar)
        feed = FeedView(this)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(feed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mini = MiniPlayer(this)
        root.addView(mini.view)
        setContentView(root)

        val saved = savedInstanceState?.getString("tab")?.let { n -> Tab.values().firstOrNull { it.name == n } }
        signedIn = Repo.isSignedIn()
        tab = saved ?: defaultTab()
        buildTabs()
        load()
        Repo.addAccountListener(accountListener)
        UpdateUi.autoCheck(this)
    }

    private fun defaultTab() = if (signedIn) Tab.HOME else Tab.MUSIC

    /** Signed in: Home, Subscriptions and History are shown (Home first). Signed out: topic feeds plus a probed Home. */
    private fun buildTabs() {
        homeProbe?.dispose()
        tabBar.removeAllViews()
        tabButtons.clear()
        for (t in Tab.values()) {
            if (!signedIn && (t == Tab.HOME || t == Tab.SUBS || t == Tab.HISTORY)) continue
            addTabButton(t)
        }
        if (!signedIn) {
            if (tab == Tab.SUBS || tab == Tab.HISTORY) tab = Tab.MUSIC
            if (tab == Tab.HOME) addTabButton(Tab.HOME) else probeHome()
        }
        updateTabs()
    }

    private fun onAccountChanged() {
        val now = Repo.isSignedIn()
        if (now == signedIn) return
        signedIn = now
        val valid = if (now) true else tab != Tab.HOME && tab != Tab.SUBS && tab != Tab.HISTORY
        // Signing in: land on Home. Signing out: only move if the current tab vanished.
        if (now || !valid) tab = defaultTab()
        buildTabs()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (recreating) return
        val regionChanged = regionSeen != Region.version
        regionSeen = Region.version
        val wasSignedIn = signedIn
        onAccountChanged()
        // onAccountChanged() already reloaded if the account changed; otherwise reload for the new region.
        if (regionChanged && wasSignedIn == signedIn) { buildTabs(); load() }
        if (filtersSeen != Filters.version) { filtersSeen = Filters.version; feed.reapplyFilters() }
    }

    private fun addTabButton(t: Tab) {
        val b = Button(this, null, android.R.attr.borderlessButtonStyle).apply { setText(t.label); setOnClickListener { select(t) } }
        tabButtons[t] = b
        // Home goes first, the rest keep their enum order.
        tabBar.addView(b, if (t == Tab.HOME) 0 else tabBar.childCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        updateTabs()
    }

    /** Shows the Home tab only if Home actually returns rows (it is empty when logged out). */
    private fun probeHome() {
        homeProbe = Repo.home().subscribe({ groups ->
            if (groups.any { !it.mediaItems.isNullOrEmpty() }) {
                if (Tab.HOME !in tabButtons) addTabButton(Tab.HOME)
                homeProbe?.dispose()
            }
        }, { })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        feed.updateSpan()
    }

    private fun select(t: Tab) {
        if (t == tab) { feed.scrollToTop(); return }
        tab = t
        updateTabs()
        load()
    }

    private fun updateTabs() {
        val on = themeColor(R.attr.dalanAccent)
        val off = themeColor(R.attr.dalanTextPrimary)
        for ((t, b) in tabButtons) {
            val sel = t == tab
            b.setTextColor(if (sel) on else off)
            b.setTypeface(null, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun load() {
        loadAction?.dispose()
        feed.filterEnabled = tab != Tab.HISTORY // watch history is a record: never hide anything in it
        feed.showLoading(sections = tab != Tab.SUBS && tab != Tab.HISTORY)
        val source = when (tab) {
            Tab.HOME -> Repo.home()
            Tab.SUBS -> Repo.subscriptions()
            Tab.HISTORY -> Repo.history()
            Tab.MUSIC -> Repo.music()
            Tab.GAMING -> Repo.gaming()
            Tab.NEWS -> Repo.news()
            Tab.SPORTS -> Repo.sports()
            Tab.LIVE -> Repo.live()
        }
        loadAction = source.subscribe(
            { groups -> feed.showGroups(groups, sections = tab != Tab.SUBS && tab != Tab.HISTORY) },
            { e -> feed.showError(e.message) { load() } },
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        // The action bar draws its icons in the bar's own text colour (dark on the light theme).
        val iconColor = (actionBar?.themedContext ?: this).themeColor(android.R.attr.textColorPrimary)
        for (i in 0 until menu.size()) menu.getItem(i).icon = menu.getItem(i).icon?.mutate()?.also { it.setTint(iconColor) }
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        suggestAdapter = SimpleCursorAdapter(this, android.R.layout.simple_list_item_1, null, arrayOf("q"), intArrayOf(android.R.id.text1), 0)
        searchView.suggestionsAdapter = suggestAdapter
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                startSearch(query); searchView.clearFocus(); return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                scheduleSuggest(newText); return true
            }
        })
        searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int) = false
            override fun onSuggestionClick(position: Int): Boolean {
                val c = suggestAdapter.cursor
                if (c != null && c.moveToPosition(position)) startSearch(c.getString(c.getColumnIndexOrThrow("q")))
                return true
            }
        })
        return true
    }

    private fun startSearch(q: String) {
        if (q.isBlank()) return
        startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_QUERY, q.trim()))
    }

    private fun scheduleSuggest(text: String) {
        suggestRunnable?.let { handler.removeCallbacks(it) }
        suggestAction?.dispose()
        if (text.length < 2) { suggestAdapter.changeCursor(null); return }
        val r = Runnable {
            suggestAction = Repo.suggest(text).subscribe({ tags ->
                val c = MatrixCursor(arrayOf("_id", "q"))
                tags.take(8).forEachIndexed { i, t -> c.addRow(arrayOf(i, t)) }
                suggestAdapter.changeCursor(c)
            }, { })
        }
        suggestRunnable = r
        handler.postDelayed(r, 250)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> { load(); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onStart() { super.onStart(); mini.onStart() }

    override fun onStop() { mini.onStop(); super.onStop() }

    override fun onDestroy() {
        Repo.removeAccountListener(accountListener)
        loadAction?.dispose(); homeProbe?.dispose(); suggestAction?.dispose(); feed.dispose()
        suggestRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
