package nl.koffietijd.tibberwidget

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Alleen-lezen provider die de gerenderde dashboard-PNG's aan de launcher levert. */
class DashboardImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Alleen lezen toegestaan")
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!NAME_PATTERN.matches(name)) throw FileNotFoundException()
        val file = File(File(context!!.filesDir, DIR), name)
        if (!file.exists()) throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val DIR = "dashboard"
        private val NAME_PATTERN = Regex("dash_-?\\d+_\\d+\\.png")
    }
}
