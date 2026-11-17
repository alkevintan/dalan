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
import com.aktivesolutions.dalan.data.Repo
import io.reactivex.disposables.Disposable

/**
 * Browse screen with a search box. Tabs are topic feeds that work without an account (Music, Gaming,
 * News, Sports, Live); Home is added in front only when it returns rows (a logged-out Home is empty by
 * design). The default tab is Music.
 */
class MainActivity : Activity() {
    private enum class Tab(val label: Int) {
        HOME(R.string.home), MUSIC(R.string.music), GAMING(R.string.gaming), NEWS(R.string.news),
        SPORTS(R.string.sports), LIVE(R.string.live)
    }

    private lateinit var feed: FeedView
    private lateinit var tabBar: LinearLayout
    private val tabButtons = HashMap<Tab, Button>()
    private var tab = Tab.MUSIC
    private var loadAction: Disposable? = null
    private var homeProbe: Disposable? = null
    private var suggestAction: Disposable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var suggestRunnable: Runnable? = null
    private lateinit var suggestAdapter: SimpleCursorAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Dalan)
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
        setContentView(root)

        val saved = savedInstanceState?.getString("tab")?.let { n -> Tab.values().firstOrNull { it.name == n } }
        tab = saved ?: Tab.MUSIC
        for (t in Tab.values()) if (t != Tab.HOME) addTabButton(t)
        if (tab == Tab.HOME) addTabButton(Tab.HOME) else probeHome()
        updateTabs()
        load()
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
        for ((t, b) in tabButtons) b.isEnabled = t != tab
    }

    private fun load() {
        loadAction?.dispose()
        feed.showLoading()
        val source = when (tab) {
            Tab.HOME -> Repo.home()
            Tab.MUSIC -> Repo.music()
            Tab.GAMING -> Repo.gaming()
            Tab.NEWS -> Repo.news()
            Tab.SPORTS -> Repo.sports()
            Tab.LIVE -> Repo.live()
        }
        loadAction = source.subscribe(
            { groups -> feed.showGroups(groups, sections = true) },
            { e -> feed.showError(e.message) { load() } },
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
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

    override fun onDestroy() {
        loadAction?.dispose(); homeProbe?.dispose(); suggestAction?.dispose(); feed.dispose()
        suggestRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
