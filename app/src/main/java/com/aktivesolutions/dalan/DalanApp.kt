package com.aktivesolutions.dalan

import android.app.Application
import android.util.Log
import com.aktivesolutions.dalan.data.Filters
import com.aktivesolutions.dalan.data.ContentLanguage
import com.aktivesolutions.dalan.data.Settings
import com.aktivesolutions.dalan.player.Codecs
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
import java.io.IOException
import java.net.SocketException
import com.aktivesolutions.dalan.ui.ImageLoader
import com.liskovsoft.googlecommon.common.locale.LocaleManager
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import com.liskovsoft.youtubeapi.service.internal.YouTubeAccountManager

class DalanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Same as SmartTube's MainApplication: HTTP keep-alive off avoids YouTube throttling /
        // stalled connections with HttpURLConnection-based data sources.
        System.setProperty("http.keepAlive", "false")

        // MediaServiceCore's resolver needs GlobalPreferences (it carries the Context used by AppService,
        // MediaServiceData, MediaServicePrefs, LocaleManager ...). SmartTube does this in SplashPresenter.
        // Must run before any media-service call. LocaleManager derives language/country from the
        // system locale of that Context when no locale was saved, so no extra locale setup is needed;
        // unhold() just makes sure a stale instance is rebuilt after the context exists.
        GlobalPreferences.instance(this)
        LocaleManager.unhold()
        Settings.get(this).prefs.let { if (it.contains("region")) it.edit().remove("region").apply() } // obsolete country pref
        ContentLanguage.apply(Settings.get(this).contentLanguage)
        // The sign-in service restores accounts on a background thread; do it synchronously (idempotent)
        // so Repo.isSignedIn() is right as soon as MainActivity starts.
        try { YouTubeAccountManager.instance(YouTubeSignInService.instance()).init() } catch (e: Throwable) { Log.w("DalanApp", "account restore failed", e) }

        AppVisibility.register(this)
        ImageLoader.init(this)
        Filters.init(this)
        Codecs.warmUp()

        // Same as SmartTube's RxHelper.setupGlobalErrorHandler: errors that arrive after a subscriber
        // was disposed (UndeliverableException) must not crash the app.
        RxJavaPlugins.setErrorHandler { e0 ->
            var e: Throwable = e0
            if (e is UndeliverableException && e.cause != null) e = e.cause!!
            when (e) {
                is IOException, is InterruptedException, is SocketException -> Log.d("DalanRx", "ignored: $e")
                else -> Log.e("DalanRx", "undeliverable error", e)
            }
        }
    }
}
