package com.bdengine.mobile

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

class BDEngineApplication : Application(), Application.ActivityLifecycleCallbacks {

    private val downloadControllers = WeakHashMap<Activity, DownloadController>()

    companion object {
        private val DOWNLOAD_BRIDGE_SCRIPT = """
            (() => {
                if (window.__bdengineNativeDownloadInstalled) return;
                window.__bdengineNativeDownloadInstalled = true;

                const bridge = window.BDEngineDownloads;
                if (!bridge) return;

                const transfer = (href, suggestedName) => {
                    if (!href || (!href.startsWith('blob:') && !href.startsWith('data:'))) {
                        return false;
                    }

                    const fileName = suggestedName || 'BDEngine-export';

                    try {
                        if (href.startsWith('data:')) {
                            bridge.saveDataUrl(href, fileName, '');
                            return true;
                        }

                        fetch(href)
                            .then(response => response.blob())
                            .then(blob => {
                                const reader = new FileReader();
                                reader.onloadend = () => bridge.saveDataUrl(
                                    String(reader.result || ''),
                                    fileName,
                                    blob.type || ''
                                );
                                reader.onerror = () => bridge.reportError(fileName);
                                reader.readAsDataURL(blob);
                            })
                            .catch(() => bridge.reportError(fileName));
                    } catch (_) {
                        bridge.reportError(fileName);
                    }

                    return true;
                };

                const nativeClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    const href = this.href || this.getAttribute('href') || '';
                    const name = this.download || this.getAttribute('download') || '';
                    if (transfer(href, name)) return;
                    return nativeClick.apply(this, arguments);
                };

                document.addEventListener('click', event => {
                    const target = event.target;
                    if (!target || target.nodeType !== 1 || !target.closest) return;
                    const anchor = target.closest('a');
                    if (!anchor) return;

                    const href = anchor.href || anchor.getAttribute('href') || '';
                    const name = anchor.download || anchor.getAttribute('download') || '';
                    if (!transfer(href, name)) return;

                    event.preventDefault();
                    event.stopImmediatePropagation();
                }, true);
            })();
        """.trimIndent()

        private val MAIN_PAGE_SCROLL_GUARD_SCRIPT = """
            (() => {
                if (window.__bdengineRootScrollGuardInstalled) return;
                window.__bdengineRootScrollGuardInstalled = true;

                const applyRootRules = () => {
                    try {
                        const html = document.documentElement;
                        const body = document.body;

                        if (html) {
                            html.style.setProperty('overflow-x', 'hidden', 'important');
                            html.style.setProperty('overscroll-behavior-x', 'none', 'important');
                            html.style.setProperty('overscroll-behavior-y', 'none', 'important');
                        }

                        if (body) {
                            body.style.setProperty('overflow-x', 'hidden', 'important');
                            body.style.setProperty('overscroll-behavior-x', 'none', 'important');
                            body.style.setProperty('overscroll-behavior-y', 'none', 'important');
                        }
                    } catch (_) {}
                };

                const clampRootScroll = () => {
                    try {
                        const html = document.documentElement;
                        const body = document.body;
                        const documentHeight = Math.max(
                            html ? html.scrollHeight : 0,
                            body ? body.scrollHeight : 0
                        );
                        const maxY = Math.max(0, documentHeight - window.innerHeight);
                        const safeY = Math.min(Math.max(window.scrollY || 0, 0), maxY);

                        if (window.scrollX !== 0 || Math.abs((window.scrollY || 0) - safeY) > 0.5) {
                            window.scrollTo(0, safeY);
                        }
                    } catch (_) {}
                };

                applyRootRules();

                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', () => {
                        applyRootRules();
                        clampRootScroll();
                    }, { once: true });
                } else {
                    clampRootScroll();
                }

                window.addEventListener('scroll', clampRootScroll, { passive: true });
                window.addEventListener('resize', clampRootScroll, { passive: true });
            })();
        """.trimIndent()

        private val TRUSTED_ORIGINS = setOf(
            "https://block-display.com",
            "https://bdengine.app",
            "https://beta.bdengine.app"
        )

        private val BLOCK_DISPLAY_ORIGINS = setOf(
            "https://block-display.com"
        )
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        attachWhenReady(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        attachWhenReady(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        downloadControllers.remove(activity)?.destroy()
    }

    private fun attachWhenReady(activity: Activity) {
        if (downloadControllers.containsKey(activity)) return

        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (downloadControllers.containsKey(activity)) return@post

            val webView = findWebView(activity.window.decorView) ?: run {
                activity.window.decorView.postDelayed({ attachWhenReady(activity) }, 250L)
                return@post
            }

            val rootLayout = findRootFrameLayout(webView) ?: return@post

            // Chromium decides the exact CPU thread allocation itself. These are the
            // strongest public Android controls available to keep its renderer fast.
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }

            val controller = DownloadController(activity, rootLayout, webView)
            controller.attach()
            downloadControllers[activity] = controller

            // Current document.
            controller.installPageBridge()
            installMainPageScrollGuard(webView)
            webView.postDelayed({
                controller.installPageBridge()
                installMainPageScrollGuard(webView)
            }, 500L)

            // Every later BDEngine navigation, including the editor domain.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    DOWNLOAD_BRIDGE_SCRIPT,
                    TRUSTED_ORIGINS
                )

                // The public/main site must remain vertically scrollable. We only stop
                // root overscroll and horizontal drift that can expose an empty area.
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    MAIN_PAGE_SCROLL_GUARD_SCRIPT,
                    BLOCK_DISPLAY_ORIGINS
                )
            }
        }
    }

    private fun installMainPageScrollGuard(webView: WebView) {
        if (!isBlockDisplayUrl(webView.url)) return
        webView.evaluateJavascript(MAIN_PAGE_SCROLL_GUARD_SCRIPT, null)
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

    private fun findRootFrameLayout(webView: WebView): FrameLayout? {
        var parent = webView.parent
        while (parent is ViewGroup) {
            if (parent is FrameLayout && parent.parent != null) return parent
            parent = parent.parent
        }
        return webView.parent as? FrameLayout
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
