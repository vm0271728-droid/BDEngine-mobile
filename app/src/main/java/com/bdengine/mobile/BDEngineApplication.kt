package com.bdengine.mobile

import android.app.Activity
import android.app.Application
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

        private val TRUSTED_ORIGINS = setOf(
            "https://block-display.com",
            "https://bdengine.app",
            "https://beta.bdengine.app"
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
            webView.postDelayed({ controller.installPageBridge() }, 500L)

            // Every later BDEngine navigation, including the editor domain.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    DOWNLOAD_BRIDGE_SCRIPT,
                    TRUSTED_ORIGINS
                )
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
