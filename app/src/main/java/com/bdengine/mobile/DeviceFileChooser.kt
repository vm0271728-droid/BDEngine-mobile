package com.bdengine.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class BDEngineWebChromeClient(
    private val activity: Activity
) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        val callback = filePathCallback ?: return false

        DeviceFilePickerBridge.open(
            activity = activity,
            callback = callback,
            acceptTypes = fileChooserParams?.acceptTypes.orEmpty(),
            allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        )
        return true
    }
}

internal object DeviceFilePickerBridge {
    const val EXTRA_ACCEPT_TYPES = "bdengine.accept_types"
    const val EXTRA_ALLOW_MULTIPLE = "bdengine.allow_multiple"

    private var pendingCallback: ValueCallback<Array<Uri>>? = null

    fun open(
        activity: Activity,
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
        allowMultiple: Boolean
    ) {
        pendingCallback?.onReceiveValue(null)
        pendingCallback = callback

        try {
            activity.startActivity(
                Intent(activity, DeviceFilePickerActivity::class.java).apply {
                    putExtra(EXTRA_ACCEPT_TYPES, acceptTypes)
                    putExtra(EXTRA_ALLOW_MULTIPLE, allowMultiple)
                }
            )
        } catch (_: Throwable) {
            deliver(null)
        }
    }

    fun deliver(uris: Array<Uri>?) {
        val callback = pendingCallback
        pendingCallback = null
        callback?.onReceiveValue(uris)
    }
}

class DeviceFilePickerActivity : Activity() {

    companion object {
        private const val REQUEST_OPEN_FILE = 7201
    }

    private var pickerStarted = false
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            openSystemPicker()
        } else {
            pickerStarted = savedInstanceState.getBoolean("picker_started", false)
            if (!pickerStarted) openSystemPicker()
        }
    }

    private fun openSystemPicker() {
        val requestedTypes = intent
            .getStringArrayExtra(DeviceFilePickerBridge.EXTRA_ACCEPT_TYPES)
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val mimeTypes = requestedTypes
            .mapNotNull(::normalizeAcceptType)
            .distinct()

        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"

            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }

            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                intent.getBooleanExtra(DeviceFilePickerBridge.EXTRA_ALLOW_MULTIPLE, false)
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            pickerStarted = true
            startActivityForResult(pickerIntent, REQUEST_OPEN_FILE)
        } catch (_: Throwable) {
            deliverAndFinish(null)
        }
    }

    @Deprecated("Deprecated in Android API; kept for the WebView file chooser bridge.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_FILE) return

        if (resultCode != RESULT_OK || data == null) {
            deliverAndFinish(null)
            return
        }

        val result = mutableListOf<Uri>()

        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(result::add)
            }
        }

        data.data?.let { uri ->
            if (result.none { it == uri }) result += uri
        }

        result.forEach(::persistReadPermission)
        deliverAndFinish(result.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
            // Temporary read permission from the picker is enough for the current import.
        }
    }

    private fun deliverAndFinish(uris: Array<Uri>?) {
        if (resultDelivered) return
        resultDelivered = true
        DeviceFilePickerBridge.deliver(uris)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picker_started", pickerStarted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !resultDelivered) {
            resultDelivered = true
            DeviceFilePickerBridge.deliver(null)
        }
        super.onDestroy()
    }

    private fun normalizeAcceptType(raw: String): String? {
        val value = raw.substringBefore(';').trim().lowercase()
        if (value.isBlank()) return null

        if ('/' in value) return value

        return when (value.trimStart('.')) {
            "json", "bdengine", "bde", "bdproject" -> "application/json"
            "zip" -> "application/zip"
            else -> null
        }
    }
}
