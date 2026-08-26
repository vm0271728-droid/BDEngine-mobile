package com.bdengine.mobile

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.math.hypot
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var settingsButton: ImageView
    private lateinit var scaleTitle: TextView
    private lateinit var scaleSubtitle: TextView

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var scalePercent = DEFAULT_SCALE_PERCENT

    companion object {
        private const val BDE_URL = "https://block-display.com/editor"

        private const val PREFS_NAME = "bdengine_mobile_settings"
        private const val PREF_SCALE_PERCENT_V2 = "smallest_width_percent_v2"
        private const val PREF_GEAR_X = "gear_x"
        private const val PREF_GEAR_Y = "gear_y"

        // Requested mapping:
        // 30% = 600 dp, every 1% = 10 dp.
        // Therefore 0% = 300 dp and 100% = 1300 dp.
        private const val DEFAULT_SCALE_PERCENT = 30
        private const val DP_AT_ZERO = 300
        private const val DP_PER_PERCENT = 10

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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUi()

        scalePercent = preferences.getInt(
            PREF_SCALE_PERCENT_V2,
            DEFAULT_SCALE_PERCENT
        ).coerceIn(0, 100)

        rootLayout = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
        }

        webView = WebView(this).apply {
            pivotX = 0f
            pivotY = 0f
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }

        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootLayout)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = DESKTOP_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = false

            // Prevent accidental browser-like pinch zoom / elastic scaling.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        configureDesktopIdentity()
        createFloatingSettings()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
            }
        }

        webView.webChromeClient = WebChromeClient()

        rootLayout.post {
            applySmallestWidthScale(scalePercent)
            restoreGearPosition()
        }

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
            rootLayout.post { applySmallestWidthScale(scalePercent) }
        }
    }

    private fun createFloatingSettings() {
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            visibility = View.GONE
            elevation = dp(14).toFloat()
            background = roundedBackground(
                fillColor = Color.argb(245, 20, 22, 28),
                radius = dp(18).toFloat(),
                strokeColor = Color.argb(55, 255, 255, 255),
                strokeWidth = dp(1)
            )
        }

        val heading = TextView(this).apply {
            text = "Масштаб интерфейса"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        scaleTitle = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, 0)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        scaleSubtitle = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(170, 176, 188))
            setPadding(0, dp(2), 0, dp(8))
        }

        val seekBar = SeekBar(this).apply {
            max = 100
            progress = scalePercent
            progressTintList = ColorStateList.valueOf(Color.rgb(105, 214, 210))
            thumbTintList = ColorStateList.valueOf(Color.rgb(220, 250, 248))
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    scalePercent = progress.coerceIn(0, 100)
                    updateScaleLabels()

                    if (fromUser) {
                        applySmallestWidthScale(scalePercent)
                        saveScalePercent()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    saveScalePercent()
                }
            })
        }

        val helper = TextView(this).apply {
            text = "0% = 300 dp   •   100% = 1300 dp"
            textSize = 10.5f
            setTextColor(Color.rgb(126, 132, 146))
            setPadding(0, dp(4), 0, 0)
        }

        settingsPanel.addView(heading)
        settingsPanel.addView(scaleTitle)
        settingsPanel.addView(scaleSubtitle)
        settingsPanel.addView(
            seekBar,
            LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        settingsPanel.addView(helper)

        updateScaleLabels()

        rootLayout.addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        settingsButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(16).toFloat()
            background = roundedBackground(
                fillColor = Color.argb(242, 26, 29, 36),
                radius = dp(28).toFloat(),
                strokeColor = Color.argb(105, 105, 214, 210),
                strokeWidth = dp(1)
            )
        }

        rootLayout.addView(
            settingsButton,
            FrameLayout.LayoutParams(dp(56), dp(56))
        )

        enableGearDragging()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableGearDragging() {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        val dragThreshold = dp(6).toFloat()

        settingsButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = view.x
                    startY = view.y
                    dragging = false

                    view.animate()
                        .scaleX(0.94f)
                        .scaleY(0.94f)
                        .setDuration(80L)
                        .start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY

                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > dragThreshold) {
                        dragging = true
                        settingsPanel.visibility = View.GONE
                    }

                    if (dragging) {
                        val maxX = (rootLayout.width - view.width).coerceAtLeast(0).toFloat()
                        val maxY = (rootLayout.height - view.height).coerceAtLeast(0).toFloat()

                        view.x = (startX + dx).coerceIn(0f, maxX)
                        view.y = (startY + dy).coerceIn(0f, maxY)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100L)
                        .start()

                    if (dragging) {
                        saveGearPosition()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        toggleSettingsPanel()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun toggleSettingsPanel() {
        if (settingsPanel.visibility == View.VISIBLE) {
            settingsPanel.visibility = View.GONE
            return
        }

        settingsPanel.visibility = View.VISIBLE
        settingsPanel.post { positionSettingsPanelNearGear() }
    }

    private fun positionSettingsPanelNearGear() {
        if (!::settingsPanel.isInitialized || !::settingsButton.isInitialized) return

        val margin = dp(12).toFloat()
        val panelWidth = settingsPanel.width.toFloat()
        val panelHeight = settingsPanel.height.toFloat()
        val rootWidth = rootLayout.width.toFloat()
        val rootHeight = rootLayout.height.toFloat()

        val placeLeft = settingsButton.x > rootWidth / 2f
        val wantedX = if (placeLeft) {
            settingsButton.x - panelWidth - margin
        } else {
            settingsButton.x + settingsButton.width + margin
        }

        val wantedY = settingsButton.y + settingsButton.height / 2f - panelHeight / 2f

        settingsPanel.x = wantedX.coerceIn(
            margin,
            (rootWidth - panelWidth - margin).coerceAtLeast(margin)
        )
        settingsPanel.y = wantedY.coerceIn(
            margin,
            (rootHeight - panelHeight - margin).coerceAtLeast(margin)
        )
    }

    /**
     * Local analogue of Android's Developer options -> Smallest width.
     *
     * Because the app is locked to landscape, the screen height is normally the
     * short side. We create a larger/smaller logical WebView canvas and scale it
     * back to the physical window. The website therefore sees the same kind of
     * logical workspace change that changing Android's smallest-width dp causes,
     * while the rest of the phone remains untouched.
     */
    private fun applySmallestWidthScale(progress: Int) {
        if (!::rootLayout.isInitialized || !::webView.isInitialized) return

        rootLayout.post {
            val rootWidthPx = rootLayout.width.toFloat()
            val rootHeightPx = rootLayout.height.toFloat()
            if (rootWidthPx <= 0f || rootHeightPx <= 0f) return@post

            val density = resources.displayMetrics.density
            val targetSmallestDp = smallestWidthDpFor(progress).toFloat()
            val physicalShortSidePx = minOf(rootWidthPx, rootHeightPx)
            val virtualShortSidePx = targetSmallestDp * density
            val viewScale = physicalShortSidePx / virtualShortSidePx

            if (viewScale <= 0f) return@post

            val virtualWidthPx = (rootWidthPx / viewScale).roundToInt().coerceAtLeast(1)
            val virtualHeightPx = (rootHeightPx / viewScale).roundToInt().coerceAtLeast(1)

            val params = webView.layoutParams as FrameLayout.LayoutParams
            if (params.width != virtualWidthPx || params.height != virtualHeightPx) {
                params.width = virtualWidthPx
                params.height = virtualHeightPx
                webView.layoutParams = params
            }

            webView.pivotX = 0f
            webView.pivotY = 0f
            webView.scaleX = viewScale
            webView.scaleY = viewScale
            webView.translationX = 0f
            webView.translationY = 0f
            webView.requestLayout()
        }
    }

    private fun smallestWidthDpFor(progress: Int): Int {
        return DP_AT_ZERO + progress.coerceIn(0, 100) * DP_PER_PERCENT
    }

    private fun updateScaleLabels() {
        if (!::scaleTitle.isInitialized || !::scaleSubtitle.isInitialized) return
        val targetDp = smallestWidthDpFor(scalePercent)
        scaleTitle.text = "$targetDp dp"
        scaleSubtitle.text = "$scalePercent%  •  шаг 10 dp"
    }

    private fun saveScalePercent() {
        preferences.edit()
            .putInt(PREF_SCALE_PERCENT_V2, scalePercent)
            .apply()
    }

    private fun restoreGearPosition() {
        if (!::settingsButton.isInitialized) return

        val savedX = preferences.getFloat(PREF_GEAR_X, -1f)
        val savedY = preferences.getFloat(PREF_GEAR_Y, -1f)

        val defaultX = (rootLayout.width - settingsButton.width - dp(14)).toFloat()
        val defaultY = (rootLayout.height - settingsButton.height).coerceAtLeast(0) / 2f

        val maxX = (rootLayout.width - settingsButton.width).coerceAtLeast(0).toFloat()
        val maxY = (rootLayout.height - settingsButton.height).coerceAtLeast(0).toFloat()

        settingsButton.x = (if (savedX >= 0f) savedX else defaultX).coerceIn(0f, maxX)
        settingsButton.y = (if (savedY >= 0f) savedY else defaultY).coerceIn(0f, maxY)
    }

    private fun saveGearPosition() {
        if (!::settingsButton.isInitialized) return
        preferences.edit()
            .putFloat(PREF_GEAR_X, settingsButton.x)
            .putFloat(PREF_GEAR_Y, settingsButton.y)
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
        saveScalePercent()
        saveGearPosition()
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideSystemUi()
        rootLayout.post { applySmallestWidthScale(scalePercent) }
    }

    override fun onPause() {
        saveScalePercent()
        saveGearPosition()
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        saveScalePercent()
        saveGearPosition()
        CookieManager.getInstance().flush()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
        super.onDestroy()
    }

    private fun roundedBackground(
        fillColor: Int,
        radius: Float,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radius
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
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
