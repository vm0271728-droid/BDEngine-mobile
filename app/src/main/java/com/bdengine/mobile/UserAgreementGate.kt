package com.bdengine.mobile

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Initializes the first-run user agreement without coupling the gate to MainActivity.
 * The provider is private and exists only so it can register lifecycle callbacks early.
 */
class AgreementInitializerProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        UserAgreementGate.register(app)
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

private object UserAgreementGate {

    private const val PREFS_NAME = "bdengine_mobile_settings"
    private const val PREF_AGREEMENT_VERSION = "user_agreement_version"
    private const val AGREEMENT_VERSION = 1
    private const val SHOW_DELAY_MS = 3_250L

    private val accent = Color.rgb(105, 214, 210)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = WeakHashMap<Activity, Runnable>()
    private val active = WeakHashMap<Activity, FrameLayout>()

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
                override fun onActivityResumed(activity: Activity) {
                    if (activity !is MainActivity) return
                    schedule(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    pending.remove(activity)?.let(handler::removeCallbacks)
                }

                override fun onActivityDestroyed(activity: Activity) {
                    pending.remove(activity)?.let(handler::removeCallbacks)
                    active.remove(activity)?.let { overlay ->
                        (overlay.parent as? ViewGroup)?.removeView(overlay)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            }
        )
    }

    private fun schedule(activity: Activity) {
        if (isAccepted(activity)) return
        if (active.containsKey(activity) || pending.containsKey(activity)) return

        val runnable = Runnable {
            pending.remove(activity)
            if (activity.isFinishing || activity.isDestroyed || isAccepted(activity)) return@Runnable
            showAgreement(activity)
        }
        pending[activity] = runnable
        handler.postDelayed(runnable, SHOW_DELAY_MS)
    }

