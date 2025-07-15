package com.wangyiheng.vcamsx.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

class VideoProvider : ContentProvider(), KoinComponent {
    val infoManager by inject<InfoManager>()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val videoInfo = infoManager.getVideoInfo()
        val url = videoInfo?.videoUrl ?: return null
        val fixedUri = Uri.parse(url)
        return try {
            context?.contentResolver?.openFileDescriptor(fixedUri, mode)
        } catch (e: Exception) {
            Log.e("VideoProvider", "打开文件失败: ${e.message}")
            null
        }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(arrayOf("_id", "display_name", "size", "date_modified", "file"))
        context?.getExternalFilesDir(null)?.absolutePath?.let { path ->
            val file = File(path, "advancedModeMovies/654e1835b70883406c4640c3/caibi_60.mp4")
            cursor.addRow(arrayOf(0, file.name, file.length(), file.lastModified(), file))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
