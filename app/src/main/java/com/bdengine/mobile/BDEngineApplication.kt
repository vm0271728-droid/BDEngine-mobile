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
                const bridge = window.BDEngineDownloads;
                if (!bridge) return;

                if (window.__bdengineNativeDownloadInstalled) return;
                window.__bdengineNativeDownloadInstalled = true;

                const normalizeLabel = value => String(value || '')
                    .replace(/\s+/g, ' ')
                    .trim()
                    .toLowerCase();

                const clickedLabel = target => {
                    let node = target;
                    for (let i = 0; i < 6 && node; i++, node = node.parentElement) {
                        if (!node.getAttribute) continue;
                        const label = normalizeLabel(
                            node.getAttribute('aria-label') ||
                            node.getAttribute('title') ||
                            node.textContent || ''
                        );
                        if (label && label.length <= 120) return label;
                    }
                    return '';
                };

                const isSaveToDevice = label => {
                    const russian = label.includes('сохранить на устройство') &&
                        !label.includes('сохранить на устройство как');
                    const english = label.includes('save to device') &&
                        !label.includes('save to device as');
                    return russian || english;
                };

                const isProjectFileName = name => {
                    const normalized = String(name || '').toLowerCase();
                    return normalized.endsWith('.bdengine') || normalized.endsWith('.bdstudio');
                };

                document.addEventListener('click', event => {
                    if (isSaveToDevice(clickedLabel(event.target))) {
                        window.__bdengineNativeProjectSaveUntil = Date.now() + 5000;
                        try { bridge.armProjectSave(); } catch (_) {}
                    }
                }, true);

                const consumeProjectSave = () => {
                    const until = Number(window.__bdengineNativeProjectSaveUntil || 0);
                    const projectSave = until >= Date.now();
                    if (projectSave) window.__bdengineNativeProjectSaveUntil = 0;
                    return projectSave;
                };

                const transfer = (href, suggestedName) => {
                    if (!href || (!href.startsWith('blob:') && !href.startsWith('data:'))) {
                        return false;
                    }

                    const fileName = suggestedName || 'BDEngine-export';
                    const projectSave = consumeProjectSave() || isProjectFileName(fileName);

                    try {
                        if (href.startsWith('data:')) {
                            if (projectSave) {
                                bridge.saveProjectDataUrl(href, fileName, '');
                            } else {
                                bridge.saveDataUrl(href, fileName, '');
                            }
                            return true;
                        }

                        fetch(href)
                            .then(response => response.blob())
                            .then(blob => {
                                const reader = new FileReader();
                                reader.onloadend = () => {
                                    const dataUrl = String(reader.result || '');
                                    if (projectSave) {
                                        bridge.saveProjectDataUrl(dataUrl, fileName, blob.type || '');
                                    } else {
                                        bridge.saveDataUrl(dataUrl, fileName, blob.type || '');
                                    }
                                };
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

        private val FILE_SYSTEM_SAVE_SHIM_SCRIPT = """
            (() => {
                const bridge = window.BDEngineDownloads;
                if (!bridge) return;

                if (window.__bdengineNativeFileSystemSaveInstalled) return;
                window.__bdengineNativeFileSystemSaveInstalled = true;

                const safeSuggestedName = options => {
                    try {
                        const name = String(options && options.suggestedName || '').trim();
                        return name || 'BDEngine-project.bdengine';
                    } catch (_) {
                        return 'BDEngine-project.bdengine';
                    }
                };

                const normalizeWriteValue = value => {
                    if (!value || typeof value !== 'object' || typeof value.type !== 'string') {
                        return { action: 'write', data: value };
                    }

                    if (value.type === 'write') {
                        return { action: 'write', data: value.data };
                    }
                    if (value.type === 'truncate') {
                        return { action: 'truncate', size: Number(value.size || 0) };
                    }
                    if (value.type === 'seek') {
                        return { action: 'seek', position: Number(value.position || 0) };
                    }

                    return { action: 'write', data: value };
                };

                const createHandle = suggestedName => {
                    const state = { parts: [] };

                    return {
                        kind: 'file',
                        name: suggestedName,
                        queryPermission: async () => 'granted',
                        requestPermission: async () => 'granted',
                        isSameEntry: async other => other === this,
                        getFile: async () => new File(
                            state.parts,
                            suggestedName,
                            { type: 'application/octet-stream' }
                        ),
                        createWritable: async () => {
                            let closed = false;

                            return {
                                locked: false,
                                write: async value => {
                                    if (closed) throw new Error('Writable stream is closed');

                                    const command = normalizeWriteValue(value);
                                    if (command.action === 'seek') return;
                                    if (command.action === 'truncate') {
                                        if (command.size === 0) state.parts = [];
                                        return;
                                    }

                                    const data = command.data;
                                    if (data === undefined || data === null) return;
                                    state.parts.push(data);
                                },
                                seek: async () => {},
                                truncate: async size => {
                                    if (Number(size) === 0) state.parts = [];
                                },
                                abort: async () => {
                                    closed = true;
                                    state.parts = [];
                                },
                                close: async () => {
                                    if (closed) return;
                                    closed = true;

                                    const blob = new Blob(state.parts, {
                                        type: 'application/octet-stream'
                                    });

                                    await new Promise((resolve, reject) => {
                                        const reader = new FileReader();
                                        reader.onloadend = () => {
                                            try {
                                                const dataUrl = String(reader.result || '');
                                                if (!dataUrl) {
                                                    reject(new Error('Empty project data'));
                                                    return;
                                                }
                                                bridge.saveProjectDataUrl(
                                                    dataUrl,
                                                    suggestedName,
                                                    blob.type || 'application/octet-stream'
                                                );
                                                resolve();
                                            } catch (error) {
                                                reject(error);
                                            }
                                        };
                                        reader.onerror = () => reject(reader.error || new Error('Read failed'));
                                        reader.readAsDataURL(blob);
                                    });
                                }
                            };
                        }
                    };
                };

                try {
                    Object.defineProperty(window, 'showSaveFilePicker', {
                        configurable: true,
                        writable: true,
                        value: async options => createHandle(safeSuggestedName(options || {}))
                    });
                } catch (_) {
                    try {
                        window.showSaveFilePicker = async options =>
                            createHandle(safeSuggestedName(options || {}));
                    } catch (_) {}
                }
            })();
        """.trimIndent()

        private val MAIN_PAGE_FIXED_SCROLL_SCRIPT = """
            (() => {
                if (window.__bdengineFixedBodyScrollInstalled) return;
                window.__bdengineFixedBodyScrollInstalled = true;

                const install = () => {
                    try {
                        const html = document.documentElement;
                        const body = document.body;
                        if (!html || !body) return;

                        // Keep the browser/root viewport physically pinned. The body is
                        // the only vertical scrolling surface, so the whole page cannot
                        // drift upward while the user can still scroll through content.
                        html.style.setProperty('height', '100%', 'important');
                        html.style.setProperty('overflow', 'hidden', 'important');
                        html.style.setProperty('overscroll-behavior', 'none', 'important');

                        body.style.setProperty('height', '100%', 'important');
                        body.style.setProperty('max-height', '100%', 'important');
                        body.style.setProperty('overflow-x', 'hidden', 'important');
                        body.style.setProperty('overflow-y', 'auto', 'important');
                        body.style.setProperty('overscroll-behavior-x', 'none', 'important');
                        body.style.setProperty('overscroll-behavior-y', 'contain', 'important');
                        body.style.setProperty('-webkit-overflow-scrolling', 'touch', 'important');

                        if (window.scrollX !== 0 || window.scrollY !== 0) {
                            window.scrollTo(0, 0);
                        }
                    } catch (_) {}
                };

                install();
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', install, { once: true });
                }

                // This listener only pins the outer window. It never changes body.scrollTop.
                window.addEventListener('scroll', () => {
                    if (window.scrollX !== 0 || window.scrollY !== 0) {
                        window.scrollTo(0, 0);
                    }
                }, { passive: true });
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
        if (activity is DeviceFilePickerActivity) return
        attachWhenReady(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is DeviceFilePickerActivity) return
        attachWhenReady(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        downloadControllers.remove(activity)?.destroy()
    }

    private fun attachWhenReady(activity: Activity) {
        if (activity is DeviceFilePickerActivity) return
        if (downloadControllers.containsKey(activity)) return

        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (downloadControllers.containsKey(activity)) return@post

            val webView = findWebView(activity.window.decorView) ?: run {
                activity.window.decorView.postDelayed({ attachWhenReady(activity) }, 250L)
                return@post
            }

            val rootLayout = findRootFrameLayout(webView) ?: return@post

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }

            webView.webChromeClient = BDEngineWebChromeClient(activity)

            val controller = DownloadController(activity, rootLayout, webView)
            controller.attach()
            downloadControllers[activity] = controller

            controller.installPageBridge()
            installFileSystemSaveShim(webView)
            installMainPageFixedScroll(webView)
            webView.postDelayed({
                controller.installPageBridge()
                installFileSystemSaveShim(webView)
                installMainPageFixedScroll(webView)
            }, 500L)

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, DOWNLOAD_BRIDGE_SCRIPT, TRUSTED_ORIGINS)
                WebViewCompat.addDocumentStartJavaScript(webView, FILE_SYSTEM_SAVE_SHIM_SCRIPT, TRUSTED_ORIGINS)
                WebViewCompat.addDocumentStartJavaScript(webView, MAIN_PAGE_FIXED_SCROLL_SCRIPT, BLOCK_DISPLAY_ORIGINS)
            }
        }
    }

    private fun installFileSystemSaveShim(webView: WebView) {
        webView.evaluateJavascript(FILE_SYSTEM_SAVE_SHIM_SCRIPT, null)
    }

    private fun installMainPageFixedScroll(webView: WebView) {
        if (!isBlockDisplayUrl(webView.url)) return
        webView.evaluateJavascript(MAIN_PAGE_FIXED_SCROLL_SCRIPT, null)
    }

    private fun isBlockDisplayUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val host = android.net.Uri.parse(url).host.orEmpty().lowercase()
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
