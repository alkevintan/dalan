package com.aktivesolutions.dalan.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Looper

object Codecs {
    private const val VP9 = "video/x-vnd.on2.vp9"

    @Volatile private var cached: Boolean? = null

    /** Starts the (slow) MediaCodecList enumeration on a background thread. Call from Application.onCreate. */
    fun warmUp() {
        if (cached == null) Thread({ compute() }, "dalan-codecs").start()
    }

    /**
     * True only if a hardware (non-software) VP9 decoder is present. The SM-T550 has none.
     * The result is cached; on the main thread before [warmUp] finished this answers false
     * instead of blocking.
     */
    val hasHardwareVp9: Boolean
        get() = cached ?: if (Looper.myLooper() == Looper.getMainLooper()) false else compute()

    @Synchronized private fun compute(): Boolean {
        cached?.let { return it }
        val r = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(VP9, ignoreCase = true) } && isHardware(info)
            }
        } catch (_: Throwable) { false }
        cached = r
        return r
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
    }
}
