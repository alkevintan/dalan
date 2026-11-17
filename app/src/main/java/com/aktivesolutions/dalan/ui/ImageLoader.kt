package com.aktivesolutions.dalan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Small in-house thumbnail loader. RGB_565, downsampled to the target size, LruCache of
 * 1/8 of the max heap, max 4 concurrent downloads, 20 MB disk cache (OkHttp cache in cacheDir),
 * cancels work when a view is re-bound, and can be paused during fast flings.
 */
object ImageLoader {
    private const val MAX_CONCURRENT = 4
    private const val DISK_CACHE_BYTES = 20L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 2L * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())
    private lateinit var memory: LruCache<String, Bitmap>
    private lateinit var diskCache: Cache
    private lateinit var client: OkHttpClient
    private lateinit var cacheDir: File

    private val executor = ThreadPoolExecutor(
        MAX_CONCURRENT, MAX_CONCURRENT, 30, TimeUnit.SECONDS, LinkedBlockingDeque<Runnable>()
    )

    /** Views waiting for the loader to be un-paused. Main thread only. */
    private val pending = WeakHashMap<ImageView, Request3>()
    private var paused = false

    private class Request3(val url: String, val w: Int, val h: Int)

    private val TAG_URL = "dalan_img_url".hashCode() or 0x7f000000

    fun init(context: Context) {
        if (::memory.isInitialized) return
        val maxBytes = (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(1 shl 20)
        memory = object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        cacheDir = File(context.cacheDir, "img")
        diskCache = Cache(cacheDir, DISK_CACHE_BYTES)
        client = OkHttpManager.instance().client.newBuilder().cache(diskCache).build()
    }

    /** Loads [url] into [view], sampled down to about [w] x [h] pixels. */
    fun load(view: ImageView, url: String?, w: Int, h: Int) {
        view.setTag(TAG_URL, url)
        pending.remove(view)
        if (url.isNullOrEmpty()) { view.setImageDrawable(null); return }
        val key = "$url@$w"
        val cached = memory.get(key)
        if (cached != null) { view.setImageBitmap(cached); return }
        view.setImageDrawable(null)
        val req = Request3(url, w, h)
        if (paused) { pending[view] = req; return }
        submit(view, req)
    }

    /** Cancels any pending work for [view] (call when a ViewHolder is recycled). */
    fun cancel(view: ImageView) {
        view.setTag(TAG_URL, null)
        pending.remove(view)
    }

    /** Pause/resume starting new downloads (cached bitmaps are still applied). */
    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (!p) {
            val snapshot = HashMap(pending)
            pending.clear()
            for ((v, r) in snapshot) {
                if (v.getTag(TAG_URL) == r.url) submit(v, r)
            }
        }
    }

    /** The memory part is immediate; the disk eviction does file I/O, so it runs on the loader's executor. */
    fun clearCaches() {
        memory.evictAll()
        executor.execute { try { diskCache.evictAll() } catch (_: Exception) {} }
    }

    private fun submit(view: ImageView, r: Request3) {
        executor.execute {
            // View was re-bound or recycled while queued: skip.
            if (view.getTag(TAG_URL) != r.url) return@execute
            val bmp = try { fetch(r) } catch (_: Throwable) { null } ?: return@execute
            memory.put("${r.url}@${r.w}", bmp)
            main.post { if (view.getTag(TAG_URL) == r.url) view.setImageBitmap(bmp) }
        }
    }

    private fun fetch(r: Request3): Bitmap? {
        val resp = client.newCall(Request.Builder().url(r.url).build()).execute()
        resp.use {
            if (!it.isSuccessful) return null
            val body = it.body() ?: return null
            if (body.contentLength() > MAX_IMAGE_BYTES) return null
            val bytes = body.bytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, r.w, r.h)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            // Power-of-two sampling leaves up to 2x too many pixels: scale to the exact target width.
            val (tw, th) = scaledSize(decoded.width, decoded.height, r.w)
            if (tw == decoded.width && th == decoded.height) return decoded
            val scaled = Bitmap.createScaledBitmap(decoded, tw, th, true)
            if (scaled !== decoded) decoded.recycle()
            return scaled
        }
    }

    /** Target size keeping the aspect ratio, never upscaling. */
    internal fun scaledSize(srcW: Int, srcH: Int, reqW: Int): Pair<Int, Int> {
        if (reqW <= 0 || srcW <= reqW) return srcW to srcH
        return reqW to (srcH.toLong() * reqW / srcW).toInt().coerceAtLeast(1)
    }

    internal fun sampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        if (reqW <= 0 || reqH <= 0) return s
        while (srcW / (s * 2) >= reqW && srcH / (s * 2) >= reqH) s *= 2
        return s
    }
}
