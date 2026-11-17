package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Repo
import io.reactivex.disposables.Disposable

/** Search results grid with infinite scroll. */
class SearchActivity : Activity() {
    private lateinit var feed: FeedView
    private var query = ""
    private var action: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Dalan)
        query = intent.getStringExtra(EXTRA_QUERY) ?: ""
        title = query
        actionBar?.setDisplayHomeAsUpEnabled(true)
        feed = FeedView(this)
        setContentView(feed)
        load()
    }

    private fun load() {
        action?.dispose()
        feed.showLoading()
        action = Repo.search(query).subscribe(
            { groups -> feed.showGroups(groups, sections = false) },
            { e -> feed.showError(e.message) { load() } },
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); feed.updateSpan()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() { action?.dispose(); feed.dispose(); super.onDestroy() }

    companion object { const val EXTRA_QUERY = "query" }
}
