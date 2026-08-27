package com.bdengine.mobile

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * Keeps the Android WebView viewport pinned while routing vertical touch scrolling
 * through the main Block Display page's body scroll container.
 */
class MainPageScrollInitializerProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        MainPageScrollCompat.register(app)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

private object MainPageScrollCompat {

    private val attached = WeakHashMap<WebView, Boolean>()

    private val origins = setOf(
        "https://block-display.com",
        "https://www.block-display.com"
    )

    private val TOUCH_SCROLL_SCRIPT = """
        (() => {
            if (window.__bdengineManualMainScrollInstalled) return;

            const install = () => {
                const html = document.documentElement;
                const body = document.body;
                if (!html || !body) return false;
                if (window.__bdengineManualMainScrollInstalled) return true;
                window.__bdengineManualMainScrollInstalled = true;

                // Keep a real, bounded inner scroll surface without changing the body's
                // positioning model. This avoids the blank-screen regression caused by
                // position: fixed while still separating body scrolling from window scrolling.
                html.style.setProperty('height', '100%', 'important');
                html.style.setProperty('overflow', 'hidden', 'important');
                html.style.setProperty('overscroll-behavior', 'none', 'important');

                body.style.setProperty('height', '100%', 'important');
                body.style.setProperty('max-height', '100%', 'important');
                body.style.setProperty('overflow-x', 'hidden', 'important');
                body.style.setProperty('overflow-y', 'auto', 'important');
                body.style.setProperty('overscroll-behavior-y', 'contain', 'important');
                body.style.setProperty('-webkit-overflow-scrolling', 'touch', 'important');

                let active = false;
                let nestedHost = null;
                let startX = 0;
                let startY = 0;
                let startScroll = 0;
                let lastY = 0;
                let lastTime = 0;
                let velocity = 0;
                let flingFrame = 0;

                const stopFling = () => {
                    if (flingFrame) cancelAnimationFrame(flingFrame);
                    flingFrame = 0;
                    velocity = 0;
                };

                const findNestedScrollable = target => {
                    let node = target && target.nodeType === 1 ? target : target?.parentElement;
                    while (node && node !== body && node !== html) {
                        try {
                            const style = getComputedStyle(node);
                            const overflowY = style.overflowY;
                            if (
                                (overflowY === 'auto' || overflowY === 'scroll') &&
                                node.scrollHeight > node.clientHeight + 1
                            ) {
                                return node;
                            }
                        } catch (_) {}
                        node = node.parentElement;
                    }
                    return null;
                };

                const nestedCanMove = (host, delta) => {
                    if (!host) return false;
                    if (delta > 0) {
                        return host.scrollTop + host.clientHeight < host.scrollHeight - 1;
                    }
                    if (delta < 0) return host.scrollTop > 1;
                    return true;
                };

                const pinOuter = () => {
                    if (window.scrollX !== 0 || window.scrollY !== 0) {
                        window.scrollTo(0, 0);
                    }
                };

                const beginManualScroll = touch => {
                    active = true;
                    nestedHost = null;
                    startX = touch.clientX;
                    startY = touch.clientY;
                    startScroll = body.scrollTop;
                    lastY = touch.clientY;
                    lastTime = performance.now();
                    velocity = 0;
                };

                const startFling = () => {
                    if (Math.abs(velocity) < 0.03) return;
                    let previousTime = performance.now();

                    const step = now => {
                        const dt = Math.min(32, Math.max(1, now - previousTime));
                        previousTime = now;

                        const before = body.scrollTop;
                        body.scrollTop = before + velocity * dt;
                        pinOuter();

                        const after = body.scrollTop;
                        velocity *= Math.pow(0.94, dt / 16.67);

                        const hitEdge = Math.abs(after - before) < 0.1;
                        if (hitEdge || Math.abs(velocity) < 0.02) {
                            flingFrame = 0;
                            velocity = 0;
                            return;
                        }

                        flingFrame = requestAnimationFrame(step);
                    };

                    flingFrame = requestAnimationFrame(step);
                };

                document.addEventListener('touchstart', event => {
                    if (event.touches.length !== 1) {
                        active = false;
                        nestedHost = null;
                        return;
                    }

                    stopFling();
                    const touch = event.touches[0];
                    nestedHost = findNestedScrollable(event.target);

                    startX = touch.clientX;
                    startY = touch.clientY;
                    startScroll = body.scrollTop;
                    lastY = touch.clientY;
                    lastTime = performance.now();
                    velocity = 0;
                    active = nestedHost === null;
                }, { capture: true, passive: true });

                document.addEventListener('touchmove', event => {
                    if (event.touches.length !== 1) return;

                    const touch = event.touches[0];
                    const deltaFromLast = lastY - touch.clientY;

                    if (nestedHost) {
                        if (nestedCanMove(nestedHost, deltaFromLast)) {
                            lastY = touch.clientY;
                            lastTime = performance.now();
                            return;
                        }

                        // The nested panel reached its edge. Continue the same gesture on
                        // the page itself instead of letting Chromium fall back to root scroll.
                        beginManualScroll(touch);
                    }

                    if (!active) return;

                    const dx = touch.clientX - startX;
                    const dy = touch.clientY - startY;
                    if (Math.abs(dx) > Math.abs(dy)) return;
                    if (body.scrollHeight <= body.clientHeight + 1) return;

                    if (event.cancelable) event.preventDefault();

                    const maxScroll = Math.max(0, body.scrollHeight - body.clientHeight);
                    body.scrollTop = Math.max(0, Math.min(maxScroll, startScroll - dy));
                    pinOuter();

                    const now = performance.now();
                    const dt = Math.max(1, now - lastTime);
                    const instantVelocity = (lastY - touch.clientY) / dt;
                    velocity = velocity * 0.65 + instantVelocity * 0.35;
                    lastY = touch.clientY;
                    lastTime = now;
                }, { capture: true, passive: false });

                document.addEventListener('touchend', () => {
                    if (active) startFling();
                    active = false;
                    nestedHost = null;
                }, { capture: true, passive: true });

                document.addEventListener('touchcancel', () => {
                    active = false;
                    nestedHost = null;
                    stopFling();
                }, { capture: true, passive: true });

                window.addEventListener('scroll', pinOuter, { passive: true });
                pinOuter();
                return true;
            };

            if (!install()) {
                document.addEventListener('DOMContentLoaded', install, { once: true });
            }
        })();
    """.trimIndent()

    @Volatile
    private var registered = false

    fun register(application: Application) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
        }

        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    attachWhenReady(activity)
                }

                override fun onActivityResumed(activity: Activity) {
                    attachWhenReady(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    private fun attachWhenReady(activity: Activity) {
        if (activity !is MainActivity) return

        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post

            val webView = findWebView(activity.window.decorView) ?: run {
                activity.window.decorView.postDelayed({ attachWhenReady(activity) }, 250L)
                return@post
            }

            if (!attached.containsKey(webView)) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        TOUCH_SCROLL_SCRIPT,
                        origins
                    )
                }
                attached[webView] = true
            }

            if (isBlockDisplayUrl(webView.url)) {
                webView.evaluateJavascript(TOUCH_SCROLL_SCRIPT, null)
            }
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null

        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun isBlockDisplayUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val host = Uri.parse(url).host.orEmpty().lowercase()
            host == "block-display.com" || host == "www.block-display.com"
        } catch (_: Throwable) {
            false
        }
    }
}
