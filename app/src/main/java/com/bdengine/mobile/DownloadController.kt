package com.bdengine.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class DownloadController(
    private val activity: Activity,
    private val rootLayout: FrameLayout,
    private val webView: WebView
) {

    companion object {
        private const val BRIDGE_NAME = "BDEngineDownloads"
        private const val DOWNLOAD_FOLDER = "BDEngine"
        private const val SAVED_FOLDER = "BDEngine/Saved"
        private const val DISPLAY_DOWNLOAD_PATH = "/storage/emulated/0/Download/BDEngine"
        private const val DISPLAY_SAVED_PATH = "/storage/emulated/0/Download/BDEngine/Saved"
        private const val BANNER_DURATION_MS = 5_000L
        private const val PROJECT_SAVE_ARM_MS = 5_000L
        private const val STORAGE_PERMISSION_REQUEST = 9104
    }

    private val downloadManager =
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val pendingDownloads = mutableMapOf<Long, String>()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridge = DownloadBridge()
    private val strings: AppStrings
        get() = AppLocale.strings(activity)

    private var receiverRegistered = false
    private var activeBanner: View? = null
    private var bannerHideRunnable: Runnable? = null
    private var projectFormatDialog: AlertDialog? = null

    @Volatile
    private var projectSaveArmedUntilElapsed = 0L

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val fileName = pendingDownloads.remove(id) ?: return

            val query = DownloadManager.Query().setFilterById(id)
            try {
                downloadManager.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use

                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1

                    // A successful completion must not replace the five-second
                    // download banner before its timer has finished.
                    if (status != DownloadManager.STATUS_SUCCESSFUL) {
                        showBanner(strings.downloadError, fileName, isError = true)
                    }
                }
            } catch (_: Throwable) {
                showBanner(strings.downloadError, fileName, isError = true)
            }
        }
    }

    fun attach() {
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.isNullOrBlank()) return@setDownloadListener

            val fileName = safeFileName(
                URLUtil.guessFileName(url, contentDisposition, mimeType),
                mimeType
            )
            val projectSave = consumeProjectSaveArm()

            when (Uri.parse(url).scheme?.lowercase()) {
                "http", "https" -> {
                    if (projectSave) {
                        showNetworkProjectFormatChoice(
                            url = url,
                            userAgent = userAgent,
                            mimeType = mimeType,
                            fileName = fileName
                        )
                    } else {
                        enqueueNetworkDownload(
                            url = url,
                            userAgent = userAgent,
                            mimeType = mimeType,
                            fileName = fileName
                        )
                    }
                }

                "blob" -> {
                    if (projectSave) {
                        requestProjectBlobFromPage(url, fileName)
                    } else {
                        requestBlobFromPage(url, fileName)
                    }
                }

                "data" -> {
                    if (projectSave) {
                        showProjectFormatChoice(url, fileName, mimeType)
                    } else {
                        saveDataUrlAsync(url, fileName, mimeType)
                    }
                }

                else -> showBanner(
                    strings.downloadFailed,
                    strings.unsupportedLink,
                    isError = true
                )
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(
                    completionReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                activity.registerReceiver(completionReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Throwable) {
            receiverRegistered = false
        }
    }

    fun installPageBridge() {
        val script = """
            (() => {
                if (window.__bdengineNativeDownloadInstalled) return;
                window.__bdengineNativeDownloadInstalled = true;

                const bridge = window.$BRIDGE_NAME;
                if (!bridge) return;

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
                    const projectSave = consumeProjectSave();

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

        webView.evaluateJavascript(script, null)
    }

    fun destroy() {
        bannerHideRunnable?.let(mainHandler::removeCallbacks)
        activeBanner?.animate()?.cancel()
        activeBanner?.let(rootLayout::removeView)
        activeBanner = null
        projectFormatDialog?.dismiss()
        projectFormatDialog = null
        projectSaveArmedUntilElapsed = 0L

        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(completionReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }

        try {
            webView.removeJavascriptInterface(BRIDGE_NAME)
        } catch (_: Throwable) {
        }

        ioExecutor.shutdownNow()
    }

    private fun armProjectSave() {
        projectSaveArmedUntilElapsed = SystemClock.elapsedRealtime() + PROJECT_SAVE_ARM_MS
    }

    private fun consumeProjectSaveArm(): Boolean {
        val armedUntil = projectSaveArmedUntilElapsed
        projectSaveArmedUntilElapsed = 0L
        return armedUntil >= SystemClock.elapsedRealtime()
    }

    private fun clearProjectSaveArm() {
        projectSaveArmedUntilElapsed = 0L
    }

    private fun enqueueNetworkDownload(
        url: String,
        userAgent: String?,
        mimeType: String?,
        fileName: String
    ) {
        enqueueNetworkDownloadToFolder(
            url = url,
            userAgent = userAgent,
            mimeType = mimeType,
            fileName = fileName,
            relativeFolder = DOWNLOAD_FOLDER,
            projectSave = false
        )
    }

    private fun enqueueProjectNetworkDownload(
        url: String,
        userAgent: String?,
        mimeType: String?,
        fileName: String
    ) {
        enqueueNetworkDownloadToFolder(
            url = url,
            userAgent = userAgent,
            mimeType = mimeType,
            fileName = fileName,
            relativeFolder = SAVED_FOLDER,
            projectSave = true
        )
    }

    private fun enqueueNetworkDownloadToFolder(
        url: String,
        userAgent: String?,
        mimeType: String?,
        fileName: String,
        relativeFolder: String,
        projectSave: Boolean
    ) {
        if (!canWriteLegacyDownloads()) return

        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("BDEngine")
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "$relativeFolder/$fileName"
                )

                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                userAgent?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("User-Agent", it)
                }
            }

            val id = downloadManager.enqueue(request)
            pendingDownloads[id] = fileName
            if (projectSave) showProjectSaveBanner() else showDownloadBanner()
        } catch (_: Throwable) {
            showBanner(
                if (projectSave) strings.saveError else strings.downloadError,
                fileName,
                isError = true
            )
        }
    }

    private fun requestBlobFromPage(url: String, fileName: String) {
        requestBlobFromPage(url, fileName, projectSave = false)
    }

    private fun requestProjectBlobFromPage(url: String, fileName: String) {
        requestBlobFromPage(url, fileName, projectSave = true)
    }

    private fun requestBlobFromPage(url: String, fileName: String, projectSave: Boolean) {
        val quotedUrl = JSONObject.quote(url)
        val quotedName = JSONObject.quote(fileName)
        val saveMethod = if (projectSave) "saveProjectDataUrl" else "saveDataUrl"
        val script = """
            (() => {
                try {
                    fetch($quotedUrl)
                        .then(response => response.blob())
                        .then(blob => {
                            const reader = new FileReader();
                            reader.onloadend = () => window.$BRIDGE_NAME.$saveMethod(
                                String(reader.result || ''),
                                $quotedName,
                                blob.type || ''
                            );
                            reader.onerror = () => window.$BRIDGE_NAME.reportError($quotedName);
                            reader.readAsDataURL(blob);
                        })
                        .catch(() => window.$BRIDGE_NAME.reportError($quotedName));
                } catch (_) {
                    window.$BRIDGE_NAME.reportError($quotedName);
                }
            })();
        """.trimIndent()

        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun saveDataUrlAsync(dataUrl: String, fileName: String, mimeHint: String?) {
        showDownloadBanner()

        ioExecutor.execute {
            try {
                val parsed = decodeDataUrl(dataUrl)
                val mime = mimeHint?.takeIf { it.isNotBlank() }
                    ?: parsed.first.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                val finalName = safeFileName(fileName, mime)
                saveBytes(parsed.second, finalName, mime, DOWNLOAD_FOLDER)
                // Do not replace the active five-second download banner on success.
            } catch (_: Throwable) {
                showBanner(strings.downloadError, fileName, isError = true)
            }
        }
    }

    private fun showNetworkProjectFormatChoice(
        url: String,
        userAgent: String?,
        mimeType: String?,
        fileName: String
    ) {
        showProjectFormatChoice { extension ->
            enqueueProjectNetworkDownload(
                url = url,
                userAgent = userAgent,
                mimeType = mimeType,
                fileName = forceProjectExtension(fileName, extension)
            )
        }
    }

    private fun showProjectFormatChoice(
        dataUrl: String,
        fileName: String,
        mimeHint: String?
    ) {
        clearProjectSaveArm()
        showProjectFormatChoice { extension ->
            saveProjectDataUrlAsync(dataUrl, fileName, mimeHint, extension)
        }
    }

    private fun showProjectFormatChoice(onFormatSelected: (String) -> Unit) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

            projectFormatDialog?.dismiss()

            val formats = arrayOf(".bdengine", ".bdstudio")
            val dialog = AlertDialog.Builder(activity)
                .setTitle(strings.saveAs)
                .setItems(formats) { _, which ->
                    onFormatSelected(if (which == 1) "bdstudio" else "bdengine")
                }
                .setNegativeButton(strings.cancel, null)
                .create()

            projectFormatDialog = dialog
            dialog.setOnDismissListener {
                if (projectFormatDialog === dialog) projectFormatDialog = null
            }
            dialog.show()
        }
    }

    private fun saveProjectDataUrlAsync(
        dataUrl: String,
        fileName: String,
        mimeHint: String?,
        extension: String
    ) {
        showProjectSaveBanner()

        ioExecutor.execute {
            try {
                val parsed = decodeDataUrl(dataUrl)
                val mime = mimeHint?.takeIf { it.isNotBlank() }
                    ?: parsed.first.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                val finalName = forceProjectExtension(
                    safeFileName(fileName, mime),
                    extension
                )
                saveBytes(parsed.second, finalName, mime, SAVED_FOLDER)
            } catch (_: Throwable) {
                showBanner(strings.saveError, fileName, isError = true)
            }
        }
    }

    private fun decodeDataUrl(dataUrl: String): Pair<String, ByteArray> {
        val comma = dataUrl.indexOf(',')
        require(comma > 4) { "Invalid data URL" }

        val metadata = dataUrl.substring(5, comma)
        val payload = dataUrl.substring(comma + 1)
        val mimeType = metadata.substringBefore(';').ifBlank { "application/octet-stream" }
        val isBase64 = metadata.split(';').any { it.equals("base64", ignoreCase = true) }

        val bytes = if (isBase64) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
        }

        return mimeType to bytes
    }

    private fun saveBytes(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        relativeFolder: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$relativeFolder"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create download")

            try {
                resolver.openOutputStream(uri, "w")?.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                } ?: error("Cannot open download")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
            return
        }

        if (!canWriteLegacyDownloads()) error("Storage permission required")

        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = File(downloads, relativeFolder)
        if (!directory.exists() && !directory.mkdirs()) {
            error("Cannot create downloads folder")
        }

        val target = uniqueLegacyFile(directory, fileName)
        FileOutputStream(target).use { stream ->
            stream.write(bytes)
            stream.flush()
        }
    }

    private fun canWriteLegacyDownloads(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        activity.requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            STORAGE_PERMISSION_REQUEST
        )
        showBanner(
            strings.permissionRequired,
            strings.permissionMessage,
            isError = true
        )
        return false
    }

    private fun uniqueLegacyFile(directory: File, requestedName: String): File {
        var candidate = File(directory, requestedName)
        if (!candidate.exists()) return candidate

        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""

        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$extension")
            index++
        }
        return candidate
    }

    private fun forceProjectExtension(fileName: String, extension: String): String {
        val safeExtension = if (extension == "bdstudio") "bdstudio" else "bdengine"
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val safeBase = base.ifBlank { "BDEngine-project" }
        return "$safeBase.$safeExtension"
    }

    private fun safeFileName(name: String?, mimeType: String?): String {
        var result = name.orEmpty()
            .substringAfterLast('/')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(160)

        if (result.isBlank()) result = "BDEngine-export"

        if (!result.contains('.')) {
            val extension = mimeType
                ?.substringBefore(';')
                ?.takeIf { it.isNotBlank() }
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (!extension.isNullOrBlank()) result += ".$extension"
        }

        return result
    }

    private fun showDownloadBanner() {
        showBanner(
            title = strings.downloading,
            subtitle = DISPLAY_DOWNLOAD_PATH,
            isError = false
        )
    }

    private fun showProjectSaveBanner() {
        showBanner(
            title = strings.saving,
            subtitle = DISPLAY_SAVED_PATH,
            isError = false
        )
    }

    private fun showBanner(title: String, subtitle: String, isError: Boolean) {
        activity.runOnUiThread {
            bannerHideRunnable?.let(mainHandler::removeCallbacks)
            bannerHideRunnable = null
            activeBanner?.animate()?.cancel()
            activeBanner?.let(rootLayout::removeView)

            // Same turquoise accent used by the dp value in Settings.
            val accent = Color.rgb(105, 214, 210)

            val banner = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(
                    fillColor = Color.BLACK,
                    radius = 0f,
                    strokeColor = accent,
                    strokeWidth = 1
                )
            }

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(11))
            }

            val titleView = TextView(activity).apply {
                text = title
                textSize = 14.5f
                setTextColor(if (isError) Color.rgb(240, 112, 112) else Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val subtitleView = TextView(activity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.rgb(174, 180, 192))
                setPadding(0, dp(3), 0, 0)
            }

            content.addView(titleView)
            content.addView(subtitleView)

            val contentFrame = FrameLayout(activity)
            contentFrame.addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val innerGlowWidth = dp(14)
            val leftInnerGlow = View(activity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        Color.argb(190, 0, 0, 0),
                        Color.argb(75, 0, 0, 0),
                        Color.TRANSPARENT
                    )
                )
            }
            val rightInnerGlow = View(activity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb(75, 0, 0, 0),
                        Color.argb(190, 0, 0, 0)
                    )
                )
            }

            contentFrame.addView(
                leftInnerGlow,
                FrameLayout.LayoutParams(
                    innerGlowWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.START
                )
            )
            contentFrame.addView(
                rightInnerGlow,
                FrameLayout.LayoutParams(
                    innerGlowWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.END
                )
            )

            val timer = View(activity).apply {
                setBackgroundColor(accent)
                pivotX = 0f
                scaleX = 1f
            }

            banner.addView(contentFrame)
            banner.addView(
                timer,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            )

            val availableWidth = (rootLayout.width - dp(28)).coerceAtLeast(dp(280))
            val bannerWidth = minOf(dp(360), availableWidth)

            rootLayout.addView(
                banner,
                FrameLayout.LayoutParams(
                    bannerWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply {
                    topMargin = dp(12)
                }
            )

            activeBanner = banner
            banner.translationY = -dp(100).toFloat()
            banner.alpha = 0f
            banner.elevation = dp(24).toFloat()
            banner.bringToFront()

            // Start the five-second timer only after the banner is fully visible.
            // This prevents the banner from disappearing before the bar reaches zero.
            banner.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220L)
                .withEndAction {
                    if (activeBanner === banner) {
                        timer.animate()
                            .scaleX(0f)
                            .setDuration(BANNER_DURATION_MS)
                            .setInterpolator(LinearInterpolator())
                            .start()

                        val hideRunnable = Runnable {
                            if (activeBanner !== banner) return@Runnable

                            banner.animate()
                                .translationY((-banner.height - dp(18)).toFloat())
                                .alpha(0f)
                                .setDuration(220L)
                                .withEndAction {
                                    if (activeBanner === banner) {
                                        rootLayout.removeView(banner)
                                        activeBanner = null
                                    }
                                }
                                .start()
                        }

                        bannerHideRunnable = hideRunnable
                        mainHandler.postDelayed(hideRunnable, BANNER_DURATION_MS)
                    }
                }
                .start()
        }
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
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).roundToInt()
    }

    private inner class DownloadBridge {
        @JavascriptInterface
        fun armProjectSave() {
            this@DownloadController.armProjectSave()
        }

        @JavascriptInterface
        fun saveDataUrl(dataUrl: String?, fileName: String?, mimeType: String?) {
            if (dataUrl.isNullOrBlank()) {
                reportError(fileName ?: "BDEngine-export")
                return
            }
            saveDataUrlAsync(
                dataUrl = dataUrl,
                fileName = safeFileName(fileName, mimeType),
                mimeHint = mimeType
            )
        }

        @JavascriptInterface
        fun saveProjectDataUrl(dataUrl: String?, fileName: String?, mimeType: String?) {
            clearProjectSaveArm()
            if (dataUrl.isNullOrBlank()) {
                reportError(fileName ?: "BDEngine-project")
                return
            }
            showProjectFormatChoice(
                dataUrl = dataUrl,
                fileName = safeFileName(fileName, mimeType),
                mimeHint = mimeType
            )
        }

        @JavascriptInterface
        fun reportError(fileName: String?) {
            showBanner(
                strings.downloadError,
                fileName?.takeIf { it.isNotBlank() } ?: "BDEngine-export",
                isError = true
            )
        }
    }
}
