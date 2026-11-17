package com.liskovsoft.sharedutils.cronet

import android.content.Context
import org.chromium.net.CronetEngine

// DALAN: upstream builds an embedded Cronet engine (org.chromium.net.impl.NativeCronetProvider from
// cronet-embedded, ~5 MB of native code for armeabi-v7a). Dalan does not ship cronet-embedded, so no engine is
// available and callers (RetrofitOkHttpHelper) fall back to plain OkHttp, which they already handle (null engine).
object CronetManager {
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun getEngine(context: Context): CronetEngine? = null
}
