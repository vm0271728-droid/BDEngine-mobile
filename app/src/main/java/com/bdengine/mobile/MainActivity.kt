package com.bdengine.mobile

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var resolutionValue: TextView

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var resolutionScale = DEFAULT_RESOLUTION_SCALE

    // Scale factor currently applied on top of WebView's own desktop auto-fit scale.
    // It is reset to 1.0 whenever a new page starts loading.
    private var appliedWorkspaceFactor = 1f

    companion object {
        private const val BDE_URL = "https://block-display.com/editor"
        private const val PREFS_NAME = "bdengine_mobile_settings"
        private const val PREF_RESOLUTION_SCALE = "resolution_scale"
        private const val DEFAULT_RESOLUTION_SCALE = 0

        // User-facing behavior:
        // 0%   = slightly larger than the original automatic desktop fit.
        // 100% = considerably more workspace / smaller interface.
        // We deliberately do NOT touch the site's meta viewport or CSS layout.
        private const val FACTOR_AT_ZERO = 1.12f
        private const val FACTOR_AT_HUNDRED = 0.62f

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0.0.0 Safari/537.36"

        private val DESKTOP_IDENTITY_SCRIPT = """
            (() => {
                try {
                    Object.defineProperty(navigator, 'platform', {
                        configurable: true,
                        get: () => 'Win32'
                    });

                    if (navigator.userAgentData) {
                        const original = navigator.userAgentData;
                        const desktopUAData = new Proxy(original, {
                            get(target, prop) {
                                if (prop === 'mobile') return false;
                                if (prop === 'platform') return 'Windows';
                                const value = Reflect.get(target, prop, target);
                                return typeof value === 'function' ? value.bind(target) : value;
                            }
                        });

                        Object.defineProperty(navigator, 'userAgentData', {
                            configurable: true,
                            get: () => desktopUAData
                        });
                    }
                } catch (_) {}
            })();
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUi()
        resolutionScale = preferences.getInt(
            PREF_RESOLUTION_SCALE,
            DEFAULT_RESOLUTION_SCALE
        ).coerceIn(0, 100)

        val root = FrameLayout(this)
        webView = WebView(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = DESKTOP_USER_AGENT

            // This is the important part: let WebView build a normal wide desktop
            // viewport and automatically fit it to the phone. The floating slider
            // only changes the native page scale afterwards.
            useWideViewPort = true
            loadWithOverviewMode = true

            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        configureDesktopIdentity()
        createFloatingSettings(root)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // A navigation creates a fresh auto-fit baseline.
                appliedWorkspaceFactor = 1f
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Give Chromium a moment to finish its own overview/desktop fit,
                // then apply the user's saved workspace setting on top of it.
                webView.postDelayed({
                    applyWorkspaceScale(resolutionScale)
                }, 250L)

                CookieManager.getInstance().flush()
            }
        }

        webView.webChromeClient = WebChromeClient()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsPanel.visibility == View.VISIBLE) {
                    settingsPanel.visibility = View.GONE
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(BDE_URL)
        } else {
            webView.restoreState(savedInstanceState)
            // restoreState can restore the previous browser scale as well. Treat it
            // as the baseline and re-apply the saved workspace value after layout.
            appliedWorkspaceFactor = 1f
            webView.postDelayed({ applyWorkspaceScale(resolutionScale) }, 400L)
        }
    }

    private fun createFloatingSettings(root: FrameLayout) {
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
            elevation = dp(10).toFloat()
            background = roundedBackground(Color.argb(235, 28, 28, 32), dp(14).toFloat())
        }

        val title = TextView(this).apply {
            text = "Масштаб интерфейса"
            textSize = 14f
            setTextColor(Color.WHITE)
        }

        resolutionValue = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(2))
        }

        val seekBar = SeekBar(this).apply {
            max = 100
            progress = resolutionScale
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    resolutionScale = progress.coerceIn(0, 100)
                    updateResolutionLabel()

                    if (fromUser) {
                        applyWorkspaceScale(resolutionScale)
                        saveResolutionScale()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    saveResolutionScale()
                }
            })
        }

        settingsPanel.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        settingsPanel.addView(
            resolutionValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        settingsPanel.addView(
            seekBar,
            LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        updateResolutionLabel()

        root.addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                marginEnd = dp(70)
            }
        )

        val gearButton = TextView(this).apply {
            text = "⚙"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            elevation = dp(12).toFloat()
            background = roundedBackground(Color.argb(235, 35, 35, 40), dp(24).toFloat())
            setOnClickListener {
                settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            }
        }

        root.addView(
            gearButton,
            FrameLayout.LayoutParams(
                dp(50),
                dp(50),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                marginEnd = dp(12)
            }
        )
    }

    private fun applyWorkspaceScale(progress: Int) {
        if (!::webView.isInitialized) return

        val targetFactor = workspaceFactorFor(progress)
        val currentFactor = appliedWorkspaceFactor.coerceAtLeast(0.01f)
        val relativeFactor = targetFactor / currentFactor

        // zoomBy changes Chromium's native page scale without rewriting the site's
        // viewport, styles, media queries or DOM. This keeps BDEngine's desktop
        // layout intact while changing how much workspace fits on screen.
        try {
            webView.zoomBy(relativeFactor.coerceIn(0.01f, 100f))
            appliedWorkspaceFactor = targetFactor
        } catch (_: IllegalArgumentException) {
            // Extremely defensive fallback for vendor WebView implementations.
        }
    }

    private fun workspaceFactorFor(progress: Int): Float {
        val fraction = progress.coerceIn(0, 100) / 100f
        return FACTOR_AT_ZERO + (FACTOR_AT_HUNDRED - FACTOR_AT_ZERO) * fraction
    }

    private fun updateResolutionLabel() {
        if (!::resolutionValue.isInitialized) return
        resolutionValue.text = "$resolutionScale%"
    }

    private fun saveResolutionScale() {
        preferences.edit()
            .putInt(PREF_RESOLUTION_SCALE, resolutionScale)
            .apply()
    }

    private fun configureDesktopIdentity() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val metadata = UserAgentMetadata.Builder()
                .setMobile(false)
                .setPlatform("Windows")
                .setPlatformVersion("10.0.0")
                .build()

            WebSettingsCompat.setUserAgentMetadata(webView.settings, metadata)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                DESKTOP_IDENTITY_SCRIPT,
                setOf("https://block-display.com")
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveResolutionScale()
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideSystemUi()
    }

    override fun onPause() {
        saveResolutionScale()
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        saveResolutionScale()
        CookieManager.getInstance().flush()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
        super.onDestroy()
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
