package com.aktivesolutions.dalan.data

import com.liskovsoft.mediaserviceinterfaces.ContentService
import com.liskovsoft.mediaserviceinterfaces.MediaItemService
import com.liskovsoft.mediaserviceinterfaces.ServiceManager
import com.liskovsoft.mediaserviceinterfaces.SignInService
import com.liskovsoft.mediaserviceinterfaces.oauth.Account
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.Function
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper over MediaServiceCore's [YouTubeServiceManager]. Everything runs on
 * `Schedulers.io()` and is delivered on the main thread. Callers own the returned Disposables.
 */
object Repo {
    private val manager: ServiceManager get() = YouTubeServiceManager.instance()
    private val content: ContentService get() = manager.contentService
    private val items: MediaItemService get() = manager.mediaItemService

    /** Max number of partial emissions to take from a feed observable (they otherwise page through the whole feed). */
    private const val FEED_PARTIALS = 3

    /** A feed that produced nothing for this long ends in the error state (with Retry). */
    private const val TIMEOUT_S = 20L

    /** Item handed to the next activity (MediaItem isn't Parcelable). Only valid momentarily. */
    @Volatile var pendingItem: MediaItem? = null

    /** Empty for a logged-out user (SmartTube's BrowsePresenter switches to Music on first run for that reason). */
    fun home(): Observable<List<MediaGroup>> = feed(content.homeObserve)

    // Topic feeds that work without an account. (WEB FEtrending is probably retired by YouTube, so no Trending.)
    fun music(): Observable<List<MediaGroup>> = feed(content.musicObserve)
    fun gaming(): Observable<List<MediaGroup>> = feed(content.gamingObserve)
    fun news(): Observable<List<MediaGroup>> = feed(content.newsObserve)
    fun sports(): Observable<List<MediaGroup>> = feed(content.sportsObserve)
    fun live(): Observable<List<MediaGroup>> = feed(content.liveObserve)
    fun search(q: String): Observable<List<MediaGroup>> = feed(content.getSearchObserve(q))
    fun channel(item: MediaItem): Observable<List<MediaGroup>> = feed(content.getChannelObserve(item))
    fun channel(channelId: String): Observable<List<MediaGroup>> = feed(content.getChannelObserve(channelId))
    fun group(item: MediaItem): Observable<MediaGroup> =
        ui(content.getGroupObserve(item).timeout(TIMEOUT_S, TimeUnit.SECONDS))

    fun suggest(q: String): Observable<List<String>> = ui(content.getSearchTagsObserve(q))

    fun continueGroup(g: MediaGroup): Observable<MediaGroup> =
        ui(content.continueGroupObserve(g).timeout(TIMEOUT_S, TimeUnit.SECONDS))

    fun metadata(videoId: String): Observable<MediaItemMetadata> = ui(items.getMetadataObserve(videoId))

    fun formatInfo(videoId: String): Observable<MediaItemFormatInfo> = ui(items.getFormatInfoObserve(videoId))

    // ---- Account (sign in with a code at yt.be/activate) ----

    private val signInService: SignInService get() = manager.signInService

    /** Emits the user code once, then completes when the user approves it (blocks polling meanwhile). */
    fun signIn(): Observable<String> = ui(signInService.signInObserve())

    fun isSignedIn(): Boolean = try { signInService.isSigned } catch (_: Throwable) { false }

    fun account(): Account? = try { if (signInService.isSigned) signInService.selectedAccount else null } catch (_: Throwable) { null }

    /** Removes every account, drops cached auth and clears the auth headers. Runs on io (removeAccount can block on the network), completes on main. */
    fun signOut(): Completable = Completable.fromAction {
        val svc = signInService
        svc.accounts?.toList()?.forEach { svc.removeAccount(it) }
        manager.invalidateCache()
        Region.reapply() // invalidateCache() drops LocaleManager, and with it the Region override
        (svc as? YouTubeSignInService)?.checkAuth()
    }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).doOnTerminate { notifyAccountChanged() }

    private val accountListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private var accountHooked = false

    // The service holds its listener weakly, so keep a strong reference here.
    private val serviceListener = SignInService.OnAccountChange { notifyAccountChanged() }

    private fun notifyAccountChanged() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.post { for (l in accountListeners) l() }
    }

    /** Listener runs on the main thread when the account is added, switched or removed. Remove it in onDestroy. */
    @Synchronized
    fun addAccountListener(l: () -> Unit) {
        if (!accountHooked) {
            accountHooked = true
            try { signInService.addOnAccountChange(serviceListener) } catch (_: Throwable) { }
        }
        accountListeners.add(l)
    }

    fun removeAccountListener(l: () -> Unit) { accountListeners.remove(l) }

    fun subscriptions(): Observable<List<MediaGroup>> = single(content.subscriptionsObserve)
    fun history(): Observable<List<MediaGroup>> = single(content.historyObserve)

    private fun single(o: Observable<MediaGroup>): Observable<List<MediaGroup>> =
        ui(o.timeout(TIMEOUT_S, TimeUnit.SECONDS).map { listOf(it) })

    /** Tells YouTube "Not interested" (signed-in feeds only). Fire and forget: the result is ignored. */
    fun notInterested(feedbackToken: String) {
        try { items.markAsNotInterestedObserve(feedbackToken).subscribeOn(Schedulers.io()).subscribe({ }, { }) } catch (_: Throwable) { }
    }

    fun switchNextClient() = manager.switchNextClient()
    fun reloadPlayerResponse(videoId: String, token: String) = manager.reloadPlayerResponse(videoId, token)

    private fun <T> ui(o: Observable<T>): Observable<T> =
        o.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())

    /**
     * Accumulates the first few partial emissions into one list, then stops (disposing the upstream, whose
     * paging loop checks isDisposed, see THIRD_PARTY.md patch 4).
     *  - completes without emitting -> one empty list (the UI shows its empty state instead of spinning forever)
     *  - no first emission within [TIMEOUT_S] -> TimeoutException (the UI shows the error state)
     *  - an error after content was already delivered is swallowed (keep what we have)
     */
    private fun feed(o: Observable<List<MediaGroup>>): Observable<List<MediaGroup>> =
        Observable.defer {
            val got = AtomicBoolean(false)
            ui(
                o.take(FEED_PARTIALS.toLong())
                    .doOnNext { got.set(true) }
                    .timeout(
                        Observable.timer(TIMEOUT_S, TimeUnit.SECONDS),
                        Function<List<MediaGroup>, Observable<Long>> { Observable.never() }
                    )
                    .onErrorResumeNext { e: Throwable -> if (got.get()) Observable.empty() else Observable.error(e) }
                    .defaultIfEmpty(emptyList())
                    .scan(emptyList<MediaGroup>()) { acc, next -> acc + next }
                    .skip(1)
            )
        }
}
