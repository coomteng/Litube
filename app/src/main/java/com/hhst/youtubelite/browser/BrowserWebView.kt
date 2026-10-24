package com.hhst.youtubelite.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView that can pretend its window stayed visible.
 *
 * Shorts play inside the page, not in the native player. When the screen turns off or the
 * app goes to the background, the window becomes invisible and Chromium hides the
 * WebContents, which suspends media and tells the page it is hidden (YouTube then calls
 * `stopVideo()`). Holding [keepVisible] while a Short should keep playing makes Chromium
 * treat the content as still on screen, the same way the native player keeps running under
 * its foreground service. Released again on resume, so normal background throttling returns.
 */
@SuppressLint("ViewConstructor")
class BrowserWebView(context: Context) : WebView(context) {
    @Volatile
    var keepVisible = false
        set(value) {
            if (field == value) return
            field = value
            // Re-dispatch the real state once the hold ends, so Chromium catches up.
            if (!value && windowVisibility != View.VISIBLE) super.onWindowVisibilityChanged(windowVisibility)
        }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (keepVisible) View.VISIBLE else visibility)
    }

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        super.dispatchWindowVisibilityChanged(if (keepVisible) View.VISIBLE else visibility)
    }
}
