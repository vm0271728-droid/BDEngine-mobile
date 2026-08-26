package com.bdengine.mobile

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var settingsButton: ImageView
    private lateinit var scaleValue: TextView
    private lateinit var usageValue: TextView
    private lateinit var splashOverlay: FrameLayout
    private lateinit var splashText: TextView
    private lateinit var usageTracker: AppUsageTracker

    private val mainHandler = Handler(Looper.getMainLooper())

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var scalePercent = DEFAULT_SCALE_PERCENT
    private var hasResumedOnce = false
    private var splashDismissRunnable: Runnable? = null

    private val usageTicker = object : Runnable {
        override fun run() {
            if (::settingsPanel.isInitialized && settingsPanel.visibility == View.VISIBLE) {
                updateUsageValue()
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val usageCheckpoint = object : Runnable {
        override fun run() {
            if (::usageTracker.isInitialized) {
                usageTracker.checkpoint()
                mainHandler.postDelayed(this, USAGE_CHECKPOINT_MS)
            }
        }
    }

    companion object {
        private const val BDE_URL = "https://block-display.com/editor"

        private const val PREFS_NAME = "bdengine_mobile_settings"
        private const val PREF_SCALE_PERCENT_V2 = "smallest_width_percent_v2"
        private const val PREF_GEAR_X = "gear_x"
        private const val PREF_GEAR_Y = "gear_y"

        // 30% = 600 dp, every 1% = 10 dp.
        private const val DEFAULT_SCALE_PERCENT = 30
        private const val DP_AT_ZERO = 300
        private const val DP_PER_PERCENT = 10

        private const val SETTINGS_BUTTON_SIZE_DP = 40
        private const val SPLASH_DURATION_MS = 3000L
        private const val USAGE_CHECKPOINT_MS = 30_000L

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

        private val HIDE_SIGNUP_BANNER_SCRIPT = """
            (() => {
                const marker = 'Sign up to create and share content.';

                const hideBanner = () => {
                    try {
                        const elements = document.querySelectorAll('body *');

                        for (const element of elements) {
                            const text = (element.textContent || '')
                                .replace(/\s+/g, ' ')
                                .trim();

                            if (!text.includes(marker)) continue;

                            let current = element;
                            for (let i = 0; i < 6 && current; i++, current = current.parentElement) {
                                const style = getComputedStyle(current);
                                if (style.position === 'fixed' || style.position === 'sticky') {
                                    current.style.setProperty('display', 'none', 'important');
                                    current.style.setProperty('visibility', 'hidden', 'important');
                                    current.style.setProperty('pointer-events', 'none', 'important');
                                    break;
                                }
                            }
                        }
                    } catch (_) {}
                };

                let pending = false;
                const schedule = () => {
                    if (pending) return;
                    pending = true;
                    requestAnimationFrame(() => {
                        pending = false;
                        hideBanner();
                    });
                };

                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', hideBanner, { once: true });
                } else {
                    hideBanner();
                }

                new MutationObserver(schedule).observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class', 'style']
                });

                window.addEventListener('scroll', schedule, { passive: true });
            })();
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUi()
        usageTracker = AppUsageTracker(this)

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
        createSplashScreen(usageTracker.loadingTextForEntry())

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(HIDE_SIGNUP_BANNER_SCRIPT, null)
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
                when {
                    splashOverlay.visibility == View.VISIBLE -> finish()
                    settingsPanel.visibility == View.VISIBLE -> hideSettingsPanel()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
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

    private fun createSplashScreen(initialText: String) {
        splashOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            elevation = dp(100).toFloat()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            translationY = dp(34).toFloat()
        }

        splashText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            maxWidth = dp(560)
            setPadding(dp(24), 0, dp(24), 0)
        }

        val spinner = SegmentSpinnerView(this)

        content.addView(
            splashText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            spinner,
            LinearLayout.LayoutParams(dp(60), dp(60)).apply {
                topMargin = dp(20)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        splashOverlay.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        rootLayout.addView(
            splashOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        showSplash(initialText)
    }

    private fun showSplash(text: String) {
        if (!::splashOverlay.isInitialized) return

        splashDismissRunnable?.let(mainHandler::removeCallbacks)
        hideSettingsPanel()
        settingsButton.visibility = View.GONE
        splashText.text = text
        splashOverlay.visibility = View.VISIBLE
        splashOverlay.bringToFront()

        val dismissRunnable = Runnable {
            splashOverlay.visibility = View.GONE
            settingsButton.visibility = View.VISIBLE
            settingsButton.bringToFront()
            rootLayout.post { restoreGearPosition() }
        }

        splashDismissRunnable = dismissRunnable
        mainHandler.postDelayed(dismissRunnable, SPLASH_DURATION_MS)
    }

    private fun createFloatingSettings() {
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
            elevation = dp(14).toFloat()
            background = roundedBackground(
                fillColor = Color.argb(246, 18, 20, 25),
                radius = dp(16).toFloat(),
                strokeColor = Color.argb(48, 255, 255, 255),
                strokeWidth = dp(1)
            )
        }

        val heading = TextView(this).apply {
            text = "Настройки"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }

        val scaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val scaleLabel = TextView(this).apply {
            text = "Масштаб"
            textSize = 13.5f
            setTextColor(Color.rgb(215, 219, 228))
        }

        scaleValue = TextView(this).apply {
            textSize = 13.5f
            setTextColor(Color.rgb(105, 214, 210))
            gravity = Gravity.END
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        scaleRow.addView(
            scaleLabel,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        scaleRow.addView(scaleValue)

        val seekBar = SeekBar(this).apply {
            max = 100
            progress = scalePercent
            progressTintList = ColorStateList.valueOf(Color.rgb(105, 214, 210))
            thumbTintList = ColorStateList.valueOf(Color.rgb(220, 250, 248))
            setPadding(0, dp(3), 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    scalePercent = progress.coerceIn(0, 100)
                    updateScaleValue()

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

        val divider = View(this).apply {
            setBackgroundColor(Color.argb(32, 255, 255, 255))
        }

        val usageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
        }

        val usageLabel = TextView(this).apply {
            text = "В приложении"
            textSize = 13.5f
            setTextColor(Color.rgb(215, 219, 228))
        }

        usageValue = TextView(this).apply {
            textSize = 13.5f
            gravity = Gravity.END
            setTextColor(Color.rgb(170, 176, 188))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        usageRow.addView(
            usageLabel,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        usageRow.addView(usageValue)

        settingsPanel.addView(heading)
        settingsPanel.addView(
            scaleRow,
            LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        settingsPanel.addView(
            seekBar,
            LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        settingsPanel.addView(
            divider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(7)
            }
        )
        settingsPanel.addView(
            usageRow,
            LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        updateScaleValue()
        updateUsageValue()

        rootLayout.addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        settingsButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            elevation = dp(12).toFloat()
            background = roundedBackground(
                fillColor = Color.argb(244, 24, 27, 33),
                radius = dp(20).toFloat(),
                strokeColor = Color.argb(90, 105, 214, 210),
                strokeWidth = dp(1)
            )
        }

        rootLayout.addView(
            settingsButton,
            FrameLayout.LayoutParams(
                dp(SETTINGS_BUTTON_SIZE_DP),
                dp(SETTINGS_BUTTON_SIZE_DP)
            )
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
                        hideSettingsPanel()
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
            hideSettingsPanel()
            return
        }

        if (splashOverlay.visibility == View.VISIBLE) return

        updateUsageValue()
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.bringToFront()
        settingsButton.bringToFront()
        settingsPanel.post { positionSettingsPanelNearGear() }

        mainHandler.removeCallbacks(usageTicker)
        mainHandler.post(usageTicker)
    }

    private fun hideSettingsPanel() {
        if (::settingsPanel.isInitialized) {
            settingsPanel.visibility = View.GONE
        }
        mainHandler.removeCallbacks(usageTicker)
    }

    private fun positionSettingsPanelNearGear() {
        if (!::settingsPanel.isInitialized || !::settingsButton.isInitialized) return

        val margin = dp(10).toFloat()
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

    private fun updateScaleValue() {
        if (!::scaleValue.isInitialized) return
        scaleValue.text = "${smallestWidthDpFor(scalePercent)} dp"
    }

    private fun updateUsageValue() {
        if (!::usageValue.isInitialized || !::usageTracker.isInitialized) return
        usageValue.text = formatUsageDuration(usageTracker.totalUsageMsNow())
    }

    private fun formatUsageDuration(milliseconds: Long): String {
        var totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        val days = totalSeconds / 86_400L
        totalSeconds %= 86_400L
        val hours = totalSeconds / 3_600L
        totalSeconds %= 3_600L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L

        val clock = String.format(
            Locale.getDefault(),
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )

        return if (days > 0L) "${days}д $clock" else clock
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
        usageTracker.checkpoint()
        saveScalePercent()
        saveGearPosition()
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        webView.onResume()
        hideSystemUi()
        usageTracker.startSession()

        mainHandler.removeCallbacks(usageCheckpoint)
        mainHandler.postDelayed(usageCheckpoint, USAGE_CHECKPOINT_MS)

        if (hasResumedOnce) {
            showSplash(usageTracker.loadingTextForEntry())
        } else {
            hasResumedOnce = true
        }

        rootLayout.post { applySmallestWidthScale(scalePercent) }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(usageTicker)
        mainHandler.removeCallbacks(usageCheckpoint)
        usageTracker.stopSessionAndRecordExit()
        saveScalePercent()
        saveGearPosition()
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        splashDismissRunnable?.let(mainHandler::removeCallbacks)
        mainHandler.removeCallbacks(usageTicker)
        mainHandler.removeCallbacks(usageCheckpoint)
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
