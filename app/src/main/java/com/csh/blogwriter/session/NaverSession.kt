package com.csh.blogwriter.session

import android.webkit.CookieManager
import com.csh.blogwriter.data.prefs.SettingsStore
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class NaverSession @Inject constructor(private val settings: SettingsStore) {
    suspend fun logout() {
        suspendCancellableCoroutine { cont -> CookieManager.getInstance().removeAllCookies { cont.resume(Unit) } }
        CookieManager.getInstance().flush()
        settings.setBlogId(null)
    }
}
