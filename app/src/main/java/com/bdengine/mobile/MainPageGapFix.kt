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
 * Keeps the Block Display landing page's root window stationary and moves scrolling
 * onto a real inner page container. Unlike body/html, the chosen host is not Chromium's
 * special root scroller, so normal touch scrolling can continue without moving WebView.
 */
class MainPageGapInitializerProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        MainPageInnerScrollFix.register(app)
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

private object MainPageInnerScrollFix {

    private val attached = WeakHashMap<WebView, Boolean>()

    private val origins = setOf(
        "https://block-display.com",
        "https://www.block-display.com"
    )

    private val SCRIPT = """
        (() => {
            if (window.__bdengineInnerRootScrollInstalled) return;

            const normalize = value => String(value || '')
                .replace(/\s+/g, ' ')
                .trim()
                .toLowerCase();

            const findExact = text => {
                const wanted = normalize(text);
                const nodes = document.querySelectorAll(
                    'h1,h2,h3,h4,h5,h6,[role="heading"],a,button,p,span'
                );
                for (const node of nodes) {
                    if (normalize(node.textContent) === wanted) return node;
                }
                return null;
            };

            const isUsable = element => {
                if (!element || element === document.body || element === document.documentElement) {
                    return false;
                }

                try {
                    const rect = element.getBoundingClientRect();
                    const style = getComputedStyle(element);
                    if (style.display === 'none' || style.visibility === 'hidden') return false;
                    if (style.position === 'fixed') return false;
                    if (rect.width < window.innerWidth * 0.60) return false;
                    return true;
                } catch (_) {
                    return false;
                }
            };

            const commonAncestor = (first, second) => {
                if (!first || !second) return null;
                const seen = new Set();
                let node = first;
                while (node && node !== document.body) {
                    seen.add(node);
                    node = node.parentElement;
                }
                node = second;
                while (node && node !== document.body) {
                    if (seen.has(node)) return node;
                    node = node.parentElement;
                }
                return null;
            };

            const chooseHost = () => {
                const doc = document.scrollingElement || document.documentElement;
                const documentHeight = Math.max(
                    doc ? doc.scrollHeight : 0,
                    document.body ? document.body.scrollHeight : 0,
                    window.innerHeight
                );

                const ecosystem = findExact('Ecosystem');
                const startModeling = findExact('Start modeling now');
                let shared = commonAncestor(ecosystem, startModeling);

                // Prefer the page wrapper that contains the lower landing sections.
                // Climb until it carries most of the document's vertical content.
                while (shared && shared.parentElement && shared.parentElement !== document.body) {
                    if (isUsable(shared) && shared.scrollHeight >= documentHeight * 0.72) break;
                    shared = shared.parentElement;
                }
                if (isUsable(shared) && shared.scrollHeight >= window.innerHeight * 1.15) {
                    return shared;
                }

                const preferred = [
                    document.querySelector('main'),
                    document.querySelector('[role="main"]'),
                    document.querySelector('#page'),
                    document.querySelector('#app'),
                    document.querySelector('#root'),
                    document.querySelector('#__next'),
                    document.querySelector('.site'),
                    document.querySelector('.site-content'),
                    document.querySelector('.page')
                ];

                for (const candidate of preferred) {
                    if (!isUsable(candidate)) continue;
                    if (candidate.scrollHeight >= documentHeight * 0.72) return candidate;
                }

                // Final fallback: a direct body child that visually represents the page.
                let best = null;
                let bestScore = 0;
                for (const candidate of Array.from(document.body?.children || [])) {
                    if (!isUsable(candidate)) continue;
                    const score = candidate.scrollHeight;
                    if (score > bestScore && score >= documentHeight * 0.72) {
                        best = candidate;
                        bestScore = score;
                    }
                }
                return best;
            };

            const install = () => {
                if (window.__bdengineInnerRootScrollInstalled) return true;
                const html = document.documentElement;
                const body = document.body;
                if (!html || !body) return false;

                const host = chooseHost();
                if (!host) return false;

                const previousY = window.scrollY ||
                    (document.scrollingElement ? document.scrollingElement.scrollTop : 0) || 0;

                window.__bdengineInnerRootScrollInstalled = true;
                window.__bdengineInnerRootScrollHost = host;

                // Root scrolling is what makes the transformed Android WebView expose the
                // oversized virtual viewport. Keep that root stationary.
                html.style.setProperty('height', '100%', 'important');
                html.style.setProperty('overflow', 'hidden', 'important');
                html.style.setProperty('overscroll-behavior', 'none', 'important');

                body.style.setProperty('height', '100%', 'important');
                body.style.setProperty('overflow', 'hidden', 'important');
                body.style.setProperty('overscroll-behavior', 'none', 'important');

                // This is a normal DOM element, not body/html, so Chromium treats it as a
                // genuine independent scroll surface.
                host.style.setProperty('height', '100vh', 'important');
                host.style.setProperty('max-height', '100vh', 'important');
                host.style.setProperty('min-height', '0px', 'important');
                host.style.setProperty('overflow-x', 'hidden', 'important');
                host.style.setProperty('overflow-y', 'auto', 'important');
                host.style.setProperty('overscroll-behavior-y', 'contain', 'important');
                host.style.setProperty('-webkit-overflow-scrolling', 'touch', 'important');
                host.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');

                const pinRoot = () => {
                    if (window.scrollX !== 0 || window.scrollY !== 0) {
                        window.scrollTo(0, 0);
                    }
                };

                pinRoot();
                if (previousY > 0) host.scrollTop = previousY;
                window.addEventListener('scroll', pinRoot, { passive: true });
                return true;
            };

            const tryInstall = () => {
                try { install(); } catch (_) {}
            };

            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', tryInstall, { once: true });
            } else {
                tryInstall();
            }

            window.addEventListener('load', tryInstall, { passive: true });
            setTimeout(tryInstall, 250);
            setTimeout(tryInstall, 750);
            setTimeout(tryInstall, 1600);
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
                    attach(activity)
                }

                override fun onActivityResumed(activity: Activity) {
                    attach(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    private fun attach(activity: Activity) {
        if (activity !is MainActivity) return

        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post

            val webView = findWebView(activity.window.decorView) ?: run {
                activity.window.decorView.postDelayed({ attach(activity) }, 250L)
                return@post
            }

            if (attached.put(webView, true) != null) return@post

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, origins)
            }

            webView.evaluateJavascript(SCRIPT, null)
            webView.postDelayed({ webView.evaluateJavascript(SCRIPT, null) }, 600L)
            webView.postDelayed({ webView.evaluateJavascript(SCRIPT, null) }, 1800L)
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
}
