package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Repo
import io.reactivex.disposables.Disposable

/**
 * Sign in with a YouTube TV code: shows the code from [Repo.signIn], the user approves it at
 * yt.be/activate on another device, then the account is persisted by MediaServiceCore and we finish.
 * Polling stops after ~10 minutes (200 x 3 s in AuthService); any error just shows "Get new code".
 */
class SignInActivity : ThemedActivity() {
    private lateinit var codeView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var retry: Button
    private var action: Disposable? = null
    private var gotCode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.signin_title)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(this, 24)
        col.setPadding(pad, pad, pad, pad)

        fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(this@SignInActivity, top); gravity = Gravity.CENTER_HORIZONTAL }

        col.addView(TextView(this).apply {
            setText(R.string.signin_instructions); textSize = 24f; gravity = Gravity.CENTER
            maxWidth = dp(this@SignInActivity, 640)
        }, lp())
        col.addView(TextView(this).apply {
            setText(R.string.signin_url); textSize = 32f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        }, lp(16))
        codeView = TextView(this).apply {
            textSize = 56f; typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; setTextIsSelectable(true); letterSpacing = 0.05f
        }
        col.addView(codeView, lp(32))
        progress = ProgressBar(this).apply { isIndeterminate = true }
        col.addView(progress, lp(32))
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setTextColor(themeColor(R.attr.dalanTextSecondary)) }
        col.addView(status, lp(8))
        retry = Button(this).apply { setText(R.string.retry); setStartGlyph(R.drawable.ic_refresh); setOnClickListener { start() } }
        col.addView(retry, lp(16))

        val browser = Intent(Intent.ACTION_VIEW, Uri.parse("https://yt.be/activate"))
        if (browser.resolveActivity(packageManager) != null) {
            col.addView(Button(this).apply {
                setText(R.string.signin_open_browser)
                setStartGlyph(R.drawable.ic_open_in_new)
                setOnClickListener { try { startActivity(browser) } catch (_: Exception) { } }
            }, lp(16))
        }
        col.addView(Button(this).apply { setText(R.string.cancel); setOnClickListener { finish() } }, lp(8))

        val sv = ScrollView(this)
        sv.isFillViewport = true
        // Vertically centred when it fits, scrolls otherwise (small screens / landscape).
        val wrap = LinearLayout(this)
        wrap.gravity = Gravity.CENTER
        wrap.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sv.addView(wrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(sv)

        start()
    }

    private fun start() {
        action?.dispose()
        gotCode = false
        codeView.text = ""
        codeView.visibility = View.INVISIBLE
        progress.visibility = View.VISIBLE
        retry.visibility = View.GONE
        status.setText(R.string.loading)
        action = Repo.signIn().subscribe(
            { code ->
                gotCode = true
                codeView.text = code
                codeView.visibility = View.VISIBLE
                status.setText(R.string.signin_waiting)
            },
            { _ ->
                showError()
            },
            {
                if (!Repo.isSignedIn()) { showError(); return@subscribe }
                val acc = Repo.account()
                val name = acc?.name?.takeIf { it.isNotBlank() } ?: acc?.email?.takeIf { it.isNotBlank() }
                val msg = if (name != null) getString(R.string.signin_done, name) else getString(R.string.signin_done_generic)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            },
        )
    }

    private fun showError() {
        progress.visibility = View.GONE
        codeView.visibility = View.INVISIBLE
        status.setText(if (gotCode) R.string.signin_expired else R.string.signin_error)
        retry.setText(if (gotCode) R.string.signin_new_code else R.string.retry)
        retry.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        action?.dispose()
        super.onDestroy()
    }
}
