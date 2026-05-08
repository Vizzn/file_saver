package com.incrediblezayed.file_saver

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


private const val SAVE_FILE = 886325063

class Dialog(private val activity: Activity) : PluginRegistry.ActivityResultListener {
    private var result: MethodChannel.Result? = null
    private var bytes: ByteArray? = null
    private var fileName: String? = null
    private var expectedFileName: String? = null
    private var expectedMimeType: String? = null
    private val TAG = "Dialog Activity"

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != SAVE_FILE) {
            return false
        }

        if (resultCode == Activity.RESULT_OK && data?.data != null) {
            Log.d(TAG, "Starting file operation")
            completeFileOperation(data.data!!)
        } else {
            Log.d(TAG, "Activity result was null")
            result?.success(null)
            result = null
        }

        return true
    }

    fun openFileManager(
        fileName: String?,
        fileExtension: String?,
        bytes: ByteArray?,
        type: String?,
        includeExtension: Boolean?,
        result: MethodChannel.Result
    ) {
        Log.d(TAG, "Opening File Manager")
        val nonNullExtension = fileExtension ?: "";
        var fileNameWithExtension = fileName
        if (includeExtension == true) {
            fileNameWithExtension += if (nonNullExtension.startsWith('.')) {
                nonNullExtension;
            } else {
                ".$nonNullExtension"
            }
        }
        this.result = result
        this.bytes = bytes
        this.fileName = fileName
        this.expectedFileName = fileNameWithExtension
        this.expectedMimeType = type
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(Intent.EXTRA_TITLE, "$fileNameWithExtension")
        intent.putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            Environment.getExternalStorageDirectory().path
        )
        intent.type = type
        activity.startActivityForResult(intent, SAVE_FILE)
    }

    private fun completeFileOperation(uri: Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val finalUri = resolveNameConflict(uri)
                saveFile(finalUri)
                val fileUtils = FileUtils(activity)
                result?.success(fileUtils.getPath(finalUri));
                result = null
                //result?.success(getRealPathFromUri(activity, uri))
            } catch (e: SecurityException) {
                Log.d(TAG, "Security Exception while saving file" + e.message)

                result?.error("Security Exception", e.localizedMessage, e)
                result = null
            } catch (e: Exception) {
                Log.d(TAG, "Exception while saving file" + e.message)
                result?.error("Error", e.localizedMessage, e)
                result = null
            }
        }
    }

    private fun saveFile(uri: Uri) {
        try {
            Log.d(TAG, "Saving file")

            val opStream = activity.contentResolver.openOutputStream(uri)
            opStream?.write(bytes)

        } catch (e: Exception) {
            Log.d(TAG, "Error while writing file" + e.message)
        }
    }

    // ACTION_CREATE_DOCUMENT auto-resolves conflicts by appending " (N)" AFTER
    // the extension (e.g. "report.xls (1)"), which breaks the file's type.
    // When we detect that pattern, rename the document so the suffix sits
    // BEFORE the extension instead ("report (1).xls") and refresh the stored
    // MIME type — Downloads provider keeps the original octet-stream after
    // rename, so file managers would otherwise still show the file as binary.
    private fun resolveNameConflict(uri: Uri): Uri {
        val expected = expectedFileName ?: return uri
        val actual = queryDisplayName(uri) ?: return uri
        val suffixMatch = Regex("^${Regex.escape(expected)} \\((\\d+)\\)$")
            .matchEntire(actual) ?: return uri

        val dotIdx = expected.lastIndexOf('.')
        val base = if (dotIdx > 0) expected.substring(0, dotIdx) else expected
        val ext = if (dotIdx > 0) expected.substring(dotIdx) else ""

        var counter = suffixMatch.groupValues[1].toIntOrNull() ?: 1
        var attempts = 0
        while (attempts < 10000) {
            val candidate = "$base ($counter)$ext"
            try {
                // renameDocument throws on name conflict; returns null when the
                // rename succeeded in place (URI unchanged), or a new URI when
                // the provider re-issues an identifier.
                val newUri = DocumentsContract.renameDocument(
                    activity.contentResolver, uri, candidate
                )
                val finalUri = newUri ?: uri
                refreshMimeType(finalUri)
                return finalUri
            } catch (e: Exception) {
                Log.d(TAG, "Rename to '$candidate' failed: ${e.message}")
            }
            counter++
            attempts++
        }
        return uri
    }

    private fun refreshMimeType(uri: Uri) {
        val mime = expectedMimeType ?: return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
            }
            activity.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to update MIME type to '$mime': ${e.message}")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to query display name: ${e.message}")
            null
        }
    }
}