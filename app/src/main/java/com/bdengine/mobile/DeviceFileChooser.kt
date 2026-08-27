package com.bdengine.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast

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
            allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        )
        return true
    }
}

internal object DeviceFilePickerBridge {
    const val EXTRA_ALLOW_MULTIPLE = "bdengine.allow_multiple"

    private var pendingCallback: ValueCallback<Array<Uri>>? = null

    fun open(
        activity: Activity,
        callback: ValueCallback<Array<Uri>>,
        allowMultiple: Boolean
    ) {
        pendingCallback?.onReceiveValue(null)
        pendingCallback = callback

        try {
            activity.startActivity(
                Intent(activity, DeviceFilePickerActivity::class.java).apply {
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
        private val SUPPORTED_EXTENSIONS = setOf("bdengine", "bdstudio")
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
        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)

            // .bdengine and .bdstudio do not have standardized MIME types across
            // Android document providers. Use the system picker broadly, then verify
            // the real display name before returning anything to BDEngine.
            type = "*/*"

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

        val selected = linkedSetOf<Uri>()

        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(selected::add)
            }
        }

        data.data?.let(selected::add)

        val supported = selected.filter(::isSupportedProjectFile)
        val rejectedCount = selected.size - supported.size

        if (rejectedCount > 0) {
            Toast.makeText(
                this,
                "Поддерживаются только .bdengine и .bdstudio",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (supported.isEmpty()) {
            deliverAndFinish(null)
            return
        }

        supported.forEach(::persistReadPermission)
        deliverAndFinish(supported.toTypedArray())
    }

    private fun isSupportedProjectFile(uri: Uri): Boolean {
        val displayName = queryDisplayName(uri)
            ?: uri.lastPathSegment
            ?: return false

        val extension = displayName
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()

        return extension in SUPPORTED_EXTENSIONS
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Throwable) {
            null
        }
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
}