    private fun isAccepted(context: Context): Boolean {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_AGREEMENT_VERSION, 0) >= AGREEMENT_VERSION
    }

    private fun accept(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_AGREEMENT_VERSION, AGREEMENT_VERSION)
            .apply()
    }

    private fun showAgreement(activity: Activity) {
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (active.containsKey(activity)) return

        val strings = AppLocale.strings(activity)
        var detailsOverlay: FrameLayout? = null
        var backCallback: OnBackPressedCallback? = null

        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(198, 0, 0, 0))
            isClickable = true
            isFocusable = true
            elevation = dp(activity, 180).toFloat()
            alpha = 0f
        }

        val card = FrameLayout(activity).apply {
            background = panelBackground(fill = Color.BLACK, stroke = accent)
            elevation = dp(activity, 8).toFloat()
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 20))
        }

        val title = TextView(activity).apply {
            text = strings.agreementTitle
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

        val introText = strings.agreementIntro
        val linkText = strings.agreementLink
        val linkStart = introText.lastIndexOf(linkText)
        val intro = SpannableString(introText).apply {
            if (linkStart >= 0) {
                setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            if (detailsOverlay == null) {
                                detailsOverlay = showAgreementDetails(
                                    activity = activity,
                                    host = host,
                                    parentOverlay = overlay,
                                    onClosed = { detailsOverlay = null }
                                )
                            }
                        }

                        override fun updateDrawState(ds: android.text.TextPaint) {
                            ds.color = accent
                            ds.isUnderlineText = false
                            ds.typeface = Typeface.create(ds.typeface, Typeface.BOLD)
                        }
                    },
                    linkStart,
                    linkStart + linkText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        val description = TextView(activity).apply {
            text = intro
            textSize = 13.5f
            setTextColor(Color.rgb(205, 210, 220))
            setLineSpacing(0f, 1.16f)
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            setPadding(0, dp(activity, 13), 0, 0)
        }

        val divider = View(activity).apply {
            setBackgroundColor(Color.argb(48, 105, 214, 210))
        }

        val checkBox = CheckBox(activity).apply {
            text = strings.agreementCheck
            textSize = 13.5f
            setTextColor(Color.rgb(225, 228, 235))
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    accent,
                    Color.rgb(104, 110, 120)
                )
            )
            setPadding(0, dp(activity, 4), 0, dp(activity, 2))
        }

        val acceptButton = TextView(activity).apply {
            text = strings.agreementOk
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            isEnabled = false
        }

        fun updateAcceptState(enabled: Boolean) {
            acceptButton.isEnabled = enabled
            acceptButton.setTextColor(if (enabled) accent else Color.rgb(105, 110, 120))
            acceptButton.background = panelBackground(
                fill = Color.BLACK,
                stroke = if (enabled) accent else Color.rgb(62, 66, 74)
            )
            acceptButton.alpha = if (enabled) 1f else 0.58f
        }

        checkBox.setOnCheckedChangeListener { _, checked ->
            updateAcceptState(checked)
        }
        updateAcceptState(false)

        acceptButton.setOnClickListener {
            if (!checkBox.isChecked) return@setOnClickListener
            accept(activity)
            backCallback?.remove()
            detailsOverlay?.let { details -> host.removeView(details) }
            detailsOverlay = null

            overlay.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction {
                    active.remove(activity)
                    host.removeView(overlay)
                }
                .start()
        }

        content.addView(title)
        content.addView(description)
        content.addView(
            divider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                topMargin = dp(activity, 17)
                bottomMargin = dp(activity, 8)
            }
        )
        content.addView(checkBox)
        content.addView(
            acceptButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 44)
            ).apply {
                topMargin = dp(activity, 12)
            }
        )

        card.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addInnerEdgeGlow(activity, card)

        val availableWidth = (host.width - dp(activity, 36)).coerceAtLeast(dp(activity, 300))
        val cardWidth = minOf(dp(activity, 500), availableWidth)
        overlay.addView(
            card,
            FrameLayout.LayoutParams(
                cardWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        host.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        active[activity] = overlay
        overlay.bringToFront()

        if (activity is AppCompatActivity) {
            backCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val details = detailsOverlay
                    if (details != null) {
                        host.removeView(details)
                        detailsOverlay = null
                    } else {
                        activity.finish()
                    }
                }
            }.also { callback ->
                activity.onBackPressedDispatcher.addCallback(activity, callback)
            }
        }

        overlay.animate().alpha(1f).setDuration(180L).start()
    }

    private fun showAgreementDetails(
        activity: Activity,
        host: FrameLayout,
        parentOverlay: FrameLayout,
        onClosed: () -> Unit
    ): FrameLayout {
        val strings = AppLocale.strings(activity)
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(218, 0, 0, 0))
            isClickable = true
            isFocusable = true
            elevation = parentOverlay.elevation + dp(activity, 20)
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 22), dp(activity, 18), dp(activity, 22), dp(activity, 18))
            background = panelBackground(fill = Color.BLACK, stroke = accent)
        }

        val title = TextView(activity).apply {
            text = strings.agreementTitle
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

        val agreementText = TextView(activity).apply {
            text = strings.agreementFull
            textSize = 12.8f
            setTextColor(Color.rgb(205, 210, 220))
            setLineSpacing(0f, 1.14f)
            setTextIsSelectable(true)
            setPadding(0, dp(activity, 8), dp(activity, 4), dp(activity, 12))
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(activity, 18))
            addView(
                agreementText,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val closeButton = TextView(activity).apply {
            text = strings.agreementClose
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            background = panelBackground(fill = Color.BLACK, stroke = accent)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                host.removeView(overlay)
                onClosed()
            }
        }

        card.addView(title)
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(activity, 8)
            }
        )
        card.addView(
            closeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 42)
            ).apply {
                topMargin = dp(activity, 10)
            }
        )

        val availableWidth = (host.width - dp(activity, 34)).coerceAtLeast(dp(activity, 320))
        val availableHeight = (host.height - dp(activity, 30)).coerceAtLeast(dp(activity, 240))
        val cardWidth = minOf(dp(activity, 690), availableWidth)
        val cardHeight = minOf(dp(activity, 430), availableHeight)

        overlay.addView(
            card,
            FrameLayout.LayoutParams(cardWidth, cardHeight, Gravity.CENTER)
        )

        host.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.bringToFront()
        return overlay
    }

    private fun addInnerEdgeGlow(activity: Activity, card: FrameLayout) {
        val glowWidth = dp(activity, 12)

        val left = View(activity).apply {
            isClickable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.argb(58, 105, 214, 210),
                    Color.argb(18, 105, 214, 210),
                    Color.TRANSPARENT
                )
            )
        }
        val right = View(activity).apply {
            isClickable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(18, 105, 214, 210),
                    Color.argb(58, 105, 214, 210)
                )
            )
        }

        card.addView(
            left,
            FrameLayout.LayoutParams(
                glowWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        )
        card.addView(
            right,
            FrameLayout.LayoutParams(
                glowWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        )
    }

    private fun panelBackground(fill: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = 0f
            setStroke(1, stroke)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
