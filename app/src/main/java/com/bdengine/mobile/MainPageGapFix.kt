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
 * Compacts only the oversized landing-page sections that become visibly empty when
 * BDEngine Mobile uses a large virtual dp viewport. It deliberately does not change
 * root scrolling or WebView geometry.
 */
class MainPageGapInitializerProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        MainPageGapFix.register(app)
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

private object MainPageGapFix {

    private val attached = WeakHashMap<WebView, Boolean>()

    private val origins = setOf(
        "https://block-display.com",
        "https://www.block-display.com"
    )

    private val SCRIPT = """
        (() => {
            if (window.__bdengineMainGapFixInstalled) return;
            window.__bdengineMainGapFixInstalled = true;

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

            const sectionFor = (anchor, otherAnchor) => {
                if (!anchor) return null;

                const semanticSection = anchor.closest('section');
                if (semanticSection && (!otherAnchor || !semanticSection.contains(otherAnchor))) {
                    return semanticSection;
                }

                let node = anchor;
                let best = anchor.parentElement;
                while (node && node.parentElement) {
                    const parent = node.parentElement;
                    if (otherAnchor && parent.contains(otherAnchor)) break;
                    best = parent;
                    node = parent;
                }
                return best;
            };

            const visible = element => {
                try {
                    const style = getComputedStyle(element);
                    if (style.display === 'none' || style.visibility === 'hidden') return false;
                    if (style.position === 'fixed') return false;
                    const rect = element.getBoundingClientRect();
                    return rect.width > 2 && rect.height > 2;
                } catch (_) {
                    return false;
                }
            };

            const meaningfulBottom = section => {
                let bottom = section.getBoundingClientRect().top;
                const nodes = section.querySelectorAll(
                    'h1,h2,h3,h4,h5,h6,p,a,button,img,picture,video,canvas,svg'
                );

                for (const node of nodes) {
                    if (!visible(node)) continue;
                    bottom = Math.max(bottom, node.getBoundingClientRect().bottom);
                }
                return bottom;
            };

            const compactTrailingSpace = (section, threshold) => {
                if (!section) return false;

                const rect = section.getBoundingClientRect();
                const contentBottom = meaningfulBottom(section);
                const blank = rect.bottom - contentBottom;
                if (blank <= threshold) return false;

                section.style.setProperty('min-height', '0px', 'important');
                section.style.setProperty('height', 'auto', 'important');

                const style = getComputedStyle(section);
                const paddingBottom = parseFloat(style.paddingBottom) || 0;
                const marginBottom = parseFloat(style.marginBottom) || 0;

                if (paddingBottom > threshold * 0.45) {
                    section.style.setProperty('padding-bottom', '32px', 'important');
                }
                if (marginBottom > threshold * 0.45) {
                    section.style.setProperty('margin-bottom', '0px', 'important');
                }
                return true;
            };

            const compactLeadingSpace = (section, anchor, threshold) => {
                if (!section || !anchor) return false;

                const sectionRect = section.getBoundingClientRect();
                const anchorRect = anchor.getBoundingClientRect();
                const blank = anchorRect.top - sectionRect.top;
                if (blank <= threshold) return false;

                section.style.setProperty('min-height', '0px', 'important');
                section.style.setProperty('height', 'auto', 'important');

                const style = getComputedStyle(section);
                const paddingTop = parseFloat(style.paddingTop) || 0;
                const marginTop = parseFloat(style.marginTop) || 0;

                if (paddingTop > threshold * 0.45) {
                    section.style.setProperty('padding-top', '32px', 'important');
                }
                if (marginTop > threshold * 0.45) {
                    section.style.setProperty('margin-top', '0px', 'important');
                }
                return true;
            };

            let scheduled = false;
            const repair = () => {
                scheduled = false;
                try {
                    const ecosystem = findExact('Ecosystem');
                    const startModeling = findExact('Start modeling now');
                    if (!ecosystem || !startModeling) return;

                    const ecosystemSection = sectionFor(ecosystem, startModeling);
                    const startSection = sectionFor(startModeling, ecosystem);
                    const threshold = Math.max(110, window.innerHeight * 0.20);

                    compactTrailingSpace(ecosystemSection, threshold);
                    compactLeadingSpace(startSection, startModeling, threshold);
                } catch (_) {}
            };

            const schedule = () => {
                if (scheduled) return;
                scheduled = true;
                requestAnimationFrame(repair);
            };

            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', schedule, { once: true });
            } else {
                schedule();
            }

            window.addEventListener('resize', schedule, { passive: true });
            window.addEventListener('load', schedule, { passive: true });
            document.addEventListener('load', schedule, { capture: true, passive: true });

            const observe = () => {
                if (!document.documentElement) return;
                new MutationObserver(schedule).observe(document.documentElement, {
                    childList: true,
                    subtree: true
                });
            };

            if (document.documentElement) observe();
            else document.addEventListener('DOMContentLoaded', observe, { once: true });

            setTimeout(schedule, 250);
            setTimeout(schedule, 1000);
            setTimeout(schedule, 2500);
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

            if (attached.put(webView, true) == true) {
                if (isBlockDisplayUrl(webView.url)) webView.evaluateJavascript(SCRIPT, null)
                return@post
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, origins)
            }

            if (isBlockDisplayUrl(webView.url)) {
                webView.evaluateJavascript(SCRIPT, null)
                webView.postDelayed({
                    if (isBlockDisplayUrl(webView.url)) webView.evaluateJavascript(SCRIPT, null)
                }, 900L)
            }
        }
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

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null

        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
