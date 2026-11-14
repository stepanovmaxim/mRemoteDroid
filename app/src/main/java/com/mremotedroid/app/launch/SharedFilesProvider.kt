package com.mremotedroid.app.launch

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.mremotedroid.app.R
import java.io.File
import java.io.FileNotFoundException

/** Shows the folder shared with RDP sessions in the system Files app and file pickers. */
class SharedFilesProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private fun base(): File =
        SharedFiles.directory(context!!)?.canonicalFile ?: throw FileNotFoundException("storage unavailable")

    private fun fileOf(documentId: String): File {
        val base = base()
        if (documentId == ROOT_DOCUMENT) return base
        val file = File(base, documentId).canonicalFile
        if (!file.path.startsWith(base.path + File.separator)) throw FileNotFoundException(documentId)
        return file
    }

    private fun idOf(file: File): String {
        val base = base()
        val canonical = file.canonicalFile
        return if (canonical == base) ROOT_DOCUMENT else canonical.relativeTo(base).path
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, SharedFiles.ROOT_ID)
                add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT)
                add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            }
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addFile(fileOf(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
        fileOf(parentDocumentId).listFiles()?.forEach { addFile(it) }
    }

    private fun MatrixCursor.addFile(file: File) {
        val isDir = file.isDirectory
        val isRoot = idOf(file) == ROOT_DOCUMENT
        var flags = if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (!isRoot) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idOf(file))
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) SharedFiles.DRIVE_NAME else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    override fun getDocumentType(documentId: String): String = mimeOf(fileOf(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileOf(parentDocumentId)
        return fileOf(documentId).path.startsWith(parent.path + File.separator)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentDocumentId)
        val safeName = File(displayName).name
        var file = File(parent, safeName)
        var n = 1
        while (file.exists()) {
            val stem = safeName.substringBeforeLast('.')
            val ext = safeName.substringAfterLast('.', "")
            file = File(parent, if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext")
            n++
        }
        if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        return idOf(file)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileOf(documentId)
        val target = File(file.parentFile, File(displayName).name)
        if (!file.renameTo(target)) throw FileNotFoundException(documentId)
        return idOf(target)
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_DOCUMENT) throw FileNotFoundException(documentId)
        fileOf(documentId).deleteRecursively()
    }

    private companion object {
        const val ROOT_DOCUMENT = "."
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_ICON, Root.COLUMN_FLAGS
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
        )
    }
}
